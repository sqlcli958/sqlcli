# CLI 命令测试用例清单

## 测试前置数据

| 编号 | 前置项 | 说明 |
|---|---|---|
| P-001 | `mysql_ok` | 可连接的 MySQL alias，配置 `secretRef`、`driverRef`、`decryptColumns`、`sm4Key` |
| P-002 | `oracle_ok` | 可连接的 Oracle alias，用户名可作为默认 schema |
| P-003 | `yearning_ok` | `accessMode=yearning` 的可用 alias |
| P-004 | `readonly_ok` | `readonly=true` 的 alias |
| P-005 | `missing_secret` | `secretRef` 已配置但密钥不存在的 alias |
| P-006 | `graph_ok` | 已通过 `schema import --from-db` 生成图谱工作区的 alias |
| P-007 | `graph_empty` | 未生成图谱工作区的 alias |
| P-008 | `mysql8_test` | 可新增、更新、删除的测试 driver 名称 |
| P-009 | `tmp/sqlcli-query.sql` | 内容为 `SELECT 1 AS id` 的 SQL 文件 |
| P-010 | `tmp/workspace-export.json` | 可写入的图谱快照导出路径 |
| P-017 | `multi_schema_ok` | 可连接的 alias，其数据库至少暴露 2 个非系统 schema（下称 `schema_a`、`schema_b`），用于验证按 schema 逐个导入 |

## 顶层命令

| 用例编号 | 命令 / 参数 | 场景 | 前置条件 | 预期结果 |
|---|---|---|---|---|
| TOP-001 | `sql-cli` | 无参数启动 | 无 | 输出顶层帮助，退出码为 0 |
| TOP-002 | `sql-cli -h` | 短帮助参数 | 无 | 输出帮助，包含 `list`、`alias`、`driver`、`crypto`、`schema` |
| TOP-003 | `sql-cli --help` | 长帮助参数 | 无 | 输出帮助，退出码为 0 |
| TOP-004 | `sql-cli -V` | 短版本参数 | 无 | 输出版本号 |
| TOP-005 | `sql-cli --version` | 长版本参数 | 无 | 输出版本号 |
| TOP-006 | `sql-cli --debug mysql_ok "SELECT 1"` | debug 放在 alias 前 | P-001 | 报错提示 `--debug` 应放在 alias 后 |
| TOP-007 | `sql-cli unknown-system-command` | 未知首参数被视作 alias | 无 | 输出 alias 提示或 SQL 缺失错误 |
| TOP-008 | `sql-cli mysql_ok` | 只输入 alias | P-001 | 输出该 alias 可用动作提示 |

## Alias 列表与管理

| 用例编号 | 命令 / 参数 | 场景 | 前置条件 | 预期结果 |
|---|---|---|---|---|
| ALIAS-001 | `sql-cli list` | 顶层列出 alias | 至少存在一个 alias | 输出 alias 列表 |
| ALIAS-002 | `sql-cli list --json` | 顶层 JSON 输出 alias | 至少存在一个 alias | 输出 JSON 数组 |
| ALIAS-003 | `sql-cli list -j` | 顶层 JSON 短参数 | 至少存在一个 alias | 输出 JSON 数组 |
| ALIAS-004 | `sql-cli alias` | alias 命令无 action | 无 | 输出 alias 帮助 |
| ALIAS-005 | `sql-cli alias list` | 验证该命令不存在（`AliasCommand` 只有 show/add/update/remove 子命令，列表入口是顶层 `sql-cli list`，见 user-manual §5.1） | 无 | 报错未知子命令，退出码非 0 |
| ALIAS-006 | `sql-cli alias show mysql_ok` | 查看已存在 alias | P-001 | 输出连接信息，敏感字段被脱敏 |
| ALIAS-007 | `sql-cli alias show not_exists` | 查看不存在 alias | 无 | 报错 `Unknown alias` |
| ALIAS-008 | `sql-cli alias add tmp_mysql --db-type mysql --driver-ref mysql8 --jdbc-url jdbc:mysql://127.0.0.1:3306/test --username root --secret-ref env:MYSQL_PASSWORD --description "tmp mysql"` | 使用 JDBC URL 新增 MySQL alias | `mysql8` driver 存在 | 创建成功，aliases 文件出现 `tmp_mysql` |
| ALIAS-009 | `sql-cli alias add tmp_mysql_parts --db-type mysql --driver-ref mysql8 --host 127.0.0.1 --port 3306 --database test --username root --secret-ref env:MYSQL_PASSWORD` | 使用 host/port/database 新增 MySQL alias | `mysql8` driver 存在 | 创建成功 |
| ALIAS-010 | `sql-cli alias add tmp_oracle_service --db-type oracle --driver-ref oracle19 --host 127.0.0.1 --port 1521 --service-name orclpdb1 --username scott --secret-ref env:ORACLE_PASSWORD` | 使用 serviceName 新增 Oracle alias | `oracle19` driver 存在 | 创建成功 |
| ALIAS-011 | `sql-cli alias add tmp_oracle_sid --db-type oracle --driver-ref oracle19 --host 127.0.0.1 --port 1521 --sid orcl --username scott --secret-ref env:ORACLE_PASSWORD` | 使用 SID 新增 Oracle alias | `oracle19` driver 存在 | 创建成功 |
| ALIAS-012 | `sql-cli alias add tmp_yearning --access-mode yearning --yearning-host http://127.0.0.1 --yearning-idc dev --yearning-database test --secret-ref env:YEARNING_TOKEN` | 新增 Yearning alias | 无 | 创建成功 |
| ALIAS-013 | `sql-cli alias add tmp_readonly --db-type mysql --driver-ref mysql8 --jdbc-url jdbc:mysql://127.0.0.1:3306/test --username root --secret-ref env:MYSQL_PASSWORD --readonly true` | 新增只读 alias | `mysql8` driver 存在 | 创建成功，`readonly=true` |
| ALIAS-014 | `sql-cli alias add tmp_sm4 --db-type mysql --driver-ref mysql8 --jdbc-url jdbc:mysql://127.0.0.1:3306/test --username root --secret-ref env:MYSQL_PASSWORD --sm4-key 88ED6EA3C8054CD9 --sm4-private-tag ENC --sm4-version 240606 --decrypt-columns phone,account` | 新增带 SM4 配置 alias | `mysql8` driver 存在 | 创建成功，解密列被保存 |
| ALIAS-015 | `sql-cli alias add invalid_missing_driver --db-type mysql --username root --secret-ref env:MYSQL_PASSWORD` | 新增缺少 driverRef | 无 | 报错 `driverRef is required` |
| ALIAS-016 | `sql-cli alias add invalid_missing_user --db-type mysql --driver-ref mysql8 --secret-ref env:MYSQL_PASSWORD` | 新增缺少 username | `mysql8` driver 存在 | 报错 `username is required` |
| ALIAS-017 | `sql-cli alias add invalid_missing_secret --db-type mysql --driver-ref mysql8 --username root` | 新增缺少 secretRef | `mysql8` driver 存在 | 报错 `secretRef is required` |
| ALIAS-018 | `sql-cli alias add invalid_mysql_no_database --db-type mysql --driver-ref mysql8 --host 127.0.0.1 --username root --secret-ref env:MYSQL_PASSWORD` | MySQL 无 URL 且无 database | `mysql8` driver 存在 | 报错 `jdbcUrl or database is required for mysql` |
| ALIAS-019 | `sql-cli alias add invalid_oracle_no_service --db-type oracle --driver-ref oracle19 --host 127.0.0.1 --username scott --secret-ref env:ORACLE_PASSWORD` | Oracle 无 URL 且无 serviceName/SID | `oracle19` driver 存在 | 报错 `jdbcUrl or serviceName/sid is required for oracle` |
| ALIAS-020 | `sql-cli alias add invalid_url_type --db-type mysql --driver-ref mysql8 --jdbc-url jdbc:postgresql://127.0.0.1/test --username root --secret-ref env:MYSQL_PASSWORD` | dbType 与 JDBC URL 不一致 | `mysql8` driver 存在 | 报错 dbType 不匹配 |
| ALIAS-021 | `sql-cli alias update tmp_mysql --description "updated"` | 更新描述 | ALIAS-008 已执行 | 更新成功 |
| ALIAS-022 | `sql-cli alias update tmp_mysql --readonly false` | 更新只读标志 | ALIAS-008 已执行 | 更新成功 |
| ALIAS-023 | `sql-cli alias update not_exists --description "x"` | 更新不存在 alias | 无 | 报错 `Unknown alias` |
| ALIAS-024 | `sql-cli alias remove tmp_mysql` | 删除 alias | ALIAS-008 已执行 | 删除成功 |
| ALIAS-025 | `sql-cli alias remove not_exists` | 删除不存在 alias | 无 | 报错 `Unknown alias` |
| ALIAS-026 | `sql-cli alias unknown` | 未知 alias action | 无 | 输出 `Unknown action` |

