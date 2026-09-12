# sql-cli 分发与安装指南

## 概述

sql-cli 提供跨平台分发方案，支持 macOS、Linux 和 Windows。用户拿到一个 zip 包，运行安装脚本即可使用。安装脚本自动检测 Java 环境，缺少时自动下载 Adoptium JRE 17。

## 构建分发包

```bash
# 1. 确保 JAR 已构建
mvn -q -DskipTests package

# 2. 运行打包脚本
./sh/build-dist.sh
```

产物：`dist/sql-cli-1.0.0.zip`（约 35MB）

## 分发包结构

```
sql-cli-1.0.0/
├── install.sh           # macOS/Linux 安装脚本
├── install.bat          # Windows 安装脚本
├── sql-cli.sh           # macOS/Linux 启动脚本（安装后保留）
├── sql-cli.bat          # Windows 启动脚本（安装后保留）
├── sql-cli.jar          # 应用 JAR（35MB）
└── drivers/             # JDBC 驱动（可选）
    ├── mysql/
    ├── oracle/
    └── postgresql/
```

## 用户安装

### macOS / Linux

```bash
# 解压
unzip sql-cli-1.0.0.zip
cd sql-cli-1.0.0

# 运行安装脚本
./install.sh
```

安装脚本会：
1. 检测系统是否有 Java 17+（检查 JAVA_HOME 和 PATH）
2. 如果没有，自动从 [Adoptium](https://adoptium.net/) 下载 JRE 17 到 `~/.sql-cli/jre/`
3. 安装文件到 `~/.sql-cli/`
4. 生成配置文件模板
5. 创建 `/usr/local/bin/sql-cli` 软链接

安装后：

```bash
sql-cli --version
sql-cli list
sql-cli <alias> "SELECT 1"
```

### Windows

```cmd
:: 解压后进入目录
cd sql-cli-1.0.0

:: 运行安装脚本（双击 install.bat 或命令行运行）
install.bat
```

安装脚本会：
1. 检测系统 Java 17+（检查 JAVA_HOME、PATH）
2. 如果没有，自动从 Adoptium 下载 JRE 17 到 `%USERPROFILE%\.sql-cli\jre\`
3. 安装文件到 `%USERPROFILE%\.sql-cli\`
4. 生成配置文件模板

安装后：

```cmd
%USERPROFILE%\.sql-cli\sql-cli.bat --version
%USERPROFILE%\.sql-cli\sql-cli.bat list
```

如需全局使用，手动添加到 PATH：

```cmd
setx PATH "%PATH%;%USERPROFILE%\.sql-cli"
```

## 安装后目录布局

```
~/.sql-cli/
├── jre/                 # 自动下载的 JRE（仅在系统无 Java 时）
│   ├── bin/
│   └── lib/
├── sql-cli.jar          # 应用 JAR
├── sql-cli.sh           # macOS/Linux 启动脚本
├── sql-cli.bat          # Windows 启动脚本
├── config/
│   ├── settings.yaml    # 驱动配置
│   └── aliases.yaml     # 数据库别名
└── drivers/             # JDBC 驱动目录（用户自行放入）
    ├── mysql/
    ├── oracle/
    └── postgresql/
```

## Java 环境查找逻辑

启动脚本按以下优先级查找 Java：

1. `JAVA_HOME` 环境变量
2. `~/.sql-cli/jre/bin/java`（安装脚本自动下载的）
3. PATH 中的 `java` 命令

用户安装过 JDK 17+ 的情况下不会下载额外的 JRE。

## 配置数据库别名

编辑 `~/.sql-cli/config/aliases.yaml`：

```yaml
aliases:
  my-db:
    url: "jdbc:mysql://localhost:3306/mydb"
    driverRef: mysql8
    username: root
    secretRef: "env:MY_DB_PASSWORD"
    description: "本地 MySQL"

  prod-oracle:
    url: "jdbc:oracle:thin:@//10.0.0.1:1521/orcl"
    driverRef: oracle6
    username: readonly
    secretRef: "env:ORACLE_PASSWORD"
    description: "生产 Oracle 只读库"
```

## JDBC 驱动获取与校验

分发包不带各厂商的 JDBC 驱动（License 限制，尤其 Oracle），`drivers/` 目录下的 jar 由用户
自行下载放入（见上面《安装后目录布局》）。Web UI 设置页的「上传 jar」只是把文件字节写到
这个目录，同样建议上传后手动记一次 SHA256——上传接口只校验大小上限和 zip 文件头，
不做来源校验。

**去哪下载**：

| dbType | 来源 | 当前配置里的示例 jar |
|---|---|---|
| mysql | Maven Central（`mysql:mysql-connector-j`） | `mysql-connector-j-8.3.0.jar` |
| postgresql | Maven Central（`org.postgresql:postgresql`） | `postgresql-42.7.1.jar` |
| clickhouse | Maven Central（`com.clickhouse:clickhouse-jdbc`） | `clickhouse-jdbc-0.6.3-all.jar` |
| oracle | Oracle 官方下载页（需登录）；`ojdbc8` 及以上版本也发布在 Maven Central（`com.oracle.database.jdbc:ojdbc8`），老版本 `ojdbc6` 没有 | `ojdbc8-19.22.jar` |
| sqlite | 不需要下载，`org.sqlite.JDBC` 已随 `sql-cli.jar` 一起打包 | — |

**记录 SHA256**：下载后立刻记一次校验和，换机器部署或怀疑 jar 被替换时能有凭有据地核对，
不需要额外脚本，一行命令：

```bash
# macOS / Linux
shasum -a 256 drivers/mysql/mysql-connector-j-8.3.0.jar >> drivers/CHECKSUMS.sha256
```

```powershell
# Windows
(Get-FileHash drivers\mysql\mysql-connector-j-8.3.0.jar -Algorithm SHA256).Hash `
  + "  mysql-connector-j-8.3.0.jar" | Add-Content drivers\CHECKSUMS.sha256
```

**校验**（同样一行）：

```bash
# macOS / Linux：批量核对 CHECKSUMS.sha256 里记录的每一个 jar
shasum -a 256 -c drivers/CHECKSUMS.sha256
```

```powershell
# Windows：逐个核对，跟 CHECKSUMS.sha256 里记的值比对
(Get-FileHash drivers\mysql\mysql-connector-j-8.3.0.jar -Algorithm SHA256).Hash
```

`CHECKSUMS.sha256` 跟着 `drivers/` 目录一起保管（连同发行包外发时可以一并带上），
不是分发包自带的东西——`drivers/` 本来就是运行时才补的，装完再补一份记录成本最低。

## 卸载

### macOS / Linux

```bash
rm -rf ~/.sql-cli
sudo rm -f /usr/local/bin/sql-cli
```

### Windows

```cmd
rmdir /s /q %USERPROFILE%\.sql-cli
:: 如果添加过 PATH，手动删除
```

## FAQ

**Q: 已有 JDK 17+，安装脚本会重复下载吗？**
A: 不会。安装脚本会优先检测 JAVA_HOME 和 PATH，已有 Java 17+ 则跳过下载。

**Q: JRE 下载失败怎么办？**
A: 检查网络连接。也可手动安装 Java 17+ 后重新运行安装脚本。JRE 从 Adoptium（Eclipse 官方）下载，免费开源。

**Q: 分发包里没有 JRE，为什么只有 35MB？**
A: JRE 在安装时按需下载（约 50MB），避免分发包过大。如果系统已有 Java 17+ 则完全不需要下载。

**Q: 支持哪些架构？**
A: macOS（x64、aarch64）、Linux（x64、aarch64）、Windows（x64）。安装脚本自动检测架构。

---

## macOS .pkg 打包（自 macos-pkg-packaging-guide 合并，2026-08-20）
### 概述

sql-cli 使用 macOS 自带的 `jpackage` 和 `pkgbuild` 工具，将 Java 应用打包为 `.pkg` 安装包。用户安装后可直接在终端使用 `sql-cli` 命令，无需安装 Java 运行时。

### 前置条件

- macOS 系统
- JDK 17+（`jpackage` 从 JDK 14 开始内置）
- 已构建的项目 JAR：`mvn -DskipTests package`

### 打包步骤

#### 第一步：生成 app-image

`jpackage` 将 JAR + JRE 打包为 macOS `.app` bundle，包含一个原生启动器。

```bash
mkdir -p dist/input
cp target/sql-cli.jar dist/input/

jpackage \
  --name "sql-cli" \
  --input dist/input \
  --main-jar sql-cli.jar \
  --main-class com.sqlcli.SqlCli \
  --type app-image \
  --dest dist/app-image \
  --app-version "1.0.0" \
  --vendor "sql-cli" \
  --java-options "-Xmx512m -Djava.awt.headless=true" \
  --description "Multi-database CLI tool"
```

产物：`dist/app-image/sql-cli.app/`（约 165MB）

**app-image 目录结构：**

```
sql-cli.app/
└── Contents/
    ├── MacOS/
    │   └── sql-cli          # 原生启动器（175KB），直接调用 JVM
    ├── app/
    │   ├── sql-cli.jar      # 应用 JAR（35MB）
    │   ├── sql-cli.cfg      # JVM 启动配置
    │   └── .jpackage.xml
    ├── runtime/             # 精简 JRE（130MB）
    │   └── Contents/
    │       ├── Home/lib/    # JVM 库文件
    │       └── MacOS/       # JNI 库
    └── Resources/
        └── sql-cli.icns     # 应用图标
```

**关键参数说明：**

| 参数 | 说明 |
|------|------|
| `--name` | 应用名称，决定 .app 和启动器名称 |
| `--input` | JAR 文件所在目录 |
| `--main-jar` | 主 JAR 文件名 |
| `--main-class` | 入口类 |
| `--type app-image` | 生成 .app bundle（非安装包） |
| `--java-options` | JVM 启动参数 |
| `--app-version` | 版本号 |

#### 第二步：构建 pkg 安装目录

创建符合 macOS 文件系统规范的安装目录结构，将 `.app` 放入 `/usr/local/lib/`，并在 `/usr/local/bin/` 创建命令行入口。

```bash
PKG_ROOT="dist/pkg-root"
rm -rf "$PKG_ROOT"
mkdir -p "$PKG_ROOT/usr/local/lib/sql-cli"
mkdir -p "$PKG_ROOT/usr/local/bin"

cp -R dist/app-image/sql-cli.app "$PKG_ROOT/usr/local/lib/sql-cli/"

cat > "$PKG_ROOT/usr/local/bin/sql-cli" << 'EOF'
#!/bin/bash
exec /usr/local/lib/sql-cli/sql-cli.app/Contents/MacOS/sql-cli "$@"
EOF
chmod 755 "$PKG_ROOT/usr/local/bin/sql-cli"
```

**安装目录结构：**

```
pkg-root/
└── usr/
    └── local/
        ├── bin/
        │   └── sql-cli          # wrapper 脚本 → 在 PATH 中
        └── lib/
            └── sql-cli/
                └── sql-cli.app/ # 完整应用
```

#### 第三步：打包为 pkg

使用 `pkgbuild` 将安装目录打包为 macOS 标准安装包。

```bash
pkgbuild \
  --root "$PKG_ROOT" \
  --identifier com.sqlcli.app \
  --version "1.0.0" \
  --install-location "/" \
  dist/sql-cli-1.0.0.pkg
```

产物：`dist/sql-cli-1.0.0.pkg`（约 78MB，含压缩）

**关键参数说明：**

| 参数 | 说明 |
|------|------|
| `--root` | 要打包的根目录，其下内容按目录结构安装 |
| `--identifier` | 包标识符，用于系统识别和卸载 |
| `--version` | 包版本号 |
| `--install-location` | 安装目标根路径，`/` 表示按 root 内路径原样安装 |

#### 第四步：清理临时文件

```bash
rm -rf dist/app-image dist/input dist/pkg-root
```

最终产物只有 `dist/sql-cli-1.0.0.pkg`。

### 一键打包脚本

```bash
#!/bin/bash
set -e

VERSION="1.0.0"
DIST_DIR="dist"

echo "=== 构建 sql-cli $VERSION 安装包 ==="

if [ ! -f target/sql-cli.jar ]; then
    echo "构建 JAR..."
    mvn -q -DskipTests package
fi

rm -rf "$DIST_DIR"
mkdir -p "$DIST_DIR/input"

echo "Step 1/3: 生成 app-image..."
cp target/sql-cli.jar "$DIST_DIR/input/"
jpackage \
  --name "sql-cli" \
  --input "$DIST_DIR/input" \
  --main-jar sql-cli.jar \
  --main-class com.sqlcli.SqlCli \
  --type app-image \
  --dest "$DIST_DIR/app-image" \
  --app-version "$VERSION" \
  --java-options "-Xmx512m -Djava.awt.headless=true"

echo "Step 2/3: 构建安装目录..."
PKG_ROOT="$DIST_DIR/pkg-root"
mkdir -p "$PKG_ROOT/usr/local/lib/sql-cli"
mkdir -p "$PKG_ROOT/usr/local/bin"
cp -R "$DIST_DIR/app-image/sql-cli.app" "$PKG_ROOT/usr/local/lib/sql-cli/"
cat > "$PKG_ROOT/usr/local/bin/sql-cli" << 'WRAPPER'
#!/bin/bash
exec /usr/local/lib/sql-cli/sql-cli.app/Contents/MacOS/sql-cli "$@"
WRAPPER
chmod 755 "$PKG_ROOT/usr/local/bin/sql-cli"

echo "Step 3/3: 打包 pkg..."
pkgbuild \
  --root "$PKG_ROOT" \
  --identifier com.sqlcli.app \
  --version "$VERSION" \
  --install-location "/" \
  "$DIST_DIR/sql-cli-$VERSION.pkg"

rm -rf "$DIST_DIR/input" "$DIST_DIR/app-image" "$DIST_DIR/pkg-root"

echo "✅ 完成: $DIST_DIR/sql-cli-$VERSION.pkg"
```

### 安装与卸载

#### 安装

双击 `.pkg` 文件，按照安装向导完成安装。或通过命令行：

```bash
sudo installer -pkg dist/sql-cli-1.0.0.pkg -target /
```

#### 验证

```bash
sql-cli --version
sql-cli list
sql-cli <alias> "SELECT 1"
```

#### 卸载

```bash
sudo rm -rf /usr/local/lib/sql-cli /usr/local/bin/sql-cli
```

### 安装后文件布局

```
/usr/local/
├── bin/
│   └── sql-cli              # 终端命令（在 PATH 中）
└── lib/
    └── sql-cli/
        └── sql-cli.app/     # 应用 + JRE（165MB）
            └── Contents/
                ├── MacOS/sql-cli    # 原生启动器
                ├── app/sql-cli.jar  # 应用代码
                └── runtime/         # JRE 运行时
```

### 工作原理

1. `jpackage` 使用 `jlink` 生成精简 JRE（仅包含应用需要的 JDK 模块），并创建原生启动器
2. 启动器通过 JNI 直接调用 `libjli.dylib` 启动 JVM，无需 `java` 命令
3. `pkgbuild` 将文件按目录结构打包，安装时由 macOS Installer 写入对应路径
4. `/usr/local/bin` 默认在 macOS 的 PATH 中，用户安装后可直接使用

### 注意事项

- 配置文件（`config/settings.yaml`、`config/aliases.yaml`）和驱动目录（`./drivers/`）仍从**当前工作目录**读取
- JRE 随应用打包，用户无需单独安装 Java
- 仅支持 macOS，Windows 需使用 `--type msi` 或 `--type exe`
- 如需自定义 JRE 模块，可通过 `--jlink-options` 参数控制
