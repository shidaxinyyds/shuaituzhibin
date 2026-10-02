package com.stzb.assistant.service

import android.content.Context
import android.content.res.Resources
import android.graphics.PointF
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 全机型坐标映射中枢（设计画布 + 严格可逆缩放）
 *
 * ## 设计画布
 * 高度恒为 [BASE_HEIGHT] = 720 的**设计画布**，宽度按真实屏幕长宽比等比推导。
 * 保留这套设计空间的理由：全项目所有战术常量（选队标签、ROI、看门狗落点等）
 * 都是按 720 高标定的。若把画布直接换成原生分辨率（如 2712x1220），
 * 那些常量会被静默作废、行为整体错位——那是比"偏几像素"严重得多的事故。
 *
 * ## 本次修复的实质问题
 * 旧实现把 X/Y 缩放都写成 `physicalHeight / 720`，而 `virtualWidth` 又经过
 * 「向上对齐 16 字节」（`(raw + 15) and 15.inv()`）。两者不互逆，于是产生随 X
 * 线性增长的系统偏差：
 *
 * | 分辨率        | 旧实现右边缘 X 误差 |
 * |---------------|--------------------|
 * | 2340 x 1080   | +12 px             |
 * | 3120 x 1440   | +16 px             |
 * | 2772 x 1284   | +24.3 px           |
 *
 * 24px 已足够让点击落到相邻地块上，而这类**系统性**偏差不是靠高斯抖动
 * （±1px）能掩盖的，表现为"左侧还行、越往右越点不准"。
 *
 * 现在 [scaleX]/[scaleY] 与 [virtualWidth]/[virtualHeight] 严格互逆：
 * `toReal(virtualWidth, virtualHeight) == (physicalWidth, physicalHeight)`，
 * 且 [toVirtual] 是它的精确逆运算。这一不变量由 [selfTest] 在运行时校验。
 *
 * ## 坐标系语义（务必分清）
 * - **设计画布坐标**（虚拟坐标）：OCR/模板匹配的输出空间，等于捕获位图的像素空间。
 * - **屏幕坐标**（物理像素）：`dispatchGesture` 与 `MotionEvent.rawX` 的空间。
 * 两者由 [toReal]/[toVirtual] 互相转换。任何"识别到的位置"要点击，都必须经过 [toReal]。
 */
object CoordinateTransformer {

    private const val TAG = "CoordinateTransformer"

    /** 设计画布高度。全项目战术常量都以此为基准，不可随意改动。 */
    const val BASE_HEIGHT = 720f

    @Volatile
    private var appContext: Context? = null

    @Volatile
    var physicalWidth: Float = 1920f
        private set

    @Volatile
    var physicalHeight: Float = 1080f
        private set

    /** 设计画布宽度（= 捕获位图宽度）。 */
    @Volatile
    var virtualWidth: Float = 1280f
        private set

    /** 设计画布高度（= 捕获位图高度）。旋转或分屏后可被刷新，因此不再是 val。 */
    @Volatile
    var virtualHeight: Float = BASE_HEIGHT
        private set

    /** X 轴缩放：屏幕像素 / 设计画布像素。 */
    @Volatile
    var scaleX: Float = 1.5f
        private set

    /** Y 轴缩放：屏幕像素 / 设计画布像素。 */
    @Volatile
    var scaleY: Float = 1.5f
        private set

    /**
     * 兼容旧调用点的 Y 轴缩放别名。
     * 旧代码把 `scaleFactor` 当作"两轴共用的等比系数"，正是偏移的来源；
     * 现在它只是 [scaleY] 的只读别名，新代码请改用 [scaleX]/[scaleY]。
     */
    @Deprecated("请使用 scaleX / scaleY，两轴不再相等")
    val scaleFactor: Float
        get() = scaleY

    /**
     * 捕获画布原点相对屏幕原点的偏移。
     *
     * 对**全屏捕获**这两个值必须保持 0：MediaProjection 镜像与 `dispatchGesture`
     * 都以 display 原点为基准，若在这里再叠加挖孔屏/状态栏高度，反而会引入
     * 恒定偏移。它们只在捕获区域与 display 原点不重合时（分屏、自由窗口）才需要设置。
     */
    @Volatile
    var insetLeft: Float = 0f
        private set

    @Volatile
    var insetTop: Float = 0f
        private set

    init {
        refreshMetrics()
    }