## Driver 管理

| 用例编号 | 命令 / 参数 | 场景 | 前置条件 | 预期结果 |
|---|---|---|---|---|
| DRIVER-001 | `sql-cli driver` | driver 命令无 action | 无 | 输出 driver 帮助 |
| DRIVER-002 | `sql-cli driver list` | 列出 driver | 至少存在一个 driver | 输出 driver 列表 |
| DRIVER-003 | `sql-cli driver list --json` | JSON 输出 driver | 至少存在一个 driver | 输出 JSON 数组 |
| DRIVER-004 | `sql-cli driver list -j` | JSON 短参数 | 至少存在一个 driver | 输出 JSON 数组 |
| DRIVER-005 | `sql-cli driver show mysql8` | 查看已存在 driver | `mysql8` driver 存在 | 输出 driver 详情 |
| DRIVER-006 | `sql-cli driver show not_exists` | 查看不存在 driver | 无 | 报错 `Unknown driver` |
| DRIVER-007 | `sql-cli driver add mysql8_test --db-type mysql --driver-class com.mysql.cj.jdbc.Driver --jar ./drivers/mysql/mysql-connector-j.jar` | 新增 driver | jar 路径可用于配置 | 创建成功 |
| DRIVER-008 | `sql-cli driver add invalid_no_type --driver-class com.mysql.cj.jdbc.Driver --jar ./drivers/mysql/mysql-connector-j.jar` | 新增缺少 dbType | 无 | 报错 `dbType is required` |
| DRIVER-009 | `sql-cli driver add invalid_no_class --db-type mysql --jar ./drivers/mysql/mysql-connector-j.jar` | 新增缺少 driverClass | 无 | 报错 `driverClass is required` |
| DRIVER-010 | `sql-cli driver add invalid_no_jar --db-type mysql --driver-class com.mysql.cj.jdbc.Driver` | 新增缺少 jar | 无 | 报错 `at least one --jar is required` |
| DRIVER-011 | `sql-cli driver update mysql8_test --jar ./drivers/mysql/mysql-connector-j-new.jar` | 更新 driver jar | DRIVER-007 已执行 | 更新成功 |
| DRIVER-012 | `sql-cli driver update not_exists --jar ./drivers/mysql/mysql-connector-j.jar` | 更新不存在 driver | 无 | 报错 `Unknown driver` |
| DRIVER-013 | `sql-cli driver default --db-type mysql --driver-ref mysql8` | 设置默认 driver | `mysql8` driver 存在 | 设置成功 |
| DRIVER-014 | `sql-cli driver default --db-type mysql --driver-ref not_exists` | 默认 driver 指向不存在引用 | 无 | 报错 `Unknown driver` |
| DRIVER-015 | `sql-cli driver default --driver-ref mysql8` | 缺少 dbType | `mysql8` driver 存在 | 报错 `--db-type is required` |
| DRIVER-016 | `sql-cli driver default --db-type mysql` | 缺少 driverRef | 无 | 报错 `--driver-ref is required` |
| DRIVER-017 | `sql-cli driver remove mysql8_test` | 删除 driver | DRIVER-007 已执行 | 删除成功 |
| DRIVER-018 | `sql-cli driver remove not_exists` | 删除不存在 driver | 无 | 报错 `Unknown driver` |
| DRIVER-019 | `sql-cli driver unknown` | 未知 driver action | 无 | 输出 `Unknown action` |

## Crypto 命令

| 用例编号 | 命令 / 参数 | 场景 | 前置条件 | 预期结果 |
|---|---|---|---|---|
| CRYPTO-001 | `sql-cli crypto` | crypto 无子命令 | 无 | 正常退出，无业务输出 |
| CRYPTO-002 | `sql-cli crypto sm4 --encrypt --text 13800138000 --key 88ED6EA3C8054CD9` | 使用 key 加密 | 无 | 输出以 `ENC#240606#` 开头的密文 |
| CRYPTO-003 | `sql-cli crypto sm4 --decrypt --text <cipher> --key 88ED6EA3C8054CD9` | 使用 key 解密 | CRYPTO-002 输出密文 | 输出原文 |
| CRYPTO-004 | `sql-cli crypto sm4 --encrypt --text 13800138000 --key 88ED6EA3C8054CD9 --private-tag PII --version 250101` | 自定义 tag 和版本 | 无 | 输出以 `PII#250101#` 开头的密文 |
| CRYPTO-005 | `sql-cli crypto sm4 --encrypt --text 13800138000 --alias mysql_ok` | 使用 alias 的 SM4 配置加密 | P-001 | 输出密文 |
| CRYPTO-006 | `sql-cli crypto sm4 --decrypt --text <cipher> --alias mysql_ok` | 使用 alias 的 SM4 配置解密 | P-001，已有密文 | 输出原文 |
| CRYPTO-007 | `sql-cli crypto sm4 --text 13800138000 --key 88ED6EA3C8054CD9` | 未指定加密或解密 | 无 | 报错必须指定一个 `--encrypt` 或 `--decrypt` |
| CRYPTO-008 | `sql-cli crypto sm4 --encrypt --decrypt --text 13800138000 --key 88ED6EA3C8054CD9` | 同时指定加密和解密 | 无 | 报错必须且只能指定一个 |
| CRYPTO-009 | `sql-cli crypto sm4 --encrypt --text 13800138000` | 缺少 alias/key | 无 | 报错 `Either --alias or --key is required` |
| CRYPTO-010 | `sql-cli crypto sm4 --encrypt --alias mysql_ok` | 缺少必填 text | P-001 | picocli 报错缺少 `--text` |
| CRYPTO-011 | `sql-cli crypto sm4 --encrypt --text 13800138000 --alias alias_without_sm4` | alias 缺少 sm4Key | alias 存在但无 sm4Key | 报错 alias 缺少 sm4Key |

## SQL 执行

