package com.stzb.assistant.ocr

import android.util.Log
import java.util.ArrayDeque
import java.util.Locale

/**
 * 识别健康度统计。
 *
 * ## 为什么需要它
 * OCR 是否可用，此前只在 App 启动时打一行日志。挂机过程中**完全无从判断**
 * "识别是不是在退化"——是引擎没编译进来？是画面在过渡动画里读不到字？
 * 还是游戏改了按键文案导致匹配不上？
 *
 * 这三者在 [StzbUiMatcher.ButtonLookupFailure] 层面已经能区分单次失败，
 * 但**单次失败说明不了问题**：一次过渡动画也会失败。真正要回答的是
 * "最近这段时间识别整体灵不灵"，这需要统计。
 *
 * 因此这里做一件很小的事：把每次场景判定与按键定位的结果累计起来，
 * 给出成功率与"整帧无文字"的比例，让人**当场**看出识别在退化，
 * 而不是等到某个战术流程莫名失败之后再去猜。
 *
 * ## 设计取舍
 * * 只做计数与最近窗口，不做采样落盘——目的是"当场可看"，不是事后分析；
 * * **没有样本时如实说"尚无样本"**，绝不把 0 次识别显示成 100% 成功率。
 */
object RecognitionHealth {

    private const val TAG = "RecognitionHealth"

    /** 最近事件窗口大小（用于显示"最近发生了什么"）。 */
    private const val WINDOW = 40

    enum class Kind { SCENE, BUTTON }

    data class Sample(
        val kind: Kind,
        val ok: Boolean,
        val textBlockCount: Int,
        val detail: String,
        val atMs: Long
    )

    private val recent = ArrayDeque<Sample>()

    private var totalScene = 0L
    private var sceneOk = 0L
    private var sceneUnknown = 0L

    private var totalButton = 0L
    private var buttonOk = 0L

    private var textSamples = 0L
    private var textBlockSum = 0L
    private var zeroTextFrames = 0L

    /** 记录一次场景判定。 */
    @Synchronized
    fun recordScene(state: StzbUiMatcher.GameState) {
        totalScene++
        val ok = state != StzbUiMatcher.GameState.UNKNOWN
        if (ok) sceneOk++ else sceneUnknown++
        push(Sample(Kind.SCENE, ok, 0, "场景判定 → ${state.name}", System.currentTimeMillis()))
    }

    /** 记录一次按键定位。 */
    @Synchronized
    fun recordButton(
        type: StzbUiMatcher.ButtonType,
        ok: Boolean,
        failureDesc: String?,
        textBlockCount: Int
    ) {
        totalButton++
        if (ok) buttonOk++
        noteTextCount(textBlockCount)
        val detail = if (ok) {
            "按键 ${type.name} 定位成功（读到 $textBlockCount 个文本块）"
        } else {
            "按键 ${type.name} 定位失败：${failureDesc ?: "未知原因"}（读到 $textBlockCount 个文本块）"
        }
        push(Sample(Kind.BUTTON, ok, textBlockCount, detail, System.currentTimeMillis()))
    }

    /** 只记录"这一帧读到了多少文字"，用于统计整帧无文字的比例。 */
    @Synchronized
    fun recordTextFrame(textBlockCount: Int) {
        noteTextCount(textBlockCount)
    }

    private fun noteTextCount(n: Int) {
        textSamples++
        textBlockSum += n
        if (n <= 0) zeroTextFrames++
    }

    private fun push(s: Sample) {
        recent.addLast(s)
        while (recent.size > WINDOW) recent.removeFirst()
    }

    @Synchronized
    fun reset() {
        recent.clear()
        totalScene = 0; sceneOk = 0; sceneUnknown = 0
        totalButton = 0; buttonOk = 0
        textSamples = 0; textBlockSum = 0; zeroTextFrames = 0
        Log.i(TAG, "识别健康度统计已重置")
    }

    data class Snapshot(
        val totalScene: Long,
        val sceneOk: Long,
        val sceneUnknown: Long,
        val totalButton: Long,
        val buttonOk: Long,
        val textSamples: Long,
        val textBlockSum: Long,
        val zeroTextFrames: Long,
        val recent: List<Sample>
    ) {
        /** 场景判定成功率；无样本时为 NaN（**不是** 0，也不是 1）。 */
        val sceneRate: Double
            get() = if (totalScene == 0L) Double.NaN else sceneOk.toDouble() / totalScene

        val buttonRate: Double
            get() = if (totalButton == 0L) Double.NaN else buttonOk.toDouble() / totalButton

        val avgTextBlocks: Double
            get() = if (textSamples == 0L) Double.NaN else textBlockSum.toDouble() / textSamples

        val hasSamples: Boolean get() = totalScene > 0 || totalButton > 0
    }

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(
        totalScene = totalScene,
        sceneOk = sceneOk,
        sceneUnknown = sceneUnknown,
        totalButton = totalButton,
        buttonOk = buttonOk,
        textSamples = textSamples,
        textBlockSum = textBlockSum,
        zeroTextFrames = zeroTextFrames,
        recent = recent.toList()
    )

    private fun fmtRate(v: Double): String =
        if (v.isNaN()) "—（无样本）" else String.format(Locale.US, "%.0f%%", v * 100)

    /**
     * 一眼看懂的多行摘要。
     * 没有样本时如实说明，不把"一次都没识别过"包装成"100% 成功"。
     */
    @Synchronized
    fun summary(): String {
        val s = snapshot()
        if (!s.hasSamples) {
            return "识别健康度：尚无样本（本次运行还没有执行过识别）"
        }

        val sb = StringBuilder()
        sb.append("识别健康度（场景样本 ${s.totalScene}，按键样本 ${s.totalButton}）\n")
        sb.append("· 场景判定成功率: ").append(fmtRate(s.sceneRate))
            .append("（UNKNOWN ").append(s.sceneUnknown).append(" 次）\n")
        sb.append("· 按键定位成功率: ").append(fmtRate(s.buttonRate)).append('\n')
        sb.append("· 平均文本块: ")
        sb.append(
            if (s.avgTextBlocks.isNaN()) "—"
            else String.format(Locale.US, "%.1f", s.avgTextBlocks)
        ).append(" 个/帧；整帧无文字 ")
            .append(s.zeroTextFrames).append('/').append(s.textSamples).append(" 次识别\n")

        if (!OcrManager.isEngineAvailable) {
            sb.append("· ⚠️ OCR 引擎当前不可用：上述失败源于此，不是画面问题（需要带 ncnn/OpenCV 的构建）\n")
        } else if (s.textSamples >= 5 && s.zeroTextFrames.toDouble() / s.textSamples >= 0.5) {
            sb.append("· ⚠️ 超过一半的帧读不到任何文字：请检查识别区域标定，或确认游戏界面是否已更新\n")
        }

        sb.append("· 最近: ").append(s.recent.lastOrNull()?.detail ?: "—")
        return sb.toString()
    }
}
