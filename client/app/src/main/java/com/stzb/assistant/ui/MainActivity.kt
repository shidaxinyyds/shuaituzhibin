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
import com.stzb.assistant.service.ShizukuTouchManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 商业化主控台 (MainActivity)
 *
 * 仅保留普通用户真正需要的两块能力：
 *   1. 运行环境：三项权限一键直达系统设置，开启后实时变绿；
 *   2. 游戏知识库：切换/热更/打地指南。
 * 全部战术执行入口收敛到游戏内悬浮控制面板，主界面不再暴露调试/流水线按钮。
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
    private lateinit var btnCapture: MaterialButton
    private lateinit var btnOverlay: MaterialButton
    private lateinit var btnShizuku: MaterialButton
    private lateinit var btnAccessibility: MaterialButton
    private lateinit var btnToggleOverlay: MaterialButton

    private var isOverlayShown = false

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
    }

    override fun onResume() {
        super.onResume()
        checkPermissions()
        refreshKnowledgeUi()
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
        btnCapture = findViewById(R.id.btnScreenCapturePermission)
        btnOverlay = findViewById(R.id.btnOverlayPermission)
        btnShizuku = findViewById(R.id.btnShizukuPermission)
        btnAccessibility = findViewById(R.id.btnAccessibilityPermission)
        btnToggleOverlay = findViewById(R.id.btnToggleOverlay)
    }

    private fun setupButtons() {
        // 1. 一键直达：屏幕捕获
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

        // 2. 一键直达：悬浮窗权限
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

        // 3. 一键直达：Shizuku 触控通道
        btnShizuku.setOnClickListener {
            when {
                ShizukuTouchManager.hasPermission() -> {
                    Toast.makeText(this, "Shizuku 触控通道已就绪，状态极佳 ✓", Toast.LENGTH_SHORT).show()
                }
                ShizukuTouchManager.isShizukuAvailable() -> {
                    ShizukuTouchManager.requestPermission(this)
                    Toast.makeText(this, "正在请求 Shizuku 底层授权...", Toast.LENGTH_SHORT).show()
                }
                else -> {
                    // 尝试拉起 Shizuku 应用
                    val shizukuIntent = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                    if (shizukuIntent != null) {
                        startActivity(shizukuIntent)
                        Toast.makeText(this, "正在打开 Shizuku，请启动服务后返回", Toast.LENGTH_LONG).show()
                    } else {
                        // 一键直达系统开发者选项（无线调试）
                        try {
                            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                            Toast.makeText(this, "未检测到 Shizuku，已直达开发者选项 (开启无线调试)；亦可直接开启第 4 项无障碍服务", Toast.LENGTH_LONG).show()
                        } catch (e: Exception) {
                            Toast.makeText(this, "建议安装 Shizuku，或直接开启下方第 4 项无障碍服务", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }

        // 4. 一键直达：无障碍服务通道
        btnAccessibility.setOnClickListener {
            openAccessibilitySettings()
        }

        // 5. 展开 / 隐藏悬浮胶囊
        btnToggleOverlay.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请先授予第 2 项悬浮窗权限", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val intent = Intent(this, FloatOverlayService::class.java)
            if (isOverlayShown) {
                stopService(intent)
                isOverlayShown = false
                btnToggleOverlay.text = "5. 显示游戏悬浮胶囊"
                log("悬浮胶囊已关闭。")
            } else {
                startService(intent)
                isOverlayShown = true
                btnToggleOverlay.text = "5. 隐藏游戏悬浮胶囊"
                log("🟢 悬浮胶囊已显示，单击它即可展开战术总控面板。")
            }
        }

        // 游戏知识库
        btnSwitchGame.setOnClickListener { showGameSwitchDialog() }
        btnUpdateKnowledge.setOnClickListener { checkKnowledgeUpdate() }
        btnViewLandGuide.setOnClickListener { showLandGuide() }
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
        val suggestions = com.stzb.assistant.knowledge.KnowledgeBaseManager.activeProfile.defenderDb.landSuggestions
        if (suggestions.isEmpty()) {
            Toast.makeText(this, "当前游戏无土地建议数据", Toast.LENGTH_SHORT).show()
            return
        }
        val sb = StringBuilder()
        suggestions.toSortedMap().forEach { (lvl, s) ->
            sb.append("【Lv.$lvl 土地指南】推荐兵力: ${s.recommendedSoldiers}+\n")
            sb.append("  • 软柿子优先打: ${s.safeHeroes.joinToString("、")}\n")
            if (s.blacklistHeroes.isNotEmpty()) {
                sb.append("  • 黑名单千万别撞: ${s.blacklistHeroes.joinToString("、")}\n")
            }
            sb.append("  • 策略: ${s.note}\n\n")
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("📖 开荒打地天梯指南")
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
        // 1. 屏幕捕获
        val hasCapture = ScreenCaptureService.isCapturing.get()
        btnCapture.text = if (hasCapture) "1. 屏幕捕获  运行中 ✓" else "1. 启动屏幕捕获"
        markReady(btnCapture, hasCapture)

        // 2. 悬浮窗权限
        val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true
        btnOverlay.text = if (hasOverlay) "2. 悬浮窗权限  已授予 ✓" else "2. 授予悬浮窗权限"
        markReady(btnOverlay, hasOverlay)

        // 3. Shizuku 触控
        val hasShizuku = ShizukuTouchManager.hasPermission()
        btnShizuku.text = if (hasShizuku) "3. Shizuku 触控  已授权 ✓" else "3. 授权 Shizuku 触控"
        markReady(btnShizuku, hasShizuku)

        // 4. 无障碍服务通道
        val hasAccessibility = AutoTouchService.isConnected || isAccessibilityServiceEnabled()
        btnAccessibility.text = if (hasAccessibility) "4. 无障碍服务  已开启 ✓" else "4. 开启无障碍服务通道"
        markReady(btnAccessibility, hasAccessibility)
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

    private fun log(message: String) {
        val currentText = tvLog.text.toString()
        tvLog.text = "$message\n\n$currentText"
    }
}
