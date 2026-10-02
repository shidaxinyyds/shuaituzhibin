package com.stzb.assistant.tactics

import android.graphics.Rect
import android.util.Log
import com.stzb.assistant.ocr.OcrManager
import com.stzb.assistant.ocr.StzbUiMatcher
import com.stzb.assistant.service.CoordinateTransformer
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
     * 强力自愈恢复回大地图主场景
     * @param maxAttempts 最大尝试自愈轮次 (默认 4 次)
     * @return true 表示成功回到大地图；false 表示仍处于严重异常状态
     */
    suspend fun recoverToMainMap(maxAttempts: Int = 4): Boolean {
        for (attempt in 1..maxAttempts) {
            val currentState = EngineBridge.detectGameState()
            Log.d(TAG, "自愈巡检第 [$attempt/$maxAttempts] 轮: 当前场景 = $currentState")

            if (currentState == StzbUiMatcher.GameState.MAIN_MAP) {
                return true
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

            // 3. 未知场景兜底：尝试点击右上角或左上角返回/关闭区域
            tapSafeBlankArea()
            delay(600)
        }

        val finalCheck = EngineBridge.detectGameState()
        val success = (finalCheck == StzbUiMatcher.GameState.MAIN_MAP)
        Log.i(TAG, "自愈恢复流程结束，最终状态: $finalCheck, 成功=$success")
        return success
    }

    /**
     * 检索屏幕上是否存在通用弹窗，并自动点击关闭/确定按键
     */
    suspend fun dismissAnyDialog(): Boolean {
        val frame = EngineBridge.captureFrame() ?: return false
        val matches = OcrManager.findKeywords(frame, DISMISS_KEYWORDS)
        frame.recycle()

        if (matches.isNotEmpty()) {
            // 优先选取置信度最高的关闭/确认按键
            val bestBtn = matches.maxByOrNull { it.confidence } ?: return false
            Log.i(TAG, "发现突发弹窗！命中按键: '${bestBtn.matchedFullText}', 坐标=(${bestBtn.centerX}, ${bestBtn.centerY})")
            val tapped = EngineBridge.tap(bestBtn.centerX, bestBtn.centerY)
            if (tapped) {
                EngineBridge.humanDelay(400, 700)
                return true
            }
        }
        return false
    }

    /**
     * 点击屏幕安全空白区域 (用于关闭轮盘菜单、次级悬浮窗等，不触发任何其他建筑物点击)
     * 选取 720p 顶部偏下安全边缘：(virtualWidth / 2, 75px)
     */
    suspend fun tapSafeBlankArea(): Boolean {
        val safeX = CoordinateTransformer.virtualWidth / 2f
        val safeY = 75f
        Log.d(TAG, "执行空白安全区点击以关闭下级抽屉: ($safeX, $safeY)")
        return EngineBridge.tap(safeX, safeY)
    }
}