| 用例编号 | 命令 / 参数 | 场景 | 前置条件 | 预期结果 |
|---|---|---|---|---|
| SQL-001 | `sql-cli mysql_ok "SELECT 1 AS id"` | 默认 CSV 查询 | P-001 | 输出 CSV，包含 `id` 和 `1` |
| SQL-002 | `sql-cli mysql_ok "SELECT 1 AS id" -f csv` | CSV 输出 | P-001 | 输出 CSV |
| SQL-003 | `sql-cli mysql_ok "SELECT 1 AS id" -f table` | table 输出 | P-001 | 输出表格 |
| SQL-004 | `sql-cli mysql_ok "SELECT 1 AS id" -f json` | JSON 输出 | P-001 | 输出 JSON |
| SQL-005 | `sql-cli mysql_ok "SELECT 1 AS id" --format json` | format 长参数 | P-001 | 输出 JSON |
| SQL-006 | `sql-cli mysql_ok --file tmp/sqlcli-query.sql` | 从文件执行 SQL | P-001, P-009 | 输出查询结果 |
| SQL-007 | `sql-cli mysql_ok -F tmp/sqlcli-query.sql` | 从文件执行 SQL 短参数 | P-001, P-009 | 输出查询结果 |
| SQL-008 | `sql-cli mysql_ok --file tmp/not-exists.sql` | SQL 文件不存在 | P-001 | 报错 `File not found` |
| SQL-009 | `sql-cli mysql_ok` | 未提供 SQL | P-001 | 输出 alias 提示 |
| SQL-010 | `sql-cli mysql_ok -f json` | 只有参数没有 SQL | P-001 | 报错 `No SQL provided` |
| SQL-011 | `sql-cli mysql_ok "SELECT 1; SELECT 2"` | 多语句执行 | P-001 | 依次输出 Statement 1/2 和 Statement 2/2 |
| SQL-012 | `sql-cli mysql_ok "SELECT ';' AS semi; SELECT 2"` | 字符串中包含分号 | P-001 | 正确拆分为两条语句，不在字符串分号处拆分 |
| SQL-013 | `sql-cli mysql_ok "SELECT '-- ;' AS comment_like; SELECT 2"` | 字符串中包含注释和分号 | P-001 | 正确拆分和执行 |
| SQL-014 | `sql-cli mysql_ok --debug "SELECT 1"` | 开启 debug | P-001 | 查询成功，debug 系统属性生效 |
| SQL-015 | `sql-cli mysql_ok -d "SELECT 1"` | debug 短参数 | P-001 | 查询成功，debug 系统属性生效 |
| SQL-016 | `sql-cli mysql_ok "SELECT phone FROM users WHERE phone='13800138000'" --decrypt-cols phone` | 临时指定加密/解密列 | P-001 | SQL 执行前明文条件被加密，结果列尝试解密 |
| SQL-017 | `sql-cli mysql_ok "SELECT phone FROM users" --decrypt-cols phone --no-decrypt` | 禁用输出解密 | P-001 | 返回原始密文 |
| SQL-018 | `sql-cli alias_without_sm4 "SELECT phone FROM users" --decrypt-cols phone` | 指定解密列但 alias 缺少 sm4Key | alias 存在但无 sm4Key | 报错缺少 sm4Key |
| SQL-019 | `sql-cli readonly_ok "SELECT 1"` | 只读 alias 执行 SELECT | P-004 | 查询成功 |
| SQL-020 | `sql-cli readonly_ok "UPDATE t SET c=1"` | 只读 alias 执行 UPDATE | P-004 | 拒绝执行写操作 |
| SQL-021 | `sql-cli mysql_ok "UPDATE t SET c=1 WHERE id=1"` | 非只读 alias 执行 UPDATE | P-001 | 执行更新，生成恢复 SQL |
| SQL-022 | `sql-cli mysql_ok "DELETE FROM t WHERE id=1"` | 非只读 alias 执行 DELETE | P-001 | 执行删除，生成恢复 SQL |
| SQL-023 | `sql-cli mysql_ok "INSERT INTO t(id) VALUES(1)"` | 执行 INSERT | P-001 | 执行成功，输出影响行数 |
| SQL-024 | `sql-cli mysql_ok "CREATE TABLE tmp_cli_test(id INT)"` | 执行 DDL | P-001 | 执行成功或按数据库返回错误 |
| SQL-025 | `sql-cli yearning_ok "SELECT 1"` | Yearning 查询 | P-003 | 通过 Yearning 执行并输出结果 |
| SQL-026 | `sql-cli yearning_ok "UPDATE t SET c=1"` | Yearning 写操作 | P-003 | 按 Yearning 支持范围执行或返回明确错误 |

## Alias 动作命令

| 用例编号 | 命令 / 参数 | 场景 | 前置条件 | 预期结果 |
|---|---|---|---|---|
| ACTION-001 | `sql-cli mysql_ok test` | 测试 JDBC 连接成功 | P-001 | 输出 `Connection successful` |
| ACTION-002 | `sql-cli mysql_ok test --json` | 测试连接 JSON 输出 | P-001 | 输出包含 `status` 和 `alias` 的 JSON |
| ACTION-003 | `sql-cli mysql_ok test -j` | 测试连接 JSON 短参数 | P-001 | 输出 JSON |
| ACTION-004 | `sql-cli missing_secret test` | 密钥缺失时测试连接 | P-005 | 输出连接失败和 root cause/hints |
| ACTION-005 | `sql-cli yearning_ok test` | 测试 Yearning alias | P-003 | 输出 Yearning 连接测试结果 |
| ACTION-006 | `sql-cli mysql_ok ddl users` | 获取表 DDL | P-001，`users` 表存在 | 输出 CREATE TABLE 或等价 DDL |
| ACTION-007 | `sql-cli mysql_ok ddl users --schema test` | 指定 schema 获取 DDL | P-001 | 输出 DDL |
| ACTION-008 | `sql-cli oracle_ok ddl users` | Oracle 小写表名自动转大写 | P-002 | 查询 `USERS` DDL |
| ACTION-009 | `sql-cli mysql_ok ddl` | ddl 缺少表名 | P-001 | 输出 ddl 用法 |
| ACTION-010 | `sql-cli mysql_ok ddl not_exists` | 获取不存在表 DDL | P-001 | 输出失败信息 |
| ACTION-011 | `sql-cli mysql_ok tables` | 列出表，默认 table 格式 | P-001 | 输出表格和表数量 |
| ACTION-012 | `sql-cli mysql_ok tables --schema test` | 按 schema 列表 | P-001 | 输出指定 schema 表 |
| ACTION-013 | `sql-cli mysql_ok tables -s test` | schema 短参数 | P-001 | 输出指定 schema 表 |
| ACTION-014 | `sql-cli mysql_ok tables --pattern "%user%"` | 按表名模式过滤 | P-001 | 只输出匹配表 |
| ACTION-015 | `sql-cli mysql_ok tables -p "%user%"` | pattern 短参数 | P-001 | 只输出匹配表 |
| ACTION-016 | `sql-cli mysql_ok tables -f table` | table 格式输出 | P-001 | 输出表格 |
| ACTION-017 | `sql-cli mysql_ok tables -f csv` | CSV 格式输出 | P-001 | 输出 CSV |
| ACTION-018 | `sql-cli mysql_ok tables -f json` | JSON 格式输出 | P-001 | 输出 JSON 数组 |
| ACTION-019 | `sql-cli yearning_ok tables` | Yearning alias 列表 | P-003 | 输出不支持 `tables` 的提示 |
| ACTION-020 | `sql-cli oracle_ok tables` | Oracle 未指定 schema | P-002 | 默认使用用户名作为 schema |
| ACTION-021 | `sql-cli mysql_ok secret` | secret 缺少子动作 | P-001 | 输出 `secret set|delete|status` 用法 |
| ACTION-022 | `sql-cli mysql_ok secret status` | 查看 keyring/encrypted/env 密钥状态 | P-001 | 输出密钥类型和 available/missing |
| ACTION-023 | `sql-cli mysql_ok secret status --json` | 密钥状态 JSON 输出 | P-001 | 输出 JSON |
| ACTION-024 | `sql-cli mysql_ok secret status -j` | 密钥状态 JSON 短参数 | P-001 | 输出 JSON |
| ACTION-025 | `sql-cli missing_secret secret status` | 查看缺失密钥状态 | P-005 | 输出 missing |
| ACTION-026 | `sql-cli mysql_ok secret set` | 交互式写入密钥 | P-001，终端可交互 | 提示输入密码，写入后自动测试连接 |
| ACTION-027 | `sql-cli mysql_ok secret set --stdin` | stdin 写入密钥 | P-001 | 从标准输入读取密码，写入后自动测试连接 |
| ACTION-028 | `sql-cli mysql_ok secret delete` | 删除密钥 | P-001 | 删除对应 keyring/encrypted 密钥 |
| ACTION-029 | `sql-cli alias_env_only secret set` | env 类型 secretRef 执行 set | alias 的 `secretRef=env:XXX` | 输出不支持该 secretRef |
| ACTION-030 | `sql-cli mysql_ok secret unknown` | 未知 secret 子动作 | P-001 | 输出 `Unknown secret action` |
| ACTION-031 | `sql-cli mysql_ok unknown-action` | 未知 alias action 会被当作 SQL | P-001 | 尝试执行 `unknown-action` SQL 并返回数据库错误 |

## Schema / 图谱工作区命令

