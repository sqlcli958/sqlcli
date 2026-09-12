#!/usr/bin/env bash
# sql-cli 启动脚本（macOS / Linux）
# Java 查找顺序: 配置文件 javaHome > JAVA_HOME > 安装目录 jre > PATH

set -euo pipefail

SCRIPT_PATH="$0"
while [ -L "$SCRIPT_PATH" ]; do
  SCRIPT_PATH=$(readlink "$SCRIPT_PATH")
done
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$SCRIPT_PATH")" && pwd)

JAR_PATH="$SCRIPT_DIR/sql-cli.jar"
CONFIG_FILE="$SCRIPT_DIR/config/settings.yaml"
INSTALL_DIR="${SQLCLI_INSTALL_DIR:-$HOME/.sql-cli}"
LOCAL_JRE="$INSTALL_DIR/jre/bin/java"

if [ ! -f "$JAR_PATH" ]; then
  echo "错误: 找不到 $JAR_PATH" >&2
  exit 1
fi

# 从配置文件读取 javaHome（简易解析，取第一个非注释非空的 javaHome 值）
read_config_java_home() {
  if [ ! -f "$CONFIG_FILE" ]; then
    return 1
  fi
  local value
  value=$(sed -n -E 's/^[[:space:]]*javaHome:[[:space:]]*"?([^"#]*)"?.*/\1/p' "$CONFIG_FILE" | head -1)
  value="${value%"${value##*[![:space:]]}"}"
  # 跳过空值和相对路径（如 "."）
  if [ -n "$value" ] && [[ "$value" == /* ]] && [ -d "$value" ]; then
    echo "$value"
    return 0
  fi
  return 1
}

# 查找 Java（优先级: 配置文件 > JAVA_HOME > 本地 JRE > PATH）
CONFIG_JAVA_HOME=""
if CONFIG_JAVA_HOME=$(read_config_java_home); then
  JAVA_BIN="$CONFIG_JAVA_HOME/bin/java"
elif [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
  JAVA_BIN="${JAVA_HOME}/bin/java"
elif [ -x "$LOCAL_JRE" ]; then
  JAVA_BIN="$LOCAL_JRE"
else
  JAVA_BIN="java"
fi

# Debug 模式
JAVA_OPTS=""
for arg in "$@"; do
  if [ "$arg" = "-d" ] || [ "$arg" = "--debug" ]; then
    JAVA_OPTS="-DSQLCLI_DEBUG=DEBUG"
    break
  fi
done

# 代理白名单
NON_PROXY_HOSTS="${SQLCLI_NON_PROXY_HOSTS:-172.100.1.*}"
JAVA_OPTS="$JAVA_OPTS -Dhttp.nonProxyHosts=$NON_PROXY_HOSTS -DsocksNonProxyHosts=$NON_PROXY_HOSTS"

# 切换到脚本目录，确保 config/ 相对路径生效
cd "$SCRIPT_DIR"
exec "$JAVA_BIN" $JAVA_OPTS -jar "$JAR_PATH" "$@"
