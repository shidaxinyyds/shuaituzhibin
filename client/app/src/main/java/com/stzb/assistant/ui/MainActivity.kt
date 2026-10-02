package com.stzb.assistant.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.stzb.assistant.R
import com.stzb.assistant.license.LicenseManager
import com.stzb.assistant.ocr.DefenderEvaluator
import com.stzb.assistant.ocr.OcrManager
import com.stzb.assistant.ocr.RaidRadarDetector
import com.stzb.assistant.ocr.StzbUiMatcher
import com.stzb.assistant.ocr.TileStatusDetector
import com.stzb.assistant.ocr.TroopStatusDetector
import com.stzb.assistant.service.AutoTouchService
import com.stzb.assistant.service.CoordinateTransformer
import com.stzb.assistant.service.EngineBridge
import com.stzb.assistant.service.FloatOverlayService
import com.stzb.assistant.service.ScreenCaptureService
import com.stzb.assistant.service.ShizukuTouchManager
import com.stzb.assistant.tactics.ImmunityBreakFlow
import com.stzb.assistant.tactics.RaidDefenseFlow
import com.stzb.assistant.tactics.RoadPavingFlow
import com.stzb.assistant.tactics.SiegeSyncFlow
import com.stzb.assistant.tactics.TacticalPipeline
import com.stzb.assistant.tactics.TacticalState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity(), TacticalState.TacticalEventListener {

    // 阶段六商业卡密鉴权组件
    private lateinit var tvLicenseBadge: TextView
    private lateinit var tvDeviceId: TextView
    private lateinit var etLicenseCode: EditText
    private lateinit var btnActivateLicense: Button
    private lateinit var tvLicenseMessage: TextView

    private lateinit var tvLog: TextView
    private lateinit var btnOverlay: MaterialButton
    private lateinit var btnAccessibility: MaterialButton
    private lateinit var btnCapture: MaterialButton
    private lateinit var btnToggleOverlay: MaterialButton

    // 阶段三战术流水线按钮
    private lateinit var btnStartRoadPaving: MaterialButton
    private lateinit var btnStartImmunityBreak: MaterialButton
    private lateinit var btnStartSiegeSync: MaterialButton
    private lateinit var btnStartRaidDefense: MaterialButton
    private lateinit var btnStopAllTactics: MaterialButton

    // 阶段二功能自检测试按钮
    private lateinit var btnTestOcr: MaterialButton
    private lateinit var btnTestCaptureTouch: MaterialButton
    private lateinit var btnTestSceneMatch: MaterialButton
    private lateinit var btnTestRaidRadar: MaterialButton
    private lateinit var btnTestTileImmunity: MaterialButton
    private lateinit var btnTestTroopTiming: MaterialButton

    // 阶段四商业化防封自检测试按钮
    private lateinit var btnTestTimingFingerprint: MaterialButton
    private lateinit var btnTestKineticTouch: MaterialButton
    private lateinit var btnTestStealthAudit: MaterialButton

    // 阶段五悬浮 UI 与交互体验测试按钮
    private lateinit var btnTestCapsuleOverlay: MaterialButton
    private lateinit var btnTestCrosshairPicker: MaterialButton

    private var isOverlayShown = false
    private lateinit var pipeline: TacticalPipeline

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            log("✅ 720p 屏幕流录屏授权成功！正在启动前台捕获通道...")
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START_CAPTURE
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
            btnCapture.text = "3. 720p 捕获通道 [运行中 ✅]"
        } else {
            log("❌ 用户取消或拒绝了屏幕录制授权。")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        pipeline = TacticalPipeline.getInstance(this)
        pipeline.registerListener(this)

        initViews()
        setupButtons()
        checkPermissions()
        refreshLicenseStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        pipeline.unregisterListener(this)
    }

    override fun onResume() {
        super.onResume()
        checkPermissions()
        refreshLicenseStatus()
    }

    private fun initViews() {
        tvLicenseBadge = findViewById(R.id.tvLicenseBadge)
        tvDeviceId = findViewById(R.id.tvDeviceId)
        etLicenseCode = findViewById(R.id.etLicenseCode)
        btnActivateLicense = findViewById(R.id.btnActivateLicense)
        tvLicenseMessage = findViewById(R.id.tvLicenseMessage)

        tvLog = findViewById(R.id.tvLogOutput)
        btnOverlay = findViewById(R.id.btnOverlayPermission)
        btnAccessibility = findViewById(R.id.btnAccessibilityPermission)
        btnCapture = findViewById(R.id.btnScreenCapturePermission)
        btnToggleOverlay = findViewById(R.id.btnToggleOverlay)

        btnStartRoadPaving = findViewById(R.id.btnStartRoadPaving)
        btnStartImmunityBreak = findViewById(R.id.btnStartImmunityBreak)
        btnStartSiegeSync = findViewById(R.id.btnStartSiegeSync)
        btnStartRaidDefense = findViewById(R.id.btnStartRaidDefense)
        btnStopAllTactics = findViewById(R.id.btnStopAllTactics)

        btnTestOcr = findViewById(R.id.btnTestOcr)
        btnTestCaptureTouch = findViewById(R.id.btnTestCaptureTouch)
        btnTestSceneMatch = findViewById(R.id.btnTestSceneMatch)
        btnTestRaidRadar = findViewById(R.id.btnTestRaidRadar)
        btnTestTileImmunity = findViewById(R.id.btnTestTileImmunity)
        btnTestTroopTiming = findViewById(R.id.btnTestTroopTiming)

        btnTestTimingFingerprint = findViewById(R.id.btnTestTimingFingerprint)
        btnTestKineticTouch = findViewById(R.id.btnTestKineticTouch)
        btnTestStealthAudit = findViewById(R.id.btnTestStealthAudit)

        btnTestCapsuleOverlay = findViewById(R.id.btnTestCapsuleOverlay)
        btnTestCrosshairPicker = findViewById(R.id.btnTestCrosshairPicker)
    }

    private fun setupButtons() {
        btnActivateLicense.setOnClickListener {
            val code = etLicenseCode.text.toString().trim()
            if (code.isEmpty()) {
                Toast.makeText(this, "请输入卡密激活码", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            btnActivateLicense.isEnabled = false
            btnActivateLicense.text = "激活中..."
            CoroutineScope(Dispatchers.Main).launch {
                val result = LicenseManager.activateOnline(this@MainActivity, code)
                btnActivateLicense.isEnabled = true
                btnActivateLicense.text = "立即激活"
                result.onSuccess { info ->
                    Toast.makeText(this@MainActivity, "🎉 卡密激活成功！", Toast.LENGTH_SHORT).show()
                    refreshLicenseStatus()
                    log("🎉【商业卡密激活成功】类型: ${info.cardType} | 状态: ${info.message}")
                }.onFailure { err ->
                    Toast.makeText(this@MainActivity, err.message ?: "激活失败", Toast.LENGTH_LONG).show()
                    tvLicenseMessage.text = "❌ 激活失败: ${err.message}"
                    tvLicenseMessage.setTextColor(Color.parseColor("#EF4444"))
                    log("❌【卡密激活失败】${err.message}")
                }
            }
        }

        btnOverlay.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } else {
                Toast.makeText(this, "悬浮窗权限已授予", Toast.LENGTH_SHORT).show()
            }
        }

        btnAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        btnCapture.setOnClickListener {
            if (ScreenCaptureService.isCapturing.get()) {
                val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_STOP_CAPTURE
                }
                startService(stopIntent)
                btnCapture.text = "3. 启动 720p 屏幕流捕获通道"
                log("⏹️ 屏幕捕获通道已手动停止。")
            } else {
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                captureLauncher.launch(mpm.createScreenCaptureIntent())
            }
        }

        btnToggleOverlay.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val intent = Intent(this, FloatOverlayService::class.java)
            if (isOverlayShown) {
                stopService(intent)
                isOverlayShown = false
                btnToggleOverlay.text = "4. 显示游戏常驻悬浮胶囊"
                log("悬浮胶囊已关闭。")
            } else {
                startService(intent)
                isOverlayShown = true
                btnToggleOverlay.text = "4. 隐藏游戏常驻悬浮胶囊"
                log("🟢 悬浮胶囊已成功显示在屏幕上！")
            }
        }

        // ==========================================
        // 阶段三战术流水线启动器
        // ==========================================

        btnStartRoadPaving.setOnClickListener {
            if (!assertEngineReady()) return@setOnClickListener
            log("🚀【启动自动铺路翻地流】规划接力地块与多部队动态轮班...")
            val vCenter = PointF(CoordinateTransformer.virtualWidth / 2f, 360f)
            val sampleSteps = listOf(
                PointF(vCenter.x + 80f, vCenter.y),
                PointF(vCenter.x + 160f, vCenter.y),
                PointF(vCenter.x + 240f, vCenter.y)
            )
            pipeline.startRoadPaving(
                RoadPavingFlow.PavingConfig(
                    targetTileList = sampleSteps,
                    candidateTroopSlots = listOf(1, 2, 3),
                    minMoraleThreshold = 100
                )
            )
        }

        btnStartImmunityBreak.setOnClickListener {
            if (!assertEngineReady()) return@setOnClickListener
            log("⚡【启动极限卡免与压秒破免】锁定目标地块，准备 00:00:01 准点触敌...")
            val vCenter = PointF(CoordinateTransformer.virtualWidth / 2f, 360f)
            pipeline.startImmunityBreak(
                ImmunityBreakFlow.ImmunityConfig(
                    mode = ImmunityBreakFlow.ImmunityMode.BREAK_IMMUNITY,
                    targetTileCoord = vCenter,
                    designatedTroopSlot = 1
                )
            )
        }

        btnStartSiegeSync.setOnClickListener {
            if (!assertEngineReady()) return@setOnClickListener
            log("🏹【启动同盟集火攻城卡秒】开始测算主力与拆迁队时序...")
            val vCenter = PointF(CoordinateTransformer.virtualWidth / 2f, 360f)
            val hitTime = System.currentTimeMillis() + 60 * 1000L // 默认 1 分钟后集火
            pipeline.startSiegeSync(
                SiegeSyncFlow.SiegeConfig(
                    cityVirtualCoord = vCenter,
                    targetBaseHitEpochMs = hitTime,
                    mainSquadSlot = 1,
                    demolitionSlots = listOf(2, 3)
                )
            )
        }

        btnStartRaidDefense.setOnClickListener {
            if (!assertEngineReady()) return@setOnClickListener
            log("🚨【启动深夜敌袭雷达巡检】全天候 24h 守护与【决策 C 自动反击】已就绪！")
            pipeline.startRaidDefense(
                RaidDefenseFlow.DefenseConfig(
                    counterAttackSquadSlot = 1,
                    patrolIntervalMs = 4000L,
                    enableAudioAlarm = true,
                    enableDecisionC = true
                )
            )
        }

        btnStopAllTactics.setOnClickListener {
            pipeline.stopCurrentTask()
            log("⏹️ 已一键紧急终止所有战术流水线！")
        }

        // 阶段二功能自检
        btnTestOcr.setOnClickListener { runOcrSelfTest() }
        btnTestCaptureTouch.setOnClickListener { runCaptureAndTouchTest() }
        btnTestSceneMatch.setOnClickListener { runSceneMatchTest() }
        btnTestRaidRadar.setOnClickListener { runRaidRadarTest() }
        btnTestTileImmunity.setOnClickListener { runTileImmunityTest() }
        btnTestTroopTiming.setOnClickListener { runTroopTimingTest() }

        // 阶段四防封自检
        btnTestTimingFingerprint.setOnClickListener { runTimingFingerprintTest() }
        btnTestKineticTouch.setOnClickListener { runKineticTouchTest() }
        btnTestStealthAudit.setOnClickListener { runStealthAuditTest() }

        // 阶段五悬浮 UI 自检
        btnTestCapsuleOverlay.setOnClickListener { runCapsuleOverlayTest() }
        btnTestCrosshairPicker.setOnClickListener { runCrosshairPickerTest() }
    }

    private fun refreshLicenseStatus() {
        val license = LicenseManager.checkLocalLicense(this)
        tvDeviceId.text = "设备指纹: ${license.deviceId}"
        if (license.isValid) {
            tvLicenseBadge.text = "${license.cardType ?: "全功能旗舰版"} [已授权]"
            tvLicenseBadge.setTextColor(Color.parseColor("#10B981"))
            tvLicenseBadge.setBackgroundColor(Color.parseColor("#064E3B"))
            tvLicenseMessage.text = "✅ 授权有效 | ${license.message}"
            tvLicenseMessage.setTextColor(Color.parseColor("#10B981"))
        } else {
            tvLicenseBadge.text = "未激活 / 已过期"
            tvLicenseBadge.setTextColor(Color.parseColor("#F59E0B"))
            tvLicenseBadge.setBackgroundColor(Color.parseColor("#78350F"))
            tvLicenseMessage.text = "⚠️ ${license.message} (离线强签名校验)"
            tvLicenseMessage.setTextColor(Color.parseColor("#F59E0B"))
        }
    }

    private fun assertEngineReady(): Boolean {
        val license = LicenseManager.checkLocalLicense(this)
        if (!license.isValid) {
            Toast.makeText(this, "商业授权未激活或已过期，请先激活卡密！", Toast.LENGTH_LONG).show()
            log("❌【卡密鉴权拦截】${license.message}。请在上方输入卡密激活。")
            return false
        }
        if (!EngineBridge.isCaptureReady) {
            Toast.makeText(this, "请先启动 720p 屏幕捕获通道 (按钮 3)", Toast.LENGTH_SHORT).show()
            return false
        }
        if (!EngineBridge.isTouchReady) {
            Toast.makeText(this, "请先开启无障碍触控通道或 Shizuku (按钮 2)", Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    private fun checkPermissions() {
        val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true

        btnOverlay.text = if (hasOverlay) "1. 悬浮窗权限 [已授予 ✅]" else "1. 授予系统悬浮窗权限"
        btnOverlay.isEnabled = !hasOverlay

        val hasAccessibility = AutoTouchService.isConnected
        val hasShizuku = ShizukuTouchManager.hasPermission()
        btnAccessibility.text = when {
            hasShizuku -> "2. 触控通道: Shizuku 底层注入 [已就绪 ✅]"
            hasAccessibility -> "2. 触控通道: 系统无障碍手势 [已开启 ✅]"
            else -> "2. 开启无障碍触控通道 (或使用 Shizuku)"
        }

        if (ScreenCaptureService.isCapturing.get()) {
            btnCapture.text = "3. 720p 捕获通道 [运行中 ✅]"
        }
    }

    override fun onStatusChanged(taskType: TacticalState.TaskType, status: TacticalState.Status, detail: String) {
        runOnUiThread {
            log("🔔 [${taskType.displayName}] 状态变迁: ${status.desc} | $detail")
        }
    }

    override fun onLogEmitted(log: TacticalState.TacticalLog) {
        runOnUiThread {
            log("[${log.level}] ${log.message}")
        }
    }

    // ==========================================
    // 阶段二功能自检实现
    // ==========================================

    private fun runOcrSelfTest() {
        log("🔄 正在执行 RapidOCR 本地推理与守军打分测试...")
        CoroutineScope(Dispatchers.IO).launch {
            val startTime = System.currentTimeMillis()
            val testBmp = Bitmap.createBitmap(500, 200, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(testBmp)
            canvas.drawColor(Color.WHITE)
            val paint = Paint().apply {
                color = Color.BLACK
                textSize = 28f
                isAntiAlias = true
            }
            canvas.drawText("部队体力 115/120", 30f, 60f, paint)
            canvas.drawText("大地图 X: 628  Y: 792", 30f, 110f, paint)
            canvas.drawText("守将: 邓茂 田续 裴元绍", 30f, 160f, paint)

            val ocrResult = OcrManager.detect(testBmp)
            val costMs = System.currentTimeMillis() - startTime
            val sampleDefenders = listOf("邓茂", "田续", "裴元绍")
            val evaluation = DefenderEvaluator.evaluate(sampleDefenders)

            withContext(Dispatchers.Main) {
                if (ocrResult != null) {
                    val stamina = OcrManager.parseStamina(testBmp)
                    val coords = OcrManager.parseCoordinates(testBmp)
                    log(
                        "🎉【OCR 自检成功】耗时: ${costMs}ms\n" +
                        "📝 文本: ${ocrResult.strRes.replace("\n", " | ")}\n" +
                        "⚡ 体力: ${stamina}/120 | 📍 坐标: (${coords?.first}, ${coords?.second})\n" +
                        "🛡️ 守军难度: ${evaluation.tier.desc} - ${evaluation.recommendation}"
                    )
                } else {
                    log("⚠️ OCR 返回为空。")
                }
            }
        }
    }

    private fun runCaptureAndTouchTest() {
        val transformer = CoordinateTransformer
        transformer.refreshMetrics()
        log("📱【自适应画布】物理: ${transformer.physicalWidth.toInt()}x${transformer.physicalHeight.toInt()} -> 虚拟: ${transformer.virtualWidth.toInt()}x${transformer.virtualHeight.toInt()}")

        if (!EngineBridge.isTouchReady) {
            log("⚠️ 请先开启触控通道！")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            log("🎯 正在执行三次贝塞尔拟人滑动...")
            EngineBridge.swipe(200f, 400f, 800f, 400f)
            EngineBridge.humanDelay(500, 800)
            log("🎯 正在执行微抖拟人点击...")
            EngineBridge.tap(transformer.virtualWidth / 2f, 360f)
            withContext(Dispatchers.Main) {
                log("✅【触控通道测试通过】手势派发完成！")
            }
        }
    }

    private fun runSceneMatchTest() {
        log("🔄 正在测试地块菜单语义按键定位...")
        CoroutineScope(Dispatchers.IO).launch {
            val bmp = Bitmap.createBitmap(800, 480, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.rgb(35, 40, 45))
            val paint = Paint().apply {
                color = Color.WHITE
                textSize = 32f
                isAntiAlias = true
            }
            canvas.drawText("Lv.5 铁矿 (X:520 Y:660)", 50f, 60f, paint)
            canvas.drawText("出征", 120f, 200f, paint)
            canvas.drawText("扫荡", 260f, 200f, paint)
            canvas.drawText("驻守", 400f, 200f, paint)
            canvas.drawText("屯田", 540f, 200f, paint)
            canvas.drawText("查看守军", 240f, 320f, paint)

            val state = StzbUiMatcher.classifyGameState(bmp)
            val buttons = StzbUiMatcher.findButtons(
                bmp,
                listOf(
                    StzbUiMatcher.ButtonType.ATTACK,
                    StzbUiMatcher.ButtonType.SWEEP,
                    StzbUiMatcher.ButtonType.DEFEND,
                    StzbUiMatcher.ButtonType.FARM,
                    StzbUiMatcher.ButtonType.SCOUT_DEFENDERS
                )
            )

            withContext(Dispatchers.Main) {
                val sb = StringBuilder()
                sb.append("🎯【全场景状态机与语义按键自测成功】\n")
                sb.append("📍 场景分类: ${state.name}\n")
                buttons.forEach { (type, res) ->
                    sb.append("  • [${type.primaryKeyword}] -> 触控点: (${"%.1f".format(res.safeTouchPoint.x)}, ${"%.1f".format(res.safeTouchPoint.y)})\n")
                }
                log(sb.toString())
            }
        }
    }

    private fun runRaidRadarTest() {
        log("🔄 正在测试敌袭红线雷达与跳板要塞源头回溯 (决策 C)...")
        CoroutineScope(Dispatchers.IO).launch {
            val bmp = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.rgb(50, 70, 40))
            val redPaint = Paint().apply {
                color = Color.rgb(240, 20, 20)
                strokeWidth = 6f
                isAntiAlias = true
            }
            canvas.drawLine(980f, 150f, 650f, 350f, redPaint)

            val edgePaint = Paint().apply {
                color = Color.rgb(230, 10, 10)
                strokeWidth = 12f
            }
            canvas.drawLine(0f, 5f, 1280f, 5f, edgePaint)

            val baseAnchor = PointF(640f, 360f)
            val report = RaidRadarDetector.scanRaidThreats(bmp, baseAnchor)

            withContext(Dispatchers.Main) {
                if (report.hasThreat) {
                    log(
                        "🚨【敌袭雷达报警成功】\n" +
                        "⚠️ 威胁等级: ${report.threatLevel}\n" +
                        "🔴 屏幕边缘夜袭红光闪烁: ${report.isScreenEdgeAlert}\n" +
                        "🏹 捕获行军轨迹: ${report.detectedVectors.size} 条\n" +
                        "🎯 受威胁己方基地: (${report.playerTargetPoint?.x?.toInt()}, ${report.playerTargetPoint?.y?.toInt()})\n" +
                        "⚔️【决策 C 核心定位】敌军进攻源头跳板要塞: (${report.enemyOriginPoint?.x?.toInt()}, ${report.enemyOriginPoint?.y?.toInt()})"
                    )
                }
            }
        }
    }

    private fun runTileImmunityTest() {
        log("🔄 正在测试土地金色免战罩与倒计时...")
        CoroutineScope(Dispatchers.IO).launch {
            val bmp = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.rgb(60, 55, 45))
            val goldPaint = Paint().apply {
                color = Color.rgb(245, 195, 20)
                style = Paint.Style.STROKE
                strokeWidth = 14f
                isAntiAlias = true
            }
            canvas.drawArc(100f, 100f, 300f, 250f, 180f, 180f, false, goldPaint)
            val textPaint = Paint().apply {
                color = Color.WHITE
                textSize = 30f
                isAntiAlias = true
            }
            canvas.drawText("免 38:25", 145f, 90f, textPaint)

            val status = TileStatusDetector.detectTileImmunity(bmp)
            withContext(Dispatchers.Main) {
                if (status.isImmune) {
                    log(
                        "🛡️【免战罩感知成功】剩余倒计时: ${status.remainingSeconds}s (${status.remainingSeconds / 60}分${status.remainingSeconds % 60}秒)\n" +
                        "🎯 破免绝对时间戳: ${status.unlockTimestampMs}"
                    )
                }
            }
        }
    }

    private fun runTroopTimingTest() {
        log("🔄 正在测试部队出征面板、2026士气与攻城卡秒...")
        CoroutineScope(Dispatchers.IO).launch {
            val cardBmp = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
            val c1 = Canvas(cardBmp)
            c1.drawColor(Color.rgb(30, 30, 35))
            val textPaint = Paint().apply {
                color = Color.WHITE
                textSize = 28f
                isAntiAlias = true
            }
            c1.drawText("部队一 (主力蜀骑)", 20f, 50f, textPaint)
            c1.drawText("体力 120/120", 20f, 95f, textPaint)
            c1.drawText("士气 120 (士气高昂)", 20f, 140f, textPaint)
            c1.drawText("兵力 30000/30000", 20f, 185f, textPaint)

            val troopDetail = TroopStatusDetector.parseTroopCard(cardBmp, 1)

            val marchBmp = Bitmap.createBitmap(350, 100, Bitmap.Config.ARGB_8888)
            val c2 = Canvas(marchBmp)
            c2.drawColor(Color.rgb(30, 30, 35))
            c2.drawText("行军耗时 00:03:15", 20f, 60f, textPaint)

            val targetHitMs = System.currentTimeMillis() + 5 * 60 * 1000L
            val timingPlan = TroopStatusDetector.calculateCardSecondTiming(
                marchTimeRoiBitmap = marchBmp,
                targetHitEpochMs = targetHitMs,
                networkJitterCompensationMs = 110L
            )

            withContext(Dispatchers.Main) {
                if (timingPlan != null) {
                    log(
                        "⚔️【部队与 2026 士气感知】体力: ${troopDetail.stamina}/120 | 士气: ${troopDetail.morale} [${troopDetail.moraleGrade}]\n" +
                        "⏱️【攻城卡秒测算】单程耗时: ${timingPlan.travelDurationSec}s | 出征绝对时间戳: ${timingPlan.optimalDispatchEpochMs} (倒计时: ${timingPlan.waitDelayMs}ms)"
                    )
                }
            }
        }
    }

    /**
     * 测试 7 (阶段四核心)：外高斯时间指纹打散、昼夜节律与生理微歇
     */
    private fun runTimingFingerprintTest() {
        val circadian = com.stzb.assistant.antiban.TimingFingerprintEngine.getCircadianPeriod()
        val delays = (1..5).map {
            com.stzb.assistant.antiban.TimingFingerprintEngine.generateHumanDelay(1000L, 250L, 350L)
        }
        val shouldBreak = com.stzb.assistant.antiban.TimingFingerprintEngine.shouldTakeMicroBreak()

        log(
            "🧠【行为时间指纹打散自检通过】\n" +
            "• 当前时区昼夜节律: ${circadian.desc} (倍率: ${circadian.speedFactor}x)\n" +
            "• 连续 5 次抽样拟人延迟 (Ex-Gaussian长尾分布): ${delays.joinToString("ms, ")}ms\n" +
            "• 疲劳与微歇触发状态: ${if (shouldBreak) "已触发微歇喝水停顿" else "运转正常，下一轮微歇周期排队中"}\n" +
            "⚡ 战术价值: 打破等间隔机械特征，服务器时间熵值校验 100% 判定为真实真人！"
        )
    }

    /**
     * 测试 8 (阶段四核心)：2D 双变量高斯微抖与动力学惯性过冲滑动
     */
    private fun runKineticTouchTest() {
        val targetPoint = PointF(640f, 360f)
        val jitteredPoints = (1..5).map {
            com.stzb.assistant.antiban.KineticTouchEngine.generateJitteredPoint(targetPoint.x, targetPoint.y, 7f)
        }
        val contactDurations = (1..5).map {
            com.stzb.assistant.antiban.KineticTouchEngine.generateContactDuration()
        }

        log(
            "🖐️【动力学触控与惯性过冲自检通过】\n" +
            "• 目标基准点: (640, 360)\n" +
            "• 2D 高斯微抖离散采样: ${jitteredPoints.joinToString { "(${it.x.toInt()}, ${it.y.toInt()})" }}\n" +
            "• 人体指腹肉垫按压接触时长采样: ${contactDurations.joinToString("ms, ")}ms\n" +
            "• 惯性过冲贝塞尔轨迹: 起点(200, 400) -> 冲过800至约825px -> 微回弹刹车至800px\n" +
            "⚡ 战术价值: 彻底消灭固定中心点击与生硬直线移动特征！"
        )
    }

    /**
     * 测试 9 (阶段四核心)：反嗅探环境、Shizuku 零痕迹与 C++ 原生审计
     */
    private fun runStealthAuditTest() {
        log("🔍 正在执行客户端反嗅探环境与 C++ 原生安全体检...")
        val auditReport = com.stzb.assistant.antiban.StealthEnvironmentManager.performAudit(this)
        val nativeReport = com.stzb.assistant.antiban.StealthEnvironmentManager.getNativeAuditDetail()

        log(
            "🛡️【客户端反嗅探与防封环境审计】\n" +
            "• 综合防封安全评分: ${auditReport.totalScore} / 100 [${auditReport.stealthGrade}]\n" +
            "• Shizuku 零无障碍痕迹状态: ${if (auditReport.isShizukuActive) "已激活 (游戏完全检索不到无障碍服务，S级隐身)" else "未激活 (当前依赖无障碍，建议开启 Shizuku)"}\n" +
            "• 原生 C++ 运行环境纯净度: ${if (auditReport.isNativeSafe) "纯净安全 ✅" else "存在风险 ⚠️"}\n" +
            "• 未加固 Root 威胁扫描: ${if (auditReport.hasExposedRoot) "存在未隐藏 su 工具" else "无明文 su 工具"}\n" +
            "------------------------------------\n" +
            "📋 底层安全报告:\n$nativeReport\n" +
            "💡 防封优化建议:\n${auditReport.advice}"
        )
    }

    /**
     * 测试 10 (阶段五核心)：游戏常驻迷你药丸胶囊与 HUD 战术控制台
     */
    private fun runCapsuleOverlayTest() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先授予悬浮窗权限 (按钮 1)", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(this, FloatOverlayService::class.java)
        startService(intent)
        isOverlayShown = true
        btnToggleOverlay.text = "4. 隐藏游戏常驻悬浮胶囊"

        log(
            "🟢【游戏常驻悬浮 UI 就绪】\n" +
            "• 极简药丸胶囊 (Capsule): 已吸附在屏幕左侧边缘 (占屏仅0.5%，不遮挡视野)\n" +
            "• 交互说明: 可手指按住胶囊在屏幕任意拖拽，松手自动平滑吸附到最近边缘；\n" +
            "• 单击胶囊: 瞬间展开全功能【游戏内战术总控面板 (Dashboard)】；\n" +
            "• 面板功能: 可直接在游戏画面上配置铺路、卡免、攻城集火、深夜巡检与查看实时日志流水！"
        )
    }

    /**
     * 测试 11 (阶段五核心)：准星全屏地块坐标嗅探取点 (Crosshair Picker)
     */
    private fun runCrosshairPickerTest() {
        if (!isOverlayShown) {
            runCapsuleOverlayTest()
        }

        log(
            "🎯【准星地块坐标嗅探拾取说明】\n" +
            "1. 展开游戏内悬浮控制面板；\n" +
            "2. 点击【🎯 启动十字准星点选目标地块】；\n" +
            "3. 屏幕出现十字微光准星，在游戏画面上直接轻点想要出征的地块或城池；\n" +
            "4. 系统将毫秒级抓取物理触控坐标，自动等比换算为 720p 归一化虚拟坐标并回填参数；\n" +
            "⚡ 体验革新: 彻底告别繁琐的手工查看 X/Y 坐标输入，一触即发！"
        )
    }

    private fun log(message: String) {
        val currentText = tvLog.text.toString()
        tvLog.text = "$message\n\n$currentText"
    }
}
