package com.stzb.assistant.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.stzb.assistant.knowledge.KnowledgeBaseManager
import java.io.File

/**
 * 每款游戏各自一份数据的作用域工具 (PerGameScope)
 *
 * ## 它要解决的到底是什么
 * 知识库解耦做完之后，"词表/数值/名单"都跟着 gameId 走了，但还有一整类数据
 * **不在知识包里、也不该在知识包里**：用户在这台机器上标定出来的锚点、地图投影、
 * 场景指纹、按键模板。它们是"这台设备 + 这款游戏 + 这个机型"的产物，
 * 天然必须按游戏分域。
 *
 * 过去它们全挤在一个不带游戏后缀的键/目录里。后果不是"少个功能"，而是
 * **拿 A 游戏的标定去点 B 游戏的界面**：
 *   * UiAnchors：率土出征面板的部队标签位置，落到三战的面板里就是往空白处点；
 *   * MapProjection：每格像素数与镜头中心世界坐标是率土地图量出来的，
 *     换游戏后所有世界坐标换算全错，且错得很自信（下发的目标坐标看着合法）；
 *   * SceneFingerprint：率土底部功能栏的哈希，可能把别的游戏的界面认成"主界面"；
 *   * ButtonTemplateStore：率土"出征"按钮的像素模板拿去匹配三战界面，
 *     匹配引擎会给出一个峰值，然后我们照着那个峰值点下去。
 * 这类错误的共同点是**没有任何一方报错**，玩家只会觉得"切了游戏就乱点"。
 *
 * ## 三条口径
 *   1. **只有本机产出的数据在这里分域**；打包进 APK 的资产（assets/…）由
 *      [assetDirs] 给出候选顺序，同样带游戏后缀，理由见该函数注释。
 *   2. **旧数据归率土**（[LEGACY_OWNER_GAME_ID]）：分域之前本产品只真跑过率土，
 *      老键里的标定值只可能是率土的。把它平白发给另一款游戏就是上面那条事故；
 *      所以迁移只写进 stzb 域，其它游戏首次进入就是"未标定"，界面会如实显示。
 *   3. **迁移是幂等的一次性动作**：迁完删掉旧键；中途被杀也不会重复迁移，
 *      因为新键存在时优先用新键。
 *
 * 注意：切游戏时 SharedPreferences 里**其它游戏**的数据不能被清掉，
 * 所以清标定一律只删本游戏那一份（见各存储类的 clearAllCalibration）。
 */
object PerGameScope {

    private const val TAG = "PerGameScope"

    /**
     * 分域之前那些无后缀键/目录所属的游戏。
     *
     * 这是本工程里**唯一**一处把"旧数据属于谁"写死的地方，
     * 各存储类一律引用本常量，不要再各自复制一份 "stzb" 字面量。
     */
    const val LEGACY_OWNER_GAME_ID = "stzb"

    /** 当前激活游戏的 id（总控未初始化时是内置默认档案，与 activeProfile 初值一致）。 */
    fun gameId(): String = KnowledgeBaseManager.activeProfile.gameId

    /** 裸键 -> 带游戏后缀的键（"data" -> "data_stzb"）。 */
    fun key(baseKey: String): String = baseKey + "_" + gameId()

    /** 裸目录名 -> 带游戏后缀的目录名（"button_templates" -> "button_templates_stzb"）。 */
    fun dirName(baseName: String): String = baseName + "_" + gameId()

    private fun prefs(context: Context, name: String): SharedPreferences =
        context.getSharedPreferences(name, Context.MODE_PRIVATE)

    /**
     * 读本游戏的分域值；本游戏没有就读旧键并按 [LEGACY_OWNER_GAME_ID] 迁移一次。
     *
     * @return null 表示本游戏确实没有标定数据（调用方应走默认值，**不是**用旧键兜底）
     */
    fun readScopedString(context: Context, prefsName: String, baseKey: String): String? {
        val p = prefs(context, prefsName)
        val scoped = key(baseKey)
        p.getString(scoped, null)?.let { return it }
        if (gameId() != LEGACY_OWNER_GAME_ID) {
            // 旧键属于率土。当前是别的游戏，绝不能拿它当本游戏的标定。
            return null
        }
        val legacy = p.getString(baseKey, null) ?: return null
        p.edit().putString(scoped, legacy).remove(baseKey).apply()
        Log.i(TAG, "已把旧标定键 [$baseKey] 迁入分域键 [$scoped]（旧键属于 $LEGACY_OWNER_GAME_ID）。")
        return legacy
    }

    /** 写本游戏的分域值。 */
    fun writeScopedString(context: Context, prefsName: String, baseKey: String, value: String) {
        prefs(context, prefsName).edit().putString(key(baseKey), value).apply()
    }

