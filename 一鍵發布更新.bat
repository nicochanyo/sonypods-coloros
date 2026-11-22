@echo off
chcp 65001 >nul
title SonyPods ColorOS - 一鍵編譯與發布 (GitHub + LSPosed 倉庫)
cd /d "%~dp0"

echo ========================================================
echo   SonyPods ColorOS - 自動化編譯與上傳腳本
echo   1. 自動檢查/編譯最新簽名 APK
echo   2. 自動提交並推送原始碼至 nicochanyo/sonypods-coloros
echo   3. 自動建立 Release 並上傳 APK 至 LSPosed 倉庫與原始碼倉庫
echo ========================================================
echo.

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0publish.ps1"

echo.
pause
