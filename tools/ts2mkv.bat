@echo off
chcp 65001 >nul
REM 在 Windows 上把 .ts 无损转成 .mkv（需要 ffmpeg 在 PATH 里，或把 ffmpeg.exe 放在本目录）
REM 用法：把要处理的文件夹拖到这个 bat 文件上
setlocal
if "%~1"=="" ( echo 用法：把视频文件夹拖到本文件上 & pause & exit /b )
for /r "%~1" %%F in (*.ts *.m2ts *.mts) do (
  if not exist "%%~dpnF.mkv" (
    echo 转封装：%%F
    ffmpeg -hide_banner -loglevel error -stats -y -i "%%F" -map 0 -c copy "%%~dpnF.mkv"
  )
)
echo 全部处理完毕
pause
