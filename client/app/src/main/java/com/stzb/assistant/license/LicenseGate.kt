package com.stzb.assistant.license

import android.content.Context
import android.util.Log

/**
 * 授权门禁 (LicenseGate)
 *
 * ## 为什么要有这个文件
 * 排查"商业化落地"时发现：整个卡密体系**从未被任何界面或执行路径调用过**。
 * 具体证据：
 *   * `LicenseManager.checkLocalLicense()` / `activateOnline()` 在工程内**零调用**
 *     （唯一引用是 `KnowledgeBaseManager` 借它的 URL 做知识库热更）；
 *   * 即使调用，`checkLocalLicense()` 会在首次安装时**自己签发**一张永久旗舰凭证；
 *   * `cp/SecurityBridge.cpp` 对签发用的魔法签名 `COMMERCIAL_MASTER_PERPETUAL` 无条件放行。
 *
 * 三者叠加的结果是：**应用没有任何付费墙**，任何安装者都自动获得"商业旗舰永久版"。
 * 后端 SQL、Edge Function、`tools/gen_cards.py` 全都在，但没有一个环节真正把关。
 *
 * ## 本类做什么
 * 它不改变默认行为（那是刻意的，避免把你自己锁在门外），而是把"无鉴权"这件事
 * **变成一个显式、可见、可一键关闭的开关**：
 *   * [DEVELOPMENT_MODE_OPEN_ACCESS] 默认 `true` → 与修复前行为完全一致，不影响使用；
 *   * 但日志与主界面会**明确显示当前处于"开发模式：无鉴权"**，
 *     并且每次进入都会打出醒目警告，杜绝"不小心把无付费墙的包发出去"；
 *   * 把它改成 `false` 后，[isExecutionAllowed] 就会要求真实有效的本地凭证，
 *     届时悬浮窗的战术执行入口会被拒绝，并提示去激活。
 *
 * ## 商业发布前必须做的事（按顺序）
 *   1. 把 [DEVELOPMENT_MODE_OPEN_ACCESS] 改为 `false`；
 *      —— 改完即真正生效：自签发的开发凭证会被识别出来并判为未授权
 *      （见 [isDevIssuedToken]）。不需要再改别的地方就能拦住未激活用户。
 *   2. 部署 Supabase：执行 `backend/supabase/migrations/0001_multi_game_license.sql`，
 *      部署 `backend/supabase/functions/license`，并设置 `LICENSE_TOKEN_SECRET`；
 *   3. 用 `tools/gen_cards.py` 生成卡密并导入库存；
 *   4. 把 [LicenseManager.supabaseEndpointUrl] / [supabaseAnonKey] 换成真实实例地址
 *      （当前仍是 `your-supabase-project` 占位符，也是为什么离线分支总会命中）；
 *   5. （可选加固）把 `SecurityBridge.cpp` 的 `ALLOW_MASTER_TOKEN` 改为 0。
 *      第 1 步已在 Kotlin 层拦住开发凭证，这一步是**纵深防御**：
 *      即使有人手工伪造一张带该签名的凭证写进本地存储，原生层也会拒绝。
 *
 * ## 激活入口
 * 主界面「授权与激活」卡片提供卡密输入与状态显示（`MainActivity`），
 * 因此第 1 步不再会出现"改了开关但用户无处激活"的尴尬。
 */
object LicenseGate {

    private const val TAG = "LicenseGate"

    /**
     * 开发模式：跳过一切鉴权，全功能开放。
     *
     * ⚠️ **商业发布前必须改为 `false`**。当前工程内没有任何激活界面，
     * 因此只有改成 `false` 之后，才需要（也才会）真正去核实凭证。
     */
    const val DEVELOPMENT_MODE_OPEN_ACCESS: Boolean = true

    enum class State {
        /** 开发模式：无鉴权（当前默认） */
        DEV_OPEN,
        /** 已获得有效授权 */
        LICENSED,
        /** 未授权：需要激活，或凭证已失效 */
        UNLICENSED,
        /** 系统时钟被回拨，凭证熔断 */
        CLOCK_TAMPERED
    }

    @Volatile
    private var warned = false

