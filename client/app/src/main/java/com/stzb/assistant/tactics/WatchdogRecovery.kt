package com.stzb.assistant.tactics

import android.graphics.Rect
import android.util.Log
import com.stzb.assistant.ocr.OcrManager
import com.stzb.assistant.ocr.StzbUiMatcher
import com.stzb.assistant.service.EngineBridge
import kotlinx.coroutines.delay

/**
 * 健壮任务管线自愈与异常弹窗容错看门狗 (WatchdogRecovery)
 * 
 * 借鉴 MaaFramework 管道流节点容错设计，专治率土实际运行中的各种突发中断：
 *   1. 每日签到/同盟宣战公告/赛季结算弹窗自愈关闭；
 *   2. 网络波动“重新连接”确认框自动重连；
 *   3. “土地上限已满”、“不相连”、“体力不足”等操作异常智能关窗；
 *   4. 迷航状态一键恢复回大地图主界面 (MAIN_MAP)。
 */
object WatchdogRecovery {

    private const val TAG = "WatchdogRecovery"

    // 常见弹窗交互确认/关闭语义按键
    private val DISMISS_KEYWORDS = listOf("确定", "确认", "我知道了", "关闭", "重试", "取消", "领取", "好的")

    /**
     * 感知不可用时允许的盲点次数。
     *
     * `recoverToMainMap` 是整个工程里**唯一在感知失败时仍然主动点击**的地方，
     * 也就是"乱点"风险的源头。当 OCR 不可用时，`dismissAnyDialog()` 必然返回 false
     * （它靠读文字找按钮），于是兜底分支会在**完全不知道屏幕上是什么**的情况下反复点击。
     *
     * 允许一次：万一只是个能收起的浮层，试一下是值得的；
     * 不允许更多：继续盲点已经接近乱点，可能误触弹窗里的按钮。
     */
    private const val MAX_BLIND_TAPS_WHEN_PERCEPTION_DEAD = 1

    /**
     * 强力自愈恢复回大地图主场景
     * @param maxAttempts 最大尝试自愈轮次 (默认 4 次)
     * @return true 表示成功回到大地图；false 表示仍处于严重异常状态
     */
    suspend fun recoverToMainMap(maxAttempts: Int = 4): Boolean {
        var blindTaps = 0
        for (attempt in 1..maxAttempts) {
            val currentState = EngineBridge.detectGameState()
            Log.d(TAG, "自愈巡检第 [$attempt/$maxAttempts] 轮: 当前场景 = $currentState")

            if (currentState == StzbUiMatcher.GameState.MAIN_MAP) {
                return true
            }

            // ★ 统一的盲点闸：感知不可用时，**任何**一次点击都要先扣预算。
            //
            // 刻意放在所有点击分支之前，而不是只放在"未知场景兜底"那一支——
            // 第一版就是这么写的，结果"已知次级面板"那一支同样会点击却绕过了预算。
            // 放在这里的意义是：**以后新增分支也不会绕过它**。
            if (!OcrManager.isEngineAvailable) {
                blindTaps++
                if (blindTaps > MAX_BLIND_TAPS_WHEN_PERCEPTION_DEAD) {
                    Log.e(
                        TAG,
                        "自愈中止：文字识别不可用，无法判断屏幕上是什么；" +
                            "已做 ${blindTaps - 1} 次盲点后停止。" +
                            "请手动把游戏切回大地图，或先用带 ncnn/OpenCV 的构建让识别可用。"
                    )
                    break
                }
                Log.w(
                    TAG,
                    "文字识别不可用：本次自愈是**盲点**（无法确认屏幕上有什么），仅尝试一次收起浮层。"
                )
            }

            // 1. 尝试检测并关闭任何覆盖在屏幕上的弹窗确认框
            val dismissedDialog = dismissAnyDialog()
            if (dismissedDialog) {
                EngineBridge.humanDelay(600, 1000)
                continue
            }

            // 2. 如果停留在地块菜单或部队出征面板，点击空白安全区域关闭浮层
            if (currentState == StzbUiMatcher.GameState.TILE_ACTION_MENU ||
                currentState == StzbUiMatcher.GameState.TROOP_DISPATCH_DIALOG ||
                currentState == StzbUiMatcher.GameState.DEFENDER_INFO_DIALOG ||
                currentState == StzbUiMatcher.GameState.COORDINATE_SEARCH_DIALOG
            ) {
                tapSafeBlankArea()
                EngineBridge.humanDelay(500, 800)
                continue
            }

            // 3. 未知场景兜底：点击安全空白区收起浮层
            tapSafeBlankArea()
            delay(600)
        }

        val finalCheck = EngineBridge.detectGameState()
        val success = (finalCheck == StzbUiMatcher.GameState.MAIN_MAP)
        Log.i(TAG, "自愈恢复流程结束，最终状态: $finalCheck, 成功=$success")
        return success
    }