    /** 只清本游戏的这一项（不清整个 prefs 文件，避免误删其它游戏的标定）。 */
    fun removeScopedString(context: Context, prefsName: String, baseKey: String) {
        prefs(context, prefsName).edit().remove(key(baseKey)).apply()
    }

    /**
     * 取本游戏的数据目录；旧的同名目录会在率土域下被一次性改名接管。
     *
     * 目录走"改名"而不是"复制"：模板图可能有好几张，复制会留下两份真相。
     */
    fun scopedDir(context: Context, baseDirName: String): File {
        val root = context.filesDir
        val scoped = File(root, dirName(baseDirName))
        if (scoped.exists()) return scoped
        val legacy = File(root, baseDirName)
        if (legacy.exists() && gameId() == LEGACY_OWNER_GAME_ID) {
            if (legacy.renameTo(scoped)) {
                Log.i(TAG, "已把旧目录 [${legacy.name}] 改名为分域目录 [${scoped.name}]。")
            } else {
                Log.w(TAG, "旧目录 [${legacy.name}] 改名失败，本次仍按未标定处理（不复制，避免两份真相）。")
            }
        }
        if (!scoped.exists()) scoped.mkdirs()
        return scoped
    }

    /**
     * 打包资产的候选目录顺序：先本游戏专属目录，再（仅率土）旧的无后缀目录。
     *
     * 为什么旧目录只给率土：`assets/templates/`、`assets/defender_refs/` 这些是
     * 照着率土界面截出来的图。三战玩家用它们做模板匹配，匹配器照样会给出峰值，
     * 于是我们把一个率土的按钮位置当成三战的按钮点下去——这是"看起来在工作、
     * 内容却来自另一款游戏"，属于必须禁止的那一类。
     *
     * @param baseDir assets 下的裸目录名，如 "templates"
     * @return 依序尝试的目录路径，形如 ["templates/sgz", "templates"]（最后一项只对率土出现）
     */
    fun assetDirs(baseDir: String): List<String> {
        val perGame = "$baseDir/$gameId()"
        return if (gameId() == LEGACY_OWNER_GAME_ID) listOf(perGame, baseDir) else listOf(perGame)
    }

    /**
     * 单个资产**文件名**的候选顺序：先带游戏标记的名字，再（仅率土）旧的无标记名字。
     *
     * 与 [assetDirs] 是同一条口径，只是针对"文件名里插标记"这种组织方式
     * （`models/` 下的权重都是扁平文件，没法按目录分域）。
     * 标记插在扩展名之前：`yolo26s.param` → `yolo26s_stzb.param`。
     *
     * 为什么率土额外保留旧名：分域之前发布的权重名有两种写法
     * （`yolo26s_stzb.param` 已带标记、`slg_knowledge_vector_hnsw.bin` 未带标记），
     * 而构建这些资产的脚本（tools/p1/build_rag_index.py、tools/p1/check_assets.py）
     * 认的就是旧名。让旧名继续作为**只发给率土**的兜底候选，
     * 既不用改构建脚本，也不会让别的游戏吃到率土的权重。
     *
     * @param fileName 资产文件名（不含目录），如 `slg_knowledge_vector_hnsw.bin`
     * @return 依序尝试的文件名，形如 ["slg_knowledge_vector_hnsw_sgz.bin"]
     *         （率土则是 ["slg_knowledge_vector_hnsw_stzb.bin", "slg_knowledge_vector_hnsw.bin"]）
     */
    fun assetNameCandidates(fileName: String): List<String> {
        val dot = fileName.lastIndexOf('.')
        val stem = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot) else ""
        val scoped = "${stem}_${gameId()}$ext"
        return if (gameId() == LEGACY_OWNER_GAME_ID && scoped != fileName) {
            listOf(scoped, fileName)
        } else {
            listOf(scoped)
        }
    }

    /**
     * 注册"切游戏就重新载入"。
     *
     * 各存储类在 attach() 里调用它，把自己 load() 的闭包交进来。
     * 不这么做的后果：同一次进程里切了游戏，内存里仍是上一款游戏的标定，
     * 而盘上已经写对域了 —— 表现成"切回去才对，切过来时乱点一阵"。
     */
    fun reloadOnProfileSwitch(reload: () -> Unit) {
        KnowledgeBaseManager.registerListener(object : KnowledgeBaseManager.ProfileChangeListener {
            override fun onProfileChanged(newProfile: com.stzb.assistant.knowledge.GameProfile) {
                try {
                    reload()
                } catch (e: Exception) {
                    Log.w(TAG, "切换到 [${newProfile.gameId}] 后重新载入分域数据失败: ${e.message}")
                }
            }
        })
    }
}
