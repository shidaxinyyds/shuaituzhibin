package com.stzb.assistant.runtime

import android.content.Context
import android.util.Log
import com.stzb.assistant.ai.vision.PerceptionTier
import com.stzb.assistant.ai.vision.VisionPolicy

/**
 * 视觉能力统一运行时 (VisionRuntime)
 *
 * ## 为什么要有这一层
 * 原先 `YoloDetector` 在工程里 **没有任何调用方**（`YoloNative` / `YoloDetector`
 * 只在自己的包内出现），也就是说"真实 ncnn 目标检测"写着写着就成了摆设。
 * 但直接到处 `new YoloDetector(context)` 会带来两个真实隐患：
 *
 *   1. **构造即加载**：`YoloDetector.init { initModel() }` 会在构造时就加载
 *      ncnn 权重（12~22MB 级）。若每帧/每次巡检都 new 一个，等于持续吃内存。
 *   2. **没有资源准入**：没有任何水位判断，低内存时照样加载，
 *      结果往往是**本进程被 LMK 杀掉**——正在执行的压秒/夜战防护随之消失。
 *
 * 这一层把它们收敛成：
 *   * **进程内单例**（只加载一次，可复用）；
 *   * **加载前过 [ResourceGuard] 准入**，没有余量就明确拒绝；
 *   * 注册释放钩子，系统回收内存时主动卸载。
 *
 * ## 使用原则（重要）
 * 视觉检测在本项目里**只做增强与可观测性，绝不能削弱既有安全判定**。
 * 例如夜战哨兵：OpenCV 雷达判定为威胁时，**即使 YOLO 没检出任何目标也绝不下调等级**——
 * 漏检一次就是真实事故。YOLO 只用于"双通道一致"时提高置信、以及记录一致性统计。
 */
object VisionRuntime {

    private const val TAG = "VisionRuntime"

    /** 加载 ncnn YOLO 的预计内存开销（MB），按 yolov8s INT8 上限取，留冗余。 */
    private const val YOLO_COST_MB = 24

    @Volatile
    private var yoloRef: com.stzb.assistant.ai.vision.YoloDetector? = null

    /** 最近一次"拿不到检测器"的原因，供 UI/日志如实展示。 */
    @Volatile
    private var unavailableReason: String = "尚未尝试初始化视觉能力。"

    /**
     * 当前激活游戏的感知层级策略（由 [com.stzb.assistant.knowledge.KnowledgeBaseManager]
     * 在切换/热更知识库时同步）。**默认 [VisionPolicy.SLG_DEFAULT]——不含 DETECTOR。**
     *
     * 这是"确定性优先"的执行闸门：SLG/MMO 盘下，即便 assets 里塞了 YOLO 权重也不会被加载。
     * 只有动作类（知识包显式开 DETECTOR）才会走到下面的真推理路径。
     */
    @Volatile
    var policy: VisionPolicy = VisionPolicy.SLG_DEFAULT

    private var releaseHookRegistered = false

    /**
     * 获取 YOLO 检测器；拿不到时返回 null 并把原因写进 [unavailableReason]。
     *
     * 刻意返回可空：调用方必须显式处理"没有视觉能力"的情况，
     * 而不是拿到一个对象就以为一定能用。
     */
    @Synchronized
    fun yolo(context: Context): com.stzb.assistant.ai.vision.YoloDetector? {
        // 闸门 0：本游戏是否启用「检测器」层级（确定性优先；SLG/MMO 默认不启用）。
        // 放在最前面，且顺带释放可能因切换游戏而残留的旧检测器，避免误用其结论。
        if (!policy.allows(PerceptionTier.DETECTOR)) {
            yoloRef = null
            unavailableReason = "本游戏视觉策略未启用检测器（确定性优先阶梯），" +
                "目标检测关闭，视觉以确定性通道（模板/颜色/OCR）为准。"
            Log.i(TAG, unavailableReason)
            return null
        }

        yoloRef?.let { return it }

        if (!releaseHookRegistered) {
            ResourceGuard.registerReleaseHook { release() }
            releaseHookRegistered = true
        }

        // 闸门 1：权重资产是否存在
        if (!com.stzb.assistant.ai.assets.ModelAssetManager.isYoloReady(context)) {
            // 期望文件名按**当前激活游戏**报。原来这里写死 "yolov8*_stzb"，
            // 换一款游戏后照这句话去放文件，放的那个名字本游戏根本读不到——
            // 提示把人引向一个永远不会生效的路径，比不提示更糟。
            val sampleName = com.stzb.assistant.ai.assets.ModelAssetManager
                .modelCandidates("yolov8s.param").first()
            unavailableReason = "未打包本游戏的 YOLO 权重（assets/models/$sampleName 等成对 .param+.bin），" +
                "目标检测能力不可用，视觉相关增强将跳过。"
            Log.i(TAG, unavailableReason)
            return null
        }

        // 闸门 2：系统资源余量
        if (!ResourceGuard.canAfford(YOLO_COST_MB)) {
            unavailableReason = "系统可用内存不足（加载 YOLO 需约 ${YOLO_COST_MB}MB）：" +
                "${ResourceGuard.describe()}。为保住常驻进程，本次不加载视觉模型。"
            Log.w(TAG, unavailableReason)
            return null
        }

        return try {
            val det = com.stzb.assistant.ai.vision.YoloDetector(context)
            yoloRef = det
            unavailableReason = if (det.isNativeReady) {
                "YOLO 原生通道已就绪（真推理）。"
            } else {
                "YOLO 权重存在但原生推理不可用（本包未链接 ncnn），只走几何色度回退。"
            }
            Log.i(TAG, unavailableReason)
            det
        } catch (e: Exception) {
            yoloRef = null
            unavailableReason = "初始化 YOLO 检测器异常: ${e.message}"
            Log.w(TAG, unavailableReason)
            null
        }
    }

    /**
     * 原生视觉通道是否可用（需要：有权重 + 资源允许 + 原生推理真的起来）。
     *
     * 只有返回 true 时，调用方才可以把检测结果当作**证据**使用；
     * 否则检测结果仅供参考，不得用于下调任何安全判定。
     */
    fun isNativeVisionReady(): Boolean = yoloRef?.isNativeReady == true

    /** 释放视觉模型（系统低内存时由 ResourceGuard 回调）。 */
    fun release() {
        synchronized(this) {
            yoloRef = null
            Log.i(TAG, "已释放 YOLO 检测器（下次需要时会重新按资源水位判断能否加载）。")
        }
    }

    /** 可直接打进日志的一行式说明。 */
    fun describe(): String = buildString {
        append("视觉运行时: 检测器=").append(if (yoloRef != null) "已加载" else "未加载")
        append(" · 原生推理=").append(isNativeVisionReady())
        append(" · ").append(unavailableReason)
    }
}
