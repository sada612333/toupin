@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

:: ===================== 配置 =====================
set "EMULATOR=D:\Android\SDK\emulator\emulator.exe"
set "ADB=D:\Android\SDK\platform-tools\adb.exe"
set "AVD_NAME=Resizable_Experimental"
set "PORT=8080"
:: =================================================

echo.
echo  [ AVD WebRTC 一键启动脚本 ]
echo.

:: 强制重启 adb，解决多设备冲突
"%ADB%" kill-server >nul 2>nul
"%ADB%" start-server >nul 2>nul

:: 固定获取你的电脑 IP（最稳写法）
for /f "tokens=2 delims=:" %%i in ('netsh interface ip show addresses "WLAN" ^| findstr "192.168"') do (
    set "IP=%%i"
    set "IP=!IP: =!"
)

:: 如果上面获取失败，直接写死你的 IP（你自己的）
if not defined IP set "IP=192.168.10.13"

echo 本机局域网IP：%IP%
echo.

:: 启动 AVD
echo 正在启动 AVD...
start "" "%EMULATOR%" -avd %AVD_NAME% -dns-server 223.5.5.5.5,114.114.114.114 -netspeed full -netdelay none

:: 等待设备
echo 等待 AVD 连接...
:wait
"%ADB%" devices | findstr "emulator" >nul
if errorlevel 1 (
    timeout /t 2 /nobreak >nul
    goto wait
)

:: 清理代理
echo 清理代理...
"%ADB%" -e shell settings put global http_proxy :0
"%ADB%" -e shell settings delete global http_proxy 2>nul

:: 端口转发（只发给模拟器，不会冲突）
echo 端口转发 %PORT%...
"%ADB%" -e forward tcp:%PORT% tcp:%PORT%

echo.
echo ======================================================
echo                    启动成功
echo.
echo  物理机信令地址：
echo      ws://%IP%:%PORT%
echo.
echo ======================================================
echo.

pause