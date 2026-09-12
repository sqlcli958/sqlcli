@echo off
setlocal enabledelayedexpansion

:: sql-cli installer for Windows.
:: Re-running this script updates program files and keeps existing user config.

if not defined SQLCLI_VERSION set "SQLCLI_VERSION=1.0.0"
set "VERSION=%SQLCLI_VERSION%"
if not defined SQLCLI_INSTALL_DIR set "SQLCLI_INSTALL_DIR=%USERPROFILE%\.sql-cli"
set "INSTALL_DIR=%SQLCLI_INSTALL_DIR%"
set "JRE_DIR=%INSTALL_DIR%\jre"
set "VERSION_FILE=%INSTALL_DIR%\VERSION"
set "ADOPTIUM_API=https://api.adoptium.net/v3/binary/latest/17/ga"
set "SCRIPT_DIR=%~dp0"

echo.
echo === sql-cli 安装程序 v%VERSION% ===

set "CURRENT_VERSION="
if exist "%VERSION_FILE%" set /p CURRENT_VERSION=<"%VERSION_FILE%"
if not defined CURRENT_VERSION (
    echo [INFO] 执行首次安装
) else if "%CURRENT_VERSION%"=="%VERSION%" (
    echo [INFO] 检测到已安装 v%CURRENT_VERSION%，将重新安装当前版本
) else (
    echo [INFO] 检测到已安装 v%CURRENT_VERSION%，将更新到 v%VERSION%
)
echo.

echo [INFO] 检测 Java 17+ 环境...
set "JAVA_CMD="

if defined JAVA_HOME call :try_java "%JAVA_HOME%\bin\java.exe"
if not defined JAVA_CMD call :try_java "%JRE_DIR%\bin\java.exe"
if not defined JAVA_CMD call :try_java "java"

if not defined JAVA_CMD (
    echo [WARN] 未找到 Java 17+，将安装内置 JRE
    call :download_jre || exit /b 1
    set "JAVA_CMD=%JRE_DIR%\bin\java.exe"
) else (
    echo [INFO] 已找到 Java: !JAVA_CMD!
)

call :install_files || exit /b 1
call :verify_install
call :print_summary

endlocal
exit /b 0

:try_java
set "CANDIDATE=%~1"
if not defined CANDIDATE exit /b 1
if /i not "%CANDIDATE%"=="java" (
    if not exist "%CANDIDATE%" exit /b 1
) else (
    where java >nul 2>&1 || exit /b 1
)

set "JMAJOR="
for /f "tokens=3" %%v in ('"%CANDIDATE%" -version 2^>^&1 ^| findstr /i "version"') do (
    set "JVER=%%v"
    set "JVER=!JVER:"=!"
    for /f "delims=." %%a in ("!JVER!") do set "JMAJOR=%%a"
)
if not defined JMAJOR exit /b 1
if !JMAJOR! GEQ 17 (
    set "JAVA_CMD=%CANDIDATE%"
    exit /b 0
)
exit /b 1

:download_jre
set "URL=%ADOPTIUM_API%/windows/x64/jre/hotspot/normal/eclipse"
set "TMP_FILE=%TEMP%\sql-cli-jre.zip"
set "TMP_DIR=%TEMP%\sql-cli-jre-extract"

echo [INFO] 下载地址: %URL%
if not exist "%INSTALL_DIR%" mkdir "%INSTALL_DIR%"
if exist "%TMP_FILE%" del /f "%TMP_FILE%" >nul 2>&1
if exist "%TMP_DIR%" rmdir /s /q "%TMP_DIR%"
mkdir "%TMP_DIR%"

where curl >nul 2>&1
if %errorlevel% EQU 0 (
    curl -fSL --progress-bar -o "%TMP_FILE%" "%URL%"
) else (
    powershell -NoProfile -Command "Invoke-WebRequest -Uri '%URL%' -OutFile '%TMP_FILE%'"
)
if %errorlevel% NEQ 0 (
    echo [ERROR] JRE 下载失败，请检查网络连接
    echo [ERROR] 也可以手动安装 Java 17+: https://adoptium.net/
    exit /b 1
)

powershell -NoProfile -Command "Expand-Archive -Path '%TMP_FILE%' -DestinationPath '%TMP_DIR%' -Force"
if %errorlevel% NEQ 0 (
    echo [ERROR] JRE 解压失败
    exit /b 1
)
del /f "%TMP_FILE%" >nul 2>&1