| 用例编号 | 命令 / 参数 | 场景 | 前置条件 | 预期结果 |
|---|---|---|---|---|
| GRAPH-001 | `sql-cli graph_empty schema list` | 工作区不存在时列表 | P-007 | 输出请先 import 的提示，退出码非 0 |
| GRAPH-002 | `sql-cli graph_ok schema --help` | schema 帮助 | P-006 | 输出 schema actions 和详细用法 |
| GRAPH-003 | `sql-cli graph_ok schema list` | 列出工作区表 | P-006 | 按 schema 输出表列表 |
| GRAPH-004 | `sql-cli graph_ok schema list --json` | 工作区表 JSON 输出 | P-006 | 输出 JSON 数组 |
| GRAPH-005 | `sql-cli graph_ok schema list -j` | JSON 短参数 | P-006 | 输出 JSON 数组 |
| GRAPH-006 | `sql-cli graph_ok schema describe trade.users` | 查看表详情 | P-006，表存在 | 输出表、字段、关系 |
| GRAPH-007 | `sql-cli graph_ok schema describe trade.users --json` | 表详情 JSON 输出 | P-006，表存在 | 输出包含 table/columns/relations 的 JSON |
| GRAPH-008 | `sql-cli graph_ok schema describe` | describe 缺少表名 | P-006 | 输出 describe 用法 |
| GRAPH-009 | `sql-cli graph_ok schema describe trade.not_exists` | describe 不存在表 | P-006 | 输出 `Table not found` |
| GRAPH-010 | `sql-cli graph_ok schema query trade.users` | 查询一跳关联 | P-006，表存在 | 输出相关关系 |
| GRAPH-011 | `sql-cli graph_ok schema query trade.users --depth 2` | 查询两跳关联 | P-006，表存在 | 输出 depth 2 关联 |
| GRAPH-012 | `sql-cli graph_ok schema query trade.users -d 2 --json` | depth 短参数和 JSON 输出 | P-006，表存在 | 输出 JSON |
| GRAPH-013 | `sql-cli graph_ok schema query` | query 缺少表名 | P-006 | 输出 query 用法 |
| GRAPH-014 | `sql-cli graph_ok schema query trade.not_exists` | query 不存在表 | P-006 | 输出 `Table not found` |
| GRAPH-015 | `sql-cli graph_ok schema path trade.users trade.orders` | 查找表路径 | P-006，表存在且有关联 | 输出路径或无路径提示 |
| GRAPH-016 | `sql-cli graph_ok schema path trade.users trade.orders --json` | 路径 JSON 输出 | P-006 | 输出 JSON |
| GRAPH-017 | `sql-cli graph_ok schema path trade.users` | path 缺少第二个表 | P-006 | 输出 path 用法 |
| GRAPH-018 | `sql-cli graph_ok schema search 手机号` | 搜索关键词 | P-006 | 输出匹配结果 |
| GRAPH-019 | `sql-cli graph_ok schema search 手机号 --json` | 搜索 JSON 输出 | P-006 | 输出 JSON |
| GRAPH-020 | `sql-cli graph_ok schema search` | search 缺少关键词 | P-006 | 输出 search 用法 |
| GRAPH-021 | `sql-cli graph_ok schema import --from-db` | 从数据库全量导入 | P-001 或 P-002 | 创建或刷新图谱工作区，输出导入任务 |
| GRAPH-022 | `sql-cli graph_ok schema import --from-db --schema trade` | 指定 schema 导入 | P-001 或 P-002 | 只导入指定 schema |
| GRAPH-023 | `sql-cli graph_ok schema import --from-db -s trade` | schema 短参数 | P-001 或 P-002 | 只导入指定 schema |
| GRAPH-024 | `sql-cli graph_ok schema import --from-db --table trade.users` | 指定表导入 | P-001 或 P-002 | 只导入指定表 |
| GRAPH-025 | `sql-cli graph_ok schema import --from-db --batch-size 10` | 指定批次大小 | P-001 或 P-002 | 按批次执行导入 |
| GRAPH-026 | `sql-cli graph_ok schema import --from-db --merge` | 数据库导入合并已有人工数据 | P-006 | 导入成功，保留可合并人工数据 |
| GRAPH-027 | `sql-cli graph_ok schema import --from-db --force-overwrite` | 强制覆盖导入 | P-006 | 导入成功，允许覆盖 |
| GRAPH-028 | `sql-cli graph_ok schema import --input tmp/workspace-export.json` | 从快照导入 | P-010 文件存在 | 导入成功 |
| GRAPH-029 | `sql-cli graph_ok schema import --input tmp/workspace-export.json --merge` | 快照合并导入 | P-006, P-010 | 合并并保存工作区 |
| GRAPH-030 | `sql-cli graph_ok schema import --input tmp/not-exists.json` | 快照文件不存在 | P-006 | 输出 `File not found` |
| GRAPH-031 | `sql-cli graph_ok schema import status` | 查看导入状态 | P-006 | 输出当前任务或无进行中任务 |
| GRAPH-032 | `sql-cli graph_ok schema import status --json` | 导入状态 JSON 输出 | P-006 | 有任务时输出 JSON，无任务时输出文本提示 |
| GRAPH-033 | `sql-cli graph_ok schema import resume` | 恢复导入 | 存在可恢复导入任务 | 继续执行任务并输出状态 |
| GRAPH-034 | `sql-cli graph_ok schema import --resume` | 使用参数形式恢复导入 | 存在可恢复导入任务 | 继续执行任务并输出状态 |
| GRAPH-035 | `sql-cli graph_ok schema import reset` | 重置导入任务 | P-006 | 输出状态已重置 |
| GRAPH-036 | `sql-cli graph_ok schema import` | import 缺少来源 | P-006 | 输出 import 用法 |
| GRAPH-037 | `sql-cli graph_ok schema export` | 导出默认路径 | P-006 | 导出到工作区默认 `workspace-export.json` |
| GRAPH-038 | `sql-cli graph_ok schema export --output tmp/workspace-export.json` | 导出指定路径 | P-006 | 文件不存在时导出成功 |
| GRAPH-039 | `sql-cli graph_ok schema export -o tmp/workspace-export.json --force` | 覆盖导出 | P-006，目标文件已存在 | 覆盖成功 |
| GRAPH-040 | `sql-cli graph_ok schema export --output tmp/workspace-export.json` | 目标文件已存在且未 force | P-006，目标文件已存在 | 报错提示使用 `--force` |
| GRAPH-041 | `sql-cli graph_ok schema edit --table trade.users --description 用户表` | 编辑表描述 | P-006，表存在 | 保存成功 |
| GRAPH-042 | `sql-cli graph_ok schema edit --table trade.users --description 用户表 --add-tag PII` | 编辑表描述和标签 | P-006，表存在 | 保存成功，标签增加 |
| GRAPH-043 | `sql-cli graph_ok schema edit --table trade.not_exists --description x` | 编辑不存在表 | P-006 | 输出 `Table not found` |
| GRAPH-044 | `sql-cli graph_ok schema edit --column trade.users.phone --description 手机号` | 编辑字段描述 | P-006，字段存在 | 保存成功 |
| GRAPH-045 | `sql-cli graph_ok schema edit --column trade.users.phone --description 手机号 --example 13800138000 --add-tag PII --add-constraint "11位数字"` | 编辑字段完整元数据 | P-006，字段存在 | 保存成功，样例/标签/约束更新 |
| GRAPH-046 | `sql-cli graph_ok schema edit --column trade.users.not_exists --description x` | 编辑不存在字段 | P-006 | 输出 `Column not found` |
| GRAPH-047 | `sql-cli graph_ok schema edit --table trade.users` | edit 无实际修改项 | P-006，表存在 | 输出 `没有修改` |
| GRAPH-048 | `sql-cli graph_ok schema edit` | edit 缺少 table/column | P-006 | 输出 edit 用法 |
| GRAPH-049 | `sql-cli graph_ok schema stats` | 查看统计 | P-006 | 输出 schemas/tables/columns/relations 等统计 |
| GRAPH-050 | `sql-cli graph_ok schema stats --json` | 统计 JSON 输出 | P-006 | 输出 JSON |
| GRAPH-051 | `sql-cli graph_ok schema validate` | 校验工作区 | P-006 | 输出校验通过或问题列表，并保存问题 |
| GRAPH-052 | `sql-cli graph_ok schema validate --json` | 校验 JSON 输出 | P-006 | 输出 validation issues JSON |
| GRAPH-053 | `sql-cli graph_ok schema add-term buyer` | 添加术语 | P-006 | 创建或更新 term |
| GRAPH-054 | `sql-cli graph_ok schema add-term buyer --display-name 买家 --description 下单用户 --aliases "购买人,客户" --map trade.orders.buyer_id` | 添加术语完整参数 | P-006，映射目标存在 | 保存术语和映射关系 |
| GRAPH-055 | `sql-cli graph_ok schema add-term` | add-term 缺少名称 | P-006 | 输出 add-term 用法 |
| GRAPH-056 | `sql-cli graph_ok schema add-term buyer --map trade.not_exists.id` | 术语映射目标不存在 | P-006 | 输出无法解析映射目标 |
| GRAPH-057 | `sql-cli graph_ok schema add-relation --type join_observed --from trade.orders.user_id --to trade.users.id` | 推断类关系未给 confidence | P-006，端点存在且类型允许人工创建 | 拒绝并提示必须显式 `--confidence`，退出码 1，工作区不变 |
| GRAPH-058 | `sql-cli graph_ok schema add-relation --type join_observed --from trade.orders.user_id --to trade.users.id --join "orders.user_id = users.id" --confidence 0.9 --verified` | 添加关系完整参数 | P-006，端点存在 | 保存关系并设置 join/confidence/verified，confidence 原样落库 |
| GRAPH-058a | `sql-cli graph_ok schema add-relation --type join_observed --from trade.orders.user_id --to trade.users.id --confidence 0.6 --verified` | verified 但 confidence < 0.9 | P-006，端点存在 | 拒绝并提示 verified 关系 confidence 需 ≥ 0.9，退出码 1 |
| GRAPH-058b | `sql-cli graph_ok schema add-relation --type join_observed --from trade.orders --to trade.users --confidence 0.9` | 端点类型不匹配（表 vs 列） | P-006 | 拒绝并输出 invalid endpoint，退出码 1 |
| GRAPH-058c | `sql-cli graph_ok schema add-relation --type foreign_key_declared --from trade.orders.user_id --to trade.users.id --confidence 1.0` | 人工创建 declared FK | P-006 | 拒绝，提示该类型只能由导入产生 |
| GRAPH-059 | `sql-cli graph_ok schema add-relation --from trade.orders.user_id --to trade.users.id` | 缺少 type | P-006 | 输出 add-relation 用法 |
| GRAPH-060 | `sql-cli graph_ok schema add-relation --type join_observed --from trade.orders.user_id` | 缺少 to | P-006 | 输出 add-relation 用法 |
| GRAPH-061 | `sql-cli graph_ok schema add-relation --type not_a_type --from trade.orders.user_id --to trade.users.id` | 未知关系类型 | P-006 | 输出 `Unknown relation type` |
| GRAPH-062 | `sql-cli graph_ok schema add-relation --type join_observed --from trade.orders.not_exists --to trade.users.id` | 关系端点不存在 | P-006 | 输出无法解析端点 |
| GRAPH-063 | `sql-cli graph_ok schema index status` | 查看索引状态 | P-006 | 输出未构建或索引 manifest |
| GRAPH-064 | `sql-cli graph_ok schema index status --json` | 索引状态 JSON 输出 | P-006，索引存在 | 输出 manifest JSON |
| GRAPH-065 | `sql-cli graph_ok schema index rebuild` | 重建索引 | P-006 | 输出 shard/table/column/term 数量 |
| GRAPH-066 | `sql-cli graph_ok schema index unknown` | 未知 index 子动作 | P-006 | 输出 index 用法 |
| GRAPH-067 | `sql-cli graph_ok schema diagram` | 生成默认关系图 | P-006 | 生成 developer-graph 目录并输出统计 |
| GRAPH-068 | `sql-cli graph_ok schema diagram --output tmp/developer-graph` | 指定输出目录 | P-006 | 输出到指定目录 |
| GRAPH-069 | `sql-cli graph_ok schema diagram -o tmp/developer-graph --json` | 指定输出并 JSON 返回 | P-006 | 输出 manifest JSON |
| GRAPH-070 | `sql-cli ui --alias graph_ok --port 9090 --no-open` | 指定别名和端口启动 UI，不自动打开 | P-006 | 服务启动并输出访问地址 |
| GRAPH-071 | `sql-cli ui --port 0 --no-open` | 随机可用端口启动首页 UI | P-006 | 服务启动并输出实际端口和别名首页 |
| GRAPH-072 | 已移除的 alias 级 UI 入口 | 旧入口不再保留 | P-006 | 返回未知 schema action |
| GRAPH-073 | `sql-cli graph_ok schema unknown` | 未知 schema action | P-006 | 输出未知 action 和帮助 |
| GRAPH-074 | `sql-cli graph_empty schema stats` | 工作区不存在时执行 stats | P-007 | 输出请先 import 的提示 |
| GRAPH-075 | `sql-cli graph_empty schema validate` | 工作区不存在时执行 validate | P-007 | 输出请先 import 的提示 |
| GRAPH-076 | `sql-cli graph_ok schema edit --table trade.users --business-name 用户表` | 编辑表业务名 | P-006，表存在 | 保存成功，`describe` 读到同一个 businessName |
| GRAPH-077 | `sql-cli graph_ok schema edit --column trade.users.phone --business-name 手机号 --semantic-type phone` | 编辑字段业务名和语义类型 | P-006，字段存在 | 保存成功，`search --json` 读到同一个 businessName/semanticType |
| GRAPH-078 | `sql-cli graph_ok schema edit --column trade.users.phone --semantic-type mobile` | 语义类型非法值 | P-006，字段存在 | 保存失败，报错信息列出全部合法 semanticType 取值，字段不变 |
| GRAPH-079 | `sql-cli graph_ok schema edit --column trade.users.phone --semantic-type ""` | 清空语义类型 | P-006，字段已设置 semanticType | 保存成功，semanticType 变为空 |
| GRAPH-080 | `sql-cli graph_ok schema add-metric gmv_paid --expression "SUM(orders.amount)" --filters "status IN (2,3)" --grain-column trade.orders.created_at --grains day,month --dimensions trade.orders.channel` | 创建指标（含粒度/维度） | P-006，列存在 | 写入成功，`status=candidate`，`metric --json` 读到同一份字段 |
| GRAPH-081 | `sql-cli graph_ok schema add-metric gmv_paid --expression "SUM(orders.amount)"` | 同名重写 | GRAPH-080 之后 | 幂等更新同一条记录，`metrics --json` 数量不变 |
| GRAPH-082 | `sql-cli graph_ok schema add-metric broken --expression "COUNT(*)" --dimensions trade.orders.not_a_column` | 维度列不存在 | P-006 | 拒绝，报错点名缺的列，退出码 1，工作区不写入 |
| GRAPH-083 | `sql-cli graph_ok schema add-metric broken --expression "COUNT(*)" --join-path relation:graph_ok:foreign_key:not-exist\|inner` | joinPath 引用不存在的关系 | P-006 | 拒绝，报错点名缺的 relationId |
| GRAPH-084 | `sql-cli graph_ok schema metrics --json` | 列出全部指标 | P-006，已有指标 | 输出指标数组 |
| GRAPH-085 | `sql-cli graph_ok schema metric gmv_paid --json` | 查看指标详情 | GRAPH-080 之后 | 输出 expression/filters/grain/dimensions/joinPath 完整字段 |
| GRAPH-086 | `sql-cli graph_ok schema metric not_exists` | 查看不存在的指标 | P-006 | 输出 `Metric not found`，退出码 1 |
| GRAPH-087 | `sql-cli graph_ok schema expand-metric gmv_paid --grain month --dimensions trade.orders.channel --time-from 2026-01-01 --time-to 2026-02-01 --json` | 单表指标展开成 SQL | GRAPH-080 之后 | 输出 `{metric, sql}`，`sql` 含分桶列、维度列、`WHERE`/`GROUP BY` |
| GRAPH-088 | `sql-cli graph_ok schema expand-metric gmv_paid --grain quarter` | 请求未声明的粒度 | GRAPH-080 之后（只声明了 day/month） | 拒绝，报错列出该指标声明的粒度范围 |
| GRAPH-089 | 跨表指标（`--join-path` 引用一条复合外键分组里的边）+ `expand-metric` | 复合外键 JOIN 条件完整性 | P-006，两列复合外键表 | 展开出的 `ON` 条件包含复合键全部列对，不因引用组内某一条边而漏列 |
| GRAPH-090 | `sql-cli graph_ok schema edit --column trade.orders.customer_name --redundant-of trade.customer.name` | 标注冗余副本 | P-006，两列均存在 | 保存成功，`describe --json` 读到同一个 `redundantOf`（列 id） |
| GRAPH-091 | `sql-cli graph_ok schema edit --column trade.orders.customer_name --redundant-of trade.customer.not_exists` | 权威源列不存在 | P-006 | 拒绝，报错点名缺的列，退出码 1，字段不写入 |
| GRAPH-092 | `sql-cli graph_ok schema edit --column trade.orders.customer_name --redundant-of trade.orders.customer_name` | 权威源指向自身 | P-006 | 拒绝，退出码 1，字段不写入 |
| GRAPH-093 | `sql-cli graph_ok schema edit --column trade.orders.customer_name --redundant-of ""` | 清空冗余标注 | GRAPH-090 之后 | 保存成功，`redundantOf` 变为空 |
| GRAPH-094 | `sql-cli graph_ok schema search customer_name` | 检索命中冗余列 | GRAPH-090 之后 | 文本输出带 `[冗余，权威源:trade.customer.name]`；`--json` 每条命中都带 `redundantOf` 字段（非冗余列为 `null`） |

