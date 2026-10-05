#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""率土 YOLO 本机网页标注器（零依赖，仅标准库 + 浏览器）
======================================================
labelImg 装不上时的替代：不装任何包，浏览器里看图画框，直接写 YOLO txt。
类别固定读 data.yaml 的 names（与端侧 YoloDetector.DetectionClass 对齐），
按数字键 1-7 切类，画框→存 dataset/labels/<split>/<同名>.txt。

启动
----
    python tools/train_yolo/label_web.py                 # 默认标 dataset/images/train
    python tools/train_yolo/label_web.py --split val     # 标 val
    python tools/train_yolo/label_web.py --port 8765

然后浏览器打开 http://127.0.0.1:8765
    左键拖拽 = 画框（用当前选中类别）    数字 1-7 = 切类
    点框 = 选中        Delete = 删框        S = 保存       ← / → = 上/下一张
"""
import argparse
import json
import os
import sys
import threading
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except (AttributeError, ValueError):
    pass

HERE = os.path.dirname(os.path.abspath(__file__))
DATA_YAML = os.path.join(HERE, "data.yaml")
IMG_EXTS = (".jpg", ".jpeg", ".png", ".bmp")
CT = {".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".png": "image/png", ".bmp": "image/bmp"}


def load_names(path=DATA_YAML):
    """返回按 id 升序排列的类别名列表（下标=id）。"""
    id_to_name = {}
    in_names = False
    with open(path, "r", encoding="utf-8") as fh:
        for raw in fh:
            s = raw.strip()
            if s.startswith("names"):
                in_names = True
                continue
            if in_names:
                if not s or s.startswith("#"):
                    continue
                if not raw.startswith((" ", "\t")):
                    break
                body = s.split("#", 1)[0].strip()
                if ":" in body:
                    k, v = body.split(":", 1)
                    try:
                        id_to_name[int(k.strip())] = v.strip().strip("'\"")
                    except ValueError:
                        in_names = False
    if not id_to_name:
        raise RuntimeError("data.yaml 未解析到 names")
    return [id_to_name[i] for i in sorted(id_to_name)]


class Store:
    def __init__(self, dataset, split):
        self.img_dir = os.path.join(dataset, "images", split)
        self.lbl_dir = os.path.join(dataset, "labels", split)
        os.makedirs(self.lbl_dir, exist_ok=True)
        self.names = load_names()

    def images(self):
        if not os.path.isdir(self.img_dir):
            return []
        return sorted(f for f in os.listdir(self.img_dir)
                      if f.lower().endswith(IMG_EXTS))

    def _txt(self, fname):
        return os.path.join(self.lbl_dir, os.path.splitext(fname)[0] + ".txt")

    def read_boxes(self, fname):
        p = self._txt(fname)
        boxes = []
        if os.path.isfile(p):
            with open(p, "r", encoding="utf-8") as fh:
                for line in fh:
                    q = line.split()
                    if len(q) >= 5:
                        boxes.append([int(q[0])] + [float(x) for x in q[1:5]])
        return boxes

    def write_boxes(self, fname, boxes):
        lines = []
        for b in boxes:
            cid = int(b[0])
            if cid < 0 or cid >= len(self.names):
                continue
            vals = " ".join("%.6f" % float(v) for v in b[1:5])
            lines.append("%d %s" % (cid, vals))
        with open(self._txt(fname), "w", encoding="utf-8") as fh:
            fh.write("\n".join(lines) + ("\n" if lines else ""))
        return len(lines)


STORE = None


def make_html():
    return """<!doctype html><html lang="zh"><head><meta charset="utf-8">
