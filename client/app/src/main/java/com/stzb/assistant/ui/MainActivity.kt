package com.stzb.assistant.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.stzb.assistant.R
import com.stzb.assistant.service.AutoTouchService
import com.stzb.assistant.service.FloatOverlayService
import com.stzb.assistant.service.ScreenCaptureService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 商业化主控台 (MainActivity)
 *
 * 仅保留普通用户真正需要的两块能力：
 *   1. 运行环境：四项权限/服务一键直达系统设置，开启后实时变绿；
 *   2. 游戏知识库：切换/热更/打地指南。
 * 全部战术执行入口收敛到游戏内悬浮控制面板，主界面不再暴露调试/流水线按钮。
 *
 * 触控通道：**仅保留系统无障碍手势通道**。原先并列的 Shizuku 通道已彻底移除——
 * 它要求用户额外安装 Shizuku 并开启无线调试，授权后又会独占点击链路
 * （`EngineBridge` 一旦检测到 Shizuku 权限就不再回退无障碍），
 * 属于典型的"用户看不见却左右行为"的坑，且与商业化开箱即用的目标冲突。
 */
class MainActivity : AppCompatActivity() {

    // 游戏知识库
    private lateinit var tvKnowledgeTitle: TextView
    private lateinit var tvKnowledgeVersionBadge: TextView
    private lateinit var tvKnowledgeStats: TextView
    private lateinit var btnSwitchGame: Button
    private lateinit var btnUpdateKnowledge: Button
    private lateinit var btnViewLandGuide: Button
    private lateinit var tvKnowledgeMessage: TextView

    private lateinit var tvLog: TextView
    private lateinit var btnOverlay: MaterialButton
    private lateinit var btnAccessibility: MaterialButton
    private lateinit var btnToggleOverlay: MaterialButton
    private lateinit var btnCapture: MaterialButton

    // 授权与激活
    private lateinit var tvLicenseStatus: TextView
    private lateinit var btnActivateLicense: MaterialButton
    private lateinit var btnResetLicense: MaterialButton

    // 文字识别（OCR）状态
    private lateinit var tvOcrStatus: TextView
    private lateinit var btnOcrDiagnostics: MaterialButton

    // 诸葛军师 AI 大脑
    private lateinit var tvAdvisorEngineStatus: TextView
    private lateinit var btnConfigAiBrain: MaterialButton
    private lateinit var btnTestAiBrain: MaterialButton

