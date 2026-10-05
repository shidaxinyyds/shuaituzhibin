# ============================================================================
#  方案 A++ · 全引擎一键训练/导出脚本（在你自己的 PowerShell 里跑，需要 GPU+联网）
# ----------------------------------------------------------------------------
#  用法（在本仓库根目录 d:\mj\shuaituzhibin 打开 PowerShell）：
#    1) 先只装环境、不训练：        .\tools\a_plus_plus\setup_and_train.ps1 -SetupOnly
#    2) 装好 + 跑 OCR 与 rbt3（零标注就能出）：
#         .\tools\a_plus_plus\setup_and_train.ps1
#    3) 全跑（含 bge 索引与 YOLO，需你先备好数据，见下）：
#         .\tools\a_plus_plus\setup_and_train.ps1 -All -NcnnBin "D:\ncnn\build-vulkan-vs2022\bin" `
#             -Corpus "tools\a_plus_plus\corpus_stzb.jsonl" -YoloData "tools\train_yolo\data.yaml"
#
#  前置：
#   - NVIDIA GPU（本机 RTX 4060 8GB 足够）+ 驱动
#   - Python 3.10/3.11（脚本自动挑一个标准安装；优先 3.10）
#   - ncnn Windows 预编译包（含 onnx2ncnn.exe / ncnn2table.exe / ncnn2int8.exe）
#       下载：https://github.com/Tencent/ncnn/releases  →  ncnn-*-windows-vs2022.zip，解压后把其 bin 目录传给 -NcnnBin
#
#  数据（只有这两项要你亲手准备，其余脚本自动下基座/自动合成）：
#   - YOLO：tools\train_yolo\dataset\images\{train,val} + labels\{train,val}（11 类 YOLO 标注）
#   - bge 索引：一份率土语料 JSONL（每行 {"text": "..."}），-Corpus 指定
# ============================================================================
param(
    [string]$NcnnBin = $env:STZB_NCNN_BIN,   # ncnn 工具目录（onnx2ncnn/ncnn2table/ncnn2int8）
    [string]$Corpus  = "",                    # RAG 语料 JSONL（留空则跳过建索引）
    [string]$YoloData = "",                   # YOLO data.yaml（留空或数据集缺失则跳过 YOLO）
    [switch]$SetupOnly,                        # 只建 venv + 装依赖
    [switch]$All                               # 连 YOLO/索引一起跑（需上面数据就位）
)

$ErrorActionPreference = "Stop"
$Repo = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
Set-Location $Repo
$Venv  = Join-Path $Repo ".venv-app"
$Pypi  = Join-Path $Venv "Scripts\python.exe"

function Say($m){ Write-Host "`n==== $m ====" -ForegroundColor Cyan }

# ---- 1) 选一个可用的标准 Python（3.10/3.11），不要 3.8 ----
Say "定位 Python 3.10/3.11"
$Py = $null
foreach($cand in @(
    "C:\Users\ing\AppData\Local\Programs\Python\Python310\python.exe",
    "C:\Users\ing\AppData\Local\Programs\Python\Python311\python.exe"
)){
    if(Test-Path $cand){ $Py = $cand; break }
}
if(-not $Py){
    # 用 py launcher 探测
    $found = (& py -3.10 -c "import sys;print(sys.executable)") 2>$null
    if($LASTEXITCODE -eq 0 -and $found){ $Py = $found.Trim() }
}
if(-not $Py){ throw "没找到 Python 3.10/3.11，请装一个（不要用 3.8）。" }
& $Py --version
Say "venv: $Venv"
if(-not (Test-Path $Pypi)){ & $Py -m venv $Venv }

# ---- 2) 装依赖（CUDA 版 torch 先装，让 ultralytics/transformers 复用）----
Say "升级 pip + 安装 CUDA torch"
& $Pypi -m pip install --upgrade pip
# cu121 适配 RTX 40 系；驱动不支持可换 cu118
& $Pypi -m pip install torch torchvision --index-url https://download.pytorch.org/whl/cu121
Say "安装训练/导出依赖"
& $Pypi -m pip install ultralytics transformers optimum onnx onnxruntime huggingface_hub pillow datasets
& $Pypi -c "import torch;print('torch',torch.__version__,'cuda',torch.cuda.is_available())"

if($SetupOnly){ Say "仅安装完成。去掉 -SetupOnly 即可开跑。"; exit 0 }

# ---- 3) 逐台引擎（按投入产出比排序）----
$P = "tools/a_plus_plus"

Say "① PP-OCRv5（零标注，最大红利）"
$ocrArgs = @("$P/export_ppocrv5.py","--download")
if($NcnnBin){ $ocrArgs += @("--ncnn-bin", $NcnnBin) }
& $Pypi @ocrArgs

Say "② rbt3 意图+槽位（缺数据自动合成语料）"
& $Pypi "$P/train_intent_slot_rbt3.py" --epochs 8

Say "③ bge-small-zh INT8（导出模型）"
$bgeArgs = @("$P/export_bge_small.py")
if($Corpus -and (Test-Path $Corpus)){ $bgeArgs += @("--corpus", $Corpus, "--build-index") }
else { Write-Host "  未给 -Corpus 或语料不存在：跳过 512 维索引（RAG 继续走哈希占位）" -ForegroundColor Yellow }
& $Pypi @bgeArgs

if($All){
    Say "④ YOLO26（需你已备好 dataset/images+labels）"
    $yaml = if($YoloData){ $YoloData } else { "tools/train_yolo/data.yaml" }
    $dsDir = Join-Path (Split-Path $yaml -Parent) "dataset"
    if(Test-Path $dsDir){
        $yoloArgs = @("$P/train_yolo26.py","--data",$yaml,"--epochs","200","--device","0")
        if($NcnnBin){ $yoloArgs += @("--ncnn-bin", $NcnnBin) }
        & $Pypi @yoloArgs
    } else {
        Write-Host "  数据集缺失：$dsDir`n  请先采集率土截图并按 11 类标注放入 images/{train,val}+labels/{train,val}，再跑 -All。" -ForegroundColor Yellow
    }
} else {
    Write-Host "`n（YOLO 需 GPU+已标注数据集，加 -All 且备好 dataset 后再跑）" -ForegroundColor DarkGray
}

# ---- 4) 契约自检：全绿即可提交入包 ----
Say "A++ 资产契约自检"
& $Pypi "$P/build_all.py" --validate-only
Write-Host "`n自检表若出现 ❌：缺哪项就补哪项数据/ncnn 工具后重跑对应引擎。全 ✅ 后：git add 权重 -> 推送出 APK。" -ForegroundColor Green
