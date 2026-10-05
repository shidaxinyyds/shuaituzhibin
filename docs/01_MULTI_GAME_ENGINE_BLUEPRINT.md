# 多游戏引擎分层蓝图 · 确定性优先 + 模型升级阶梯

> **一句话**：一套**通用引擎核** + 每游戏一个**知识包** + 一个**默认关、可插拔的检测器模块**。
> 视觉感知按「**升级阶梯**」从便宜到贵逐级启用，**只有下一级被真机实测证明兜不住某个具体缺口时，才爬一级**。
> 本蓝图同时是**代码事实**的说明——文中每个概念都能在下述源文件里找到对应实现。

---

## 一、三层架构

```mermaid
flowchart TD
    A["屏幕帧 (MediaProjection)"] --> B["① 引擎核（100% 通用，写一次）"]
    B --> B1["确定性感知：模板/HSV/几何 (OpenCvMatcher, TileStatusDetector, RaidRadarDetector)"]
    B --> B2["OCR：数字/文本/按钮 (OcrManager, StzbUiMatcher)"]
    B --> B3["分类器：轻量 CNN (DefenderTemplateClassifier, SceneFingerprint)"]
    B -.->|仅当游戏开启 DETECTOR 层| B4["检测器：YOLO (YoloDetector, 默认关)"]
    B1 & B2 & B3 --> C["态势模型 → 决策/流程 (tactics/*, EdgeSlmEngine, SlgRagEngine)"]
    C --> D["拟人化操作 (antiban/*, AutoTouchService, BezierTrajectory)"]
    E["② 每游戏知识包 GameProfile (JSON, 云端热更)"] -->|声明 vision_policy / 模板 / 词表 / 数值| B
    F["③ 可插拔检测器模块 (PerceptionTier.DETECTOR)"] -.->|动作类按需挂载| B4
```

| 层 | 职责 | 换游戏时 | 代表实现 |
| :-- | :-- | :-- | :-- |
| **① 引擎核** | 截屏→感知→态势→决策→拟人触控，全游戏复用 | **不动** | `ScreenCaptureService`、`ocr/*`、`tactics/*`、`antiban/*`、`service/EngineBridge` |
| **② 知识包** | 模板图、UI 语义词表、OCR 词表、数值、**感知层级策略** | **只换这里** | `knowledge/GameProfile.kt`（JSON 可云热更） |
| **③ 检测器模块** | 密集/遮挡/跨缩放多目标 | **按需插** | `ai/vision/YoloDetector.kt`（默认关） |

> 关键红利：**加一款游戏 ≈ 加一个知识包**，不换技术；只有真需要的游戏才额外"插一个检测器"。

---

## 二、模型升级阶梯（PerceptionTier）

> 实现：`client/.../ai/vision/PerceptionTier.kt`。成本自下而上单调递增。

| 层级 | 解决什么 | 数据成本 | 端侧(无 GPU / 185MB) | 何时用 |
| :-- | :-- | :-- | :-- | :-- |
| **DETERMINISTIC** | 模板匹配 + 颜色/HSV + 几何 | **零** | ✅ 极轻 | 默认打底，一切从这里开始 |
| **OCR** | 读数字/文本/按钮（兵堆数量、守军、界面词） | 零（现成模型） | ✅ | 默认开 |
| **CLASSIFIER** | 轻量 CNN 分类：建筑等级/稀有度/buff/场景 | **几百张裁剪**（不画框） | ✅ 几 MB | 模板太脆时补，**优先于检测器** |
| **DETECTOR** | YOLO 目标检测：密集/遮挡/跨缩放多目标 | 几千框 + GPU | ⚠️ 勉强 | **仅**动作类 / 连续 3D 地图 |
| **POLICY** | 决策/操作策略（规则微脑 / RL / 行为克隆） | 极高 | ⚠️ | 实时动作类"会玩"才需 |

**升级铁律（防过度设计）**：
1. 永远先用**最便宜**的层级把功能跑通；
2. 只有当某个**具体场景**在真机上被证明"下一级兜不住"，才允许爬一级；
3. 爬级前必须能说清"**是哪一类目标、什么失败模式、为什么确定性不行**"——说不出就不上。

---

## 三、每类游戏默认用哪级（VisionPolicy）

> 实现：`VisionPolicy` 的三个预设 + `GameProfile.visionPolicy`（默认 `SLG_DEFAULT`，Fail-Closed）。

| 游戏族 | 默认层级 | 含 DETECTOR(YOLO)? | 判据 |
| :-- | :-- | :-- | :-- |
| **SLG 沙盘**（率土/三战/谋定/RoK） | DETERMINISTIC + OCR + CLASSIFIER | ❌ **不用** | 格子地图 + 归属色 + 兵堆**数字角标** + 可锁缩放 + 全模板贴图 |
| **SLG 连续 3D**（无尽的拉格朗日） | 同上，**边缘可开 DETECTOR** | ⚠️ 按需 | 连续太空、3D 舰船旋转/缩放/密集舰队——确定性吃力的那部分才补 |
| **回合 MMO**（梦幻西游） | DETERMINISTIC + OCR | ❌ 不用 | 回合制、重 UI 流程、战斗固定站位 |
| **实时动作**（DNF 手游） | 全开（含 DETECTOR + POLICY） | ✅ **必须** | 实时、动态、满屏特效、密集遮挡、非模板 |

