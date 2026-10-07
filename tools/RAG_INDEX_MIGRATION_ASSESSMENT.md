<!-- 机读对账行：由 tools/rag_bench.py --doc-marker 生成，禁止手抄改动 -->
BENCH-THRESHOLDS:embed_mul_adds=20000000;scan_ratio_migrate=0.1;truncation_guard=4096;corpus_entries=95

# RAG 索引迁移评估：HNSW 图检索 与 sqlite-vec（P5）

> 复跑命令：`python tools/rag_bench.py`（退出码 0 = 维持现状 / 1 = 越线待决策 / 2 = 本闸门不可信）
> 判据自测：`python tools/rag_bench.py --selftest`（15 项断言）
> 本文所有"实测"均来自上面这个脚本在本机的输出；所有"估算"都单独标注了估算口径。

## 一、结论

**STAY：不启用图索引做近似检索，不引入 sqlite-vec。全量精确扫是当前规模下的正确解。**

三个理由，按重要性排：

1. **扫描不是瓶颈，查询侧 embedd 才是。** 真实候选池只有 21 条（生产调用一律带
   `category`，最大的一类是语料派生出来的 `HERO_COUNTER` 21 条），一次检索的乘加是
   `21×512 = 10,752`；
   而把查询句子变成 512 维向量要跑一次 bge-small-zh INT8，量级 `~2×10^7` 乘加。
   比值 **0.054%**。把 0.054% 的那一段优化掉，端到端延迟不会有任何可感知变化。
   （语料从 42 条扩到 95 条之后这个结论**没有翻转**：池子最大的一档从 11 条涨到 21 条，
   比值翻不到一个数量级，离 10% 的迁移线还差三个数量级。）
2. **工程里原来那条"图检索"分支是负优化，已经删掉。** 它把 V2 容器尾部的第 0 层邻接表
   读进内存，然后从**全部节点**起 BFS 收候选，再用 `seen.size > 4096` 截断。
   起点集已经是全图 ⇒ 候选集恒等于"全量扫"，一次余弦都没省，只多跑一趟遍历；
   而那个截断在语料超过 4096 后会**按遍历顺序静默丢掉尾部条目**（实测：5000 条丢 904 条），
   不报错、不降级，只是结果变少——而这个结果直接决定软柿雷达把哪块地划成"可打"。
   取证与复刻见 `rag_bench.py: pseudo_ann_candidate_set()`。
3. **sqlite-vec 换不来近似检索，只会换来了一个更大的包。** 它自己就是
   brute-force only（作者原话："Only brute-force search for now"，v0.1.x 无 ANN 索引），
   而要在 Android 上用它，必须先解决"系统 SQLite 不允许加载扩展"，
   也就是把整套自带 SQLite 塞进 APK。用 1.7MB 级体积去换一个同样全量扫的实现，
   对本项目是净亏。详细代价见第四节。

## 二、实测数据（PC 参考口径，不参与裁决）

资产：`client/app/src/main/assets/models/slg_knowledge_vector_hnsw.bin`

| 项 | 值 |
| --- | --- |
| 容器体积 | 226.1 KB |
| magic / version | `SLG_HNSW_RAG_V2` / 2 |
| 条目数 / 维度 | **95** / 512 |
| 向量区 | 190.0 KB（占 84.0%） |
| 文本区（id/category/title/keywords/content/advice） | 29.4 KB |
| 尾部 HNSW 第 0 层图区 | 4.5 KB，95 节点，平均度 11.1 |
| 容器尾部残留 | 0 字节（生成器与端侧解析器口径一致） |
| 语料源 `tools/a_plus_plus/corpus_stzb.jsonl` | 95 行（与条目数一致，闸门盯这条） |
| 语料怎么来 | **由 `tools/build_rag_corpus.py` 从知识包 `pipeline/rate_of_land.json` 派生**，每条带 `source`；不再手写。双向对账见 `tools/validate_rag_corpus_provenance.py` |
| 分类分布 | DEFENDER_AVOID 11 / DEFENDER_HARD 10 / DEFENDER_MODERATE 7 / DEFENDER_SAFE 11 / LAND_SIEGE 6 / DEFENDER_LAND 5 / HERO_COUNTER 21 / SKILL_SYNERGY 5 / TACTICAL_DECREE 19 |