## 历史 SQL 重执行（批次 4b ✅ 已实现，2026-08-20）

> 实现：`SqlRerunController` + `POST /api/sql/execute`，只读语句白名单。
> **RERUN-006 / RERUN-008 仍待批次 4**（行数上限配置化、Yearning 路由）。
> 前置补充：P-011 `graph_ok` 下已有若干条 SELECT 历史记录（`raw_sql` 非空）；
> P-012 由旧 JSONL 迁移来的历史记录（`raw_sql` 为空）；P-013 一条 UPDATE 历史记录。

| 用例编号 | 命令 / 参数 | 场景 | 前置条件 | 预期结果 |
|---|---|---|---|---|
| RERUN-001 | `POST /api/sql/execute {alias, executionId}` | 重执行 SELECT 历史 | P-011 | 返回结构化结果（列/类型/行/耗时/截断标志），表格渲染 |
| RERUN-002 | `POST /api/sql/execute` 目标为 UPDATE 历史 | 写语句不在只读白名单 | P-013 | 拒绝执行并返回原因，不产生任何写入 |
| RERUN-003 | `POST /api/sql/execute` 目标为 JSONL 迁移记录 | `raw_sql` 为空无法重放 | P-012 | 返回「历史记录未保存原文」，UI 按钮为禁用态 |
| RERUN-004 | `POST /api/sql/execute` 使用 `readonly_ok` | 只读别名重执行 SELECT | P-004 | 正常返回结果 |
| RERUN-005 | 重执行成功后查询运行记录 | 重执行本身要留痕 | P-011 | **当前实现不留痕**：`SqlRerunController` 全文没有 `RunStateStore`，重放不写 `sql_execution`。期望值（新增记录 + 来源标记 rerun + 关联原记录 id）待 P0 的 SqlTaskModule 收编后才成立 |
| RERUN-006 | 重执行 `SELECT` 返回超大结果集 | 行数上限保护 | P-011 | 结果被截断且返回截断标志与行数提示 |
| RERUN-007 | 重执行结果含 NULL / 长文本 / 二进制列 | 表格组件渲染 | P-011 | NULL 与长文本有明确呈现，二进制不撑破布局，可横向滚动 |
| RERUN-008 | 重执行 `yearning_ok` 的 SELECT 历史 | Yearning 路由 | P-003 | 走 YearningQueryExecutor 返回结果；写语句一律拒绝 |

