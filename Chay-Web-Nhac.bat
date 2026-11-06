@echo off
chcp 65001 >nul
title TikTok Music Clean Server
cd /d "%~dp0"
echo Đang khởi động máy chủ TikTok Music Clean tại http://localhost:3000 ...
start http://localhost:3000
node server.js
pause