    /**
     * 绑定应用上下文。绑定后 [readPhysicalSize] 能拿到比 `Resources.getSystem()`
     * 更可靠的尺寸（含分屏/多窗口的真实窗口边界），应在 Application 启动时调用。
     */
    fun attach(context: Context) {
        appContext = context.applicationContext
        refreshMetrics()
    }

    /**
     * 重新计算物理尺寸、设计画布尺寸与两轴缩放。
     *
     * 调用时机：Application 启动、开始捕获、以及显示尺寸/旋转变化时。
     */
    fun refreshMetrics() {
        val (rawW, rawH) = readPhysicalSize()

        // 全项目的战术 UI 都假定「横屏、全屏」运行（游戏本身也是横屏），
        // 因此统一把长边当宽。若实际处于竖屏，dispatchGesture 的坐标空间会与
        // 该假设不符——这种情形由 isLandscapeGeometryConsistent() 暴露给上层，
        // 让上层显式拒绝执行，而不是发出一个必然落空的点击。
        physicalWidth = maxOf(rawW, rawH).toFloat()
        physicalHeight = minOf(rawW, rawH).toFloat()

        if (physicalWidth <= 0f || physicalHeight <= 0f) {
            Log.e(TAG, "读取到的屏幕尺寸非法 (${physicalWidth}x${physicalHeight})，保留上一次的有效值。")
            return
        }

        // 设计画布宽度 = 720 高下的等比宽度。
        // 注意：这里刻意**不做 16 字节对齐**——对齐会让画布宽高比与真实屏幕
        // 不再一致，而 X 缩放又是从画布宽度反推的，两者叠加就是历史偏移的根源。
        virtualWidth = maxOf(1f, (BASE_HEIGHT * physicalWidth / physicalHeight).roundToInt().toFloat())
        virtualHeight = BASE_HEIGHT

        scaleX = physicalWidth / virtualWidth
        scaleY = physicalHeight / virtualHeight
    }

    /**
     * 设置捕获画布原点相对屏幕原点的偏移。
     * 全屏捕获时必须传 0（默认值），详见 [insetLeft] 的说明。
     */
    fun updateSafeInsets(left: Float, top: Float) {
        this.insetLeft = left
        this.insetTop = top
    }

    /**
     * 设计画布坐标 -> 屏幕物理像素坐标。
     * 所有"识别到的位置"在点击前都必须经过这里。
     */
    fun toReal(virtualX: Float, virtualY: Float): PointF {
        return PointF(virtualX * scaleX + insetLeft, virtualY * scaleY + insetTop)
    }

    /**
     * 屏幕物理像素坐标 -> 设计画布坐标。
     * 用于把用户触摸的 `rawX/rawY`（屏幕空间）回填为可持久化的目标坐标。
     */
    fun toVirtual(realX: Float, realY: Float): PointF {
        val vX = (realX - insetLeft) / scaleX
        val vY = (realY - insetTop) / scaleY
        return PointF(vX, vY)
    }

    /**
     * 当前屏幕几何是否与"横屏全屏"假设一致。
     *
     * `dispatchGesture` 按当前显示旋转解释坐标：竖屏下把长边当 X 会发出越界点击。
     * 上层应先查这个标志，不一致时拒绝执行并提示，而不是盲点。
     */
    fun isLandscapeGeometryConsistent(): Boolean {
        val (rawW, rawH) = readPhysicalSize()
        return rawW >= rawH
    }

    /**
     * 运行时自检：校验映射可逆、且画布尺寸与屏幕等比。
     *
     * 这是把"文档承诺"变成"运行时可验证不变量"的手段——本项目历史上正因为
     * 缺少这类校验，才让 16 对齐偏差悄悄存在了很久而无人发现。
     *
     * @return 失败原因列表；为空表示通过。
     */
    fun selfTest(): List<String> {
        val problems = mutableListOf<String>()

        if (scaleX <= 0f || scaleY <= 0f) {
            problems += "缩放系数非法: scaleX=$scaleX, scaleY=$scaleY"
            return problems
        }

        // 1) 画布右下角必须精确落在屏幕右下角
        val corner = toReal(virtualWidth, virtualHeight)
        val expectX = physicalWidth + insetLeft
        val expectY = physicalHeight + insetTop
        if (abs(corner.x - expectX) > 0.5f || abs(corner.y - expectY) > 0.5f) {
            problems += "画布右下角映射错误: 得到 (${corner.x}, ${corner.y})，应为 ($expectX, $expectY)"
        }

        // 2) 往返变换必须无损
        val probeX = virtualWidth / 3f
        val probeY = virtualHeight / 3f
        val probe = toReal(probeX, probeY)
        val back = toVirtual(probe.x, probe.y)
        if (abs(back.x - probeX) > 0.01f || abs(back.y - probeY) > 0.01f) {
            problems += "往返变换不可逆: 期望 ($probeX, $probeY)，得到 (${back.x}, ${back.y})"
        }

        // 3) 两轴清晰度应基本一致（差异过大说明画布被非等比拉伸）
        if (abs(scaleX - scaleY) / maxOf(scaleX, scaleY) > 0.02f) {
            problems += "两轴缩放差异过大 (scaleX=$scaleX, scaleY=$scaleY)，画布可能被非等比拉伸"
        }

        return problems
    }