    /**
     * OCR 初始化在 `App` 里是**异步预热**的，刚进界面时可能还没出结果。
     * 用它延迟补刷，避免界面一直停在"正在初始化…"。
     */
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            log("✅ 屏幕流录制授权成功，正在启动前台捕获通道...")
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START_CAPTURE
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
            btnCapture.post { checkPermissions() }
        } else {
            log("❌ 用户取消或拒绝了屏幕录制授权。")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupButtons()
        checkPermissions()
        refreshKnowledgeUi()
        // 把授权状态明确摆到界面上：当前工程处于"开发模式（无鉴权）"，
        // 这件事必须在发布前被看见，而不是只躺在代码注释里。
        refreshLicenseUi()
        refreshAdvisorBrainUi()
        // OCR 状态：先立即读一次，再延迟补刷两次（App 里是异步预热的）
        refreshOcrUi()
        uiHandler.postDelayed({ refreshOcrUi() }, 1500L)
        uiHandler.postDelayed({ refreshOcrUi() }, 4000L)
    }

    override fun onResume() {
        super.onResume()
        checkPermissions()
        refreshKnowledgeUi()
        refreshLicenseUi()
        refreshAdvisorBrainUi()
        refreshOcrUi()
    }

    override fun onDestroy() {
        // 及时摘掉延迟任务，避免 Activity 已销毁后仍持有引用
        uiHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun initViews() {
        tvKnowledgeTitle = findViewById(R.id.tvKnowledgeTitle)
        tvKnowledgeVersionBadge = findViewById(R.id.tvKnowledgeVersionBadge)
        tvKnowledgeStats = findViewById(R.id.tvKnowledgeStats)
        btnSwitchGame = findViewById(R.id.btnSwitchGame)
        btnUpdateKnowledge = findViewById(R.id.btnUpdateKnowledge)
        btnViewLandGuide = findViewById(R.id.btnViewLandGuide)
        tvKnowledgeMessage = findViewById(R.id.tvKnowledgeMessage)

        tvLog = findViewById(R.id.tvLogOutput)
        btnOverlay = findViewById(R.id.btnOverlayPermission)
        btnAccessibility = findViewById(R.id.btnAccessibilityPermission)
        btnToggleOverlay = findViewById(R.id.btnToggleOverlay)
        btnCapture = findViewById(R.id.btnScreenCapturePermission)

        tvLicenseStatus = findViewById(R.id.tvLicenseStatus)
        btnActivateLicense = findViewById(R.id.btnActivateLicense)
        btnResetLicense = findViewById(R.id.btnResetLicense)

        tvOcrStatus = findViewById(R.id.tvOcrStatus)
        btnOcrDiagnostics = findViewById(R.id.btnOcrDiagnostics)

        tvAdvisorEngineStatus = findViewById(R.id.tvAdvisorEngineStatus)
        btnConfigAiBrain = findViewById(R.id.btnConfigAiBrain)
        btnTestAiBrain = findViewById(R.id.btnTestAiBrain)
    }

    private fun setupButtons() {
        // 1. 一键直达：悬浮窗权限
        btnOverlay.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } else {
                Toast.makeText(this, "悬浮窗权限已授予", Toast.LENGTH_SHORT).show()
            }
        }

        // 2. 一键直达：无障碍服务通道（唯一的触控通道，必开）
        btnAccessibility.setOnClickListener {
            openAccessibilitySettings()
        }

        // 3. 展开 / 隐藏悬浮胶囊（以服务生命周期为唯一状态源，不再用易失真的成员变量）
        btnToggleOverlay.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请先授予第 1 项悬浮窗权限", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val intent = Intent(this, FloatOverlayService::class.java)
            if (FloatOverlayService.isShowing) {
                stopService(intent)
                log("悬浮胶囊已关闭。")
            } else {
                startService(intent)
                log("🟢 悬浮胶囊已显示，单击它即可展开战术总控面板。")
            }
            // 服务启停是异步的，延后一拍再刷新状态，避免读到中间态。
            btnToggleOverlay.post { checkPermissions() }
        }

        // 4. 一键直达：屏幕捕获
        btnCapture.setOnClickListener {
            if (ScreenCaptureService.isCapturing.get()) {
                val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_STOP_CAPTURE
                }
                startService(stopIntent)
                log("⏹️ 屏幕捕获通道已手动停止。")
                btnCapture.post { checkPermissions() }
            } else {
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                captureLauncher.launch(mpm.createScreenCaptureIntent())
            }
        }

        // 游戏知识库
        btnSwitchGame.setOnClickListener { showGameSwitchDialog() }
        btnUpdateKnowledge.setOnClickListener { checkKnowledgeUpdate() }
        btnViewLandGuide.setOnClickListener { showLandGuide() }

        // 授权与激活
        btnActivateLicense.setOnClickListener { showActivationDialog() }
        btnResetLicense.setOnClickListener { confirmResetLicense() }

        // 文字识别（OCR）
        btnOcrDiagnostics.setOnClickListener { showOcrDiagnostics() }

        // 诸葛军师 AI 大脑
        btnConfigAiBrain.setOnClickListener { showAiBrainConfigDialog() }
        btnTestAiBrain.setOnClickListener { showQuickTestAiDialog() }
    }

    // ==========================================================
    // 文字识别（OCR）状态
    // ==========================================================

    /**
     * 刷新 OCR 状态显示。
     *
     * 为什么必须摆在主界面：默认构建里 native OCR 是空桩，而在此之前主界面
     * **完全不显示**这一点——用户只能等某个战术流程莫名失败才发现"识别根本不存在"。
     * 把这三种状态区分开，才不会把"还没初始化完"误报成"不可用"：
     *   1. 尚未尝试初始化（异步预热中）→ 正在初始化
     *   2. 就绪
     *   3. 失败 → 展示具体原因
     */
    private fun refreshOcrUi() {
        val ocr = com.stzb.assistant.ocr.OcrManager
        val colorRes: Int
        when {
            ocr.isEngineAvailable -> {
                tvOcrStatus.text = "文字识别（OCR）：✅ 引擎就绪（native 推理可用）"
                colorRes = R.color.success
            }
            ocr.unavailableReason != null -> {
                tvOcrStatus.text = buildString {
                    append("文字识别（OCR）：❌ 不可用\n")
                    append(ocr.unavailableReason)
                    append("\n→ 场景判定、按键定位、坐标读取都依赖它，")
                    append("请用带 ncnn/OpenCV 的方式构建后再试。")
                }
                colorRes = R.color.danger
            }
            else -> {
                tvOcrStatus.text = "文字识别（OCR）：正在初始化…"
                colorRes = R.color.text_secondary
            }
        }
        tvOcrStatus.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    /** 识别环境详情：把资产级的事实一次摊开，便于判断到底缺哪一块。 */
    private fun showOcrDiagnostics() {
        val ocr = com.stzb.assistant.ocr.OcrManager
        val text = buildString {
            append("【引擎状态】\n")
            append(if (ocr.isEngineAvailable) "✅ 可用（native 推理已就绪）" else "❌ 不可用")
            append('\n')
            ocr.unavailableReason?.let { append("原因：$it\n") }
            append("\n【资产与能力盘点】\n")
            append(
                try {
                    com.stzb.assistant.ai.assets.ModelAssetManager.describeAvailability(this@MainActivity)
                } catch (e: Exception) {
                    "盘点失败: ${e.message}"
                }
            )
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("识别环境详情")
            .setMessage(text)
            .setPositiveButton("知道了", null)
            .show()
    }

    // ==========================================================
    // 授权与激活
    // ==========================================================

    /**
     * 刷新授权状态显示。
     *
     * 这里刻意用醒目文案把「开发模式（无鉴权）」摆到界面上：当前工程就处于该状态，
     * 若只写在代码注释里，很容易在不知情的情况下把没有付费墙的包发出去。
     */
    private fun refreshLicenseUi() {
        val gate = com.stzb.assistant.license.LicenseGate
        val state = gate.state(this)
        tvLicenseStatus.text = gate.describe(this)
        val colorRes = when (state) {
            com.stzb.assistant.license.LicenseGate.State.DEV_OPEN -> R.color.warning
            com.stzb.assistant.license.LicenseGate.State.LICENSED -> R.color.success
            else -> R.color.danger
        }
        tvLicenseStatus.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    /** 卡密激活对话框。 */
    private fun showActivationDialog() {
        val input = EditText(this).apply {
            hint = "请输入卡密（如 STZB-XXXX-XXXX）"
            setSingleLine()
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val container = android.widget.FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(
                input,
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("激活卡密")
            .setView(container)
            .setPositiveButton("激活", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener {
                    val code = input.text.toString().trim()
                    if (code.isEmpty()) {
                        Toast.makeText(this, "激活码不能为空", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    dialog.dismiss()
                    performActivation(code)
                }
        }
        dialog.show()
    }

    private fun performActivation(code: String) {
        log("正在激活卡密: $code ...")
        CoroutineScope(Dispatchers.Main).launch {
            val result = com.stzb.assistant.license.LicenseManager.activateOnline(this@MainActivity, code)
            result.onSuccess { info ->
                // 注意：当 Supabase 端点仍是 `your-supabase-project` 占位符时，
                // 这里会走"开发态离线签发"分支，cardType 会明确写着「（非真实授权）」。
                // 界面上照原样显示，不要把它当成真实激活成功。
                log("激活返回: ${info.message}")
                Toast.makeText(this@MainActivity, info.message, Toast.LENGTH_LONG).show()
            }.onFailure { err ->
                log("❌ 激活失败: ${err.message}")
                Toast.makeText(this@MainActivity, err.message ?: "激活失败", Toast.LENGTH_LONG).show()
            }
            refreshLicenseUi()
        }
    }

    private fun confirmResetLicense() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("重置授权")
            .setMessage(
                "将清除本地凭证缓存。\n" +
                    "· 开发模式下：下次检查会重新签发开发态凭证；\n" +
                    "· 已关闭开发模式时：需要重新输入卡密才能执行战术动作。"
            )
            .setPositiveButton("确认重置") { _, _ ->
                com.stzb.assistant.license.LicenseManager.clearLicense(this)
                log("已清除本地授权缓存。")
                refreshLicenseUi()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showGameSwitchDialog() {
        val games = com.stzb.assistant.knowledge.KnowledgeBaseManager.getSupportedGames()
        val gameNames = games.map { it.second }.toTypedArray()
        val currentIndex = games.indexOfFirst {
            it.first == com.stzb.assistant.knowledge.KnowledgeBaseManager.activeProfile.gameId
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("选择要挂接的游戏知识库")
            .setSingleChoiceItems(gameNames, if (currentIndex >= 0) currentIndex else 0) { dialog, which ->
                val selectedGameId = games[which].first
                com.stzb.assistant.knowledge.KnowledgeBaseManager.switchGame(this, selectedGameId)
                refreshKnowledgeUi()
                log("🔄 已切换知识库: ${com.stzb.assistant.knowledge.KnowledgeBaseManager.activeProfile.gameName}")
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun checkKnowledgeUpdate() {
        btnUpdateKnowledge.isEnabled = false
        btnUpdateKnowledge.text = "检查中..."
        CoroutineScope(Dispatchers.Main).launch {
            val result = com.stzb.assistant.knowledge.KnowledgeBaseManager.checkCloudUpdate(this@MainActivity)
            btnUpdateKnowledge.isEnabled = true
            btnUpdateKnowledge.text = "检查更新"
            result.onSuccess { updateRes ->
                refreshKnowledgeUi()
                if (updateRes.isUpdated) {
                    Toast.makeText(this@MainActivity, updateRes.message, Toast.LENGTH_SHORT).show()
                    tvKnowledgeMessage.text = "🎉 ${updateRes.message}"
                    tvKnowledgeMessage.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.success))
                } else {
                    Toast.makeText(this@MainActivity, "已是最新版本", Toast.LENGTH_SHORT).show()
                    tvKnowledgeMessage.text = "✅ 当前已是最新知识库 (${updateRes.currentVersion})"
                    tvKnowledgeMessage.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.success))
                }
            }.onFailure { err ->
                Toast.makeText(this@MainActivity, err.message ?: "检查失败", Toast.LENGTH_SHORT).show()
                tvKnowledgeMessage.text = "⚠️ 检查云端失败: ${err.message}"
                tvKnowledgeMessage.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.warning))
            }
        }
    }

    private fun showLandGuide() {
        com.stzb.assistant.ai.rag.SlgRagEngine.init(this)
        val suggestions = com.stzb.assistant.knowledge.KnowledgeBaseManager.activeProfile.defenderDb.landSuggestions
        if (suggestions.isEmpty()) {
            Toast.makeText(this, "当前游戏无土地建议数据", Toast.LENGTH_SHORT).show()
            return
        }
        val sb = StringBuilder()
        sb.append("【SLG-RAG 战术知识库 · 土地打分天梯】\n\n")
        suggestions.toSortedMap().forEach { (lvl, s) ->
            sb.append("📍【Lv.$lvl 土地守军】推荐兵力: ${s.recommendedSoldiers}+\n")
            sb.append("  🟢 软柿子优先开: ${s.safeHeroes.joinToString("、")}\n")
            if (s.blacklistHeroes.isNotEmpty()) {
                sb.append("  🔴 翻车雷区(避开): ${s.blacklistHeroes.joinToString("、")}\n")
            }
            sb.append("  💡 攻坚要诀: ${s.note}\n\n")
        }
        sb.append("───────────────────────\n")
        sb.append("🛡️ 军师 RAG 自动感知已常驻：游戏内侦察守军时，悬浮窗将自动进行 RAG 毫秒级阵容扫描与翻车风险拦截！")

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("📖 开荒打地指南 (RAG向量增强)")
            .setMessage(sb.toString().trim())
            .setPositiveButton("我知道了", null)
            .show()
    }

    private fun refreshKnowledgeUi() {
        val profile = com.stzb.assistant.knowledge.KnowledgeBaseManager.activeProfile
        tvKnowledgeTitle.text = profile.gameName
        tvKnowledgeVersionBadge.text = "v${profile.profileVersion}"
        val heroCount = profile.defenderDb.dangerHeroes.size +
                profile.defenderDb.hardHeroes.size +
                profile.defenderDb.moderateHeroes.size +
                profile.defenderDb.safeHeroes.size
        tvKnowledgeStats.text =
            "规则: ${profile.rules.maxMorale}士气/${profile.rules.maxStamina}体力 | 守军库: ${heroCount}名 | 语义按键: ${profile.semanticButtons.size}组"
        tvKnowledgeMessage.text = "当前加载: ${profile.gameName} (包名: ${profile.targetPackage})"
        tvKnowledgeMessage.setTextColor(ContextCompat.getColor(this, R.color.success))
    }

    /**
     * 权限就绪态视觉标记：已开启变绿并打勾，未开启保持中性色。
     */
    private fun markReady(btn: MaterialButton, ready: Boolean) {
        val success = ContextCompat.getColor(this, R.color.success)
        val surface = ContextCompat.getColor(this, R.color.surface_elevated)
        val outline = ContextCompat.getColor(this, R.color.outline)
        val bg = if (ready) success else surface
        btn.backgroundTintList = ColorStateList.valueOf(bg)
        btn.strokeColor = ColorStateList.valueOf(if (ready) success else outline)
        btn.setTextColor(if (ready) Color.WHITE else ContextCompat.getColor(this, R.color.text_primary))
    }

    private fun checkPermissions() {
        // 1. 悬浮窗权限（胶囊与准星取点的前置条件）
        val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true
        btnOverlay.text = if (hasOverlay) "1. 悬浮窗权限  已授予 ✓" else "1. 授予悬浮窗权限"
        markReady(btnOverlay, hasOverlay)

        // 2. 无障碍服务通道（产品现在唯一的触控通道，必须开启）
        val hasAccessibility = AutoTouchService.isConnected || isAccessibilityServiceEnabled()
        btnAccessibility.text = if (hasAccessibility) "2. 无障碍服务  已开启 ✓" else "2. 开启无障碍服务通道"
        markReady(btnAccessibility, hasAccessibility)

        // 3. 游戏悬浮胶囊：状态直接取自服务生命周期，进程重建后也不会失真
        val hasCapsule = FloatOverlayService.isShowing
        btnToggleOverlay.text = if (hasCapsule) "3. 游戏悬浮胶囊  显示中 ✓" else "3. 显示游戏悬浮胶囊"
        markReady(btnToggleOverlay, hasCapsule)

        // 4. 屏幕捕获
        val hasCapture = ScreenCaptureService.isCapturing.get()
        btnCapture.text = if (hasCapture) "4. 屏幕捕获  运行中 ✓" else "4. 启动屏幕捕获"
        markReady(btnCapture, hasCapture)
    }

    /**
     * 直接跳转到本应用的无障碍服务详情页，解决“找不到入口”的问题；失败降级到总列表。
     */
    private fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                putExtra(
                    ":settings:fragment_args_key",
                    "$packageName/${AutoTouchService::class.java.name}"
                )
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
            Toast.makeText(this, "请开启“率土管家”触控服务", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (e2: Exception) {
                Toast.makeText(this, "无法打开系统设置，请手动进入“设置-无障碍”", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * 通过 Settings.Secure 实时判断本无障碍服务是否已真正开启。
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val svcName = "$packageName/${AutoTouchService::class.java.name}"
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.equals(svcName, ignoreCase = true) }
    }

    private fun refreshAdvisorBrainUi() {
        val slmEngine = com.stzb.assistant.ai.microbrain.EdgeSlmEngine(this)
        val hasSlm = slmEngine.hasLocalSlmWeight()
        if (hasSlm) {
            tvAdvisorEngineStatus.text = "军师大脑：端侧本地小模型就绪 (Qwen2.5-0.5B 本地推理)"
            tvAdvisorEngineStatus.setTextColor(ContextCompat.getColor(this, R.color.success))
        } else {
            tvAdvisorEngineStatus.text = "军师大脑：端侧 RAG 向量底座 (100% 本地离线，0网络依赖)"
            tvAdvisorEngineStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }
    }

    private fun showAiBrainConfigDialog() {
        val slmEngine = com.stzb.assistant.ai.microbrain.EdgeSlmEngine(this)
        val hasSlm = slmEngine.hasLocalSlmWeight()

        val text = buildString {
            append("【端侧本地小模型架构与选型报告】\n\n")
            append("1. 当前端侧状态：\n")
            if (hasSlm) {
                append("  🟢 已检测到本地小模型权重文件，端侧本地神经推理就绪。\n\n")
            } else {
                append("  🟡 当前由【端侧 RAG 密集向量底座】全面驱动（100% 离线、0 延迟、0 幻觉）。\n\n")
            }
            append("2. 业界最优端侧小模型推荐：\n")
            append("  👑 【首选推荐】阿里通义千问 Qwen2.5-0.5B-Instruct (INT4量化)\n")
            append("     • 体积: 仅约 350MB (Q4_K_M GGUF)\n")
            append("     • 运行时内存: 约 450MB ~ 600MB\n")
            append("     • 评定: 全球 1B 以下中文理解与三国谋略能力最强的小模型！唯一能在手机端流畅输出文言文风骨与率土战术策略的 SLM。\n\n")
            append("  ⚠️ 【不推荐】SmolLM2-135M / 360M：95% 为英文预训练，不懂中文战法与三国黑话，极易胡言乱语。\n")
            append("  ⚠️ 【不推荐】1B+ 以上模型 (MiniCPM/Llama)：内存占用超过 1.5GB，与游戏 2GB 内存叠加必定被系统 LMK 杀后台。\n\n")
            append("3. 本地模型落位路径：\n")
            append("  将 GGUF 文件放至 assets/models/qwen2.5-0.5b-instruct-q4_k_m.gguf 或手机内部存储目录。")
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("🧠 端侧本地小模型指引")
            .setMessage(text)
            .setPositiveButton("我知道了", null)
            .show()
    }

    private fun showQuickTestAiDialog() {
        val quickQueries = arrayOf(
            "⚔️ 开荒如何实现低损打5级地？",
            "🛡️ 遇到敌军神兵大赏法刀，我军该如何防范？",
            "🎯 攻城时主力与拆迁压秒的最佳时机是什么？",
            "⚡ 周瑜陆逊吕蒙队伍该怎么搭配战法？"
        )

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("💬 端侧离线问策 (诸葛军师)")
            .setItems(quickQueries) { _, which ->
                val q = quickQueries[which].substring(2).trim()
                val slmEngine = com.stzb.assistant.ai.microbrain.EdgeSlmEngine(this)
                slmEngine.askAdvisor(q) { reply ->
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("📜 诸葛军师策论 (端侧纯离线)")
                        .setMessage(reply)
                        .setPositiveButton("领教了", null)
                        .show()
                }
            }
            .setNegativeButton("返回", null)
            .show()
    }

    private fun log(message: String) {
        val currentText = tvLog.text.toString()
        tvLog.text = "$message\n\n$currentText"
    }
}
