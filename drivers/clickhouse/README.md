# ClickHouse JDBC Driver

## 下载

下载 ClickHouse JDBC 驱动的 shaded (all) 版本：

```bash
# 下载 clickhouse-jdbc-0.9.8-all.jar
curl -L -o clickhouse-jdbc-0.9.8-all.jar \
  https://repo1.maven.org/maven2/com/clickhouse/clickhouse-jdbc/0.9.8/clickhouse-jdbc-0.9.8-all.jar
```

## 验证

```bash
# 验证 SHA256（发布后更新）
shasum -a 256 clickhouse-jdbc-0.9.8-all.jar
```

## 放置路径

将 JAR 文件放置在当前目录（`drivers/clickhouse/`）下。

项目配置中引用路径为 `./drivers/clickhouse/clickhouse-jdbc-0.9.8-all.jar`。

## 版本说明

- 使用 `-all` classifier（带 shaded dependencies），避免依赖冲突
- Driver class: `com.clickhouse.jdbc.ClickHouseDriver`
- 配置统一使用 `jdbc:clickhouse://host:port/database`；旧 `jdbc:ch:` 前缀仅作读取兼容
- V2 实现为默认实现

## 参考

- Maven Central: https://repo1.maven.org/maven2/com/clickhouse/clickhouse-jdbc/
- GitHub Releases: https://github.com/ClickHouse/clickhouse-java/releases
- 官方文档: https://clickhouse.com/docs/integrations/language-clients/java/jdbc
