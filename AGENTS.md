# AGENTS.md

本文件为 Codex (Codex.ai/code) 在此仓库中工作时提供指导。

## 构建与运行

```bash
# 构建（生成 target/sql-cli.jar，唯一的可执行包）
mvn clean package

# 构建并跳过测试
mvn -q -DskipTests package

# 运行测试
mvn test

# 运行单个测试类
mvn test -Dtest=Sm4SqlCipherParserTest

# 运行 CLI（macOS/Linux）
sh ./sql-cli <alias> "SELECT ..."

# 运行 CLI（Windows）
sql-cli.bat <alias> "SELECT ..."
java -jar target/sql-cli.jar <alias> "SELECT ..."
```

## 架构概览

**包结构：**
- `com.sqlcli.config` - 别名、驱动和 JDBC URL 配置/解析
- `com.sqlcli.connection` - 连接管理、驱动加载、查询执行、连接池
- `com.sqlcli.secret` - 密码解析（env、keyring、encrypted）
- `com.sqlcli.crypto` - SM4 加密配置
- `com.sqlcli.sql` - SM4 列加密的 SQL 改写器
- `com.sqlcli.parser` - SQL 解析（类型检测、表/列提取）
- `com.sqlcli.recovery` - UPDATE/DELETE 的恢复 SQL 生成
- `com.sqlcli.strategy` - 数据库特定策略（MySQL、Oracle、PostgreSQL）
- `com.sqlcli.output` - 输出格式化器（csv、json、table）

**核心流程：**
1. `SettingsConfig` 加载 `config/settings.yaml` 统一管理所有配置
2. `AliasResolver` 根据 `aliasesPath` 加载别名配置，通过 `SecretResolver` 解析 `secretRef`
3. `DriverResolver` 从 `SettingsConfig` 获取驱动配置
4. `ConnectionManager` 通过 `DriverLoader` 创建连接（使用隔离的 ClassLoader 加载 `./drivers/` 中的外部 JDBC jar）
5. `QueryExecutor` 执行 SQL：
   - `Sm4SqlCipherParser` 在执行前改写 SQL，加密敏感列
   - `RecoveryBuilder` 为 UPDATE/DELETE 操作生成恢复 SQL
   - `FormatterFactory` 根据 `-f` 选项格式化输出

**配置文件：**
- `config/settings.yaml` - 主配置文件，包含：
  - `aliasesPath` - 别名配置文件路径
  - `masterPasswordEnv` - 主密码环境变量名
  - `driverDefaults` - 驱动默认配置（按数据库类型）
  - `drivers` - JDBC 驱动配置（dbType、driverClass、jars）
  - `secrets` - 加密密码存储（AES-GCM）
- `config/aliases.yaml` - macOS 别名配置（使用 keyring）
- `config/aliases-windows.yaml` - Windows 别名配置（使用 encrypted）

**配置文件优先级：**
1. 环境变量 `SQLCLI_ALIASES_PATH` 指定的路径（最高优先级）
2. `config/settings.yaml` 中的 `aliasesPath` 配置
3. 默认 `config/aliases.yaml`

**密码方案：**
- `env:VAR_NAME` - 环境变量（跨平台）
- `keyring:name` - macOS Keychain（仅 macOS）
- `encrypted:name` - AES-GCM 加密存储在 settings.yaml（跨平台，推荐 Windows）

## 命令结构

**命令唯一性约束：** 同一功能只允许一个 CLI 入口，不得增加别名命令、快捷命令或另一套等价语法。帮助统一使用标准的 `<command> --help`，例如 `sql-cli <alias> schema add-relation --help`，不得增加 `help <command>` 形式。

所有别名相关操作以 `<alias>` 作为第一级参数：

```bash
sql-cli <alias> "SQL"              # 快速查询（默认 csv 输出）
sql-cli <alias> test               # 测试连接
sql-cli <alias> ddl <table>        # 获取表 DDL
sql-cli <alias> tables             # 列出表
sql-cli <alias> secret set         # 设置密码（交互式）
sql-cli <alias> secret status      # 查看密码状态
```

系统管理命令：

```bash
sql-cli list                       # 列出别名
sql-cli alias add/update/remove    # 别名管理
sql-cli driver add/update/remove   # 驱动管理
sql-cli crypto                     # 加密工具
```

查询选项：

```bash
sql-cli <alias> "SQL" [-f table|json|csv] [--decrypt-cols col1,col2] [--no-decrypt] [-d]
```

| 选项 | 说明 |
|------|------|
| `-f` | 输出格式（table/json/csv），默认 csv |
| `--decrypt-cols` | SM4 解密列名，逗号分隔 |
| `--no-decrypt` | 返回原始加密数据，不解密 |
| `-d` / `--debug` | 启用详细日志 |

## SM4 列加密

工具支持敏感列（手机号、账号等）的自动 SM4 加密/解密。

**别名配置：**
```yaml
aliases:
  my-db:
    sm4Key: "88ED6EA3C8054CD9"        # SM4 密钥（16字符）
    sm4PrivateTag: "ENC"              # 密文前缀（默认：ENC）
    sm4Version: "240606"              # 版本标识（默认：240606）
    decryptColumns: [phone, account]  # 自动解密列
```

**SQL 改写：** `Sm4SqlCipherParser` 自动加密以下位置的明文值：
- SELECT WHERE 比较表达式：`phone = '13800138000'` → `phone = 'ENC#240606#...'`
- SELECT WHERE IN 列表：`phone IN ('138', '139')` → 加密值
- INSERT VALUES：加密列中的明文值被加密
- UPDATE SET：加密列中的明文值被加密

**输出解密：** 查询结果自动解密 `decryptColumns` 中的列，除非指定 `--no-decrypt`。

**手动加密：**
```bash
sql-cli crypto sm4 --encrypt --text "明文" --alias <alias>
sql-cli crypto sm4 --decrypt --text "密文" --alias <alias>
```

## 恢复 SQL

UPDATE/DELETE 操作在执行前自动生成恢复 SQL：
- UPDATE：生成反向 UPDATE，使用原始值
- DELETE：生成 INSERT，包含被删除行的数据

恢复文件保存用于回滚。

## Oracle 连接说明

Oracle JDBC URL 支持两种格式：
- SID：`jdbc:oracle:thin:@host:port:sid`
- SERVICE_NAME：`jdbc:oracle:thin:@//host:port/serviceName`

`ConnectionManager` 应用 Oracle VPN 兼容默认值：`oracle.jdbc.javaNetNio=false`、`oracle.net.disableOob=true`。

DDL/tables 操作需要指定 `--schema`（默认使用用户名）。

## MySQL 说明

tables 输出的 schema 列为空（MySQL 无 schema 概念）。
DDL 使用 `SHOW CREATE TABLE`。

## 输出格式

默认为 `csv`。使用 `-f table` 生成可读表格，`-f json` 生成机器可读输出。

## Java 版本

需要 Java 17（pom.xml 中 maven.compiler.source/target）。