set "FOUND_JRE="
for /d /r "%TMP_DIR%" %%d in (*) do (
    if not defined FOUND_JRE if exist "%%d\bin\java.exe" set "FOUND_JRE=%%d"
)
if not defined FOUND_JRE (
    echo [ERROR] 无法识别 JRE 解压结构
    rmdir /s /q "%TMP_DIR%"
    exit /b 1
)

if exist "%JRE_DIR%" rmdir /s /q "%JRE_DIR%"
mkdir "%JRE_DIR%"
xcopy "%FOUND_JRE%\*" "%JRE_DIR%\" /e /i /y >nul
rmdir /s /q "%TMP_DIR%"

if not exist "%JRE_DIR%\bin\java.exe" (
    echo [ERROR] JRE 安装失败: %JRE_DIR%\bin\java.exe 不存在
    exit /b 1
)
echo [INFO] JRE 17 已安装到 %JRE_DIR%
exit /b 0

:install_files
if not exist "%SCRIPT_DIR%sql-cli.jar" (
    echo [ERROR] 找不到 %SCRIPT_DIR%sql-cli.jar
    exit /b 1
)
if not exist "%SCRIPT_DIR%sql-cli.bat" (
    echo [ERROR] 找不到 %SCRIPT_DIR%sql-cli.bat
    exit /b 1
)

if not exist "%INSTALL_DIR%" mkdir "%INSTALL_DIR%"
copy /y "%SCRIPT_DIR%sql-cli.jar" "%INSTALL_DIR%\sql-cli.jar" >nul
copy /y "%SCRIPT_DIR%sql-cli.bat" "%INSTALL_DIR%\sql-cli.bat" >nul

if exist "%SCRIPT_DIR%drivers" xcopy "%SCRIPT_DIR%drivers\*" "%INSTALL_DIR%\drivers\" /e /i /y >nul
if exist "%SCRIPT_DIR%skills" xcopy "%SCRIPT_DIR%skills\*" "%INSTALL_DIR%\skills\" /e /i /y >nul
call :install_config_if_missing

>"%VERSION_FILE%" echo %VERSION%
echo [INFO] 程序文件已安装到 %INSTALL_DIR%
exit /b 0

:install_config_if_missing
set "CONFIG_DIR=%INSTALL_DIR%\config"
if not exist "%CONFIG_DIR%" mkdir "%CONFIG_DIR%"

if not exist "%CONFIG_DIR%\settings.yaml" (
    if exist "%SCRIPT_DIR%config\settings.yaml" (
        copy /y "%SCRIPT_DIR%config\settings.yaml" "%CONFIG_DIR%\settings.yaml" >nul
        echo [INFO] 已安装默认 settings.yaml
    )
) else (
    echo [INFO] 保留已有 settings.yaml
)

if not exist "%CONFIG_DIR%\aliases.yaml" (
    if exist "%SCRIPT_DIR%config\aliases.yaml" (
        copy /y "%SCRIPT_DIR%config\aliases.yaml" "%CONFIG_DIR%\aliases.yaml" >nul
        echo [INFO] 已安装默认 aliases.yaml
    )
) else (
    echo [INFO] 保留已有 aliases.yaml
)
exit /b 0

:verify_install
echo.
echo [INFO] 验证安装...
set "INSTALLED_VER="
for /f "tokens=*" %%v in ('"%INSTALL_DIR%\sql-cli.bat" --version 2^>nul') do set "INSTALLED_VER=%%v"
if defined INSTALLED_VER (
    echo [INFO] sql-cli v%VERSION% 安装成功（程序自检通过）
) else (
    echo [WARN] 验证失败，请检查 Java 环境: %JAVA_CMD%
)
exit /b 0

:print_summary
echo.
echo ========================================
echo [INFO] 安装目录: %INSTALL_DIR%
echo [INFO] 当前版本: %VERSION%
echo [INFO] 配置目录: %INSTALL_DIR%\config\
echo [INFO] 驱动目录: %INSTALL_DIR%\drivers\
echo.
echo [INFO] 使用方式:
echo   %INSTALL_DIR%\sql-cli.bat list
echo   %INSTALL_DIR%\sql-cli.bat ^<alias^> "SELECT 1"
echo   %INSTALL_DIR%\sql-cli.bat --version
echo.
echo [INFO] 添加到 PATH（可选）:
echo   setx PATH "%%PATH%%;%INSTALL_DIR%"
echo.
echo [INFO] 配置别名: 编辑 %INSTALL_DIR%\config\aliases.yaml
echo ========================================
echo.
exit /b 0
