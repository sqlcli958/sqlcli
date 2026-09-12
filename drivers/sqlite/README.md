# SQLite JDBC Driver

## 不需要下载

`org.xerial:sqlite-jdbc` 已经是 sql-cli 自身的编译依赖（运行库 `~/.sql-cli/sqlcli.db` 就是用它读写的），
打包时随主 jar（`target/sql-cli.jar`）一起带上。`config/settings.yaml` 里 `sqlite3` 驱动条目的
`jars` 因此是空列表 `[]`——`DriverLoader` 遇到空 jars 列表会直接从当前进程的 classpath 加载
`org.sqlite.JDBC`，不走隔离 ClassLoader。这是 sqlite 相对 MySQL/Oracle/PostgreSQL/ClickHouse
这些外部数据库最大的便利：不用去 Maven Central 或官网下 jar，也不用管放哪个目录。

## 连接方式

SQLite 连接的是一个本地文件，不是 host/port：

```yaml
aliases:
  local-sqlite:
    dbType: sqlite
    driverRef: sqlite3
    database: D:/data/shop.db     # 或者用 jdbcUrl: jdbc:sqlite:D:/data/shop.db
    readonly: true
```

不需要 `username`、`secretRef`。

## 文件不存在时的行为

sqlite-jdbc 对不存在的文件不报错，而是静默新建一个空库——`SqliteDatabaseStrategy` 在构建 JDBC URL
时会提前检查文件是否存在，不存在就明确报错并打印解析后的绝对路径。确实需要新建空库，
在别名 `params` 里显式加 `createIfMissing: "true"`。

## 版本说明

- Maven 坐标：`org.xerial:sqlite-jdbc:3.46.1.3`（见根 `pom.xml`）
- Driver class：`org.sqlite.JDBC`
- 没有 schema/catalog 概念，图谱里固定用 `main`（见 `SqliteDatabaseStrategy` 类注释）
- 没有 `COMMENT` 语法，表/字段注释一律为空，属于正常情况

## 参考

- 官方文档：https://github.com/xerial/sqlite-jdbc
- Maven Central：https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/
