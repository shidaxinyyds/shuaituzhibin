package com.stzb.assistant.knowledge

import com.stzb.assistant.ai.vision.VisionPolicy
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
    /**
     * 本知识包对应游戏的包名（率土 com.netease.stzb / 三战 com.aligames.sgzzlb）。
     *
     * 它是**前台闸门唯一的方向凭据**（见 service/ForegroundGate）：触控坐标是从游戏
     * 画面推出来的，游戏不在前台时继续派发就是往别的应用里乱点。热更改本值（例如渠道服
     * 包名不同）能直接换掉判据，不需要重发 APK。
     */
    val targetPackage: String,
    val description: String,
    val rules: GameRules,
    val semanticButtons: Map<String, ButtonDef>,
    val defenderDb: DefenderDatabase,
    val tacticalDefaults: TacticalDefaults,
    val watchdogKeywords: List<String>,
    /**
     * 引擎侧按**组 ID** 取用的中文词表（组 ID → 「出现任一词即命中该组」）。
     *
     * 为什么它必须在知识包里而不是在代码里：`ocr/StzbUiMatcher.classifyGameState` 判的是
     * "屏幕上读到过哪些中文词"、`ai/rag/SlgRagEngine` 判的是"战报里出现过哪些战法名"，
     * 而这些词是游戏美术与文案决定的（率土叫「屯田」，别的游戏可能叫「开垦」）。
     * 词表写死在引擎里，"加一款游戏不用改代码"就是空话；现在**组合关系**
     * （哪几组要同时成立、命中几算数）留在引擎里，**词本身**交给知识包，
     * 热更词表即可适配另一款游戏的界面文案与战法体系。
     *
     * 组 ID 由消费方声明：
     *   * `StzbUiMatcher.SCENE_*` —— 场景判定（出征面板/守军面板/地块菜单/坐标/筑城/主界面）；
     *   * `SlgRagEngine.VOCAB_*` —— 战报 PVE/PVP 分流语汇与战法字典。
     *
     * ⚠️ 两类组 ID 的缺配行为**不同**（有意为之）：
     *   * `SCENE_*`：引擎里留着一份率土默认词表作兼容回落，缺配时打一条 W 日志——
     *     因为场景判定失败会让整条自愈链路罢工，需要一个"至少还能跑"的底；
     *   * `VOCAB_*`（战法字典等）：**没有任何默认值**，缺配就如实不判定。
     *     把率土战法名当成另一款游戏战报的结论念给玩家，是实质性错误输出，
     *     比"这次没给出战法分析"危险得多。
     */
    val sceneKeywords: Map<String, List<String>> = emptyMap(),
    /**
     * 武将**基础速度**参考表（武将名 → 速度量级，不含装备/加点/阵营加成）。
     *
     * 它是战报诊断里"谁先手"在读不到速度数字时的唯一兜底依据，
     * 因此**属于每款游戏的数据**，过去它写死在 `SlgRagEngine` 里（连同那句
     * "本文件不出现任何武将名"的注释），既是假注释也是第二权威，现在收进知识包。
     * 空表 = 本游戏没有速度参考 → 先手判定一律退回"需实测算"，绝不臆断。
     */
    val heroBaseSpeed: Map<String, Int> = emptyMap(),
    /**
     * PVE 守军机制词表（机制词 → 命中后给玩家的解读文案）。
     *
     * 战报里"暴走 / 反击 / 狂怒 / 免疫 / 守军未溃"这类字样是**游戏文案**，
     * 每条字样背后该怎么打（带解控？改主动输出？）也是**游戏知识**，
     * 过去它们成对写死在 `SlgRagEngine.analyzePveDefense` 里，换个游戏就整套失效。
     * 引擎只保留"读到机制词就判为守军未溃、需要补刀"这条通用逻辑。
     * 空表 = 本游戏没有机制词表 → 机制判定如实回答"无从判定"，不拿率土的字样去套。
     */
    val pveMechanicNotes: Map<String, String> = emptyMap(),
    /**
     * 本游戏默认启用的感知层级（确定性优先阶梯）。
     * 默认 [VisionPolicy.SLG_DEFAULT]：不含 DETECTOR，即 SLG 沙盘盘**不加载 YOLO**。
     * 只有动作类游戏才在知识包里显式开 DETECTOR/POLICY。
     */
    val visionPolicy: VisionPolicy = VisionPolicy.SLG_DEFAULT
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
     * 为什么必须有这道闸：云端返回的 JSON 即使结构合法，也可能是**空壳或自相矛盾**——
     * [fromJson] 对缺失字段一律用默认值兜底，于是会"成功"解析出一个
     * 守军库为空、按键表为空、士气阈值高于上限的配置。若直接采用并写盘，用户会**静默**
     * 失去全部战术能力（守军评估没人可评、按键别名全空、铺路永远等不到士气），
     * 而且下次冷启动还会继续拿它当"最新配置"用。
     *
     * 三条口径（有意为之，不要改成含糊的"尽量"）：
     *   1. **只拦"会静默出错"的包，不拦"能力还没配齐"的包**：
     *      [heroBaseSpeed] / [pveMechanicNotes] / 地块建议为空都放行——引擎在这些表
     *      空时走的是"如实不判定"分支，输出诚实；而名单里混进空名、阈值互相矛盾时，
     *      输出是**自信且错误**的，那才是必须拦的。
     *   2. **语义按键的必需清单不在这里复制一份**，直接取引擎枚举
     *      `ocr/StzbUiMatcher.ButtonType`。自己抄一份清单等于再造第二权威，
     *      将来引擎加一个按键这里必然漏改。
     *   3. [rules] 的不可能值一律拒绝：宁可退回内置包，也不要一个
     *      "看着在工作、其实朝错误方向算"的配置。
     *
     * 与 `tools/check_knowledge_base.py` 的分工：那道闸守的是**出厂与导出侧**
     * （源码里的内置表 + 上传产物）；本函数守的是**端上采纳侧**——
     * 有人手工往云端塞了一份没走过导出脚本的 JSON 时，这里是最后一道。
     * 两处的判据必须同步收紧，任何一侧新增一条，另一侧漏改都会留下空档。
     *
     * @return (是否可用, 不可用时的原因)
     */
    fun validateFor(expectedGameId: String): Pair<Boolean, String> {
        // ── 1. 身份。拿错游戏的包比拿到空包更糟：它会用 A 游戏的按钮文案去点 B 游戏的界面。
        if (gameId != expectedGameId) {
            return false to "game_id 不匹配（内容=$gameId，期望=$expectedGameId）"
        }
        if (profileVersion.isBlank()) return false to "profile_version 为空"
        if (targetPackage.isBlank()) return false to "targetPackage 为空（无法判断游戏是否在前台）"

        // ── 2. 武将天梯。"非空但含空名"比"完全为空"更阴险：
        //     DefenderEvaluator 用 contains 双向匹配，空名会命中任意读到的守将，
        //     于是整块地的守军全被评成空名所在的那一档（挂在 danger 表里就是全员极高危），
        //     界面照常出货、结论全是错的。
        val allHeroes = defenderDb.dangerHeroes + defenderDb.hardHeroes +
            defenderDb.moderateHeroes + defenderDb.safeHeroes
        if (allHeroes.isEmpty()) return false to "守军武将库为空（守军评估会完全失效）"
        val blankHeroCount = allHeroes.count { it.name.isBlank() }
        if (blankHeroCount > 0) return false to
            "守军武将库里有 $blankHeroCount 条 name 为空（空名会命中任意守将，整块地的评级被它一家定死）"

        // ── 3. 按键语义表：必须覆盖引擎枚举的每一个按键，且每项至少有一个能用的词。
        //     少一个键不是"少一个功能"，而是那个动作**退回枚举里写死的率土词**——
        //     热更的意义恰恰是给这些动作配本游戏的叫法，缺键等于这项能力被静默作废。
        if (semanticButtons.isEmpty()) return false to "按键语义表为空（按键定位会退回枚举默认值）"
        val unusableButtonKeys =
            com.stzb.assistant.ocr.StzbUiMatcher.ButtonType.values()
                .map { it.name }
                .filter { key ->
                    val btn = semanticButtons[key]
                    btn == null ||
                        (btn.primaryKeyword.isBlank() && btn.aliases.none { it.isNotBlank() })
                }
        if (unusableButtonKeys.isNotEmpty()) return false to
            "按键语义表缺少可用词条：${unusableButtonKeys.joinToString("/")}" +
                "（这些动作只能退回枚举里的率土默认词，换游戏后等于拿别的游戏的按钮文案去点）"

        // ── 4. 机制数值的自相矛盾：每一条都对应一个具体的静默失效路径。
        val r = rules
        val impossibleRule = when {
            r.maxStamina < 1 ->
                "maxStamina=${r.maxStamina}（它是部队卡片抽体力的分母，<1 时永远读不出体力）"
            r.staminaPerAction < 1 ->
                "staminaPerAction=${r.staminaPerAction}（单次消耗至少 1，否则出征体力门槛恒等于 0）"
            r.staminaPerAction > r.maxStamina ->
                "staminaPerAction=${r.staminaPerAction} 大于 maxStamina=${r.maxStamina}" +
                    "（requiredStaminaNow() 永远满足不了，等于永远不许出征）"
            r.maxMorale < 1 -> "maxMorale=${r.maxMorale}（士气档位判据的分母）"
            r.moraleStandard < 1 -> "moraleStandard=${r.moraleStandard}"
            r.moraleStandard > r.maxMorale ->
                "moraleStandard=${r.moraleStandard} 大于 maxMorale=${r.maxMorale}（士气永远落不进 OPTIMAL/NORMAL）"
            r.minMoraleForPaving > r.maxMorale ->
                "minMoraleForPaving=${r.minMoraleForPaving} 高于 maxMorale=${r.maxMorale}（铺路永远等不到士气条件）"
            r.immunityDurationSec < 1 ->
                "immunityDurationSec=${r.immunityDurationSec}（读不到免战倒计时时它被当保守下界，<1 会算出『现在就能打』）"
            r.immunityPaddingMs < 0 || r.immunityPaddingMs > 60000 ->
                "immunityPaddingMs=${r.immunityPaddingMs} 不在 0~60000（破免提前量是毫秒级补偿，超出即单位写错，会压秒压到半个钟头前）"
            r.nightWindowStartHour !in 0..23 || r.nightWindowEndHour !in 0..23 ->
                "夜间窗口 ${r.nightWindowStartHour}~${r.nightWindowEndHour} 含非法小时"
            r.nightStaminaMultiplier.isNaN() || r.nightStaminaMultiplier < 1.0 ->
                "nightStaminaMultiplier=${r.nightStaminaMultiplier}（倍率只可能 >=1；该游戏无夜战惩罚请写 1.0）"
            r.mapCoordMax !in 100..10000 ->
                "mapCoordMax=${r.mapCoordMax} 不在可信区间 100~10000" +
                    "（太小会把玩家真实坐标当幻觉裁掉，太大则这道门禁形同虚设）"
            else -> null
        }
        if (impossibleRule != null) return false to "机制数值不可能成立：$impossibleRule"

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
                put("map_coord_max", rules.mapCoordMax)
            })

            // 2. 按键语义字典
            val btnJson = JSONObject()
            semanticButtons.forEach { (key, btn) ->
                btnJson.put(key, JSONObject().apply {
                    put("primary_keyword", btn.primaryKeyword)
                    put("aliases", JSONArray(btn.aliases))
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
                        put("defender_total_soldiers", s.defenderTotalSoldiers)
                    })
                }
                put("land_suggestions", landArr)
            })

            // 4. 战术流水线默认调优参数
            put("tactical_defaults", JSONObject().apply {
                put("paving_default_slots", JSONArray(tacticalDefaults.pavingDefaultSlots))
                put("paving_step_interval_ms", tacticalDefaults.pavingStepIntervalMs)
                put("immunity_default_troop_slot", tacticalDefaults.immunityDefaultTroopSlot)
                put("siege_main_squad_slot", tacticalDefaults.siegeMainSquadSlot)
                put("siege_demolition_slots", JSONArray(tacticalDefaults.siegeDemolitionSlots))
                put("raid_patrol_interval_ms", tacticalDefaults.raidPatrolIntervalMs)
                put("raid_decision_c_auto_counter", tacticalDefaults.raidDecisionCAutoCounter)
                put("raid_alarm_sound", tacticalDefaults.raidAlarmSound)
            })

            // 5. 看门狗异常弹窗关闭词表
            put("watchdog_keywords", JSONArray(watchdogKeywords))

            // 5b. 场景判定词表（组 ID → 词；缺失即由引擎回落率土默认并打 W 日志）
            val sceneJson = JSONObject()
            sceneKeywords.forEach { (groupId, words) ->
                sceneJson.put(groupId, JSONArray(words))
            }
            put("scene_keywords", sceneJson)

            // 5c. 武将基础速度参考表（先手判定的兜底依据；空 = 该游戏不判定先手）
            val speedJson = JSONObject()
            heroBaseSpeed.forEach { (name, speed) -> speedJson.put(name, speed) }
            put("hero_base_speed", speedJson)

            // 5d. PVE 守军机制词表（机制词 → 解读；空 = 该游戏不做机制判定）
            val mechanicJson = JSONObject()
            pveMechanicNotes.forEach { (word, note) -> mechanicJson.put(word, note) }
            put("pve_mechanic_notes", mechanicJson)

            // 6. 感知层级策略（确定性优先阶梯；缺省即 SLG_DEFAULT，不开检测器）
            put("vision_policy", VisionPolicy.toJson(visionPolicy))
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
                // 缺键时沿用 1500（率土十三州）：老云端配置不用改也能按今天的行为跑。
                // 新游戏若地图尺寸不同，**必须显式给出这一项**，否则合法坐标会被门禁丢弃。
                mapCoordMax = rObj.optInt("map_coord_max", 1500)
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

                buttons[key] = ButtonDef(key, primaryKeyword, aliases)
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
                    note = item.optString("note", ""),
                    defenderTotalSoldiers = item.optInt("defender_total_soldiers", 0)
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
                siegeMainSquadSlot = tObj.optInt("siege_main_squad_slot", 1),
                siegeDemolitionSlots = parseIntList("siege_demolition_slots", listOf(2, 3)),
                raidPatrolIntervalMs = tObj.optLong("raid_patrol_interval_ms", 4000L),
                raidDecisionCAutoCounter = tObj.optBoolean("raid_decision_c_auto_counter", true),
                raidAlarmSound = tObj.optBoolean("raid_alarm_sound", true)
            )

            // 5. Watchdog keywords
            val wArr = root.optJSONArray("watchdog_keywords") ?: JSONArray()
            val watchdog = (0 until wArr.length()).map { wArr.getString(it) }

            // 5b. 场景判定词表（组 ID → 词）。缺失就是"没配"，交由引擎回落率土默认词表并打 W 日志；
            //     这里不做任何补全，免得一份空表在端上被当成"这个游戏就该用率土文案"。
            val sceneObj = root.optJSONObject("scene_keywords") ?: JSONObject()
            val sceneWords = mutableMapOf<String, List<String>>()
            sceneObj.keys().forEach { groupId ->
                val arr = sceneObj.optJSONArray(groupId) ?: return@forEach
                val words = (0 until arr.length()).map { arr.getString(it) }.filter { it.isNotBlank() }
                if (words.isNotEmpty()) sceneWords[groupId] = words
            }

            // 5c. 武将基础速度参考表（只收数字项，脏数据直接丢，不让它把先手判定带偏）
            val speedObj = root.optJSONObject("hero_base_speed") ?: JSONObject()
            val heroSpeed = mutableMapOf<String, Int>()
            speedObj.keys().forEach { name ->
                if (name.isNotBlank()) {
                    val v = speedObj.optInt(name, -1)
                    if (v > 0) heroSpeed[name] = v
                }
            }

            // 5d. PVE 守军机制词表（词与解读都必须非空，脏项直接丢，避免给玩家念半句话）
            val mechanicObj = root.optJSONObject("pve_mechanic_notes") ?: JSONObject()
            val mechanicNotes = mutableMapOf<String, String>()
            mechanicObj.keys().forEach { word ->
                val note = mechanicObj.optString(word, "")
                if (word.isNotBlank() && note.isNotBlank()) mechanicNotes[word] = note
            }

            // 6. Vision policy（缺失/为空回退 SLG_DEFAULT，不误开检测器）
            val visionPolicy = VisionPolicy.fromJson(root.optJSONArray("vision_policy"))

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
                watchdogKeywords = watchdog,
                sceneKeywords = sceneWords,
                heroBaseSpeed = heroSpeed,
                pveMechanicNotes = mechanicNotes,
                visionPolicy = visionPolicy
            )
        }
    }
}