## 候选变更发布 / 拒绝（批次 6 ✅ 已实现，2026-08-21）

> 实现：`GraphStatus.candidate`；UI 候选区在 `RelationList` 的「待审核」分组。
> **CAND-002 的术语侧只有 CLI，UI 术语子视图未做。**
> 前置补充：P-014 `graph_ok` 下存在由 CLI 写入的候选关系与候选术语各一条。

| 用例编号 | 命令 / 参数 | 场景 | 前置条件 | 预期结果 |
|---|---|---|---|---|
| CAND-001 | `sql-cli graph_ok schema add-relation --type join_observed --from trade.orders.user_id --to trade.users.id --confidence 0.85` | Agent 写入默认进候选 | P-006 | 关系落库且 `status=candidate`；revision 递增（写入本身是一次 mutation） |
| CAND-002 | `sql-cli graph_ok schema add-term buyer --display-name 买家` | 术语写入默认进候选 | P-006 | term `status=candidate` |
| CAND-003 | `sql-cli graph_ok schema edit --table trade.orders --description 订单主表` | edit 不走候选 | P-006 | 表描述直接生效，状态不变为 candidate |
| CAND-004 | UI 图谱页表详情 | 候选与正式分区展示 | P-014 | 候选关系排在「待审核」分组（琥珀边框），正式关系在下方 |
| CAND-005 | UI 候选区「发布」单条候选关系 | 人工评审通过 | P-014 | confidence ≥ 0.9 → `verified`，否则 → `partial`；revision 递增 |
| CAND-006 | UI 候选区「拒绝」单条候选关系 | 人工评审否决 | P-014 | 该关系被删除；revision 递增 |
| CAND-007 | 对同一端点重复写入候选 | 候选幂等 | P-014 | 覆盖同一条候选而非堆积重复项 |
| CAND-008 | 发布候选后再次执行 `schema validate` | 发布结果参与校验 | CAND-005 已执行 | 校验覆盖新发布对象 |
| CAND-009 | `sql-cli graph_ok schema stats` | 统计口径 | P-014 | 当前实现不区分候选与正式，全部计入——**待定：是否要拆开** |
| CAND-010 | `sql-cli graph_ok schema search buyer` | 候选是否参与检索 | P-014 | **当前实现：候选照常返回，与正式对象无区别**。CLI 侧看不出一条关系是不是还没发布 |
| CAND-011 | `sql-cli graph_ok schema describe trade.orders` | describe 的候选标识 | P-014 | **当前实现：候选关系照常列出且不显示 status**，与正式关系无法区分 |

## 审批闸门（2026-08-22 ✅ 已实现）

> 实现：`ApprovalGate` + `approval_request` 表 + 评审页。开关关闭时行为与以前完全一致。
> **这些用例是阻塞式的**：CLI 会停住等，必须在另一端（评审页或直接改表）裁决才会返回。
> 前置补充：P-015 `approve_ok` —— 开了 `approveUpdate: true` 的别名；
> P-016 `approve_query` —— 开了 `approveQuery: true` 的别名。

