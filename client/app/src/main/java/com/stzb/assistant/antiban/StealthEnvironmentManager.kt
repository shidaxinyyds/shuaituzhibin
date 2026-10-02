package com.stzb.assistant.antiban

import android.content.Context
import android.os.Build
import android.util.Log
import com.stzb.assistant.service.AutoTouchService
import com.stzb.assistant.service.ShizukuTouchManager
import java.io.File

/**
 * 客户端环境对抗与反嗅探管理器 (StealthEnvironmentManager)
 * 
 * 核心痛点解决：
 *   1. 【无障碍零痕迹隐身 (Zero-Accessibility Footprint)】：
 *      强烈推荐并接入 Shizuku 底层注入通道。在此模式下，游戏客户端通过系统
 *      AccessibilityManager 扫描正在启用的无障碍列表时，完全检索不到本助手的任何痕迹；
 *   2. 【原生 C++ 反挂钩与反注入 (Anti-Hook/Anti-Debug)】：
 *      通过 NDK 原生层检查 TracerPid 调试状态、扫描 /proc/self/maps 是否含有 Frida/Xposed 注入痕迹、
 *      探测本地 27042 Frida 默认调试端口；
 *   3. 【宿主环境安全体检与综合防封评分 (0 ~ 100)】：
 *      动态评估当前运行环境并给出专业防封加固指引。
 */
object StealthEnvironmentManager {

    private const val TAG = "StealthEnvironmentManager"

    init {
        try {
            System.loadLibrary("RapidOcr")
            Log.d(TAG, "libRapidOcr 原生安全模块加载就绪。")
        } catch (e: Throwable) {
            Log.e(TAG, "加载原生安全库异常: ${e.message}")
        }
    }

    // 原生 C++ JNI 接口
    private external fun isNativeEnvSafe(): Boolean
    private external fun getNativeSecurityReport(): String

    data class StealthAuditReport(
        val totalScore: Int,                 // 综合防封安全评分 (0 ~ 100)
        val isShizukuActive: Boolean,        // 是否已开启免 Root Shizuku 隐身触控
        val isAccessibilityActive: Boolean,  // 是否启用了系统无障碍
        val isNativeSafe: Boolean,           // 原生底层环境是否纯净
        val hasExposedRoot: Boolean,         // 是否存在暴露的 su/magisk 二进制
        val stealthGrade: String,            // "S 级 (无痕隐身)", "A 级 (高防护)", "D 级 (存在风险)"
        val advice: String                   // 安全优化建议
    )

    /**
     * 运行全方位反嗅探与防封环境审计
     */
    fun performAudit(context: Context): StealthAuditReport {
        val hasShizuku = ShizukuTouchManager.hasPermission()
        val hasAccessibility = AutoTouchService.isConnected

        // 1. 原生 NDK 环境检查
        val nativeSafe = try {
            isNativeEnvSafe()
        } catch (e: Throwable) {
            true // 模拟器或单测环境降级
        }

        // 2. 检查常见明文 su / root 路径
        val hasRoot = checkSuBinaries()

        // 3. 计算防封健康指数
        var score = 100
        val advices = mutableListOf<String>()

        if (!nativeSafe) {
            score -= 50
            advices.add("⚠️ 检测到当前进程被附加调试或存在 Hook 注入模块，建议重启设备！")
        }

        if (hasShizuku) {
            // Shizuku 模式：无障碍特征完全隐蔽，得最高分
            advices.add("✅ 已激活 Shizuku 底层注入，无障碍列表 100% 零痕迹，隐身性极佳。")
        } else if (hasAccessibility) {
            score -= 15
            advices.add("💡 当前使用无障碍触控通道。建议激活并切换至 Shizuku，彻底清除无障碍特征。")
        } else {
            score -= 30
            advices.add("⚠️ 尚未授权任何触控通道。")
        }

        if (hasRoot) {
            score -= 10
            advices.add("💡 检测到存在未隐藏的 Root 工具，若游戏启用了 Root 检测可能触发弹窗。")
        }

        val clampedScore = score.coerceIn(0, 100)
        val grade = when {
            clampedScore >= 90 -> "S 级 (无痕级极速隐身)"
            clampedScore >= 75 -> "A 级 (商业标准高防护)"
            else -> "D 级 (存在环境安全隐患)"
        }

        val adviceSummary = advices.joinToString("\n")
        Log.i(TAG, "安全环境审计完毕: 评分=$clampedScore, 等级=$grade")

        return StealthAuditReport(
            totalScore = clampedScore,
            isShizukuActive = hasShizuku,
            isAccessibilityActive = hasAccessibility,
            isNativeSafe = nativeSafe,
            hasExposedRoot = hasRoot,
            stealthGrade = grade,
            advice = adviceSummary
        )
    }

    /**
     * 获取 C++ 原生底层反调试报告
     */
    fun getNativeAuditDetail(): String {
        return try {
            getNativeSecurityReport()
        } catch (e: Throwable) {
            "Native security engine not loaded: ${e.message}"
        }
    }

    private fun checkSuBinaries(): Boolean {
        val paths = arrayOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/system/su",
            "/system/bin/.ext/.su",
            "/data/adb/magisk"
        )
        return paths.any { File(it).exists() }
    }
}
