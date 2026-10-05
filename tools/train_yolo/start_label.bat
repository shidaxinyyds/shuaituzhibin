@echo off
cd /d "%~dp0"
echo ============================================================
echo   STZB YOLO Labeler   http://127.0.0.1:8765
echo   Browser opens automatically. KEEP THIS WINDOW OPEN
echo   while you are labeling. Close it (or Ctrl+C) to stop.
echo ============================================================
python label_web.py %*
echo.
echo [labeler exited] Read any error above. Press any key to close.
pause >nul
