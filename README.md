# 率土全能管家 · 商业旗舰版 (Rate of Land Assistant Pro)

[![Build Android APK](https://github.com/shidaxinyyds/shuaituzhibin/actions/workflows/build_apk.yml/badge.svg)](https://github.com/shidaxinyyds/shuaituzhibin/actions/workflows/build_apk.yml)
[![Version](https://img.shields.io/badge/version-1.0.0--commercial-brightgreen.svg)]()
[![License](https://img.shields.io/badge/auth-Supabase%20Serverless%20%2B%20C%2B%2B%20HMAC-blue.svg)]()

> 基于 Android NDK C++ 与 Kotlin 混合架构深度定制，面向 2025~2026 征服赛季真实游戏机制研发的【率土之滨全场景全自动战术管家】。  
> 融合 **RapidOCR 离线视觉识别**、**Shizuku 底层无痕触控**、**外高斯拟人防封对抗**、**游戏内胶囊 HUD 悬浮交互** 以及 **Supabase Serverless 零维护商业卡密系统**。

---

## 🌟 核心战术矩阵与技术架构

```
┌────────────────────────────────────────────────────────────────────────┐
│                   率土全能管家 · 六大核心架构全景图                     │
└────────────────────────────────────────────────────────────────────────┘
  [阶段一：底层基建] 720p 等比归一化坐标系 ── 双通道触控 (Shizuku + 无障碍)
         │
  [阶段二：视觉感知] RapidOCR 本地离线引擎 ── 敌袭红线雷达 ── 2026士气/体力
         │
  [阶段三：战术动作] 自动铺路翻地 ── 00:00:01极限卡免 ── 攻城集火 ── 决策C反击
         │
  [阶段四：防封风控] 外高斯时间指纹打散 ── 2D双变量微抖 ── 惯性过冲 ── C++巡检
         │
  [阶段五：交互体验] 游戏常驻药丸胶囊 ── 深色HUD控制台 ── 十字准星嗅探取点
         │
  [阶段六：商业闭环] Supabase Edge Functions ── C++ HMAC-SHA256 离线验签
```

### 1. 阶段一：底层跨设备适配与前台捕获
- **720p 等比归一化坐标系 (`CoordinateTransformer`)**：支持全机型（折叠屏、平板、各类比例手机）像素无损映射，虚拟画布永远锚定 1280x720，双向无缝换算。
- **双通道触控支持 (`AutoTouchService` + `ShizukuTouchManager`)**：
  - 普通模式：无障碍手势自动化派发。
  - 隐身模式：通过 Shizuku 获得底层 ADB shell 权限，游戏完全检索不到任何系统无障碍服务开启特征。
- **Android 14+ 兼容的前台流捕获 (`ScreenCaptureService`)**：MediaProjection 前台常驻采集，直通 NDK 内存，帧率稳定且低功耗。

### 2. 阶段二：视觉感知与 2026 核心特征库
- **纯本地 RapidOCR 毫秒级推理 (`OcrManager`)**：无模板 PNG 依赖，纯语义文字提取（识别体力 `/120`、大地图坐标、出征按键），彻底免疫网易游戏图标风格改版。
- **深夜敌袭红线雷达与源头回溯 (`RaidRadarDetector`)**：HSV 动态色彩过滤屏幕边缘红光警报与大地图行军红线，毫秒级反向测算敌军进攻源头（跳板要塞与链接地），赋能决策 C 自动反击。
- **免战金色倒计时监测 (`TileStatusDetector`)**：金罩微弧边缘提取与 `00:xx:xx` 破免时间戳绝对换算。
- **2026 征服赛季士气与攻城测算 (`TroopStatusDetector`)**：支持 120 满士气与体力判定，守军阵容智能打分，攻城部队行军耗时抓取。

### 3. 阶段三：四大王牌战术执行流
- **自动多部队轮换铺路流 (`RoadPavingFlow`)**：多队伍轮班交替、防体力溢出休整、遇敌撤退避险。
- **00:00:01 准点触敌极限卡免流 (`ImmunityBreakFlow`)**：毫秒级时钟对齐，精准压在免战结束瞬间触敌翻地，或提前派兵驻守接力卡免。
- **同盟集火攻城倒排卡秒流 (`SiegeSyncFlow`)**：针对拆迁队（慢速）与主力主力（高速骑兵）耗时差异，自动倒排发兵时序，确保所有部队在同一秒集火触城。
- **深夜敌袭高保真巡检与【决策 C 自动反击】(`RaidDefenseFlow`)**：
  - 声光震动刺耳警报唤醒熟睡玩家；
  - **决策 C 核心**：自动调遣精锐部队直捣敌军进攻跳板要塞与前沿链接地，拆毁敌方行军通道！
- **看门狗自愈恢复机 (`WatchdogRecovery`)**：自动识别并关闭升级弹窗、邮件提醒、活动推窗，无论外界干扰如何均能平稳重返主地图。

### 4. 阶段四：商业级防封与拟人化风控对抗
- **外高斯 (Ex-Gaussian) 长尾时间指纹 (`TimingFingerprintEngine`)**：彻底消灭固定延时与机械节奏，完美拟合真实人类反应时间分布；支持昼夜节律（夜间自动降低操作频次与反应速度）和连续作业生理疲劳微歇。
- **动力学触控微抖与惯性过冲 (`KineticTouchEngine`)**：
  - 2D 双变量高斯离散点击位置；
  - 模拟指腹肉垫接触时长（70ms ~ 175ms）；
  - 贝塞尔惯性过冲滑动：滑过目标点 15~35px 并伴随物理微回弹刹车，消灭生硬直线手势。
- **客户端反嗅探环境与 C++ 原生审计 (`StealthEnvironmentManager` + `SecurityBridge.cpp`)**：
  - 无障碍服务零暴露检测；
  - 原生 C++ 检查 `TracerPid` 动态调试；
  - 检查 `/proc/self/maps` 注入库（Frida / Xposed / Substrate）；
  - 本地 TCP 27042 调试端口连通探测。

### 5. 阶段五：游戏常驻悬浮 UI 与极致交互
- **极简迷你药丸胶囊 (`view_floating_capsule.xml`)**：仅占屏幕 0.5% 边缘区域，手指任意拖动，松手平滑自动吸附边缘；实时变色反馈战术状态（绿就绪/蓝执行/黄卡秒/红敌袭）。
- **展开式战术 HUD 控制台 (`view_floating_dashboard.xml`)**：5 分页标签式面板，游戏画面内直接配置执行铺路、卡免、攻城、巡检与实时战术流水日志。
- **十字准星全屏地块坐标嗅探取点 (`view_crosshair_picker.xml`)**：直接在游戏画面上点选地块，系统毫秒级抓取并换算为虚拟坐标，告别手工输 X/Y 坐标。

### 6. 阶段六：商业化鉴权与 Supabase 云端生态
- **全功能旗舰版计费模型**：不划分阉割功能版，全功能全开，统一按时间周期（周卡 / 月卡 / 季卡 / 赛季卡）计费。
- **零自建服务器成本**：Supabase Serverless + PostgreSQL + Edge Functions，高可用抗高并发。
- **纯原生 C++ HMAC-SHA256 离线强验签**：私钥不留在 Java 层，激活后本地离线缓存凭证，断网脱机也能正常运行，单次验签耗时 `< 1ms`。
- **设备硬件指纹绑定与时钟防回拨熔断**：一机一码锁定，系统时钟篡改主动熔断防破解。

### 7. 模块化进阶：跨游戏独立知识库与云端静默热更体系 (`KnowledgeBaseManager`)
- **通用引擎与游戏规则解耦**：触控、防封、720p 归一化、悬浮窗、感知与决策链路是通用底座，各游戏规则独立封装为 JSON 与实体 Profile。
  唯一的例外是授权链路（`LicenseManager` 的 `game_id` 仍写死率土），所以"通用"目前指的是**玩法链路不换技术**，不是整包无例外。
- **内置官方级知识库**（**唯一权威是 Kotlin 内置库**，`pipeline/*.json` 只是它的导出产物）：
  - **《率土之滨 · 2026征服赛季旗舰版》(`stzb`)**：120 士气/体力，Lv.3~Lv.8 开荒天梯打分与克制避让，19 组抗版本迭代语义按键，看门狗弹窗过滤。
  - **《三国志·战略版 · PK赛季旗舰版》(`sgz`)**：100 士气，夜战双倍消耗，占领/调动/筑城按键，守军兵种克制。
  - **只发云端、APK 里没有内置档案的游戏也能被激活并列进切换对话框**（本机标定/模板/语料按 `gameId` 自动分域并随切游戏重载）。
    想看清"加一款游戏到底还要人工补什么"（模板图、YOLO 权重、RAG 语料、授权改造）：
    `python tools/check_multi_game_readiness.py --show-checklist`。
- **云端零发版静默热更**：游戏官方更新数值或按键时，**先改内置库、再导出、再发布**：

  ```bash
  python tools/export_profile.py                 # 内置库 → pipeline/*.json（产物不可手工编辑）
  python tools/check_knowledge_base.py           # 三源对账：内置库 / RAG 表 / 云端产物
  python tools/upload_profile.py --json pipeline/rate_of_land.json   # 发布到 Supabase
  ```

  全网客户端自动拉取热更新，彻底告别频繁重新发版打包 APK！
  > ⚠️ **绝不手改 `pipeline/*.json`**：`run_all_checks.py` 里的 `export-freshness` 是硬闸门，
  > 产物与内置库不一致时直接失败——因为“版本号相同、内容变了”的云端配置永远不会被
  > 客户端采纳（端上只接受严格更新的版本），而你从日志上看不出任何异常。

---

## 🛠️ 项目目录结构

```
shuaituzhibin/
├── .github/
│   └── workflows/
│       └── build_apk.yml             # GitHub Actions 自动化编译 APK 脚本
├── client/                           # Android 客户端主工程 (Kotlin + C++ NDK)
│   ├── app/
│   │   ├── src/main/
│   │   │   ├── cpp/                  # C++ NDK 核心 (SecurityBridge.cpp, RapidOCR)
│   │   │   ├── java/com/stzb/assistant/
│   │   │   │   ├── antiban/          # 阶段四：防封风控与动力学触控
│   │   │   │   ├── knowledge/        # 跨游戏知识库、Profile与云端热更管理
│   │   │   │   ├── license/          # 阶段六：卡密激活与离线验签
│   │   │   │   ├── ocr/              # 阶段二：RapidOCR与率土特征识别
│   │   │   │   ├── overlay/          # 阶段五：悬浮窗胶囊与HUD控制台
│   │   │   │   ├── service/          # 阶段一：720p映射与双通道触控
│   │   │   │   ├── tactics/          # 阶段三：战术流与决策C反击
│   │   │   │   └── ui/               # 主界面与自检中心
│   │   │   └── res/layout/           # 悬浮窗与主界面 XML
│   │   └── build.gradle
│   ├── build.gradle
│   └── settings.gradle
├── backend/
│   └── supabase/
│       ├── migrations/
│       │   └── 0001_multi_game_license.sql # 数据库建表、知识库热更表与存储过程
│       └── functions/
│           └── license/index.ts            # Supabase Edge Functions 无服务接口 (鉴权+知识库热更)
├── pipeline/
│   ├── rate_of_land.json             # 率土之滨云端知识库产物（由 export_profile.py 导出）
│   └── three_kingdoms_profile.json   # 三国志·战略版云端知识库产物（同上）
├── tools/
│   ├── gen_cards.py                  # 商业卡密批量生成工具 (零知识安全)
│   ├── keep_alive.py                 # Supabase 免费实例自动保活脚本
│   ├── export_profile.py             # 内置知识库 → 云端产物导出器（--check 为新鲜度闸门）
│   ├── validate_decision_logic.py    # 决策规则镜像 + 源码反照（守军评级/选队/铺路逐格推进）
│   ├── check_knowledge_base.py       # 知识库一致性与字段接线闸门（三源对账 + 死配置）
│   ├── run_all_checks.py             # 一键静态回归（共 37 项，上述闸门全部在内）
│   ├── ...                           # 其余校验脚本均由 run_all_checks.py 调用
│   └── upload_profile.py             # 知识库云端热更发布工具（缺字段/陈旧/版本倒退一律拒发）
├── docs/
│   └── 00_COMMERCIAL_ROADMAP.md      # 六阶段全流程落地规划文档
├── .gitignore
└── README.md
```

---

## 🚀 编译与打包构建

### 方式一：GitHub Actions 自动构建 (推荐)
本项目已预置全自动 CI/CD 流程：
1. 推送代码至本仓库后，GitHub Actions 将自动触发。
2. 自动配置 JDK 17、Android SDK、NDK 与 CMake，执行 `./gradlew assembleRelease`。
3. 编译完成后，前往仓库的 **Actions** -> **Artifacts** 即可直接下载生成的 Release/Debug APK！
4. 推送带版本号的 git tag（如 `v1.0.0`）会自动发布 GitHub Release 并在 Release 页面挂载 APK 供用户下载。

### 方式二：本地 Android Studio 编译
1. 使用 **Android Studio Iguana / Jellyfish** 打开 `client` 目录。
2. 配置好 NDK（版本建议 25.x 或更高）与 CMake 3.22+。
3. 同步 Gradle 并运行 `assembleRelease` 即可生成 APK。

---

## 💳 商家卡密生成与 Supabase 后端部署

### 1. 数据库与云端函数部署
1. 前往 [Supabase 官网](https://supabase.com) 创建免费项目。
2. 在 **SQL Editor** 中执行 `backend/supabase/migrations/0001_multi_game_license.sql` 完成数据表创建。
3. 部署 Edge Function：
   ```bash
   cd backend/supabase/functions/license
   supabase functions deploy license --no-verify-jwt
   ```
4. 在 Supabase 后台设置环境变量 `LICENSE_TOKEN_SECRET`（与 C++ 层密钥一致）。

### 2. 批量生成卡密并导入发卡网
使用内置的零知识卡密生成脚本：
```bash
# 生成 50 张率土之滨 30 天月卡
python tools/gen_cards.py --game stzb --type month --days 30 --count 50 --prefix STZB

# 生成 10 张 7 天周卡
python tools/gen_cards.py --game stzb --type week --days 7 --count 10 --prefix STZB

# 生成 5 张 15 分钟体验卡 (用于测试)
python tools/gen_cards.py --game stzb --type trial --minutes 15 --count 5 --prefix TEST
```
- 控制台将输出明文卡密（可直接导入发卡网供买家购买）。
- 底部同时自动生成对应的 SQL 批量导入语句，直接粘贴到 Supabase SQL Editor 执行即可上架库存！

---

## ⚖️ 免责声明
本项目仅供移动端自动化技术交流与计算机视觉算法研究使用，严禁用于任何破坏游戏公平性或违反相关法律法规的非法商业盈利活动。使用者需自行承担因违规操作产生的全部后果与法律责任。