### 逐游戏落位
| 游戏 | 族 | vision_policy | 备注 |
| :-- | :-- | :-- | :-- |
| 率土之滨 | SLG | `SLG_DEFAULT` | **本命盘，去 YOLO，确定性 + OCR 打底** |
| 三国志·战略版 | SLG | `SLG_DEFAULT` | 与率土同构，复用引擎核，只加知识包 |
| 三国：谋定天下 | SLG | `SLG_DEFAULT` | 同上；个别等级/稀有度难分→加 CLASSIFIER |
| 万国觉醒 RoK | SLG | `SLG_DEFAULT` | 图标化地图，模板匹配友好 |
| 无尽的拉格朗日 | SLG-3D | `SLG_DEFAULT`(+按需 DETECTOR) | 先确定性打底，舰队识别实测不行的那类再补检测器 |
| 梦幻西游手游 | MMO | `MMO_DEFAULT` | 价值在流程 + OCR |
| DNF 手游 | 动作 | `ACTION_DEFAULT` | 独立技术栈、独立风险，**单独立项、排最后** |

---

## 四、代码落地（本蓝图已写入实现，名实相符）

| 变更 | 文件 | 作用 |
| :-- | :-- | :-- |
| 新增感知阶梯抽象 | `ai/vision/PerceptionTier.kt` | `enum PerceptionTier` + `data class VisionPolicy`（含 `SLG_DEFAULT/MMO_DEFAULT/ACTION_DEFAULT` + JSON 往返） |
| 知识包声明层级 | `knowledge/GameProfile.kt` | 新增 `visionPolicy`（默认 `SLG_DEFAULT`），`toJson/fromJson` 支持；**旧缓存无该字段→回退 SLG_DEFAULT，绝不误开检测器** |
| 执行闸门 | `runtime/VisionRuntime.kt` | **闸门 0**：`policy` 不含 `DETECTOR` 时 `yolo()` 直接返回 null（权重存在也不加载）；调用方已按 null 优雅降级到确定性 |
| 激活同步 | `knowledge/KnowledgeBaseManager.kt` | 切游戏/热更时经 `setActiveProfile()` 把 `visionPolicy` 同步给 `VisionRuntime.policy` |
| 检测器重定位 | `ai/vision/YoloDetector.kt` | KDoc 明确其为**默认关、可插拔的 DETECTOR 层**；7 类契约保留仅为"真要上检测器时按图训练"，不代表率土依赖 |

> **效果**：率土（`SLG_DEFAULT`）运行时 `VisionRuntime.yolo()` 恒为 null → 全链路走确定性，YOLO 即便有权重也不参与；未来 DNF 在其知识包写 `"vision_policy":["...","DETECTOR","POLICY"]` 即插即用，无需改引擎核。

---

## 五、数据与标注策略（吸取率土 YOLO 教训）

1. **能确定性就不 ML；能分类就不检测**——分类器只要几百张裁剪，绕开"逐个体画框"这个坑。
2. **真到检测器那步也不手标几千框**：用确定性层先自动圈候选区 → 半自动 → **bootstrap 自训练**（`tools/train_yolo/prelabel.py`），且**只训真缺的那一类**。
3. **锁定地图缩放**是 SLG 自动化的前提——尺度一定，模板匹配近乎完美，直接消灭 YOLO 最大的存在理由。

---

## 六、推进波次（"每款都想要"的正确姿势：愿景全要，落地有序）

| 波次 | 内容 | 依赖 |
| :-- | :-- | :-- |
| 1 | **率土确定性核 + 真机验证(Phase E)** | 现在 |
| 2 | 三战 / RoK / 谋定 —— **只加知识包** | 波次 1 引擎核 |
| 3 | 梦幻西游 —— MMO 流程 + OCR | 引擎核复用 |
| 4 | 无尽的拉格朗日 —— 按需插 DETECTOR(小) | 检测器插件接口 |
| 5 | DNF —— 实时检测 + 策略，**单独立项** | 独立预算/风险 |

---

## 七、边界与风险（诚实声明）
- **确定性会丢的**：重度重叠兵团的**精确个体计数**（用色块团 + 数字角标做**态势估计**替代，够用）。
- **维护成本**：美术改版需更新模板/词表（远轻于重训模型）。
- **动作类是另一物种**：实时低延迟 + 强反外挂 + 高频改版，别和 SLG 绑一条 roadmap。
- 本文所述"去 YOLO"仅指**率土/SLG 默认不启用**；检测器作为可插拔模块**保留在代码里**，供动作类按需开启。
