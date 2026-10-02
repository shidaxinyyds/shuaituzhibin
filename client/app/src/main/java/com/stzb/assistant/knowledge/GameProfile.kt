package com.stzb.assistant.knowledge

import org.json.JSONArray
import org.json.JSONObject

/**
 * 通用游戏自动化知识库结构定义 (GameProfile)
 * 
 * 核心设计思想：
 *   1. 【引擎与知识库彻底解耦】：触控、防封、720p归一化、悬浮窗为 100% 通用基座；
 *   2. 【游戏专属数据独立封装】：UI语义词典、游戏机制数值、守军天梯打分、战术参数全面结构化；
 *   3. 【云端热更新友好】：支持与 JSON 互相无损序列化，便于从 Supabase 云端拉取最新配置，免去更新 APK。
 */
data class GameProfile(
    val gameId: String,
    val gameName: String,
    val profileVersion: String,
    val targetPackage: String,
    val description: String,
    val rules: GameRules,
    val semanticButtons: Map<String, ButtonDef>,
    val defenderDb: DefenderDatabase,
    val tacticalDefaults: TacticalDefaults,
    val watchdogKeywords: List<String>
) {
    /**
     * 把 "1.10.2" 这类版本号解析成可比较的数字序列。
     * 非数字段按 0 处理，因此畸形版本号不会抛异常。
     */
    private fun versionParts(v: String): List<Int> =
        v.trim().split('.', '-', '_', ' ').map { it.trim().toIntOrNull() ?: 0 }

    /**
     * 与 [other] 比较版本号：负数=更旧，0=相同，正数=更新。
     *
     * ⚠️ 为什么要专门写这个函数：[profileVersion] 是 `String`，
     * 直接写 `a >= b` 是**字典序**比较，会得出错误结论：
     *   `"9" >= "10"`      → true（'9' > '1'）—— 旧缓存压住了新内置版本
     *   `"1.10.0" < "1.9.0"` → true        —— 真正的新版本反被拒绝
     * 表现为"明明热更成功了，重启后又变回旧版"，或"怎么都热更不上去"。
     */
    fun compareVersion(other: String): Int {
        val a = versionParts(profileVersion)
        val b = versionParts(other)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return if (x > y) 1 else -1
        }
        return 0
    }

    /**
     * 该知识库是否"看起来真的可用"。
     *
     * 为什么必须有这道闸：云端返回的 JSON 即使结构合法，也可能是**空壳**——
     * [fromJson] 对缺失字段一律用默认值兜底，于是会"成功"解析出一个
     * 守军库为空、按键表为空的配置。若直接采用并写盘，用户会**静默**失去
     * 全部战术能力（守军评估没人可评、按键别名全空），而且下次冷启动还会继续用它。
     *
     * @return (是否可用, 不可用时的原因)
     */
    fun validateFor(expectedGameId: String): Pair<Boolean, String> {
        if (gameId != expectedGameId) {
            return false to "game_id 不匹配（内容=$gameId，期望=$expectedGameId）"
        }
        if (profileVersion.isBlank()) return false to "profile_version 为空"
        val heroCount = defenderDb.dangerHeroes.size + defenderDb.hardHeroes.size +
            defenderDb.moderateHeroes.size + defenderDb.safeHeroes.size
        if (heroCount == 0) return false to "守军武将库为空（守军评估会完全失效）"
        if (semanticButtons.isEmpty()) return false to "按键语义表为空（按键定位会退回枚举默认值）"
        if (targetPackage.isBlank()) return false to "targetPackage 为空（无法判断游戏是否在前台）"
        return true to "校验通过"
    }

    /**
     * 将知识库序列化为 JSON 字符串 (便于本地缓存或上传至 Supabase 后端)
     */
    fun toJson(): String {
        val root = JSONObject().apply {
            put("game_id", gameId)
            put("game_name", gameName)
            put("profile_version", profileVersion)
            put("target_package", targetPackage)
            put("description", description)

            // 1. 游戏机制数值
            put("rules", JSONObject().apply {
                put("max_stamina", rules.maxStamina)
                put("stamina_per_action", rules.staminaPerAction)
                put("max_morale", rules.maxMorale)
                put("morale_standard", rules.moraleStandard)
                put("min_morale_for_paving", rules.minMoraleForPaving)
                put("immunity_duration_sec", rules.immunityDurationSec)
                put("immunity_padding_ms", rules.immunityPaddingMs)
                put("night_window_start_hour", rules.nightWindowStartHour)
                put("night_window_end_hour", rules.nightWindowEndHour)
                put("night_stamina_multiplier", rules.nightStaminaMultiplier)
            })

            // 2. 按键语义字典
            val btnJson = JSONObject()
            semanticButtons.forEach { (key, btn) ->
                btnJson.put(key, JSONObject().apply {
                    put("primary_keyword", btn.primaryKeyword)
                    put("aliases", JSONArray(btn.aliases))
                    put("fallback_roi_ratio", JSONArray(btn.fallbackRoiRatio))
                })
            }
            put("semantic_buttons", btnJson)

            // 3. 守军难度与克制数据库
            put("defender_db", JSONObject().apply {
                fun serializeHeroes(heroes: List<HeroEntry>): JSONArray {
                    val arr = JSONArray()
                    heroes.forEach { h ->
                        arr.put(JSONObject().apply {
                            put("name", h.name)
                            put("tag", h.tag)
                            put("threat_score", h.threatScore)
                            put("description", h.description)
                            put("counter_tip", h.counterTip)
                        })
                    }
                    return arr
                }

                put("danger_heroes", serializeHeroes(defenderDb.dangerHeroes))
                put("hard_heroes", serializeHeroes(defenderDb.hardHeroes))
                put("moderate_heroes", serializeHeroes(defenderDb.moderateHeroes))
                put("safe_heroes", serializeHeroes(defenderDb.safeHeroes))

                val landArr = JSONArray()
                defenderDb.landSuggestions.forEach { (level, s) ->
                    landArr.put(JSONObject().apply {
                        put("land_level", level)
                        put("recommended_soldiers", s.recommendedSoldiers)
                        put("safe_heroes", JSONArray(s.safeHeroes))
                        put("blacklist_heroes", JSONArray(s.blacklistHeroes))
                        put("note", s.note)
                    })
                }
                put("land_suggestions", landArr)
            })

            // 4. 战术流水线默认调优参数
            put("tactical_defaults", JSONObject().apply {
                put("paving_default_slots", JSONArray(tacticalDefaults.pavingDefaultSlots))
                put("paving_step_interval_ms", tacticalDefaults.pavingStepIntervalMs)
                put("immunity_default_troop_slot", tacticalDefaults.immunityDefaultTroopSlot)
                put("immunity_break_precision_ms", tacticalDefaults.immunityBreakPrecisionMs)
                put("siege_main_squad_slot", tacticalDefaults.siegeMainSquadSlot)
                put("siege_demolition_slots", JSONArray(tacticalDefaults.siegeDemolitionSlots))
                put("raid_patrol_interval_ms", tacticalDefaults.raidPatrolIntervalMs)
                put("raid_decision_c_auto_counter", tacticalDefaults.raidDecisionCAutoCounter)
                put("raid_alarm_sound", tacticalDefaults.raidAlarmSound)
            })

            // 5. 看门狗异常弹窗关闭词表
            put("watchdog_keywords", JSONArray(watchdogKeywords))
        }
        return root.toString(2)
    }

    companion object {
        /**
         * 从 JSON 反序列化构建 GameProfile 对象
         */
        fun fromJson(jsonStr: String): GameProfile {
            val root = JSONObject(jsonStr)
            val gameId = root.getString("game_id")
            val gameName = root.getString("game_name")
            val profileVersion = root.getString("profile_version")
            val targetPackage = root.optString("target_package", "")
            val description = root.optString("description", "")

            // 1. Rules
            val rObj = root.getJSONObject("rules")
            val rules = GameRules(
                maxStamina = rObj.optInt("max_stamina", 120),
                staminaPerAction = rObj.optInt("stamina_per_action", 20),
                maxMorale = rObj.optInt("max_morale", 120),
                moraleStandard = rObj.optInt("morale_standard", 100),
                minMoraleForPaving = rObj.optInt("min_morale_for_paving", 100),
                immunityDurationSec = rObj.optInt("immunity_duration_sec", 3600),
                immunityPaddingMs = rObj.optLong("immunity_padding_ms", 1000L),
                nightWindowStartHour = rObj.optInt("night_window_start_hour", 0),
                nightWindowEndHour = rObj.optInt("night_window_end_hour", 7),
                nightStaminaMultiplier = rObj.optDouble("night_stamina_multiplier", 1.0)
            )

            // 2. Buttons
            val bObj = root.optJSONObject("semantic_buttons") ?: JSONObject()
            val buttons = mutableMapOf<String, ButtonDef>()
            bObj.keys().forEach { key ->
                val btnItem = bObj.getJSONObject(key)
                val primaryKeyword = btnItem.getString("primary_keyword")
                val aliasArr = btnItem.optJSONArray("aliases") ?: JSONArray()
                val aliases = mutableListOf<String>()
                for (i in 0 until aliasArr.length()) aliases.add(aliasArr.getString(i))

                val roiArr = btnItem.optJSONArray("fallback_roi_ratio") ?: JSONArray()
                val rois = mutableListOf<Float>()
                for (i in 0 until roiArr.length()) rois.add(roiArr.getDouble(i).toFloat())

                buttons[key] = ButtonDef(key, primaryKeyword, aliases, rois)
            }

            // 3. Defender DB
            val dObj = root.optJSONObject("defender_db") ?: JSONObject()
            fun parseHeroes(key: String): List<HeroEntry> {
                val arr = dObj.optJSONArray(key) ?: return emptyList()
                val list = mutableListOf<HeroEntry>()
                for (i in 0 until arr.length()) {
                    val h = arr.getJSONObject(i)
                    list.add(HeroEntry(
                        name = h.getString("name"),
                        tag = h.optString("tag", "常规"),
                        threatScore = h.optInt("threat_score", 2),
                        description = h.optString("description", ""),
                        counterTip = h.optString("counter_tip", "")
                    ))
                }
                return list
            }

            val suggestions = mutableMapOf<Int, LandSuggestion>()
            val landArr = dObj.optJSONArray("land_suggestions") ?: JSONArray()
            for (i in 0 until landArr.length()) {
                val item = landArr.getJSONObject(i)
                val lvl = item.getInt("land_level")
                val safeArr = item.optJSONArray("safe_heroes") ?: JSONArray()
                val safeList = (0 until safeArr.length()).map { safeArr.getString(it) }
                val blkArr = item.optJSONArray("blacklist_heroes") ?: JSONArray()
                val blkList = (0 until blkArr.length()).map { blkArr.getString(it) }
                suggestions[lvl] = LandSuggestion(
                    landLevel = lvl,
                    recommendedSoldiers = item.optInt("recommended_soldiers", 3000),
                    safeHeroes = safeList,
                    blacklistHeroes = blkList,
                    note = item.optString("note", "")
                )
            }

            val defenderDb = DefenderDatabase(
                dangerHeroes = parseHeroes("danger_heroes"),
                hardHeroes = parseHeroes("hard_heroes"),
                moderateHeroes = parseHeroes("moderate_heroes"),
                safeHeroes = parseHeroes("safe_heroes"),
                landSuggestions = suggestions
            )

            // 4. Tactical Defaults
            val tObj = root.optJSONObject("tactical_defaults") ?: JSONObject()
            fun parseIntList(key: String, def: List<Int>): List<Int> {
                val arr = tObj.optJSONArray(key) ?: return def
                return (0 until arr.length()).map { arr.getInt(it) }
            }

            val tacticalDefaults = TacticalDefaults(
                pavingDefaultSlots = parseIntList("paving_default_slots", listOf(1, 2, 3)),
                pavingStepIntervalMs = tObj.optLong("paving_step_interval_ms", 2000L),
                immunityDefaultTroopSlot = tObj.optInt("immunity_default_troop_slot", 1),
                immunityBreakPrecisionMs = tObj.optLong("immunity_break_precision_ms", 1000L),
                siegeMainSquadSlot = tObj.optInt("siege_main_squad_slot", 1),
                siegeDemolitionSlots = parseIntList("siege_demolition_slots", listOf(2, 3)),
                raidPatrolIntervalMs = tObj.optLong("raid_patrol_interval_ms", 4000L),
                raidDecisionCAutoCounter = tObj.optBoolean("raid_decision_c_auto_counter", true),
                raidAlarmSound = tObj.optBoolean("raid_alarm_sound", true)
            )

            // 5. Watchdog keywords
            val wArr = root.optJSONArray("watchdog_keywords") ?: JSONArray()
            val watchdog = (0 until wArr.length()).map { wArr.getString(it) }

            return GameProfile(
                gameId = gameId,
                gameName = gameName,
                profileVersion = profileVersion,
                targetPackage = targetPackage,
                description = description,
                rules = rules,
                semanticButtons = buttons,
                defenderDb = defenderDb,
                tacticalDefaults = tacticalDefaults,
                watchdogKeywords = watchdog
            )
        }
    }
}

