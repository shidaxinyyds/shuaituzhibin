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
import com.stzb.assistant.R
import com.stzb.assistant.ocr.StzbUiMatcher
import com.stzb.assistant.service.CoordinateTransformer
import com.stzb.assistant.service.EngineBridge
import com.stzb.assistant.service.UiAnchors
import com.stzb.assistant.tactics.ImmunityBreakFlow
import com.stzb.assistant.tactics.RaidDefenseFlow
import com.stzb.assistant.tactics.RoadPavingFlow
import com.stzb.assistant.tactics.SiegeSyncFlow
import com.stzb.assistant.tactics.TacticalPipeline
import com.stzb.assistant.tactics.TacticalState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
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

    private var capsuleView: View? = null
    private var dashboardView: View? = null
    private var pickerView: View? = null

    private lateinit var capsuleParams: WindowManager.LayoutParams
    private lateinit var dashboardParams: WindowManager.LayoutParams
    private lateinit var pickerParams: WindowManager.LayoutParams

    // 各战术已确认的目标地块（720p 归一化虚拟坐标序列）
    private val pickedPavingPoints = mutableListOf<PointF>()
    private val pickedImmunityPoints = mutableListOf<PointF>()
    private val pickedSiegePoints = mutableListOf<PointF>()

    /**
     * 与 [pickedPavingPoints] 一一对应的**世界坐标**（大地图格坐标）。
     *
     * 只有在完成过地图投影标定时才会有值。有值时铺路流程会先把镜头对准每一格再点，
     * 目标不会因为镜头移动而失效——这正是"取点得到的是一次性屏幕点"这一缺陷的解。
     */
    private val pickedPavingWorld = mutableListOf<Pair<Int, Int>>()

    /** 卡免目标的世界坐标（标定后才会有值）。 */
    private var pickedImmunityWorld: Pair<Int, Int>? = null

    /** 攻城目标的世界坐标（标定后才会有值）。 */
    private var pickedSiegeWorld: Pair<Int, Int>? = null

    // 端侧认知微脑（军令/战报的规则解析）
    private val edgeSlmEngine = com.stzb.assistant.ai.microbrain.EdgeSlmEngine(context)
    private var lastExtractedOrder: com.stzb.assistant.ai.microbrain.TacticalOrder? = null

    // 说明：这里刻意**不再持有 DualTrackSafetyGate**。
    // 该守门员在 verifyAndDispatch 里会把军令解析出的世界坐标 (x,y) 丢弃，
    // 改用"屏幕正中"作为点击目标（其内部注释自称"世界坐标与屏幕像素解耦"），
    // 这正是"识别到意图却点错地方"的元凶之一。目标坐标改为由用户点选后
    // 直接交给流水线，不再经过这个会改写目标的环节。
    // 详见 DualTrackSafetyGate.kt 顶部的停用说明。

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
        PAVING, IMMUNITY, SIEGE,
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

    init {
        pipeline.registerListener(this)
        com.stzb.assistant.knowledge.KnowledgeBaseManager.registerListener(profileChangeListener)
        com.stzb.assistant.tactics.ScheduledTaskManager.registerListener(scheduleChangeListener)
        com.stzb.assistant.tactics.ScheduledTaskManager.init(context, pipeline)
        com.stzb.assistant.tactics.AutoPilot.registerListener(autoPilotListener)

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

        // 面板宽度：原先用 WRAP_CONTENT，实际宽度由「7 个 Tab 横向排布」撑开，
        // 在真机上约等于屏宽的 79%（实测截图 2151/2712 px），既过宽又不可预测。
        // 现在改为「屏宽 × 固定比例」的确定性宽度，并按需求整体收窄三分之一：
        //     0.793 × 2/3 ≈ 0.53
        // 这样面板在任何机型上都保持同一视觉占比，不再随文案长度忽宽忽窄。
        val screenWidth = context.resources.displayMetrics.widthPixels
        val panelWidth = (screenWidth * DASHBOARD_WIDTH_RATIO).toInt()

        dashboardParams = WindowManager.LayoutParams(
            panelWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            // 用 CENTER_HORIZONTAL 精确居中，取代原先写死的 x = (屏宽 - 356) / 2。
            // 那个 356 是「以为面板只有 356px 宽」的错误假设：面板实际远宽于它，
            // 于是整体右偏，右边缘被推出屏幕（最右的「日志」Tab 与「停止全部」被裁掉）。
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = dp(35) // 靠近顶部放置，避免遮挡游戏底部主力队伍栏与操作菜单
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

        val tabPaving = dashboardView?.findViewById<Button>(R.id.tabPaving)
        val tabImmunity = dashboardView?.findViewById<Button>(R.id.tabImmunity)
        val tabSiege = dashboardView?.findViewById<Button>(R.id.tabSiege)
        val tabPatrol = dashboardView?.findViewById<Button>(R.id.tabPatrol)
        val tabAdvisor = dashboardView?.findViewById<Button>(R.id.tabAdvisor)
        val tabSchedule = dashboardView?.findViewById<Button>(R.id.tabSchedule)
        val tabLogs = dashboardView?.findViewById<Button>(R.id.tabLogs)
        val tabCalibrate = dashboardView?.findViewById<Button>(R.id.tabCalibrate)

        val panelPaving = dashboardView?.findViewById<LinearLayout>(R.id.panelPaving)
        val panelImmunity = dashboardView?.findViewById<LinearLayout>(R.id.panelImmunity)
        val panelSiege = dashboardView?.findViewById<LinearLayout>(R.id.panelSiege)
        val panelPatrol = dashboardView?.findViewById<LinearLayout>(R.id.panelPatrol)
        val panelAdvisor = dashboardView?.findViewById<LinearLayout>(R.id.panelAdvisor)
        val panelSchedule = dashboardView?.findViewById<LinearLayout>(R.id.panelSchedule)
        val panelLogs = dashboardView?.findViewById<LinearLayout>(R.id.panelLogs)
        val panelCalibrate = dashboardView?.findViewById<LinearLayout>(R.id.panelCalibrate)

        val tabs = listOf(tabPaving, tabImmunity, tabSiege, tabPatrol, tabAdvisor, tabSchedule, tabLogs, tabCalibrate)
        val panels = listOf(panelPaving, panelImmunity, panelSiege, panelPatrol, panelAdvisor, panelSchedule, panelLogs, panelCalibrate)

        fun switchTab(index: Int) {
            panels.forEachIndexed { i, p -> p?.visibility = if (i == index) View.VISIBLE else View.GONE }
            tabs.forEachIndexed { i, t ->
                t?.setBackgroundResource(if (i == index) R.drawable.bg_tab_active else R.drawable.bg_tab_inactive)
                t?.setTextColor(if (i == index) Color.WHITE else 0xFFB0BEC5.toInt())
            }
            when (index) {
                5 -> refreshScheduleDisplay()
                7 -> refreshCalibrateDisplay()
            }
        }

        tabPaving?.setOnClickListener { switchTab(0) }
        tabImmunity?.setOnClickListener { switchTab(1) }
        tabSiege?.setOnClickListener { switchTab(2) }
        tabPatrol?.setOnClickListener { switchTab(3) }
        tabAdvisor?.setOnClickListener { switchTab(4) }
        tabSchedule?.setOnClickListener { switchTab(5) }
        tabLogs?.setOnClickListener { switchTab(6) }
        tabCalibrate?.setOnClickListener { switchTab(7) }

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

        // 2. 卡免
        root.findViewById<Button>(R.id.btnPickImmunityTile)?.setOnClickListener {
            startCrosshairPicker(PickTarget.IMMUNITY)
        }
        root.findViewById<Button>(R.id.btnExecBreakImmunity)?.setOnClickListener {
            if (!ensureLicense()) return@setOnClickListener
            if (!ensureEngineReady()) return@setOnClickListener
            val target = pickedImmunityPoints.firstOrNull()
            if (target == null) {
                Toast.makeText(context, "请先点选要卡免的目标地块", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            pipeline.startImmunityBreak(
                ImmunityBreakFlow.ImmunityConfig(
                    mode = ImmunityBreakFlow.ImmunityMode.BREAK_IMMUNITY,
                    targetTileCoord = target,
                    designatedTroopSlot = tacticalDefaults().immunityDefaultTroopSlot,
                    targetWorldCoord = pickedImmunityWorld
                )
            )
            hideDashboard()
        }

        // 3. 攻城
        root.findViewById<Button>(R.id.btnPickSiegeCity)?.setOnClickListener {
            startCrosshairPicker(PickTarget.SIEGE)
        }
        root.findViewById<Button>(R.id.btnExecSiegeSync)?.setOnClickListener {
            if (!ensureLicense()) return@setOnClickListener
            if (!ensureEngineReady()) return@setOnClickListener
            val target = pickedSiegePoints.firstOrNull()
            if (target == null) {
                Toast.makeText(context, "请先点选要集火的城池/要塞", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            pipeline.startSiegeSync(
                SiegeSyncFlow.SiegeConfig(
                    cityVirtualCoord = target,
                    targetBaseHitEpochMs = System.currentTimeMillis() + 60 * 1000L,
                    mainSquadSlot = tacticalDefaults().siegeMainSquadSlot,
                    demolitionSlots = tacticalDefaults().siegeDemolitionSlots,
                    latencyCompensationMs = activeRules().immunityPaddingMs,
                    cityWorldCoord = pickedSiegeWorld
                )
            )
            hideDashboard()
        }

        // 4. 深夜巡检
        root.findViewById<Button>(R.id.btnExecRaidPatrol)?.setOnClickListener {
            if (!ensureLicense()) return@setOnClickListener
            if (!ensureEngineReady()) return@setOnClickListener
            pipeline.startRaidDefense(
                RaidDefenseFlow.DefenseConfig(
                    counterAttackSquadSlot = tacticalDefaults().immunityDefaultTroopSlot,
                    enableAudioAlarm = tacticalDefaults().raidAlarmSound,
                    enableDecisionC = tacticalDefaults().raidDecisionCAutoCounter
                )
            )
            hideDashboard()
        }

        // 5. 停止当前 / 停止全部
        root.findViewById<Button>(R.id.btnStopCurrent)?.setOnClickListener {
            pipeline.stopCurrentTask()
            Toast.makeText(context, "已停止当前任务", Toast.LENGTH_SHORT).show()
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
        // 这条链路此前**没有任何调用方**：`DefenderEvaluator` 与
        // `EngineBridge.evaluateDefenderPanel` 都写好了，却没人用（需求 5 里的"摆设"）。
        // 现在给军师页一个手动入口——刻意做成**手动**而不是自动：
        // 它依赖先打开「查看守军」面板，自动去做会在错误的界面读出无关文字。
        root.findViewById<Button>(R.id.btnAdvisorEvaluateDefenders)?.setOnClickListener {
            if (!ensureEngineReady()) return@setOnClickListener
            val roi = com.stzb.assistant.service.UiAnchors.rect(
                com.stzb.assistant.service.UiAnchors.RectKey.DEFENDER_PANEL
            )
            val result = EngineBridge.evaluateDefenderPanel(roi)
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
                }
                tvAdvisorMetrics?.text =
                    "状态: 守军评估完成（识别到 ${result.matchedDefenders.size} 名守将）"
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
            tvAdvisorStream?.text = buildString {
                append("📜【军令已解析】目标【${order.targetName}】")
                append("(${order.targetCoord?.first ?: "-"}, ${order.targetCoord?.second ?: "-"})\n")
                append(order.advisorThinking)
                append("\n— 识别原文: ")
                append(decreeText.take(120))
            }
            val brainDesc = edgeSlmEngine.getBrainDescription()
            tvAdvisorMetrics?.text = "状态: 军令解析完成 ($brainDesc)"

            // 若云端大模型处于激活状态，异步获取军师深度战略提炼
            if (com.stzb.assistant.ai.advisor.MilitaryAdvisorCloudBridge.isCloudAiActive(context)) {
                com.stzb.assistant.ai.advisor.MilitaryAdvisorCloudBridge.interpretAllianceDecree(
                    context = context,
                    decreeText = decreeText,
                    parsedTargetName = order.targetName,
                    parsedCoord = order.targetCoord,
                    parsedTimeMs = order.targetTime
                ) { success, cloudInterpretation ->
                    if (success) {
                        mainHandler.post {
                            tvAdvisorStream?.text = buildString {
                                append("📜【诸葛军师 · 云端战略洞察】\n")
                                append(cloudInterpretation).append("\n\n")
                                append("▶ 战术指令: 目标【${order.targetName}】")
                                append("(${order.targetCoord?.first ?: "-"}, ${order.targetCoord?.second ?: "-"})\n")
                                append(order.advisorThinking)
                            }
                        }
                    }
                }
            }
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

        // 7. 定时任务：计划完全由用户编排，工程内不预置任何任务
        root.findViewById<Button>(R.id.btnAddScheduleTask)?.setOnClickListener {
            showScheduleEditorDialog(existing = null)
        }

        root.findViewById<Button>(R.id.btnResetScheduleDefaults)?.setOnClickListener {
            showScheduleManagerDialog()
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
                "${t.primaryKeyword}（${t.name}）" + if (store.has(t)) " ✓ 已登记" else ""
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

        root.findViewById<Button>(R.id.btnCalibReset)?.setOnClickListener {
            com.stzb.assistant.service.MapProjection.clearCalibration()
            com.stzb.assistant.service.UiAnchors.clearAllCalibration()
            com.stzb.assistant.service.SceneFingerprint.clear()
            com.stzb.assistant.service.ButtonTemplateStore.clearAll()
            resetGuidedCalibration()
            refreshCalibrateDisplay()
            Toast.makeText(context, "已清除全部标定，回到默认值", Toast.LENGTH_SHORT).show()
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
            append("模板：").append(if (store.has(type)) "已登记" else "未登记").append("\n\n")

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
        }
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
                    task.taskType == TacticalState.TaskType.RAID_DEFENSE -> ""
                    !task.hasTarget() -> " · ⚠️无目标（到点将跳过而不是乱点）"
                    task.targetWorld() != null -> {
                        val w = task.targetWorld()!!
                        " · 目标(${task.targetX?.toInt()}, ${task.targetY?.toInt()}) 世界(${w.first},${w.second})$countTag"
                    }
                    else -> " · 目标(${task.targetX?.toInt()}, ${task.targetY?.toInt()}) ⚠️未标定世界坐标$countTag"
                }
                sb.append("${index + 1}. [${task.timeStr}] ${task.name}\n")
                // 攻城的卡秒偏移必须显示出来，否则"设了 90 秒"在界面上看不出任何区别。
                val timingTag = if (task.taskType == TacticalState.TaskType.SIEGE_SYNC) {
                    " · 卡秒偏移 ${task.hitOffsetSeconds}s"
                } else {
                    ""
                }
                sb.append("   ${task.taskType.displayName} $statusTag$targetTag$timingTag\n")
            }
            tvList?.text = sb.toString().trimEnd()
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
        val typeLabels = arrayOf("深夜巡检守护", "自动铺路翻地", "同盟攻城集火")
        val typeValues = arrayOf(
            TacticalState.TaskType.RAID_DEFENSE,
            TacticalState.TaskType.ROAD_PAVING,
            TacticalState.TaskType.SIEGE_SYNC
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

        // 攻城卡秒偏移：任务触发后经过多少秒发动总攻。
        // 此前这个值硬编码为 60 秒、界面完全看不到，用户无法表达"要压到 21:00:00 整"。
        // 只在攻城任务里出现，其余类型隐藏并沿用原值。
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
         *
         * 原先只取 `firstOrNull()`，于是"用户在铺路页签点了 5 块地、建了定时任务"
         * 到点只铺 1 块，而且界面没有任何提示。现在整体带走。
         * 铺路的世界坐标列表与点位列表本就一一对应，这里按同一下标取，不会错位。
         */
        fun resolveTargets(
            type: TacticalState.TaskType
        ): List<com.stzb.assistant.tactics.ScheduledTaskManager.TargetPoint> {
            // 注意：TargetPoint 是 object ScheduledTaskManager 的**嵌套类**，
            // 只能通过类名限定访问，不能通过实例引用（如 `val mgr = ScheduledTaskManager; mgr.TargetPoint`）——
            // 后者 Kotlin 会报 "Classifier accessed via instance reference"。
            return when (type) {
                TacticalState.TaskType.ROAD_PAVING -> pickedPavingPoints.mapIndexed { i, p ->
                    val w = pickedPavingWorld.getOrNull(i)
                    com.stzb.assistant.tactics.ScheduledTaskManager.TargetPoint(p.x, p.y, w?.first, w?.second)
                }

                TacticalState.TaskType.SIEGE_SYNC -> pickedSiegePoints.firstOrNull()?.let { p ->
                    // 攻城是集火单一城池，只保留第一座
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

            // 卡秒偏移只对攻城有意义，其它类型直接隐藏，避免留下一个"填了也没用"的输入框。
            val isSiege = type == TacticalState.TaskType.SIEGE_SYNC
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
                type == TacticalState.TaskType.RAID_DEFENSE ->
                    "巡检守护不需要目标地块。"

                targets.isEmpty() ->
                    "⚠️ 该类型需要目标地块。请先到对应页签点选地块；" +
                        "未设目标的任务到点会被跳过，而不是乱点一处。"

                type == TacticalState.TaskType.SIEGE_SYNC -> {
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
                    append("将处理 $n 块地（你在「铺路」页签点选的全部目标）")
                    when {
                        worldCount == n ->
                            append("，均含世界坐标：到点会先对准镜头再点")
                        worldCount > 0 ->
                            append(
                                "；⚠️ 其中 ${n - worldCount} 块缺世界坐标。" +
                                    "为避免坐标错位，到点只会处理有世界坐标的 $worldCount 块，" +
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

                if (type != TacticalState.TaskType.RAID_DEFENSE && primary == null) {
                    val tabName = if (type == TacticalState.TaskType.ROAD_PAVING) "铺路" else "攻城"
                    Toast.makeText(
                        context,
                        "请先到「$tabName」页签点选目标地块，再保存该任务",
                        Toast.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }

                // 卡秒偏移只在攻城任务里读取与校验；其它类型沿用原值（或默认 60 秒）。
                // 这里刻意对非法输入**明确报错并中止保存**，而不是悄悄回落到 60 秒——
                // 悄悄回落会让用户以为自己设的 90 秒生效了，到点却按 60 秒打。
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

                val mgr = com.stzb.assistant.tactics.ScheduledTaskManager
                val extra = mgr.encodeExtraTargets(targets)
                if (existing == null) {
                    mgr.addTask(
                        context, name, time, type, primary?.x, primary?.y,
                        hitOffsetSeconds = hitOffset,
                        targetWorldX = primary?.worldX, targetWorldY = primary?.worldY,
                        extraTargetsRaw = extra
                    )
                } else {
                    mgr.updateTask(
                        context, existing.id, name, time, type,
                        primary?.x, primary?.y, hitOffset,
                        targetWorldX = primary?.worldX, targetWorldY = primary?.worldY,
                        extraTargetsRaw = extra
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
                MotionEvent.ACTION_UP -> true
                else -> false
            }
        }
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
     * 为什么必须排除：`EdgeSlmEngine.extractCoordinates` 取的是**第一个**落在 1..1500
     * 的"x y"匹配，而 HUD 右上角显示的正是**你自己当前的坐标**，
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
            siegeHitEpochMs = 0L
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
                // 把"敌袭巡检守护是否真的在跑"如实传进去：
                // 引擎自己无从得知，不传的话它只能编一句"雷达哨兵保持巡查"。
                val patrolRunning =
                    pipeline.currentTaskType == TacticalState.TaskType.RAID_DEFENSE
                val advisorThought = edgeSlmEngine.generateAdvisorLiveStream(
                    detail, lastExtractedOrder, patrolRunning
                )
                stream?.text = "【军师推演】$advisorThought"
            }
        }
    }

    override fun onLogEmitted(log: TacticalState.TacticalLog) {
        mainHandler.post {
            val tvLogs = dashboardView?.findViewById<TextView>(R.id.tvInGameLogs) ?: return@post
            val current = tvLogs.text.toString()
            tvLogs.text = "[${log.level}] ${log.message}\n$current"
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
    }
}
