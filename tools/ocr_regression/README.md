# OCR 回归测试（Phase B · 真机）

## 为什么需要真机
native OCR 是 **ncnn/OpenCV .so**，只在 Android 设备运行；本机（Windows）无 ncnn 运行时、
且离线装 PaddleOCR 的网络此前已验证被墙。因此**新旧方案的准确率/召回率/耗时必须在真机测**，
本目录只负责把「测试协议 + 打分脚本」固化成可复现流程，数字由设备运行回填。

## 测试协议
1. 构建 debug APK（CI 产物或本地 `gradlew assembleDebug`），安装到目标机。
2. 准备 **fixture ROI**：从游戏截屏裁出下列小区域，PNG 存到 `fixtures/`（每场景 ≥8 张）：
   - `stamina`   武将体力 `xx/120`
   - `coordinate` 坐标数字（`武威 (228,132)` 的括号数字 / 输入框数字）
   - `countdown` 行军/免战倒计时 `mm:ss` 或 `hh:mm:ss`
   - `ui_text`   关键按钮文字（扫荡/出征/屯田/税收）
   - `vertical_name` 守将竖排名（Phase C 第二通道，验证 getRotateCropImage 转置是否命中）
3. 触发识别、抓取结果。两种取数方式，任选其一：
   - **A. 现网路径**：正常跑对应战术，过滤 logcat：
     `adb logcat -s OcrManager:* DefenderTemplateClassifier:* RapidOcr:*`
     其中 `小字 ROI 放大: WxH -> WxH` 证明上采样生效；native 若开启 Logger 会打印每框几何。
   - **B. 直接喂 fixture**：临时在设备上用一个调试入口对 `fixtures/` 逐张 `OcrManager.detect*`，
     把 `(scenario, file, expected, got, latency_ms)` 落一行。
4. 把结果整理成 `results.csv`（见 `results.example.csv` 的列）：
   ```
   scenario,file,expected,got,latency_ms
   stamina,t1.png,88/120,88/120,14
   ...
   ```
5. 打分：
   ```
   python score.py results.csv
   ```
   输出：各场景 **准确率/召回率/平均耗时**，并汇总。

## 对比基线（新旧）
在**改动前的 commit** 上重复步骤 1–5 得 `results_before.csv`，改动后得 `results_after.csv`，
用 `python score.py results_after.csv --baseline results_before.csv` 输出前后差值。
> 关键预期：`stamina/coordinate/countdown`（小字）应因**上采样**召回上升；
> `ui_text`（横排大字）应基本不变（`detectSmall` 对 ≥40px 短边不放大，行为等价）；
> `vertical_name` 用于判断 native 竖排转置是否已把整列识别成一横排。

# P2 当前结论（实测，非推断）

**裁决：`STAY_V3` —— 迁移闸门保持关闭，出厂仍为 PP-OCRv3。**

决定性事实（本机 `onnxruntime 1.23.2` 直接读模型 I/O 得到，不是猜测）：

| 项 | 实测值 |
|---|---|
| `.a_plus_plus/ocr_v5/rec.onnx` 输出类别数 | **18385** |
| 仓库唯一词典 `ppocr_keys_v1.txt` 行数 | **6623**（⇒ 只配 6625 类的 v3 rec） |
| 在包 `ch_PP-OCRv3_rec_infer.param` 末层 `num_output` | **6625** ✔ 与词典配套 |
| `ppocr_keys_v5.txt` | **不存在**，且离线下载被墙 |

即 **v5 权重缺配套词典**。用 v3 词典去解 18385 类的 rec，输出不是报错、
不是空结果，而是**能读出字的乱码**——体力/坐标/倒计时解析全部失败，
依赖文字的战术整条静默超时。这比崩溃更难查，因为它长得像「暂时没识别到」。
因此 `赢才迁` 在这里连「跑分」的前提都不成立。

### 已落地的两道静态闸门（不依赖设备、CI 可跑）

```bash
# 1) 权重↔词典配套契约：rec 的 num_output 必须 == 词典行数 + 2
python tools/ocr_regression/check_ocr_asset_contract.py --report
python tools/ocr_regression/check_ocr_asset_contract.py --selftest   # 注入半套/破损反例

# 2) 迁移判定闸门：把「赢」写成 8 条合取阈值，缺证据一律不放行
python tools/ocr_regression/decision_gate.py            # 真实裁决（当前 STAY_V3，退出码 1）
python tools/ocr_regression/decision_gate.py --selftest # 用合成跑分驱动每条拒绝分支
```

契约校验拦的是这条**当前能一路绿灯进仓库**的事故路径：有人转出 v5 ncnn、
把 v3 词典改名成 `ppocr_keys_v5.txt` 一起提交 ⇒ 版本容错按「文件齐备」选中 v5
⇒ 上线乱码。名字对得上、文件都在，只有**类别数 ↔ 词典长度**这一条不变量能拦住它。

> 注：`decision_gate.py` 的真实裁决**没有**注册成 `run_all_checks.py` 的必需项——
> 它的正常输出就是 `STAY_V3`（退出码 1），属期望态而非构建失败；注册成必需项会让
> CI 长期飘红，最终导致所有人忽略它。注册的是契约校验与两个自测。

### 解除阻塞需要做什么（按顺序）

1. 取 **`ppocr_keys_v5.txt`（18383 行）** —— 必须与 v5 rec 的 18385 类严格配套；
2. 本机有 `onnx2ncnn`/`pnnx` 可执行时跑 `python tools/a_plus_plus/export_ppocrv5.py --src .a_plus_plus/ocr_v5`；
3. `check_ocr_asset_contract.py` 必须对 v5 判 PASS；
4. 按上文协议在**真机**产出 `results_before.csv`(v3) 与 `results_after.csv`(v5)；
5. `decision_gate.py` 判 `MIGRATE` 才允许提交 v5 资产；判 `STAY_V3` 就删除 v5 文件回落。

## 本目录约定
- `fixtures/` 被 .gitignore 排除（设备截屏，体积大、不入库）。
- `results*.csv` 允许提交（脱敏后的小样本，作为回归留痕）。
