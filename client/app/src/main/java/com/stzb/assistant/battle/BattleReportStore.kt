package com.stzb.assistant.battle

import android.content.Context
import android.util.Log
import com.stzb.assistant.service.PerGameScope
import org.json.JSONObject
import java.io.File

/**
 * 战报库 (BattleReportStore)
 *
 * ## 存什么、存在哪
 * `filesDir/battle_reports_<gameId>/reports.jsonl`，一行一条 [BattleReport]。
 *
 * 按游戏分域的理由与标定数据一致（见 [PerGameScope]）：率土的战报丢进三战的统计里，
 * 胜率与战损均值就会变成两套数值体系的平均值——一个谁也不对应、但看起来很像结论的数。
 *
 * ## 为什么是 JSONL 而不是 SQLite / 一个 JSON 数组
 *   * **JSONL**：追加一条 = 重写一个几千行的小文件，坏一行不影响其它行；
 *   * **整个 JSON 数组**：任意一条写坏，整份库都读不出来（一损俱损）；
 *   * **SQLite**：这份数据的量级（上限几百条）不值得引入一套 schema 迁移。
 *
 * ## 上限
 * [MAX_RECORDS] 条，超了丢最旧的。这个库的用途是"最近打得怎么样"，
 * 不是归档：无限增长只会拖慢每次读盘，而读盘发生在悬浮窗的每次战报会诊里。
 */
object BattleReportStore {

    private const val TAG = "BattleReportStore"
    private const val DIR_NAME = "battle_reports"
    private const val FILE_NAME = "reports.jsonl"

    /** 环形上限：够算"近 20 场"，又不会让一次读盘变得不可接受。 */
    private const val MAX_RECORDS = 400

    private val lock = Any()

    @Volatile
    private var appContext: Context? = null

    /** 内存镜像；null = 需要重新读盘（切游戏时必须置空，否则会继续用上一款游戏的战报）。 */
    private var cache: List<BattleReport>? = null

    /**
     * 挂接上下文并登记"切游戏就重载"。
     *
     * 必须调（在 Application/Service 的初始化里）：没有上下文就既读不到也写不进，
     * 而 [append] 会静默返回 false——那种"功能看着在、其实一条都没存"的状态
     * 比直接报错更难发现。
     */
    fun attach(context: Context) {
        appContext = context.applicationContext
        PerGameScope.reloadOnProfileSwitch {
            synchronized(lock) { cache = null }
        }
        Log.i(TAG, "战报库已就绪（分域目录：${PerGameScope.dirName(DIR_NAME)}）")
    }

    /**
     * 追加一条战报。
     *
     * @return 只有真正落盘成功才返回 true；未挂接上下文、写盘异常都返回 false
     *         （调用方据此决定要不要提示"本次战报没能存下来"）
     */
    fun append(report: BattleReport): Boolean {
        val file = fileOf() ?: return false
        synchronized(lock) {
            return try {
                val kept = (readAllLocked() + report).takeLast(MAX_RECORDS)
                val body = kept.joinToString("\n") { it.toJson().toString() }
                writeAtomic(file, if (body.isEmpty()) "" else body + "\n")
                cache = kept
                true
            } catch (e: Throwable) {
                Log.w(TAG, "写入战报失败: ${e.message}")
                false
            }
        }
    }

    /** 最近 [limit] 场（按写入顺序，最后一条最新）。 */
    fun recent(limit: Int = 20): List<BattleReport> {
        if (limit <= 0) return emptyList()
        synchronized(lock) {
            return readAllLocked().takeLast(limit)
        }
    }

    /** 当前库里有多少条（面板上要报"样本数"，不能只报胜率）。 */
    fun count(): Int = synchronized(lock) { readAllLocked().size }

    private fun fileOf(): File? {
        val ctx = appContext ?: return null
        return try {
            File(PerGameScope.scopedDir(ctx, DIR_NAME), FILE_NAME)
        } catch (e: Throwable) {
            Log.w(TAG, "取战报目录失败: ${e.message}")
            null
        }
    }

    /** 调用方必须已持有 [lock]。 */
    private fun readAllLocked(): List<BattleReport> {
        cache?.let { return it }
        val file = fileOf()
        if (file == null || !file.exists()) {
            cache = emptyList()
            return emptyList()
        }
        val parsed = mutableListOf<BattleReport>()
        try {
            file.forEachLine(Charsets.UTF_8) { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@forEachLine
                // 单行坏掉只丢这一行：整份库读不出来会让玩家的历史统计一夜清零。
                runCatching { BattleReport.fromJson(JSONObject(trimmed)) }
                    .getOrNull()
                    ?.let { parsed.add(it) }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "读取战报库失败（按空库处理）: ${e.message}")
        }
        cache = parsed
        return parsed
    }

    /**
     * 原子写：先写 `.tmp` 再改名。
     *
     * 直接覆盖目标文件的写法在"写到一半被杀"时会留下半行 JSON，
     * 而那正好会让整份战报库在某次冷启动后变成空库。
     */
    private fun writeAtomic(target: File, content: String) {
        val parent = target.absoluteFile.parentFile ?: return
        if (!parent.exists()) parent.mkdirs()
        val tmp = File(parent, target.name + ".tmp")
        tmp.writeText(content, Charsets.UTF_8)
        if (target.exists() && !target.delete()) {
            Log.w(TAG, "旧战报文件删不掉，改用覆盖写。")
        }
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }
}