/**
 * 游戏核心机制数值定义
 */
data class GameRules(
    /**
     * 体力上限 (率土 120, 三战 120)。
     * 它现在真正参与两处判定：部队卡片抽体力的分母（OCR "xx/120"）与
     * 日常后勤的满溢水位。分母写死 120 时，换个上限的游戏会直接读不出体力。
     */
    val maxStamina: Int = 120,
    val staminaPerAction: Int = 20,            // 单次出征/行军体力消耗 → 被 requiredStaminaNow() 使用
    /**
     * 士气上限 (率土 2026 征服赛季 120, 三战 100)。
     * 被 TroopStatusDetector.gradeMorale() 用作“满士气”判据：不取这个值而写死 120，
     * 三战（满值 100）就永远得不到 OPTIMAL 档。
     */
    val maxMorale: Int = 120,
    /**
     * 基准士气值（无加成无减损基准）。
     *
     * 被 TroopStatusDetector.gradeMorale() 用作 NORMAL / LOW_PENALTY 的分界：
     * 低于它就意味着战力要打折，远射/打架应当谨慎。
     */
    val moraleStandard: Int = 100,
    val minMoraleForPaving: Int = 100,        // 铺路最小士气阈值 → 被各战术流与无人托管使用
    /**
     * 占领后免战时长（默认 60 分钟）。
     *
     * 它现在的真实角色：**看得到金色免战罩、但 OCR 读不出剩余倒计时时的保守估计下界**
     * （见 ImmunityBreakFlow）。这个分支不再拿一个拍脑袋的“60 秒”去出征，
     * 而是按本值给出“至少还要多久才破免”并向用户中止。热更改本值，告警文案与
     * 估计立刻跟着变。
     */
    val immunityDurationSec: Int = 3600,
    val immunityPaddingMs: Long = 1000L,       // 00:00:01 破免压秒触敌提前量补偿 → 已接入攻城/定时/托管
    val nightWindowStartHour: Int = 0,         // 夜间窗口起点 → 被 isNightNow() 使用
    val nightWindowEndHour: Int = 7,           // 夜间窗口终点 → 被 isNightNow() 使用
    val nightStaminaMultiplier: Double = 1.0,  // 夜间体力消耗倍率 → 被 requiredStaminaNow() 使用
    /**
     * 大地图世界坐标的**有效上界**（下界恒为 1）。率土十三州约 1500×1500。
     *
     * 它是防幻觉门禁里"这个坐标可不可能存在"的唯一判据（[isValidWorldCoord]）：
     * 过去四处解析坐标的地方各自写死 `1..1500`，等于把率土的地图尺寸当成
     * 所有游戏的公理——换个地图更大的游戏，合法坐标会被自己的门禁判成幻觉丢掉；
     * 换个更小的游戏，越界坐标反而能通过。两种方向都是静默错，所以收进知识库。
     */
    val mapCoordMax: Int = 1500
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

    /**
     * 这对世界坐标在**本游戏**的大地图上是否可能真实存在。
     *
     * 用途只有一个：把"读出来的坐标"送进下发链路之前的那道防幻觉门禁
     * （见 ai/decision/DualTrackSafetyGate 与各坐标解析点）。
     * [mapCoordMax] 被配成非法值（<1）时一律判 false——**宁可拒绝，也不放行一个无法校验的坐标**。
     */
    fun isValidWorldCoord(x: Int, y: Int): Boolean =
        mapCoordMax >= 1 && x in 1..mapCoordMax && y in 1..mapCoordMax
}