上一版这张表是 42 条 / 94.3 KB，且只有守军类：`SKILL_SYNERGY`（战法协同）与
`TACTICAL_DECREE`（军令）两个检索通道在资产里是 **0 条**，全靠端侧 `seedEntries()`
那几条硬编码兜底 —— 即"军师推演战法冲突 / 给军令建议"从来没有语料撑起过。
扩到 95 条是把这两个通道填上，不是把同一个通道灌得更长。

单次查询全量扫（纯 Python 逐元素循环，与 Kotlin `computeCosineSimilarity` 同构；取 3~5 次最小值）：

| 候选池 | 乘加次数 | 占一次 embedd | 本机耗时 |
| --- | --- | --- | --- |
| 95 | 48,640 | 0.24% | ~3.5 ms |
| 200 | 102,400 | 0.51% | ~7.4 ms |
| 1,000 | 512,000 | 2.56% | ~35.3 ms |
| 2,000 | 1,024,000 | 5.12% | ~73.7 ms |
| 3,907 | 2,000,384 | 10.00% | ~158.3 ms |
| 4,096 | 2,097,152 | 10.49% | ~164.7 ms |

**口径声明（重要，别把这张表读成端侧数字）**

* 本机 = Windows PC + CPython；端侧 = ARM 大核 + ART JIT。同一份逐元素循环，
  ART 通常比 CPython 快数倍，所以这张表是端侧的**上界参考**，不是实测。
  裁决因此只看与机器无关的乘加比值，不看毫秒。
* `embed_mul_adds = 20000000` 是**量级估算**：模型文件 `bge_zh_int8.onnx` 23,375 KB，
  INT8 权重按约 1 字节/参数反推 ≈ 2×10^7 参数 ⇒ 一次前向至少 2×10^7 乘加。
  没有逐层解析 ONNX 计算图（本机无 `onnx`/`onnxruntime` 模块），
  所以这个数**只用来定数量级**：结论要成立，只需它比扫描大 2 个数量级以上，
  而现在大 3 个数量级。真机上量出 `embedd 实际耗时` 后可以把比值换成耗时比。
* 合成规模那一列是把 95 条向量循环复制到目标条数，只为看复杂度趋势，
  **不代表真实语料的召回表现**。

## 三、临界阈值：什么时候必须回头改

判据写成与机器无关的形式，`rag_bench.py` 与本文同源（上面那行机读标记）。
满足**任意一条**就要重开这个评估，不是可选：

| 触发条件 | 阈值 | 为什么是这个量级 |
| --- | --- | --- |
| 单个 `category` 候选池的扫描乘加 ≥ 一次查询 embedd 的 10% | `scan_ratio_migrate = 0.1`，512 维下约 **3,907 条/类** | 10% 是"优化开始能被用户感知"的下限；再低就淹没在 embedd 抖动里 |
| 候选池条数达到截断防线 | `truncation_guard = 4096` | 任何按遍历顺序截断的实现从这里开始静默丢条目 |
| 端侧真机实测：单次检索 P95 > 50 ms | 需真机数据，本机测不了 | 雷达是轮询型调用，50 ms 是"不影响 UI 帧"的经验线，**属待校准** |

配套硬要求（越线后必须一起满足，否则等于没做）：

1. 换真 HNSW 就要有**固定入口点 + `ef` 贪心扩展 + 分层图**，
   而不是"第 0 层邻接 + 从所有点起 BFS"。后者已被证明是伪 ANN（第一节理由 2）。
2. 语料涨到千级时，先做的**不是**上索引，而是把 `category` 分池做实：
   现在生产调用已经全部带 `category`，单池最大 21 条（`HERO_COUNTER`）；扩到千条时如果只扩某一类，
   阈值就要按单池算，不能按全库算。
3. 任何近似检索都要带**召回回归**：同一批查询在精确扫与近似扫下的 top-3 差异率
   必须有数字。没有这个数字就不许上线——因为检索结论会流到"这块地能不能打"。

## 四、sqlite-vec 的具体代价（Android）

