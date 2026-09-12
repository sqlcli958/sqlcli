#!/usr/bin/env bash
# sql-cli 分发包构建脚本
# 用法: ./build-dist.sh
# 产物: dist/sql-cli-{version}.zip
#
# 版本管理：
#   pom.xml 是唯一版本源（<version>X.Y.Z-SNAPSHOT</version>）
#   打包时自动从 pom.xml 读取版本号并注入到安装脚本中

set -euo pipefail

# sh/ 目录在项目根目录下，定位到上级作为项目根
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_DIR"

VERSION=$(sed -n 's/.*<version>\([^<]*\)<\/version>.*/\1/p' pom.xml | head -1 | cut -d'-' -f1)
DIST_DIR="$PROJECT_DIR/dist"
STAGING="$DIST_DIR/sql-cli-$VERSION"

echo "=== 构建 sql-cli v${VERSION} 分发包 ==="

# Step 1: 构建 JAR。发布包默认重新构建，避免新版本打进旧 jar。
if [ "${SQLCLI_SKIP_BUILD:-false}" = "true" ]; then
    echo "[WARN] SQLCLI_SKIP_BUILD=true，跳过 Maven 构建"
else
    echo "[INFO] 构建 JAR..."
    mvn -q -DskipTests package
fi

if [ ! -f "target/sql-cli.jar" ]; then
    echo "[ERROR] 找不到 target/sql-cli.jar"
    exit 1
fi

# Step 2: 清理并准备 staging 目录
rm -rf "$STAGING"
mkdir -p "$STAGING"

# Step 3: 复制文件并注入版本号
SCRIPT_DIR="$PROJECT_DIR/sh"

cp target/sql-cli.jar "$STAGING/"
cp "$SCRIPT_DIR/sql-cli.sh" "$STAGING/"
cp "$SCRIPT_DIR/sql-cli.bat" "$STAGING/"
printf '%s\n' "$VERSION" > "$STAGING/VERSION"

# 将版本号注入安装脚本
sed "s/\${SQLCLI_VERSION:-1.0.0}/$VERSION/" "$SCRIPT_DIR/install.sh" > "$STAGING/install.sh"
sed "s/set \"SQLCLI_VERSION=1.0.0\"/set \"SQLCLI_VERSION=$VERSION\"/" "$SCRIPT_DIR/install.bat" > "$STAGING/install.bat"
chmod +x "$STAGING/install.sh"

# Step 4: 复制 config 示例配置
if [ -d "$SCRIPT_DIR/config" ]; then
    cp -R "$SCRIPT_DIR/config" "$STAGING/"
fi

# Step 5: 复制 drivers（可选）
if [ -d "drivers" ]; then
    cp -R drivers "$STAGING/"
fi

# Step 6: 复制 skills（可选）
if [ -d "skills" ]; then
    cp -R skills "$STAGING/"
    find "$STAGING/skills" -name ".DS_Store" -delete 2>/dev/null || true
fi

# Step 7: 设置权限
chmod +x "$STAGING/sql-cli.sh" "$STAGING/install.sh"

# Step 8: 打包 zip
ZIP_FILE="$DIST_DIR/sql-cli-${VERSION}.zip"
rm -f "$ZIP_FILE"
find "$STAGING" -name ".DS_Store" -delete 2>/dev/null || true
cd "$DIST_DIR"
zip -r "sql-cli-${VERSION}.zip" "sql-cli-${VERSION}/" -x "*.DS_Store"
cd "$PROJECT_DIR"

# Step 9: 清理 staging
rm -rf "$STAGING"

echo ""
echo "[INFO] 分发包已生成: $ZIP_FILE"
echo "[INFO] 大小: $(du -h "$ZIP_FILE" | cut -f1)"
echo ""
echo "版本发布流程:"
echo "  1. 修改 pom.xml 中的 <version>X.Y.Z</version>"
echo "  2. 运行 ./build-dist.sh"
echo "  3. 分发 dist/sql-cli-X.Y.Z.zip"
echo ""
echo "用户安装:"
echo "  macOS/Linux: unzip && cd sql-cli-$VERSION && ./install.sh"
echo "  Windows:     解压 && 进入目录 && install.bat"