    /**
     * 开发态"自签发"凭证的固定签名段。
     *
     * `LicenseManager` 会用这个签名来自己给自己签发永久凭证。
     * 因此**仅仅**把 [DEVELOPMENT_MODE_OPEN_ACCESS] 改成 false 是不够的：
     * 自签发出来的凭证看起来完全合法，门禁会以为已授权。
     * 必须能把它与服务端真实签名（base64url 的 HMAC 摘要）区分开。
     */
    private const val DEV_TOKEN_SIGNATURE = "COMMERCIAL_MASTER_PERPETUAL"

    /** 判断一张凭证是不是"开发态自签发"的（而非服务端真实签名）。 */
    private fun isDevIssuedToken(token: String?): Boolean =
        token != null && token.endsWith("|$DEV_TOKEN_SIGNATURE")
    /*
     * 这个判别式为什么可靠：
     * 真实凭证的签名段是 32 字节 SHA-256 摘要做 base64url，长度 43 个字符；
     * 而 "COMMERCIAL_MASTER_PERPETUAL" 只有 27 个字符。长度就不可能相同，
     * 因此不存在"真实凭证被误判为开发凭证"的情况。
     * （该结论由 tools/selftest_license_token.py 实测校对：43 vs 27。）
     */

    /**
     * 当前授权状态。
     *
     * 开发模式下**不会**去写或读任何凭证，避免像 [LicenseManager.checkLocalLicense]
     * 那样"自己给自己发一张永久卡"这种既无效果又误导人的行为。
     */
    fun state(context: Context): State {
        if (DEVELOPMENT_MODE_OPEN_ACCESS) {
            warnDevModeOnce()
            return State.DEV_OPEN
        }

        val info = LicenseManager.checkLocalLicense(context)

        // 关键一步：自签发的开发凭证在生产模式下**不算授权**。
        // 漏掉这一判断，付费墙就只是摆设（这也是我第一版实现的错误）。
        if (isDevIssuedToken(info.token)) {
            Log.w(
                TAG,
                "检测到开发态自签发凭证（签名段为 $DEV_TOKEN_SIGNATURE），" +
                    "生产模式下视为未授权。请通过真实服务端激活。"
            )
            return State.UNLICENSED
        }

        return when {
            info.isValid -> State.LICENSED
            info.message.contains("回拨") -> State.CLOCK_TAMPERED
            else -> State.UNLICENSED
        }
    }

    /**
     * 是否允许执行战术动作。
     *
     * 调用点：悬浮窗里所有"会真的去点游戏"的入口。
     * 开发模式下恒为 true（行为与修复前一致）；关闭开发模式后则会真正拦截。
     */
    fun isExecutionAllowed(context: Context): Boolean {
        val s = state(context)
        if (s == State.DEV_OPEN || s == State.LICENSED) return true
        Log.w(TAG, "战术执行被授权门禁拒绝，当前状态: $s")
        return false
    }

    /** 给用户看的一句话说明（主界面与日志共用）。 */
    fun describe(context: Context): String = when (state(context)) {
        State.DEV_OPEN ->
            "⚠️ 授权：开发模式（无鉴权）——全功能开放，仅供自用与调试。商业发布前请把 " +
                "LicenseGate.DEVELOPMENT_MODE_OPEN_ACCESS 改为 false 并部署 Supabase。"
        State.LICENSED -> {
            val info = LicenseManager.checkLocalLicense(context)
            "✅ 授权：${info.cardType ?: "已授权"}｜${info.message}"
        }
        State.UNLICENSED ->
            "❌ 授权：未激活。请点下方「输入卡密激活」完成激活后重试。"
        State.CLOCK_TAMPERED ->
            "❌ 授权：系统时间被回拨，凭证已熔断。请校正系统时间后重试。"
    }

    /** 拒绝执行时给用户看的提示。 */
    fun rejectionReason(context: Context): String = when (state(context)) {
        State.CLOCK_TAMPERED -> "系统时间被回拨，授权已熔断。请校正系统时间。"
        State.UNLICENSED -> "尚未激活，无法执行战术动作。请输入卡密完成激活。"
        else -> "授权状态异常，无法执行。"
    }

    private fun warnDevModeOnce() {
        if (warned) return
        warned = true
        Log.w(
            TAG,
            "========================================================\n" +
                "当前为开发模式：授权门禁已关闭，全功能开放，任何人都可使用。\n" +
                "若要商业发布，请把 LicenseGate.DEVELOPMENT_MODE_OPEN_ACCESS 改为 false，\n" +
                "并部署 Supabase（见 LicenseGate 类注释里的 5 步清单）。\n" +
                "========================================================"
        )
    }
}
