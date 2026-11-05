@echo off
chcp 65001 >nul
rem Universal Router Mode - tether invertido por WiFi.
rem Necesita permisos de administrador para crear el adaptador de red virtual.
net session >nul 2>&1
if %errorlevel% neq 0 (
  powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
  exit /b
)
cd /d "%~dp0"
title Universal Router Mode - Tether invertido
echo.
echo  1. En el telefono: activa "Tether invertido" en Universal Router Mode.
echo  2. Conecta este PC a la red WiFi DIRECT-UR-RouterMode del telefono.
echo  3. Deja esta ventana abierta mientras uses internet. Cierrala para desconectar.
echo.
sing-box.exe run -c config.json
echo.
echo sing-box se detuvo. Revisa el mensaje de arriba.
pause
