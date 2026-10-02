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
                put("screen_virtual_width", rules.screenVirtualWidth)
                put("screen_virtual_height", rules.screenVirtualHeight)
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
                nightStaminaMultiplier = rObj.optDouble("night_stamina_multiplier", 1.0),
                screenVirtualWidth = rObj.optInt("screen_virtual_width", 1280),
                screenVirtualHeight = rObj.optInt("screen_virtual_height", 720)
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
    val staminaPerAction: Int = 20,            // 单次出征/行军体力消耗
    val maxMorale: Int = 120,                 // 士气上限 (率土2026征服赛季为 120, 三战为 100)
    val moraleStandard: Int = 100,            // 基准士气值 (无加成无减损基准)
    val minMoraleForPaving: Int = 100,        // 铺路最小士气阈值
    val immunityDurationSec: Int = 3600,       // 占领后免战时长 (默认 60 分钟 = 3600 秒)
    val immunityPaddingMs: Long = 1000L,       // 00:00:01 破免压秒触敌提前量补偿
    val nightWindowStartHour: Int = 0,         // 深夜雷达重点布防起始时间 (00:00)
    val nightWindowEndHour: Int = 7,           // 深夜雷达重点布防结束时间 (07:00)
    val nightStaminaMultiplier: Double = 1.0,  // 夜间体力消耗倍率 (率土为 1.0, 三战夜战为 2.0)
    val screenVirtualWidth: Int = 1280,        // 720p 归一化基准宽
    val screenVirtualHeight: Int = 720         // 720p 归一化基准高
)

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
