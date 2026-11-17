#!/usr/bin/env bash
# 把 .ts/.m2ts/.mts 无损转封装成 .mkv（只换容器、不重编码，画质音质完全不变，速度取决于硬盘）
# 用法：
#   ./ts2mkv.sh /vol1/1000/影视          # 处理该目录（含子目录）
#   ./ts2mkv.sh /vol1/1000/影视 --delete # 转成功后删除原 ts
# 需要 ffmpeg：飞牛 NAS 可用 `sudo apt install ffmpeg` 或 Docker 里跑
set -u
DIR="${1:-.}"
DEL="${2:-}"
command -v ffmpeg >/dev/null || { echo "没找到 ffmpeg，先安装：sudo apt install ffmpeg"; exit 1; }
find "$DIR" -type f \( -iname '*.ts' -o -iname '*.m2ts' -o -iname '*.mts' \) -print0 |
while IFS= read -r -d '' f; do
  out="${f%.*}.mkv"
  [ -f "$out" ] && { echo "跳过（已存在）：$out"; continue; }
  echo "转封装：$f"
  if ffmpeg -hide_banner -loglevel error -stats -y -i "$f" -map 0 -c copy -c:s copy "$out"; then
    echo "完成：$out"
    [ "$DEL" = "--delete" ] && rm -f "$f" && echo "已删除原文件：$f"
  else
    echo "失败：$f（可能是损坏或非标准 TS，保留原文件）"
    rm -f "$out"
  fi
done
echo "全部处理完毕。"
