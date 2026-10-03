# 守军头像图库构建器（defender_gallery）

把「各等级土地守军面板截图」加工成运行时用的**确定性头像模板库**：
`client/app/src/main/assets/defender_refs/<武将名>.png`（一人一张）。

## 你要做的：投递截图
1. 把截图放进本目录下的 `raw/`（已被 `.gitignore` 排除，不会入库）。
2. 覆盖范围：**Lv3 / Lv4 / Lv5 / Lv6 / Lv7**。其中 **Lv6、Lv7 会有两队守一块**，
   务必让两队的武将头像都清晰出现在截图里。
3. 每张图要求：**武将头像 + 武将名都清晰可读**（名字用来给头像打标签）。
4. 目标不是穷举组合，而是**尽量覆盖不同的守将头像**（软柿子 + 危险将都要有）。

建议（非强制）的文件名，便于我核对等级/队伍：
`lv5_s1_XXX.png` / `lv6_s1_XXX.png` / `lv6_s2_XXX.png`

## 构建流程（我来做，你不用装 OCR）
1. 我直接**查看你的截图**，标定「头像槽位的像素框」（含单队/双队两种布局）——
   不依赖离线 OCR，我读名字、生成逐槽裁剪清单，更准。
2. 用裁剪脚本按固定槽位切出每张头像 → 落到 `assets/defender_refs/<武将名>.png`。
3. 运行时 `DefenderTemplateClassifier` 用同一套槽位裁当前面板 →
   `OpenCvMatcher.match` 逐个比库 → 得到守将身份 → 喂 `DefenderEvaluator`（取最危险）。

## 产物去向
- 头像模板 PNG 提交进 `client/app/src/main/assets/defender_refs/`（随 APK 打包）。
- `raw/`、`work/`、`vis/` 一律不提交。
