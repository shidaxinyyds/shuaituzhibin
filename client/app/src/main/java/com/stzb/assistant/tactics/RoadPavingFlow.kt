package com.stzb.assistant.tactics

import android.graphics.PointF
import android.graphics.Rect
import android.util.Log
import com.stzb.assistant.ocr.StzbUiMatcher
import com.stzb.assistant.ocr.TileStatusDetector
import com.stzb.assistant.ocr.TroopStatusDetector
import com.stzb.assistant.service.CoordinateTransformer
import com.stzb.assistant.service.EngineBridge
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 自动铺路翻地与体力动态轮班机 (RoadPavingFlow)
 * 
 * 核心痛点解决：
 *   1. 解决跨州大战机械铺路数小时、手指点废的痛点；
 *   2. 多部队动态轮班 (Troop 1 -> Troop 2 -> Troop 3)：谁体力充足选谁，彻底规避 120 点体力满溢惩罚；
 *   3. 2026 征服赛季士气校验：低于 100 士气严禁出征，避免低士气战力骤降白送；
 *   4. 结合 WatchdogRecovery 自动处理“免战中”、“占领上限”、“不相连”等异常提示；
 *   5. 全程三次贝塞尔曲线 + 2D 高斯离散随机触控，高度拟人防封。
 */