| 用例编号 | 命令 / 参数 | 场景 | 前置条件 | 预期结果 |
|---|---|---|---|---|
| APPR-001 | `sql-cli mysql_ok "UPDATE t SET c=1 WHERE id=1"` | 开关全关 | P-001 | 不产生 `approval_request` 记录，直接执行（回归保护：默认行为不变） |
| APPR-002 | `sql-cli approve_ok "UPDATE t SET c=1 WHERE id=1"` | 写操作命中开关 | P-015 | CLI 阻塞；`GET /api/approvals` 出现一条 `kind=update` 的 pending |
| APPR-003 | 对 APPR-002 的记录 `POST /api/approvals/{id}/decide {approved}` | 批准放行 | APPR-002 挂起中 | CLI 在 ≤2 秒内继续执行并正常返回；记录转 approved |
| APPR-004 | 对 APPR-002 的记录 decide `{rejected, reason}` | 拒绝 | APPR-002 挂起中 | CLI 报审批被拒并退出非 0；**数据库无任何写入** |
| APPR-005 | decide `{rejected}` 不带 reason | 拒绝必须填理由 | APPR-002 挂起中 | 报错，记录仍为 pending（批准可不填） |
| APPR-006 | 挂起后不处理，等待超过 10 分钟 | 超时 | P-015 | 记录转 expired，CLI 报超时退出；**不是自动放行** |
| APPR-007 | `sql-cli approve_query "SELECT 1"` | 查询也能上闸 | P-016 | 出现 `kind=query` 的 pending，批准后返回结果 |
| APPR-008 | `sql-cli approve_ok schema add-relation …` | 图谱写入命中 GRAPH 开关 | P-015 + `approveGraph:true` | 出现 `kind=graph` 的 pending；拒绝后 revision 不变 |
| APPR-009 | 在 UI 里同时发起 9 个需审批的图谱写入 | 并发等待上限（`MAX_WAITERS` 是**进程内**计数，所以要在 UI 侧压，CLI 各是独立进程） | P-015 | 前 8 个挂起，第 9 个立即失败并说明已达上限；**评审页仍能正常拉取列表**（线程池不能被挂起请求占满） |
| APPR-012 | 对已裁决的记录再次 decide | 重复裁决 | APPR-003 已执行 | 报错说明当前状态，不覆盖原结果 |
| APPR-010 | 顶栏选中别名 A，评审页查看 | 评审跨数据源 | P-015 + P-016 各有 pending | 两个别名的待审批都可见 |
| APPR-011 | `sql-cli approve_ok "SELECT 1"` | 只开了 update 开关 | P-015 | 查询不进审批，直接返回 |

## 输出格式与参数冲突

| 用例编号 | 命令 / 参数 | 场景 | 前置条件 | 预期结果 |
|---|---|---|---|---|
| OPT-001 | `sql-cli mysql_ok "SELECT 1" -f xml` | SQL 输出格式未知 | P-001 | 执行路径应返回明确错误或默认处理，行为需固化 |
| OPT-002 | `sql-cli mysql_ok tables -f xml` | tables 输出格式未知 | P-001 | 当前回退为 table 输出，建议确认是否保留 |
| OPT-003 | `sql-cli graph_ok schema query trade.users -d 2` | `-d` 同时触发 debug 和 depth | P-006 | depth 生效；同时 debug 也会被全局扫描开启，建议记录为待优化 |
| OPT-004 | `sql-cli mysql_ok "SELECT 1" --format` | `--format` 缺值 | P-001 | 当前可能继续解析并最终执行或报 SQL 缺失，建议固化为参数错误 |
| OPT-005 | `sql-cli mysql_ok "SELECT 1" --decrypt-cols` | `--decrypt-cols` 缺值 | P-001 | 当前可能忽略，建议固化为参数错误 |
| OPT-006 | `sql-cli graph_ok schema query trade.users --depth abc` | depth 非数字 | P-006 | 抛出数字解析错误 |
| OPT-007 | `sql-cli graph_ok schema add-relation --type join_observed --from trade.orders.user_id --to trade.users.id --confidence abc` | confidence 非数字 | P-006 | 抛出数字解析错误 |
| OPT-008 | `sql-cli ui --port abc --no-open` | port 非数字 | P-006 | 返回参数错误 |
| OPT-009 | `sql-cli graph_ok schema import --from-db --batch-size abc` | batch-size 非数字 | P-006 | 抛出数字解析错误 |
| OPT-010 | `sql-cli graph_ok schema export -f --output tmp/workspace-export.json` | schema export 的 `-f` 表示 force | P-006 | 覆盖导出成功 |
| OPT-011 | `sql-cli mysql_ok "SELECT 1" -f json` | SQL 执行的 `-f` 表示 format | P-001 | JSON 输出 |

## 规划但当前未实现命令的负向用例

| 用例编号 | 命令 / 参数 | 场景 | 前置条件 | 预期结果 |
|---|---|---|---|---|
| TODO-001 | `sql-cli conn test mysql_ok` | 规划中的连接测试入口 | P-001 | 当前未实现，应提示未知或被当作 alias |
| TODO-002 | `sql-cli conn doctor mysql_ok` | 规划中的连接诊断入口 | P-001 | 当前未实现 |
| TODO-003 | `sql-cli query mysql_ok --sql "SELECT 1"` | 规划中的标准查询入口 | P-001 | 当前未实现 |
| TODO-004 | `sql-cli exec mysql_ok --sql "UPDATE t SET c=1"` | 规划中的标准执行入口 | P-001 | 当前未实现 |
| TODO-005 | `sql-cli alias validate mysql_ok` | 规划中的 alias 校验入口 | P-001 | 当前未实现 |
| TODO-006 | `sql-cli secret status mysql_ok` | 规划中的顶层 secret 入口 | P-001 | 当前未实现 |
| TODO-007 | `sql-cli schema index update --changed table:graph_ok:trade.users` | 规划中的增量索引 | P-006 | 当前未实现 |
| TODO-008 | `sql-cli graph_ok schema add-lineage --from a --to b` | 规划中的血缘关系入口 | P-006 | 当前未实现 |

## 多 Schema 导入（2026-08-27 补充）

> CLI `--schema` 和 Web UI 图谱页左侧表目录是同一条导入路径（`WorkspaceImportService`），
> 按 schema 逐个导而不是一次全导：一个连接下十几个 schema、几千张表，全导会拖慢图谱渲染和
> 索引重建（见 `web/src/features/explorer/SchemaExplorer.tsx` 头部注释）。
> UI 侧对应 `GET /api/schemas/catalog?alias=` 读目录、`POST /api/aliases/{alias}/import?schema=` 导入。

| 用例编号 | 命令 / 参数 | 场景 | 前置条件 | 预期结果 |
|---|---|---|---|---|
| MSCHEMA-001 | `sql-cli multi_schema_ok schema import --from-db --schema schema_a` | 先导入第一个 schema | P-017 | 只写入 `schema_a` 的表，工作区不出现 `schema_b` |
| MSCHEMA-002 | `sql-cli multi_schema_ok schema import --from-db --schema schema_b` | 紧接着导入第二个 schema | MSCHEMA-001 已执行 | `schema_a` 的表不受影响，`schema_b` 的表新增；两者同时存在于同一个工作区 |
| MSCHEMA-003 | `sql-cli multi_schema_ok schema list` | 校验两个 schema 都在 | MSCHEMA-002 已执行 | 按 schema 分组输出，`schema_a` 和 `schema_b` 都出现 |
| MSCHEMA-004 | `sql-cli multi_schema_ok schema import --from-db --schema schema_a` | 对已导入的 schema 重新导入（刷新） | MSCHEMA-002 已执行 | 只刷新 `schema_a` 的物理结构和注释，`schema_b` 的表不受影响，`schema_a` 已有的业务名/描述等人工字段不被覆盖 |
| MSCHEMA-005 | `GET /api/schemas/catalog?alias=multi_schema_ok` | 打开图谱页查看 schema 目录 | P-017 | 返回该数据源**全部** schema（含未导入的），每项带 `imported` 标志，不只列出已导入的 |
| MSCHEMA-006 | `POST /api/aliases/multi_schema_ok/import?schema=schema_a` | 点击表目录里 `schema_a` 的导入按钮 | P-017 | 效果与 MSCHEMA-001 等价：只导入 `schema_a`，其余 schema 不受影响 |
| MSCHEMA-007 | 依次对 `schema_a`、`schema_b` 各点一次「导入」按钮 | 通过 UI 完成多 schema 导入 | P-017 | 两次点击后 `GET /api/schemas/catalog` 里两个 schema 的 `imported` 均为 true；`schema describe`/`schema search` 能跨两个 schema 命中 |

## 驱动上传（2026-08-27 补充）

> Web UI 设置页「上传 jar」对应 `POST /api/drivers/upload?dbType=&filename=`（`DriverAdminController#upload`），
> 请求体是文件原始字节（内置 HttpServer 不解析 multipart）。落盘到 `drivers/{dbType}/`，
> 校验顺序：`dbType` 必填 → 文件名只接受 `.jar` 且不能含路径分隔符 → 大小 ≤ 64MB → 文件头必须是合法 zip（jar 本质是 zip）。

