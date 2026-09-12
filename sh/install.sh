#!/usr/bin/env bash
# sql-cli installer for macOS / Linux.
# Re-running this script updates program files and keeps existing user config.

set -euo pipefail

VERSION="${SQLCLI_VERSION:-1.0.0}"
INSTALL_DIR="${SQLCLI_INSTALL_DIR:-$HOME/.sql-cli}"
JRE_DIR="$INSTALL_DIR/jre"
VERSION_FILE="$INSTALL_DIR/VERSION"
ADOPTIUM_API="https://api.adoptium.net/v3/binary/latest/17/ga"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

info() { printf "${GREEN}[INFO]${NC} %s\n" "$1"; }
warn() { printf "${YELLOW}[WARN]${NC} %s\n" "$1"; }
error() { printf "${RED}[ERROR]${NC} %s\n" "$1" >&2; }

script_dir() {
    local path="$0"
    while [ -L "$path" ]; do
        local target
        target="$(readlink "$path")"
        case "$target" in
            /*) path="$target" ;;
            *) path="$(dirname "$path")/$target" ;;
        esac
    done
    cd "$(dirname "$path")" && pwd
}

java_major_version() {
    local java_cmd="$1"
    if [[ "$java_cmd" == /* ]]; then
        [ -x "$java_cmd" ] || { echo 0; return; }
    else
        command -v "$java_cmd" >/dev/null 2>&1 || { echo 0; return; }
    fi

    local first_line version
    first_line="$("$java_cmd" -version 2>&1 | head -1 || true)"
    version="$(printf '%s\n' "$first_line" | sed -E 's/.*"([0-9]+)(\.[0-9]+)*.*/\1/')"
    echo "${version:-0}"
}

use_java_if_supported() {
    local java_cmd="$1"
    local version
    version="$(java_major_version "$java_cmd")"
    if [ "$version" -ge 17 ]; then
        echo "$java_cmd"
        return 0
    fi
    return 1
}