| 事项 | 事实 | 来源 |
| --- | --- | --- |
| 系统 SQLite 能不能 `load_extension` | 拿不到。requery sqlite-android 把 **"Loadable extension support"** 明确列进它相对 AOSP 绑定的改动清单 ⇒ 想用扩展就得换掉系统那份 SQLite | requery/sqlite-android README "Changes" 一节（本次抓取）；AOSP 编译 flags 未逐项核验 |
| 自带 SQLite 的体积 | requery `sqlite-android`：`arm64-v8a` **~1.7 MB**（`armeabi-v7a` ~1.2 MB、`x86_64` ~1.8 MB）；minSdk 21；接法是把 `android.database.sqlite.SQLiteDatabase` 换成 `io.requery...SQLiteDatabase`（Room 走 `RequerySQLiteOpenHelperFactory`） | 该 README 的 "CPU Architectures / Requirements / Usage" |
| vec0 扩展体积 | 官方 release v0.1.9 的 `sqlite-vec-0.1.9-loadable-android-aarch64.tar.gz` = **58.8 KB**（压缩，另有 armv7a 55.3 / i686 56.1 / x86_64 55.6）；解包后 `.so` 体积本次未核验 | GitHub releases API 实测 |
| 本工程 ABI | `abiFilters 'arm64-v8a'`（单 ABI），所以按 1 个 ABI 计 | `client/app/build.gradle` |
| 现有 SQLite 依赖 | **0 处**（`java/` 全量搜 `sqlite|Room|SupportSQLite` 无命中）⇒ 这是从零引入一套原生存储栈 + 一套数据库文件生命周期 | 本次搜索 |
| 它能提供 ANN 吗 | 不能，v0.1.x 只有 brute-force | 作者博客 "Only brute-force search for now" |
| 它的强项对本项目有用吗 | 用处小。它强在 OLTP（`vec0` 虚拟表的增删改与分块存储）与"同一套 SQL 跨端"；本项目的语料是**随 APK 打包/云热更的只读快照**，运行期不增删 | — |
| 参考性能（外部，非本机） | sift1m（100 万条 ×128 维，k=20，M1 mini）：`sqlite-vec static` 17 ms、`vec0` 33 ms、numpy 136 ms | 作者博客 benchmark |

一句话：为了一个 95 条 / 226 KB 的只读语料，要付"自带 SQLite ≈1.7 MB + 扩展 + 一套 JNI 加载与生命周期代码 + 数据库文件管理"，
换来的仍然是全量扫。**这就是 P5 的否决理由**，不是"没时间做"。
（语料从 42 涨到 95 条、体积从 94 KB 涨到 226 KB，这条理由**没有松动**：
要付的 1.7 MB 一个字没少，而省下来的仍然只是 0.054% 那一段。）

什么时候 sqlite-vec 会变成正确选择：语料需要**运行期增删**（例如把玩家自建阵容存本地）、
或需要跨表 SQL 关联（向量 + 结构化字段一起过滤）、或条数进入 10 万级
——那时它省的是开发与内存，而不只是延迟。

## 五、代码侧现状（可核对）

* `SlgRagEngine.loadFromBinaryAsset()` 解析 V2 时**跳过**尾部图区；日志明确写
  `检索方式=全量精确扫（未启用图索引）`，不是"静默没实现"。
* `SlgRagEngine.search()` 候选池注释里写了被删分支的三条理由，与本文件第一、三节一致。
* V2 容器仍能正常解析（图区不读不影响条目解析；`rag_bench.py` 校 `尾部残留=0`）。
* `tools/p1/check_assets.py` 继续校 RAG 索引契约（magic/条数/维度/条目可解析）。

## 六、本文的诚实清单（尚未证明的事）

1. **端侧一次检索的真实毫秒数**没测过：需要真机 + `Trace` 打点。
   现在所有毫秒都是 PC 口径，且已声明为上界参考。
2. **bge 查询侧 embedd 的乘加/延迟**是量级估算，未逐层解析 ONNX 计算图。
3. **`scan_ms_migrate` 一类的体验阈值**（第三节第 3 行）是经验值，标着**待校准**。
4. sqlite-vec 解包后 `.so` 的确切体积、以及与本项目现有 native 库的符号冲突风险，
   都没有验证过——真要迁移前这两项必须先做。
5. 近似检索的**召回损失**没有本项目的数字（第三节要求 3）：所以现在也**没有资格**谈"上 ANN 划算"。