    /**
     * 检索屏幕上是否存在通用弹窗，并自动点击关闭/确定按键 (结合当前知识库动态关闭词表)
     */
    suspend fun dismissAnyDialog(): Boolean {
        val frame = EngineBridge.captureFrame() ?: return false
        val activeKeywords = com.stzb.assistant.knowledge.KnowledgeBaseManager.activeProfile.watchdogKeywords
        val targetKeywords = if (activeKeywords.isNotEmpty()) activeKeywords else DISMISS_KEYWORDS
        val matches = OcrManager.findKeywords(frame, targetKeywords)
        frame.recycle()

        if (matches.isNotEmpty()) {
            // 优先按**知识库里的关键词顺序**挑选，而不是按 OCR 置信度。
            //
            // 原实现是 `matches.maxByOrNull { it.confidence }`。置信度只表示
            // "这几个字读得清楚"，**并不表示"这个按钮按下去安全"**：
            // 一个同时有「确定」与「取消」的弹窗里，置信度更高的可能是「取消」，
            // 按下去就把用户原本的操作取消了。
            //
            // 而 `watchdogKeywords` 是一个**有序**列表，作者把「确定」放在最前面，
            // 正是在表达"优先按它"。这与此前修掉的 `threatScore` 属于同一类问题：
            // **配置里写了意图，代码却从来没有使用这个意图**。
            // 现在：先按列表顺序，其次才在同一关键词内比置信度。
            val bestBtn = matches.sortedWith(
                compareBy(
                    { m ->
                        val idx = targetKeywords.indexOfFirst { m.matchedFullText.contains(it) }
                        if (idx < 0) Int.MAX_VALUE else idx
                    },
                    { m -> -m.confidence }
                )
            ).firstOrNull() ?: return false

            Log.i(
                TAG,
                "发现突发弹窗！命中按键: '${bestBtn.matchedFullText}', " +
                    "坐标=(${bestBtn.centerX}, ${bestBtn.centerY}), 置信度=${bestBtn.confidence}"
            )
            // 用"画面是否真的变化"来确认弹窗被关掉，而不是只确认手势派发成功。
            // 原实现只要 tap() 返回 true 就认为"已关闭"，把"点了但没关掉"也算成功。
            val closed = EngineBridge.tapAndVerify(bestBtn.centerX, bestBtn.centerY, attempts = 2, waitMs = 700)
            if (closed) {
                EngineBridge.humanDelay(400, 700)
                return true
            }
            Log.w(TAG, "已点击弹窗按键但画面无变化，判定未成功关闭。")
        }
        return false
    }

    /**
     * 点击地图空白处，用于收起轮盘菜单、次级悬浮面板等。
     *
     * 原实现点在 `(virtualWidth / 2, 75)`——在 720 高画布上 y=75 恰好落在
     * **顶部资源栏/坐标栏**上（换算到 1080p 约 y=112px），属于每次都去点一条
     * 满是可交互元素的横栏，极易误触地图/提醒/同盟战报等入口。
     *
     * 现在改为落在「纵向 62% 的地图区域」：它在顶部资源栏之下、底部部队与
     * 功能栏之上，是画面上最接近纯地图的位置。
     *
     * 说明：这仍然是一个**经验值**而非标定值。STZB 的 UI 在不同赛季皮肤下会有
     * 位移，理想做法是由用户在本机标定一次并持久化。此处先保证"不会去点顶部横栏"。
     */
    suspend fun tapSafeBlankArea(): Boolean {
        // 落点现在来自可标定的锚点表：真机上如果这个位置仍然会误触，可以单独标定它，
        // 而不必重新改代码发版。
        val p = com.stzb.assistant.service.UiAnchors.point(
            com.stzb.assistant.service.UiAnchors.Key.MAP_BLANK
        )
        Log.d(TAG, "执行地图空白区点击以收起浮层: (${p.x}, ${p.y})")
        return EngineBridge.tap(p.x, p.y)
    }
}