/**
 * 游戏核心机制数值定义
 */
data class GameRules(
    val maxStamina: Int = 120,                // 体力上限 (率土 120, 三战 120)
    val staminaPerAction: Int = 20,            // 单次出征/行军体力消耗 → 被 requiredStaminaNow() 使用
    val maxMorale: Int = 120,                 // 士气上限 (率土2026征服赛季为 120, 三战为 100)
    /**
     * 基准士气值（无加成无减损基准）。
     *
     * ⚠️ 目前**仅作展示与配置留档**，没有任何决策读取它。
     * 若将来要做"士气增减益换算"，应当从这里取基准，而不是再写一个 100。
     */
    val moraleStandard: Int = 100,
    val minMoraleForPaving: Int = 100,        // 铺路最小士气阈值 → 被各战术流与无人托管使用
    /**
     * 占领后免战时长（默认 60 分钟）。
     *
     * ⚠️ 目前**仅作展示与配置留档**：`ImmunityBreakFlow` 的破免时刻是从画面上
     * OCR 读到的倒计时反推的，并没有用这个配置值去估算。
     * 留在这里是为了将来"读不到倒计时时用配置值兜底"这类用途。
     */
    val immunityDurationSec: Int = 3600,
    val immunityPaddingMs: Long = 1000L,       // 00:00:01 破免压秒触敌提前量补偿 → 已接入攻城/定时/托管
    val nightWindowStartHour: Int = 0,         // 夜间窗口起点 → 被 isNightNow() 使用
    val nightWindowEndHour: Int = 7,           // 夜间窗口终点 → 被 isNightNow() 使用
    val nightStaminaMultiplier: Double = 1.0  // 夜间体力消耗倍率 → 被 requiredStaminaNow() 使用
) {
    /*
     * 说明：这里刻意**不再**定义 screenVirtualWidth / screenVirtualHeight。
     *
     * 它们曾经存在，但从未被任何代码读取——而设计画布尺寸的唯一权威是
     * `CoordinateTransformer`（它按真实屏幕长宽比动态推导）。留着这两项等于
     * 留着"第二套基准"，一旦有人误用就会得到与 CoordinateTransformer 不一致的坐标。
     * 因此直接移除，而不是保留一个看起来能用、实则无人维护的字段。
     */

    /** 当前是否落在知识库定义的夜间窗口内。 */
    fun isNightNow(
        nowHour: Int = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    ): Boolean =
        if (nightWindowStartHour <= nightWindowEndHour) {
            nowHour in nightWindowStartHour until nightWindowEndHour
        } else {
            // 跨零点窗口，例如 22:00 ~ 07:00
            nowHour >= nightWindowStartHour || nowHour < nightWindowEndHour
        }

    /**
     * 当前这一次出征实际需要的最小体力。
     *
     * 单次消耗取自 [staminaPerAction]，夜间按 [nightStaminaMultiplier] 放大
     * （三战夜战为双倍）。这样这两个字段才真正参与判定，而不是躺在 JSON 里当装饰。
     */
    fun requiredStaminaNow(
        nowHour: Int = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    ): Int {
        val mul = if (isNightNow(nowHour)) nightStaminaMultiplier else 1.0
        return maxOf(1, Math.round(staminaPerAction * mul).toInt())
    }
}