/**
 * 语义按键与 OCR 容错别名定义
 */
data class ButtonDef(
    val actionKey: String,                    // 按键动作标识 (如 ATTACK, SWEEP, DEFEND)
    val primaryKeyword: String,               // 主关键字 ("出征")
    val aliases: List<String> = emptyList()   // OCR 容错别名 ("出 征", "确定出征", "出征作战")
    /*
     * 说明：这里刻意**不再**定义 fallbackRoiRatio（屏幕归一化兜底区域）。
     *
     * 它曾经存在并被序列化，但从未被任何代码读取：按键定位只走 OCR 关键字匹配。
     * 真正需要它时，含义是“读不到关键字就按猜的坐标点一下”——在出征/确定这类
     * 高风险链路上，这种“凭猜测开火”比“报错并重试”危险得多（可误触到付费或迁城入口）。
     * 所以直接删除，而不是留一个看起来能用、实则无人维护（且危险）的字段。
     */
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
    val recommendedSoldiers: Int,             // 推荐开荒/攻打兵力（**我方**该带多少）
    val safeHeroes: List<String>,             // 推荐优先挑着打的软柿子守军
    val blacklistHeroes: List<String>,        // 坚决避开的翻车黑名单守军
    val note: String,                         // 战略注意事项
    /**
     * 守军**总兵力**（地块上实际有多少兵，与 [recommendedSoldiers] 不是一回事）。
     *
     * 为什么单独存这一项：玩家判断"能不能打"靠的是拿自己的兵和守军的兵比，
     * 而库里过去只有"推荐带多少"这一个结论值，玩家看不到依据；
     * 6 级地起出现的"双队"更是只能靠 note 里一句人读的话，机器无法据此说话。
     * 数值取官方《各等级土地兵力值统览》，出处写在入库处注释里。
     * **0 = 尚无可靠数据**，调用方必须按"未知"处理，绝不许拿 0 当"守军没兵"。
     */
    val defenderTotalSoldiers: Int = 0
)