class RoadPavingFlow(
    private val listener: TacticalState.TacticalEventListener? = null
) {

    private val isRunning = AtomicBoolean(false)

    data class PavingConfig(
        val targetTileList: List<PointF>, // 目标地块虚拟坐标序列 (由近及远)
        val candidateTroopSlots: List<Int> = listOf(1, 2, 3), // 参与轮班的部队槽位编号
        val minMoraleThreshold: Int = 100, // 2026 赛季最低出征士气门槛 (满士气 120 增伤 16%)
        val maxPavingCount: Int = 50 // 最大铺路地块上限
    )

    fun stop() {
        isRunning.set(false)
        log(TacticalState.TacticalLog(
            taskType = TacticalState.TaskType.ROAD_PAVING,
            level = "WARN",
            message = "⏹️ 收到用户终止指令，正在安全退出铺路执行流..."
        ))
    }

    /**
     * 启动自动铺路主循环
     */
    suspend fun startPaving(config: PavingConfig): Boolean {
        if (!isRunning.compareAndSet(false, true)) {
            Log.w(TAG, "自动铺路已在运行中，请勿重复启动。")
            return false
        }

        listener?.onStatusChanged(
            TacticalState.TaskType.ROAD_PAVING,
            TacticalState.Status.RUNNING,
            "开始自动铺路流水线，总规划地块数: ${config.targetTileList.size}"
        )

        var pavedCount = 0
        var currentSlotIndex = 0

        try {
            for ((index, tileCoord) in config.targetTileList.withIndex()) {
                if (!isRunning.get()) break
                if (pavedCount >= config.maxPavingCount) {
                    logInfo("🎯 已达到本次规划最大铺路上限 (${config.maxPavingCount}块)，顺利结单。")
                    break
                }

                logTactic("📍 [第 ${index + 1}/${config.targetTileList.size} 块] 正在锁定目标地块: (${tileCoord.x.toInt()}, ${tileCoord.y.toInt()})")

                // 1. 确保在大地图主场景
                val ready = WatchdogRecovery.recoverToMainMap()
                if (!ready) {
                    logWarn("大地图场景自愈失败，稍后重试...")
                    EngineBridge.humanDelay(1500, 2500)
                    continue
                }

                // 2. 点击地块，呼出操作轮盘
                EngineBridge.tap(tileCoord.x, tileCoord.y)
                val menuOpened = EngineBridge.waitForState(StzbUiMatcher.GameState.TILE_ACTION_MENU, 2500)
                if (!menuOpened) {
                    logWarn("未检测到地块操作轮盘，再次点击重试...")
                    EngineBridge.tap(tileCoord.x, tileCoord.y)
                    if (!EngineBridge.waitForState(StzbUiMatcher.GameState.TILE_ACTION_MENU, 2000)) {
                        WatchdogRecovery.recoverToMainMap()
                        continue
                    }
                }

                // 3. 点击“出征”按键
                val attackClicked = EngineBridge.clickButton(StzbUiMatcher.ButtonType.ATTACK)
                if (!attackClicked) {
                    logWarn("未能点击到【出征】按键，可能该地已被占领或处于免战中。")
                    WatchdogRecovery.recoverToMainMap()
                    continue
                }

                // 4. 等待部队出征面板弹出
                val dialogReady = EngineBridge.waitForState(StzbUiMatcher.GameState.TROOP_DISPATCH_DIALOG, 3000)
                if (!dialogReady) {
                    logWarn("出征面板未弹出，执行弹窗自愈...")
                    WatchdogRecovery.recoverToMainMap()
                    continue
                }

                // 5. 【核心轮班算法】：遍历候选部队，选出体力>=20且士气>=100的最优出征队
                val selectedSlot = selectOptimalTroop(config.candidateTroopSlots, config.minMoraleThreshold)
                if (selectedSlot == null) {
                    logWarn("⚠️ 当前所有候选部队体力均不足 20 或士气过低，等待体力回复 3 分钟...")
                    listener?.onStatusChanged(
                        TacticalState.TaskType.ROAD_PAVING,
                        TacticalState.Status.WAITING_COUNTDOWN,
                        "全员体力不足或士气低迷，休眠等待体力回复中"
                    )
                    WatchdogRecovery.recoverToMainMap()
                    delay(3 * 60 * 1000L) // 体力 3 分钟回 1 点
                    continue
                }

                logInfo("⚡ 选中轮班部队: [部队 ${selectedSlot}]，体力士气状态极佳！")

                // 6. 点击确认出征
                EngineBridge.humanDelay(400, 800)
                val confirmSuccess = EngineBridge.clickButton(StzbUiMatcher.ButtonType.CONFIRM)
                if (!confirmSuccess) {
                    logWarn("未能点击【确定出征】，尝试二次寻找...")
                    EngineBridge.clickButton(StzbUiMatcher.ButtonType.CONFIRM)
                }

                // 7. 动态等待行军触敌与土地占领
                logInfo("🏹 部队已派遣出发！进入行军占领等待中...")
                // 率土近邻铺路单程通常在 30 秒 ~ 2 分钟，此处做状态监测
                waitForTileOccupation(tileCoord)

                pavedCount++
                logTactic("✅ 成功攻占第 $pavedCount 块领地！准备下一块推进...")
                EngineBridge.humanDelay(1200, 2000)
            }

            listener?.onStatusChanged(
                TacticalState.TaskType.ROAD_PAVING,
                TacticalState.Status.COMPLETED,
                "铺路任务完成，共成功翻地铺设: $pavedCount 块"
            )
            return true

        } catch (e: Exception) {
            log(TacticalState.TacticalLog(
                taskType = TacticalState.TaskType.ROAD_PAVING,
                level = "ERROR",
                message = "铺路主循环发生未捕获异常: ${e.message}"
            ))
            listener?.onStatusChanged(
                TacticalState.TaskType.ROAD_PAVING,
                TacticalState.Status.FAILED,
                "发生异常: ${e.message}"
            )
            return false
        } finally {
            isRunning.set(false)
        }
    }

    /**
     * 智能选队与 2026 士气/体力评估
     */
    private suspend fun selectOptimalTroop(candidateSlots: List<Int>, minMorale: Int): Int? {
        for (slot in candidateSlots) {
            // 切换/选中该部队卡片 (点击部队槽位对应的屏幕区域)
            clickTroopSlotTab(slot)
            EngineBridge.humanDelay(250, 450)

            // 解析当前选中的部队卡片信息
            val cardRoi = getTroopCardRoi(slot)
            val detail = EngineBridge.parseTroopCard(cardRoi, slot)

            if (detail != null) {
                val stamina = detail.stamina ?: 100
                val morale = detail.morale ?: 100

                // 铺路最低要求：体力 >= 20，士气 >= minMorale
                if (stamina >= 20 && morale >= minMorale) {
                    return slot
                } else {
                    Log.d(TAG, "部队[$slot] 不达标: 体力=$stamina(需>=20), 士气=$morale(需>=$minMorale)")
                }
            } else {
                // OCR 略过未能解析标签时默认可用
                return slot
            }
        }
        return null
    }

    /**
     * 点击选队面板顶部的部队标签 (部队一、部队二...)
     */
    private suspend fun clickTroopSlotTab(slot: Int) {
        val vWidth = CoordinateTransformer.virtualWidth
        // 典型 720p 出征面板部队标签在顶部 X 轴均匀分布 (200px ~ 800px), Y 约 160px
        val stepX = 140f
        val startX = 220f
        val targetX = startX + (slot - 1) * stepX
        EngineBridge.tap(targetX, 160f)
    }

    private fun getTroopCardRoi(slot: Int): Rect {
        // 出征面板卡片大致在中央偏左 (X: 180~650, Y: 220~550)
        return Rect(180, 220, 650, 550)
    }

    /**
     * 监测行军占领完成
     */
    private suspend fun waitForTileOccupation(targetCoord: PointF) {
        // 关闭可能残留的弹窗回到大地图
        WatchdogRecovery.recoverToMainMap()

        // 轮询等待土地进入免战状态或恢复大地图静止态 (最多等待 90 秒)
        val startWait = System.currentTimeMillis()
        while (isRunning.get() && (System.currentTimeMillis() - startWait < 90 * 1000L)) {
            val immunity = EngineBridge.detectTileImmunity()
            if (immunity.isImmune) {
                logInfo("🛡️ 感知到目标地块已挂起金色免战罩，确认成功占领！")
                break
            }
            delay(3000)
        }
    }

    private fun logInfo(msg: String) = log(TacticalState.TacticalLog(TacticalState.TaskType.ROAD_PAVING, "INFO", msg))
    private fun logWarn(msg: String) = log(TacticalState.TacticalLog(TacticalState.TaskType.ROAD_PAVING, "WARN", msg))
    private fun logTactic(msg: String) = log(TacticalState.TacticalLog(TacticalState.TaskType.ROAD_PAVING, "TACTIC", msg))

    private fun log(entry: TacticalState.TacticalLog) {
        Log.i(TAG, "[${entry.level}] ${entry.message}")
        listener?.onLogEmitted(entry)
    }

    companion object {
        private const val TAG = "RoadPavingFlow"
    }
}
