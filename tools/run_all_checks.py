#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
一键回归：把项目所有静态校验器串成一条命令
==========================================

背景
----
本机没有 JDK / Android SDK / NDK，也无法访问外网，因此 `./gradlew` 跑不起来。
作为替代，这个会话里逐步积累了若干**静态校验器与实测脚本**：

  1. `verify_refs.py`              资源/成员/findViewById/领域不变量 一致性
  2. `verify_refs.py --selftest`   上述规则的反例自测
  3. `check_native_ocr_build.py`   native OCR 构建的前置条件（include/链接库/JNI 签名）
  4. `selftest_native_ocr_check.py` 上述校验器的反例自测（注入已知缺陷）
  5. `check_ci_shell.py`           workflow 的 shell 结构
  6. `selftest_check_ci_shell.py`  上述校验器的反例自测
  7. `selftest_license_token.py`   授权凭证判别逻辑自测
  8. `validate_scene_fingerprint.py` 场景指纹算法在真机截图上的实测（需要截图）
 8b. `gen_button_seeds.py`  按键模板种子的三重验收：自检 / 跨帧迁移 / 不误命中（需要截图）
  9. `tools/p1/check_assets.py`    入包资产体检：体积预算 / RAG 索引契约 / 假权重（离线纯 stdlib）
 10. `tools/p1/check_bases.py`     模型权重下载清单核验（默认离线只读；`--download` 才联网）
 11. `check_undeclared_receivers.py` 接收者标识符声明对账（拦 `Unresolved reference: scope` 这类）
 12. `check_ctor_named_args.py`    具名构造参数对账（拦 `No parameter with name` 这类）
 13. `check_kotlin_api_pitfalls.py` 把「只有真编译才暴露」的第三方 API 误用（ORT getEnv/shape-Int/Result.close、Pattern 当 Regex、TextBlock.box）固化成本机闸门
 14. `ocr_regression/check_ocr_asset_contract.py` OCR rec 输出类别数 ↔ 词典行数 配套契约（拦「v5 权重 + v3 词典」这类能一路绿灯入包、上机才变乱码的半套资产）
 15. `ocr_regression/decision_gate.py --selftest` P2 迁移判定闸门的阈值自测（其真实裁决 STAY_V3 属期望态，故不作为必需项）
 16. `validate_antiban_math.py`         P3 拟人数学镜像：从 Kotlin 源码正则取常数，逐式验证速度剖面/有界延迟/泊松间隔的分布性质
 17. `p3/check_antiban_wiring.py`       P3 接线契约：拟人能力是否真的接在调用链上（拦「代码写对但没人调」这类静默失效）
 18. `p3/check_antiban_wiring.py --selftest` 上述闸门的反例自测（注入 9 条已知回退形态）
 19. `ci_status.py --selftest`      CI 速查工具的判定自测（**cancelled / 根本没跑起 / 真失败** 必须分开；把 Run #28 的误读固化成规则）
 20. `check_knowledge_base.py`      知识库一致性与接线闸门（三套权威对账 / 幽灵武将 / 死配置 / 云端产物漂移）
 21. `check_knowledge_base.py --selftest` 上述闸门的反例自测（每类缺陷注入一条，正例不许误报）
 22. `export_profile.py --check`      云端产物新鲜度（pipeline/*.json 必须由内置库导出，拦手工漂移）
 23. `validate_template_matcher.py`   模板匹配在真机截图上的实测（ZNCC 得分/正负例可分性，需要截图）
 24. `upload_profile.py --selftest`   发布闸门的反例自测（字段齐全/产物新鲜/版本单调，拦“发了但永远不生效”）
 25. `rag_bench.py --contract`        RAG 检索通道契约（通道 ↔ 真实 .bin 的 category ↔ 调用点三方对账，拦“整层检索空转”）
 26. `rag_bench.py --selftest`        上述闸门 + 迁移阈值的反例自测（含“解析器自己瞎了不许报绿”）
 27. `rag_bench.py`                   P5 迁移裁决 STAY/MIGRATE（参考项：正常输出就是维持现状，不做构建门槛）
 28. `check_multi_game_readiness.py`  多游戏就绪闸门（游戏 id 字面量必须登记 / 权重文件名唯一权威 /
                                      分域数据必须注册切游戏重载 / 资产目录走候选 / 纯云端激活硬闸）
 29. `check_multi_game_readiness.py --selftest` 上述闸门的反例自测（干净夹具不许误报、9 条回退形态
                                      各自必须被抓到、例外登记表自己失效必须被抓到）
 30. `check_kotlin_braces.py`         Kotlin/Java 括号与字面量配平（字符串/注释/模板都分得清，
                                      拦"少一个 } / 引号没闭合"这类本机唯一能抓的编译期缺陷；
                                      **执行顺序上排在所有语义闸门之前**）
 31. `check_kotlin_braces.py --selftest` 上述闸门的正例/反例自测（7 个易误伤正例 + 10 条结构缺陷）

> 28 / 29 是一组：P7 要回答的问题不是"多游戏功能好不好用"，而是**对外喊的
> "加一款游戏零代码改动"到底成不成立**。它成立的前提是四条结构性不变量一直成立；
> 这四条被改回去时 App 不报错、不降级，只表现为"换了游戏但识别还是上一款"。
> 想看清还剩哪些**必须人工补**的例外：`python tools/check_multi_game_readiness.py --show-checklist`。

> 20 / 21 / 22 是一组：内置 Kotlin 知识库是**唯一权威**，pipeline/*.json 只是它的
> 派生产物。20 保证两边语义对齐，22 保证产物确实是导出来的（而不是有人手改了一个数）。

> 11 / 12 是补上 `verify_refs.py` 的射程盲区：它只对账**枚举常量**与**整对象成员**
> （`ButtonType.X` / `EngineBridge.x`），查不到 `局部变量.属性`、`this 成员`
> 与**具名实参**。真实教训有两个：
>   * `OverlayWindowManager.kt` 里有 3 处 `scope.launch`，而 `scope` 从未声明；
>   * 3 处把 `ClickOutcome` 当 `ClickAndExpect` 用、写了不存在的 `.ok`。
> 这两类都让工程**编不出 APK**，却在当时的静态闸门下一路绿灯。

散着敲这些命令既容易漏，也没法作为"可交付的验证入口"。本脚本把它们串起来，
并可直接在 CI 里跑（新增的 `.github/workflows/verify.yml` 就是这么用的）。

设计要点
--------
* **不启子进程**：全部用 importlib 在当前进程内调用各校验器的 `main()`，
  避免管道相关的平台限制，也便于统一捕获输出。
* **截图类检查优雅跳过**：真机截图不可能进仓库，因此 `validate_scene_fingerprint`
  在没有提供截图时**跳过并说明**，而不是失败。
  通过 `--fingerprint-map` 或环境变量 `STZB_FP_MAP` / `STZB_FP_VARIANT` / `STZB_FP_OTHER` 提供；
  后两者可用 `;` 分隔给多张（变体越多，阈值宽容度这件事才越接近被证实）。
  加 `--strict` 可让"缺截图"也算失败。

用法
----
    python tools/run_all_checks.py
    python tools/run_all_checks.py --strict
    python tools/run_all_checks.py --fingerprint-map D:\\shots\\map.jpg --fingerprint-other D:\\shots\\app.jpg
    python tools/run_all_checks.py --only verify_refs check_ci_shell

退出码：0 = 全部必需检查通过；1 = 有必需检查失败。
"""

import argparse
import contextlib
import importlib.util
import io
import json
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)


def _checker(name):
    return os.path.join(HERE, name)


def run_checker_inprocess(name, argv):
    """在当前进程内加载并调用校验器的 main()，返回 (rc, 输出)。"""
    path = _checker(name)
    if not os.path.isfile(path):
        return 3, f"找不到校验器: {path}"

    module_name = "run_all_checks__" + name.replace(".", "_")
    spec = importlib.util.spec_from_file_location(module_name, path)
    mod = importlib.util.module_from_spec(spec)
    try:
        spec.loader.exec_module(mod)  # 先把模块跑起来（定义 main）
    except BaseException as e:
        # ⚠️ 必须是 BaseException 而不是 Exception。
        #
        # `check_ci_shell.py` 在**模块顶层**做 `except ImportError: sys.exit(2)`，
        # 而 `SystemExit` 继承自 BaseException、**不是** Exception。
        # 原先只捕获 Exception，于是缺 PyYAML 时这个 SystemExit 会一路穿出
        # 本函数与主循环，把整条回归**在第 5 项就掐断**——
        # 表现是"跑到一半就停"，极容易被误读成"后面都过了"（假绿）。
        # 现在它只会让这一项自己失败，其余检查照跑。
        return 3, f"[加载失败] {type(e).__name__}: {e}"

    buf = io.StringIO()
    old_argv = sys.argv
    sys.argv = [path] + list(argv)
    rc = 0
    try:
        with contextlib.redirect_stdout(buf):
            rc = mod.main()
    except SystemExit as e:
        rc = e.code if isinstance(e.code, int) else 1
    except Exception as e:
        rc = 3
        buf.write(f"\n[执行异常] {type(e).__name__}: {e}\n")
    finally:
        sys.argv = old_argv
    return (rc or 0), buf.getvalue()


def _has_any_base():
    """.models/ 下是否已有任何基座文件（决定 model-bases 该不该真跑）。"""
    root = os.path.join(REPO, ".models")
    if not os.path.isdir(root):
        return False
    for dirpath, _dirs, files in os.walk(root):
        if any(f for f in files if not f.startswith(".")):
            return True
    return False


def build_checks(args):
    checks = [
        {
            # 结构配平排在所有语义闸门之前：本机没有 JDK，编不出 APK 的那类缺陷
            # （少一个 }、括号错配、引号没闭合）只能靠这里挡。
            # 语义校验器在一个结构已经塌了的文件上只会给出噪声结论。
            "id": "kotlin-braces",
            "title": "Kotlin/Java 括号与字面量配平（字符串/注释/模板都分得清）",
            "script": "check_kotlin_braces.py",
            "argv": ["--root", os.path.join("client", "app", "src", "main")],
            "required": True,
        },
        {
            # 判据自测：7 个"看起来会误伤"的正例不许误报（字符串里的 }、注释里的括号、
            # 原始字符串里的引号、模板里的 lambda……），10 条结构缺陷必须各自被抓到。
            "id": "kotlin-braces-selftest",
            "title": "结构配平闸门的反例自测",
            "script": "check_kotlin_braces.py",
            "argv": ["--selftest"],
            "required": True,
        },
        {
            "id": "refs",
            "title": "资源/成员/findViewById/领域不变量 一致性",
            "script": "verify_refs.py",
            "argv": ["--root", os.path.join("client", "app", "src", "main")],
            "required": True,
        },
        {
            "id": "refs-selftest",
            "title": "领域不变量规则的反例自测",
            "script": "verify_refs.py",
            "argv": ["--selftest"],
            "required": True,
        },
        {
            "id": "native-ocr",
            "title": "native OCR 构建前置条件",
            "script": "check_native_ocr_build.py",
            "argv": ["--root", os.path.join("client", "app", "src", "main")],
            "required": True,
        },
        {
            "id": "native-ocr-selftest",
            "title": "native 校验器的反例自测（注入缺陷）",
            "script": "selftest_native_ocr_check.py",
            "argv": [],
            "required": True,
        },
        {
            "id": "undeclared-receivers",
            "title": "接收者标识符声明对账（拦 Unresolved reference）",
            "script": "check_undeclared_receivers.py",
            "argv": ["--root", os.path.join("client", "app", "src", "main")],
            "required": True,
        },
        {
            "id": "ctor-named-args",
            "title": "具名构造参数对账（拦 No parameter with name）",
            "script": "check_ctor_named_args.py",
            "argv": ["--root", os.path.join("client", "app", "src", "main")],
            "required": True,
        },
        {
            "id": "kotlin-api-pitfalls",
            "title": "Kotlin 第三方 API 误用闸门（ORT/Regex/TextBlock 编译期坑）",
            "script": "check_kotlin_api_pitfalls.py",
            "argv": ["--root", os.path.join("client", "app", "src", "main")],
            "required": True,
        },
        {
            "id": "kotlin-api-pitfalls-selftest",
            "title": "API 误用闸门的反例自测",
            "script": "check_kotlin_api_pitfalls.py",
            "argv": ["--selftest"],
            "required": True,
        },
        {
            # 注意：这里刻意**不加 --strict**。
            # 付费墙开发模式是当前有意保留的开发态（后端未部署前翻成 false 会自锁），
            # 若让它硬失败，CI 会长期飘红，而长期飘红的唯一后果是"所有人开始忽略它"。
            # 故此检查默认只报告；发布前手动 `python tools/check_release_readiness.py --strict`。
            "id": "release-readiness",
            "title": "发布就绪自检（付费墙/权限/崩溃兜底/资产）",
            "script": "check_release_readiness.py",
            "argv": [],
            "required": True,
        },
        {
            "id": "ci-shell",
            "title": "CI shell 结构",
            "script": "check_ci_shell.py",
            "argv": [],
            "required": True,
        },
        {
            "id": "ci-shell-selftest",
            "title": "CI shell 校验器的反例自测",
            "script": "selftest_check_ci_shell.py",
            "argv": [],
            "required": True,
        },
        {
            "id": "license-token",
            "title": "授权凭证判别逻辑自测",
            "script": "selftest_license_token.py",
            "argv": [],
            "required": True,
        },
        {
            "id": "coord-math",
            "title": "坐标变换数学验证（画布等比/标定还原/往返一致）",
            "script": "validate_coordinate_math.py",
            "argv": [],
            "required": True,
        },
        {
            "id": "timing-math",
            "title": "压秒时间数学验证（公式恒等式/结构性迟到/阈值自洽）",
            "script": "validate_timing_math.py",
            "argv": [],
            "required": True,
        },
        {
            "id": "antiban-math",
            "title": "防封拟人数学实测（速度剖面/有界延迟不贴边/泊松去周期性）",
            "script": "validate_antiban_math.py",
            "argv": [],
            "required": True,
        },
        {
            # 数学性质成立 ≠ 产品生效。本仓库真实栽过：`BezierTrajectory.easeInOut`
            # 写得完全正确，但**没有任何调用者**，于是速度剖面从来没生效过。
            # 这一对闸门守的就是「能力有没有接在链路上」与「跨文件数值不变量」。
            "id": "antiban-wiring",
            "title": "P3 防封能力接线契约（拦「写了不接」与固定周期回退）",
            "script": "p3/check_antiban_wiring.py",
            "argv": [],
            "required": True,
        },
        {
            "id": "antiban-wiring-selftest",
            "title": "接线闸门的反例自测（注入 9 条已知回退形态）",
            "script": "p3/check_antiban_wiring.py",
            "argv": ["--selftest"],
            "required": True,
        },
        {
            "id": "decision-logic",
            "title": "决策逻辑验证（守军评级性质 + 选队门槛，枚举输入空间）",
            "script": "validate_decision_logic.py",
            "argv": [],
            "required": True,
        },
        {
            "id": "watchdog-logic",
            "title": "看门狗自愈验证（状态空间枚举 + 弹窗按键顺序）",
            "script": "validate_watchdog_logic.py",
            "argv": [],
            "required": True,
        },
        {
            "id": "sentinel-logic",
            "title": "暗夜哨兵验证（主城2格警戒圈/60s秒回/防掉线微保活）",
            "script": "validate_sentinel_logic.py",
            "argv": [],
            "required": True,
        },
        {
            "id": "siege-sync",
            "title": "双压秒攻城验证（邮件法令解析/主力0s/拆迁+5s/30min自愈调动）",
            "script": "validate_siege_sync.py",
            "argv": [],
            "required": True,
        },
        {
            "id": "schedule-manager",
            "title": "离线战术定时验证（书签0漂移对准/挂牌出征/OCR读倒计时一键压秒破免）",
            "script": "validate_schedule_manager.py",
            "argv": [],
            "required": True,
        },
        {
            "id": "logistics-farming",
            "title": "日常后勤与屯田打铁验证（主城税收/伤兵补兵/体力防溢/最高级地屯田/3令防溢出/工坊打铁）",
            "script": "validate_logistics_farming.py",
            "argv": [],
            "required": True,
        },
        {
            "id": "garrison-leveling",
            "title": "驻守透视与练级验证（PVP 100兵剥皮透视/流派克制/PVE软柿子雷达/15%战损硬熔断/双队轮换）",
            "script": "validate_garrison_leveling.py",
            "argv": [],
            "required": True,
        },
        {
            "id": "asset-budget",
            "title": "入包资产体检（体积预算 / RAG 索引契约 / 假权重）",
            "script": "p1/check_assets.py",
            "argv": [],
            "required": True,
        },
        {
            # P2 OCR 迁移的两道静态闸门。
            #
            # 刻意**只注册契约校验与自测，不注册 decision_gate 的真实裁决**：
            # 闸门的正常输出就是 `STAY_V3`（退出码 1）——那是**期望状态**，
            # 不是构建失败。把它当必需项会让 CI 长期飘红，而长期飘红的唯一后果
            # 是所有人开始忽略它（本仓库已在别处栽过这个跟头）。
            # 需要裁决时手动跑：python tools/ocr_regression/decision_gate.py
            "id": "ocr-contract",
            "title": "OCR 权重↔词典配套契约（拦半套权重上机）",
            "script": "ocr_regression/check_ocr_asset_contract.py",
            "argv": [],
            "required": True,
        },
        {
            "id": "ocr-contract-selftest",
            "title": "OCR 配套契约闸门的反例自测（注入半套/破损词典）",
            "script": "ocr_regression/check_ocr_asset_contract.py",
            "argv": ["--selftest"],
            "required": True,
        },
        {
            "id": "ocr-gate-selftest",
            "title": "P2 迁移判定闸门的阈值翻转自测（合成跑分）",
            "script": "ocr_regression/decision_gate.py",
            "argv": ["--selftest"],
            "required": True,
        },
        {
            "id": "model-bases",
            "title": "模型权重下载清单核验（缺失只警告，严格模式下算失败）",
            "script": "p1/check_bases.py",
            "argv": [],
            "required": False,
            # 只有 .models/ 里一个基座文件都没有时才跳过；
            # 否则必须真跑校验器，否则「已下载」会被永远跳成 SKIP，
            # 权重坏了也照样绿灯（与之前踩过的假成功同一类问题）。
            "skip_reason": None if _has_any_base() else (
                "基座权重未下载（离线环境或尚未执行下载）；"
                "下载方补齐 .models/ 后用 python tools/p1/check_bases.py 复核"),
        },
        {
            "id": "acceptance-selftest",
            "title": "验收取证脚本的 APK 判定逻辑自测（合成 APK）",
            "script": "selftest_acceptance_check.py",
            "argv": [],
            "required": True,
        },
        {
            # 本机跑不了 Gradle ⇒ CI 是唯一真正的编译器。但“CI 红了”有
            # 两种完全相反的含义：检查没过（要改代码） vs 排队被撤（只需重触发）。
            # Run #28 就是后者被误读成前者的真实案例，所以这条归因逻辑进回归。
            # （只跑 --selftest：它不联网，用注入的 job 样本验归因规则。）
            "id": "ci-status-selftest",
            "title": "CI 状态归因自测（cancelled / 未执行 / 真失败 不混淆）",
            "script": "ci_status.py",
            "argv": ["--selftest"],
            "required": True,
        },
        {
            # 知识库闸门：内置库 ↔ RAG 私有硬编码表 ↔ pipeline/*.json 三方对账。
            #
            # 以前 required=False，是因为它报出的每一条都是待修清单（缺按键词条 /
            # 幽灵武将 / 兵力两套值 / 死配置）——一次性翻红只会让回归长期飘红，
            # 而飘红的唯一后果是没人再看它。
            # 现在待修清单已逐条清零（8 项死配置全部接线或删除），故改为必需项：
            # 任何一条重新出现都必须挡住构建，而不是沦为又一条被忽略的 WARN。
            "id": "knowledge-base",
            "title": "知识库一致性与字段接线闸门（三源对账 + 死配置）",
            "script": "check_knowledge_base.py",
            "argv": [],
            "required": True,
        },
        {
            # 内置库改完忘记重导，云端产物就落后于代码——而客户端采纳云端库的
            # 判据是「版本号严格更新」，于是真机跑的仍是旧语义，且毫无报错。
            # 这条闸门只做一件事：产物必须能被导出器原样复现，落后即失败。
            "id": "export-freshness",
            "title": "云端产物新鲜度（pipeline/*.json 必须由内置库导出）",
            "script": "export_profile.py",
            "argv": ["--check"],
            "required": True,
        },
        {
            # P5 取证实物：RAG 的检索通道契约。
            #
            # 这条是补上一个**真实存在过的静默失效**：调用侧查 DEFENDER_LAND/SKILL_SYNERGY/
            # TACTICAL_DECREE，而入包的 V2 资产用的是 DEFENDER_SAFE/MODERATE/HARD/AVOID +
            # LAND_SIEGE，两边一个都不重合 ⇒ 每条生产检索都返回空列表。
            # 它不报错、不降级，看起来"军师偶尔没参考条目"，实则整层 RAG 空转。
            # 本项拿真实 .bin 的 category 集合与 Kotlin 里的通道表、调用点、种子库三方对账。
            "id": "rag-channel-contract",
            "title": "RAG 检索通道契约（通道 ↔ 资产 category ↔ 调用点三方对账）",
            "script": "rag_bench.py",
            "argv": ["--contract"],
            "required": True,
        },
        {
            # 上面那条的判据自测：阈值、文档同源、通道契约三类缺陷各自必须被抓到，
            # 同时证明解析器不是瞎的（解析不到通道表/调用点时不许报绿）。
            "id": "rag-bench-selftest",
            "title": "RAG 阈值与通道契约闸门的反例自测",
            "script": "rag_bench.py",
            "argv": ["--selftest"],
            "required": True,
        },
        {
            # P5 的**裁决**（STAY / MIGRATE）刻意不进必需项：
            # 它的一次正常输出就是"维持现状"，而越线时该做的是人工决策 + 真机复测，
            # 不是把构建挡死（与 ocr-gate 同一处置理由）。
            # 需要复跑评估：python tools/rag_bench.py
            "id": "rag-migration-verdict",
            "title": "RAG 检索成本实测与迁移阈值裁决（参考项，不做构建门槛）",
            "script": "rag_bench.py",
            "argv": [],
            "required": False,
            "skip_reason": None,
        },
        {
            # 发布闸门只在人手动发布时才跑，所以它坏了不会有人知道——而它拦的是
            # “配置已上云但全网永远不采纳”这类完全静默的事故。
            # 自测不写仓库文件（只读产物、台账全在内存里构造），因此可以作为必需项。
            "id": "publish-gate-selftest",
            "title": "知识库发布闸门的反例自测（字段/新鲜度/版本单调）",
            "script": "upload_profile.py",
            "argv": ["--selftest"],
            "required": True,
        },
        {
            # 自测不读文件系统，必须常绿：规则本身坏了，闸门就成了摆设。
            "id": "knowledge-base-selftest",
            "title": "知识库闸门的反例自测（每类缺陷注入一条、正例不许误报）",
            "script": "check_knowledge_base.py",
            "argv": ["--selftest"],
            "required": True,
        },
        {
            # RAG 语料曾是知识包的手抄副本：知识包加了将、语料没跟上，
            # 端侧只是"检索不到那个守将"，而所有闸门照样全绿（实测少 3 条）。
            # 这条做双向对账：语料不许编造、知识包不许有漏抄的锚点、
            # category 必须落在端侧 SEARCH_CHANNELS 的源码解析结果里、
            # 产物 .bin 必须与这份语料同步（条数 + 标题字节都要能找回）。
            "id": "rag-provenance",
            "title": "RAG 语料溯源（语料↔知识包双向对账 + 资产同步）",
            "script": "validate_rag_corpus_provenance.py",
            "argv": [],
            "required": True,
        },
        {
            # 溯源校验本身也会坏：它一度把整条拼成一个大串做子串判断，
            # 于是"标题带着 120、正文被改成 999"照样绿。
            # 这个自测拿真实语料做 6 种突变，逐个断言同一个校验函数变红。
            "id": "rag-provenance-selftest",
            "title": "RAG 溯源闸门的反例自测（6 种突变必须全部拦下）",
            "script": "validate_rag_corpus_provenance.py",
            "argv": ["--selftest"],
            "required": True,
        },
        {
            # P7 的承重墙：对外喊"加一款游戏零代码改动"，靠的是四条不变量不被悄悄改回去——
            #   ① 纯云端知识包必须能激活（不能被 builtInProfiles 缺键的硬闸挡死）
            #   ② 权重文件名只能有一处权威（否则"体检判就绪 / 引擎加载另一套"）
            #   ③ 按 gameId 分域落盘的标定必须注册切游戏重载钩子
            #   ④ 游戏专属资产目录必须走 assetDirs 候选
            # 这四条坏掉时 App 不会报错，只会表现为"换了游戏但识别还是上一款"，
            # 属于典型的静默失效，因此列为必需项。
            "id": "multi-game-readiness",
            "title": "多游戏就绪闸门（游戏 id 登记 / 命名单一权威 / 分域重载 / 结构不变量）",
            "script": "check_multi_game_readiness.py",
            "argv": [],
            "required": True,
        },
        {
            # 上面那条的判据自测：干净夹具不许误报，9 条已知回退形态各自必须被抓到，
            # 例外登记表自己被改动或删除时也必须被抓到（没人核对的白名单会变成空档）。
            "id": "multi-game-readiness-selftest",
            "title": "多游戏就绪闸门的反例自测",
            "script": "check_multi_game_readiness.py",
            "argv": ["--selftest"],
            "required": True,
        },
    ]

    def env_list(name):
        """环境变量允许用 `;` 分隔给多张截图。

        为什么支持多条：这套判据的意义在于"多个真实变体都能命中、多种别的界面都不命中"，
        单张 variant 只能证明自相似，证不了阈值宽容度。Windows 路径里不会出现 `;`，
        所以这个分隔符是安全的。
        """
        raw = os.environ.get(name) or ""
        return [p.strip() for p in raw.split(";") if p.strip()]

    fp_map = args.fingerprint_map or os.environ.get("STZB_FP_MAP")
    fp_variant = list(args.fingerprint_variant) + env_list("STZB_FP_VARIANT")
    fp_other = list(args.fingerprint_other) + env_list("STZB_FP_OTHER")

    fp_argv = []
    if fp_map:
        fp_argv += ["--map", fp_map]
        for v in fp_variant:
            fp_argv += ["--variant", v]
        for o in fp_other:
            fp_argv += ["--other", o]

    checks.append(
        {
            "id": "scene-fingerprint",
            "title": "场景指纹算法实测（需要真机截图）",
            "script": "validate_scene_fingerprint.py",
            "argv": fp_argv,
            "required": bool(fp_map) or args.strict,
            "skip_reason": None if fp_map else (
                "未提供真机截图。用 --fingerprint-map <大地图截图> "
                "或环境变量 STZB_FP_MAP 提供；真机截图不适合进仓库，故默认跳过。"
            ),
        }
    )

    # 模板匹配器实测：拿真机大地图当帧、另一界面当负例，验 OpenCvMatcher 那套
    # ZNCC + 次峰抑制在真实像素上能不能区分“同一个按钮”与“不同界面”。
    # 它需要 --map 与 --other **两张**都有意义（只有正例就无法证伪），
    # 故比 scene-fingerprint 多一道门槛；真机截图不进仓库，缺参时优雅跳过。
    tpl_argv = []
    if fp_map and fp_other:
        tpl_argv = ["--map", fp_map, "--other", fp_other[0]]
    checks.append(
        {
            "id": "template-matcher",
            "title": "模板匹配实测（ZNCC 得分 / 正负例可分性，需要真机截图）",
            "script": "validate_template_matcher.py",
            "argv": tpl_argv,
            "required": bool(tpl_argv) or args.strict,
            "skip_reason": None if (fp_map and fp_other) else (
                "未同时提供大地图截图与另一界面截图。用 --fingerprint-map 加 "
                "--fingerprint-other 各一张（或环境变量 STZB_FP_MAP / STZB_FP_OTHER）"
                "即可启用；真机截图不适合进仓库，故默认跳过。"
            ),
        }
    )

    # 按键模板**种子**的验收：清单里每个种子都必须"能自检、跨帧可迁移、在别的界面上不误命中"。
    # 种子会随 APK 发给所有用户，所以这条闸门比"生成"更重要 —— 没过验收就不该有文件存在。
    # 它读的是截图目录（不进仓库），缺图时跳过并说明，理由与上面两条一样。
    seed_manifest = os.path.join(REPO, "tools", "button_seeds.json")
    seed_shots = None
    if os.path.isfile(seed_manifest):
        try:
            with io.open(seed_manifest, encoding="utf-8-sig") as fh:
                dirs = json.load(fh).get("shots_dir", "")
            # 清单可以引用多个截图目录（不同批次的真机抓屏），逐个都要在才实跑
            dirs = [dirs] if isinstance(dirs, str) else list(dirs)
            seed_shots = [os.path.join(REPO, d) for d in dirs]
        except Exception as e:  # 清单写坏也是失败，不能悄悄不跑
            seed_shots = ["__manifest_broken__:%s" % e]
    seed_ready = bool(seed_shots) and all(os.path.isdir(d) for d in seed_shots)
    seed_argv = ["--manifest", seed_manifest, "--check"] if seed_ready else []
    checks.append(
        {
            "id": "button-seeds",
            "title": "按键模板种子的三重验收（自检 / 跨帧迁移 / 不误命中）",
            "script": "gen_button_seeds.py",
            "argv": seed_argv,
            "required": bool(seed_argv) or (args.strict and seed_shots is not None),
            "skip_reason": None if seed_argv else (
                "清单引用的截图目录不在（%s）。真机截图不进仓库，故默认跳过；"
                "本地有截图时这条会实跑，一个种子过不了就不许有产物。"
                % (", ".join(seed_shots) if seed_shots else "tools/button_seeds.json 缺失")
            ),
        }
    )
    return checks


def preflight_python_syntax():
    """
    先给 tools/ 下所有 Python 文件做一次语法检查。

    为什么值得单独做：这一路我在中文提示串里嵌双引号犯过**四次**
    （`print("…"从源码提炼规则"…")`），每次都是某个检查跑到一半才崩。
    预检把这类错误一次性、清楚地暴露出来，而不是让它伪装成"某个检查失败了"。

    为什么要用 utf-8-sig 读并单独报 BOM：PowerShell 的 `-Encoding UTF8` 会写
    BOM，而 `ast.parse` 碰到行首 BOM 会报 `line 1: invalid character`——当时
    整条回归在预检就断掉，看起来像“所有检查都挂了”，实际只是一位 validator
    开头多了三个字节。现在：BOM 不再顶掉预检，而是单独列成一条可读的失败。
    """
    import ast
    bad = []
    bom = []
    BOM_BYTES = b"\xef\xbb\xbf"
    for name in sorted(os.listdir(HERE)):
        if not name.endswith(".py"):
            continue
        path = os.path.join(HERE, name)
        with open(path, "rb") as fh:
            raw = fh.read()
        if raw.startswith(BOM_BYTES):
            bom.append(name)
            raw = raw[len(BOM_BYTES):]
        try:
            ast.parse(raw.decode("utf-8"))
        except SyntaxError as e:
            bad.append((name, e.lineno, e.msg))
    if bom:
        print("❌ 预检失败：下列文件带 UTF-8 BOM（PowerShell -Encoding UTF8 写出来的）")
        for name in bom:
            print(f"   {name}  ← 用 utf-8-sig 读、无 BOM 写回即可")
    if bad:
        print("❌ 预检失败：tools/ 下有 Python 文件存在语法错误")
        for name, lineno, msg in bad:
            print(f"   {name}:{lineno}  {msg}")
        print("   提示：中文提示串里不要直接嵌双引号，用「」代替。")
        return False
    if bom:
        return False
    print(f"预检通过：tools/ 下 {sum(1 for n in os.listdir(HERE) if n.endswith('.py'))} "
          f"个 Python 文件语法正确且无 BOM")
    return True


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    ap = argparse.ArgumentParser()
    ap.add_argument("--strict", action="store_true",
                    help="把'需要外部输入而被跳过'的检查也视为失败")
    ap.add_argument("--only", nargs="*", default=None, help="只跑指定 id 的检查")
    ap.add_argument("--fingerprint-map", default=None, help="大地图基准截图路径")
    ap.add_argument("--fingerprint-variant", action="append", default=[],
                    help="同 UI 的另一张截图（期望命中）")
    ap.add_argument("--fingerprint-other", action="append", default=[],
                    help="不同 UI 的截图（期望不命中）")
    args = ap.parse_args()

    os.chdir(REPO)

    if not preflight_python_syntax():
        return 1

    checks = build_checks(args)
    if args.only:
        checks = [c for c in checks if c["id"] in set(args.only)]
        if not checks:
            print(f"没有匹配的检查 id: {args.only}", file=sys.stderr)
            return 2

    print("=" * 78)
    print("率土全能管家 · 一键静态回归")
    print("=" * 78)
    print(f"仓库根目录: {REPO}")
    print(f"检查项数量: {len(checks)}" + ("（严格模式）" if args.strict else ""))
    print("-" * 78)

    results = []
    for c in checks:
        if c.get("skip_reason"):
            # 注意：这里必须区分"可选跳过"与"严格模式下跳过即失败"。
            # 第一版只打印 SKIP，完全没看 required 字段，
            # 结果 --strict 在文档里承诺的行为根本没有实现。
            if c["required"]:
                results.append((c, "fail", c["skip_reason"], 0.0))
                print(f"FAIL | {c['id']:22s} | {c['title']}")
                print(f"       {c['skip_reason']}")
                print("       （--strict：跳过视为失败）")
            else:
                results.append((c, "skip", c["skip_reason"], 0.0))
                print(f"SKIP | {c['id']:22s} | {c['title']}")
                print(f"       {c['skip_reason']}")
            continue

        t0 = time.time()
        rc, out = run_checker_inprocess(c["script"], c["argv"])
        dt = time.time() - t0
        ok = (rc == 0)
        # required=False 的闸门：未过列成 WARN、不进退出码。
        # 以前只看了 skip_reason 里的 required，真跑起来未照样计入 FAIL——
        # “只警告”的承诺落空，后果要么是不能上 CI，要么是开始整体忽略红灯。
        # （--strict 下仍然算失败，与文档一致。）
        if not ok and not c["required"] and not args.strict:
            results.append((c, "warn", out, dt))
            print(f"WARN | {c['id']:22s} | {c['title']}  ({dt:.1f}s)  【非必需，不计入退出码】")
        else:
            results.append((c, "pass" if ok else "fail", out, dt))
            print(f"{'PASS' if ok else 'FAIL'} | {c['id']:22s} | {c['title']}  ({dt:.1f}s)")
        if not ok:
            lines = [l for l in out.splitlines() if l.strip()]
            for l in lines[-24:]:
                print(f"       {l}")
            if len(lines) > 24:
                print(f"       …上方仅列后 24 行，完整清单：python tools/{c['script']}")

    print("-" * 78)
    failed = [r for r, st, _, _ in results if st == "fail"]
    skipped = [r for r, st, _, _ in results if st == "skip"]
    warned = [r for r, st, _, _ in results if st == "warn"]

    print(f"通过 {sum(1 for _, st, _, _ in results if st == 'pass')} / "
          f"失败 {len(failed)} / 警告 {len(warned)} / 跳过 {len(skipped)}")

    if skipped:
        print("被跳过的检查（不算失败，但请知悉其未被验证）：")
        for r in skipped:
            print(f"  - {r['id']}: {r['title']}")

    if warned:
        print("\n警告项（required=False：待修清单，不影响退出码，但必须逐条收敛）：")
        for r in warned:
            print(f"  - {r['id']}: {r['title']}")

    if failed:
        print("\n失败项:")
        for r in failed:
            print(f"  - {r['id']}: {r['title']}")
        print("\n结论: 存在失败项，请先修复。")
        return 1

    print("\n结论: 全部必需检查通过。")
    print("注意: 这些都是**静态**检查，不能替代真实的 NDK/Gradle 编译与真机验证。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
