package com.stzb.assistant.overlay

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PointF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.TimePicker
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.stzb.assistant.ai.microbrain.isConcreteTargetName
import com.stzb.assistant.R
import com.stzb.assistant.ocr.StzbUiMatcher
import com.stzb.assistant.service.CoordinateTransformer
import com.stzb.assistant.service.EngineBridge
import com.stzb.assistant.service.UiAnchors
import android.widget.CheckBox
import com.stzb.assistant.ocr.TileStatusDetector
import com.stzb.assistant.tactics.DailyLogisticsFlow
import com.stzb.assistant.tactics.AccurateFarmingFlow
import com.stzb.assistant.tactics.ImmunityBreakFlow
import com.stzb.assistant.tactics.RaidDefenseFlow
import com.stzb.assistant.tactics.RoadPavingFlow
import com.stzb.assistant.tactics.SiegeSyncFlow
import com.stzb.assistant.tactics.TacticalPipeline
import com.stzb.assistant.tactics.TacticalState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 游戏内常驻悬浮 UI 总控管理器 (OverlayWindowManager)
 *
 *   1. 【极简药丸胶囊】：吸附边缘、任意拖拽、实时状态色；
 *   2. 【战术控制面板】：固定尺寸内容区，切 Tab 不再突变大小；军师前置于日志；停止拆分为“当前/全部”；
 *   3. 【准星多点取点】：逐一点选地块并落持久标记，撤销/清空/确认，确认后才回填并返回，坐标统一为点击引擎所用的 720p 归一化虚拟坐标。
 */
class OverlayWindowManager(private val context: Context) : TacticalState.TacticalEventListener {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pipeline = TacticalPipeline.getInstance(context)

    /**
     * 悬浮窗内部协程作用域。
     *
     * ## 为什么补这一行
     * 本文件里有 3 处 `scope.launch { ... }`（免战检测、军令执行、书签测试），
     * 但 `scope` **此前从未被声明过** —— 属于 `Unresolved reference: scope`，
     * 会让整个模块编译失败。只是它藏在一个 2400 行的文件里，
     * 而工程当时没有可用的编译环境，所以一直没暴露。
     *
     * 用 `SupervisorJob` 是为了让其中一个按钮的协程失败时，
     * 不会连带取消其它仍在运行的任务（例如长时压秒等待）。
     */
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var capsuleView: View? = null
    private var dashboardView: View? = null
    private var pickerView: View? = null

    /**
     * 面板内容"刚好容纳"时的自然高度（首次以 WRAP_CONTENT 展开时测得）。
     * 拖角缩放把高度下限锁在这里：内容区不可滚动，缩到比这更小会把底部按钮和
     * 缩放角标一起裁出屏幕，用户就再也抓不到角标放大回去了。
     */
    private var dashboardNaturalHeightPx = 0

    private lateinit var capsuleParams: WindowManager.LayoutParams
    private lateinit var dashboardParams: WindowManager.LayoutParams
    private lateinit var pickerParams: WindowManager.LayoutParams

    // 各战术已确认的目标地块（720p 归一化虚拟坐标序列）
    private val pickedPavingPoints = mutableListOf<PointF>()
    private val pickedImmunityPoints = mutableListOf<PointF>()
    private val pickedSiegePoints = mutableListOf<PointF>()
    private var pickedFarmingPoint: PointF? = null
    private var pickedFarmingWorld: Pair<Int, Int>? = null
    private var pickedGarrisonPoint: PointF? = null
    private var pickedGarrisonWorld: Pair<Int, Int>? = null
    private var pickedLevelingPoint: PointF? = null
    private var pickedLevelingWorld: Pair<Int, Int>? = null

    /**
     * 与 [pickedPavingPoints] 一一对应的**世界坐标**（大地图格坐标）。
     *
     * 只有在完成过地图投影标定时才会有值。有值时铺路流程会先把镜头对准每一格再点，
     * 目标不会因为镜头移动而失效——这正是"取点得到的是一次性屏幕点"这一缺陷的解。
     */
    private val pickedPavingWorld = mutableListOf<Pair<Int, Int>>()

    /** 卡免目标的世界坐标（标定后才会有值）。 */
    private var pickedImmunityWorld: Pair<Int, Int>? = null
    /** 卡免/破免官方书签名称（优先于坐标实现 0 漂移瞬间对准）。 */
    private var pickedImmunityBookmark: String? = null
    /** 最近一次 OCR 识别到的免战解除毫秒时间戳。 */
    private var lastDetectedImmunityUnlockMs: Long = 0L

    /** 攻城目标的世界坐标（标定后才会有值）。 */
    private var pickedSiegeWorld: Pair<Int, Int>? = null

    // 端侧认知微脑（军令/战报的规则解析）
    private val edgeSlmEngine = com.stzb.assistant.ai.microbrain.EdgeSlmEngine(context)
    private var lastExtractedOrder: com.stzb.assistant.ai.microbrain.TacticalOrder? = null

    /**
     * 最近一次「识别军令」读到的**原文**。
     *
     * 执行军令时需要重新走一遍"解析 → 安全校验"，而校验必须基于原文
     * （只拿 TacticalOrder 会丢掉原文里的坐标歧义与语境，也就无从判断该不该拒绝）。
     */
    private var lastExtractedDecreeText: String = ""

    /**
     * 军令安全闸门（防幻觉 + 效用评估），本轮**重新接线**。
     *
     * 此前它被摘出调用链，因为它会把军令的世界坐标丢弃、改用屏幕正中当点击目标。
     * 现在它已被修成"解析不出真实坐标就明确拒绝"的形态（见 DualTrackSafetyGate 顶部说明），
     * 因此"识别军令 → 执行军令"这条链路终于可以真实可用，而不是只解析不下发。
     */
    private val safetyGate by lazy { com.stzb.assistant.ai.decision.DualTrackSafetyGate(context) }

    // 取点回调路由与临时取点态
    private var currentPickTarget: PickTarget? = null
    private val tempPickPoints = mutableListOf<PointF>()
    private val tempPickMarkers = mutableListOf<View>()

    // 地图标定流程状态（两点标定需要跨两次取点保留第一点）
    private var calibTwoPointMode = false
    private var calibFirstPoint: PointF? = null
    private var calibFirstWorld: Pair<Int, Int>? = null

    // 引导式标定流程状态
    // 部队标签：1..5 表示正在标定该槽位，0 表示未在标定
    private var troopTabCalibSlot = 0
    // 识别区域：待标定的区域队列索引，-1 表示未在标定
    private var rectCalibIndex = -1
    // 当前区域已点的第一个角（左上角），null 表示还在等第一个点
    private var rectCalibCorner: PointF? = null

    /**
     * 正在登记模板的按键类型；null 表示当前不在按键模板标定流程中。
     *
     * 这是 OCR 不可用时**唯一**能建立按键模板的途径：让用户用十字准星指一下按键中心，
     * 我们把那一小片裁下来存成模板。没有它，"不依赖 OCR 也能点准"就无从起步。
     */
    private var templateCalibType: com.stzb.assistant.ocr.StzbUiMatcher.ButtonType? = null

    /** 引导式识别区域标定的顺序。 */
    private val rectCalibOrder = listOf(
        UiAnchors.RectKey.TROOP_CARD,
        UiAnchors.RectKey.MARCH_TIME,
        // 加上守军名单区：它直接决定"打这块地会不会白送兵"的评估准不准，
        // 而默认值只是一个宽松的居中区域，值得单独标定一次。
        UiAnchors.RectKey.DEFENDER_PANEL
    )
    enum class PickTarget {
        PAVING, IMMUNITY, SIEGE, FARMING, GARRISON, LEVELING,
        /** 标定用：点选一块地，然后把镜头对准点的世界坐标写下来（单点或两点标定）。 */
        CALIBRATION,
        /** 标定用：点选一个"收起浮层时最安全的地图空白点"。 */
        CALIBRATION_BLANK,
        /** 标定用：逐个记录出征面板的部队标签位置（写入 UiAnchors 的点锚点）。 */
        ANCHOR_POINT,
        /** 标定用：为一个识别区域先后点左上角与右下角（写入 UiAnchors 的矩形锚点）。 */
        ANCHOR_RECT,
        /**
         * 标定用：为一个语义按键登记"它长什么样"的模板。
         *
         * 这是 **OCR 不可用时唯一的按键定位引导方式**：默认构建里 native OCR 是空桩，
         * 而模板匹配不需要文字识别。没有这个入口，模板库永远是空的，
         * 那条"不依赖 OCR 也能点准"的通道就只是通了但没货。
         */
        BUTTON_TEMPLATE
    }

    private val profileChangeListener = object : com.stzb.assistant.knowledge.KnowledgeBaseManager.ProfileChangeListener {
        override fun onProfileChanged(newProfile: com.stzb.assistant.knowledge.GameProfile) {
            mainHandler.post {
                dashboardView?.findViewById<TextView>(R.id.tvDashboardTitle)?.text =
                    "${newProfile.gameName} · 战术总控"
            }
        }
    }

    private val scheduleChangeListener = object : com.stzb.assistant.tactics.ScheduledTaskManager.TaskChangeListener {
        override fun onTasksUpdated(taskList: List<com.stzb.assistant.tactics.ScheduledTaskManager.ScheduledTask>) {
            mainHandler.post { refreshScheduleDisplay() }
        }
    }

    /**
     * 无人托管的状态回调。
     *
     * ⚠️ 必须声明在下面的 `init {}` **之前**：Kotlin 按文本顺序初始化属性并执行 init 块，
     * 而 init 里会把它注册给 AutoPilot。若声明在 init 之后，注册到的会是尚未初始化的 null。
     */
    private val autoPilotListener = object : com.stzb.assistant.tactics.AutoPilot.Listener {
        override fun onPilotChanged(running: Boolean) {
            mainHandler.post {
                dashboardView?.findViewById<Button>(R.id.btnAdvisorAutoSense)?.text =
                    if (running) "无人托管：开" else "无人托管：关"
            }
        }

        override fun onPilotTick(stateDesc: String, action: String) {
            mainHandler.post {
                val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                // 只更新 metrics，不覆盖 tvAdvisorStream，保证军令/战报文本持久留存
                dashboardView?.findViewById<TextView>(R.id.tvAdvisorMetrics)?.text =
                    "[$time] 感知: $stateDesc | 托管: $action"
            }
        }
    }

    /**
     * 军令安全闸门的结论回调：把"通过 / 拦截 / 已下发"如实写进游戏内日志流。
     *
     * 这里刻意用 [com.stzb.assistant.tactics.TacticalState.TaskType.TACTICAL_HUD] 作为日志归类——
     * 该枚举此前声明了却**没有任何地方引用**（等于一段永不生效的分支），
     * 现在它承载"端侧 RAG 战术智脑"这一路的输出，真正参与运行。
     */
    private val safetyGateListener = object : com.stzb.assistant.ai.decision.DualTrackSafetyGate.SafetyGateCallback {
        override fun onOrderVerified(
            order: com.stzb.assistant.ai.microbrain.TacticalOrder,
            utilityScore: Float
        ) {
            emitAdvisorLog("INFO", "🛡️ 军令【${order.targetName}】通过安全门禁，效用得分 ${"%.1f".format(utilityScore)}。")
        }

        override fun onOrderRejected(
            order: com.stzb.assistant.ai.microbrain.TacticalOrder,
            reason: String
        ) {
            emitAdvisorLog("WARN", "⛔ 军令【${order.targetName}】被拦截：$reason")
        }

        override fun onExecutionDispatched(taskType: com.stzb.assistant.tactics.TacticalState.TaskType, summary: String) {
            emitAdvisorLog("TACTIC", "🚀 $summary")
        }
    }

    /** 把军师/闸门结论写入统一的日志流（悬浮窗「日志」页签可见）。 */
    private fun emitAdvisorLog(level: String, message: String) {
        pipeline.onLogEmitted(
            com.stzb.assistant.tactics.TacticalState.TacticalLog(
                taskType = com.stzb.assistant.tactics.TacticalState.TaskType.TACTICAL_HUD,
                level = level,
                message = message
            )
        )
    }

    init {
        pipeline.registerListener(this)
        com.stzb.assistant.knowledge.KnowledgeBaseManager.registerListener(profileChangeListener)
        com.stzb.assistant.tactics.ScheduledTaskManager.registerListener(scheduleChangeListener)
        com.stzb.assistant.tactics.ScheduledTaskManager.init(context, pipeline)
        com.stzb.assistant.tactics.AutoPilot.registerListener(autoPilotListener)
        safetyGate.setCallback(safetyGateListener)

        initLayoutParams()
        createCapsuleView()
        createDashboardView()
        createPickerView()
    }

    private fun initLayoutParams() {
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        capsuleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(20)
            y = dp(120)
        }

        // 面板默认宽度 = 屏宽 × DASHBOARD_WIDTH_RATIO（0.53，按需求整体收窄三分之一）。
        // 但这只是**可被用户拖角改写的初始值**：一旦缩放/拖动过，尺寸与位置从
        // SharedPreferences 读回来覆盖它（见 setupDashboardResize / persistDashboard*）。
        // gravity 用 TOP|START 而非 CENTER_HORIZONTAL：右下角缩放要跟手 1:1，
        // 居中重力下改宽度会让左右两边同时外扩、角标只走一半距离，手感是错的。
        val screenWidth = context.resources.displayMetrics.widthPixels
        val geom = context.getSharedPreferences(PREFS_OVERLAY, Context.MODE_PRIVATE)
        val defaultWidth = (screenWidth * DASHBOARD_WIDTH_RATIO).toInt()
        val savedW = geom.getInt(KEY_DASH_W, 0)
        val savedH = geom.getInt(KEY_DASH_H, 0)
        val savedX = geom.getInt(KEY_DASH_X, -1)
        val savedY = geom.getInt(KEY_DASH_Y, -1)
        val panelWidth = if (savedW > 0) savedW.coerceAtMost(screenWidth) else defaultWidth

        dashboardParams = WindowManager.LayoutParams(
            panelWidth,
            if (savedH > 0) savedH else WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 首次（没拖动过）水平居中；之后按用户放的位置还原。
            x = if (savedX >= 0) savedX else (screenWidth - panelWidth) / 2
            y = if (savedY >= 0) savedY else dp(35) // 默认贴顶，避开游戏底部主力队伍栏与操作菜单
        }

        pickerParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
    }

    // ==========================================
    // 1. 迷你药丸胶囊 (Capsule)
    // ==========================================

    private fun createCapsuleView() {
        val inflater = LayoutInflater.from(context)
        capsuleView = inflater.inflate(R.layout.view_floating_capsule, null)

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        capsuleView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = capsuleParams.x
                    initialY = capsuleParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) isDragging = true
                    capsuleParams.x = initialX + dx
                    capsuleParams.y = initialY + dy
                    windowManager.updateViewLayout(capsuleView, capsuleParams)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) snapCapsuleToEdge() else showDashboard()
                    true
                }
                else -> false
            }
        }

        try {
            windowManager.addView(capsuleView, capsuleParams)
        } catch (e: Exception) {
            Log.e(TAG, "创建常驻胶囊悬浮窗失败: ${e.message}")
        }
    }

    private fun snapCapsuleToEdge() {
        val screenWidth = context.resources.displayMetrics.widthPixels
        val middle = screenWidth / 2
        // 边距与宽度兜底都改用 dp：原来是裸像素（15 / 150），
        // 在非 3.0 密度的机型上吸附位置会漂移。
        val margin = dp(15)
        val capsuleWidth = capsuleView?.width?.takeIf { it > 0 } ?: dp(130)
        capsuleParams.x = if (capsuleParams.x < middle) margin else screenWidth - capsuleWidth - margin
        windowManager.updateViewLayout(capsuleView, capsuleParams)
    }

    // ==========================================
    // 2. 展开式战术控制面板 (Dashboard)
    // ==========================================

    private fun createDashboardView() {
        val inflater = LayoutInflater.from(context)
        dashboardView = inflater.inflate(R.layout.view_floating_dashboard, null)

        dashboardView?.findViewById<TextView>(R.id.tvDashboardTitle)?.text =
            "${com.stzb.assistant.knowledge.KnowledgeBaseManager.activeProfile.gameName} · 战术总控"
        dashboardView?.findViewById<TextView>(R.id.tvCloseDashboard)?.setOnClickListener { hideDashboard() }

        setupDashboardDrag()
        setupDashboardResize()

        val tabPaving = dashboardView?.findViewById<Button>(R.id.tabPaving)
        val tabImmunity = dashboardView?.findViewById<Button>(R.id.tabImmunity)
        val tabSiege = dashboardView?.findViewById<Button>(R.id.tabSiege)
        val tabPatrol = dashboardView?.findViewById<Button>(R.id.tabPatrol)
        val tabAdvisor = dashboardView?.findViewById<Button>(R.id.tabAdvisor)
        val tabSchedule = dashboardView?.findViewById<Button>(R.id.tabSchedule)
        val tabLogistics = dashboardView?.findViewById<Button>(R.id.tabLogistics)
        val tabFarming = dashboardView?.findViewById<Button>(R.id.tabFarming)
        val tabLogs = dashboardView?.findViewById<Button>(R.id.tabLogs)
        val tabCalibrate = dashboardView?.findViewById<Button>(R.id.tabCalibrate)
        val tabGarrison = dashboardView?.findViewById<Button>(R.id.tabGarrison)
        val tabLeveling = dashboardView?.findViewById<Button>(R.id.tabLeveling)

        val panelPaving = dashboardView?.findViewById<LinearLayout>(R.id.panelPaving)
        val panelImmunity = dashboardView?.findViewById<LinearLayout>(R.id.panelImmunity)
        val panelSiege = dashboardView?.findViewById<LinearLayout>(R.id.panelSiege)
        val panelPatrol = dashboardView?.findViewById<LinearLayout>(R.id.panelPatrol)
        val panelAdvisor = dashboardView?.findViewById<LinearLayout>(R.id.panelAdvisor)
        val panelSchedule = dashboardView?.findViewById<LinearLayout>(R.id.panelSchedule)
        val panelLogistics = dashboardView?.findViewById<LinearLayout>(R.id.panelLogistics)
        val panelFarming = dashboardView?.findViewById<LinearLayout>(R.id.panelFarming)
        val panelLogs = dashboardView?.findViewById<LinearLayout>(R.id.panelLogs)
        val panelCalibrate = dashboardView?.findViewById<LinearLayout>(R.id.panelCalibrate)
        val panelGarrison = dashboardView?.findViewById<LinearLayout>(R.id.panelGarrison)
        val panelLeveling = dashboardView?.findViewById<LinearLayout>(R.id.panelLeveling)

        val tabs = listOf(
            tabPaving, tabImmunity, tabSiege, tabPatrol, tabAdvisor,
            tabSchedule, tabLogistics, tabFarming, tabLogs, tabCalibrate,
            tabGarrison, tabLeveling
        )
        val panels = listOf(
            panelPaving, panelImmunity, panelSiege, panelPatrol, panelAdvisor,
            panelSchedule, panelLogistics, panelFarming, panelLogs, panelCalibrate,
            panelGarrison, panelLeveling
        )

        fun switchTab(index: Int) {
            panels.forEachIndexed { i, p -> p?.visibility = if (i == index) View.VISIBLE else View.GONE }
            tabs.forEachIndexed { i, t ->
                t?.setBackgroundResource(if (i == index) R.drawable.bg_tab_active else R.drawable.bg_tab_inactive)
                t?.setTextColor(if (i == index) Color.WHITE else 0xFFB0BEC5.toInt())
            }
            when (index) {
                5 -> refreshScheduleDisplay()
                9 -> refreshCalibrateDisplay()
            }
        }

        tabPaving?.setOnClickListener { switchTab(0) }
        tabImmunity?.setOnClickListener { switchTab(1) }
        tabSiege?.setOnClickListener { switchTab(2) }
        tabPatrol?.setOnClickListener { switchTab(3) }
        tabAdvisor?.setOnClickListener { switchTab(4) }
        tabSchedule?.setOnClickListener { switchTab(5) }
        tabLogistics?.setOnClickListener { switchTab(6) }
        tabFarming?.setOnClickListener { switchTab(7) }
        tabLogs?.setOnClickListener { switchTab(8) }
        tabCalibrate?.setOnClickListener { switchTab(9) }
        tabGarrison?.setOnClickListener { switchTab(10) }
        tabLeveling?.setOnClickListener { switchTab(11) }

        switchTab(0)

        setupDashboardActions()
    }

    private fun setupDashboardActions() {
        val root = dashboardView ?: return

        val ensureEngineReady = {
            when {
                !EngineBridge.isCaptureReady -> {
                    Toast.makeText(context, "请先在主程序开启屏幕捕获", Toast.LENGTH_SHORT).show()
                    false
                }
                !EngineBridge.isTouchReady -> {
                    Toast.makeText(context, "请先开启无障碍触控通道", Toast.LENGTH_SHORT).show()
                    false
                }
                else -> true
            }
        }

        // 1. 铺路（多点）
        root.findViewById<Button>(R.id.btnPickPavingTile)?.setOnClickListener {
            startCrosshairPicker(PickTarget.PAVING)
        }
        root.findViewById<Button>(R.id.btnExecPaving)?.setOnClickListener {
            if (!ensureLicense()) return@setOnClickListener
            if (!ensureEngineReady()) return@setOnClickListener
            // 绝不再回退到「屏幕中心」：那等于把"用户没选目标"变成"盲点两下屏幕中间"，
            // 既必然无效，又可能在游戏里造成误操作（历史上的屏幕中心恰好压在城池/资源栏上）。
            // 没有目标就明确拒绝执行，并告诉用户该做什么。
            if (pickedPavingPoints.isEmpty()) {
                Toast.makeText(context, "请先点选目标地块（可多点），再开始铺路", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            pipeline.startRoadPaving(
                RoadPavingFlow.PavingConfig(
                    targetTileList = pickedPavingPoints.toList(),
                    // 槽位与士气门槛取自当前知识库，而不是写死的 1/2/3 与 100：
                    // 这样切换游戏或云端热更知识库后，行为会真的跟着变
                    // （此前 TacticalDefaults 全部是死数据，改了没有任何效果）。
                    candidateTroopSlots = tacticalDefaults().pavingDefaultSlots,
                    minMoraleThreshold = activeRules().minMoraleForPaving,
                    // 已标定地图比例时，流程会按世界坐标逐格对准镜头，目标不再因镜头移动失效
                    worldTargetList = pickedPavingWorld.toList()
                )
            )
            hideDashboard()
        }

        // 2. 卡免与一键压秒破免
        val tvImmunityCoord = root.findViewById<TextView>(R.id.tvImmunityCoord)
        val tvImmunityOcrResult = root.findViewById<TextView>(R.id.tvImmunityOcrResult)

        root.findViewById<Button>(R.id.btnPickImmunityTile)?.setOnClickListener {
            startCrosshairPicker(PickTarget.IMMUNITY)
        }

        root.findViewById<Button>(R.id.btnInputImmunityBookmark)?.setOnClickListener {
            val input = EditText(context).apply {
                hint = "输入游戏内书签名称(如: 虎牢关/要塞1)"
                setText(pickedImmunityBookmark ?: "")
            }
            val dialog = AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("设置免战目标书签")
                .setMessage("填入官方书签名称后，破免出征将自动实现 0 漂移对准地块。")
                .setView(input)
                .setPositiveButton("确定") { _, _ ->
                    val text = input.text.toString().trim()
                    if (text.isNotEmpty()) {
                        pickedImmunityBookmark = text
                        tvImmunityCoord?.text = "卡免目标: 🔖书签 [$text] (0 漂移对准)"
                        Toast.makeText(context, "已锁定官方书签: [$text]", Toast.LENGTH_SHORT).show()
                    } else {
                        pickedImmunityBookmark = null
                        tvImmunityCoord?.text = "卡免地块：尚未选择 (可点选或填入官方书签)"
                    }
                }
                .setNegativeButton("取消", null)
                .create()
            applyOverlayWindowType(dialog)
            dialog.show()
        }

        root.findViewById<Button>(R.id.btnDetectTileImmunityOcr)?.setOnClickListener {
            if (!ensureLicense()) return@setOnClickListener
            if (!ensureEngineReady()) return@setOnClickListener
            tvImmunityOcrResult?.text = "正在检测免战倒计时..."
            scope.launch {
                val bookmark = pickedImmunityBookmark
                if (!bookmark.isNullOrBlank()) {
                    when (val nav = com.stzb.assistant.service.MapNavigator.jumpByBookmark(bookmark)) {
                        is com.stzb.assistant.service.MapNavigator.Result.Reached -> {
                            Log.i("OverlayWindowManager", "已按书签 [$bookmark] 居中地块")
                        }
                        else -> Log.w("OverlayWindowManager", "书签跳转未完成，继续读取画面")
                    }
                }
                val status = EngineBridge.detectTileImmunity()
                withContext(Dispatchers.Main) {
                    if (status.isImmune && status.remainingSeconds > 0) {
                        lastDetectedImmunityUnlockMs = status.unlockTimestampMs
                        val formattedTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(status.unlockTimestampMs))
                        val hitTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(status.unlockTimestampMs + 1000L))
                        tvImmunityOcrResult?.text = "🛡️ 免战倒计时: ${status.remainingSeconds}秒\n" +
                            "⏰ 解锁时刻: $formattedTime · 🎯 建议触敌: $hitTime (+1000ms)"
                        Toast.makeText(context, "成功识别免战倒计时: ${status.remainingSeconds}秒", Toast.LENGTH_SHORT).show()
                    } else {
                        lastDetectedImmunityUnlockMs = 0L
                        tvImmunityOcrResult?.text = "⚠️ 未检测到有效免战罩或已过免战期！可直接出征。"
                        Toast.makeText(context, "目标地块当前无免战光罩", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        root.findViewById<Button>(R.id.btnAddImmunityToSchedule)?.setOnClickListener {
            if (lastDetectedImmunityUnlockMs <= 0L && pickedImmunityBookmark.isNullOrBlank() && pickedImmunityPoints.isEmpty()) {
                Toast.makeText(context, "请先点选/填写目标并测算免战倒计时", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // 计算建议触发时间 (提前 1 分钟唤醒准备)
            val launchEpoch = if (lastDetectedImmunityUnlockMs > 0) {
                lastDetectedImmunityUnlockMs - 60_000L
            } else {
                System.currentTimeMillis() + 60_000L
            }
            val cal = Calendar.getInstance().apply { timeInMillis = launchEpoch }
            val timeStr = "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
            val targetName = pickedImmunityBookmark ?: "目标地块"
            val prefilledTask = com.stzb.assistant.tactics.ScheduledTaskManager.ScheduledTask(
                id = "",
                name = "破免-$targetName",
                timeStr = timeStr,
                taskType = TacticalState.TaskType.TACTICAL_SCHEDULE,
                isEnabled = true,
                targetX = pickedImmunityPoints.firstOrNull()?.x,
                targetY = pickedImmunityPoints.firstOrNull()?.y,
                targetWorldX = pickedImmunityWorld?.first,
                targetWorldY = pickedImmunityWorld?.second,
                bookmarkName = pickedImmunityBookmark,
                actionType = StzbUiMatcher.ButtonType.ATTACK,
                troopSlot = tacticalDefaults().immunityDefaultTroopSlot,
                isImmunityBreak = true
            )
            showScheduleEditorDialog(prefilledTask)
        }

        root.findViewById<Button>(R.id.btnExecBreakImmunity)?.setOnClickListener {
            if (!ensureLicense()) return@setOnClickListener
            if (!ensureEngineReady()) return@setOnClickListener
            val target = pickedImmunityPoints.firstOrNull()
            val bookmark = pickedImmunityBookmark
            if (target == null && bookmark.isNullOrBlank()) {
                Toast.makeText(context, "请先点选免战地块或填入官方书签", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            pipeline.startImmunityBreak(
                ImmunityBreakFlow.ImmunityConfig(
                    mode = ImmunityBreakFlow.ImmunityMode.BREAK_IMMUNITY,
                    targetTileCoord = target ?: com.stzb.assistant.service.MapProjection.viewportCenterCanvas(),
                    designatedTroopSlot = tacticalDefaults().immunityDefaultTroopSlot,
                    latencyCompensationMs = activeRules().immunityPaddingMs,
                    targetWorldCoord = pickedImmunityWorld,
                    bookmarkName = bookmark
                )
            )
            hideDashboard()
        }

        // 3. 攻城
        var parsedMailPlan: com.stzb.assistant.tactics.AllianceMailParser.SiegeMailPlan? = null

        root.findViewById<Button>(R.id.btnParseAllianceMail)?.setOnClickListener {
            if (!ensureLicense()) return@setOnClickListener
            if (!ensureEngineReady()) return@setOnClickListener

            val frame = EngineBridge.captureFrame()
            if (frame == null) {
                Toast.makeText(context, "截屏失败，请确保截屏通道正常", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val plan = try {
                com.stzb.assistant.tactics.AllianceMailParser.parseFromScreen(frame)
            } finally {
                frame.recycle()
            }

            if (plan != null && (plan.targetWorldCoord != null || plan.targetName.isNotEmpty())) {
                parsedMailPlan = plan
                pickedSiegeWorld = plan.targetWorldCoord
                val coordStr = plan.targetWorldCoord?.let { "(${it.first},${it.second})" } ?: "大地图居中"
                val fortStr = plan.fortressWorldCoord?.let { " | 要塞:(${it.first},${it.second})" } ?: ""
                root.findViewById<TextView>(R.id.tvSiegeCoord)?.text =
                    "🎯 目标:${plan.targetName} $coordStr\n⏰ 触敌:${plan.targetTimeStr} (拆迁+${plan.demolitionOffsetSec}s)$fortStr"
                Toast.makeText(context, "已解析邮件卡片: ${plan.targetName}，发车前30分钟自愈调动与双压秒已就绪！", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, "当前画面未识别到有效同盟法令/邮件，请先打开邮件或点选城池", Toast.LENGTH_SHORT).show()
            }
        }

        root.findViewById<Button>(R.id.btnPickSiegeCity)?.setOnClickListener {
            startCrosshairPicker(PickTarget.SIEGE)
        }
        root.findViewById<Button>(R.id.btnExecSiegeSync)?.setOnClickListener {
            if (!ensureLicense()) return@setOnClickListener
            if (!ensureEngineReady()) return@setOnClickListener

            val mailPlan = parsedMailPlan
            if (mailPlan != null) {
                pipeline.startSiegeFromMail(mailPlan)
                hideDashboard()
                return@setOnClickListener
            }

            val target = pickedSiegePoints.firstOrNull()
            if (target == null && pickedSiegeWorld == null) {
                Toast.makeText(context, "请先解析邮件卡片或点选要集火的城池/要塞", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // Q2=A：没有从邮件法令解析到**真实触敌时刻**时，不再拍脑袋 now+60s 去压秒。
            // 压秒的价值就在那一秒，用假时刻只会把主力送到非约定时间白白暴露。显式拒绝并指引。
            Toast.makeText(
                context,
                "未解析到法令的真实触敌时刻，已拒绝压秒。请先点「解析邮件」获取法令时间，或到「定时」页签手动设定触城时刻后再发车。",
                Toast.LENGTH_LONG
            ).show()
            return@setOnClickListener
        }

        // 4. 深夜巡检
        root.findViewById<Button>(R.id.btnExecRaidPatrol)?.setOnClickListener {
            if (!ensureLicense()) return@setOnClickListener
            if (!ensureEngineReady()) return@setOnClickListener
            pipeline.startNightSentinel(
                RaidDefenseFlow.DefenseConfig(
                    baseAnchor = null,
                    baseWorldCoord = com.stzb.assistant.service.MapProjection.baseWorld,
                    alertCircleRadiusTiles = 2,
                    counterAttackSquadSlot = tacticalDefaults().immunityDefaultTroopSlot,
                    retreatSquadSlots = listOf(1, 2),
                    patrolIntervalMs = tacticalDefaults().raidPatrolIntervalMs,
                    enableAudioAlarm = tacticalDefaults().raidAlarmSound,
                    enableAutoRetreat = true,
                    enableDecisionC = tacticalDefaults().raidDecisionCAutoCounter,
                    enableEmergencyFortify = true,
                    enableKeepAliveJiggle = true,
                    keepAliveIntervalMs = 300_000L
                )
            )
            hideDashboard()
        }

        // 5. 停止当前 / 停止全部
        root.findViewById<Button>(R.id.btnStopCurrent)?.setOnClickListener {
            pipeline.stopCurrentTask()
            // 夜战守护是独立后台 Job，不占当前任务槽，需显式停；否则“停止当前”再也停不掉哨兵。
            pipeline.stopGuardian()
            Toast.makeText(context, "已停止当前任务与夜战守护", Toast.LENGTH_SHORT).show()
        }
        root.findViewById<Button>(R.id.btnEmergencyStop)?.setOnClickListener {
            pipeline.stopAll()
            com.stzb.assistant.tactics.AutoPilot.stop(pipeline)
            Toast.makeText(context, "已停止全部任务、无人托管与守护", Toast.LENGTH_SHORT).show()
        }

        // 6. 诸葛军师
        val tvAdvisorStream = root.findViewById<TextView>(R.id.tvAdvisorStream)
        val tvAdvisorMetrics = root.findViewById<TextView>(R.id.tvAdvisorMetrics)

        root.findViewById<Button>(R.id.btnAdvisorAutoSense)?.setOnClickListener { toggleAutoPilot() }

        // 🛡️ 评估当前守军 —— 让"打这块地会不会白送兵"这个能力真正可用。
        //
        // 刻意做成**手动**而不是自动：它依赖先打开「查看守军」面板，自动去做会在
        // 错误的界面读出无关文字。现走**双队版**：自动读 Lv6/7 的守军1+守军2 取最危险。
        root.findViewById<Button>(R.id.btnAdvisorEvaluateDefenders)?.setOnClickListener {
            if (!ensureEngineReady()) return@setOnClickListener
            val roi = com.stzb.assistant.service.UiAnchors.rect(
                com.stzb.assistant.service.UiAnchors.RectKey.DEFENDER_PANEL
            )
            // 双队评估含抓帧/OCR/切页点击，重且会挂起，故放协程 + IO 线程，避免卡住浮层主线程。
            tvAdvisorStream?.text = "正在评估守军（自动读取 Lv6/7 双队）…"
            tvAdvisorMetrics?.text = "状态: 守军评估中…"
            scope.launch {
                val result = try {
                    withContext(Dispatchers.IO) { EngineBridge.evaluateDefenderPanelDual(roi) }
                } catch (e: Throwable) {
                    android.util.Log.e("Overlay", "守军双队评估异常", e)
                    null
                }
                if (result == null) {
                    tvAdvisorStream?.text = buildString {
                        append("⚠️ 未能评估守军（没有拿到守军名单）。\n")
                        append("文字识别状态：")
                        append(
                            com.stzb.assistant.ocr.OcrManager.unavailableReason
                                ?: "引擎已就绪，但该区域未识别到文字"
                        )
                        append("\n请先打开「查看守军」面板再点此按钮；")
                        append("若识别区域不准，可到「标定」页签标定「查看守军·武将名单区」。")
                    }
                    tvAdvisorMetrics?.text = "状态: 守军评估失败（未拿到守军名单）"
                } else {
                    tvAdvisorStream?.text = buildString {
                        append(result.tier.desc).append('\n')
                        append(result.recommendation).append('\n')
                        append("综合风险分: ").append(result.totalRiskScore).append('\n')
                        if (result.matchedDefenders.isEmpty()) {
                            append("\n（未匹配到知识库中的守将，按常规守军处理）")
                        } else {
                            append("\n命中守将:\n")
                            result.matchedDefenders.forEach { m ->
                                append("  • ").append(m.name).append(" → ").append(m.tier.desc)
                                    .append("｜").append(m.tag).append('\n')
                                append("    克制建议: ").append(m.counterTip).append('\n')
                            }
                        }
                        append("\n（Lv6/7 双队已自动合并取最危险；单队地块不受影响）")
                    }
                    tvAdvisorMetrics?.text =
                        "状态: 守军评估完成（识别到 ${result.matchedDefenders.size} 名守将）"
                }
            }
        }

        root.findViewById<Button>(R.id.btnAdvisorScanDecree)?.setOnClickListener {
            if (!ensureEngineReady()) return@setOnClickListener
            val screenshot = EngineBridge.captureFrame()
            if (screenshot == null) {
                tvAdvisorStream?.text = "⚠️ 抓屏失败，无法读取军令文本。请确认屏幕捕获通道仍在运行。"
                return@setOnClickListener
            }
            val ocrResult = com.stzb.assistant.ocr.OcrManager.detect(screenshot)
            screenshot.recycle()
            val rawDecree = ocrResult?.strRes?.trim().orEmpty()
            // 用"排除 HUD 坐标读数"的文本做解析：否则可能把你**自己**的坐标当成军令目标。
            val decreeText = semanticTextExcludingHud(ocrResult)

            if (decreeText.isBlank()) {
                // 原实现在这里会拿一段**硬编码的军令样本**继续往下解析，
                // 于是无论画面是什么，用户看到的永远是"已解析 虎牢关(782,451)"，
                // 完全无法察觉 OCR 其实从未工作过——这正是"识别不到"被掩盖的方式。
                tvAdvisorStream?.text = buildString {
                    append("⚠️ 未能从当前画面读到任何军令文字，解析已中止。\n")
                    if (rawDecree.isNotEmpty()) {
                        append("（画面里读到了文字，但都落在已排除的 HUD 坐标读数区域里，")
                        append("没有可用于解析的军令内容。）\n")
                    }
                    append("OCR 状态：")
                    append(
                        com.stzb.assistant.ocr.OcrManager.unavailableReason
                            ?: "引擎已就绪，但当前画面中未检测到文字"
                    )
                    append("\n请把游戏切到含军令文字的界面（同盟聊天 / 邮件 / 法令）后重试。")
                }
                tvAdvisorMetrics?.text = "状态: 无可用文本输入"
                return@setOnClickListener
            }

            val order = edgeSlmEngine.parseAllianceDecree(decreeText)
            lastExtractedOrder = order
            // 原文一并留存：执行军令时要基于原文重走"解析→安全校验"，
            // 只凭 TacticalOrder 会丢掉语境，也就判断不出该不该拒绝。
            lastExtractedDecreeText = decreeText
            // 证据清单必须直接上屏：置信度以前只默默进 Utility 打分，面板上不显示，
            // 于是"读到了什么 / 什么都没读到"在界面上完全看不出来。
            val missing = buildList {
                if (!order.targetName.isConcreteTargetName()) add("具体地名")
                if (order.targetCoord == null) add("坐标")
                if (order.targetTime <= 0L) add("时间")
            }
            tvAdvisorStream?.text = buildString {
                append("📜【军令已解析】目标【${order.targetName}】")
                append("(${order.targetCoord?.first ?: "-"}, ${order.targetCoord?.second ?: "-"})\n")
                append(order.advisorThinking)
                append("\n— 证据置信度 ${"%.0f".format(order.confidence * 100f)}%")
                if (missing.isNotEmpty()) {
                    append("（未读到：${missing.joinToString("、")}）")
                    if (order.confidence < 0.60f) {
                        append("\n⚠️ 证据不足：缺少坐标时「执行军令」会被安全闸直接拒掉（不允许拿屏幕正中当目标）。")
                        append("请在军令文本里明确写出坐标，如 582,391。")
                    }
                }
                append("\n— 识别原文: ")
                append(decreeText.take(120))
            }
            val brainDesc = edgeSlmEngine.getBrainDescription()
            tvAdvisorMetrics?.text = "状态: 军令解析完成 ($brainDesc)"
        }

        root.findViewById<Button>(R.id.btnAdvisorDiagnose)?.setOnClickListener {
            if (!ensureEngineReady()) return@setOnClickListener
            val screenshot = EngineBridge.captureFrame()
            if (screenshot == null) {
                tvAdvisorStream?.text = "⚠️ 抓屏失败，无法读取战报文本。请确认屏幕捕获通道仍在运行。"
                return@setOnClickListener
            }
            val ocrResult = com.stzb.assistant.ocr.OcrManager.detect(screenshot)
            screenshot.recycle()
            val rawReport = ocrResult?.strRes?.trim().orEmpty()
            // 战报同样排除 HUD 读数：战报里常有"坐标 (x,y)"字样，
            // 若把常驻的 HUD 读数混进来，会与战报自身的坐标混淆。
            val reportText = semanticTextExcludingHud(ocrResult)

            if (reportText.isBlank()) {
                tvAdvisorStream?.text = buildString {
                    append("⚠️ 未能从当前画面读到战报文字，会诊已中止。\n")
                    if (rawReport.isNotEmpty()) {
                        append("（画面里读到了文字，但都落在已排除的 HUD 坐标读数区域里。）\n")
                    }
                    append("OCR 状态：")
                    append(
                        com.stzb.assistant.ocr.OcrManager.unavailableReason
                            ?: "引擎已就绪，但当前画面中未检测到文字"
                    )
                    append("\n请把游戏切到战报详情页后重试。")
                }
                return@setOnClickListener
            }

            tvAdvisorMetrics?.text = "状态: 战报会诊分析中..."
            tvAdvisorStream?.text = "📜 正在结合 RAG 向量底座与军师大脑推演战报克制机制，请稍候..."

            edgeSlmEngine.diagnoseBattleReportAsync(reportText) { diagnosis ->
                mainHandler.post {
                    tvAdvisorStream?.text = buildString {
                        append(diagnosis.militaryCommentary)
                        append("\n— 识别原文: ")
                        append(reportText.take(120))
                    }
                    tvAdvisorMetrics?.text = "状态: 战报会诊完成 (${edgeSlmEngine.getBrainDescription()})"
                }
            }
        }

        root.findViewById<Button>(R.id.btnAdvisorAskAi)?.setOnClickListener {
            showAskAdvisorDialog()
        }

        // 6.1 「执行军令」：把 lastExtractedOrder 真正送进流水线。
        //
        // 本轮接线：此前"识别军令"只把结果写到 tvAdvisorStream 展示，
        // `lastExtractedOrder` 除了拼状态文案之外**没有任何执行路径**——
        // 也就是"解析得出来、却永远不会打出去"。现在由 DualTrackSafetyGate 统一把关后下发：
        // 通过 → 落到对应战术流；不通过（如缺少坐标系/未标定）→ 明确拒绝并说明原因。
        root.findViewById<Button>(R.id.btnAdvisorExecuteOrder)?.setOnClickListener {
            if (!ensureLicense()) return@setOnClickListener
            if (!ensureEngineReady()) return@setOnClickListener

            val order = lastExtractedOrder
            if (order == null) {
                Toast.makeText(context, "请先点「识别军令」解析出一条军令", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            tvAdvisorMetrics?.text = "状态: 军令安全审查中（防幻觉 + 效用评估）..."
            hideDashboard()
            scope.launch {
                val (ok, detail) = safetyGate.parseAndDispatch(lastExtractedDecreeText)
                withContext(Dispatchers.Main) {
                    tvAdvisorMetrics?.text = "状态: ${if (ok) "军令已下发" else "军令被拦截"}"
                    tvAdvisorStream?.append("\n$detail")
                }
            }
        }

        // 7. 定时任务：计划完全由用户编排，工程内不预置任何任务
        root.findViewById<Button>(R.id.btnAddScheduleTask)?.setOnClickListener {
            showScheduleEditorDialog(existing = null)
        }

        root.findViewById<Button>(R.id.btnResetScheduleDefaults)?.setOnClickListener {
            showScheduleManagerDialog()
        }

        // 7.1 精确闹钟权限：Android 12+ 必须手动授予，否则 RTC 硬件唤醒会被系统拒绝。
        root.findViewById<Button>(R.id.btnEnableExactAlarm)?.setOnClickListener {
            val sm = com.stzb.assistant.tactics.ScheduledTaskManager
            if (sm.needsExactAlarmPermission(context)) {
                if (sm.openExactAlarmSettings(context)) {
                    Toast.makeText(
                        context,
                        "请在弹出的系统设置页里允许「闹钟与提醒」，返回后点「刷新 RTC 状态」。",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                        context,
                        "本机未提供该设置页。请手动到：设置 → 应用 → 特殊应用权限 → 闹钟与提醒 → 允许本应用。",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } else {
                Toast.makeText(context, "已具备精确闹钟权限，无需重复授权。", Toast.LENGTH_SHORT).show()
            }
            refreshRtcStatus()
        }

        root.findViewById<Button>(R.id.btnRefreshRtcStatus)?.setOnClickListener {
            refreshRtcStatus()
        }

        root.findViewById<Button>(R.id.btnTestBookmarkJump)?.setOnClickListener {
            if (!ensureEngineReady()) return@setOnClickListener
            val input = EditText(context).apply {
                hint = "输入游戏内书签名称(如: 虎牢关/要塞1)"
            }
            val dialog = AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("测试书签 0 漂移跳转")
                .setMessage("测试将点击书签抽屉入口并检索该书签条目，验证能否精准瞬移居中。")
                .setView(input)
                .setPositiveButton("开始测试") { _, _ ->
                    val name = input.text.toString().trim()
                    if (name.isEmpty()) {
                        Toast.makeText(context, "书签名称不能为空", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    hideDashboard()
                    scope.launch {
                        when (val res = com.stzb.assistant.service.MapNavigator.jumpByBookmark(name)) {
                            is com.stzb.assistant.service.MapNavigator.Result.Reached -> withContext(Dispatchers.Main) {
                                Toast.makeText(context, "✅ 书签 [$name] 跳转成功！镜头已居中对准。", Toast.LENGTH_LONG).show()
                            }
                            is com.stzb.assistant.service.MapNavigator.Result.Refused -> withContext(Dispatchers.Main) {
                                Toast.makeText(context, "⚠️ 书签跳转被拒绝: ${res.reason}", Toast.LENGTH_LONG).show()
                            }
                            is com.stzb.assistant.service.MapNavigator.Result.Failed -> withContext(Dispatchers.Main) {
                                Toast.makeText(context, "❌ 书签跳转失败: ${res.reason}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
                .setNegativeButton("取消", null)
                .create()
            applyOverlayWindowType(dialog)
            dialog.show()
        }

        // 8. 标定（地图投影 + 关键 UI 锚点）
        //    这是让世界坐标真正可用的入口：没有标定，MapProjection 的默认值在真机上
        //    只是折算出来的猜测，"点不准"就是这么来的。
        root.findViewById<Button>(R.id.btnCalibReadHud)?.setOnClickListener {
            val world = com.stzb.assistant.service.MapProjection.readCurrentCenterWorld()
            if (world == null) {
                val reason = com.stzb.assistant.ocr.OcrManager.unavailableReason
                    ?: "未能在右上角读到坐标读数（可能该界面不显示坐标，或识别区域需要标定）"
                Toast.makeText(context, "读取 HUD 坐标失败", Toast.LENGTH_SHORT).show()
                dashboardView?.findViewById<TextView>(R.id.tvCalibrateStatus)?.text =
                    "❌ 读取 HUD 坐标失败：$reason\n" +
                        "请确认已开启屏幕捕获；若 OCR 不可用，请改用「③ 两点标定」手工输入。"
                return@setOnClickListener
            }
            com.stzb.assistant.service.MapProjection.setBaseWorld(world.first, world.second)
            // 已有标定时，顺便把镜头中心校正到刚读到的值
            com.stzb.assistant.service.MapProjection.calibration?.let { c ->
                com.stzb.assistant.service.MapProjection.calibrate(
                    c.copy(
                        centerWorldX = world.first.toFloat(),
                        centerWorldY = world.second.toFloat()
                    )
                )
            }
            refreshCalibrateDisplay()
            Toast.makeText(
                context,
                "已读到 HUD 坐标 (${world.first},${world.second}) 并记为基地",
                Toast.LENGTH_LONG
            ).show()
        }

        root.findViewById<Button>(R.id.btnCalibPickTile)?.setOnClickListener {
            calibTwoPointMode = false
            calibFirstPoint = null
            calibFirstWorld = null
            Toast.makeText(
                context,
                "请在游戏画面上点选一块地；点选后需要输入它显示的世界坐标",
                Toast.LENGTH_LONG
            ).show()
            startCrosshairPicker(PickTarget.CALIBRATION)
        }

        root.findViewById<Button>(R.id.btnCalibTwoPoint)?.setOnClickListener {
            calibTwoPointMode = true
            calibFirstPoint = null
            calibFirstWorld = null
            Toast.makeText(
                context,
                "两点标定：先点第一块地并输入坐标，再点第二块地并输入坐标",
                Toast.LENGTH_LONG
            ).show()
            startCrosshairPicker(PickTarget.CALIBRATION)
        }

        root.findViewById<Button>(R.id.btnCalibBlankPoint)?.setOnClickListener {
            Toast.makeText(
                context,
                "请点选一个平时不会被误触的地图空白处（用于自动收起浮层）",
                Toast.LENGTH_LONG
            ).show()
            startCrosshairPicker(PickTarget.CALIBRATION_BLANK)
        }

        root.findViewById<Button>(R.id.btnCalibFingerprint)?.setOnClickListener {
            if (!EngineBridge.isCaptureReady) {
                Toast.makeText(context, "请先在主界面开启屏幕捕获", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val frame = EngineBridge.captureFrame()
            if (frame == null) {
                Toast.makeText(context, "抓屏失败，无法记录场景指纹", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val ok = try {
                com.stzb.assistant.service.SceneFingerprint.calibrate(frame)
            } finally {
                frame.recycle()
            }
            refreshCalibrateDisplay()
            Toast.makeText(
                context,
                if (ok) "场景指纹已记录：此后无需 OCR 也能判断是否在大地图"
                else "记录失败：请确认当前正站在大地图界面且屏幕捕获正常",
                Toast.LENGTH_LONG
            ).show()
        }

        root.findViewById<Button>(R.id.btnCalibFingerprintClear)?.setOnClickListener {
            com.stzb.assistant.service.SceneFingerprint.clear()
            refreshCalibrateDisplay()
            Toast.makeText(context, "已清除场景指纹", Toast.LENGTH_SHORT).show()
        }

        // ⑥ 引导式标定部队标签：逐槽位点选，点一次记一格
        root.findViewById<Button>(R.id.btnCalibTroopTabs)?.setOnClickListener {
            troopTabCalibSlot = 1
            rectCalibIndex = -1
            rectCalibCorner = null
            Toast.makeText(
                context,
                "请先把游戏切到【出征选队面板】，再点选【部队${troopTabCalibSlot}】标签的位置",
                Toast.LENGTH_LONG
            ).show()
            startCrosshairPicker(PickTarget.ANCHOR_POINT)
        }

        // ⑦ 引导式标定识别区域：每个区域先后点左上角、右下角
        root.findViewById<Button>(R.id.btnCalibRois)?.setOnClickListener {
            troopTabCalibSlot = 0
            rectCalibIndex = 0
            rectCalibCorner = null
            Toast.makeText(
                context,
                "标定【${rectCalibOrder[0].label}】：请先点它的左上角",
                Toast.LENGTH_LONG
            ).show()
            startCrosshairPicker(PickTarget.ANCHOR_RECT)
        }

        // ⑧ 登记按键模板：**OCR 不可用时按键定位的唯一引导方式**。
        //    让用户用十字准星指一下按键中心，我们把那一小片裁下来存成模板；
        //    之后即使 OCR 完全不可用，该按键也能被模板匹配定位到。
        root.findViewById<Button>(R.id.btnCalibButtonTemplate)?.setOnClickListener {
            val types = com.stzb.assistant.ocr.StzbUiMatcher.ButtonType.values()
            val store = com.stzb.assistant.service.ButtonTemplateStore
            val labels = types.map { t ->
                // 分清"本机登记过"和"只是随包种子"：后者是别人手机上的像素，
                // 正是最该被本机重新登记替换掉的那一张。
                val mark = when (store.sourceOf(t)) {
                    com.stzb.assistant.service.ButtonTemplateStore.Source.USER -> " ✓ 本机已登记"
                    com.stzb.assistant.service.ButtonTemplateStore.Source.SEED -> " ◦ 随包种子"
                    else -> ""
                }
                "${t.primaryKeyword}（${t.name}）$mark"
            }.toTypedArray()
            val dlg = AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("选择要登记模板的按键")
                .setItems(labels) { _, which ->
                    templateCalibType = types[which]
                    Toast.makeText(
                        context,
                        "请把十字准星对准【${types[which].primaryKeyword}】按键的中心" +
                            "（并确保游戏此时正停在该按键所在的界面）",
                        Toast.LENGTH_LONG
                    ).show()
                    startCrosshairPicker(PickTarget.BUTTON_TEMPLATE)
                }
                .setNegativeButton("取消", null)
                .create()
            applyOverlayWindowType(dlg)
            dlg.show()
        }

        // ⑨ 试匹配：当场验证"登记出来的模板到底有没有用"。
        //    模板匹配的命中率只能在真机上确认，而我没有设备；与其让人去翻 logcat，
        //    不如把"用了哪条通道、分数多少、为什么失败"一次摊在对话框里。
        root.findViewById<Button>(R.id.btnCalibTryMatch)?.setOnClickListener {
            val store = com.stzb.assistant.service.ButtonTemplateStore
            val registered = com.stzb.assistant.ocr.StzbUiMatcher.ButtonType.values()
                .filter { store.has(it) }
            if (registered.isEmpty()) {
                Toast.makeText(
                    context,
                    "还没有登记任何按键模板。请先用「⑧ 登记按键模板」登记一个。",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }
            val labels = registered.map { "${it.primaryKeyword}（${it.name}）" }.toTypedArray()
            val dlg = AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("试匹配哪个按键？")
                .setItems(labels) { _, which -> runTemplateTrial(registered[which]) }
                .setNegativeButton("取消", null)
                .create()
            applyOverlayWindowType(dlg)
            dlg.show()
        }

        // 守军头像阈值现场校准：真机 MediaProjection 抓帧与截图非同一路径、分数会整体下移。
        // 首次真机按 classify 日志（含各槽最高分与当前门槛）把门槛挪到“能覆盖真机、又不误认”的位置。
        // 越界（0.50~0.95 之外）由 setMatchThreshold 拒绝并保留原值，这里只按 ±0.02 步进。
        val defThreshStep = 0.02f
        root.findViewById<Button>(R.id.btnCalibDefThreshDown)?.setOnClickListener {
            adjustDefenderThreshold(-defThreshStep)
        }
        root.findViewById<Button>(R.id.btnCalibDefThreshUp)?.setOnClickListener {
            adjustDefenderThreshold(defThreshStep)
        }

        root.findViewById<Button>(R.id.btnCalibReset)?.setOnClickListener {
            com.stzb.assistant.service.MapProjection.clearCalibration()
            com.stzb.assistant.service.UiAnchors.clearAllCalibration()
            com.stzb.assistant.service.SceneFingerprint.clear()
            com.stzb.assistant.service.ButtonTemplateStore.clearAll()
            resetGuidedCalibration()
            refreshCalibrateDisplay()
            Toast.makeText(context, "已清除全部标定，回到默认值", Toast.LENGTH_SHORT).show()
        }

        // 9. 后勤全托管
        root.findViewById<Button>(R.id.btnExecLogistics)?.setOnClickListener {
            if (!ensureLicense()) return@setOnClickListener
            if (!ensureEngineReady()) return@setOnClickListener

            val tax = root.findViewById<CheckBox>(R.id.cbLogisticsTax)?.isChecked ?: true
            val recruit = root.findViewById<CheckBox>(R.id.cbLogisticsRecruit)?.isChecked ?: true
            val stamina = root.findViewById<CheckBox>(R.id.cbLogisticsStamina)?.isChecked ?: true
            val upgrade = root.findViewById<CheckBox>(R.id.cbLogisticsUpgrade)?.isChecked ?: true

            pipeline.startDailyLogistics(
                DailyLogisticsFlow.LogisticsConfig(
                    enableTaxLevy = tax,
                    enableReserveRecruitment = recruit,
                    enableStaminaProtection = stamina,
                    enableCityConstruction = upgrade
                )
            )
            hideDashboard()
        }

        // 10. 智能屯田打铁
        val spFarmingSlot = root.findViewById<Spinner>(R.id.spFarmingSlot)
        val spFarmingResType = root.findViewById<Spinner>(R.id.spFarmingResType)

        val slotLabels = arrayOf("部队1", "部队2", "部队3", "部队4", "部队5")
        spFarmingSlot?.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, slotLabels)
        spFarmingSlot?.setSelection(1) // 默认二队

        val resLabels = arrayOf("石料(优先)", "铁矿", "木材", "粮食")
        val resTypes = arrayOf(
            TileStatusDetector.ResourceType.STONE,
            TileStatusDetector.ResourceType.IRON,
            TileStatusDetector.ResourceType.WOOD,
            TileStatusDetector.ResourceType.GRAIN
        )
        spFarmingResType?.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, resLabels)

        root.findViewById<Button>(R.id.btnPickFarmingTile)?.setOnClickListener {
            startCrosshairPicker(PickTarget.FARMING)
        }

        root.findViewById<Button>(R.id.btnExecFarming)?.setOnClickListener {
            if (!ensureLicense()) return@setOnClickListener
            if (!ensureEngineReady()) return@setOnClickListener

            val selectedSlot = (spFarmingSlot?.selectedItemPosition ?: 1) + 1
            val selectedResType = resTypes.getOrElse(spFarmingResType?.selectedItemPosition ?: 0) {
                TileStatusDetector.ResourceType.STONE
            }
            val blacksmith = root.findViewById<CheckBox>(R.id.cbFarmingBlacksmith)?.isChecked ?: true

            val targetP = pickedFarmingPoint
            if (targetP == null && pickedFarmingWorld == null) {
                Toast.makeText(context, "请先点选屯田地块或配置书签", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            pipeline.startAccurateFarming(
                AccurateFarmingFlow.FarmingConfig(
                    targetTileCoord = targetP,
                    targetWorldCoord = pickedFarmingWorld,
                    targetResourceType = selectedResType,
                    farmingTroopSlot = selectedSlot,
                    minTileLevel = 5,
                    enableBlacksmithCheck = blacksmith
                )
            )
            hideDashboard()
        }

        // ------------------------------------------------------
        // panelGarrison: PVP 驻守剥皮透视与 PVE 软柿子雷达
        // ------------------------------------------------------
        root.findViewById<Button>(R.id.btnPickGarrisonTarget)?.setOnClickListener {
            startCrosshairPicker(PickTarget.GARRISON)
        }

        root.findViewById<Button>(R.id.btnStartGarrisonStrip)?.setOnClickListener {
            if (!ensureLicense()) return@setOnClickListener
            if (!ensureEngineReady()) return@setOnClickListener

            val bookmark = root.findViewById<EditText>(R.id.etGarrisonBookmark)?.text?.toString()?.trim()
            val targetP = pickedGarrisonPoint
            if (targetP == null && pickedGarrisonWorld == null && bookmark.isNullOrBlank()) {
                Toast.makeText(context, "请先点选目标地块或填写官方书签", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            pipeline.startGarrisonStripping(
                com.stzb.assistant.tactics.GarrisonStripperFlow.GarrisonConfig(
                    targetTileCoord = targetP,
                    targetWorldCoord = pickedGarrisonWorld,
                    bookmarkName = bookmark?.takeIf { it.isNotBlank() },
                    spartanTroopSlot = 5,
                    autoDispatchCounter = false
                )
            )
            hideDashboard()
        }

        root.findViewById<Button>(R.id.btnStartSoftTileRadar)?.setOnClickListener {
            if (!ensureLicense()) return@setOnClickListener
            if (!ensureEngineReady()) return@setOnClickListener

            val bookmark = root.findViewById<EditText>(R.id.etGarrisonBookmark)?.text?.toString()?.trim()
            val targetP = pickedGarrisonPoint
            pipeline.startSoftTileRadar(
                com.stzb.assistant.tactics.SoftTileRadarFlow.RadarConfig(
                    centerCoord = targetP,
                    centerWorldCoord = pickedGarrisonWorld,
                    bookmarkName = bookmark?.takeIf { it.isNotBlank() },
                    scanRadiusTiles = 2,
                    targetMinLevel = 6,
                    targetMaxLevel = 9,
                    maxScanCount = 12
                )
            )
            hideDashboard()
        }

        root.findViewById<Button>(R.id.btnBindToLeveling)?.setOnClickListener {
            val best = pipeline.softTileRadarFlow.getBestSoftTile()
            if (best != null) {
                pickedLevelingPoint = best.canvasCoord
                pickedLevelingWorld = best.worldCoord
                root.findViewById<TextView>(R.id.tvLevelingTargetCoord)?.text =
                    "练级地: (${best.canvasCoord.x.toInt()}, ${best.canvasCoord.y.toInt()})${worldSuffix(best.worldCoord)}"
                root.findViewById<EditText>(R.id.etLevelingTileLevel)?.setText(best.level.toString())
                Toast.makeText(context, "已将最优地 Lv.${best.level}(${best.rating}) 绑定至练级流水线", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "尚未探测到可用软柿子地块，请先运行雷达扫描", Toast.LENGTH_SHORT).show()
            }
        }

        // ------------------------------------------------------
        // panelLeveling: 全赛季二三队低损速升 40 级流水线
        // ------------------------------------------------------
        root.findViewById<Button>(R.id.btnPickLevelingTarget)?.setOnClickListener {
            startCrosshairPicker(PickTarget.LEVELING)
        }

        root.findViewById<Button>(R.id.btnStartLeveling)?.setOnClickListener {
            if (!ensureLicense()) return@setOnClickListener
            if (!ensureEngineReady()) return@setOnClickListener

            val slotAText = root.findViewById<EditText>(R.id.etLevelingSlotA)?.text?.toString()
            val slotBText = root.findViewById<EditText>(R.id.etLevelingSlotB)?.text?.toString()
            val lvlText = root.findViewById<EditText>(R.id.etLevelingTileLevel)?.text?.toString()

            val slotA = slotAText?.toIntOrNull() ?: 2
            val slotB = slotBText?.toIntOrNull() ?: 3
            val lvl = lvlText?.toIntOrNull() ?: 7

            val targetP = pickedLevelingPoint
            if (targetP == null && pickedLevelingWorld == null) {
                Toast.makeText(context, "请先点选练级地块或从雷达一键绑定", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            pipeline.startSquadLeveling(
                com.stzb.assistant.tactics.SquadLevelingFlow.LevelingConfig(
                    targetTileCoord = targetP,
                    targetWorldCoord = pickedLevelingWorld,
                    tileLevel = lvl,
                    squadSlotA = slotA,
                    squadSlotB = slotB,
                    staminaMinThreshold = 20,
                    maxCasualtyRate = 0.15f,
                    minHealthPercent = 0.70f,
                    haltOnSevereInjury = true,
                    autoReplenishReserves = true,
                    maxRounds = 15
                )
            )
            hideDashboard()
        }

        root.findViewById<Button>(R.id.btnStopLeveling)?.setOnClickListener {
            pipeline.squadLevelingFlow.stop()
            root.findViewById<TextView>(R.id.tvLevelingLiveStatus)?.text = "练级已停止"
            Toast.makeText(context, "二三队低损练级流水线已终止", Toast.LENGTH_SHORT).show()
        }
    }

    /** 中止所有引导式标定流程。 */
    /**
     * 当场对某个按键做一次定位尝试，并把**结论**直接显示出来。
     *
     * 为什么需要它：模板匹配的命中率只能在真机上确认，而本机没有设备。
     * 与其让人去翻 logcat，不如把"走了哪条通道、分数多少、为什么失败"一次摊开。
     *
     * 注意 `record = false`：一次手工试验不应该污染挂机期间的识别健康度统计。
     */
    private fun runTemplateTrial(type: com.stzb.assistant.ocr.StzbUiMatcher.ButtonType) {
        if (!EngineBridge.isCaptureReady) {
            Toast.makeText(context, "屏幕捕获未运行，无法试匹配", Toast.LENGTH_LONG).show()
            return
        }
        val frame = EngineBridge.captureFrame()
        if (frame == null) {
            Toast.makeText(context, "抓屏失败，无法试匹配", Toast.LENGTH_LONG).show()
            return
        }
        val lookup = try {
            com.stzb.assistant.ocr.StzbUiMatcher.findButtonWithReason(frame, type, record = false)
        } catch (e: Exception) {
            Log.w(TAG, "试匹配异常: ${e.message}")
            null
        } finally {
            frame.recycle()
        }

        val cv = com.stzb.assistant.ocr.OpenCvMatcher
        val store = com.stzb.assistant.service.ButtonTemplateStore
        val text = buildString {
            append("按键：").append(type.primaryKeyword).append("（").append(type.name).append("）\n")
            append("OpenCV 通道：")
                .append(if (cv.isAvailable) "可用（主通道）" else "不可用（走纯 Java 兜底）").append('\n')
            append("模板：").append(
                when (store.sourceOf(type)) {
                    com.stzb.assistant.service.ButtonTemplateStore.Source.USER -> "本机登记"
                    com.stzb.assistant.service.ButtonTemplateStore.Source.SEED ->
                        "随包种子（不是本机像素，命中不稳时请重新登记）"
                    else -> "未登记"
                }
            ).append("\n\n")

            val b = lookup?.button
            if (b != null) {
                append("✅ 定位成功\n")
                append("  通道：")
                    .append(if (b.matchedText.contains("模板")) "模板匹配" else "OCR 文字识别").append('\n')
                append("  触控点：(").append(b.safeTouchPoint.x.toInt()).append(", ")
                    .append(b.safeTouchPoint.y.toInt()).append(")\n")
                append("  分数/置信度：").append("%.3f".format(b.confidence)).append('\n')
                if (!b.matchedText.contains("模板")) {
                    append("  识别到的文字：").append(b.matchedText).append('\n')
                }
            } else {
                append("❌ 未定位到\n")
                append("  原因：").append(lookup?.failure?.desc ?: "未知").append('\n')
                append("  本帧读到文本块：").append(lookup?.scannedTextCount ?: 0).append(" 个\n")
            }
            append("\n说明：若显示「未定位到」但模板确实已登记，多半是当前画面并非该按键所在界面，")
            append("或登记时对准的位置与现在不一致。可重新登记一次再试。")
        }

        val dlg = AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("试匹配结果")
            .setMessage(text)
            .setPositiveButton("知道了", null)
            // 试匹配失败往往就是模板不好（对准的位置偏了、或那片区域几乎纯色）。
            // 给一个就地清掉的入口，免得留着一个"已登记但永远匹配不上"的模板。
            .setNeutralButton("清除该模板") { _, _ ->
                store.clear(type)
                refreshCalibrateDisplay()
                Toast.makeText(
                    context,
                    "已清除【${type.primaryKeyword}】的按键模板，可重新用「⑧」登记一次",
                    Toast.LENGTH_LONG
                ).show()
            }
            .create()
        applyOverlayWindowType(dlg)
        dlg.show()
    }

    private fun resetGuidedCalibration() {
        calibTwoPointMode = false
        calibFirstPoint = null
        calibFirstWorld = null
        troopTabCalibSlot = 0
        rectCalibIndex = -1
        rectCalibCorner = null
        templateCalibType = null
    }

    // ==========================================================
    // 8. 标定（地图投影 + UI 锚点）
    // ==========================================================

    /** 刷新标定面板的状态显示。 */
    private fun refreshCalibrateDisplay() {
        val tv = dashboardView?.findViewById<TextView>(R.id.tvCalibrateStatus) ?: return
        val proj = com.stzb.assistant.service.MapProjection.describeCalibration()
        val totalAnchors = com.stzb.assistant.service.UiAnchors.Key.values().size +
            com.stzb.assistant.service.UiAnchors.RectKey.values().size
        val uncal = com.stzb.assistant.service.UiAnchors.uncalibratedCount()
        val ocrHint = if (com.stzb.assistant.ocr.OcrManager.isEngineAvailable) {
            "OCR 可用：可用「①」自动读坐标，或「②」单点标定。"
        } else {
            "⚠️ OCR 不可用（${com.stzb.assistant.ocr.OcrManager.unavailableReason ?: "未编译"}）：" +
                "「①」会失败，「②」也无法用；请用「③ 两点标定」手工输入两块地的坐标。"
        }
        tv.text = buildString {
            append("地图投影：").append(proj).append('\n')
            append("UI 锚点：").append(totalAnchors - uncal).append('/').append(totalAnchors).append(" 已标定\n")
            append("场景指纹：").append(com.stzb.assistant.service.SceneFingerprint.describe()).append('\n')
            append(ocrHint).append('\n')
            // 按键模板：OCR 不可用时按键定位的唯一依靠，必须让用户看得见有多少可用
            append(com.stzb.assistant.service.ButtonTemplateStore.describe()).append('\n')
            // 识别健康度：把"识别最近灵不灵"当场摆出来。
            // 单次失败说明不了问题（一次过渡动画也会失败），要看的是成功率与
            // "整帧无文字"的比例——这才是"识别在退化"的可观测信号。
            append('\n').append(com.stzb.assistant.ocr.RecognitionHealth.summary())
            // 守军头像阈值：真机识别不灵时，这是第一个该看的旋钮（配合 classify 日志微调）。
            append('\n')
            append("守军头像阈值：")
                .append("%.3f".format(com.stzb.assistant.ocr.DefenderTemplateClassifier.currentMatchThreshold))
                .append("（可用上方「守军阈值 ±」按真机日志微调，区间 0.50~0.95）")
        }
    }

    /**
     * 步进调整守军头像采信门槛并反馈。越界时分类器拒绝并保留原值（返回 false），
     * 这里如实提示“已到边界”，不谎报成功。
     */
    private fun adjustDefenderThreshold(delta: Float) {
        val classifier = com.stzb.assistant.ocr.DefenderTemplateClassifier
        val applied = classifier.setMatchThreshold(classifier.currentMatchThreshold + delta)
        Toast.makeText(
            context,
            if (applied) {
                "守军头像阈值 → ${"%.3f".format(classifier.currentMatchThreshold)}（下次评估即生效）"
            } else {
                "已到边界 [0.50, 0.95]，阈值保持不变（再低会误认守将，再高会几乎全拒）"
            },
            Toast.LENGTH_SHORT
        ).show()
        refreshCalibrateDisplay()
    }

    /**
     * 弹出"输入世界坐标"对话框。
     * 游戏里的坐标是 X/Y 两个整数（如 228 / 132），因此用两个纯数字输入框。
     */
    private fun askWorldCoordinate(title: String, onConfirm: (Pair<Int, Int>) -> Unit) {
        val pad = dp(16)
        val form = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        form.addView(TextView(context).apply {
            text = "把这块地在游戏里显示的坐标填进来（例如 228 和 132）"
            textSize = 12f
        })
        val etX = EditText(context).apply {
            hint = "X（如 228）"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val etY = EditText(context).apply {
            hint = "Y（如 132）"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        form.addView(etX)
        form.addView(etY)

        val dlg = AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setView(form)
            .setPositiveButton("确定", null)
            .setNegativeButton("取消", null)
            .create()
        applyOverlayWindowType(dlg)
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val x = etX.text.toString().trim().toIntOrNull()
                val y = etY.text.toString().trim().toIntOrNull()
                if (x == null || y == null) {
                    Toast.makeText(context, "请输入合法的整数坐标", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                dlg.dismiss()
                onConfirm(Pair(x, y))
            }
        }
        dlg.show()
    }

    /** 取点确认后，把标定用的一次性点交给标定流程。 */
    private fun handleCalibrationPick(p: PointF) {
        if (!calibTwoPointMode) {
            askWorldCoordinate("① 输入这块地的世界坐标") { w ->
                completeSinglePointCalibration(p, w)
            }
            return
        }

        val first = calibFirstPoint
        val firstWorld = calibFirstWorld
        if (first == null || firstWorld == null) {
            askWorldCoordinate("① 输入【第一块地】的世界坐标") { w ->
                calibFirstPoint = p
                calibFirstWorld = w
                Toast.makeText(context, "已记录第一点，请再点选第二块地", Toast.LENGTH_LONG).show()
                startCrosshairPicker(PickTarget.CALIBRATION)
            }
        } else {
            askWorldCoordinate("② 输入【第二块地】的世界坐标") { w ->
                val ok = com.stzb.assistant.service.MapProjection
                    .calibrateFromTwoPoints(first, firstWorld, p, w)
                calibTwoPointMode = false
                calibFirstPoint = null
                calibFirstWorld = null
                refreshCalibrateDisplay()
                Toast.makeText(
                    context,
                    if (ok) "两点标定成功，世界坐标已可用" else "两点标定失败：两点坐标不能相同，且需相距足够远",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * 单点标定：已知镜头中心世界坐标 c，用户点选到的画布坐标 p，以及该点的世界坐标 w，
     * 则每格像素数 tilePx = (p - 视口中心) / (w - c)。
     */
    private fun completeSinglePointCalibration(p: PointF, w: Pair<Int, Int>) {
        val c = com.stzb.assistant.service.MapProjection.readCurrentCenterWorld()
        if (c == null) {
            val reason = com.stzb.assistant.ocr.OcrManager.unavailableReason ?: "读不到 HUD 坐标"
            Toast.makeText(context, "单点标定需要先知道镜头中心坐标：$reason", Toast.LENGTH_LONG).show()
            dashboardView?.findViewById<TextView>(R.id.tvCalibrateStatus)?.text =
                "❌ 单点标定失败：$reason\n请改用「③ 两点标定」，它只需要两块地各自的坐标。"
            return
        }

        val dx = (w.first - c.first).toFloat()
        val dy = (w.second - c.second).toFloat()
        if (kotlin.math.abs(dx) < 0.5f || kotlin.math.abs(dy) < 0.5f) {
            Toast.makeText(
                context,
                "这块地与镜头中心的世界坐标太接近，无法解出每格像素数；请选一块离屏幕中心远一些的地",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val vc = com.stzb.assistant.service.MapProjection.viewportCenterCanvas()
        val tilePxX = (p.x - vc.x) / dx
        val tilePxY = (p.y - vc.y) / dy
        if (tilePxX <= 1f || tilePxY <= 1f) {
            Toast.makeText(
                context,
                "解出的每格像素数不合理（${"%.1f".format(tilePxX)}, ${"%.1f".format(tilePxY)}），请检查输入的坐标是否正确",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        com.stzb.assistant.service.MapProjection.calibrate(
            com.stzb.assistant.service.MapProjection.Calibration(
                tilePxX = tilePxX,
                tilePxY = tilePxY,
                centerWorldX = c.first.toFloat(),
                centerWorldY = c.second.toFloat()
            )
        )
        refreshCalibrateDisplay()
        Toast.makeText(
            context,
            "单点标定成功：每格 ${"%.1f".format(tilePxX)}x${"%.1f".format(tilePxY)}px，世界坐标已可用",
            Toast.LENGTH_LONG
        ).show()
    }

    /**
     * 当前生效知识库的战术默认参数。
     * 切换游戏知识库或云端热更后，这些值会立即生效——这正是知识库"真被使用"的体现。
     */
    private fun tacticalDefaults() =
        com.stzb.assistant.knowledge.KnowledgeBaseManager.activeProfile.tacticalDefaults

    /** 当前生效知识库的游戏规则（体力/士气/免战等阈值）。 */
    private fun activeRules() =
        com.stzb.assistant.knowledge.KnowledgeBaseManager.activeProfile.rules

    private fun refreshScheduleDisplay() {
        val root = dashboardView ?: return
        val tvStatus = root.findViewById<TextView>(R.id.tvScheduleSummary)
        val tvList = root.findViewById<TextView>(R.id.tvScheduleTasksDisplay)

        val tasks = com.stzb.assistant.tactics.ScheduledTaskManager.getTasks()
        tvStatus?.text = "⏰ 定时计划共 ${tasks.size} 项（每 15 秒轮询一次）"

        refreshRtcStatus()

        if (tasks.isEmpty()) {
            tvList?.text = "尚未编排任何定时任务。\n" +
                "点击下方「＋ 新建定时任务」自行添加——工程内不预置任何任务。"
        } else {
            val sb = StringBuilder()
            tasks.forEachIndexed { index, task ->
                val statusTag = if (task.isEnabled) "【已开启】" else "【已暂停】"
                // 多目标任务把"到点会处理几块地"直接写在计划列表里——
                // 否则用户点选了 5 块地，却只能看到 1 个目标坐标，会以为另外 4 块丢了。
                val allCount = task.allTargets().size
                val countTag = if (task.taskType == TacticalState.TaskType.ROAD_PAVING && allCount > 1) {
                    " · 共 $allCount 块地"
                } else {
                    ""
                }
                val targetTag = when {
                    task.taskType == TacticalState.TaskType.RAID_DEFENSE || task.taskType == TacticalState.TaskType.NIGHT_SENTINEL -> ""
                    !task.bookmarkName.isNullOrBlank() -> " · 🔖书签[${task.bookmarkName}] (0漂移对准)"
                    !task.hasTarget() -> " · ⚠️无目标（到点将跳过而不是乱点）"
                    task.targetWorld() != null -> {
                        val w = task.targetWorld()!!
                        " · 目标(${task.targetX?.toInt()}, ${task.targetY?.toInt()}) 世界(${w.first},${w.second})$countTag"
                    }
                    else -> " · 目标(${task.targetX?.toInt()}, ${task.targetY?.toInt()}) ⚠️未标定世界坐标$countTag"
                }
                sb.append("${index + 1}. [${task.timeStr}] ${task.name}\n")
                // 攻城的卡秒偏移或定时的动作槽位必须显示出来
                val timingTag = if (task.taskType == TacticalState.TaskType.SIEGE_SYNC) {
                    " · 卡秒偏移 ${task.hitOffsetSeconds}s"
                } else if (task.taskType == TacticalState.TaskType.TACTICAL_SCHEDULE) {
                    " · 动作: ${task.actionType.primaryKeyword} · 第${task.troopSlot}队" + (if (task.isImmunityBreak) " [压秒破免]" else "")
                } else {
                    ""
                }
                sb.append("   ${task.taskType.displayName} $statusTag$targetTag$timingTag\n")
            }
            tvList?.text = sb.toString().trimEnd()
        }
    }

    /**
     * 刷新"硬件级 RTC 唤醒"的真实状态。
     *
     * 这个状态是**必须**露出来的：Android 12+ 不授予「闹钟与提醒」时，
     * `setExactAndAllowWhileIdle` 会被系统直接拒绝，定时任务在深度息屏后不会触发。
     * 如果界面上不显示，用户只会得到"我设了任务却没执行"的困惑，
     * 而日志里的 SecurityException 又不会被普通用户看到。
     */
    private fun refreshRtcStatus() {
        val root = dashboardView ?: return
        val tvRtc = root.findViewById<TextView>(R.id.tvScheduleRtcStatus) ?: return
        val sm = com.stzb.assistant.tactics.ScheduledTaskManager

        val permissionOk = !sm.needsExactAlarmPermission(context)
        tvRtc.text = buildString {
            append(if (permissionOk) "🔓 精确闹钟权限：已授予" else "🔒 精确闹钟权限：未授予（息屏后不会准点触发）")
            append("\n")
            append("⏰ ").append(sm.exactAlarmStatusText)
        }
    }

    /**
     * 新建 / 编辑定时任务表单。
     *
     * 关于目标坐标：铺路与攻城必须要有明确目标，目标取自对应页签中**用户已点选**的地块。
     * 原实现没有"目标"这个概念，触发时直接把屏幕正中当目标去点——那不是用户意图，
     * 还可能对着城池误操作。所以这里不提供"随便取一个点"的入口，只如实展示当前可用的目标。
     */
    private fun showScheduleEditorDialog(existing: com.stzb.assistant.tactics.ScheduledTaskManager.ScheduledTask?) {
        val typeLabels = arrayOf("暗夜天眼哨兵", "同盟战役双压秒", "离线战术定时(含破免)", "日常后勤全托管", "自动屯田打铁")
        val typeValues = arrayOf(
            TacticalState.TaskType.NIGHT_SENTINEL,
            TacticalState.TaskType.SIEGE_SYNC,
            TacticalState.TaskType.TACTICAL_SCHEDULE,
            TacticalState.TaskType.LOGISTICS_STEWARD,
            TacticalState.TaskType.FARMING_STEWARD
        )

        val pad = dp(16)
        val form = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }

        val etName = EditText(context).apply {
            hint = "任务名称"
            setText(existing?.name ?: "")
        }
        form.addView(etName)

        form.addView(TextView(context).apply {
            text = "执行时间（24 小时制）"
            setPadding(0, dp(12), 0, 0)
        })

        val timePicker = TimePicker(context).apply { setIs24HourView(true) }
        run {
            val parts = (existing?.timeStr ?: "20:00").split(":")
            timePicker.hour = parts.getOrNull(0)?.toIntOrNull() ?: 20
            timePicker.minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
        }
        form.addView(timePicker)

        form.addView(TextView(context).apply {
            text = "任务类型"
            setPadding(0, dp(12), 0, 0)
        })

        val spType = Spinner(context).apply {
            adapter = ArrayAdapter(
                context,
                android.R.layout.simple_spinner_dropdown_item,
                typeLabels.toList()
            )
        }
        val initialIndex = typeValues.indexOfFirst { it == existing?.taskType }
        spType.setSelection(if (initialIndex >= 0) initialIndex else 0)
        form.addView(spType)

        val tvTargetHint = TextView(context).apply {
            setPadding(0, dp(12), 0, 0)
            textSize = 12f
        }
        form.addView(tvTargetHint)

        // 官方书签/标记名称
        val tvBookmarkLabel = TextView(context).apply {
            text = "官方书签/标记（选填，优先通过游戏书签 0 漂移对准）"
            setPadding(0, dp(12), 0, 0)
            textSize = 12f
        }
        form.addView(tvBookmarkLabel)

        val etBookmark = EditText(context).apply {
            hint = "例如: 虎牢关 / 主城 / 要塞1"
            setText(existing?.bookmarkName ?: "")
            setSingleLine()
        }
        form.addView(etBookmark)

        // 战术动作与槽位 (出征/扫荡/屯田/驻守)
        val actionLabels = arrayOf("出征 (ATTACK)", "扫荡 (SWEEP)", "屯田 (FARM)", "驻守 (DEFEND)")
        val actionValues = arrayOf(
            StzbUiMatcher.ButtonType.ATTACK,
            StzbUiMatcher.ButtonType.SWEEP,
            StzbUiMatcher.ButtonType.FARM,
            StzbUiMatcher.ButtonType.DEFEND
        )
        val tvActionLabel = TextView(context).apply {
            text = "执行战术动作"
            setPadding(0, dp(12), 0, 0)
            textSize = 12f
        }
        form.addView(tvActionLabel)

        val spAction = Spinner(context).apply {
            adapter = ArrayAdapter(
                context,
                android.R.layout.simple_spinner_dropdown_item,
                actionLabels.toList()
            )
        }
        val initialActionIdx = actionValues.indexOfFirst { it == existing?.actionType }
        spAction.setSelection(if (initialActionIdx >= 0) initialActionIdx else 0)
        form.addView(spAction)

        val tvTroopLabel = TextView(context).apply {
            text = "出征部队槽位 (1 ~ 5)"
            setPadding(0, dp(12), 0, 0)
            textSize = 12f
        }
        form.addView(tvTroopLabel)

        val etTroopSlot = EditText(context).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "例如 1"
            setText((existing?.troopSlot ?: 1).toString())
            setSingleLine()
        }
        form.addView(etTroopSlot)

        val cbImmunityBreak = CheckBox(context).apply {
            text = "启用 OCR 压秒破免模式 (+1000ms 精准触敌)"
            isChecked = existing?.isImmunityBreak ?: false
            textSize = 12f
        }
        form.addView(cbImmunityBreak)

        // 攻城卡秒偏移：任务触发后经过多少秒发动总攻。
        val tvHitOffsetLabel = TextView(context).apply {
            setPadding(0, dp(12), 0, 0)
            textSize = 12f
        }
        form.addView(tvHitOffsetLabel)

        val etHitOffset = EditText(context).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "例如 60"
            setText((existing?.hitOffsetSeconds ?: 60L).toString())
            setSingleLine()
        }
        form.addView(etHitOffset)

        /**
         * 取该类型当前点选的**全部**目标（画布坐标 + 配对的世界坐标）。
         */
        fun resolveTargets(
            type: TacticalState.TaskType
        ): List<com.stzb.assistant.tactics.ScheduledTaskManager.TargetPoint> {
            return when (type) {
                TacticalState.TaskType.ROAD_PAVING,
                TacticalState.TaskType.TACTICAL_SCHEDULE -> pickedPavingPoints.mapIndexed { i, p ->
                    val w = pickedPavingWorld.getOrNull(i)
                    com.stzb.assistant.tactics.ScheduledTaskManager.TargetPoint(p.x, p.y, w?.first, w?.second)
                }

                TacticalState.TaskType.SIEGE_SYNC -> pickedSiegePoints.firstOrNull()?.let { p ->
                    listOf(com.stzb.assistant.tactics.ScheduledTaskManager.TargetPoint(p.x, p.y, pickedSiegeWorld?.first, pickedSiegeWorld?.second))
                } ?: emptyList()

                else -> emptyList()
            }
        }

        fun updateTargetHint() {
            val type = typeValues[spType.selectedItemPosition]
            val targets = resolveTargets(type)
            val n = targets.size
            val worldCount = targets.count { it.worldX != null && it.worldY != null }

            val isSiege = type == TacticalState.TaskType.SIEGE_SYNC
            val isSchedule = type == TacticalState.TaskType.TACTICAL_SCHEDULE
            val isSentinel = type == TacticalState.TaskType.RAID_DEFENSE || type == TacticalState.TaskType.NIGHT_SENTINEL

            tvBookmarkLabel.visibility = if (isSchedule || isSiege) View.VISIBLE else View.GONE
            etBookmark.visibility = if (isSchedule || isSiege) View.VISIBLE else View.GONE

            tvActionLabel.visibility = if (isSchedule) View.VISIBLE else View.GONE
            spAction.visibility = if (isSchedule) View.VISIBLE else View.GONE
            tvTroopLabel.visibility = if (isSchedule) View.VISIBLE else View.GONE
            etTroopSlot.visibility = if (isSchedule) View.VISIBLE else View.GONE
            cbImmunityBreak.visibility = if (isSchedule) View.VISIBLE else View.GONE

            tvHitOffsetLabel.visibility = if (isSiege) View.VISIBLE else View.GONE
            etHitOffset.visibility = if (isSiege) View.VISIBLE else View.GONE
            if (isSiege) {
                tvHitOffsetLabel.text =
                    "卡秒偏移：以**任务时间**为基准，经过多少秒发动总攻（5~3600 秒）\n" +
                        "例：任务 20:59 + 偏移 60 秒 → 21:00:00 触敌\n" +
                        "注：轮询有最多约 15 秒延迟，偏移太小时命中时刻会已过去，" +
                        "该任务将被跳过并在日志说明（不会晚打）。"
            }
            tvTargetHint.text = when {
                isSentinel ->
                    "🛡️ 暗夜哨兵自动全天候巡检主城及周边2格，无需指定目标地块。"

                isSchedule -> {
                    val bm = etBookmark.text.toString().trim()
                    if (bm.isNotEmpty()) {
                        "🔖 已设置官方书签 [$bm]，到点将通过官方标记抽屉 0 漂移瞬间对准。"
                    } else if (targets.isNotEmpty()) {
                        "🎯 将处理点选的 ${targets.size} 处目标地块。"
                    } else {
                        "⚠️ 建议填入官方书签名称（0 漂移对准），或先到准星取点页签点选地块。"
                    }
                }

                targets.isEmpty() ->
                    "⚠️ 该类型需要目标地块。请先到对应页签点选地块；" +
                        "未设目标的任务到点会被跳过，而不是乱点一处。"

                isSiege -> {
                    val t = targets.first()
                    val coord = String.format(
                        java.util.Locale.US, "(%.0f, %.0f)", t.x, t.y
                    )
                    "集火目标: $coord（攻城一次只打一座城）" + if (t.worldX != null) {
                        " · 世界坐标 (${t.worldX},${t.worldY})"
                    } else {
                        " · ⚠️ 未标定世界坐标，建议先到「标定」页签标定"
                    }
                }

                else -> buildString {
                    append("将处理 $n 处目标（你在准星取点/铺路页签点选的全部目标）")
                    when {
                        worldCount == n ->
                            append("，均含世界坐标：到点会先对准镜头再点")
                        worldCount > 0 ->
                            append(
                                "；⚠️ 其中 ${n - worldCount} 处缺世界坐标。" +
                                    "为避免坐标错位，到点只会处理有世界坐标的 $worldCount 处，" +
                                    "建议先到「标定」页签补齐"
                            )
                        else ->
                            append("；⚠️ 未标定世界坐标，镜头一旦移动目标即失效，建议先到「标定」页签标定")
                    }
                }
            }
        }

        spType.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) = updateTargetHint()

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = updateTargetHint()
        }
        updateTargetHint()

        val dialog = AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(if (existing == null) "新建定时任务" else "编辑定时任务")
            .setView(form)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create()
        applyOverlayWindowType(dialog)
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val type = typeValues[spType.selectedItemPosition]
                val name = etName.text.toString().trim().ifBlank { typeLabels[spType.selectedItemPosition] }
                val time = "%02d:%02d".format(timePicker.hour, timePicker.minute)
                val targets = resolveTargets(type)
                val primary = targets.firstOrNull()
                val bookmark = etBookmark.text.toString().trim().ifBlank { null }

                if (type == TacticalState.TaskType.TACTICAL_SCHEDULE && primary == null && bookmark == null) {
                    Toast.makeText(
                        context,
                        "离线战术任务请至少填入「官方书签」或到「准星取点」点选地块",
                        Toast.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }

                // 后勤全托管不需要点击目标（它自己按书签/坐标对准主城），因此单独豁免；
                // 屯田打铁则"目标坐标或书签"至少要有一个。
                val needsTarget = when (type) {
                    TacticalState.TaskType.RAID_DEFENSE,
                    TacticalState.TaskType.NIGHT_SENTINEL,
                    TacticalState.TaskType.TACTICAL_SCHEDULE,
                    TacticalState.TaskType.LOGISTICS_STEWARD -> false
                    TacticalState.TaskType.FARMING_STEWARD -> primary == null && bookmark == null
                    else -> primary == null
                }
                if (needsTarget) {
                    val tabName = when (type) {
                        TacticalState.TaskType.SIEGE_SYNC -> "攻城"
                        TacticalState.TaskType.FARMING_STEWARD -> "屯田"
                        else -> "准星取点"
                    }
                    Toast.makeText(
                        context,
                        "请先到「$tabName」页签点选目标地块（或填入官方书签），再保存该任务",
                        Toast.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }

                // 卡秒偏移只在攻城任务里读取与校验；其它类型沿用原值（或默认 60 秒）。
                val rawOffset = if (type == TacticalState.TaskType.SIEGE_SYNC) {
                    etHitOffset.text.toString().trim().toLongOrNull()
                } else {
                    null
                }
                if (type == TacticalState.TaskType.SIEGE_SYNC &&
                    (rawOffset == null || rawOffset < 5L || rawOffset > 3600L)
                ) {
                    Toast.makeText(
                        context,
                        "卡秒偏移请填 5~3600 之间的整数秒（当前填的是「${etHitOffset.text}」）",
                        Toast.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }
                val hitOffset = rawOffset ?: existing?.hitOffsetSeconds ?: 60L
                val actionType = actionValues[spAction.selectedItemPosition]
                val troopSlot = etTroopSlot.text.toString().trim().toIntOrNull()?.coerceIn(1, 5) ?: 1
                val isImmunity = cbImmunityBreak.isChecked

                val mgr = com.stzb.assistant.tactics.ScheduledTaskManager
                val extra = mgr.encodeExtraTargets(targets)
                if (existing == null) {
                    mgr.addTask(
                        context, name, time, type, primary?.x, primary?.y,
                        hitOffsetSeconds = hitOffset,
                        targetWorldX = primary?.worldX, targetWorldY = primary?.worldY,
                        extraTargetsRaw = extra,
                        bookmarkName = bookmark,
                        actionType = actionType,
                        troopSlot = troopSlot,
                        isImmunityBreak = isImmunity
                    )
                } else {
                    mgr.updateTask(
                        context, existing.id, name, time, type,
                        primary?.x, primary?.y, hitOffset,
                        targetWorldX = primary?.worldX, targetWorldY = primary?.worldY,
                        extraTargetsRaw = extra,
                        bookmarkName = bookmark,
                        actionType = actionType,
                        troopSlot = troopSlot,
                        isImmunityBreak = isImmunity
                    )
                }
                if (type == TacticalState.TaskType.ROAD_PAVING && targets.size > 1) {
                    Toast.makeText(
                        context,
                        "已保存：到点将处理 ${targets.size} 块地",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                refreshScheduleDisplay()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    /**
     * Service 上下文创建的对话框必须声明为悬浮窗类型，否则会因缺少窗口令牌而无法显示。
     */
    private fun applyOverlayWindowType(dialog: AlertDialog) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        dialog.window?.setType(type)
    }

    /** 任务管理：启用/暂停、编辑、删除、清空全部。 */
    private fun showScheduleManagerDialog() {
        val mgr = com.stzb.assistant.tactics.ScheduledTaskManager
        val list = mgr.getTasks()
        if (list.isEmpty()) {
            Toast.makeText(context, "当前没有任何定时任务，点「＋ 新建定时任务」开始编排。", Toast.LENGTH_SHORT).show()
            return
        }

        val labels = list.map { t ->
            val tag = if (t.isEnabled) "已开启" else "已暂停"
            val target = when {
                t.taskType == TacticalState.TaskType.RAID_DEFENSE -> ""
                !t.hasTarget() -> " · ⚠️无目标"
                t.targetWorld() != null -> " · 有目标(世界坐标)"
                else -> " · 有目标(未标定世界坐标)"
            }
            "${t.timeStr}  ${t.name}\n${t.taskType.displayName} · $tag$target"
        }.toTypedArray()

        val dialog = AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("管理定时任务（共 ${list.size} 项）")
            .setItems(labels) { _, which -> showScheduleTaskActions(list[which]) }
            .setNeutralButton("清空全部") { _, _ ->
                mgr.clearAll(context)
                refreshScheduleDisplay()
                Toast.makeText(context, "已清空全部定时计划", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("关闭", null)
            .create()
        applyOverlayWindowType(dialog)
        dialog.show()
    }

    private fun showScheduleTaskActions(task: com.stzb.assistant.tactics.ScheduledTaskManager.ScheduledTask) {
        val mgr = com.stzb.assistant.tactics.ScheduledTaskManager
        val actions = arrayOf(
            if (task.isEnabled) "暂停此任务" else "启用此任务",
            "编辑此任务",
            "删除此任务"
        )
        val dialog = AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("${task.timeStr}  ${task.name}")
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> {
                        mgr.toggleTask(context, task.id, !task.isEnabled)
                        refreshScheduleDisplay()
                    }
                    1 -> showScheduleEditorDialog(task)
                    2 -> {
                        mgr.removeTask(context, task.id)
                        refreshScheduleDisplay()
                    }
                }
            }
            .setNegativeButton("返回", null)
            .create()
        applyOverlayWindowType(dialog)
        dialog.show()
    }

    private fun showAskAdvisorDialog() {
        val quickQueries = arrayOf(
            "⚔️ 开荒配将与低损开地攻略",
            "🛡️ 战报复盘：如何针对性变阵调优",
            "🎯 同盟攻城：主力与拆迁压秒触城时机",
            "⚡ 面对反计战必法刀，如何选队伍克制",
            "✍️ 自定义战机问策 (手动输入)"
        )

        val dialog = AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("💬 问策诸葛军师")
            .setItems(quickQueries) { _, which ->
                if (which == quickQueries.size - 1) {
                    showCustomQueryInputDialog()
                } else {
                    val cleanQuery = quickQueries[which].substring(2).trim()
                    submitAdvisorQuery(cleanQuery)
                }
            }
            .setNegativeButton("取消", null)
            .create()
        applyOverlayWindowType(dialog)
        dialog.show()
    }

    private fun showCustomQueryInputDialog() {
        val pad = dp(16)
        val form = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        val et = EditText(context).apply {
            hint = "向军师提问 (如: 周瑜陆逊吕蒙怎么配战法？)"
            isSingleLine = false
            maxLines = 4
        }
        form.addView(et)

        val dialog = AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("✍️ 军机问策")
            .setView(form)
            .setPositiveButton("推演") { _, _ ->
                val q = et.text.toString().trim()
                if (q.isNotBlank()) {
                    submitAdvisorQuery(q)
                }
            }
            .setNegativeButton("取消", null)
            .create()
        applyOverlayWindowType(dialog)
        dialog.show()
    }

    private fun submitAdvisorQuery(query: String) {
        val tvAdvisorStream = dashboardView?.findViewById<TextView>(R.id.tvAdvisorStream)
        val tvAdvisorMetrics = dashboardView?.findViewById<TextView>(R.id.tvAdvisorMetrics)

        tvAdvisorMetrics?.text = "状态: 军师推演中..."
        tvAdvisorStream?.text = "📜 诸葛军师正在调阅端侧 RAG 兵书并推演天机，请稍候...\n\n问策要点：$query"

        edgeSlmEngine.askAdvisor(query) { answer ->
            mainHandler.post {
                tvAdvisorStream?.text = answer
                tvAdvisorMetrics?.text = "状态: 军师推演完成 (${edgeSlmEngine.getBrainDescription()})"
            }
        }
    }

    private fun setupDashboardDrag() {
        val header = dashboardView?.findViewById<View>(R.id.dashboardHeader) ?: return
        var initX = 0
        var initY = 0
        var touchX = 0f
        var touchY = 0f
        header.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = dashboardParams.x
                    initY = dashboardParams.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    dashboardParams.x = initX + (event.rawX - touchX).toInt()
                    dashboardParams.y = initY + (event.rawY - touchY).toInt()
                    try {
                        windowManager.updateViewLayout(dashboardView, dashboardParams)
                    } catch (e: Exception) {
                        Log.e(TAG, "拖动控制面板异常: ${e.message}")
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    persistDashboardPosition()
                    true
                }
                else -> false
            }
        }
    }

    /**
     * 面板右下角拖角缩放。
     *
     * 交互：按住布局里的 `dashboardResizeGrip` 往右下拖，实时改 dashboardParams 的
     * width/height 并 updateViewLayout；松手后把尺寸连同位置写进 SharedPreferences，
     * 下次展开直接还原（见创建 dashboardParams 处读回 savedW/savedH 的逻辑）。
     *
     * 为什么 gravity 必须是 TOP|START：这样左边/上边固定，右下角就跟手 1:1。
     * 首次进入时高度是 WRAP_CONTENT（-2），拿不到确定像素，就用**已测量的 view 高度**
     * 作为起始值，一旦开始缩放就转成固定高度。
     */
    private fun setupDashboardResize() {
        val grip = dashboardView?.findViewById<View>(R.id.dashboardResizeGrip) ?: return
        val dm = context.resources.displayMetrics
        val minW = dp(240)
        var startW = 0
        var startH = 0
        var touchX = 0f
        var touchY = 0f
        grip.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // 首次以 WRAP_CONTENT 展开时，params.height 是 -2，用测量高度兜底并
                    // 顺手记下"内容自然高度"作为后续缩小的下限（见字段注释）。
                    val measured = dashboardView?.height ?: 0
                    if (dashboardParams.height <= 0 && measured > dashboardNaturalHeightPx) {
                        dashboardNaturalHeightPx = measured
                    }
                    startW = if (dashboardParams.width > 0) dashboardParams.width else (dashboardView?.width ?: dashboardParams.width)
                    startH = if (dashboardParams.height > 0) dashboardParams.height else measured
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val minH = maxOf(dp(200), dashboardNaturalHeightPx)
                    val maxH = (dm.heightPixels - dashboardParams.y).coerceAtLeast(minH)
                    dashboardParams.width = (startW + (event.rawX - touchX).toInt()).coerceIn(minW, dm.widthPixels)
                    dashboardParams.height = (startH + (event.rawY - touchY).toInt()).coerceIn(minH, maxH)
                    try {
                        windowManager.updateViewLayout(dashboardView, dashboardParams)
                    } catch (e: Exception) {
                        Log.e(TAG, "缩放控制面板异常: ${e.message}")
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    persistDashboardGeometry()
                    true
                }
                else -> false
            }
        }
    }

    /** 只存位置（拖动结束调用，不擅自把 WRAP_CONTENT 高度锁成固定值）。 */
    private fun persistDashboardPosition() {
        if (!::dashboardParams.isInitialized) return
        context.getSharedPreferences(PREFS_OVERLAY, Context.MODE_PRIVATE).edit()
            .putInt(KEY_DASH_X, dashboardParams.x)
            .putInt(KEY_DASH_Y, dashboardParams.y)
            .apply()
    }

    /** 存尺寸 + 位置（缩放结束调用）。此刻 width/height 都已是确定像素。 */
    private fun persistDashboardGeometry() {
        if (!::dashboardParams.isInitialized) return
        val w = if (dashboardParams.width > 0) dashboardParams.width else (dashboardView?.width ?: 0)
        val h = if (dashboardParams.height > 0) dashboardParams.height else (dashboardView?.height ?: 0)
        if (w <= 0 || h <= 0) return
        context.getSharedPreferences(PREFS_OVERLAY, Context.MODE_PRIVATE).edit()
            .putInt(KEY_DASH_W, w)
            .putInt(KEY_DASH_H, h)
            .putInt(KEY_DASH_X, dashboardParams.x)
            .putInt(KEY_DASH_Y, dashboardParams.y)
            .apply()
    }

    private fun showDashboard() {
        if (dashboardView?.parent == null) {
            try {
                windowManager.addView(dashboardView, dashboardParams)
            } catch (e: Exception) {
                Log.e(TAG, "展开控制面板异常: ${e.message}")
            }
        }
    }

    private fun hideDashboard() {
        if (dashboardView?.parent != null) {
            try {
                windowManager.removeView(dashboardView)
            } catch (e: Exception) {
                Log.e(TAG, "收起控制面板异常: ${e.message}")
            }
        }
    }

    // ==========================================
    // 3. 准星多点取点 (Crosshair Picker)
    // ==========================================

    private fun createPickerView() {
        val inflater = LayoutInflater.from(context)
        pickerView = inflater.inflate(R.layout.view_crosshair_picker, null)

        val flCrosshair = pickerView?.findViewById<FrameLayout>(R.id.flCrosshairContainer)
        val flMarkers = pickerView?.findViewById<FrameLayout>(R.id.flMarkersContainer)
        val tvCount = pickerView?.findViewById<TextView>(R.id.tvPickerCount)

        pickerView?.findViewById<Button>(R.id.btnCancelPicker)?.setOnClickListener {
            clearTempPicks()
            stopCrosshairPicker()
            showDashboard()
            // 「取消」同时充当"结束引导式标定"的出口：用户可能只有 3 支队伍，
            // 标到第 4 个槽位时按取消即可收工，已记录的部分全部保留。
            if (troopTabCalibSlot > 0 || rectCalibIndex >= 0 || calibTwoPointMode) {
                val hadProgress = troopTabCalibSlot > 0 || rectCalibIndex >= 0
                resetGuidedCalibration()
                refreshCalibrateDisplay()
                Toast.makeText(
                    context,
                    if (hadProgress) "已结束标定（已记录的部分已保存）" else "已取消标定",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        pickerView?.findViewById<Button>(R.id.btnPickerUndo)?.setOnClickListener {
            if (tempPickMarkers.isNotEmpty()) {
                val last = tempPickMarkers.removeAt(tempPickMarkers.size - 1)
                flMarkers?.removeView(last)
                tempPickPoints.removeAt(tempPickPoints.size - 1)
                tvCount?.text = "已选 ${tempPickPoints.size}"
            }
        }
        pickerView?.findViewById<Button>(R.id.btnPickerClear)?.setOnClickListener {
            clearTempPicks()
            tvCount?.text = "已选 0"
        }
        pickerView?.findViewById<Button>(R.id.btnPickerConfirm)?.setOnClickListener {
            commitPicks()
        }

        pickerView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    flCrosshair?.visibility = View.VISIBLE
                    val cw = flCrosshair?.width?.takeIf { it > 0 } ?: dp(60)
                    val ch = flCrosshair?.height?.takeIf { it > 0 } ?: dp(60)
                    flCrosshair?.x = event.rawX - cw / 2f
                    flCrosshair?.y = event.rawY - ch / 2f
                }
                MotionEvent.ACTION_UP -> {
                    addPickPoint(event.rawX, event.rawY)
                }
            }
            true
        }
    }

    private fun addPickPoint(rawX: Float, rawY: Float) {
        val virtualPoint = CoordinateTransformer.toVirtual(rawX, rawY)
        tempPickPoints.add(virtualPoint)

        val flMarkers = pickerView?.findViewById<FrameLayout>(R.id.flMarkersContainer)
        val size = dp(18)
        val marker = View(context)
        marker.background = ContextCompat.getDrawable(context, R.drawable.bg_pick_marker)
        val lp = FrameLayout.LayoutParams(size, size)
        // 标记点的 leftMargin/topMargin 基准是「容器原点」，不是「屏幕原点」。
        // 原实现直接把触摸的屏幕绝对坐标 rawX/rawY 当作 margin，
        // 一旦窗口容器原点与 display 原点不重合（状态栏内缩、挖孔、自由窗口），
        // 落点标记就会整体偏移。这里显式扣除容器在屏幕上的位置。
        val loc = IntArray(2)
        flMarkers?.getLocationOnScreen(loc)
        lp.leftMargin = (rawX - loc[0] - size / 2f).toInt()
        lp.topMargin = (rawY - loc[1] - size / 2f).toInt()
        flMarkers?.addView(marker, lp)
        tempPickMarkers.add(marker)

        pickerView?.findViewById<TextView>(R.id.tvPickerCount)?.text = "已选 ${tempPickPoints.size}"
    }

    private fun clearTempPicks() {
        val flMarkers = pickerView?.findViewById<FrameLayout>(R.id.flMarkersContainer)
        tempPickMarkers.forEach { flMarkers?.removeView(it) }
        tempPickMarkers.clear()
        tempPickPoints.clear()
    }

    /**
     * 从 OCR 结果里拼出用于语义解析的文本，但**排除 HUD 的当前坐标读数区域**。
     *
     * 为什么必须排除：`EdgeSlmEngine.extractCoordinates` 取的是**第一个**落在本游戏坐标界
     * （知识库 `map_coord_max`）内的"x y"匹配，而 HUD 右上角显示的正是**你自己当前的坐标**，
     * 格式与军令里的目标坐标一模一样。军师页喂进去的是整屏文本，HUD 又常驻画面，
     * 于是存在这样一条路径：**把"你所在的位置"当成"军令里的目标"**。
     *
     * 这类错误不会报错、也不会显得异常——它只会让你跑错地方。
     * 这正是"识别意图不准"的一种具体形态，所以在这里按已知几何把它剪掉。
     *
     * 坐标可以这样做的前提：捕获面本身就是**虚拟画布尺寸**，
     * 因此 OCR 的 boxPoint 与 `UiAnchors` 的矩形在同一坐标系里，无需换算。
     */
    private fun semanticTextExcludingHud(ocr: com.benjaminwan.ocrlibrary.OcrResult?): String {
        if (ocr == null) return ""
        val hud = UiAnchors.rect(UiAnchors.RectKey.HUD_WORLD_COORD)
        val sb = StringBuilder()
        for (b in ocr.textBlocks) {
            val pts = b.boxPoint
            if (pts.isEmpty()) continue
            val cx = pts.sumOf { it.x } / pts.size
            val cy = pts.sumOf { it.y } / pts.size
            if (hud.contains(cx, cy)) continue
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(b.text)
        }
        return sb.toString().trim()
    }

    // 说明：原先这里有一个 resolveWorldForType()，只取"第一个目标的世界坐标"。
    // 任务目标改为多目标之后它已无调用方，按本项目"死代码要删"的标准移除——
    // 留着一个看起来能用、实际会漏掉其余目标的入口，比删掉更危险。

    /**
     * 授权门禁。
     *
     * 开发模式下恒通过（行为与修复前一致，不会把你锁在门外）；
     * 把 `LicenseGate.DEVELOPMENT_MODE_OPEN_ACCESS` 改为 false 之后，
     * 未激活的凭证会被拦在这里，并给出明确原因。
     */
    private fun ensureLicense(): Boolean {
        if (com.stzb.assistant.license.LicenseGate.isExecutionAllowed(context)) return true
        val reason = com.stzb.assistant.license.LicenseGate.rejectionReason(context)
        Toast.makeText(context, reason, Toast.LENGTH_LONG).show()
        dashboardView?.findViewById<TextView>(R.id.tvAdvisorStream)?.text = "⛔ $reason"
        return false
    }

    /** 目标世界坐标的展示后缀；未标定时明确说明"镜头一动即失效"，而不是含糊带过。 */
    private fun worldSuffix(world: Pair<Int, Int>?): String =
        if (world != null) " | 世界坐标: (${world.first},${world.second})"
        else " | 世界坐标: 未标定（镜头移动后该屏幕坐标即失效，请到「标定」页签标定）"

    private fun commitPicks() {
        val pts = tempPickPoints.toList()
        // 先取出目标类型：stopCrosshairPicker() 会把它置空
        val target = currentPickTarget
        val formatPoints = { list: List<PointF> ->
            if (list.isEmpty()) "尚未点选"
            else list.joinToString("; ") { "(%.0f, %.0f)".format(it.x, it.y) }
        }
        when (target) {
            PickTarget.PAVING -> {
                pickedPavingPoints.clear(); pickedPavingPoints.addAll(pts)
                // 同步解析世界坐标（仅在标定过地图投影时可用）
                pickedPavingWorld.clear()
                pickedPavingWorld.addAll(
                    pts.mapNotNull { com.stzb.assistant.service.MapProjection.screenToWorld(it.x, it.y) }
                )
                val worldHint = when {
                    pts.isEmpty() -> ""
                    pickedPavingWorld.size == pts.size ->
                        " | 世界坐标: " + pickedPavingWorld.joinToString("; ") { "(${it.first},${it.second})" }
                    else ->
                        " | 世界坐标: 未标定地图比例（铺路将沿用屏幕坐标，镜头一动即失效）"
                }
                dashboardView?.findViewById<TextView>(R.id.tvPavingCoord)?.text =
                    if (pts.isEmpty()) "目标地块：尚未点选"
                    else "目标地块(${pts.size}块): ${formatPoints(pts)}$worldHint"
            }
            PickTarget.IMMUNITY -> {
                pickedImmunityPoints.clear(); pickedImmunityPoints.addAll(pts)
                pickedImmunityWorld = pts.firstOrNull()
                    ?.let { com.stzb.assistant.service.MapProjection.screenToWorld(it.x, it.y) }
                dashboardView?.findViewById<TextView>(R.id.tvImmunityCoord)?.text =
                    if (pts.isEmpty()) "卡免地块：尚未选择"
                    else "卡免地块: ${formatPoints(pts)}${worldSuffix(pickedImmunityWorld)}"
            }
            PickTarget.SIEGE -> {
                pickedSiegePoints.clear(); pickedSiegePoints.addAll(pts)
                pickedSiegeWorld = pts.firstOrNull()
                    ?.let { com.stzb.assistant.service.MapProjection.screenToWorld(it.x, it.y) }
                dashboardView?.findViewById<TextView>(R.id.tvSiegeCoord)?.text =
                    if (pts.isEmpty()) "集火城池：尚未点选"
                    else "集火城池: ${formatPoints(pts)}${worldSuffix(pickedSiegeWorld)}"
            }
            PickTarget.FARMING -> {
                val p = pts.firstOrNull()
                pickedFarmingPoint = p
                pickedFarmingWorld = p?.let { com.stzb.assistant.service.MapProjection.screenToWorld(it.x, it.y) }
                dashboardView?.findViewById<TextView>(R.id.tvFarmingTarget)?.text =
                    if (p == null) "屯田地块：尚未点选（优先 Lv.5+ 最高收益地）"
                    else "屯田地块: (${p.x.toInt()}, ${p.y.toInt()})${worldSuffix(pickedFarmingWorld)}"
            }
            PickTarget.GARRISON -> {
                val p = pts.firstOrNull()
                pickedGarrisonPoint = p
                pickedGarrisonWorld = p?.let { com.stzb.assistant.service.MapProjection.screenToWorld(it.x, it.y) }
                dashboardView?.findViewById<TextView>(R.id.tvGarrisonTargetCoord)?.text =
                    if (p == null) "目标: 未设定"
                    else "目标: (${p.x.toInt()}, ${p.y.toInt()})${worldSuffix(pickedGarrisonWorld)}"
            }
            PickTarget.LEVELING -> {
                val p = pts.firstOrNull()
                pickedLevelingPoint = p
                pickedLevelingWorld = p?.let { com.stzb.assistant.service.MapProjection.screenToWorld(it.x, it.y) }
                dashboardView?.findViewById<TextView>(R.id.tvLevelingTargetCoord)?.text =
                    if (p == null) "练级地: 未设定"
                    else "练级地: (${p.x.toInt()}, ${p.y.toInt()})${worldSuffix(pickedLevelingWorld)}"
            }
            PickTarget.CALIBRATION_BLANK -> {
                val p = pts.firstOrNull()
                if (p != null) {
                    com.stzb.assistant.service.UiAnchors.calibrate(
                        com.stzb.assistant.service.UiAnchors.Key.MAP_BLANK, p.x, p.y
                    )
                }
            }
            PickTarget.CALIBRATION -> {
                // 具体处理放到下面（要先恢复面板，再弹坐标输入框）
            }
            PickTarget.ANCHOR_POINT -> {
                val p = pts.firstOrNull()
                if (p != null && troopTabCalibSlot >= 1) {
                    UiAnchors.calibrate(UiAnchors.troopTabKey(troopTabCalibSlot), p.x, p.y)
                }
            }
            PickTarget.ANCHOR_RECT -> {
                val p = pts.firstOrNull()
                if (p != null && rectCalibIndex in rectCalibOrder.indices) {
                    val corner = rectCalibCorner
                    if (corner == null) {
                        // 第一个点：记为左上角，等右下角
                        rectCalibCorner = p
                    } else {
                        val regionLabel = rectCalibOrder[rectCalibIndex].label
                        val saved = UiAnchors.calibrateRect(
                            rectCalibOrder[rectCalibIndex],
                            minOf(corner.x, p.x).toInt(),
                            minOf(corner.y, p.y).toInt(),
                            maxOf(corner.x, p.x).toInt(),
                            maxOf(corner.y, p.y).toInt()
                        )
                        if (!saved) {
                            // 如实告知，而不是让用户以为"标定过了"：
                            // 过小的区域会永远读不到文字。
                            Toast.makeText(
                                context,
                                "【$regionLabel】范围太小，未保存（请把它的两个角拉得更开一些）",
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            Toast.makeText(context, "已记录【$regionLabel】", Toast.LENGTH_SHORT).show()
                        }
                        rectCalibCorner = null
                    }
                }
            }
            PickTarget.BUTTON_TEMPLATE -> {
                // 模板标定的实际处理在下方 when（要先恢复面板，再进入框选）
            }
            null -> {}
        }
        clearTempPicks()
        stopCrosshairPicker()
        showDashboard()

        when (target) {
            PickTarget.CALIBRATION -> {
                val p = pts.firstOrNull()
                if (p == null) {
                    Toast.makeText(context, "没有点选到地块，标定已取消", Toast.LENGTH_SHORT).show()
                } else {
                    handleCalibrationPick(p)
                }
            }
            PickTarget.CALIBRATION_BLANK -> {
                refreshCalibrateDisplay()
                Toast.makeText(context, "已记录地图空白点", Toast.LENGTH_SHORT).show()
            }
            PickTarget.ANCHOR_POINT -> {
                if (pts.isEmpty()) {
                    troopTabCalibSlot = 0
                    Toast.makeText(context, "未点选到位置，部队标签标定已中止", Toast.LENGTH_SHORT).show()
                } else {
                    val slot = troopTabCalibSlot
                    Toast.makeText(context, "已记录【部队$slot】标签", Toast.LENGTH_SHORT).show()
                    troopTabCalibSlot++
                    if (troopTabCalibSlot <= UiAnchors.troopTabSlotCount) {
                        Toast.makeText(
                            context,
                            "请点选【部队${troopTabCalibSlot}】标签的位置（没有这个队伍就按「取消」结束）",
                            Toast.LENGTH_LONG
                        ).show()
                        startCrosshairPicker(PickTarget.ANCHOR_POINT)
                    } else {
                        troopTabCalibSlot = 0
                        refreshCalibrateDisplay()
                        Toast.makeText(context, "部队标签标定完成", Toast.LENGTH_LONG).show()
                    }
                }
            }
            PickTarget.ANCHOR_RECT -> {
                if (pts.isEmpty()) {
                    rectCalibIndex = -1
                    rectCalibCorner = null
                    Toast.makeText(context, "未点选到位置，区域标定已中止", Toast.LENGTH_SHORT).show()
                } else if (rectCalibCorner == null) {
                    // 说明刚刚记完了右下角，推进到下一个区域
                    rectCalibIndex++
                    if (rectCalibIndex < rectCalibOrder.size) {
                        Toast.makeText(
                            context,
                            "标定【${rectCalibOrder[rectCalibIndex].label}】：请先点它的左上角",
                            Toast.LENGTH_LONG
                        ).show()
                        startCrosshairPicker(PickTarget.ANCHOR_RECT)
                    } else {
                        rectCalibIndex = -1
                        refreshCalibrateDisplay()
                        Toast.makeText(context, "识别区域标定完成", Toast.LENGTH_LONG).show()
                    }
                } else {
                    Toast.makeText(context, "已记左上角，请再点该区域的右下角", Toast.LENGTH_LONG).show()
                    startCrosshairPicker(PickTarget.ANCHOR_RECT)
                }
            }
            PickTarget.BUTTON_TEMPLATE -> {
                val type = templateCalibType
                templateCalibType = null
                val p = pts.firstOrNull()
                when {
                    type == null ->
                        Toast.makeText(context, "没有待登记的按键，已取消", Toast.LENGTH_SHORT).show()

                    p == null ->
                        Toast.makeText(context, "未点选到位置，按键模板登记已中止", Toast.LENGTH_SHORT).show()

                    else -> {
                        // 抓当前帧来裁模板：此刻游戏仍停在按键所在界面（十字准星只是覆盖层）。
                        val frame = EngineBridge.captureFrame()
                        if (frame == null) {
                            Toast.makeText(
                                context,
                                "抓屏失败，无法登记模板。请先确认屏幕捕获通道在运行。",
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            val ok = try {
                                com.stzb.assistant.service.ButtonTemplateStore.saveFromFrame(
                                    type, frame, p.x.toInt(), p.y.toInt()
                                )
                            } catch (e: Exception) {
                                Log.w(TAG, "登记按键模板异常: ${e.message}")
                                false
                            } finally {
                                frame.recycle()
                            }
                            Toast.makeText(
                                context,
                                if (ok) "已登记【${type.primaryKeyword}】的按键模板：" +
                                    "此后 OCR 不可用也能定位它"
                                else "登记失败：该位置太靠近画面边缘，或该处是纯色、没有可匹配的特征",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        refreshCalibrateDisplay()
                    }
                }
            }
            else -> Toast.makeText(context, "已确认 ${pts.size} 个地块虚拟坐标", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startCrosshairPicker(target: PickTarget) {
        currentPickTarget = target
        clearTempPicks()
        pickerView?.findViewById<TextView>(R.id.tvPickerCount)?.text = "已选 0"
        hideDashboard()
        if (pickerView?.parent == null) {
            try {
                windowManager.addView(pickerView, pickerParams)
            } catch (e: Exception) {
                Log.e(TAG, "开启准星取点异常: ${e.message}")
            }
        }
    }

    private fun stopCrosshairPicker() {
        currentPickTarget = null
        if (pickerView?.parent != null) {
            try {
                windowManager.removeView(pickerView)
            } catch (e: Exception) {
                Log.e(TAG, "关闭准星取点异常: ${e.message}")
            }
        }
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics
    ).toInt()

    // ==========================================
    // 4. 无人托管 (AutoPilot)
    // ==========================================

    /**
     * 无人托管 (AutoPilot) 的控制器。
     * 状态显示用的 [autoPilotListener] 声明在本文件上方（必须早于 init 块）。
     */
    private fun currentIntents(): com.stzb.assistant.tactics.AutoPilot.Intents =
        com.stzb.assistant.tactics.AutoPilot.Intents(
            raidDefense = true,
            pavingTargets = pickedPavingPoints.toList(),
            pavingWorldTargets = pickedPavingWorld.toList(),
            siegeTarget = pickedSiegePoints.firstOrNull(),
            siegeWorldTarget = pickedSiegeWorld,
            siegeHitEpochMs = 0L,
            dailyLogistics = true,
            farmingTarget = pickedFarmingPoint,
            farmingWorldTarget = pickedFarmingWorld,
            farmingBookmark = null,
            farmingTroopSlot = 2,
            autoFarmingEnabled = (pickedFarmingPoint != null || pickedFarmingWorld != null),
            autoLevelingEnabled = (pickedLevelingPoint != null || pickedLevelingWorld != null),
            levelingTarget = pickedLevelingPoint,
            levelingWorldTarget = pickedLevelingWorld,
            levelingBookmark = null,
            levelingSlotA = 2,
            levelingSlotB = 3,
            levelingTileLevel = 7
        )

    private fun toggleAutoPilot() {
        val pilot = com.stzb.assistant.tactics.AutoPilot
        if (pilot.isRunning) {
            pilot.stop(pipeline)
            Toast.makeText(context, "无人托管已停止", Toast.LENGTH_SHORT).show()
            return
        }
        if (!ensureLicense()) return
        when (val result = pilot.start(pipeline) { currentIntents() }) {
            is com.stzb.assistant.tactics.AutoPilot.StartResult.Started -> {
                Toast.makeText(context, "无人托管已开启：自动感知并执行", Toast.LENGTH_SHORT).show()
            }
            is com.stzb.assistant.tactics.AutoPilot.StartResult.Rejected -> {
                // 拒绝启动必须说明原因：否则用户只会看到"点了没反应"，
                // 又会回到"不知道是不是坐标问题"的猜测里。
                dashboardView?.findViewById<TextView>(R.id.tvAdvisorStream)?.text =
                    "⚠️ 无法开启无人托管\n${result.reason}"
                Toast.makeText(context, "无法开启无人托管，原因已显示在军师面板", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ==========================================
    // 5. 战术流水线事件联动
    // ==========================================

    override fun onStatusChanged(taskType: TacticalState.TaskType, status: TacticalState.Status, detail: String) {
        mainHandler.post {
            val (indicatorColor, statusEmoji) = when (status) {
                TacticalState.Status.RUNNING -> Pair(0xFF29B6F6.toInt(), "⚡")
                TacticalState.Status.WAITING_COUNTDOWN -> Pair(0xFFFFCA28.toInt(), "⏳")
                TacticalState.Status.COMPLETED -> Pair(0xFF00E676.toInt(), "🎉")
                TacticalState.Status.FAILED, TacticalState.Status.INTERRUPTED -> Pair(0xFFE53935.toInt(), "⏹️")
                else -> Pair(0xFF00E676.toInt(), "🟢")
            }

            capsuleView?.findViewById<View>(R.id.vStatusIndicator)?.setBackgroundColor(indicatorColor)
            capsuleView?.findViewById<TextView>(R.id.tvCapsuleTitle)?.text = "$statusEmoji ${taskType.displayName}"
            capsuleView?.findViewById<TextView>(R.id.tvCapsuleSubtitle)?.text = detail

            // 保持军师面板文本的持久性，仅在状态变化时更新 metrics 状态，不抹除军令与战报诊断流
            val stream = dashboardView?.findViewById<TextView>(R.id.tvAdvisorStream)
            if (stream?.text.isNullOrBlank()) {
                val patrolRunning = pipeline.isRaidPatrolActive
                val advisorThought = edgeSlmEngine.generateAdvisorLiveStream(
                    detail, lastExtractedOrder, patrolRunning
                )
                stream?.text = "【军师推演】$advisorThought"
            }

            if (taskType == TacticalState.TaskType.SQUAD_LEVELING) {
                dashboardView?.findViewById<TextView>(R.id.tvLevelingLiveStatus)?.text = "流水线状态: $detail"
            }
        }
    }

    override fun onLogEmitted(log: TacticalState.TacticalLog) {
        mainHandler.post {
            val tvLogs = dashboardView?.findViewById<TextView>(R.id.tvInGameLogs) ?: return@post
            val current = tvLogs.text.toString()
            tvLogs.text = "[${log.level}] ${log.message}\n$current"

            if (log.taskType == TacticalState.TaskType.GARRISON_RADAR && log.message.contains("透视")) {
                dashboardView?.findViewById<TextView>(R.id.tvGarrisonHudCard)?.text = log.message
            }
            if (log.taskType == TacticalState.TaskType.SOFT_TILE_RADAR && (log.message.contains("雷达") || log.message.contains("发现守军"))) {
                dashboardView?.findViewById<TextView>(R.id.tvSoftTileLeaderboard)?.text = log.message
            }
        }
    }

    fun destroy() {
        pipeline.unregisterListener(this)
        com.stzb.assistant.tactics.ScheduledTaskManager.unregisterListener(scheduleChangeListener)
        com.stzb.assistant.tactics.AutoPilot.unregisterListener(autoPilotListener)
        // 关闭悬浮胶囊即视为用户显式收回控制权：同时停掉无人托管，
        // 避免出现"界面已关、程序仍在自动点击"且无处可停的状态。
        com.stzb.assistant.tactics.AutoPilot.stop(pipeline)
        hideDashboard()
        clearTempPicks()
        stopCrosshairPicker()
        // 取消本组件派发出去的协程（免战检测/军令执行/书签测试），
        // 避免窗口已销毁后仍有回调去碰已移除的 View。
        scope.cancel()
        if (capsuleView?.parent != null) {
            try {
                windowManager.removeView(capsuleView)
            } catch (e: Exception) {
                Log.w(TAG, "销毁胶囊异常: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "OverlayWindowManager"

        /**
         * 展开式战术面板宽度占屏幕宽度的比例。
         *
         * 历史值：XML 根节点写 320dp，但 `inflate(layout, null)` 不会生成根 LayoutParams，
         * 窗口用的是 WRAP_CONTENT，所以那个 320dp 从未生效；真实宽度由 Tab 条内容撑开，
         * 实测约占屏宽 79%。此处显式收窄三分之一后固化为比例常量，
         * 既满足「减小三分之一」的需求，又让面板宽度在所有机型上可预测。
         */
        private const val DASHBOARD_WIDTH_RATIO = 0.53f

        /**
         * 面板尺寸/位置持久化：用户拖角缩放或拖动过之后，下次展开按上次的大小和位置还原，
         * 而不是每次都退回 0.53 的默认宽度。没存过时读到 0 / -1，走默认值。
         */
        private const val PREFS_OVERLAY = "overlay_dashboard"
        private const val KEY_DASH_W = "dashboard_width_px"
        private const val KEY_DASH_H = "dashboard_height_px"
        private const val KEY_DASH_X = "dashboard_x"
        private const val KEY_DASH_Y = "dashboard_y"
    }
}
