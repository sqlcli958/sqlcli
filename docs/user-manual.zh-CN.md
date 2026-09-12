# sql-cli 完整使用手册

> 2026-08-25：Web UI 一级导航是**五页**——工作台 / 图谱 / 规则 / 评审 / 设置。
> **执行记录在评审页**（第三个标签）：审批看「要不要放行」，执行记录看「放行之后
> 发生了什么」，是同一件事的两半；工作台只留数据源目录、健康视图和 SQL 编辑器。
> 别名上的**审批开关**打开后，对应操作会阻塞等人在评审页放行，见 [§16](#16-本地-web-ui)。
> 本手册其余章节的全量更新排在 dev-checklist 的「文档与契约同步」。

本文档适用于当前仓库实现（`1.0.3-SNAPSHOT`），是用户操作的基准文档。路线图和开发清单中的未完成事项不代表当前可用功能。

## 本轮实现了什么，分别怎么使用

本轮开发不只是增加命令，还加固了 SQL 执行入口、规则引擎、候选 DDL 分析基础和 Web 测试。各能力的实际开放状态如下：

| 实现能力 | 当前状态 | 使用入口 |
|---|---|---|
| 无 WHERE 的 UPDATE/DELETE 门禁 | 已开放，自动生效 | 原有 SQL 文本或 `--file` 入口 |
| UPDATE/DELETE 可靠恢复 SQL | 已开放，自动生效 | 执行符合条件的 UPDATE/DELETE |
| 单语句执行边界 | 已开放，自动生效 | 所有普通 SQL 入口 |
| JSON envelope 与退出码 | 已开放 | 查询使用 `-f json`，管理命令使用 `--json` |
| JDBC URL、异常和日志脱敏 | 已开放，自动生效 | `test`、alias 展示、执行错误和 debug 日志 |
| 精确 Web Origin、Token 和 revision 校验 | 已开放，自动生效 | `ui` 中的写操作 |
| 十三类固定 policy 规则 | 已实现 | Web UI 规则页可视化编辑；CLI 用 `schema policy check`，输入类规则由 Migration lint 提供待审 SQL |
| 四种数据库方言规则资产 | 规则文件已实现 | 审查命令现已提供待审 SQL 输入；内置规则选择入口仍未提供 |
| Policy 审计扩展字段 | 已实现并向后兼容 | evaluation/violation 查询可读取；普通 policy check 中输入字段为空 |
| 候选 DDL 内存投影 | 已开放 | `schema design review` 与 `schema migration lint` 自动使用 |
| Web revision/锁/网络失败恢复 | 已开放 | `ui` 的关系编辑器保留草稿并允许重试 |
| Vitest 与 Playwright CLI E2E | 开发验证能力 | `npm test`、`npm run test:e2e` |

设计审查和 Migration lint 都只投影内存候选，不会修改正式 workspace、增加 revision 或执行输入 SQL。

### 一次完整的常用工作流

下面以 MySQL alias `app-dev` 为例，演示从配置到查询、图谱、规则审计和 Web UI 的完整路径。

#### 第一步：配置密码和检查连接

```bash
export APP_DB_PASSWORD='数据库密码'

sql-cli list
sql-cli alias show app-dev
sql-cli app-dev secret status --json
sql-cli app-dev test --json
```

使用 `env:APP_DB_PASSWORD` 时，不需要执行 `secret set`。连接成功返回退出码 0，JSON 的 `ok` 为 `true`；连接失败返回退出码 1。

#### 第二步：执行安全查询

```bash
sql-cli app-dev \
  "SELECT id, buyer_id, status FROM app.orders WHERE id = 1001" \
  -f table
```

供脚本消费：

```bash
result=$(sql-cli app-dev \
  "SELECT id, status FROM app.orders WHERE id = 1001" \
  -f json)
code=$?

printf '%s\n' "$result"
printf 'exit=%s\n' "$code"
```

查询成功时可以从 `rows` 和 `rowCount` 读取结果。失败时 `ok=false`，同时退出码非零。

#### 第三步：建立 Schema 工作区

```bash
sql-cli app-dev schema import --from-db
sql-cli app-dev schema stats
sql-cli app-dev schema validate
```

导入后会在 `config/schema-graphs/app-dev/` 建立本地工作区。后续 `schema describe/search/path/policy/ui` 都依赖该工作区；它们不是实时数据库查询。

#### 第四步：查询并补充业务语义

```bash
sql-cli app-dev schema describe app.orders
sql-cli app-dev schema search '订单'

sql-cli app-dev schema edit \
  --table app.orders \
  --description '订单主表' \
  --add-tag business

sql-cli app-dev schema edit \
  --column app.orders.buyer_id \
  --description '下单用户 ID' \
  --business-name '买家ID' \
  --semantic-type ref_id \
  --add-tag relation-key
```

`--business-name`（表/字段都可用）和 `--semantic-type`（仅字段，取值见 `schema edit --help`）
与 Web UI 表/列详情页写的是同一批字段。`--semantic-type` 传非法值会直接报错并列出全部合法
取值，不会被静默忽略——它是固定枚举而不是自由文本，规则引擎（如 `PolicyEvaluator` 的
`semanticTypeAny`）按值精确匹配，自由文本命中不了任何规则。

增加业务术语和推断关系：

```bash
sql-cli app-dev schema add-term buyer \
  --display-name '买家' \
  --map 'app.orders.buyer_id,app.users.id'

sql-cli app-dev schema add-relation \
  --type join_observed \
  --from app.orders.buyer_id \
  --to app.users.id \
  --join 'orders.buyer_id = users.id' \
  --confidence 0.9 \
  --verified
```

#### 第五步：执行结构规则审计

```bash
sql-cli app-dev schema policy check \
  --rules docs/schema-policy-ruleset.example.yaml \
  --json
```

命令会创建一条不可变 evaluation，并持久化 violation。存在未豁免的 required/blocking 违规时退出码为 1，这表示审计发现问题，不表示程序崩溃。

```bash
sql-cli app-dev schema policy evaluation list --json
sql-cli app-dev schema policy violation list --json
sql-cli app-dev schema validate --json
```

最后一个命令还会投影当前 revision 上未关闭的 required/blocking policy 问题，使工作区结构问题和规则问题可以统一查看。

#### 第六步：启动可视化界面

```bash
sql-cli app-dev schema index rebuild
sql-cli ui --alias app-dev
```

浏览器中可以选择 Schema 和表、查看 Inspector、搜索以及维护关系。终端进程必须保持运行；按 Ctrl+C 停止。

### 新增 SQL 安全能力怎么使用

安全能力复用原有 SQL 入口，不增加绕过参数或第二套命令。

#### 危险 DML 门禁

以下 SQL 会在创建连接和执行前被拒绝，退出码为 1：

```bash
sql-cli app-dev "UPDATE app.orders SET status = 'CANCELLED'"
sql-cli app-dev "DELETE FROM app.orders"
```

应先查询目标数据，再使用能够精确限制范围的 WHERE：

```bash
sql-cli app-dev \
  "SELECT id, status FROM app.orders WHERE id = 1001" \
  -f table

sql-cli app-dev \
  "UPDATE app.orders SET status = 'CANCELLED' WHERE id = 1001"
```

工具只检查存在有效 WHERE 和能否可靠恢复，不会判断业务条件是否正确。生产环境仍应使用 `readonly: true` 和数据库只读账号。

#### Recovery SQL

对支持恢复 SQL 的数据库执行简单 UPDATE/DELETE 时，工具会自动完成：

1. 解析目标表和 WHERE。
2. 从 JDBC metadata 读取主键。
3. 在原 SQL 前读取受影响行和原值。
4. 执行原 SQL。
5. 为每行生成可唯一定位的恢复 SQL。
6. 保存到 `~/.sql-cli/recovery/`。

示例输出会包含类似路径：

```text
Task: 34  (sql-cli task status 34)
回滚 SQL 已保存，可在 Web UI 的评审页「执行记录」里查看或执行。
```

多行 UPDATE 会为每一行使用自己的主键和原值；复合主键会生成全部键条件。目标表没有主键、主键值为 NULL 或 SQL 结构无法可靠恢复时，原 UPDATE 会在执行前失败。

回滚脚本连同**执行前的原始行**一起存进本地运行库 `~/.sql-cli/sqlcli.db` 的 `recovery_artifact` 表，
不再落文件。存不下就不执行——「改错了能不能退回去」的答案必须永远是能。
需要回滚时到 Web UI 评审页的「执行记录」里展开那条记录：先看回滚 SQL 和原始行，
点「执行回滚」会再走一次审批（无条件，不受别名开关影响），批准后单连接单事务逐条执行。
升级前留下的 `~/.sql-cli/recovery/*.sql` 老文件仍能被读取和执行。

#### 单语句边界

下面的输入会返回用法错误和退出码 2：

```bash
sql-cli app-dev "UPDATE app.orders SET status='A' WHERE id=1; DELETE FROM app.orders WHERE id=2"
sql-cli app-dev --file multi-statements.sql
```

必须拆成两个独立调用。字符串和注释中的分号不会被误判为第二条语句。

### 新增机器输出契约怎么使用

普通查询使用 `-f json`：

```bash
sql-cli app-dev "SELECT 1 AS healthy" -f json
```

Schema、连接和 secret 等命令使用 `--json`：

```bash
sql-cli app-dev test --json
sql-cli app-dev secret status --json
sql-cli app-dev schema stats --json
```

脚本判断顺序：

1. 检查进程退出码。
2. 解析 JSON。
3. 检查 `ok`。
4. 成功时读取 `data` 或查询结果的 `rows`；失败时读取 `code`、`message`。

不要解析 `Connection successful` 等文本，也不要把“JSON 能解析”当作执行成功。

### 新增五类工作区规则怎么使用

本轮在原有五类规则上增加了五类可直接检查当前工作区的规则。可以创建 `app-policy.yaml`：

```yaml
kind: PolicyRuleSet
id: app-schema-policy
title: Application schema policy
version: "1"
dbType: mysql
match:
  dbTypeAny: [mysql]
rules:
  - id: vocabulary-user-id
    title: 用户标识统一命名
    category: controlled_vocabulary
    severity: warning
    enforcement: advisory
    when: {}
    statement:
      concepts:
        - standard: user_id
          aliases: [userid, uid]
    remediation: 使用 user_id

  - id: order-business-key
    title: 订单业务键唯一
    category: business_unique_index
    severity: error
    enforcement: required
    when:
      tableNameRegex: "^orders$"
    statement:
      columns: [tenant_id, order_no]
    remediation: 建立列顺序完全一致的复合唯一索引

  - id: relation-source-index
    title: 关联来源字段建立索引
    category: relation_column_index
    severity: warning
    enforcement: required
    when: {}
    statement: {}
    remediation: 建立以关系字段为最左前缀的索引

  - id: join-type-compatible
    title: JOIN 字段类型兼容
    category: join_column_type_match
    severity: error
    enforcement: required
    when: {}
    statement:
      compatibleTypeGroups:
        - [int, integer]
        - [bigint, int8]
        - [varchar, character varying]
    remediation: 对齐字段类型、长度、精度和小数位

  - id: status-dictionary
    title: 状态字段必须有字典说明
    category: status_field_dictionary
    severity: warning
    enforcement: required
    when:
      columnNameRegex: "(?i)(status|state)$"
    statement:
      dictionaryAttribute: dictionaryRef
      descriptionAttribute: dictionaryDescription
    remediation: 声明枚举、dictionaryRef 或 dictionaryDescription
```

临时执行单个规则文件：

```bash
sql-cli app-dev schema policy check --rules app-policy.yaml
```

长期启用时，把规则放到对应 alias 工作区：

```text
config/schema-graphs/app-dev/policy/
├── bindings.yaml
└── rules/
    └── app-policy.yaml
```

工作区首次初始化会自动创建 `policy/rules/` 和内容为 `ruleSets: []` 的 `policy/bindings.yaml`。

`bindings.yaml`：

```yaml
ruleSets:
  - rules/app-policy.yaml
```

之后不再需要每次传规则路径：

```bash
sql-cli app-dev schema policy check
sql-cli app-dev schema policy check --json
```

绑定中的路径必须相对 `policy/`，且不能逃出当前 alias 工作区。可以按顺序绑定多个规则文件；每个规则文件产生独立 evaluation，命令退出码取所有结果中的最高值。显式 `--rules` 只执行指定文件，不读取 bindings。

`required_business_columns` 的 `columns` 既可写字段名，也可写详细定义。详细定义支持：`name`、`aliases`、`type`、`length`、`precision`、`scale`、`nullable`、`defaultValue`、`comment` 和 `onUpdate`。未配置的属性不检查；`CURRENT_TIMESTAMP` 与 `CURRENT_TIMESTAMP()`、空字符串与 `''` 按等价值比较。只检查物理表时使用：

```yaml
when:
  tableTypeAny: [base_table]
statement:
  columns:
    - name: creator
      type: varchar
      length: 64
      defaultValue: ""
      comment: 创建者
    - name: update_time
      type: datetime
      nullable: false
      defaultValue: CURRENT_TIMESTAMP
      onUpdate: CURRENT_TIMESTAMP
      comment: 更新时间
```

MySQL 的 `onUpdate` 来自 `information_schema.COLUMNS.EXTRA`。升级后应重新执行一次 `schema import --from-db`，旧图谱快照没有该属性，也可能因历史序列化问题缺失准确的 `nullable: false`。

这些规则分别检查：

- `controlled_vocabulary`：字段是否使用标准词，而不是配置的别名。
- `business_unique_index`：业务键是否存在列顺序完全一致的唯一索引。
- `relation_column_index`：外键、推断外键和 JOIN 来源列是否有最左前缀索引；未验证的 observed JOIN 自动降为 advisory。
- `join_column_type_match`：关系两端字段的类型、长度、精度和 scale 是否一致或处于兼容类型组。
- `status_field_dictionary`：状态字段是否有枚举值、字典引用或字典描述；不会采样业务数据猜测字典。

原有五类 `naming_convention`、`required_business_columns`、`money_type_decimal`、`primary_key_required`、`sensitive_column_policy` 的完整示例位于 `docs/schema-policy-ruleset.example.yaml`。

### DDL 与 Migration 审查

`schema policy check` 仍只评估正式工作区。需要候选 DDL、危险 DML 或 Migration 配套文件时，使用下方两个唯一入口：

```bash
sql-cli app-dev schema design review \
  --ddl schema.sql \
  --rules design-rules.yaml \
  --json

sql-cli app-dev schema migration lint \
  --file migration.sql \
  --rules migration-rules.yaml \
  --rollback rollback.sql \
  --precheck precheck.sql \
  --postcheck postcheck.sql \
  --json
```

设计审查投影 CREATE/ALTER/INDEX 变更；Migration lint 额外检查 UPDATE/DELETE 的 WHERE 和 verification SQL。`--precheck`、`--postcheck` 只能包含只读 SQL。两者均输出 evaluation、violations、diagnostics 和 changes；语法/投影失败返回状态码 2。

#### 四种数据库方言规则

规则资产位于：

```text
src/main/resources/rules/dialect/mysql/8.0.yaml
src/main/resources/rules/dialect/postgresql/14.yaml
src/main/resources/rules/dialect/oracle/19c.yaml
src/main/resources/rules/dialect/clickhouse/24.x.yaml
```

这些规则作为 classpath 资源同时进入完整版和 lite JAR。每种规则覆盖分页、标识符、Schema、日期时间、JSON、upsert 和常见 DDL。普通 `policy check` 没有待审 SQL 输入；在 design review 或 Migration lint 中提供等价规则文件即可执行，并将 evaluation 和规则快照写入对应 alias 的 `policy/runs/`。

#### 候选 DDL 内存投影

内部 `CandidateDdlProjector` 已支持：

- UTF-8 SQL 文本和文件。
- CREATE TABLE。
- ALTER TABLE ADD、DROP、MODIFY、RENAME COLUMN。
- ADD/DROP INDEX 和 UNIQUE CONSTRAINT。
- CREATE INDEX。
- 按 `path:line` 输出 before/after、targetId、changeType 和 destructive。
- 在 GraphWorkspace 隔离副本中投影，不保存、不增加 revision、不产生 ChangeRecord。

不提供独立 `schema project` 命令；投影只作为 `schema design review` 和 `schema migration lint` 的共享内部步骤，避免产生等价入口。

### Web 冲突恢复怎么使用

```bash
sql-cli ui --alias app-dev
```

在关系编辑器中修改关系时：

- HTTP 409 表示 workspace revision 已变化。界面保留当前草稿，可刷新到最新 revision 后重试。
- HTTP 423 表示 alias 正被其他写操作锁定。界面保留草稿，等待导入或其他写操作结束后重试。
- 网络错误或 5xx 不会清空本地输入，可直接重试或放弃草稿。

这项能力不需要额外开关。所有写请求仍受随机 session Token、精确 Origin 和 revision 校验保护。

## 1. 产品能力与边界

sql-cli 是基于 Java 17 和 JDBC 的多数据库命令行工具，当前支持：

- MySQL、Oracle、PostgreSQL、ClickHouse JDBC 访问。
- 通过 Yearning 执行只读查询。
- CSV、JSON、ASCII 表格输出。
- 数据源 alias、JDBC 驱动和密码引用管理。
- SM4 敏感列 SQL 改写、查询结果解密和手动加解密。
- UPDATE、DELETE 执行前生成恢复 SQL。
- Schema 元数据工作区、业务术语、关系、检索、规则审计、关系图和本地 Web UI。
（SQL 与 MyBatis Mapper 的 JOIN 关系扫描命令 `schema scan-sql` 已于 2026-08-20 移除，改由 Agent 读代码提取、经 `schema add-relation` 写回，见第 15 章。）

当前尚未提供可直接调用的数据库设计审查、Migration lint/执行计划、文档自动抽取和自然语言问答命令。这些内容即使出现在路线图中，也不应按已实现功能使用。

## 2. 环境要求

运行要求：

- Java 17 或更高版本。
- 对应数据库的 JDBC 驱动 JAR。
- 从源码构建时需要 Maven；构建 Web UI 时还需要 Node.js 20 和 npm。

确认环境：

```bash
java -version
mvn -version
node -v
npm -v
```

## 3. 构建与启动

### 3.1 完整构建

默认 Maven profile 会先执行 `web/npm ci` 和 `web/npm run build`，再构建 Java 包：

```bash
mvn clean package
```

产物：

- `target/sql-cli.jar`：唯一的可执行包（shade 打成的 fat jar），约 17 MB。

  里面带着 sqlite-jdbc 的原生库——它是本地运行库（`~/.sql-cli/sqlcli.db`）用的，
  和别名里配的那些 JDBC 驱动不是一回事，那些仍然外置在 `./drivers/`。
  原生库只保留 Windows/x86_64、Mac/x86_64、Mac/aarch64、Linux/x86_64、Linux/aarch64
  五个平台；**别的平台（Alpine/musl、FreeBSD、32 位、树莓派等）跑不起来**，
  sqlite-jdbc 没有纯 Java 回退。需要时在 `pom.xml` 的 shade 过滤器里把对应平台
  从排除名单里去掉重新打包。

两个产物都不会自动包含 MySQL、Oracle、PostgreSQL 或 ClickHouse JDBC 驱动；驱动仍需在 `config/settings.yaml` 中配置外部 JAR，或由调用者自行加入 classpath。

仅构建 Java、跳过 Web：

```bash
mvn -P\!web -DskipTests package
```

### 3.2 从仓库运行

macOS/Linux：

```bash
sh ./sql-cli --help
sh ./sql-cli <alias> "SELECT 1"
```

Windows：

```bat
sql-cli.bat --help
sql-cli.bat <alias> "SELECT 1"
```

直接运行 JAR 时，应先进入项目或安装目录，确保相对路径下的 `config/` 和驱动 JAR 可解析：

```bash
java -jar target/sql-cli.jar --help
java -jar target/sql-cli.jar <alias> "SELECT 1"
```

### 3.3 帮助规则

同一功能只有一个命令入口。帮助统一使用 `<command> --help`，不支持 `help <command>`：

```bash
sql-cli --help
sql-cli alias --help
sql-cli driver --help
sql-cli <alias> schema --help
sql-cli <alias> schema policy --help
```

alias 是动态一级参数，因此 `sql-cli <alias> --help` 不是有效入口；查询动作帮助应查看对应的 alias action 或 Schema 命令。

## 4. 配置体系

所有相对路径都按启动时的工作目录解释。仓库启动脚本会先切换到脚本所在目录。

```text
config/
├── settings.yaml       # 主配置、驱动、默认驱动和加密密码
├── aliases.yaml        # 默认 alias 文件
└── schema-graphs/      # 默认 Schema 工作区根目录
```

### 4.1 `settings.yaml`

最小结构示例：

```yaml
aliasesPath: aliases.yaml
masterPasswordEnv: SQLCLI_MASTER_PASSWORD
schemaGraphPath: config/schema-graphs

driverDefaults:
  mysql: mysql8
  postgresql: pg42

drivers:
  mysql8:
    dbType: mysql
    driverClass: com.mysql.cj.jdbc.Driver
    jars:
      - drivers/mysql/mysql-connector-j.jar
  pg42:
    dbType: postgresql
    driverClass: org.postgresql.Driver
    jars:
      - drivers/postgresql/postgresql.jar

secrets: {}
```

`aliasesPath` 为相对路径时按 `config/` 目录解析，建议填写 `aliases.yaml`；也可以填写绝对路径。alias 读取优先级为：

1. 环境变量 `SQLCLI_ALIASES_PATH`。
2. `config/settings.yaml` 的 `aliasesPath`。
3. 默认 alias 路径。

当前 `alias add/update/remove` 只写入 `config/aliases.yaml`。使用 `SQLCLI_ALIASES_PATH` 或自定义 `aliasesPath` 时，应直接维护目标文件，避免读取文件和管理命令写入文件不一致。

### 4.2 `aliases.yaml`

JDBC alias 使用完整 `url` 字段：

```yaml
aliases:
  app-dev:
    dbType: mysql
    driverRef: mysql8
    url: jdbc:mysql://db.example.com:3306/app
    username: app_user
    secretRef: env:APP_DB_PASSWORD
    description: 应用开发库
    readonly: true
    params:
      maximumPoolSize: "3"
      minimumIdle: "0"
      connectionTimeoutMs: "5000"
      idleTimeoutMs: "120000"
      maxLifetimeMs: "600000"
      keepaliveTimeMs: "0"
      defaultQueryLimit: "100"
```

alias 字符串字段及 `params` 值支持完整占位符 `${ENV_NAME}` 或 `${ENV_NAME:default}`。不要在配置文件中保存明文 `password`。

`params` 中的连接池参数只用于本地连接池，不附加到 JDBC URL；其余参数会进行 URL 编码后附加到 URL。

### 4.3 数据库 URL 示例

```yaml
aliases:
  mysql-app:
    dbType: mysql
    driverRef: mysql8
    url: jdbc:mysql://mysql.example.com:3306/app
    username: app_user
    secretRef: env:MYSQL_APP_PASSWORD

  pg-app:
    dbType: postgresql
    driverRef: pg42
    url: jdbc:postgresql://pg.example.com:5432/app
    username: app_user
    secretRef: env:PG_APP_PASSWORD

  oracle-service:
    dbType: oracle
    driverRef: oracle19
    url: jdbc:oracle:thin:@//oracle.example.com:1521/APP_SERVICE
    username: APP_USER
    secretRef: encrypted:oracle-app

  oracle-sid:
    dbType: oracle
    driverRef: oracle19
    url: jdbc:oracle:thin:@oracle.example.com:1521:APPSID
    username: APP_USER
    secretRef: encrypted:oracle-sid

  analytics:
    dbType: clickhouse
    driverRef: clickhouse
    url: jdbc:clickhouse://clickhouse.example.com:8123/analytics
    username: readonly_user
    secretRef: keyring:analytics
    readonly: true
    params:
      socket_timeout: "300000"
      defaultQueryLimit: "100"
```

ClickHouse HTTPS 通常使用端口 `8443`，并在 `params` 中设置 `ssl: "true"`、`sslmode: "strict"`。

## 5. Alias 管理

> 这些操作在 Web UI 的**设置 → 数据源**里都有（新建、编辑、删除、连接测试、设置密码、
> 审批开关），见 [§16.10](#1610-设置页)。本节讲 CLI 写法，供脚本和无界面环境使用。

### 5.1 列出与查看

```bash
sql-cli list
sql-cli list --json
sql-cli alias show app-dev
```

不存在 `sql-cli alias list`；列出 alias 的唯一入口是 `sql-cli list`。

### 5.2 新增

直接提供完整 JDBC URL：

```bash
sql-cli alias add app-dev \
  --db-type mysql \
  --driver-ref mysql8 \
  --jdbc-url 'jdbc:mysql://db.example.com:3306/app' \
  --username app_user \
  --secret-ref env:APP_DB_PASSWORD \
  --description '应用开发库' \
  --readonly true
```

也可以通过 `--host`、`--port`、`--database` 组装 URL。Oracle 可额外使用 `--service-name` 或 `--sid`，二者不能同时使用。

交互式新增：

```bash
sql-cli alias add app-dev --interactive
```

### 5.3 更新与删除

```bash
sql-cli alias update app-dev --readonly false --description '应用测试库'
sql-cli alias remove app-dev
```

更新时仅覆盖显式提供的字段。删除 alias 不会自动删除该 alias 引用的系统凭据、加密密码或 Schema 工作区。

## 6. JDBC 驱动管理

> Web UI 的**设置 → 驱动**里有同样的功能，而且多两样 CLI 没有的：**jar 状态灯**
> （立刻看出 jar 缺失或加载不出驱动类，而不是等到连接失败）和**jar 上传**。
> 见 [§16.10](#1610-设置页)。

`driver` 的 action 和驱动名是位置参数：

```bash
sql-cli driver list
sql-cli driver list --json
sql-cli driver show mysql8

sql-cli driver add mysql8 \
  --db-type mysql \
  --driver-class com.mysql.cj.jdbc.Driver \
  --jar drivers/mysql/mysql-connector-j.jar

sql-cli driver update mysql8 --jar drivers/mysql/mysql-connector-j-new.jar
sql-cli driver default --db-type mysql --driver-ref mysql8
sql-cli driver remove mysql8
```

多个 JAR 可重复提供 `--jar`，也可使用逗号分隔。驱动配置写入 `config/settings.yaml`。alias 的 `dbType` 必须与 `driverRef` 对应驱动的 `dbType` 一致。

常见驱动类：

| 数据库 | Driver class |
|---|---|
| MySQL | `com.mysql.cj.jdbc.Driver` |
| PostgreSQL | `org.postgresql.Driver` |
| Oracle | `oracle.jdbc.OracleDriver` |
| ClickHouse | `com.clickhouse.jdbc.ClickHouseDriver` |

## 7. 密码与 Token 管理

### 7.1 引用类型

| 引用 | 适用平台 | 数据来源 |
|---|---|---|
| `env:VAR_NAME` | 全平台 | 环境变量 |
| `keyring:name` | macOS、Windows | macOS Keychain 或 Windows 当前用户 DPAPI 文件 |
| `encrypted:name` | 全平台运行逻辑；初始化依赖可用的系统凭据存储或环境变量 | AES-GCM 密文保存在 `settings.yaml` |

Linux 当前不支持 `keyring:*`，而 `encrypted:*` 的主密码自动存取也依赖 keyring 实现。Linux/容器环境优先使用 `env:*`。

### 7.2 设置、检查与删除

> 设置密码在 Web UI 的设置页也可以做（数据源行的 ⋯ → 设置密码）。
> `secret status` / `secret delete` **只有 CLI 有**。

```bash
sql-cli app-dev secret set
sql-cli app-dev secret status
sql-cli app-dev secret status --json
sql-cli app-dev secret delete
```

自动化环境可从标准输入读取，避免交互终端：

```bash
printf '%s\n' "$APP_DB_PASSWORD" | sql-cli app-dev secret set --stdin
```

`secret set/delete` 只支持 `keyring:*` 和 `encrypted:*`；`env:*` 应由操作系统或 CI Secret 管理。`secret status` 只返回配置和存在状态，不输出密码。

首次解析 `encrypted:*` 时，主密码读取顺序为系统凭据、`masterPasswordEnv` 指定的环境变量、交互输入。不要在命令行参数、日志或版本库中暴露密码。

## 8. 连接检查与数据库信息

```bash
sql-cli app-dev test
sql-cli app-dev test --json
sql-cli app-dev info
sql-cli app-dev info --json
```

`test` 验证 JDBC 或 Yearning 可用性。`info` 收集数据库策略提供的连接信息；Yearning alias 不支持 `info`，会返回非零状态。

JSON 成功输出使用：

```json
{"ok":true,"data":{"alias":"app-dev"}}
```

失败输出使用：

```json
{"ok":false,"code":"CONNECTION_FAILED","message":"..."}
```

## 9. SQL 执行

### 9.1 文本与文件

```bash
sql-cli app-dev "SELECT id, status FROM app.orders WHERE id = 1001"
sql-cli app-dev --file query.sql
sql-cli app-dev -F query.sql
```

每次只能执行一条 SQL。分号感知解析会允许字符串和注释中的分号，但两条实际语句会在连接数据库前被拒绝。SQL 文本和 `--file` 不能同时使用。

### 9.2 输出格式

```bash
sql-cli app-dev "SELECT * FROM app.orders" -f csv
sql-cli app-dev "SELECT * FROM app.orders" -f json
sql-cli app-dev "SELECT * FROM app.orders" -f table
```

- `csv`：默认格式，包含表头，字段按 CSV 规则转义。
- `table`：面向人工阅读，显示行数和可用的耗时。
- `json`：面向自动化，包含 `ok`、alias、数据库类型、语句类型、列、行、行数和耗时。

查询 JSON 示例：

```json
{
  "ok": true,
  "alias": "app-dev",
  "dbType": "mysql",
  "statementType": "SELECT",
  "columns": ["id", "status"],
  "rows": [{"id": 1001, "status": "PAID"}],
  "rowCount": 1,
  "elapsedMs": 8
}
```

查询失败且指定 `-f json` 时，JSON 写入标准输出：

```json
{"ok":false,"code":"EXECUTION_FAILED","message":"..."}
```

### 9.3 调试日志

```bash
sql-cli app-dev "SELECT 1" --debug
```

`-d`/`--debug` 必须放在 alias 之后；`sql-cli --debug app-dev ...` 会被拒绝。调试日志仍可能包含业务 SQL，生产环境应谨慎留存。

### 9.4 默认查询限制

数据库策略会为没有显式行数限制的只读查询应用 `params.defaultQueryLimit`，默认值为 `100`。MySQL、PostgreSQL 和 ClickHouse 使用对应的 `LIMIT` 语法，Oracle 使用 `ROWNUM` 包装。复杂或已经包含限制语法的 SQL 由数据库策略判断是否改写。

### 9.5 只读与 Yearning 限制

`readonly: true` 会拒绝 INSERT、UPDATE、DELETE、MERGE、DDL、授权和撤权语句。Yearning 模式只允许 SELECT、WITH、SHOW、DESC/DESCRIBE、EXPLAIN 等只读语句。

## 10. 表与 DDL

`tables` 直接读取实时数据库，不是 Schema 工作区：

```bash
sql-cli app-dev tables
sql-cli app-dev tables --schema app
sql-cli app-dev tables --pattern '%order%'
sql-cli app-dev tables -f table
sql-cli app-dev tables -f csv
sql-cli app-dev tables -f json
```

获取 DDL：

```bash
sql-cli app-dev ddl orders --schema app
```

MySQL 使用 `SHOW CREATE TABLE`。Oracle 通常需要显式 `--schema`，未指定时使用数据库策略的默认 schema（通常为用户名）。MySQL 的表输出中 schema 列可能为空。

## 11. 写操作安全与恢复 SQL

### 11.1 拒绝条件

- 标准 UPDATE、DELETE 必须包含有效 WHERE 条件。
- 一次只能执行一条 SQL。
- alias 为只读时拒绝所有写操作。
- 无法可靠生成恢复 SQL的复杂 UPDATE/DELETE 会被拒绝，例如 JOIN、子查询、ORDER BY、LIMIT 等受限结构。
- UPDATE 的目标表必须存在可靠主键；复合主键会全部用于逐行恢复。
- ClickHouse 标准 UPDATE/DELETE 和恢复 SQL不受支持；ClickHouse mutation 由其数据库策略处理。

### 11.2 生成位置

UPDATE/DELETE 执行前读取原始行，执行成功后将恢复 SQL 保存到：

```text
${user.home}/.sql-cli/recovery/<table>_<timestamp>_<random>.sql
```

UPDATE 生成按主键定位的反向 UPDATE，DELETE 生成包含原始数据的 INSERT。回滚脚本和原始行存在本地运行库 `~/.sql-cli/sqlcli.db` 里，含原始数据、可能有敏感信息，按安全规范保管这个文件。

恢复 SQL 是应急辅助，不替代数据库备份、事务控制、变更审批和恢复演练。正式回滚前必须人工核对目标环境、表结构和数据时效。

## 12. SM4 敏感列

### 12.1 Alias 配置

```yaml
aliases:
  app-secure:
    dbType: mysql
    driverRef: mysql8
    url: jdbc:mysql://db.example.com:3306/app
    username: app_user
    secretRef: env:APP_DB_PASSWORD
    sm4Key: "88ED6EA3C8054CD9"
    sm4PrivateTag: ENC
    sm4Version: "240606"
    decryptColumns: [phone, account]
```

`decryptColumns` 同时决定 SQL 改写时识别的敏感列和查询结果默认解密列。当前密文格式为 `ENC#<version>#<hex-ciphertext>`。

自动改写覆盖：

- WHERE 中的敏感列等值比较和 IN 明文列表。
- INSERT VALUES 中敏感列的明文值。
- UPDATE SET 中敏感列的明文值。

临时增加敏感列或关闭结果解密：

```bash
sql-cli app-secure "SELECT phone FROM app.users" --decrypt-cols phone,account
sql-cli app-secure "SELECT phone FROM app.users" --no-decrypt
```

`--no-decrypt` 仅关闭结果解密，不关闭 SQL 条件和写入值的敏感列改写。

### 12.2 手动加解密

使用 alias 配置：

```bash
sql-cli crypto sm4 --encrypt --text '13800138000' --alias app-secure
sql-cli crypto sm4 --decrypt --text 'ENC#240606#...' --alias app-secure
```

直接提供参数：

```bash
sql-cli crypto sm4 --encrypt \
  --text '13800138000' \
  --key '88ED6EA3C8054CD9' \
  --private-tag ENC \
  --version 240606
```

必须且只能指定 `--encrypt`、`--decrypt` 之一，并提供 `--alias` 或 `--key`。`--text` 会进入 shell history；处理正式密钥或敏感明文时应在受控终端操作并及时清理历史。

## 13. Schema 知识工作区

Schema 命令操作本地工作区，而 `tables`、`ddl` 和 SQL 查询操作实时数据库。默认工作区位于：

```text
config/schema-graphs/<alias>/
```

工作区使用原子的 current-generation 指针切换写入。**只保留当前一代**——指针切换成功后旧代立即清理，
不做多版本管理（变更历史记在 `~/.sql-cli/sqlcli.db` 的 `graph_change_log`，不靠留着旧目录）。
主要内容包括 manifest、数据源、Schema/Table/Term 节点、关系、变更、校验、导入任务、索引和 policy 审计记录。

> **候选状态。** `schema add-relation` 与 `schema add-term` 以 agent 身份写入，
> 对象状态是 `candidate`，在 Web UI 图谱页表详情里单独排进「待审核」分组，
> 由人点发布（转 `verified` 或 `partial`）或拒绝（删除）——见 [§16.6](#166-图谱页)。
>
> 注意边界：**`search` / `describe` / `query` 默认包含候选，但会标注出来**——
> `search` 结果带 `candidate` 字段（文本输出为 `[候选]`），`describe` 的候选关系带 `[候选]` 前缀，
> `stats` 给出 `candidateRelations` / `candidateTerms` 计数。候选是一个标记加一个 UI 分组，
> 不是隔离的暂存区：搜得到不代表已发布。
> `schema edit` 改已有对象的描述，不走候选；`schema import --from-db` 是 human 身份，直接生效。

首次使用通常从数据库导入：

```bash
sql-cli app-dev schema import --from-db
```

### 13.1 导入与任务恢复

```bash
sql-cli app-dev schema import --from-db
sql-cli app-dev schema import --from-db --schema app
sql-cli app-dev schema import --from-db --table app.orders
sql-cli app-dev schema import --from-db --batch-size 20
sql-cli app-dev schema import --from-db --merge
sql-cli app-dev schema import --from-db --force-overwrite

sql-cli app-dev schema import status
sql-cli app-dev schema import status --json
sql-cli app-dev schema import resume
sql-cli app-dev schema import reset
```

导入通过 JDBC 元数据提取数据库产品、Schema、表、列、主键、索引、外键和注释。批处理支持 checkpoint/resume；合并会刷新物理结构并尽量保留用户维护的语义字段。`--force-overwrite` 会允许覆盖受保护字段，使用前应先导出快照。

从快照导入：

```bash
sql-cli app-dev schema import --input workspace.json
sql-cli app-dev schema import --input workspace.json --merge
```

**Web UI 里的等价路径**：图谱页左侧表目录，不用拼 `--schema` 参数。列的是数据源里的
**全部 schema**（不只是已导入的），每个 schema 后面带导入按钮，已导入的变成刷新；
按 schema 逐个导而不是一次全导——一个连接下十几个 schema、几千张表，全导会让图谱渲染
和索引重建都变慢，这一点无论走 CLI 的 `--schema` 还是 UI 都是同一个限制。图谱还没导入时
这一页照样能进，见 [§16.6](#166-图谱页)。

### 13.2 导出

```bash
sql-cli app-dev schema export
sql-cli app-dev schema export --output workspace.json
sql-cli app-dev schema export --output workspace.json --force
```

默认输出为工作区下的 `workspace-export.json`。目标文件已存在时必须显式 `--force`。

### 13.3 浏览、关联和搜索

```bash
sql-cli app-dev schema list
sql-cli app-dev schema list --json
sql-cli app-dev schema describe app.orders
sql-cli app-dev schema describe app.orders --json
sql-cli app-dev schema describe app.orders --refresh-row-count
sql-cli app-dev schema query app.orders --depth 2
sql-cli app-dev schema path app.orders app.users
sql-cli app-dev schema search '订单'
sql-cli app-dev schema search '买家 手机'
sql-cli app-dev schema search '订单' --json
```

`query` 按深度展开邻接关系；`path` 查找两表之间的关系路径。`search` 在索引存在且有效时使用索引，否则回退到工作区扫描。

`describe` 输出的 `rowEstimate` 是表行数，默认为空；`--refresh-row-count` 会连库跑一次
`COUNT(*)` 并把结果写回图谱（system 身份，只改这个字段，不产生候选）。大表慎用。

`describe` 的列行会带出业务名、语义类型和值域（`| 业务:... | 语义:... | 值域:...`）；
关系下面附一行可直接复制的 `JOIN ... ON ...` 写法。别名配置了 SM4 的列标 `[SM4]`
（仅支持等值/IN 查询）。末尾的 `Recent SQL` 是这张表最近的成功查询范例（脱敏 SQL、去重后最多
5 条，来自本机执行历史）。JSON 侧对应 `sm4Columns` / `recentSql` 两个键。
表名拼错时（describe/query），报错会带图谱里相近的表名建议。

执行 SQL 报「列/表不存在」时，错误信息会附图谱纠错提示（"相近的列: ..."）；SELECT 查回
0 行且 WHERE 等值比较的值不在图谱值域内时，stderr 会提示该列的已知值域。

`search` 的中文查询按二元切词，多个词可以跨字段命中（`买家 手机` 能命中业务名 `买家手机号`）。
每条结果带 `matchedField` / `matchedText`（命中在哪个字段、命中的原文）以及
`comment` / `businessName` / `semanticType`——拿到候选表就不必再逐个 `describe`。

文本输出按表折叠：表命中一行（业务名 | 注释 | 列数，不展开全部列），该表的命中列折叠成
摘要行（最多列 3 个，剩余显示计数）。默认显示前 15 组、JSON 默认前 50 条，`--limit N`
调整（`0` 不限），截断时有尾行/stderr 提示。要看完整列结构用 `schema describe`。

#### 检索质量评估

```bash
sql-cli app-dev schema search-eval --cases docs/search-eval-cases.example.yaml
sql-cli app-dev schema search-eval --cases docs/search-eval-cases.example.yaml --json
```

`--cases` 指向一份 YAML 评估集（每条 `query` + 期望命中的图谱对象 id 列表 `expect`，模板见
`docs/search-eval-cases.example.yaml`），输出 Top1/Top5/MRR 及未命中 case 列表。全部 case
有命中退出码 0，存在完全未命中的 case 退出码 1，适合接入 CI 盯检索质量回归。

### 13.4 编辑表和列语义

```bash
sql-cli app-dev schema edit \
  --table app.orders \
  --description '订单主表' \
  --business-name '订单' \
  --add-tag business

sql-cli app-dev schema edit \
  --column app.orders.buyer_id \
  --description '买家用户 ID' \
  --business-name '买家ID' \
  --semantic-type ref_id \
  --example '10001' \
  --add-tag relation-key \
  --add-constraint '关联 app.users.id'
```

一次 edit 必须且只能指定 `--table` 或 `--column`。示例数据可能被持久化到工作区，禁止录入真实密码、Token 或未脱敏的个人信息。

`--business-name`（`--table`、`--column` 都可用）设置业务名（简短称呼）；`--semantic-type`
（仅 `--column`）设置字段的业务语义类型，二者与 Web UI 表/列详情页的编辑写的是同一批
字段（`WorkspaceMutationService` 第 397-439 行），CLI 与 UI 不分叉。

`--semantic-type` 是固定枚举，不是自由文本——消费方是 `PolicyEvaluator` 的
`semanticTypeAny` 规则条件和搜索加权，二者都按值精确匹配，「手机号」「phone」「mobile」
三种写法自由文本一条也命中不了。取值列表见 `sql-cli app-dev schema edit --help`；传非法
值直接报错并列出全部合法取值（连中文标签），不会被静默解析成空值——那种静默兜底是给旧
图谱文件反序列化用的，命令行输入走那条路等于让 Agent 以为改成功了、实际字段没变。
传空串清空该字段。

`--boost N`（`--table`、`--column` 都可用）调整该对象在 `schema search` 里的排序权重，
只影响排序不影响能否命中，默认 1.0 且必须 > 0；核心表、高频字段调高（如 1.5），废弃或
干扰项调低（如 0.5），传 `1` 恢复默认。

#### 字段值域

状态、类型、标志类字段不写清楚取值，Agent 只能猜字面量——而猜错的后果往往是
**有结果、不报错、答案是错的**。按字段实际情况四选一：

| 情况 | 写法 |
|---|---|
| 值能穷举（≤ 几十个） | `--enum-values "0=待付款,1=已付款,2=已取消"`，含义不知道时只写值 |
| 值多、来自字典表 | **不内联**，建一条指向字典表的关系（[§13.6](#136-关系)） |
| 值无限但有格式 | `--format "SM4 密文，前缀 ENC#240606#"` |
| 无枚举也无格式 | `--example <一个真实值>`，可多次追加 |

```bash
sql-cli app-dev schema edit --column app.orders.status \
  --enum-values "0=待付款,1=已付款,2=已发货,3=已完成,4=已取消"

sql-cli app-dev schema edit --column app.orders.buyer_phone \
  --format "SM4 密文，前缀 ENC#240606#"
```

`--enum-values` 是**整体替换**而不是追加——值域是封闭集合，域变了要的是新的域而不是并集；
传空串表示清空。同一个值给出两个含义会被直接拒绝（退出码 1），
因为静默取其一会让 Agent 之后一直照着错的那个生成 SQL。

值域写好后出现在 `schema describe --json` 的 `valueHints` 里，
Web UI 图谱页表详情展开字段也能看到（只读，见 [§16.6](#166-图谱页)）。

哪些字段该补，不用自己找：`schema policy check` 的 `status_field_dictionary` 规则
会列出「语义/命名像状态列但没说清取值」的字段，那就是待办清单（[§14](#14-policy-规则审计)）。

#### 冗余副本 / 权威来源

宽表里常有下单那一刻写死的冗余快照，例如 `orders.customer_name`——权威源在
`customer.name`，搜索命中哪个用哪个，可能拿到的是历史快照而不是当前值，**查出来
有数、不报错，但不是想问的那个数**。`--redundant-of` 标注这个事实：

```bash
sql-cli app-dev schema edit --column app.orders.customer_name \
  --redundant-of app.customer.name
```

传空串清空标注；权威源列不存在或指向自身会直接报错（退出码 1），不会静默写入一个
指向空气的标注。标注写好后，`schema search` 命中 `orders.customer_name` 时会在结果里
带一条「冗余，权威源:app.customer.name」提示。

**这不是规范化建议**——不会提示「这张表不满足 3NF，建议拆分」，那是设计期的事，不是
这个产品的定位（见 `docs/graph-model-vs-er-model.zh-CN.md` §4.2）。这也不是自动发现：
判断「这个列是不是真的冗余」要靠人或 Agent 读代码/读数据得出结论，工具只负责把结论
存下来、在检索命中时带出来。

**字段语义写入后标着「待确认」。** `schema edit` 的写入者是 agent，写进去的
`--description` / `--business-name` / `--semantic-type` / `--enum-values` 立刻可读可检索，
但 `schema describe` 会在那一行末尾标 `[待确认]`，工作台的完整性看板有「字段语义待确认」计数。
人在 Web UI 的字段编辑里改过一次就算确认，标记消失。反过来，已确认的字段被 agent 又改了值，
那次确认自动作废——留着等于用旧值的确认给新值背书。

字段语义没有「候选→发布」这套闸门是有意的：关系的候选态表达「这条边存不存在」，
而业务名是个标量值，「不生效的值」这回事不存在，除非每个语义字段再存一份待发布的值。

### 13.5 业务术语

```bash
sql-cli app-dev schema add-term buyer \
  --display-name '买家' \
  --description '发起订单购买行为的用户' \
  --aliases '购买人,下单用户' \
  --map 'app.orders.buyer_id,app.users.id'
```

相同名称会更新术语。`--aliases` 和 `--map` 使用逗号分隔，映射目标必须是工作区中可解析的表或列。

### 13.6 关系

```bash
sql-cli app-dev schema add-relation \
  --type join_observed \
  --from app.orders.buyer_id \
  --to app.users.id \
  --join 'orders.buyer_id = users.id' \
  --confidence 0.9 \
  --verified
```

**关系类型只有三个**，按「谁维护」划分：

| 类型 | 端点 | 谁维护 | 最低 confidence |
|---|---|---|---|
| `foreign_key` | 列 → 列 | 数据库导入，人和 Agent 不能创建或编辑 | 1.0 |
| `join_observed` | 列 → 列 | 人和 Agent，导入不会覆盖 | 0.5（**必须显式传**） |
| `term_mapping` | 术语 → 表或列 | 一般由 `add-term --map` 创建 | 0.5 |

早期版本还有 `foreign_key_inferred` / `lineage_to` / `same_as` / `depends_on` / `used_with`，
没有任何代码创建过它们、图谱里也没有实例，已经删掉；读旧图谱时会被映射成
`join_observed`（`foreign_key_declared` 映射成 `foreign_key`），所以旧命令不会报错，
但**新写的命令请直接用上面三个**。

关系的语义靠 `--join` 表达，不靠类型名。比如字段的取值来自字典表，就是一条
`join_observed` 边加上带过滤条件的 join 表达式：

```bash
sql-cli app-dev schema add-relation \
  --type join_observed \
  --from app.orders.status \
  --to sys_dict.dict_item.dict_key \
  --join "orders.status = dict_item.dict_key AND dict_item.dict_type = 'order_status'" \
  --confidence 0.9
```

`dict_type` 这个过滤条件是关键——没有它，顺着这条边去查字典表会捞出全表所有业务的字典项。

端点必须存在，`confidence` 范围为 0 到 1，相同 type/from/to 的关系按统一标识更新。

#### 字段级血缘

关系记录的是「哪两个对象相关」，血缘记录的是「一个字段由哪些字段推导而来」，是独立于
关系的 n 元结构（一个 target 对多个 source），不是第四种关系类型：

```bash
sql-cli app-dev schema add-lineage \
  --target app.order_summary.total_amount \
  --source app.order_item.price,app.order_item.qty \
  --expression 'SUM(price*qty)' \
  --through view_order_summary

sql-cli app-dev schema lineage app.order_summary.total_amount --depth 2
sql-cli app-dev schema lineage app.orders.price --downstream --json
```

`--target` 恰好一个派生列，`--source` 逗号分隔至少一个源列，`--expression`（推导表达式）、
`--through`（视图名/ETL 作业/Mapper statement id）可选。写入以 agent 身份进候选，状态规则
同本章开头的候选说明。`schema lineage` 默认查上游（该列由谁推导而来），`--downstream` 查
下游（该列参与推导了谁），`--depth` 控制展开跳数。数据来源同关系——读代码写回，没有自动
扫描器。

#### BI 指标（metric）

指标（GMV、复购率……）是与 `terms` / `lineage` 平级的第四个图谱集合，装的是聚合口径
（`expression`）、口径过滤条件（`filters`，「算不算取消单」这类争议就在这一行）、时间粒度
（`grain`）、允许切的维度（`dimensions`）和跨表时人工声明的 JOIN 路径（`joinPath`）：

```bash
sql-cli app-dev schema add-metric gmv_paid \
  --expression "SUM(orders.amount)" \
  --filters "status IN (2,3)" \
  --business-name "已支付GMV" --aliases "GMV,已支付金额" \
  --grain-column app.orders.created_at --grains day,week,month \
  --dimensions app.orders.channel

sql-cli app-dev schema metrics --json
sql-cli app-dev schema metric gmv_paid --json
```

`--join-path` 声明跨表口径要走哪条关系边，格式是逗号分隔的 `relationId|joinType`
（`joinType` 为 `inner` 或 `left`），`relationId` 从 `schema describe`/`schema path --json`
的关系对象里取。这条路径是**人工权威声明，不是自动发现的产物**——指标口径本来就是要
扯皮的东西，JOIN 路径必须是权威声明而不是从关系图里猜出来的。

```bash
sql-cli app-dev schema expand-metric gmv_paid \
  --grain month --dimensions app.orders.channel \
  --time-from 2026-01-01 --time-to 2026-02-01
```

`expand-metric` 是指标价值兑现的唯一一步：把指标按请求的粒度和维度展开成可执行 SQL
骨架。`expression`/`filters` 原样拼进 SELECT/WHERE（不解析——解析聚合表达式是无底洞）；
JOIN 直接使用关系边上的 `joinExpression`（复合外键分组之后同一约束的多条边共享同一条
完整条件，展开时不会重新拼、也不会漏列）；时间粒度的截断函数按方言生成（MySQL/Oracle/
PostgreSQL/ClickHouse 写法不同）。引用的表/列/关系在图谱里不存在，或请求的粒度/维度超出
指标声明范围时会直接报错，不产出半截 SQL。写入同样以 agent 身份进候选，同名重写是幂等
更新，`--dimensions`/`--join-path` 传值是整体替换、传空串是清空。

指标也可以在 Web UI 的图谱页左侧「指标」标签里定义（见 §16.6）。两条路的差别只有一处：
**UI 写入的是人，指标直接生效；CLI 写入的是 agent，指标是候选。**

### 13.7 统计、校验、索引和关系图

```bash
sql-cli app-dev schema stats
sql-cli app-dev schema stats --json
sql-cli app-dev schema validate
sql-cli app-dev schema validate --json

sql-cli app-dev schema index rebuild
sql-cli app-dev schema index status
sql-cli app-dev schema index status --json

sql-cli app-dev schema diagram
sql-cli app-dev schema diagram --output docs/developer-graph
sql-cli app-dev schema diagram --json
```

`validate` 会把当前校验结果写回工作区。默认关系图目录为工作区下的 `developer-graph/`。

## 14. Policy 规则审计

> **规则的编写与启用现在建议走 Web UI 的规则页**（[§16.7](#167-规则页)）：
> 两个固定分组、13 个类别按表单填、可以「预填全部」一次补齐，绑定用勾选框开关。
> 手写 YAML 仍然可用，本节讲的是 CLI 侧的执行与审计——**这部分只有 CLI 有**。

### 14.1 执行规则

```bash
sql-cli app-dev schema policy check \
  --rules docs/schema-policy-ruleset.example.yaml

sql-cli app-dev schema policy check \
  --rules docs/schema-policy-ruleset.example.yaml \
  --all

sql-cli app-dev schema policy check \
  --rules docs/schema-policy-ruleset.example.yaml \
  --json
```

#### 作用域：`scope: change | all`

每条规则的 `scope` 决定它跑在哪里：

| scope | design review / migration lint | policy check（存量图谱） |
|---|---|---|
| `change`（`naming_convention` 的默认值） | 跑，且只看这次 DDL 碰到的表 | **不跑** |
| `all`（其余类别的默认值） | 同上 | 跑 |

`--all` 让这一次连 `scope: change` 的规则也跑；要长期打开就在规则里写 `scope: all`，
或在规则集的 `defaults.scope` 里统一指定。跳过了几条会在文本输出里说明。

**为什么默认收窄**：一个十年前的遗留库上最后一次 `policy check` 报了 1487 条违规、
其中 1215 条是 `naming_convention`，豁免 0 条，之后无人再跑——那些表名永远不会改，
而一份没人能执行的报告和没有报告是一样的。`money_type_decimal`（能改类型）和
`sensitive_column_policy`（合规相关）是真能处理的，所以留在全量检查里。

design review 与 migration lint 只报**这次 DDL 碰到的表**上的违规；候选工作区里其余表的
历史违规不会跟着倒出来。

规则评估只读取当前 GraphWorkspace、规则文件和有效 waiver，不会隐式查询数据库、修改表结构或自动创建 waiver。evaluation、violation、规则快照和 waiver 保存在工作区的 `policy/` 目录，policy 审计写入不增加图谱 revision。

当前结构规则类别包括：

- `naming_convention`
- `required_business_columns`
- `money_type_decimal`
- `primary_key_required`
- `sensitive_column_policy`
- `controlled_vocabulary`
- `business_unique_index`
- `relation_column_index`
- `join_column_type_match`
- `status_field_dictionary`
- `dangerous_dml_guard`
- `migration_safety_check`
- `dialect_sql_pattern`

前十类只读图谱事实，`schema policy check` 就能跑；后三类需要 SQL 或变更集输入，
只在 design review / migration lint 里生效。UI 规则页按这条线分成「结构规范」和「SQL 与迁移」
两个分组——把它们混在一个规则集里会让整次评估报错。

方言指导使用 `kind: dialect-ruleset` 和 `dialect_sql_pattern` 类别。完整版和 lite JAR 均内置 MySQL 8.0、PostgreSQL 14、Oracle 19c、ClickHouse 24.x 规则资产；design review 和 Migration lint 会传入待审 SQL。工作区 policy 既可显式传入 `--rules`，也可通过 alias 的 `policy/bindings.yaml` 长期绑定；内置方言规则的直接选择入口仍未提供。

存在未豁免的 `required` 或 `blocking` 违规时，命令返回状态码 1；只有 advisory 或全部已豁免时返回 0。

### 14.2 查询审计记录

```bash
sql-cli app-dev schema policy evaluation list
sql-cli app-dev schema policy evaluation list --json
sql-cli app-dev schema policy evaluation show <evaluation-id> --json
sql-cli app-dev schema policy violation list
sql-cli app-dev schema policy violation list --evaluation <evaluation-id> --json
```

evaluation 是不可变评估记录。violation fingerprint 保持稳定，用于精确追踪相同规则和目标。

### 14.3 Waiver

创建有期限的精确豁免：

```bash
sql-cli app-dev schema policy waiver add \
  --rule primary_key_required \
  --target 'table:app-dev:app:legacy_orders' \
  --reason '历史表迁移窗口内暂缓' \
  --expires-at 2026-08-31T23:59:59
```

查询和撤销：

```bash
sql-cli app-dev schema policy waiver list
sql-cli app-dev schema policy waiver list --active --json
sql-cli app-dev schema policy waiver revoke <waiver-id> --reason '整改完成'
```

waiver 只精确匹配 `ruleId + targetId`，不支持通配符，也不会自动跨 alias 生效。过期或已撤销 waiver 不再豁免后续评估。

## 15. 从 SQL / MyBatis 提取 JOIN 关系

`schema scan-sql` 已于 2026-08-20 移除。内置扫描器只能识别确定性等值 JOIN，对动态 SQL、
多层嵌套和别名链路误判率高，且维护成本远大于收益。现在 Mapper/`.sql` 文件的 JOIN 提取由
Agent（读代码）完成，写回统一走唯一的 `schema add-relation` 命令。

配方：让 Agent 读 `src/main/resources/**/*Mapper.xml` 与 `.sql`，对每条确定性 JOIN 生成一条命令：

```bash
sql-cli app-dev schema add-relation \
  --type join_observed \
  --from app.orders.user_id \
  --to app.users.id \
  --join 'orders.user_id = users.id' \
  --confidence 0.8
```

约束（写入前必读）：

- **`join_observed` 必须显式 `--confidence`**：它是唯一的推断类关系，没有默认值兜底，
  漏传直接拒绝（退出码 1），工作区不变。
- **`--verified` 要求 `--confidence >= 0.9`**：只有人工核对过、或代码里能读到明确外键
  语义的关系才配得上 verified，不要拿"读起来像"的 JOIN 直接标 verified。
- **`foreign_key` 不能手工创建**：该类型只由 `schema import --from-db` 从数据库
  外键约束产生。
- 端点类型必须匹配：`join_observed` 两端都是列（`schema.table.column`），
  `term_mapping` 的 from 端是术语。
- 建议在 `--join` 里原样保留代码中的 JOIN 表达式，便于回溯到来源文件。

置信度参考：代码里同名同义字段且有明确业务语义 0.8~0.9；只是字段名相似 0.5~0.6；
低于类型的最小置信度会被直接拒绝。

## 16. 本地 Web UI

### 16.1 启动

```bash
sql-cli ui                             # 不带别名也能起，进去自动选一个
sql-cli ui --alias app-dev             # 直接打开某个数据源
sql-cli app-dev ui                     # 同上，别名前置写法
sql-cli ui --alias app-dev --port 9090 --no-open
sql-cli ui --alias app-dev --port 0 --no-open
```

**不需要先导入图谱**。没有图谱的数据源在工作台里会显示「从数据库导入图谱」按钮，
从零开始的完整路径可以全程在 UI 里走完：设置页建数据源 → 工作台导入图谱 → 图谱页用起来。

服务只监听 `127.0.0.1`。`--port 0` 由操作系统分配空闲端口，终端会打印实际 URL。
不要通过反向代理暴露到公网。按 Ctrl+C 停止服务。

### 16.2 壳层：数据源上下文与状态灯

顶栏从左到右是：品牌链接、数据源输入框、三个状态灯、只读锁、当前 revision。

数据源用的是**带补全的输入框**而不是下拉——别名多起来时可以直接打前缀过滤；
只有输入值精确命中某个别名才会切换，打字过程中不会乱跳。切数据源保留当前页面，只换上下文。

**没选数据源时会自动选一个**（优先已导入图谱的），省掉「先去目录点一下」这一步。
数据源写在地址栏的 `?alias=` 上，可以直接把链接发给别人。

三个状态灯集中在顶栏，页面内不再重复渲染同一状态。鼠标停留看具体原因：

| 灯 | 绿 | 黄 | 红 |
|---|---|---|---|
| 图谱 | 已导入 | — | 未导入 |
| 连接 | 连接正常（带服务端版本） | 正在测试 | 连接失败（带原因） |
| 索引 | 已就绪 | 图谱变更后索引落后，或状态未知 | 索引缺失，搜索不可用 |

连接灯要真的连一次数据库，所以结果缓存 5 分钟且不跟随窗口聚焦重查。

左侧一级导航可以收起（只剩图标，偏好存在浏览器本地）。当前数据源不满足条件的页面
显示为禁用并在 title 里说明原因（「请先选择数据源」/「该数据源尚未导入图谱」），
而不是藏起来——藏起来会让人以为信息架构就这么几页。

### 16.3 五个页面

| 页面 | 内容 | 前提 |
|---|---|---|
| 工作台 | 数据源目录 / SQL 编辑器 + 图谱规模 + 需要关注 | 无 |
| 图谱 | 表目录与图谱导入、搜索、关系图、表详情、关系编辑、索引重建 | 选中数据源 |
| 规则 | policy 规则的讲解与编辑，见 [§14](#14-policy-规则审计) | 选中数据源 |
| 评审 | 待审批 / 审批记录 / 执行记录三个标签，都带筛选和分页 | 无 |
| 设置 | 数据源、密钥、审批开关、驱动 | 无 |

评审页**不跟随顶栏选中的数据源**：CLI 在别的库上等审批时，不该因为顶栏选的是另一个别名就看不见。

**入口唯一**：图谱导入和索引重建都只在图谱页，连接与驱动配置只在设置页，
其他页面需要时用跳转链接过去，不复制一份按钮。

图谱页**不要求已经有图谱**——导入的唯一入口就在它的表目录里，
按「有没有图谱」禁用这一页会把新数据源锁在门外。

### 16.4 工作台

未选数据源时是**数据源目录**：每张卡片显示类型、只读标记、表/关系数量，
以及「打开」和跳到图谱页的「图谱 / 去导入」。

选中数据源后是这个库的**健康视图**：

- **SQL 编辑器**——见下方
- **图谱规模**：schema / 表 / 字段 / 关系 / 业务术语五个计数
- **需要关注**：三张卡片，用绿黄红表示是否需要处理
  - 搜索索引——落后或缺失时给「去重建」跳到图谱页
  - 图谱校验——有未处理问题时给「去处理」
  - 审批——列出该数据源开了哪几类审批；开了给「去评审」，没开给「去设置」

图谱尚未导入时，这一页只剩一个跳到图谱页的「去图谱页导入」链接，
**但 SQL 编辑器仍然在**——跑 SQL 只需要连接，和图谱无关。
导入按 schema 逐个进行，挑哪个 schema 要看表目录，所以入口在图谱页而不是这里。
命令行等价写法是 `schema import --from-db --schema <schema>`。

#### SQL 编辑器

纯文本框，一次一条语句，**Ctrl / Cmd + Enter** 执行。
执行走的是和 CLI 完全相同的那条链路：SM4 透明加解密、行数上限与超时、
UPDATE/DELETE 必须带 WHERE、readonly 别名拒写、Yearning 只读、写操作自动生成恢复 SQL，
每次执行都落一条执行记录。

**按钮文案告诉你接下来会发生什么：**

| 语句 | 按钮 | 流程 |
|---|---|---|
| 只读 | 执行 | 直接跑，结果以表格展示 |
| 写（INSERT/UPDATE/DELETE/DDL …） | 执行 | 先做**预检**，确认后才真跑 |
| 写 + 该数据源开了增删改审批 | 提交审批 | 预检 → 确认 → 挂起等放行 |

写语句点下去不会直接执行，而是先出一个**确认面板**：目标表、预估影响行数、
能不能生成恢复 SQL（绿/黄状态点）、主键。拿不到的信息写「未知」，不编数字。
点「确认执行」才真正提交；改一下 SQL 面板就作废，需要重新预检。

开了审批时，提交后页面会停在「等待审批中」并给出到评审页的链接，
直到有人裁决（上限 10 分钟）。**别关页**——关了只是看不到结果，那条审批请求还在。
被拒绝时直接显示拒绝原因（readonly、无 WHERE、审批不通过都在这里）。

结果区复用和历史重放同一个表格组件，多一个**导出 CSV**（前端直接生成，
带 UTF-8 BOM，Excel 直接能开）。行数碰到上限时表格上方会出一条黄色提示，
写明只返回了前多少行。写操作执行完显示影响行数，生成了回滚 SQL 时提示到评审页查看。

图谱页表详情的「在工作台查询」图标会带着
`SELECT * FROM <schema>.<表> LIMIT 100` 跳到这里。

一次只能执行一条语句，多语句会被拒绝；因此结果区永远只有一个结果集，没有标签页。

#### 补全

边打边弹，浮层跟着光标走。**Ctrl / Cmd + Space** 手动唤出（光标停在空白处时用），
上下键选、Enter 或 Tab 采纳、Esc 关掉。

| 打什么 | 补什么 |
|---|---|
| `FROM men` | 表。按最近的关键字判断——FROM / JOIN / UPDATE / INTO 后面补表 |
| `SELECT na` `WHERE sta` | 语句里已引用表的列，其次是表 |
| `erp_plush_test.` | 这个 schema 下的表 |
| `m.` | 别名 `m` 那张表的列。别名认 `FROM 表 [AS] 别名` 这一种写法 |

候选右侧是**图谱里的业务含义**：表给注释，列给业务名和类型（`菜单编码 · VARCHAR`）。
所以图谱补得越全，补全越好用——这是它和普通 SQL 编辑器补全的区别。

排序是前缀 > 词边界 > 包含 > 子序列，同档短的在前。词边界指下划线后面也算词首
（`menu` 命中 `sys_menu`），子序列是打首字母（`sm` 命中 `sys_menu`）。

不补关键字（SELECT / FROM 这些）：在一个文本框里补它们是噪声，不是帮助。

### 16.4b 评审页的三个标签

三个标签共用一套骨架：**标题与标签 → 筛选条 → 列表 → 分页条**，换标签只换中间的内容。

| 标签 | 内容 | 筛选 |
|---|---|---|
| 待审批 | 状态固定为待审批，两秒轮询（对面有进程在等放行） | 类型、时间范围 |
| 审批记录 | 全部审批的历史，不轮询 | 状态、类型、时间范围 |
| 执行记录 | 见 §16.5 | schema、语句类型、状态、时间范围 |

「待审批」标签上的角标是**待审批总数**，和当前筛选无关，在三个标签上都看得见。

筛选和分页都在服务端做：分页之后「当前页里符合条件的那几条」和「符合条件的第 N 页」
是两回事，前端过滤一页数据会给出错的结果。改筛选条件会自动回到第一页。

### 16.5 执行记录（在评审页）

执行记录来自本地 SQLite（`~/.sql-cli/sqlcli.db`），在**评审页的第三个标签**，
每页 20 条，底部分页条显示当前区间和总页数（全站三个列表共用同一个分页组件）。

**成功、失败、被拒绝的语句都在里面。** 行首状态点：绿=成功，黄=被规则拦下（readonly、
无 WHERE、审批被拒），红=真的执行失败。

**四个筛选条件**（顶部一排下拉）：schema、语句类型、执行状态、时间范围
（全部 / 最近 1 小时 / 24 小时 / 7 天 / 30 天）。schema 取自语句里的 `schema.table` 限定名，
没写限定名时退回别名的默认 schema；选项就是这个数据源历史里实际出现过的那些。

**点 SQL 那一列展开**，里面是：

- **完整 SQL**——原文，按主子句换行排版过（不改大小写、不改语义）。
  列表里显示的是脱敏文本，展开看到的是真实值，所以 `WHERE id='<redacted>'`
  这种记录展开后能看清到底改的是哪一行。老库里迁移来的写语句没存原文，会注明。
- **失败原因 / 拒绝原因**——失败的语句点开就知道为什么，不用回终端翻日志。
- **数据源、schema、状态、实际影响行数**。

每行右侧的操作按语句类型分成两种：

**只读语句 → 「执行」按钮。** 保存了原文的记录点击原地重放，结果以表格展示
（列头带类型、数字右对齐、NULL 与长文本有专门呈现、超限时提示已截断）。
重放本身产生一条新的执行记录。只有脱敏文本的旧记录按钮禁用，说明「历史未保存原文，无法重放」。

**写语句 → 「回滚」按钮。** 展开后显示这次写操作的**回滚 SQL** 与**执行前的原始行**。
真要执行点「执行回滚」，再点一次「确认」才发请求；请求发出后**无条件走一次审批**
（`kind=recovery`，不受别名审批开关影响），批准前按钮显示「等待审批中」，
批准后单连接单事务逐条执行，全部成功才提交。没有回滚脚本的记录会提示读取失败。

### 16.6 图谱页

工具条：表目录折叠按钮、搜索框、重建索引按钮、当前视图规模。

- **重建索引按钮只在索引不是「就绪」时出现**——已经就绪时没有可做的事，
  状态本身在顶栏灯里看。这是全应用唯一的索引重建入口。
- 视图规模显示 `返回数/总数 表 · 返回数/总数 关系`，超出上限时标「已截断」
  （画布一次最多取 500 个节点、1000 条边）。

**图谱还没导入时这一页照样能进**，只是画布、搜索和索引三块降级：
表目录默认展开，工具条位置提示「还没有图谱，从左侧表目录按 schema 导入」。
表目录读的是数据库的 schema 清单，不依赖工作区，所以这条路走得通。

**表目录**（有图谱时默认收起）列的是**数据源里的全部 schema，不只是图谱里已有的**——
未导入的也要露出来，否则不知道还有什么可以导。系统 schema 默认折叠，可以一键显示。
每个 schema 后面带导入按钮，已导入的变成刷新；按 schema 逐个导而不是一次全导，
因为一个连接下十几个 schema、几千张表，全导会让图谱渲染和索引重建都变慢。
点 schema 名可以把画布过滤到这个 schema。

**左侧栏三个标签**（有图谱时才出现）：表目录 / 术语 / 指标。三者共用同一个位置而不是各开一块，
因为它们都是「按名字找东西」的入口。

**指标子视图**是人在 UI 里声明业务口径的地方：

- 列表显示口径表达式、**过滤条件**、时间列与粒度、维度。过滤条件单独占一行不是排版偏好——
  同一个「订单金额」算不算取消单、算不算退款单，大部分口径争议就在这一行
- 「新建指标」表单：名称、业务名、口径表达式、过滤条件、时间列、粒度、维度。
  列引用写 `schema.table.column`，和 CLI 的 `--dimensions` 完全一样，拼错了当场报错
  并指出是哪个字段的哪个值——静默吞掉的后果是展开出的 SQL 少一个 GROUP BY 列，
  不报错、有结果、数是错的
- 「展开 SQL」按选定粒度生成可执行 SQL（纯离线，不连库），再点「去工作台执行」
  把它送进工作台编辑器
- **在这里定义的指标直接生效**（`verified`），CLI `add-metric` 写的是候选。
  这不是权限大小的差别，是「人声明口径」和「Agent 猜口径」的差别
- 跨表口径的 `joinPath` 目前只展示不编辑，要改用
  `sql-cli <alias> schema add-metric --join-path relId|inner`

**表详情**（选中表时出现在右侧）：

- 顶部「在工作台查询」带着 `SELECT * FROM <schema>.<表> LIMIT 100` 跳到工作台并填好
- 顶部「刷新表结构」从数据库重读字段、类型和注释；**业务描述、语义类型和自建关系不会被覆盖**
- 字段列表带类型、长度、可空性三态灯和主键标记；展开单个字段可维护业务名与语义类型
- 关系分入向 / 出向两组；Agent 写入的候选关系单独成「待审核」分组（琥珀色边框），
  可逐条或勾选多条批量发布/拒绝（`POST /api/relations/review`），发布后才进入正式图谱
- 校验问题就地列出
- **行数默认不查**，点采集按钮才走一次 `COUNT(*)`（大表可能要几秒）

### 16.7 规则页

左侧是**两个固定分组**，不能自建规则集：

| 分组 | 什么时候执行 |
|---|---|
| 结构规范 | 设计新表与审查 DDL 时（`schema design review`） |
| SQL 与迁移 | 审查迁移脚本时（`schema migration lint`） |

分组固定是因为两类规则在评估器里本来就不能混——SQL 类规则拿不到 SQL 输入会让整次评估报错。
规则集的 id、文件名、版本、匹配条件全部内部生成，不需要填。

每个分组前有状态点：灰=还没有规则，绿=已启用校验，黄=有规则但未启用（`policy check` 不会执行它）。
「启用校验」是分组顶部的勾选框，对应 `policy/bindings.yaml` 里的绑定。

主区默认是**规则讲解**（每个类别是什么、什么时候用）。添加规则时按类别选，
也可以「预填全部 N 条」一次把这个分组缺的类别都补上，每条带默认值，之后照常改照常删。
删到一条不剩会连整个规则文件一起删——规则集至少要有一条规则才合法。

### 16.8 评审页

两个标签：**待审批**（带数量徽标，每 2 秒轮询一次）和**全部**（含已批准/已拒绝/已作废）。

每张卡片显示类型（查询 / 更新 / 图谱）、别名、状态灯、时间和脱敏摘要，
「查看原文」展开未脱敏的完整语句。裁决时填理由——**拒绝必填，批准可不填**。

审批开关见 [§16.9](#169-审批开关)。

### 16.9 审批开关

按数据源配置，默认全部关闭（关闭时操作直接执行）：

```yaml
aliases:
  prod:
    approveQuery: false        # 查询：阻塞等人
    approveUpdate: true        # 增删改：阻塞等人
    graphApproval: manual      # 图谱：auto（默认，自动批准并留底）| manual（排队等人）
```

`graphApproval` 取代了原来的布尔 `approveGraph`（老写法仍然读得懂，`true` = `manual`）。
换成两个取值是因为「关掉审批」和「自动批准」不是一回事：**两种模式的审批记录一样全**
——谁改的、哪次会话、什么时候、属于哪一批一个不少——区别只是要不要等人。
CI 和定时脚本用 `auto`，没人点批准也不会卡死。

也可以在**设置页的数据源表格里直接勾选**——这是会被反复切换的运行时策略
（上线前打开、验完关掉），所以放在列表里而不是埋进编辑表单。

打开后，对应操作会**阻塞在发起方**——终端里的 CLI 停在那里等，UI 里的请求挂起——
直到有人在评审页裁决。10 分钟无人处理自动作废，而**不是自动放行**：
隔了那么久，当初的判断依据已经失效。同时挂起的操作上限为 8 个，超出的直接失败并说明原因。

> **给 Agent 用的别名要留意这一条。** Agent 通过 CLI 操作开了审批开关的别名同样会被阻塞，
> 而且它看不到评审页。要么关掉开关，要么让命令挂起时把它丢后台，用下面的 `task` 命令回查，
> 而不是干等。

阻塞的命令会打印 `Task: <id>  (sql-cli task status <id>)`：

```bash
sql-cli task list                         # 最近的写任务，默认 20 条
sql-cli task list --alias app-dev --status pending --json
sql-cli task status 34
sql-cli task status 34 --json             # 状态 + 事件时间线
```

`task list` 支持 `--alias`、`--status`、`--limit` 过滤。两者都是顶层命令（不接在别名后面），
按 id 读 `task_run` 及关联事件，纯查询，不会被审批阻塞。

### 16.9.1 批次：多条变更一次提交、一次裁决

一个业务需求要改几十处时，逐条审批会把队列淹掉，人只能盲批。把它们归成一批：

```bash
sql-cli prod batch begin --intent "梳理报事域状态字段"    # 返回批次号，比如 77
sql-cli prod schema edit --column ... --batch 77          # 只入批次，图谱不动
sql-cli prod batch submit 77                              # 封口，整批进待审批
sql-cli prod batch status 77                              # 哪条被否、理由是什么
sql-cli prod batch list --draft                           # 还没提交的批次
sql-cli prod batch discard 77                             # 丢掉一个草稿批次
```

`--intent` 必填：评审页上一批就是一张卡，标题就是这句话。

SQL 也能进批次（`batch begin --kind sql`，语句加 `--batch 77` 即**不执行**，只入批）。
一批只装同一类，混装会被拒绝。

**落地保证**

| 类型 | 保证 |
|---|---|
| 图谱批次 | 一次 load / apply / save，**要么全进，要么图谱一个字节没动** |
| SQL 批次（DML） | 单连接单事务按 `seq` 执行，任一条失败整批 rollback；回滚脚本按批一份、语句逆序 |
| SQL 批次（含 DDL） | 允许，但 `submit` 时强制标注**不可回滚**——DDL 隐式提交，rollback 对它无效 |

**允许部分批准**：评审页上每条有「否掉这条」，卡片底部「批准这一批」落地其余的，
批次状态记 `partial`。被否的条目带着理由回来，`batch status` 取得到——
**按理由改完再提，不要原样重提**。

`draft` 批次没 `submit` 就等于没写：它既不在图谱 / 数据库里，也不在待审批队列里。

### 16.9.2 会话身份：这串改动出自哪次会话

```bash
sql-cli session begin --agent claude      # 一次会话开头做一次
sql-cli session status
sql-cli session end
```

之后**这个工作目录下**的每条执行记录和每条审批都会带上会话号与 agent 名。
按工作目录隔离而不是按进程：agent 每个命令都是新 shell，环境变量不跨调用。

优先级：`--session <id>` 参数 > 状态文件 > `SQLCLI_SESSION` 环境变量（面向 CI）> 空。
**取不到就留空，不会编一个占位 id**——每条命令一个新号等于没有会话。

`--session` / `--agent` / `--batch` 是全局参数，任何命令后面都能加。

### 16.10 设置页

**数据源**标签的表格列出名称、类型、图谱规模、是否只读、三个审批开关，
行内操作是三个图标「编辑 / 测试连接 / ⋯」（说明在鼠标停留的提示里），
次要操作（设置密码、删除数据源）收在 ⋯ 菜单里。

- 新建与编辑用同一个表单：访问方式（jdbc / yearning）、类型、驱动、JDBC URL、用户名、
  密钥引用、默认 schema、只读开关、描述
- 测试连接就地出结果，不写进缓存
- 设置密码是行内输入框，值不进任何缓存与全局状态，提交后立即清空，响应体也不含它
- 删除数据源要二次确认，**图谱数据与已存密码不会一起删掉**，需要另行清理
- 仅 CLI 可用的只剩 `sql-cli <alias> secret status / delete`

**驱动**标签列出名称、类型、驱动类、jar 状态灯、被引用数：

- jar 状态：**绿=可加载，黄=文件在但驱动类加载不出来，红=文件不存在**。
  这一列是为了替代「连接失败」——jar 路径写错或下载到一个 0 字节的占位文件时，
  用户原本看到的是连接超时，离根因隔了十万八千里
- 新建/编辑时按类型预填驱动类；jar 可以**直接上传**，也可以手填路径
- 被数据源引用的驱动不能删（按钮禁用并列出占用方）；每种类型可以指定一个默认驱动

### 16.11 并发与错误

写请求使用随机会话 Token、Origin 校验和 workspace revision 乐观锁。
发生 409 revision 冲突、423 写锁冲突或网络错误时，界面保留草稿并允许恢复或重试。

Origin 校验把 `localhost`、`127.0.0.1`、`::1` 视为同一台主机——用哪种写法访问都能写入。
（早期版本只认服务器启动时打印的那一种，另一种写法下所有写操作 403、读操作却正常，
症状看起来像「保存失败」而不是「跨域被拦」。）

## 17. Yearning 模式

Yearning alias 示例：

```yaml
aliases:
  prod-audit:
    accessMode: yearning
    yearningHost: https://yearning.example.com
    yearningIdc: production
    yearningDatabase: app
    username: audit_user
    secretRef: env:YEARNING_TOKEN
    readonly: true
```

```bash
sql-cli prod-audit test
sql-cli prod-audit "SELECT id, status FROM orders WHERE id = 1001" -f json
```

`secretRef` 在 Yearning 模式中表示认证 Token。Yearning 模式不加载 JDBC 驱动、不支持 `info`、实时 `tables/ddl` 和任何写语句。实际兼容性受部署的 Yearning 版本和接口配置影响。

## 18. 自动化契约与退出码

常规退出码：

| 状态码 | 含义 |
|---|---|
| `0` | 成功；或无数据但命令正常完成 |
| `1` | 连接、执行、I/O、校验或 required/blocking policy 失败 |
| `2` | 命令、参数、单语句契约或输入用法错误 |
| `130` | Schema UI 被中断并完成停止处理 |

Shell 自动化示例：

```bash
if result=$(sql-cli app-dev "SELECT 1 AS healthy" -f json); then
  printf '%s\n' "$result"
else
  code=$?
  printf 'sql-cli failed, exit=%s\n' "$code" >&2
  exit "$code"
fi
```

面向程序消费时优先使用 `--json` 或 `-f json`，同时检查进程退出码和 JSON 的 `ok` 字段。不要仅根据错误文本做分支判断。

## 19. 数据库差异

### MySQL

- 表枚举按 catalog 工作，输出 schema 可能为空。
- DDL 使用 `SHOW CREATE TABLE`。
- 建议 JDBC URL 明确时区、字符集和 TLS 参数。

### PostgreSQL

- 默认 schema 通常为 `public`，也可显式 `--schema`。
- 注意大小写标识符和 `search_path`。

### Oracle

- 同时支持 SID 与 SERVICE_NAME URL。
- 自动应用 `oracle.jdbc.javaNetNio=false`、`oracle.net.disableOob=true` VPN 兼容默认值。
- 表和 DDL 操作建议显式提供大写 owner/schema。

### ClickHouse

- 默认 JDBC HTTP 端口通常为 8123，HTTPS 通常为 8443。
- 不支持标准 UPDATE/DELETE、事务和恢复 SQL。
- 使用对应 ClickHouse 语法及只读账号，生产 alias 建议 `readonly: true`。

## 20. 常见故障

### Unknown alias

```text
Error: Unknown alias: app-dev
```

检查当前工作目录、`SQLCLI_ALIASES_PATH`、`settings.yaml` 的 `aliasesPath`，再执行 `sql-cli list`。

### Unknown driverRef / Driver not found

确认 `driverRef` 存在、dbType 一致、JAR 路径有效、驱动类正确。相对 JAR 路径按启动工作目录解析。

### Environment secret not found

```bash
export APP_DB_PASSWORD='...'
sql-cli app-dev secret status
```

确认环境变量已导出到启动 sql-cli 的同一进程环境。

### Multiple statements are not supported

拆成多个独立调用，每次只执行一条语句。不要通过拼接或 SQL 文件绕过该安全约束。

### UPDATE/DELETE rejected

确认存在有效 WHERE、表具有可靠主键、SQL 不含恢复生成不支持的复杂结构。若数据库策略本身不支持恢复 SQL，应改用数据库原生、已审批的变更流程。

### Schema 工作区不存在

```bash
sql-cli app-dev schema import --from-db
```

若导入中断，先查看 `schema import status`，再决定 `resume` 或 `reset`。

### 索引过时

```bash
sql-cli app-dev schema index rebuild
sql-cli app-dev schema index status
```

### Web UI 端口冲突

```bash
sql-cli ui --alias app-dev --port 0 --no-open
```

使用终端打印的实际 URL。

## 21. 开发者验证

Java 全量测试：

```bash
mvn test
```

单个测试类：

```bash
mvn test -Dtest=Sm4SqlCipherParserTest
```

前端静态检查、构建和单元测试：

```bash
cd web
npm ci
npm run lint
npm run build
npm test
```

Playwright CLI 浏览器 E2E 使用本机 Chrome：

```bash
cd web
npm run test:e2e
```

发布前还应验证 `target/sql-cli.jar`、macOS/Linux shell 脚本和 Windows batch 脚本，并对目标数据库执行受控集成测试。测试环境不得使用生产写权限账号。

## 22. 安全使用清单

- 生产 alias 默认设置 `readonly: true`，并使用数据库侧只读账号双重限制。
- 密码使用 `env:*`、`keyring:*` 或 `encrypted:*`，不提交明文凭据。
- 每次只执行一条 SQL；写操作先在测试环境验证影响行数和恢复 SQL。
- 将 `~/.sql-cli/recovery/` 和 Schema 工作区按敏感数据管理。
- 自动化同时检查退出码与 JSON `ok`。
- Web UI 只在本机使用，不对公网监听或转发。
- 定期升级 JDBC 驱动和依赖，并执行 Maven/npm 安全审计与全量回归。
