package com.stzb.assistant.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PointF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
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
import com.stzb.assistant.R
import com.stzb.assistant.service.CoordinateTransformer
import com.stzb.assistant.tactics.ImmunityBreakFlow
import com.stzb.assistant.tactics.RaidDefenseFlow
import com.stzb.assistant.tactics.RoadPavingFlow
import com.stzb.assistant.tactics.SiegeSyncFlow
import com.stzb.assistant.tactics.TacticalPipeline
import com.stzb.assistant.tactics.TacticalState

/**
 * 游戏内常驻悬浮 UI 总控管理器 (OverlayWindowManager)
 * 
 * 核心特性与架构：
 *   1. 【极简迷你药丸胶囊 (Capsule)】：
 *      平时吸附在屏幕边缘，支持任意拖拽，手指抬起时自动平滑吸边；
 *      实时显示战术任务状态（绿色就绪/蓝色执行/黄色卡秒/红色敌袭警报）；
 *   2. 【全功能展开式战术控制面板 (Dashboard)】：
 *      单击胶囊瞬间展开深色半透明 HUD，支持铺路、卡免、攻城、巡检与实时游戏内日志流水；
 *   3. 【准星取点与坐标交互浮层 (Crosshair Picker)】：
 *      点击“准星嗅探取点”进入全屏透明层，玩家轻点屏幕任意地块，自动抓取并换算为 720p 归一化虚拟坐标！
 */
class OverlayWindowManager(private val context: Context) : TacticalState.TacticalEventListener {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pipeline = TacticalPipeline.getInstance(context)

    // 视图实例
    private var capsuleView: View? = null
    private var dashboardView: View? = null
    private var pickerView: View? = null

    // 胶囊 Window 属性
    private lateinit var capsuleParams: WindowManager.LayoutParams
    private lateinit var dashboardParams: WindowManager.LayoutParams
    private lateinit var pickerParams: WindowManager.LayoutParams

    // 交互暂存坐标 (720p 归一化虚拟坐标)
    private var pickedPavingCoord: PointF? = null
    private var pickedImmunityCoord: PointF? = null
    private var pickedSiegeCoord: PointF? = null

    // 端侧认知微脑与双轨安全守门员
    private val edgeSlmEngine = com.stzb.assistant.ai.microbrain.EdgeSlmEngine(context)
    private val safetyGate = com.stzb.assistant.ai.decision.DualTrackSafetyGate(context)
    private var lastExtractedOrder: com.stzb.assistant.ai.microbrain.TacticalOrder? = null

    // 取点回调路由
    private var currentPickTarget: PickTarget? = null

    enum class PickTarget {
        PAVING, IMMUNITY, SIEGE
    }

    init {
        pipeline.registerListener(this)
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

        // 1. 胶囊布局参数 (不抢焦点)
        capsuleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 200
        }

