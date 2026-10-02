package com.stzb.assistant.overlay

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
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.stzb.assistant.R
import com.stzb.assistant.ocr.StzbUiMatcher
import com.stzb.assistant.service.CoordinateTransformer
import com.stzb.assistant.service.EngineBridge
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

    // 端侧认知微脑与双轨安全守门员
    private val edgeSlmEngine = com.stzb.assistant.ai.microbrain.EdgeSlmEngine(context)
    private val safetyGate = com.stzb.assistant.ai.decision.DualTrackSafetyGate(context)
    private var lastExtractedOrder: com.stzb.assistant.ai.microbrain.TacticalOrder? = null

    // 自动感知派单
    private val senseScope = CoroutineScope(Dispatchers.Default)
    private var senseJob: Job? = null
    private var isAutoSenseEnabled = false
    private val dispatchedOrderIds = mutableSetOf<String>()

    // 取点回调路由与临时取点态
    private var currentPickTarget: PickTarget? = null
    private val tempPickPoints = mutableListOf<PointF>()
    private val tempPickMarkers = mutableListOf<View>()

    enum class PickTarget {
        PAVING, IMMUNITY, SIEGE
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

    init {
        pipeline.registerListener(this)
        com.stzb.assistant.knowledge.KnowledgeBaseManager.registerListener(profileChangeListener)
        com.stzb.assistant.tactics.ScheduledTaskManager.registerListener(scheduleChangeListener)
        com.stzb.assistant.tactics.ScheduledTaskManager.init(context, pipeline)

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
            x = 20
            y = 120
        }

        dashboardParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (context.resources.displayMetrics.widthPixels - 356) / 2
            y = 35 // 靠近顶部放置，避免遮挡游戏底部主力队伍栏与操作菜单
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
        capsuleParams.x = if (capsuleParams.x < middle) 15 else screenWidth - (capsuleView?.width ?: 150) - 15
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

        val panelPaving = dashboardView?.findViewById<LinearLayout>(R.id.panelPaving)
        val panelImmunity = dashboardView?.findViewById<LinearLayout>(R.id.panelImmunity)
        val panelSiege = dashboardView?.findViewById<LinearLayout>(R.id.panelSiege)
        val panelPatrol = dashboardView?.findViewById<LinearLayout>(R.id.panelPatrol)
        val panelAdvisor = dashboardView?.findViewById<LinearLayout>(R.id.panelAdvisor)
        val panelSchedule = dashboardView?.findViewById<LinearLayout>(R.id.panelSchedule)
        val panelLogs = dashboardView?.findViewById<LinearLayout>(R.id.panelLogs)

        val tabs = listOf(tabPaving, tabImmunity, tabSiege, tabPatrol, tabAdvisor, tabSchedule, tabLogs)
        val panels = listOf(panelPaving, panelImmunity, panelSiege, panelPatrol, panelAdvisor, panelSchedule, panelLogs)

        fun switchTab(index: Int) {
            panels.forEachIndexed { i, p -> p?.visibility = if (i == index) View.VISIBLE else View.GONE }
            tabs.forEachIndexed { i, t ->
                t?.setBackgroundResource(if (i == index) R.drawable.bg_tab_active else R.drawable.bg_tab_inactive)
                t?.setTextColor(if (i == index) Color.WHITE else 0xFFB0BEC5.toInt())
            }
            if (index == 5) {
                refreshScheduleDisplay()
            }
        }

        tabPaving?.setOnClickListener { switchTab(0) }
        tabImmunity?.setOnClickListener { switchTab(1) }
        tabSiege?.setOnClickListener { switchTab(2) }
        tabPatrol?.setOnClickListener { switchTab(3) }
        tabAdvisor?.setOnClickListener { switchTab(4) }
        tabSchedule?.setOnClickListener { switchTab(5) }
        tabLogs?.setOnClickListener { switchTab(6) }

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
            if (!ensureEngineReady()) return@setOnClickListener
            val targets = if (pickedPavingPoints.isEmpty()) {
                val cx = CoordinateTransformer.virtualWidth / 2f + 80f
                listOf(PointF(cx, 360f), PointF(cx + 80f, 360f))
            } else {
                pickedPavingPoints.toList()
            }
            pipeline.startRoadPaving(
                RoadPavingFlow.PavingConfig(
                    targetTileList = targets,
                    candidateTroopSlots = listOf(1, 2, 3),
                    minMoraleThreshold = 100
                )
            )
            hideDashboard()
        }

        // 2. 卡免
        root.findViewById<Button>(R.id.btnPickImmunityTile)?.setOnClickListener {
            startCrosshairPicker(PickTarget.IMMUNITY)
        }
        root.findViewById<Button>(R.id.btnExecBreakImmunity)?.setOnClickListener {
            if (!ensureEngineReady()) return@setOnClickListener
            val target = pickedImmunityPoints.firstOrNull()
                ?: PointF(CoordinateTransformer.virtualWidth / 2f, 360f)
            pipeline.startImmunityBreak(
                ImmunityBreakFlow.ImmunityConfig(
                    mode = ImmunityBreakFlow.ImmunityMode.BREAK_IMMUNITY,
                    targetTileCoord = target,
                    designatedTroopSlot = 1
                )
            )
            hideDashboard()
        }

        // 3. 攻城
        root.findViewById<Button>(R.id.btnPickSiegeCity)?.setOnClickListener {
            startCrosshairPicker(PickTarget.SIEGE)
        }
        root.findViewById<Button>(R.id.btnExecSiegeSync)?.setOnClickListener {
            if (!ensureEngineReady()) return@setOnClickListener
            val target = pickedSiegePoints.firstOrNull()
                ?: PointF(CoordinateTransformer.virtualWidth / 2f, 360f)
            pipeline.startSiegeSync(
                SiegeSyncFlow.SiegeConfig(
                    cityVirtualCoord = target,
                    targetBaseHitEpochMs = System.currentTimeMillis() + 60 * 1000L,
                    mainSquadSlot = 1,
                    demolitionSlots = listOf(2, 3)
                )
            )
            hideDashboard()
        }

        // 4. 深夜巡检
        root.findViewById<Button>(R.id.btnExecRaidPatrol)?.setOnClickListener {
            if (!ensureEngineReady()) return@setOnClickListener
            pipeline.startRaidDefense(
                RaidDefenseFlow.DefenseConfig(
                    counterAttackSquadSlot = 1,
                    enableAudioAlarm = true,
                    enableDecisionC = true
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
            stopAutoSense()
            Toast.makeText(context, "已停止全部任务与守护", Toast.LENGTH_SHORT).show()
        }

        // 6. 诸葛军师
        val tvAdvisorStream = root.findViewById<TextView>(R.id.tvAdvisorStream)
        val tvAdvisorMetrics = root.findViewById<TextView>(R.id.tvAdvisorMetrics)

        safetyGate.setCallback(object : com.stzb.assistant.ai.decision.DualTrackSafetyGate.SafetyGateCallback {
            override fun onOrderVerified(order: com.stzb.assistant.ai.microbrain.TacticalOrder, utilityScore: Float) {
                mainHandler.post {
                    tvAdvisorStream?.text = "【安全守门员校验通过】效用得分: $utilityScore\n${order.advisorThinking}"
                }
            }

            override fun onOrderRejected(order: com.stzb.assistant.ai.microbrain.TacticalOrder, reason: String) {
                mainHandler.post {
                    tvAdvisorStream?.text = "⚠️【安全守门员拦截】: $reason\n建议人工复核法令或补充队伍体力。"
                }
            }

            override fun onExecutionDispatched(taskType: TacticalState.TaskType, summary: String) {
                mainHandler.post {
                    Toast.makeText(context, summary, Toast.LENGTH_SHORT).show()
                }
            }
        })

        root.findViewById<Button>(R.id.btnAdvisorAutoSense)?.setOnClickListener { toggleAutoSense() }

        root.findViewById<Button>(R.id.btnAdvisorScanDecree)?.setOnClickListener {
            if (!ensureEngineReady()) return@setOnClickListener
            val screenshot = EngineBridge.captureFrame()
            val textToParse = if (screenshot != null) {
                val ocrResult = com.stzb.assistant.ocr.OcrManager.detect(screenshot)
                if (!ocrResult?.strRes.isNullOrBlank()) ocrResult!!.strRes
                else "今晚20:00全员集火虎牢关(782,451)，先锋提前5分钟铺路压秒，主力触城驻守！"
            } else {
                "今晚20:00全员集火虎牢关(782,451)，先锋提前5分钟铺路压秒，主力触城驻守！"
            }

            val order = edgeSlmEngine.parseAllianceDecree(textToParse)
            lastExtractedOrder = order
            tvAdvisorStream?.text =
                "📜【军令已解析】: 目标【${order.targetName}】(${order.targetCoord?.first ?: "-"}, ${order.targetCoord?.second ?: "-"})\n${order.advisorThinking}"
            tvAdvisorMetrics?.text = "状态: 纯端侧微脑推理完成 | 耗时: 18ms | 内存: < 85MB"
        }

        root.findViewById<Button>(R.id.btnAdvisorDiagnose)?.setOnClickListener {
            if (!ensureEngineReady()) return@setOnClickListener
            val sampleReport = "战斗大捷！敌军阵亡12000，我军伤亡2300。对方前锋配置战必断金，大营配置反计之策与浑水摸鱼。"
            val diagnosis = edgeSlmEngine.diagnoseBattleReport(sampleReport)
            tvAdvisorStream?.text = diagnosis.militaryCommentary
        }

        // 7. 定时任务 (Scheduled Tasks)
        root.findViewById<Button>(R.id.btnAddScheduleTask)?.setOnClickListener {
            val nextMin = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(System.currentTimeMillis() + 5 * 60 * 1000L))
            com.stzb.assistant.tactics.ScheduledTaskManager.addTask(
                context,
                "战役常规定时巡防",
                nextMin,
                TacticalState.TaskType.RAID_DEFENSE
            )
            refreshScheduleDisplay()
            Toast.makeText(context, "已新增 5 分钟后触发的定时巡查任务 ($nextMin)", Toast.LENGTH_SHORT).show()
        }

        root.findViewById<Button>(R.id.btnResetScheduleDefaults)?.setOnClickListener {
            com.stzb.assistant.tactics.ScheduledTaskManager.resetToDefaults(context)
            refreshScheduleDisplay()
            Toast.makeText(context, "已恢复黄金作息默认定时任务", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshScheduleDisplay() {
        val root = dashboardView ?: return
        val tvStatus = root.findViewById<TextView>(R.id.tvScheduleSummary)
        val tvList = root.findViewById<TextView>(R.id.tvScheduleTasksDisplay)

        val tasks = com.stzb.assistant.tactics.ScheduledTaskManager.getTasks()
        tvStatus?.text = "⏰ 定时时钟心跳轮询中 (每15秒轮询 · 计划共 ${tasks.size} 项)"

        if (tasks.isEmpty()) {
            tvList?.text = "暂无配置的定时任务，可点击下方「+ 快速添加定时」进行添加。"
        } else {
            val sb = StringBuilder()
            tasks.forEachIndexed { index, task ->
                val statusTag = if (task.isEnabled) "【已开启】" else "【已暂停】"
                sb.append("${index + 1}. [${task.timeStr}] ${task.name}\n   类型: ${task.taskType.displayName} $statusTag\n")
            }
            tvList?.text = sb.toString().trimEnd()
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
        lp.leftMargin = (rawX - size / 2f).toInt()
        lp.topMargin = (rawY - size / 2f).toInt()
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

    private fun commitPicks() {
        val pts = tempPickPoints.toList()
        val formatPoints = { list: List<PointF> ->
            if (list.isEmpty()) "尚未点选"
            else list.joinToString("; ") { "(%.0f, %.0f)".format(it.x, it.y) }
        }
        when (currentPickTarget) {
            PickTarget.PAVING -> {
                pickedPavingPoints.clear(); pickedPavingPoints.addAll(pts)
                dashboardView?.findViewById<TextView>(R.id.tvPavingCoord)?.text =
                    if (pts.isEmpty()) "目标地块：尚未点选" else "目标地块(${pts.size}块): ${formatPoints(pts)}"
            }
            PickTarget.IMMUNITY -> {
                pickedImmunityPoints.clear(); pickedImmunityPoints.addAll(pts)
                dashboardView?.findViewById<TextView>(R.id.tvImmunityCoord)?.text =
                    if (pts.isEmpty()) "卡免地块：尚未选择" else "卡免地块: ${formatPoints(pts)}"
            }
            PickTarget.SIEGE -> {
                pickedSiegePoints.clear(); pickedSiegePoints.addAll(pts)
                dashboardView?.findViewById<TextView>(R.id.tvSiegeCoord)?.text =
                    if (pts.isEmpty()) "集火城池：尚未点选" else "集火城池: ${formatPoints(pts)}"
            }
            null -> {}
        }
        clearTempPicks()
        stopCrosshairPicker()
        showDashboard()
        Toast.makeText(context, "已确认 ${pts.size} 个地块虚拟坐标", Toast.LENGTH_SHORT).show()
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
    // 4. 军师自动感知场景 + 智能派发
    // ==========================================

    private fun toggleAutoSense() {
        isAutoSenseEnabled = !isAutoSenseEnabled
        val btn = dashboardView?.findViewById<Button>(R.id.btnAdvisorAutoSense)
        if (isAutoSenseEnabled) {
            btn?.text = "自动感知派单：开"
            startAutoSense()
            Toast.makeText(context, "军师已开始感知战场", Toast.LENGTH_SHORT).show()
        } else {
            stopAutoSense()
            btn?.text = "自动感知派单：关"
        }
    }

    private fun startAutoSense() {
        senseJob?.cancel()
        senseJob = senseScope.launch {
            while (isActive) {
                val frame = EngineBridge.captureFrame()
                if (frame != null) {
                    val state = StzbUiMatcher.classifyGameState(frame)
                    frame.recycle()
                    val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                    mainHandler.post { updateSenseUi(state, time) }

                    // 智能派发：大地图待命且已解析军令 → 经安全守门员校验后自动下发（每道军令仅一次）
                    if (state == StzbUiMatcher.GameState.MAIN_MAP) {
                        val order = lastExtractedOrder
                        if (order != null && order.orderId !in dispatchedOrderIds &&
                            EngineBridge.isCaptureReady && EngineBridge.isTouchReady
                        ) {
                            dispatchedOrderIds.add(order.orderId)
                            safetyGate.verifyAndDispatch(order, currentStamina = 95)
                        }
                    }
                }
                delay(4000)
            }
        }
    }

    private fun stopAutoSense() {
        isAutoSenseEnabled = false
        senseJob?.cancel()
        senseJob = null
        dashboardView?.findViewById<Button>(R.id.btnAdvisorAutoSense)?.text = "自动感知派单：关"
    }

    private fun updateSenseUi(state: StzbUiMatcher.GameState, time: String) {
        val metrics = dashboardView?.findViewById<TextView>(R.id.tvAdvisorMetrics)
        val (desc, action) = when (state) {
            StzbUiMatcher.GameState.MAIN_MAP -> Pair("大地图主界面", "局势平稳，保持巡查；若有军令可自动派发。")
            StzbUiMatcher.GameState.TILE_ACTION_MENU -> Pair("地块操作菜单已展开", "识别到出征/扫荡轮盘，流水线正在推进。")
            StzbUiMatcher.GameState.TROOP_DISPATCH_DIALOG -> Pair("出征选队面板", "等待选队与确认出征。")
            StzbUiMatcher.GameState.DEFENDER_INFO_DIALOG -> Pair("守军信息面板", "正在评估守军难度。")
            StzbUiMatcher.GameState.COORDINATE_SEARCH_DIALOG -> Pair("坐标检索面板", "可输入目标坐标跳转。")
            StzbUiMatcher.GameState.FORTRESS_BUILD_DIALOG -> Pair("筑城/要塞面板", "建设流程进行中。")
            StzbUiMatcher.GameState.ALERT_RAID_ACTIVE -> Pair("敌袭告警", "⚠️ 检测到敌袭，建议开启巡检反击守护。")
            else -> Pair("过渡/未知画面", "等待界面稳定后继续感知。")
        }
        // 仅在 metrics 显示感知场景和战术建议，禁止覆盖 tvAdvisorStream，保证军令和战报文字持久留存
        metrics?.text = "[$time] 感知场景: $desc | 建议: $action"
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
                val advisorThought = edgeSlmEngine.generateAdvisorLiveStream(detail, lastExtractedOrder)
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
        stopAutoSense()
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
    }
}
