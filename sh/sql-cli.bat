@echo off
setlocal enabledelayedexpansion

:: sql-cli 启动脚本（Windows）
:: Java 查找顺序: 配置文件 javaHome > JAVA_HOME > 安装目录 jre > PATH

set "SCRIPT_DIR=%~dp0"
set "JAR_PATH=%SCRIPT_DIR%sql-cli.jar"
set "CONFIG_FILE=%SCRIPT_DIR%config\settings.yaml"
if not defined SQLCLI_INSTALL_DIR set "SQLCLI_INSTALL_DIR=%USERPROFILE%\.sql-cli"
set "INSTALL_DIR=%SQLCLI_INSTALL_DIR%"
set "LOCAL_JRE=%INSTALL_DIR%\jre\bin\java.exe"

if not exist "%JAR_PATH%" (
    echo 错误: 找不到 %JAR_PATH%
    exit /b 1
)

:: 查找 Java
set "JAVA_BIN="

:: 策略 1: 从配置文件读取 javaHome
if exist "%CONFIG_FILE%" (
    for /f "delims=" %%l in ('findstr /r /c:"^[ ]*javaHome:" "%CONFIG_FILE%"') do (
        set "CFG_LINE=%%l"
        set "CFG_JAVA_HOME=!CFG_LINE:*javaHome:=!"
        for /f "tokens=*" %%v in ("!CFG_JAVA_HOME!") do set "CFG_JAVA_HOME=%%v"
        if defined CFG_JAVA_HOME (
            if exist "!CFG_JAVA_HOME!\bin\java.exe" (
                set "JAVA_BIN=!CFG_JAVA_HOME!\bin\java.exe"
            )
        )
    )
)

:: 策略 2: 系统 JAVA_HOME
if not defined JAVA_BIN (
    if defined JAVA_HOME (
        if exist "%JAVA_HOME%\bin\java.exe" (
            set "JAVA_BIN=%JAVA_HOME%\bin\java.exe"
        )
    )
)

:: 策略 3: 本地 JRE
if not defined JAVA_BIN (
    if exist "%LOCAL_JRE%" (
        set "JAVA_BIN=%LOCAL_JRE%"
    )
)

:: 策略 4: PATH
if not defined JAVA_BIN (
    set "JAVA_BIN=java"
)

:: Debug 模式
set "JAVA_OPTS="
for %%a in (%*) do (
    if "%%a"=="-d" set "JAVA_OPTS=-DSQLCLI_DEBUG=DEBUG"
    if "%%a"=="--debug" set "JAVA_OPTS=-DSQLCLI_DEBUG=DEBUG"
)

:: 代理白名单
if not defined SQLCLI_NON_PROXY_HOSTS set "SQLCLI_NON_PROXY_HOSTS=172.100.1.*"
set "JAVA_OPTS=%JAVA_OPTS% -Dhttp.nonProxyHosts=%SQLCLI_NON_PROXY_HOSTS% -DsocksNonProxyHosts=%SQLCLI_NON_PROXY_HOSTS%"

:: 切换到脚本目录，确保 config\ 相对路径生效
cd /d "%SCRIPT_DIR%"

:: 执行
"%JAVA_BIN%" %JAVA_OPTS% -jar "%JAR_PATH%" %*