configured_java_home() {
    local settings="$INSTALL_DIR/config/settings.yaml"
    [ -f "$settings" ] || return 1

    local value
    value="$(sed -n -E 's/^[[:space:]]*javaHome:[[:space:]]*"?([^"#]*)"?.*/\1/p' "$settings" | head -1)"
    value="${value%"${value##*[![:space:]]}"}"
    [ -n "$value" ] && [[ "$value" == /* ]] && [ -x "$value/bin/java" ] || return 1
    echo "$value/bin/java"
}

detect_java() {
    local candidate
    for candidate in \
        "$(configured_java_home || true)" \
        "${JAVA_HOME:+$JAVA_HOME/bin/java}" \
        "$JRE_DIR/bin/java" \
        "java"; do
        [ -n "$candidate" ] || continue
        use_java_if_supported "$candidate" && return 0
    done
    return 1
}

adoptium_platform() {
    local os arch
    os="$(uname -s)"
    arch="$(uname -m)"

    case "$os:$arch" in
        Darwin:x86_64) echo "mac/x64/tar.gz" ;;
        Darwin:arm64) echo "mac/aarch64/tar.gz" ;;
        Linux:x86_64) echo "linux/x64/tar.gz" ;;
        Linux:aarch64) echo "linux/aarch64/tar.gz" ;;
        Darwin:*) error "不支持的 macOS 架构: $arch"; exit 1 ;;
        Linux:*) error "不支持的 Linux 架构: $arch"; exit 1 ;;
        *) error "不支持的操作系统: $os（Windows 请使用 install.bat）"; exit 1 ;;
    esac
}

download_jre() {
    local platform os arch url tmp_file tmp_dir extracted_root
    platform="$(adoptium_platform)"
    os="${platform%%/*}"
    arch="$(printf '%s' "$platform" | cut -d/ -f2)"
    url="${ADOPTIUM_API}/${os}/${arch}/jre/hotspot/normal/eclipse"

    info "正在从 Adoptium 下载 JRE 17..."
    info "下载地址: $url"

    tmp_file="$(mktemp /tmp/sql-cli-jre.XXXXXX)"
    tmp_dir="$(mktemp -d /tmp/sql-cli-jre-extract.XXXXXX)"
    trap 'rm -f "$tmp_file"; rm -rf "$tmp_dir"' RETURN

    if ! curl -fSL --progress-bar -o "$tmp_file" "$url"; then
        error "JRE 下载失败，请检查网络连接"
        error "也可以手动安装 Java 17+: https://adoptium.net/"
        exit 1
    fi

    tar -xzf "$tmp_file" -C "$tmp_dir"
    extracted_root="$(find "$tmp_dir" -mindepth 1 -maxdepth 1 -type d | head -1)"

    rm -rf "$JRE_DIR"
    mkdir -p "$JRE_DIR"
    if [ -d "$extracted_root/Contents/Home/bin" ]; then
        cp -R "$extracted_root/Contents/Home/." "$JRE_DIR/"
    elif [ -d "$extracted_root/bin" ]; then
        cp -R "$extracted_root/." "$JRE_DIR/"
    else
        error "无法识别 JRE 解压结构"
        exit 1
    fi

    [ -x "$JRE_DIR/bin/java" ] || { error "JRE 安装失败: $JRE_DIR/bin/java 不存在"; exit 1; }
    info "JRE 17 已安装到 $JRE_DIR"
}

install_config_if_missing() {
    local src_dir="$1/config"
    local dst_dir="$INSTALL_DIR/config"
    mkdir -p "$dst_dir"

    if [ -f "$src_dir/settings.yaml" ] && [ ! -f "$dst_dir/settings.yaml" ]; then
        cp "$src_dir/settings.yaml" "$dst_dir/settings.yaml"
        info "已安装默认 settings.yaml"
    else
        info "保留已有 settings.yaml"
    fi

    if [ -f "$src_dir/aliases.yaml" ] && [ ! -f "$dst_dir/aliases.yaml" ]; then
        cp "$src_dir/aliases.yaml" "$dst_dir/aliases.yaml"
        info "已安装默认 aliases.yaml"
    elif [ -f "$dst_dir/aliases.yaml" ]; then
        info "保留已有 aliases.yaml"
    fi
}

copy_dir_contents() {
    local src="$1"
    local dst="$2"
    [ -d "$src" ] || return 0
    mkdir -p "$dst"
    cp -R "$src/." "$dst/"
}

install_files() {
    local src_dir="$1"
    [ -f "$src_dir/sql-cli.jar" ] || { error "找不到 $src_dir/sql-cli.jar"; exit 1; }
    [ -f "$src_dir/sql-cli.sh" ] || { error "找不到 $src_dir/sql-cli.sh"; exit 1; }

    mkdir -p "$INSTALL_DIR"
    cp "$src_dir/sql-cli.jar" "$INSTALL_DIR/sql-cli.jar"
    cp "$src_dir/sql-cli.sh" "$INSTALL_DIR/sql-cli.sh"
    chmod +x "$INSTALL_DIR/sql-cli.sh"

    copy_dir_contents "$src_dir/drivers" "$INSTALL_DIR/drivers"
    copy_dir_contents "$src_dir/skills" "$INSTALL_DIR/skills"
    install_config_if_missing "$src_dir"

    printf '%s\n' "$VERSION" > "$VERSION_FILE"
    info "程序文件已安装到 $INSTALL_DIR"
}

create_command_link() {
    local link="/usr/local/bin/sql-cli"
    local target="$INSTALL_DIR/sql-cli.sh"

    if [ -w "$(dirname "$link")" ] || [ -L "$link" ]; then
        ln -sf "$target" "$link" 2>/dev/null && {
            info "命令入口已更新: $link"
            return
        }
    fi

    warn "未自动写入 $link"
    warn "可将以下目录加入 PATH: $INSTALL_DIR"
    warn "或手动执行: sudo ln -sf $target $link"
}

installed_version() {
    [ -f "$VERSION_FILE" ] && head -1 "$VERSION_FILE" || true
}

install_mode() {
    local current="$1"
    if [ -z "$current" ]; then
        echo "install"
    elif [ "$current" = "$VERSION" ]; then
        echo "reinstall"
    else
        echo "update"
    fi
}

main() {
    local src_dir current mode java_cmd
    src_dir="$(script_dir)"
    current="$(installed_version)"
    mode="$(install_mode "$current")"

    echo ""
    printf "${CYAN}=== sql-cli 安装程序 v%s ===${NC}\n" "$VERSION"
    case "$mode" in
        install) info "执行首次安装" ;;
        reinstall) info "检测到已安装 v${current}，将重新安装当前版本" ;;
        update) info "检测到已安装 v${current}，将更新到 v${VERSION}" ;;
    esac
    echo ""

    info "检测 Java 17+ 环境..."
    if java_cmd="$(detect_java)"; then
        info "已找到 Java: $java_cmd"
    else
        warn "未找到 Java 17+，将安装内置 JRE"
        mkdir -p "$INSTALL_DIR"
        download_jre
        java_cmd="$JRE_DIR/bin/java"
    fi

    install_files "$src_dir"
    create_command_link

    echo ""
    info "验证安装..."
    if "$INSTALL_DIR/sql-cli.sh" --version >/dev/null 2>&1; then
        info "sql-cli v$(installed_version) 安装成功（程序自检通过）"
    else
        warn "验证失败，请检查 Java 环境: $java_cmd"
    fi

    echo ""
    echo "========================================"
    info "安装目录: $INSTALL_DIR"
    info "当前版本: $(installed_version)"
    info "配置目录: $INSTALL_DIR/config/"
    info "驱动目录: $INSTALL_DIR/drivers/"
    echo ""
    info "使用方式:"
    echo "  sql-cli list"
    echo "  sql-cli <alias> \"SELECT 1\""
    echo "  sql-cli --version"
    echo ""
    info "配置别名: 编辑 $INSTALL_DIR/config/aliases.yaml"
    echo "========================================"
    echo ""
}

main "$@"