    /**
     * 记录一行可供 logcat 核对的自检日志。
     * 排查"点不准"时先看这一行：它会直接暴露几何是否自洽。
     */
    fun logSelfTest() {
        val problems = selfTest()
        if (problems.isEmpty()) {
            Log.i(
                TAG,
                "坐标自检通过: 屏幕 ${physicalWidth}x${physicalHeight}, " +
                    "画布 ${virtualWidth}x${virtualHeight}, scaleX=$scaleX, scaleY=$scaleY"
            )
        } else {
            Log.e(TAG, "坐标自检失败: " + problems.joinToString("; "))
        }
    }

    /**
     * 读取物理屏幕尺寸（横屏/竖屏的原始值，未做长短边归一）。
     *
     * 优先顺序：绑定上下文后的 `maximumWindowMetrics`（API 30+，含分屏真实边界）
     * -> `Display.getRealMetrics`（旧 API，含状态栏/导航栏的真实尺寸）
     * -> `Resources.getSystem()`（无上下文时的兜底）。
     */
    private fun readPhysicalSize(): Pair<Int, Int> {
        val ctx = appContext
        if (ctx != null) {
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (wm != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        val bounds = wm.maximumWindowMetrics.bounds
                        if (bounds.width() > 0 && bounds.height() > 0) {
                            return Pair(bounds.width(), bounds.height())
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "读取 maximumWindowMetrics 失败，回退旧接口: ${e.message}")
                    }
                }
                try {
                    val dm = DisplayMetrics()
                    @Suppress("DEPRECATION")
                    wm.defaultDisplay.getRealMetrics(dm)
                    if (dm.widthPixels > 0 && dm.heightPixels > 0) {
                        return Pair(dm.widthPixels, dm.heightPixels)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "读取 getRealMetrics 失败，回退系统资源: ${e.message}")
                }
            }
        }
        val dm = Resources.getSystem().displayMetrics
        return Pair(dm.widthPixels, dm.heightPixels)
    }

    /**
     * 屏幕锚点自适应计算工具。
     *
     * 说明：这是给"相对屏幕边缘定位"的 UI 用的辅助 API（例如资源栏、聊天栏）。
     * 它曾经完全无人调用；接入时请优先用它而不是写死像素，
     * 因为写死的中心点/边缘点正是历史上"点在屏幕正中而非目标地块"的成因之一。
     */
    enum class Anchor {
        TOP_LEFT,      // 资源栏、主公头像
        TOP_CENTER,    // 坐标搜索栏、天气
        TOP_RIGHT,     // 地图、提醒、同盟战报
        BOTTOM_LEFT,   // 聊天栏、邮件
        BOTTOM_RIGHT,  // 武将、部队、战法、征兵
        CENTER         // 弹出地块指令菜单（扫荡/出征/屯田）
    }

    /**
     * 根据 UI 锚点计算实际设计画布坐标。
     * @param anchor 锚点类型
     * @param offsetX 相对该锚点的 X 偏移（设计画布单位，720 高基准）
     * @param offsetY 相对该锚点的 Y 偏移
     */
    fun getAnchoredVirtualPoint(anchor: Anchor, offsetX: Float, offsetY: Float): PointF {
        return when (anchor) {
            Anchor.TOP_LEFT -> PointF(offsetX, offsetY)
            Anchor.TOP_CENTER -> PointF((virtualWidth / 2f) + offsetX, offsetY)
            Anchor.TOP_RIGHT -> PointF(virtualWidth - offsetX, offsetY)
            Anchor.BOTTOM_LEFT -> PointF(offsetX, virtualHeight - offsetY)
            Anchor.BOTTOM_RIGHT -> PointF(virtualWidth - offsetX, virtualHeight - offsetY)
            Anchor.CENTER -> PointF((virtualWidth / 2f) + offsetX, (virtualHeight / 2f) + offsetY)
        }
    }
}
