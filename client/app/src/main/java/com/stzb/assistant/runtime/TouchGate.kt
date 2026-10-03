package com.stzb.assistant.runtime

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 全局触控所有权锁 (TouchGate)
 *
 * ## 为什么必须有这一层
 * 本项目所有战术流最终都通过无障碍/Shizuku 往**同一块游戏画面**派发点击/滑动。
 * 夜战哨兵是 7x24 常驻守护，其它任务（铺路/攻城/屯田/后勤）是间歇前台任务，
 * 二者若同时操作画面，就会互相抢屏：哨兵把镜头拉回主城、前台却以为还在出征面板，
 * 结果是**盲点、误点、状态机错乱**——这是单画面自动化最本质的竞态。
 *
 * 此前用"全局单任务互斥"回避它（[com.stzb.assistant.tactics.TacticalPipeline.launchTask]
 * 先 stopCurrentTask），代价是哨兵被任意前台任务掐掉、夜战防护整段消失。
 *
 * ## 现在的模型：所有权锁 + 独立守护
 *  - **前台任务**整段执行持有所有权（[foregroundSession]）——它本来就在独占画面推进自己的一步一序；
 *  - **夜战哨兵**作为独立后台 Job 与前台并存：**抓屏/告警不需要锁**（截图与触控相互独立），
 *    因此威胁告警永不因前台占用而漏报；只有它真正要"点"的时候才来抢锁：
 *      * 非关键动作（5 分钟微保活、清理 stray 弹窗）→ [tryGuardedTouch]，抢不到就跳过，绝不与前台抢屏；
 *      * 关键动作（紧急秒回撤退/闭城）→ [guardedTouchWithTimeout]，最多等若干秒，等不到就放弃并告警
 *        （警报已响，玩家已醒，宁可延后一次点击也不要打断一场正在压秒的攻城）。
 *
 * 这样写触控的只有"当前前台任务"与"守护"两方，一把互斥锁即可保证**任意时刻只有一个画面操作者**，
 * 既实现了真并发守护（夜战不再被掐），又不引入抢屏竞态。
 */
object TouchGate {

    private const val TAG = "TouchGate"

    private val mutex = Mutex()

    /** 画面是否正被某个操作者占用（供只读判断，不排队）。 */
    val isHeld: Boolean get() = mutex.isLocked

    /**
     * 前台任务整段独占画面。在 [block] 执行期间持有所有权，
     * 守护的关键触控会等到本段结束（或超时放弃）才能插入。
     */
    suspend fun <T> foregroundSession(block: suspend () -> T): T {
        return mutex.withLock { block() }
    }

    /**
     * 守护的**非关键**触控：仅在无人占用时执行一次；抢不到锁立即返回 null（跳过，不等待）。
     * 用于微保活、清理 stray 弹窗这类"这次不做没关系"的动作。
     */
    suspend fun <T> tryGuardedTouch(block: suspend () -> T): T? {
        if (mutex.tryLock()) {
            try {
                return block()
            } finally {
                mutex.unlock()
            }
        }
        return null
    }

    /**
     * 守护的**关键**触控：最多等待 [timeoutMs] 争取所有权；超时仍被前台占用则返回 null，
     * 由调用方如实告警，绝不无限期阻塞守护巡检循环。
     */
    suspend fun <T> guardedTouchWithTimeout(timeoutMs: Long, block: suspend () -> T): T? {
        return try {
            withTimeoutOrNull(timeoutMs) {
                mutex.withLock { block() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "关键触控获取所有权异常: ${e.message}")
            null
        }
    }
}