| 用例编号 | 命令 / 参数 | 场景 | 前置条件 | 预期结果 |
|---|---|---|---|---|
| DRVUP-001 | `POST /api/drivers/upload?dbType=mysql&filename=mysql-connector-j-8.3.0.jar`（body 为有效 jar 二进制） | 正常上传 | 无 | 返回 201 和 `{path, size, fileName}`，文件写入 `drivers/mysql/mysql-connector-j-8.3.0.jar` |
| DRVUP-002 | 同上，但 body 是文本内容改后缀成 `.jar` | 上传非法内容（缺少 zip 文件头） | 无 | 报错「这不是有效的 jar 文件（缺少 zip 文件头），可能是下载失败的残缺文件」，临时文件被清理，不留残留 |
| DRVUP-003 | `filename=../../evil.jar` | 文件名含路径分隔符 | 无 | 报错「文件名不合法，只接受 .jar 且不能包含路径分隔符」 |
| DRVUP-004 | 不带 `dbType` 查询参数 | 缺少 dbType | 无 | 报错 `dbType is required` |
| DRVUP-005 | 上传 body 超过 64MB | 超出大小上限 | 无 | 报错「jar 超过 64MB 上限」，不写入目标文件 |
| DRVUP-006 | `filename=driver.txt` | 非 `.jar` 后缀 | 无 | 报错文件名不合法 |
| DRVUP-007 | 上传成功后在「新建驱动」表单里查看 jar 列表 | 上传结果直接可用 | DRVUP-001 已执行 | 返回的相对路径自动加入表单 jars 列表，无需手工填路径 |
| DRVUP-008 | 对同一 `dbType`+文件名再次上传（内容不同） | 覆盖上传 | DRVUP-001 已执行 | 新内容替换旧文件（`REPLACE_EXISTING`），已引用该路径的 driver 配置无需重新保存即生效 |

## 规则页新形态（2026-08-27 补充）

> 规则页固定两个分组（`structure.yaml` 设计期规则、`sql-migration.yaml` SQL/迁移期规则），
> 不支持自建规则集——两类规则在评估器里本来就不能混，`RulesPage.tsx` 头部注释说明了原因。
> 接口：`GET /api/policy/rules`、`POST /api/policy/rules`、`PUT /api/policy/rules/{fileName}`、
> `DELETE /api/policy/rules/{fileName}`、`PUT /api/policy/rules/{fileName}/binding`。

| 用例编号 | 命令 / 参数 | 场景 | 前置条件 | 预期结果 |
|---|---|---|---|---|
| RULESUI-001 | `GET /api/policy/rules` | 打开规则页 | P-006 | 返回两个固定分组当前状态；未创建过的分组（如 `sql-migration.yaml`）显示「还没有规则」 |
| RULESUI-002 | 点击「结构规范」分组的添加图标（该分组尚未创建） | 新建规则集的第一条规则 | P-006，`structure.yaml` 未创建 | 进入「添加规则」视图，可选类别限定为 design 阶段（如 `naming_convention`、`required_business_columns`），不出现 `dangerous_dml_guard` 等 sql 阶段类别 |
| RULESUI-003 | 视图内点击「预填全部 N 条」 | 一键补齐该分组缺的类别 | RULESUI-002 场景 | 补齐当前分组所有还未配置的类别，每条带默认值，未保存前可继续改/删 |
| RULESUI-004 | 单独选择类别 `required_business_columns` | 只加一条规则 | P-006 | 进入该类别的表单，「必备字段」用列表编辑器逐字段配置（字段名/别名/类型/可空性/默认值/注释），不是纯文本 |
| RULESUI-005 | `POST /api/policy/rules {fileName:'structure.yaml', ruleSet:{...}}` | 首次保存规则集 | RULESUI-002~004 之后 | 创建成功，`version` 从 `1` 开始，保存按钮从「保存」变为「已保存」 |
| RULESUI-006 | 修改已保存的规则后 `PUT /api/policy/rules/structure.yaml` | 更新规则集 | RULESUI-005 已执行 | `version` 自增；改动前按钮禁用显示「已保存」，改动后才能点「保存」 |
| RULESUI-007 | 勾选/取消「启用校验」（`PUT /api/policy/rules/structure.yaml/binding {enabled}`） | 绑定生效开关 | RULESUI-005 已执行（已保存过） | 状态点在灰/绿/黄间切换；规则集从未保存过时该勾选框禁用 |
| RULESUI-008 | 删除某分组仅剩的最后一条规则 | 清空规则集 | 该分组恰好只有 1 条规则 | 弹出确认「删掉最后一条规则会清空整个分组」；确认后 `DELETE /api/policy/rules/{fileName}`，分组状态回到「还没有规则」 |
| RULESUI-009 | 在「SQL 与迁移」分组添加规则 | 只能选 sql 阶段类别 | P-006 | 可选类别限定为 `dangerous_dml_guard`、`migration_safety_check`、`dialect_sql_pattern`，不出现 design 阶段类别 |
| RULESUI-010 | 会话未建立时打开规则页 | 图谱工作区连接中 | 无 | 主区提示「正在连接图谱工作区…」，不报错、不崩溃 |

## 值域写入（2026-08-27 补充）

> `schema edit --column` 的三个值域选项写入同一个 `ColumnValueHints`：`--enum-values`
> 整体替换（重复值直接拒绝、不是后者覆盖前者）、`--format` 覆盖单个字符串、`--example`
> 逐个追加且去重。三者面向不同场景（封闭值域 / 有格式的开放值域 / 无规律的开放值域举例），
> 帮助文本建议三选一，但代码没有互斥校验，可以同时写。仅对 `--column` 生效，`--table` 分支不处理这三个选项。

| 用例编号 | 命令 / 参数 | 场景 | 前置条件 | 预期结果 |
|---|---|---|---|---|
| VALDOM-001 | `sql-cli graph_ok schema edit --column trade.orders.status --enum-values "0=待付款,1=已付款,2=已发货,3=已完成,4=已取消"` | 设置封闭值域 | P-006，列存在 | 保存成功，`describe` 输出该列 `\| 值域:0=待付款,1=已付款,2=已发货,3=已完成,4=已取消` |
| VALDOM-002 | `sql-cli graph_ok schema edit --column trade.orders.status --enum-values "0=待付款,0=已支付"` | 值域取值重复 | P-006，列存在 | 拒绝，报错「值域取值重复: 0」，字段不写入，退出码非 0 |
| VALDOM-003 | `sql-cli graph_ok schema edit --column trade.orders.status --enum-values ""` | 清空值域 | VALDOM-001 之后 | 保存成功，`describe` 不再显示「值域」；`describe --json` 的 `valueHints.enumValues` 为空数组 |
| VALDOM-004 | `sql-cli graph_ok schema edit --column trade.orders.remark --format "SM4 密文，前缀 ENC#240606#"` | 设置格式说明 | P-006，列存在 | 保存成功，`describe --json` 的 `valueHints.format` 写入该说明 |
| VALDOM-005 | `sql-cli graph_ok schema edit --column trade.orders.remark --example "首次购买"` | 追加第一个样例值 | P-006，列存在 | 保存成功，`valueHints.sampleValues` 新增一项 |
| VALDOM-006 | `sql-cli graph_ok schema edit --column trade.orders.remark --example "首次购买"` | 追加相同样例值 | VALDOM-005 之后 | 幂等：`sampleValues` 不出现重复项 |
| VALDOM-007 | `sql-cli graph_ok schema edit --column trade.orders.remark --example "复购客户"` | 追加不同样例值 | VALDOM-005 之后 | `sampleValues` 累加为两项，原有样例保留 |
| VALDOM-008 | `sql-cli graph_ok schema edit --column trade.orders.status --enum-values "0=待付款" --format "两位数字编码"` | 同时写入 enum-values 和 format | P-006，列存在 | 两个字段都被写入且并存（无互斥校验），`describe --json` 同时看到 `enumValues` 和 `format` |
| VALDOM-009 | `sql-cli graph_ok schema edit --table trade.orders --description 订单主表 --enum-values "0=x"` | `--table` 分支不处理值域选项 | P-006，表存在 | 表描述照常保存成功；`--enum-values` 被静默忽略，不报错、不写入任何列的 `valueHints` |