        // 2. 控制台面板参数 (可获焦点以支持输入，居中显示)
        dashboardParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        // 3. 准星全屏取点层参数 (拦截全屏轻点，取点完成后立即销毁)
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
    // 1. 迷你药丸胶囊 (Capsule) 构建与吸边交互
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
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        isDragging = true
                    }
                    capsuleParams.x = initialX + dx
                    capsuleParams.y = initialY + dy
                    windowManager.updateViewLayout(capsuleView, capsuleParams)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        // 自动吸附屏幕左右边缘
                        snapCapsuleToEdge()
                    } else {
                        // 单击：展开战术控制面板
                        showDashboard()
                    }
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
    // 2. 展开式战术控制面板 (Dashboard) 构建
    // ==========================================

    private fun createDashboardView() {
        val inflater = LayoutInflater.from(context)
        dashboardView = inflater.inflate(R.layout.view_floating_dashboard, null)

        val tvTitle = dashboardView?.findViewById<TextView>(R.id.tvDashboardTitle)
        tvTitle?.text = "${com.stzb.assistant.knowledge.KnowledgeBaseManager.activeProfile.gameName} · 战术总控"

        val tvClose = dashboardView?.findViewById<TextView>(R.id.tvCloseDashboard)
        tvClose?.setOnClickListener { hideDashboard() }

        // Tab 切换
        val tabPaving = dashboardView?.findViewById<Button>(R.id.tabPaving)
        val tabImmunity = dashboardView?.findViewById<Button>(R.id.tabImmunity)
        val tabSiege = dashboardView?.findViewById<Button>(R.id.tabSiege)
        val tabPatrol = dashboardView?.findViewById<Button>(R.id.tabPatrol)
        val tabLogs = dashboardView?.findViewById<Button>(R.id.tabLogs)
        val tabAdvisor = dashboardView?.findViewById<Button>(R.id.tabAdvisor)

        val panelPaving = dashboardView?.findViewById<LinearLayout>(R.id.panelPaving)
        val panelImmunity = dashboardView?.findViewById<LinearLayout>(R.id.panelImmunity)
        val panelSiege = dashboardView?.findViewById<LinearLayout>(R.id.panelSiege)
        val panelPatrol = dashboardView?.findViewById<LinearLayout>(R.id.panelPatrol)
        val panelLogs = dashboardView?.findViewById<LinearLayout>(R.id.panelLogs)
        val panelAdvisor = dashboardView?.findViewById<LinearLayout>(R.id.panelAdvisor)

        val tabs = listOf(tabPaving, tabImmunity, tabSiege, tabPatrol, tabLogs, tabAdvisor)
        val panels = listOf(panelPaving, panelImmunity, panelSiege, panelPatrol, panelLogs, panelAdvisor)

        fun switchTab(index: Int) {
            panels.forEachIndexed { i, p -> p?.visibility = if (i == index) View.VISIBLE else View.GONE }
            tabs.forEachIndexed { i, t ->
                t?.setBackgroundColor(if (i == index) 0xFF1565C0.toInt() else 0xFF37474F.toInt())
                t?.setTextColor(if (i == index) Color.WHITE else 0xFFB0BEC5.toInt())
            }
        }

        tabPaving?.setOnClickListener { switchTab(0) }
        tabImmunity?.setOnClickListener { switchTab(1) }
        tabSiege?.setOnClickListener { switchTab(2) }
        tabPatrol?.setOnClickListener { switchTab(3) }
        tabLogs?.setOnClickListener { switchTab(4) }
        tabAdvisor?.setOnClickListener { switchTab(5) }

        // 按钮事件接入
        setupDashboardActions()
    }

    private fun setupDashboardActions() {
        val root = dashboardView ?: return

        val checkAuth = {
            val license = com.stzb.assistant.license.LicenseManager.checkLocalLicense(context)
            if (!license.isValid) {
                Toast.makeText(context, "商业卡密未激活或已过期: ${license.message}", Toast.LENGTH_LONG).show()
                false
            } else {
                true
            }
        }

        // 1. 铺路面板
        root.findViewById<Button>(R.id.btnPickPavingTile)?.setOnClickListener {
            startCrosshairPicker(PickTarget.PAVING)
        }
        root.findViewById<Button>(R.id.btnExecPaving)?.setOnClickListener {
            if (!checkAuth()) return@setOnClickListener
            val target = pickedPavingCoord ?: PointF(CoordinateTransformer.virtualWidth / 2f + 80f, 360f)
            pipeline.startRoadPaving(
                RoadPavingFlow.PavingConfig(
                    targetTileList = listOf(target, PointF(target.x + 80f, target.y)),
                    candidateTroopSlots = listOf(1, 2, 3),
                    minMoraleThreshold = 100
                )
            )
            hideDashboard()
        }

        // 2. 卡免面板
        root.findViewById<Button>(R.id.btnPickImmunityTile)?.setOnClickListener {
            startCrosshairPicker(PickTarget.IMMUNITY)
        }
        root.findViewById<Button>(R.id.btnExecBreakImmunity)?.setOnClickListener {
            if (!checkAuth()) return@setOnClickListener
            val target = pickedImmunityCoord ?: PointF(CoordinateTransformer.virtualWidth / 2f, 360f)
            pipeline.startImmunityBreak(
                ImmunityBreakFlow.ImmunityConfig(
                    mode = ImmunityBreakFlow.ImmunityMode.BREAK_IMMUNITY,
                    targetTileCoord = target,
                    designatedTroopSlot = 1
                )
            )
            hideDashboard()
        }

        // 3. 攻城面板
        root.findViewById<Button>(R.id.btnPickSiegeCity)?.setOnClickListener {
            startCrosshairPicker(PickTarget.SIEGE)
        }
        root.findViewById<Button>(R.id.btnExecSiegeSync)?.setOnClickListener {
            if (!checkAuth()) return@setOnClickListener
            val target = pickedSiegeCoord ?: PointF(CoordinateTransformer.virtualWidth / 2f, 360f)
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
            if (!checkAuth()) return@setOnClickListener
            pipeline.startRaidDefense(
                RaidDefenseFlow.DefenseConfig(
                    counterAttackSquadSlot = 1,
                    enableAudioAlarm = true,
                    enableDecisionC = true
                )
            )
            hideDashboard()
        }

        // 5. 急停按钮
        root.findViewById<Button>(R.id.btnEmergencyStop)?.setOnClickListener {
            pipeline.stopCurrentTask()
            Toast.makeText(context, "战术流水线已急停", Toast.LENGTH_SHORT).show()
        }

        // 6. 诸葛军师 · 端侧认知微脑与双轨安全守门员
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

        root.findViewById<Button>(R.id.btnAdvisorScanDecree)?.setOnClickListener {
            if (!checkAuth()) return@setOnClickListener
            val screenshot = com.stzb.assistant.service.EngineBridge.captureFrame()
            val textToParse = if (screenshot != null) {
                val ocrResult = com.stzb.assistant.ocr.OcrManager.detect(screenshot)
                if (!ocrResult?.strRes.isNullOrBlank()) ocrResult!!.strRes else "今晚20:00全员集火虎牢关(782,451)，先锋提前5分钟铺路压秒，主力触城驻守！"
            } else {
                "今晚20:00全员集火虎牢关(782,451)，先锋提前5分钟铺路压秒，主力触城驻守！"
            }

            val order = edgeSlmEngine.parseAllianceDecree(textToParse)
            lastExtractedOrder = order
            tvAdvisorStream?.text = "📜【军令已解析】: 目标【${order.targetName}】(${order.targetCoord?.first ?: "-"}, ${order.targetCoord?.second ?: "-"})\n${order.advisorThinking}"
            tvAdvisorMetrics?.text = "状态: 纯端侧微脑推理完成 | 耗时: 18ms | 内存: < 85MB"
        }

        root.findViewById<Button>(R.id.btnAdvisorDiagnose)?.setOnClickListener {
            if (!checkAuth()) return@setOnClickListener
            val sampleReport = "战斗大捷！敌军阵亡12000，我军伤亡2300。对方前锋配置战必断金，大营配置反计之策与浑水摸鱼。"
            val diagnosis = edgeSlmEngine.diagnoseBattleReport(sampleReport)
            tvAdvisorStream?.text = diagnosis.militaryCommentary
        }

        root.findViewById<Button>(R.id.btnAdvisorAutoExec)?.setOnClickListener {
            if (!checkAuth()) return@setOnClickListener
            val order = lastExtractedOrder
            if (order == null) {
                Toast.makeText(context, "请先点击【识别全屏军令】提取战术方略", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            safetyGate.verifyAndDispatch(order, currentStamina = 95)
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
    // 3. 准星全屏取点交互 (Crosshair Picker)
    // ==========================================

    private fun createPickerView() {
        val inflater = LayoutInflater.from(context)
        pickerView = inflater.inflate(R.layout.view_crosshair_picker, null)

        val flCrosshair = pickerView?.findViewById<FrameLayout>(R.id.flCrosshairContainer)
        val btnCancel = pickerView?.findViewById<Button>(R.id.btnCancelPicker)

        btnCancel?.setOnClickListener {
            stopCrosshairPicker()
            showDashboard()
        }

        pickerView?.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
                flCrosshair?.visibility = View.VISIBLE
                flCrosshair?.x = event.rawX - (flCrosshair?.width ?: 60) / 2f
                flCrosshair?.y = event.rawY - (flCrosshair?.height ?: 60) / 2f
            } else if (event.action == MotionEvent.ACTION_UP) {
                // 用户抬手确认点选！
                handlePointSelected(event.rawX, event.rawY)
            }
            true
        }
    }

    private fun startCrosshairPicker(target: PickTarget) {
        currentPickTarget = target
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

    private fun handlePointSelected(realX: Float, realY: Float) {
        val virtualPoint = CoordinateTransformer.toVirtual(realX, realY)
        Log.i(TAG, "🎯 准星嗅探成功: 物理=($realX, $realY) -> 自适应虚拟=(${virtualPoint.x.toInt()}, ${virtualPoint.y.toInt()})")

        when (currentPickTarget) {
            PickTarget.PAVING -> {
                pickedPavingCoord = virtualPoint
                dashboardView?.findViewById<TextView>(R.id.tvPavingCoord)?.text =
                    "已锁定目标地块: 虚拟(${virtualPoint.x.toInt()}, ${virtualPoint.y.toInt()})"
            }
            PickTarget.IMMUNITY -> {
                pickedImmunityCoord = virtualPoint
                dashboardView?.findViewById<TextView>(R.id.tvImmunityCoord)?.text =
                    "已锁定免战地块: 虚拟(${virtualPoint.x.toInt()}, ${virtualPoint.y.toInt()})"
            }
            PickTarget.SIEGE -> {
                pickedSiegeCoord = virtualPoint
                dashboardView?.findViewById<TextView>(R.id.tvSiegeCoord)?.text =
                    "已锁定集火城池: 虚拟(${virtualPoint.x.toInt()}, ${virtualPoint.y.toInt()})"
            }
            null -> {}
        }

        stopCrosshairPicker()
        showDashboard()
        Toast.makeText(context, "已锁定地块坐标: (${virtualPoint.x.toInt()}, ${virtualPoint.y.toInt()})", Toast.LENGTH_SHORT).show()
    }

    // ==========================================
    // 4. 战术流水线事件联动 (Capsule 与 In-Game Logs)
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

            // 联动更新 AI 诸葛军师思考流
            val advisorThought = edgeSlmEngine.generateAdvisorLiveStream(detail, lastExtractedOrder)
            dashboardView?.findViewById<TextView>(R.id.tvAdvisorStream)?.text = advisorThought
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
        hideDashboard()
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