/**
 * 战术流水线执行参数
 */
data class TacticalDefaults(
    val pavingDefaultSlots: List<Int> = listOf(1, 2, 3),
    /**
     * 自动铺路的逐格步进基准。
     * 被 RoadPavingFlow 作为两块地之间间隔的**下限**使用（上限在其上 +1200ms 随机），
     * 热更它等于改铺路节奏，而不是把节奏改成恒速（恒周期是最好提取的机器特征）。
     */
    val pavingStepIntervalMs: Long = 2000L,
    val immunityDefaultTroopSlot: Int = 1,
    /*
     * 说明：这里刻意**不再**定义 immunityBreakPrecisionMs。
     *
     * 它与 GameRules.immunityPaddingMs 是同一个意思（破免压秒时的提前量补偿），
     * 但没有任何代码读它；实际生效的那一项是 immunityPaddingMs（已接入悬浮窗/
     * 无人托管/定时管家）。两个名字管同一件事，热更时改哪个都不确定，因此删掉重复项。
     */
    val siegeMainSquadSlot: Int = 1,
    val siegeDemolitionSlots: List<Int> = listOf(2, 3),
    val raidPatrolIntervalMs: Long = 4000L,
    val raidDecisionCAutoCounter: Boolean = true,
    val raidAlarmSound: Boolean = true
)