/**
 * 语义按键与 OCR 容错别名定义
 */
data class ButtonDef(
    val actionKey: String,                    // 按键动作标识 (如 ATTACK, SWEEP, DEFEND)
    val primaryKeyword: String,               // 主关键字 ("出征")
    val aliases: List<String> = emptyList(),  // OCR 容错别名 ("出 征", "确定出征", "出征作战")
    val fallbackRoiRatio: List<Float> = emptyList() // 屏幕归一化相对位置 [x1, y1, x2, y2]
)

/**
 * 守军难度与克制天梯数据库
 */
data class DefenderDatabase(
    val dangerHeroes: List<HeroEntry>,        // 极高危翻车武将 (带暴走、混乱、怯战、高爆发)
    val hardHeroes: List<HeroEntry>,          // 较难守将 (身板硬、稳定输出)
    val moderateHeroes: List<HeroEntry>,      // 中等难度
    val safeHeroes: List<HeroEntry>,          // 白给软柿子 (无硬控、极低战损，推荐收割)
    val landSuggestions: Map<Int, LandSuggestion> = emptyMap() // 土地等级开荒指南
)

data class HeroEntry(
    val name: String,
    val tag: String,                          // 标签 (如 "强控-暴走", "高爆发", "禁疗", "高免伤")
    val threatScore: Int,                     // 威胁评分 (1~5 分)
    val description: String,                  // 战法/技能威胁说明
    val counterTip: String                    // 针对性克制/避让建议
)

data class LandSuggestion(
    val landLevel: Int,                       // 土地等级 (Lv.3 ~ Lv.8)
    val recommendedSoldiers: Int,             // 推荐开荒/攻打兵力
    val safeHeroes: List<String>,             // 推荐优先挑着打的软柿子守军
    val blacklistHeroes: List<String>,        // 坚决避开的翻车黑名单守军
    val note: String                          // 战略注意事项
)

/**
 * 战术流水线执行参数
 */
data class TacticalDefaults(
    val pavingDefaultSlots: List<Int> = listOf(1, 2, 3),
    val pavingStepIntervalMs: Long = 2000L,
    val immunityDefaultTroopSlot: Int = 1,
    val immunityBreakPrecisionMs: Long = 1000L,
    val siegeMainSquadSlot: Int = 1,
    val siegeDemolitionSlots: List<Int> = listOf(2, 3),
    val raidPatrolIntervalMs: Long = 4000L,
    val raidDecisionCAutoCounter: Boolean = true,
    val raidAlarmSound: Boolean = true
)
