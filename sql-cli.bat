@echo off
setlocal enabledelayedexpansion

:: Resolve script directory
set SCRIPT_DIR=%~dp0
set JAR_PATH=%SCRIPT_DIR%target\sql-cli.jar

:: Check if jar exists
if not exist "%JAR_PATH%" (
    echo sql-cli jar not found: %JAR_PATH%
    echo Run: mvn -q -DskipTests package
    exit /b 1
)

:: Set JAVA_HOME if available
if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\java.exe" (
        set JAVA_BIN=%JAVA_HOME%\bin\java.exe
    ) else (
        set JAVA_BIN=java
    )
) else (
    set JAVA_BIN=java
)

:: Check for debug flag
set JAVA_OPTS=
for %%a in (%*) do (
    if "%%a"=="-d" set JAVA_OPTS=-DSQLCLI_DEBUG=DEBUG
    if "%%a"=="--debug" set JAVA_OPTS=-DSQLCLI_DEBUG=DEBUG
)

:: 跳过代理的 IP 地址列表（Java 使用 | 分隔，支持 * 通配符）
:: 用于 Clash Verge 等本地代理软件
:: 默认包含 172.100.1.x（公网 IP，不在 Java 默认私有 IP 范围内）
if not defined SQLCLI_NON_PROXY_HOSTS set SQLCLI_NON_PROXY_HOSTS=172.100.1.*
set JAVA_OPTS=%JAVA_OPTS% -Dhttp.nonProxyHosts=%SQLCLI_NON_PROXY_HOSTS% -DsocksNonProxyHosts=%SQLCLI_NON_PROXY_HOSTS%

:: 配置与驱动都按 ~/.sql-cli 解析（SettingsConfig.configRoot），跟当前目录无关。
:: 这里仍然 cd 一下只是为了让相对路径的 --file / --output 有个稳定基准。
cd /d "%SCRIPT_DIR%"

:: Execute java
"%JAVA_BIN%" %JAVA_OPTS% -jar "%JAR_PATH%" %*