package com.stzb.assistant.service

import android.util.Log
import com.stzb.assistant.knowledge.KnowledgeBaseManager

/**
 * 前台闸门 (ForegroundGate)
 *
 * 解决的是"游魂点击"这一类最伤信任的故障：
 *   游戏被切到后台、被系统杀掉、弹了个系统对话框、或者用户正停在助手的设置页上——
 *   而流水线毫不知情，继续按着上一轮算出来的坐标往屏幕上砸手势。
 * 这些坐标是从游戏画面推出来的，落到别的应用上就是**在别人界面里乱点**：
 * 轻则把玩家的微信/浏览器翻得乱七八糟，重则点到某个"确认支付""立即迁城"。
 * 对一款要收费的工具来说，这一条就足以毁掉口碑。
 *
 * 判据来自知识库的 `GameProfile.targetPackage`（率土 com.netease.stzb /
 * 三战 com.aligames.sgzzlb），所以**换游戏或改包名只需热更知识库**，
 * 不再需要动代码里的一串字面量。
 *
 * ## 取值优先级（两条通道，都为"读不到"留了出口）
 *   1. [AutoTouchService.rootInActiveWindow] 的包名——当前**持有焦点**的窗口。
 *      这是最准的一手信息，而且不会过期：助手的悬浮窗是非焦点窗口，
 *      不会把自己报成前台；只有助手的 Activity 真被打开时才会报成本应用。
 *   2. 事件流里最后一次观测到的前台包名（见 [AutoTouchService.foregroundPackage]）——
 *      部分 ROM 上 rootInActiveWindow 会返回 null，此时退回到事件观测值。
 *
 * ## 判不出来时怎么办：放行，但说清楚
 *   读不到任何前台信息（服务刚连上、还没发生任何窗口切换）时**放行**。
 *   理由：此时拦下来并不能保护任何东西，只会让整个产品在所有机型上立刻瘫痪；
 *   而"游戏一直停留在前台、期间没有任何窗口变化"恰恰是最常见的正常挂机状态。
 *   放行会打一条 INFO，便于排查"为什么这轮没拦住"。
 *
 *   反过来，只要**明确看到了别的应用在前台**，就一律拦下——这是本闸门唯一
 *   必须硬起来的情形，因为此时每一次派发都确定会点在错的地方。
 */
object ForegroundGate {

    private const val TAG = "ForegroundGate"

    /** 判定结果；把"为什么放行"区分开，日志才不会只有一句"ok"。 */
    enum class Verdict {
        /** 目标游戏确实在前台 */
        TARGET_FOREGROUND,

        /** 知识库没填包名，没有判据 */
        NO_EXPECTED_PACKAGE,

        /** 无障碍服务未连接，无从判断（后续派发本身也会失败） */
        SERVICE_UNAVAILABLE,

        /** 两条通道都没给出前台包名 */
        UNKNOWN_FOREGROUND_ALLOW,

        /** 确认前台不是目标游戏：必须拦 */
        BLOCKED_NOT_FOREGROUND
    }

    /**
     * 本次盲点派发是否允许。
     *
     * @param op 操作名（"点击"/"滑动"/"缩放"），只用于日志与状态回报。
     * @return true = 放行；false = 拦下，调用方**不得**再派发手势。
     */
    fun allowBlindDispatch(op: String): Boolean {
        val verdict = evaluate()
        return when (verdict) {
            Verdict.TARGET_FOREGROUND -> true

            Verdict.NO_EXPECTED_PACKAGE -> {
                // 只在缺判据时提醒一次方向，不刷屏：知识库校验本来就该挡住空包名。
                Log.w(TAG, "当前知识库未填写目标包名，$op 无法做前台校验（请检查 target_package）。")
                true
            }

            Verdict.SERVICE_UNAVAILABLE -> {
                Log.w(TAG, "无障碍服务未连接，$op 跳过前台校验（手势本身也会失败）。")
                true
            }

            Verdict.UNKNOWN_FOREGROUND_ALLOW -> {
                Log.i(TAG, "暂未观测到前台窗口归属，$op 按放行处理（可能刚接入服务、尚无窗口变化）。")
                true
            }

            Verdict.BLOCKED_NOT_FOREGROUND -> {
                val (expected, actual) = snapshot()
                Log.w(TAG, "⛔ 已拦截一次$op：目标游戏 [$expected] 不在前台，当前前台是 [$actual]。")
                false
            }
        }
    }

    /**
     * 是否**明确**看到“前台不是目标游戏”。
     *
     * 只认 [Verdict.BLOCKED_NOT_FOREGROUND]：判不出来的时候绝不能报“不在前台”，
     * 否则刚接入服务、还没发生任何窗口切换的正常挂机也会被当成故障。
     * 调用方需要的是“肯定不在了”，而不是“不确定在不在”——所以这里不采用
     * “verdict != TARGET_FOREGROUND”那种看似等价的写法。
     */
    fun isDefinitelyNotTargetForeground(): Boolean = evaluate() == Verdict.BLOCKED_NOT_FOREGROUND

    private fun evaluate(): Verdict {
        val expected = KnowledgeBaseManager.activeProfile.targetPackage
        if (expected.isBlank()) return Verdict.NO_EXPECTED_PACKAGE

        val svc = AutoTouchService.instance ?: return Verdict.SERVICE_UNAVAILABLE

        val actual = observedForeground(svc) ?: return Verdict.UNKNOWN_FOREGROUND_ALLOW
        if (actual == expected) return Verdict.TARGET_FOREGROUND

        // 明确看到别的包在前台（包括助手自己的 Activity：那意味着玩家正看着设置页，
        // 游戏画面根本不在屏幕上）——一律拦下。
        return Verdict.BLOCKED_NOT_FOREGROUND
    }

    /** 焦点窗口优先，事件观测值兜底。 */
    private fun observedForeground(svc: AutoTouchService): String? {
        val focused = try {
            svc.rootInActiveWindow?.packageName?.toString()?.takeIf { it.isNotBlank() }
        } catch (t: Throwable) {
            // 个别 ROM 在窗口切换的瞬间会抛异常；当作"这一路没读到"，继续用事件值。
            Log.d(TAG, "读取焦点窗口包名异常，改用事件观测值: ${t.javaClass.simpleName}")
            null
        }
        return focused ?: svc.foregroundPackage?.takeIf { it.isNotBlank() }
    }

    /** 供日志使用的一对 (期望包名, 实际观测包名)。 */
    private fun snapshot(): Pair<String, String> {
        val expected = KnowledgeBaseManager.activeProfile.targetPackage
        val actual = AutoTouchService.instance?.let { observedForeground(it) } ?: "未知"
        return expected to actual
    }
}
