# 配置文件说明

## 目录结构

```
config/
├── settings.yaml          # 主配置文件（驱动、密码存储、默认设置）
├── aliases.yaml           # macOS 别名配置（使用 keyring）
├── aliases-windows.yaml   # Windows 别名配置（使用 encrypted）
└── schema-graphs/         # Schema 图谱数据存储

drivers/
├── mysql/                 # MySQL JDBC 驱动
├── oracle/                # Oracle JDBC 驱动
├── postgresql/            # PostgreSQL JDBC 驱动
└── clickhouse/            # ClickHouse JDBC 驱动
```

## 驱动目录

每个驱动目录包含一个 README.md 说明下载来源和放置路径。

### ClickHouse 驱动

- 目录：`drivers/clickhouse/`
- JAR：`clickhouse-jdbc-0.9.8-all.jar`（带 shaded dependencies）
- 下载说明：见 `drivers/clickhouse/README.md`