<title>率土标注器</title><style>
body{margin:0;font-family:system-ui,"Microsoft YaHei",sans-serif;background:#111;color:#eee}
#bar{display:flex;flex-wrap:wrap;gap:6px;padding:8px;background:#1c1c1c;position:sticky;top:0;z-index:5}
button,.cls{cursor:pointer;border:1px solid #444;background:#2a2a2a;color:#eee;border-radius:6px;padding:6px 10px;font-size:13px}
.cls.on{outline:2px solid #4af;background:#123}
#wrap{display:flex;gap:8px;padding:8px}
#stage{flex:1;position:relative}
canvas{max-width:100%;display:block;border:1px solid #333;cursor:crosshair;background:#000}
#side{width:230px;background:#181818;border:1px solid #333;border-radius:6px;padding:8px;font-size:13px;max-height:90vh;overflow:auto}
.row{padding:4px 6px;border-radius:4px;cursor:pointer;display:flex;justify-content:space-between}
.row:hover,.row.sel{background:#26324a}
#idx{font-weight:700}
kbd{background:#333;border-radius:3px;padding:0 5px;font-size:11px}
#hint{color:#8aa;width:100%;font-size:12px}
</style></head><body>
<div id="bar">
  <span id="idx"></span>
  <button onclick="prev()">← 上张</button>
  <button onclick="next()">下张 →</button>
  <button onclick="save()">保存(S)</button>
  <span id="cls"></span>
  <span id="hint">左键拖拽画框 · 数字1-7切类 · 点框选中 · Del删 · ←/→翻页</span>
</div>
<div id="wrap"><div id="stage"><canvas id="cv" width="1200" height="600"></canvas></div>
<div id="side"><b>本图框（点选，Del 删除）</b><div id="list"></div></div></div>
<script>
let meta={images:[],names:[]}, cur=0, boxes=[], sel=-1, cls=0;
let img=new Image(), drag=null;
const cv=document.getElementById('cv'), ctx=cv.getContext('2d');
const COLORS=['#ff4d4d','#ffd24d','#4dd2ff','#7cff4d','#c07cff','#ff7ce0','#ff9a4d'];
async function j(u,o){const r=await fetch(u,o);return r.json();}
async function boot(){
  meta=await j('/api/meta');
  document.getElementById('cls').innerHTML=meta.names.map((n,i)=>
    `<span class="cls" data-i="${i}" onclick="setcls(${i})">${i+1} ${n}</span>`).join('');
  setcls(0); load();
}
function setcls(i){cls=i;document.querySelectorAll('.cls').forEach(e=>e.classList.toggle('on',+e.dataset.i===i));}
async function load(){
  if(!meta.images.length){document.getElementById('idx').textContent='没有图片';return;}
  const name=meta.images[cur];
  boxes=await j('/labels?name='+encodeURIComponent(name));
  sel=-1;
  document.getElementById('idx').textContent=(cur+1)+' / '+meta.images.length+'  '+name;
  img=new Image(); img.onload=()=>{cv.width=img.width;cv.height=img.height;draw();list();}; img.src='/img?name='+encodeURIComponent(name);
}
function draw(){
  ctx.clearRect(0,0,cv.width,cv.height);
  ctx.drawImage(img,0,0);
  boxes.forEach((b,i)=>{
    const [x,y,w,h]=px(b); ctx.lineWidth=i===sel?4:2;
    ctx.strokeStyle=COLORS[b[0]%COLORS.length]; ctx.strokeRect(x,y,w,h);
    ctx.fillStyle=COLORS[b[0]%COLORS.length]; ctx.font='14px system-ui';
    ctx.fillText(meta.names[b[0]],x+2,Math.max(14,y-4));
  });
  if(drag){ctx.setLineDash([6,4]);ctx.strokeStyle='#fff';ctx.strokeRect(drag.x,drag.y,drag.w,drag.h);ctx.setLineDash([]);}
}
function px(b){return [b[1]*cv.width-b[3]*cv.width/2,b[2]*cv.height-b[4]*cv.height/2,b[3]*cv.width,b[4]*cv.height];}
function norm(x,y,w,h){return [cls,x/cv.width,y/cv.height,w/cv.width,h/cv.height];}
function pos(e){const r=cv.getBoundingClientRect();return [(e.clientX-r.left)*cv.width/r.width,(e.clientY-r.top)*cv.height/r.height];}
cv.addEventListener('mousedown',e=>{const[x,y]=pos(e);
  let hit=-1; for(let i=boxes.length-1;i>=0;i--){const[bx,by,bw,bh]=px(boxes[i]);if(x>=bx&&x<=bx+bw&&y>=by&&y<=by+bh){hit=i;break;}}
  if(hit>=0){sel=hit;draw();list();return;}
  sel=-1; drag={x0:x,y0:y,x,y,w:0,h:0};});
cv.addEventListener('mousemove',e=>{if(!drag)return;const[x,y]=pos(e);
  drag.x=Math.min(drag.x0,x);drag.y=Math.min(drag.y0,y);drag.w=Math.abs(x-drag.x0);drag.h=Math.abs(y-drag.y0);draw();});
window.addEventListener('mouseup',e=>{if(!drag)return;
  if(drag.w>4&&drag.h>4)boxes.push(norm(drag.x,drag.y,drag.w,drag.h));
  drag=null;draw();list();});
async function save(){const name=meta.images[cur];
  const r=await j('/labels?name='+encodeURIComponent(name),{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(boxes)});
  document.getElementById('idx').textContent+='  ✔已存'+r.saved; }
function del(){if(sel>=0){boxes.splice(sel,1);sel=-1;draw();list();}}
function list(){document.getElementById('list').innerHTML=boxes.map((b,i)=>
  `<div class="row ${i===sel?'sel':''}" onclick="pick(${i})"><span>${i+1}. ${meta.names[b[0]]}</span><span>${(b[3]*100).toFixed(0)}x${(b[4]*100).toFixed(0)}</span></div>`).join('')||'<i>还没有框</i>';}
function pick(i){sel=i;draw();list();}
function prev(){if(cur>0){cur--;load();}} function next(){if(cur<meta.images.length-1){cur++;load();}}
window.addEventListener('keydown',e=>{
  if(e.key==='s'||e.key==='S'){save();}
  else if(e.key==='Delete'||e.key==='Backspace'){del();}
  else if(e.key==='ArrowLeft'){prev();} else if(e.key==='ArrowRight'){next();}
  else if(/^[1-7]$/.test(e.key)){setcls(+e.key-1);}});
boot();
</script></body></html>"""


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _send(self, code, body, ctype="application/json"):
        if isinstance(body, (dict, list)):
            body = json.dumps(body, ensure_ascii=False).encode("utf-8")
        elif isinstance(body, str):
            body = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _q(self):
        return parse_qs(urlparse(self.path).query)

    def do_GET(self):
        u = urlparse(self.path)
        if u.path == "/":
            self._send(200, make_html(), "text/html; charset=utf-8")
        elif u.path == "/api/meta":
            self._send(200, {"images": STORE.images(), "names": STORE.names})
        elif u.path == "/img":
            name = self._q().get("name", [""])[0]
            p = os.path.normpath(os.path.join(STORE.img_dir, name))
            if not p.startswith(os.path.abspath(STORE.img_dir)) or not os.path.isfile(p):
                self._send(404, {"err": "no image"}); return
            with open(p, "rb") as fh:
                self._send(200, fh.read(), CT.get(os.path.splitext(p)[1].lower(), "application/octet-stream"))
        elif u.path == "/labels":
            name = self._q().get("name", [""])[0]
            self._send(200, STORE.read_boxes(name))
        else:
            self._send(404, {"err": "404"})

    def do_POST(self):
        u = urlparse(self.path)
        if u.path == "/labels":
            name = self._q().get("name", [""])[0]
            n = int(self.headers.get("Content-Length", 0))
            data = json.loads(self.rfile.read(n) or b"[]")
            saved = STORE.write_boxes(name, data)
            self._send(200, {"saved": saved})
        else:
            self._send(404, {"err": "404"})


def main():
    global STORE
    ap = argparse.ArgumentParser(description="率土 YOLO 本机网页标注器")
    ap.add_argument("--dataset", default=os.path.join(HERE, "dataset"))
    ap.add_argument("--split", default="train", choices=["train", "val"])
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--no-open", action="store_true", help="不自动弹浏览器")
    args = ap.parse_args()
    STORE = Store(args.dataset, args.split)
    imgs = STORE.images()
    print("标注集：%s  图片 %d 张  类别：%s" % (args.split, len(imgs), ", ".join(STORE.names)))
    if not imgs:
        print("⚠ 该 split 没有图片，先跑 prepare_dataset.py split")
    url = "http://127.0.0.1:%d" % args.port
    print("浏览器打开 →  %s   (Ctrl+C 退出)" % url)
    sys.stdout.flush()
    if not args.no_open:
        threading.Timer(1.0, lambda: webbrowser.open(url)).start()
    try:
        ThreadingHTTPServer(("127.0.0.1", args.port), Handler).serve_forever()
    except OSError as e:
        print("❌ 端口 %d 启动失败（可能已被占用）：%s" % (args.port, e))
        print("   换个端口重试： python label_web.py --port 8899")
        sys.exit(1)
    except KeyboardInterrupt:
        print("\n已退出。")


if __name__ == "__main__":
    main()
