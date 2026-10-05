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

## 本目录约定
- `fixtures/` 被 .gitignore 排除（设备截屏，体积大、不入库）。
- `results*.csv` 允许提交（脱敏后的小样本，作为回归留痕）。
