# 改完代码 → 打包 → 重启 Web UI，供人立刻在浏览器里验证。
#
# 为什么要打包而不是只改文件：web/ 的产物是**打进 jar** 的，
# 只改 web/ 不重新 package，页面上什么都不会变。
#
# 用法： powershell -ExecutionPolicy Bypass -File dev-restart.ps1 [-Port 9999] [-SkipBuild]
param(
  [int]$Port = 9999,
  [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$jar = Join-Path $root 'target\sql-cli.jar'

if (-not $SkipBuild) {
  Write-Output '打包中…'
  # 实测：UI 在跑的时候也能打包，jar 没有被独占锁住，所以先打包再杀进程，
  # 这样构建失败时旧服务还活着，人不至于对着一个关掉的页面等
  & mvn -q -DskipTests package
  if ($LASTEXITCODE -ne 0) { throw '打包失败，旧服务保持不动' }
}

Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
  Where-Object { $_.CommandLine -like '*sql-cli.jar*' -and $_.CommandLine -like '*ui*' } |
  ForEach-Object {
    Write-Output "停掉旧服务 PID $($_.ProcessId)"
    Stop-Process -Id $_.ProcessId -Force
  }

# --no-open：不加的话每次重启都弹一个新浏览器标签，改十次就有十个标签
# 走全局启动器，不要自己拼 java 命令行：它带着代理排除这些 JAVA_OPTS，
# 自己拼会悄悄丢掉。启动器内部已经指向 target/sql-cli.jar，用的就是刚打包出来的这份。
#
# 配置和驱动的解析基准是 ~/.sql-cli，跟工作目录无关（SettingsConfig.configRoot）。
# 这条是修出来的：原来默认按当前目录解析，这个脚本用 Start-Process 继承了仓库根，
# 服务就去 D:\project\sql-cli\config\ 找图谱，报「图谱工作区不存在」，
# 还在仓库里拉出一个空的 config/schema-graphs。
$launcher = Join-Path $env:USERPROFILE '.sql-cli\sql-cli.bat'
if (-not (Test-Path $launcher)) { throw "找不到启动器 $launcher" }
Start-Process -FilePath $launcher -ArgumentList 'ui', '--no-open', '--port', $Port -WindowStyle Hidden

# 起来了才算数：直接返回会让人对着一个还没监听的端口刷新
for ($i = 0; $i -lt 30; $i++) {
  Start-Sleep -Milliseconds 500
  if (Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue) {
    Write-Output "已重启：http://127.0.0.1:$Port  （浏览器刷新即可）"
    exit 0
  }
}
throw "服务在 15 秒内没有监听 $Port，去看窗口输出"
