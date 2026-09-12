package com.sqlcli.runstate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Local run state database ({@code ~/.sql-cli/sqlcli.db}): execution history, task runs and
 * graph audit. Plain JDBC on SQLite, schema versioned through {@code PRAGMA user_version}.
 *
 * <p>Every operation opens its own short-lived connection: CLI and UI server write concurrently,
 * so WAL plus {@code busy_timeout} does the coordination instead of a shared connection.
 */
public class RunStateStore {
    private static final Logger log = LoggerFactory.getLogger(RunStateStore.class);

    /** Bump when adding a migration step to {@link #migrate}. */
    private static final int SCHEMA_VERSION = 12;
    /**
     * 版本 2 = JSONL 历史已导入。这个门槛必须和 {@link #SCHEMA_VERSION} 分开：
     * 用后者当门槛的话，每加一次表都会让老库重新导一遍 JSONL，历史直接翻倍。
     */
    private static final int LEGACY_IMPORT_VERSION = 2;
    private static final int BUSY_TIMEOUT_MS = 5000;

    /**
     * SQL 里第一个 schema 限定名（{@code schema.table}）的前缀，用于历史按 schema 筛选。
     * 语句没写限定名时退回别名的默认 schema。粗解析，不做 SQL 语法分析——
     * 筛选错一条历史的代价远小于为它引一遍 JSqlParser。
     */
    private static final java.util.regex.Pattern SCHEMA_QUALIFIED = java.util.regex.Pattern.compile(
            "(?i)\\b(?:from|join|into|update|table)\\s+[`\"\\[]?([A-Za-z0-9_$]+)[`\"\\]]?\\s*\\.\\s*[`\"\\[]?[A-Za-z0-9_$]+");

    private static final String[] DDL = {
            """
            CREATE TABLE IF NOT EXISTS sql_execution (
              id            INTEGER PRIMARY KEY AUTOINCREMENT,
              alias         TEXT    NOT NULL,
              sql_type      TEXT,
              masked_sql    TEXT    NOT NULL,
              raw_sql       TEXT,
              status        TEXT    NOT NULL,
              error_summary TEXT,
              affected_rows INTEGER,
              elapsed_ms    INTEGER NOT NULL,
              started_at    INTEGER NOT NULL,
              source        TEXT    NOT NULL DEFAULT 'normal',
              rerun_of      INTEGER REFERENCES sql_execution(id),
              target_schema TEXT,
              session_id    TEXT,
              agent_id      TEXT
            )""",
            "CREATE INDEX IF NOT EXISTS idx_sql_execution_alias_started "
                    + "ON sql_execution(alias, started_at DESC)",
            """
            CREATE TABLE IF NOT EXISTS task_run (
              id              INTEGER PRIMARY KEY AUTOINCREMENT,
              task_type       TEXT    NOT NULL,
              actor           TEXT,
              alias           TEXT,
              environment     TEXT,
              status          TEXT    NOT NULL,
              sql_hash        TEXT,
              policy_revision INTEGER,
              created_at      INTEGER NOT NULL,
              updated_at      INTEGER NOT NULL
            )""",
            """
            CREATE TABLE IF NOT EXISTS task_event (
              id          INTEGER PRIMARY KEY AUTOINCREMENT,
              task_run_id INTEGER NOT NULL REFERENCES task_run(id),
              event_type  TEXT    NOT NULL,
              payload     TEXT,
              created_at  INTEGER NOT NULL
            )""",
            "CREATE INDEX IF NOT EXISTS idx_task_event_run ON task_event(task_run_id, id)",
            """
            CREATE TABLE IF NOT EXISTS recovery_artifact (
              id           INTEGER PRIMARY KEY AUTOINCREMENT,
              execution_id INTEGER REFERENCES sql_execution(id),
              status       TEXT    NOT NULL,
              created_at   INTEGER NOT NULL,
              rollback_sql TEXT,
              backup_text  TEXT
            )""",
            """
            CREATE TABLE IF NOT EXISTS graph_change_log (
              id              INTEGER PRIMARY KEY AUTOINCREMENT,
              alias           TEXT    NOT NULL,
              operation       TEXT    NOT NULL,
              target_id       TEXT,
              actor           TEXT,
              revision_before INTEGER,
              revision_after  INTEGER,
              created_at      INTEGER NOT NULL,
              payload         TEXT,
              approval_id     INTEGER,
              reason          TEXT
            )""",
            "CREATE INDEX IF NOT EXISTS idx_graph_change_log_alias ON graph_change_log(alias, id)",
            """
            CREATE TABLE IF NOT EXISTS approval_request (
              id         INTEGER PRIMARY KEY AUTOINCREMENT,
              alias      TEXT    NOT NULL,
              kind       TEXT    NOT NULL,
              summary    TEXT    NOT NULL,
              detail     TEXT,
              status     TEXT    NOT NULL,
              reason     TEXT,
              created_at INTEGER NOT NULL,
              decided_at INTEGER,
              task_run_id INTEGER,
              target_id  TEXT,
              payload    TEXT,
              batch_id   INTEGER,
              seq        INTEGER,
              session_id TEXT,
              agent_id   TEXT,
              execution_id INTEGER
            )""",
            "CREATE INDEX IF NOT EXISTS idx_approval_status ON approval_request(status, id DESC)",
            """
            CREATE TABLE IF NOT EXISTS approval_batch (
              id           INTEGER PRIMARY KEY AUTOINCREMENT,
              alias        TEXT    NOT NULL,
              kind         TEXT    NOT NULL,
              intent       TEXT    NOT NULL,
              status       TEXT    NOT NULL,
              recoverable  INTEGER NOT NULL DEFAULT 1,
              created_at   INTEGER NOT NULL,
              submitted_at INTEGER,
              decided_at   INTEGER,
              decided_by   TEXT,
              reason       TEXT
            )""",
            "CREATE INDEX IF NOT EXISTS idx_approval_batch_status "
                    + "ON approval_batch(status, id DESC)",
            """
            CREATE TABLE IF NOT EXISTS policy_evaluation (
              id          TEXT    PRIMARY KEY,
              alias       TEXT    NOT NULL,
              status      TEXT,
              rule_set_id TEXT,
              started_at  INTEGER NOT NULL,
              evaluation  TEXT    NOT NULL,
              rule_set    TEXT    NOT NULL
            )""",
            "CREATE INDEX IF NOT EXISTS idx_policy_evaluation_alias "
                    + "ON policy_evaluation(alias, started_at DESC)",
            """
            CREATE TABLE IF NOT EXISTS policy_violation (
              id            TEXT NOT NULL,
              evaluation_id TEXT NOT NULL REFERENCES policy_evaluation(id),
              rule_id       TEXT,
              target_id     TEXT,
              severity      TEXT,
              status        TEXT,
              payload       TEXT NOT NULL,
              PRIMARY KEY (evaluation_id, id)
            )""",
            "CREATE INDEX IF NOT EXISTS idx_policy_violation_rule "
                    + "ON policy_violation(rule_id, target_id)",
            /*
             * 图谱评估（schema eval），和上面 policy 那两张表平行但**不共用**：
             *   1. policy_evaluation 的 rule_set_id / rule_set NOT NULL 图谱评估没有对应对象，
             *      伪造一个假 ruleset 会让规则页的规则集列表冒出假条目——污染另一个功能的语义；
             *   2. 评估有「指标」（要画趋势），policy 只有「违规」，没有这一列；
             *   3. finding 带修法命令，violation 没有这个概念。
             */
            """
            CREATE TABLE IF NOT EXISTS graph_evaluation (
              id            TEXT    PRIMARY KEY,
              alias         TEXT    NOT NULL,
              source        TEXT    NOT NULL,
              status        TEXT    NOT NULL,
              revision      INTEGER NOT NULL,
              started_at    INTEGER NOT NULL,
              elapsed_ms    INTEGER,
              error_count   INTEGER NOT NULL DEFAULT 0,
              warning_count INTEGER NOT NULL DEFAULT 0,
              metrics       TEXT
            )""",
            "CREATE INDEX IF NOT EXISTS idx_graph_evaluation_alias "
                    + "ON graph_evaluation(alias, started_at DESC)",
            /*
             * finding 独立成表、不塞一行 JSON：policy/runs 那次「3 轮评估 2MB、5 万行违规，
             * 列表每次全目录扫」的教训——工作队列要能排序、能分页。
             */
            """
            CREATE TABLE IF NOT EXISTS graph_finding (
              evaluation_id TEXT    NOT NULL REFERENCES graph_evaluation(id),
              seq           INTEGER NOT NULL,
              probe         TEXT    NOT NULL,
              severity      TEXT    NOT NULL,
              target_id     TEXT,
              message       TEXT    NOT NULL,
              remediation   TEXT,
              PRIMARY KEY (evaluation_id, seq)
            )""",
            "CREATE INDEX IF NOT EXISTS idx_graph_finding_probe "
                    + "ON graph_finding(probe, target_id)",
            /*
             * **agent 与 sql-cli 的每一次交互**，一条命令一行。原名 graph_read，
             * 只记 search / describe / path 三个读动作；v12 扩成全部命令并加上成败。
             *
             * <p>为什么需要它：运行库其余 11 张表记的都是「一次**结果**」——一次变更、
             * 一次执行、一次审批、一次评估。而「agent 敲了一条命令、失败了」不产生任何结果，
             * 于是没有落点。实测 graph_change_log 一万条全是成功操作（它是变更日志，
             * 按定义装不下失败），所以 schema edit 参数写错、add-relation 端点不存在、
             * revision 冲突被拦下、空壳术语被 T4 闸门拒绝——**全部不可见**。
             * 而这些恰恰是「图谱不够用 / agent 心智模型跟规则对不上」的最直接证据。
             *
             * <p><b>不合并 graph_change_log 和 sql_execution。</b>前者是审计轨迹
             * （带 before/after 和 approval_id，用来重放和 diff），后者存 raw_sql 是给
             * 重跑和回滚用的。往任何一张里塞失败都会毁掉它原本的性质。这张表只记
             * 「发生了一次交互、成没成」，有明细时用 ref_table/ref_id 指过去，不复制内容。
             *
             * <p>{@code query} 存原文，不做归一化 / 分词 / 小写化：要解决的恰恰是
             * 「业务词搜不到对的表」，把词磨平就把线索磨掉了。
             *
             * <p>{@code target_ids} 只有读动作填，是 {@code schema gaps} 排优先级的依据——
             * 只记 query 字符串拼不出「哪张表被搜到过多少次」。
             */
            """
            CREATE TABLE IF NOT EXISTS agent_interaction (
              id            INTEGER PRIMARY KEY AUTOINCREMENT,
              alias         TEXT,
              action        TEXT    NOT NULL,
              query         TEXT,
              hit_count     INTEGER,
              top_score     REAL,
              target_ids    TEXT,
              status        TEXT,
              exit_code     INTEGER,
              error_summary TEXT,
              elapsed_ms    INTEGER,
              ref_table     TEXT,
              ref_id        INTEGER,
              session_id    TEXT,
              agent_id      TEXT,
              created_at    INTEGER NOT NULL
            )""",
            "CREATE INDEX IF NOT EXISTS idx_agent_interaction_alias "
                    + "ON agent_interaction(alias, created_at DESC)",
            "CREATE INDEX IF NOT EXISTS idx_agent_interaction_status "
                    + "ON agent_interaction(status, action)"
    };

    /**
     * **建在新加列上的**索引，必须等 {@link #ADDED_COLUMNS} 的 ALTER 跑完才能建。
     *
     * <p>放进 {@link #DDL} 会在老库上炸：那一组在加列**之前**执行，
     * 索引引用的列还不存在，整个迁移失败回滚，于是 user_version 停在旧值、列永远补不上，
     * 表现成「每次写审批都报 no such column」。踩过一次，别再往 DDL 里加这种索引。
     */
    private static final String[] POST_MIGRATION_DDL = {
            "CREATE INDEX IF NOT EXISTS idx_approval_batch ON approval_request(batch_id, seq)"
    };

    /**
     * 新库由上面的 DDL 直接带上的列，老库要靠 ALTER 补。
     * 两边写法必须一致，否则同一个字段在两种库里类型不同。
     */
    private static final String[][] ADDED_COLUMNS = {
            {"approval_request", "task_run_id", "INTEGER"},
            {"approval_request", "target_id", "TEXT"},
            // 图谱审批改成「先落 db、批准后才写图谱」之后，待批准的变更本身存在这里
            {"approval_request", "payload", "TEXT"},
            // 每次图谱更新的可追溯记录：改了什么（before/after）、是哪条审批放行的
            {"graph_change_log", "payload", "TEXT"},
            {"graph_change_log", "approval_id", "INTEGER"},
            {"graph_change_log", "reason", "TEXT"},
            {"sql_execution", "target_schema", "TEXT"},
            // 溯源：这条执行 / 这条审批出自哪次会话、哪个 agent
            {"sql_execution", "session_id", "TEXT"},
            {"sql_execution", "agent_id", "TEXT"},
            // 批次：多条变更作为一个业务意图一次提交、一次裁决
            {"approval_request", "batch_id", "INTEGER"},
            {"approval_request", "seq", "INTEGER"},
            {"approval_request", "session_id", "TEXT"},
            {"approval_request", "agent_id", "TEXT"},
            // 批次里的 SQL 条目跑完之后指回那条执行记录——结果不进批次表，
            // 两个地方存同一份数据比多一条代码路径糟得多
            {"approval_request", "execution_id", "INTEGER"},
            // v12：交互日志从「只记 3 个读动作」扩成「记每一次交互，含失败」
            {"agent_interaction", "status", "TEXT"},
            {"agent_interaction", "exit_code", "INTEGER"},
            {"agent_interaction", "error_summary", "TEXT"},
            {"agent_interaction", "elapsed_ms", "INTEGER"},
            {"agent_interaction", "ref_table", "TEXT"},
            {"agent_interaction", "ref_id", "INTEGER"},
            // 反哺要回答的是「agent 读了什么 → 有没有写回」，而变更流水原来只有
            // actor（agent/human/system 三个值），接不上是哪一次会话、哪个 agent
            {"graph_change_log", "session_id", "TEXT"},
            {"graph_change_log", "agent_id", "TEXT"},
            {"recovery_artifact", "rollback_sql", "TEXT"},
            {"recovery_artifact", "backup_text", "TEXT"}
    };

    static {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            // 不能只记 debug：驱动缺失时下面每一次读写都会静默失败，
            // 表现为「运行记录永远是空的」，很难反查到根因在这里。
            log.warn("sqlite-jdbc driver not on classpath —— 运行记录与任务状态将无法保存");
        }
    }

    private final Path dbPath;
    private final Path legacyHistoryDir;
    private volatile boolean ready;

    public RunStateStore() {
        this(home().resolve("sqlcli.db"), home().resolve("execution-history"));
    }

    public RunStateStore(Path dbPath, Path legacyHistoryDir) {
        this.dbPath = dbPath;
        this.legacyHistoryDir = legacyHistoryDir;
    }

    /** Tests point {@code sqlcli.home} elsewhere so they never touch the real database. */
    private static Path home() {
        String override = System.getProperty("sqlcli.home");
        return override != null && !override.isBlank()
                ? Path.of(override)
                : Path.of(System.getProperty("user.home"), ".sql-cli");
    }

    // ---------------------------------------------------------------- writes

    public long recordExecution(String alias, String sqlType, String sql, String status,
                                String errorSummary, Long affectedRows, long elapsedMs, long startedAt) {
        return recordExecution(alias, sqlType, sql, status, errorSummary, affectedRows, elapsedMs, startedAt,
                "normal", null);
    }

    public long recordExecution(String alias, String sqlType, String sql, String status,
                                String errorSummary, Long affectedRows, long elapsedMs, long startedAt,
                                String source, Long rerunOf) {
        return recordExecution(alias, sqlType, sql, status, errorSummary, affectedRows, elapsedMs, startedAt,
                source, rerunOf, null);
    }

    /**
     * @param sql            original statement; stored both masked (for lists) and verbatim in
     *                       {@code raw_sql}. 写语句同样存原文——它的恢复脚本就在隔壁表里，
     *                       连原始行都存了，再把 SQL 打码只会让人看不出这条历史改了哪一行。
     * @param defaultSchema  别名的默认 schema，SQL 里没写限定名时用它标注这条历史
     */
    public long recordExecution(String alias, String sqlType, String sql, String status,
                                String errorSummary, Long affectedRows, long elapsedMs, long startedAt,
                                String source, Long rerunOf, String defaultSchema) {
        String type = sqlType == null ? null : sqlType.toUpperCase(Locale.ROOT);
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO sql_execution (alias, sql_type, masked_sql, raw_sql, status, error_summary,"
                             + " affected_rows, elapsed_ms, started_at, source, rerun_of, target_schema,"
                             + " session_id, agent_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, alias);
            ps.setString(2, type);
            ps.setString(3, maskSql(sql));
            ps.setString(4, sql);
            ps.setString(5, status);
            ps.setString(6, errorSummary);
            setNullableLong(ps, 7, affectedRows);
            ps.setLong(8, elapsedMs);
            ps.setLong(9, startedAt);
            ps.setString(10, source == null ? "normal" : source);
            setNullableLong(ps, 11, rerunOf);
            ps.setString(12, targetSchema(sql, defaultSchema));
            // 自动放行的查询不建审批条目（status=approved 而无人裁决 = 把日志伪装成审批），
            // 它的溯源就落在这两列上。取不到记 null，不编占位 id。
            ps.setString(13, com.sqlcli.session.SessionContext.sessionId());
            ps.setString(14, com.sqlcli.session.SessionContext.agentId());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : 0L;
            }
        } catch (Exception e) {
            // Audit storage must not make a valid database operation fail.
            log.debug("failed to record execution for alias {}", alias, e);
            return 0L;
        }
    }


    // ------------------------------------------------------- policy evaluation runs

    /**
     * 一条规则评估的违规行。{@code payload} 是 {@code PolicyViolation} 的完整 JSON，
     * 其余几列只为查询存在（「这条规则历史上违规多少次」「这张表反复违反哪条」）。
     *
     * <p>为什么不把 15 个字段全铺成列：查询只用得到其中 4 个，剩下的铺开来只会让
     * 模型每加一个字段就要改一次 DDL；而 payload 里存的就是今天写进 jsonl 的同一份内容，
     * 保真是免费的。
     */
    public record PolicyViolationRow(String id, String ruleId, String targetId,
            String severity, String status, String payload) {
    }

    /** 一次评估的全部内容：评估本身、当时用的规则集原文、违规列表。 */
    public record PolicyRunRow(String evaluationJson, String ruleSetYaml, List<String> violations) {
    }

    public boolean policyRunExists(String evaluationId) {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM policy_evaluation WHERE id = ?")) {
            ps.setString(1, evaluationId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            log.debug("failed to check policy run {}", evaluationId, e);
            return false;
        }
    }

    /**
     * 评估结果整体落库。评估行与违规行在<b>同一个事务</b>里写完——
     * 半条评估（有头没有违规明细）比没有更糟，它会让规则页显示「0 违规」这种假事实。
     */
    public void savePolicyRun(String alias, String evaluationId, String status, String ruleSetId,
            long startedAt, String evaluationJson, String ruleSetYaml,
            List<PolicyViolationRow> violations) throws IOException {
        try (Connection conn = connect()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO policy_evaluation (id, alias, status, rule_set_id, started_at,"
                                + " evaluation, rule_set) VALUES (?,?,?,?,?,?,?)")) {
                    ps.setString(1, evaluationId);
                    ps.setString(2, alias);
                    ps.setString(3, status);
                    ps.setString(4, ruleSetId);
                    ps.setLong(5, startedAt);
                    ps.setString(6, evaluationJson);
                    ps.setString(7, ruleSetYaml);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO policy_violation (id, evaluation_id, rule_id, target_id,"
                                + " severity, status, payload) VALUES (?,?,?,?,?,?,?)")) {
                    for (PolicyViolationRow row : violations) {
                        ps.setString(1, row.id());
                        ps.setString(2, evaluationId);
                        ps.setString(3, row.ruleId());
                        ps.setString(4, row.targetId());
                        ps.setString(5, row.severity());
                        ps.setString(6, row.status());
                        ps.setString(7, row.payload());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IOException("规则评估结果落库失败：" + e.getMessage(), e);
        }
    }

    /** 一次评估的全部内容；不存在返回 null。 */
    public PolicyRunRow loadPolicyRun(String evaluationId) throws IOException {
        try (Connection conn = connect()) {
            String evaluationJson;
            String ruleSetYaml;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT evaluation, rule_set FROM policy_evaluation WHERE id = ?")) {
                ps.setString(1, evaluationId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    evaluationJson = rs.getString(1);
                    ruleSetYaml = rs.getString(2);
                }
            }
            List<String> violations = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT payload FROM policy_violation WHERE evaluation_id = ? ORDER BY rowid")) {
                ps.setString(1, evaluationId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) violations.add(rs.getString(1));
                }
            }
            return new PolicyRunRow(evaluationJson, ruleSetYaml, violations);
        } catch (SQLException e) {
            throw new IOException("读取规则评估失败：" + e.getMessage(), e);
        }
    }

    /** 某别名的全部评估，最近的在前。返回的是 {@code RuleEvaluation} 的 JSON 原文。 */
    public List<String> listPolicyEvaluations(String alias) throws IOException {
        List<String> result = new ArrayList<>();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT evaluation FROM policy_evaluation WHERE alias = ?"
                             + " ORDER BY started_at DESC, id DESC")) {
            ps.setString(1, alias);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(rs.getString(1));
            }
        } catch (SQLException e) {
            throw new IOException("读取规则评估列表失败：" + e.getMessage(), e);
        }
        return result;
    }

    // ------------------------------------------------------- graph evaluation runs

    /**
     * 一次图谱评估。{@code source} 区分它是哪个命令产的：
     * {@code eval}（跑全部探针，带指标）还是 {@code value-domain}（只落那几条没有值域可写的
     * 发现，没有指标）。趋势图只该拿 {@code eval} 那些点连线——把只跑了一个探针的运行
     * 混进去，硬错误数会凭空掉到 0，看起来像修好了。
     */
    public record GraphEvaluationRow(String id, String alias, String source, String status,
            long revision, long startedAt, Long elapsedMs, int errorCount, int warningCount,
            String metricsJson) {
    }

    /** 一条 finding，字段与 {@code GraphFinding} 一一对应；{@code seq} 是它在这次评估里的序号。 */
    public record GraphFindingRow(int seq, String probe, String severity, String targetId,
            String message, String remediation) {
    }

    /**
     * 评估与 finding 在<b>同一个事务</b>里写完——半条评估（有头没有明细）比没有更糟，
     * 它会让评估页显示「0 条发现」这种假事实。
     */
    public void saveGraphEvaluation(GraphEvaluationRow evaluation, List<GraphFindingRow> findings)
            throws IOException {
        try (Connection conn = connect()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO graph_evaluation (id, alias, source, status, revision, started_at,"
                                + " elapsed_ms, error_count, warning_count, metrics)"
                                + " VALUES (?,?,?,?,?,?,?,?,?,?)")) {
                    ps.setString(1, evaluation.id());
                    ps.setString(2, evaluation.alias());
                    ps.setString(3, evaluation.source());
                    ps.setString(4, evaluation.status());
                    ps.setLong(5, evaluation.revision());
                    ps.setLong(6, evaluation.startedAt());
                    setNullableLong(ps, 7, evaluation.elapsedMs());
                    ps.setInt(8, evaluation.errorCount());
                    ps.setInt(9, evaluation.warningCount());
                    ps.setString(10, evaluation.metricsJson());
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO graph_finding (evaluation_id, seq, probe, severity, target_id,"
                                + " message, remediation) VALUES (?,?,?,?,?,?,?)")) {
                    for (GraphFindingRow row : findings) {
                        ps.setString(1, evaluation.id());
                        ps.setInt(2, row.seq());
                        ps.setString(3, row.probe());
                        ps.setString(4, row.severity());
                        ps.setString(5, row.targetId());
                        ps.setString(6, row.message());
                        ps.setString(7, row.remediation());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IOException("图谱评估结果落库失败：" + e.getMessage(), e);
        }
    }

    /** 某别名的评估列表，最近的在前；{@code alias} 为 null 时不筛别名。 */
    public List<GraphEvaluationRow> listGraphEvaluations(String alias, int limit) throws IOException {
        List<GraphEvaluationRow> result = new ArrayList<>();
        String sql = "SELECT id, alias, source, status, revision, started_at, elapsed_ms,"
                + " error_count, warning_count, metrics FROM graph_evaluation"
                + (alias == null ? "" : " WHERE alias = ?")
                + " ORDER BY started_at DESC, id DESC LIMIT ?";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            int index = 1;
            if (alias != null) ps.setString(index++, alias);
            ps.setInt(index, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new GraphEvaluationRow(rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getLong(5), rs.getLong(6), nullableLong(rs, 7),
                            rs.getInt(8), rs.getInt(9), rs.getString(10)));
                }
            }
        } catch (SQLException e) {
            throw new IOException("读取图谱评估列表失败：" + e.getMessage(), e);
        }
        return result;
    }

    /** 一次评估的 finding，按序号分页——硬错误在前是评估侧排好的顺序，这里原样返回。 */
    public List<GraphFindingRow> listGraphFindings(String evaluationId, int offset, int limit)
            throws IOException {
        List<GraphFindingRow> result = new ArrayList<>();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT seq, probe, severity, target_id, message, remediation FROM graph_finding"
                             + " WHERE evaluation_id = ? ORDER BY seq LIMIT ? OFFSET ?")) {
            ps.setString(1, evaluationId);
            ps.setInt(2, limit);
            ps.setInt(3, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new GraphFindingRow(rs.getInt(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getString(6)));
                }
            }
        } catch (SQLException e) {
            throw new IOException("读取图谱评估发现失败：" + e.getMessage(), e);
        }
        return result;
    }

    public int countGraphFindings(String evaluationId) throws IOException {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM graph_finding WHERE evaluation_id = ?")) {
            ps.setString(1, evaluationId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new IOException("读取图谱评估发现失败：" + e.getMessage(), e);
        }
    }

    /** 一条执行记录；不存在返回 null。回滚端点靠它从 id 找回别名。 */
    public SqlExecutionRow findExecution(long id) {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, alias, sql_type, masked_sql, raw_sql, status, error_summary, affected_rows,"
                             + " elapsed_ms, started_at, source, rerun_of, target_schema"
                             + " FROM sql_execution WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new SqlExecutionRow(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                        nullableLong(rs, 8), rs.getLong(9), rs.getLong(10), rs.getString(11),
                        nullableLong(rs, 12), rs.getString(13));
            }
        } catch (Exception e) {
            log.debug("failed to read execution {}", id, e);
            return null;
        }
    }

    /**
     * 把恢复脚本存进库，返回登记 id；<b>0 表示没存下</b>。
     *
     * <p>调用时机是写操作执行<em>之前</em>，所以返回 0 必须让调用方中止本次写——
     * 存不下恢复脚本就等于这次改动没有后悔药。这也是它和其余审计写入不同的地方：
     * 别的失败可以吞，这个不能。
     *
     * <p>此刻还没有 {@code sql_execution} 行（那是执行完才落的），所以 execution_id
     * 先留空，等 {@link #attachRecoveryArtifact} 补挂。
     */
    public long saveRecoveryScript(String rollbackSql, String backupText) {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO recovery_artifact (execution_id, status, created_at,"
                             + " rollback_sql, backup_text) VALUES (NULL,'saved',?,?,?)")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, rollbackSql);
            ps.setString(3, backupText);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : 0L;
            }
        } catch (Exception e) {
            log.warn("failed to save recovery script: {}", e.toString());
            return 0L;
        }
    }

    /** 写操作落完审计后，把恢复脚本挂到那条执行记录上。 */
    public void attachRecoveryArtifact(long artifactId, long executionId) {
        if (artifactId <= 0 || executionId <= 0) return;
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE recovery_artifact SET execution_id = ? WHERE id = ?")) {
            ps.setLong(1, executionId);
            ps.setLong(2, artifactId);
            ps.executeUpdate();
        } catch (Exception e) {
            // 脚本本身已经存住了，挂不上只是历史里点不出「可回滚」。
            log.debug("failed to attach recovery artifact {}", artifactId, e);
        }
    }

    /**
     * 一条执行记录的恢复脚本；没有登记过返回 null。
     *
     * <p>脚本一律在库里——升级前留在文件里的那些已由 {@code backfillRecoveryScripts}
     * 一次性搬进来，调用方不需要再认第二种形态。
     */
    public RecoveryArtifactRow findRecovery(long executionId) {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, rollback_sql, backup_text FROM recovery_artifact"
                             + " WHERE execution_id = ? ORDER BY id DESC LIMIT 1")) {
            ps.setLong(1, executionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? new RecoveryArtifactRow(rs.getLong(1), rs.getString(2), rs.getString(3))
                        : null;
            }
        } catch (Exception e) {
            log.debug("failed to read recovery artifact for execution {}", executionId, e);
            return null;
        }
    }


    // ----------------------------------------------------------------- tasks

    /**
     * 新建一条写任务记录，返回它的 id；返回 0 表示落库失败（调用方应继续执行，
     * 只是这次操作在 {@code task status} 里查不到，不阻断真正的数据库写入）。
     */
    public long createTaskRun(String alias, String taskType, String actor, String sqlHash) {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO task_run (task_type, actor, alias, status, sql_hash, created_at, updated_at)"
                             + " VALUES (?,?,?,?,?,?,?)")) {
            long now = System.currentTimeMillis();
            ps.setString(1, taskType);
            ps.setString(2, actor);
            ps.setString(3, alias);
            ps.setString(4, "running");
            ps.setString(5, sqlHash);
            ps.setLong(6, now);
            ps.setLong(7, now);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : 0L;
            }
        } catch (Exception e) {
            log.debug("failed to create task run for alias {}", alias, e);
            return 0L;
        }
    }

    /** 追加一条任务事件；{@code taskRunId <= 0} 时静默跳过（对应 createTaskRun 落库失败的情形）。 */
    public void recordTaskEvent(long taskRunId, String eventType, String payload) {
        if (taskRunId <= 0) return;
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO task_event (task_run_id, event_type, payload, created_at) VALUES (?,?,?,?)")) {
            ps.setLong(1, taskRunId);
            ps.setString(2, eventType);
            ps.setString(3, payload);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (Exception e) {
            log.debug("failed to record task event {} for run {}", eventType, taskRunId, e);
        }
    }

    /** 把任务置为终态（succeeded / rejected / failed）；不影响真正的执行结果，只是审计。 */
    public void finishTaskRun(long taskRunId, String status) {
        if (taskRunId <= 0) return;
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE task_run SET status = ?, updated_at = ? WHERE id = ?")) {
            ps.setString(1, status);
            ps.setLong(2, System.currentTimeMillis());
            ps.setLong(3, taskRunId);
            ps.executeUpdate();
        } catch (Exception e) {
            log.debug("failed to finish task run {}", taskRunId, e);
        }
    }

    // ------------------------------------------------------------- approvals

    /** 新建一条待审批请求，返回它的 id；返回 0 表示落库失败。 */
    public long createApproval(String alias, String kind, String summary, String detail) {
        return createApproval(alias, kind, summary, detail, null);
    }

    /**
     * @param taskRunId 关联的写任务；null 表示这次审批背后没有 task_run（读操作、图谱变更）。
     *                  审批详情页的预检块和时间线都是顺着这个 id 找 task_event 的，
     *                  不挂上就等于详情页永远只有一段 SQL 摘要。
     */
    public long createApproval(String alias, String kind, String summary, String detail, Long taskRunId) {
        return createApproval(alias, kind, summary, detail, taskRunId, null);
    }

    /**
     * @param targetId 审批针对的对象 id（如图谱关系 {@code relation:...}、执行记录
     *                 {@code execution:...}）;详情端点靠它把候选的 diff / 证据 / 校验拼出来。
     */
    public long createApproval(String alias, String kind, String summary, String detail,
                               Long taskRunId, String targetId) {
        return createApproval(alias, kind, summary, detail, taskRunId, targetId, null);
    }

    /**
     * @param payload 图谱审批专用：待批准的变更本身（{@code GraphChangePayload} 的 JSON）。
     *                图谱走「先落 db、批准后才写图谱」，批准时靠它重放；其余 kind 传 null。
     */
    public long createApproval(String alias, String kind, String summary, String detail,
                               Long taskRunId, String targetId, String payload) {
        return createApproval(alias, kind, summary, detail, taskRunId, targetId, payload,
                "pending", null, null);
    }

    /**
     * 全参版本。
     *
     * @param status  {@code pending}（进队列等人）、{@code approved}（别名是 auto，
     *                建好即批准，只为留底，不进待审批队列）、{@code draft}（归入某个批次、
     *                但那批还没 submit——不进任何队列，也不会落地）
     * @param batchId 归入哪一批；null = 独立条目
     * @param seq     批次内序号，SQL 批次靠它定执行顺序；独立条目为 null
     */
    public long createApproval(String alias, String kind, String summary, String detail,
                               Long taskRunId, String targetId, String payload,
                               String status, Long batchId, Integer seq) {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO approval_request (alias, kind, summary, detail, status, created_at,"
                             + " task_run_id, target_id, payload, batch_id, seq, session_id, agent_id)"
                             + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, alias);
            ps.setString(2, kind);
            ps.setString(3, summary);
            ps.setString(4, detail);
            ps.setString(5, status == null ? "pending" : status);
            ps.setLong(6, System.currentTimeMillis());
            setNullableLong(ps, 7, taskRunId);
            ps.setString(8, targetId);
            ps.setString(9, payload);
            setNullableLong(ps, 10, batchId);
            if (seq == null) ps.setNull(11, java.sql.Types.INTEGER); else ps.setInt(11, seq);
            ps.setString(12, com.sqlcli.session.SessionContext.sessionId());
            ps.setString(13, com.sqlcli.session.SessionContext.agentId());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : 0L;
            }
        } catch (Exception e) {
            // 审批落不了库就等于没有审批入口，这里不能像审计那样吞掉。
            log.warn("failed to create approval request for alias {}: {}", alias, e.toString());
            return 0L;
        }
    }

    public ApprovalRow findApproval(long id) {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(APPROVAL_COLUMNS + " WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readApproval(rs) : null;
            }
        } catch (Exception e) {
            log.debug("failed to read approval {}", id, e);
            return null;
        }
    }

    /** Newest first. {@code status} 为 null 时不过滤。 */
    public List<ApprovalRow> listApprovals(String status, int limit) {
        return listApprovals(null, status, null, null, limit, 0);
    }

    /**
     * Newest first，null 过滤条件忽略。
     *
     * <p>类型筛选必须在这里做而不是前端过滤一页数据：分页之后「当前页里符合类型的那几条」
     * 和「符合类型的第 N 页」是两回事。
     *
     * @param alias        只看这个数据源的审批，null 表示跨数据源
     * @param createdAfter 只要这个时间点之后创建的（毫秒时间戳）
     */
    public List<ApprovalRow> listApprovals(String alias, String status, String kind, Long createdAfter,
                                           int limit, int offset) {
        return listApprovals(alias, status, kind, null, createdAfter, limit, offset);
    }

    /**
     * @param excludeKind 排除这个类型。评审页的「待审批」标签用它把 graph 排掉——
     *                    图谱变更有自己的标签（带 before/after diff），两边都列会让人
     *                    以为是两件事，或者在一边批完另一边还挂着。
     */
    public List<ApprovalRow> listApprovals(String alias, String status, String kind,
                                           String excludeKind, Long createdAfter,
                                           int limit, int offset) {
        return listApprovals(alias, status, null, kind, excludeKind, createdAfter, limit, offset);
    }

    /**
     * @param excludeStatus 排除这个状态。评审页的「图谱」标签传 pending——它是历史记录视图，
     *                      还没裁决的图谱变更在「待审批」标签里，两边都列就成了两个入口。
     */
    public List<ApprovalRow> listApprovals(String alias, String status, String excludeStatus,
                                           String kind, String excludeKind, Long createdAfter,
                                           int limit, int offset) {
        Filter filter = approvalFilter(alias, status, excludeStatus, kind, excludeKind, createdAfter);
        StringBuilder sql = new StringBuilder(APPROVAL_COLUMNS).append(filter.clause());
        List<Object> args = new ArrayList<>(filter.args());
        sql.append(" ORDER BY id DESC LIMIT ? OFFSET ?");
        args.add(Math.max(1, Math.min(limit, 500)));
        args.add(Math.max(0, offset));
        List<ApprovalRow> rows = new ArrayList<>();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < args.size(); i++) ps.setObject(i + 1, args.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows.add(readApproval(rs));
            }
        } catch (Exception e) {
            log.debug("failed to list approvals", e);
        }
        return rows;
    }

    /** 同 {@link #listApprovals} 条件下的总条数，分页器算总页数用。 */
    public int countApprovals(String alias, String status, String kind, Long createdAfter) {
        return countApprovals(alias, status, kind, null, createdAfter);
    }

    public int countApprovals(String alias, String status, String kind, String excludeKind,
                              Long createdAfter) {
        return countApprovals(alias, status, null, kind, excludeKind, createdAfter);
    }

    public int countApprovals(String alias, String status, String excludeStatus, String kind,
                              String excludeKind, Long createdAfter) {
        Filter filter = approvalFilter(alias, status, excludeStatus, kind, excludeKind, createdAfter);
        return count("SELECT COUNT(*) FROM approval_request" + filter.clause(), filter.args());
    }

    private static Filter approvalFilter(String alias, String status, String excludeStatus,
                                         String kind, String excludeKind, Long createdAfter) {
        StringBuilder sql = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        appendFilter(sql, args, " AND alias = ?", alias);
        appendFilter(sql, args, " AND status = ?", status);
        appendFilter(sql, args, " AND status <> ?", excludeStatus);
        appendFilter(sql, args, " AND kind = ?", kind);
        appendFilter(sql, args, " AND kind <> ?", excludeKind);
        appendFilter(sql, args, " AND created_at >= ?", createdAfter);
        // 草稿条目属于一个还没 submit 的批次：它不在队列里，也还没发生过，
        // 待审批和审批记录两个视图都不该看到它。要看未提交的东西用 `batch list --draft`。
        if (!ApprovalRow.STATUS_DRAFT.equals(status)) {
            sql.append(" AND status <> '").append(ApprovalRow.STATUS_DRAFT).append("'");
        }
        return new Filter(sql.toString(), args);
    }

    /**
     * 裁决一条待审批请求。
     *
     * <p>状态写在 WHERE 里而不是先读后判断：等待方（CLI 进程）超时置 expired 和
     * 审批方（UI 进程）点批准是两个进程在抢同一行，只有 UPDATE 的原子性能定输赢。
     *
     * @return true 表示这次调用真的改变了状态
     */
    public boolean decideApproval(long id, String status, String reason) {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE approval_request SET status = ?, reason = ?, decided_at = ?"
                             + " WHERE id = ? AND status = 'pending'")) {
            ps.setString(1, status);
            ps.setString(2, reason);
            ps.setLong(3, System.currentTimeMillis());
            ps.setLong(4, id);
            return ps.executeUpdate() > 0;
        } catch (Exception e) {
            log.warn("failed to decide approval {}: {}", id, e.toString());
            return false;
        }
    }

    /**
     * 结掉挂在同一个对象上的待审批。
     *
     * <p>候选关系的发布 / 拒绝有两个触发点：审批中心裁决那条审批，或者代码里直接调
     * {@code publishRelation}（批量评审、CLI）。后者发生时那条审批还挂着，不同步结掉
     * 就是一个已经处理完的数字永远留在待审批角标上。
     *
     * @return 结掉的条数
     */
    public int decidePendingApprovalsByTarget(String alias, String kind, String targetId,
                                              String status, String reason) {
        if (targetId == null) return 0;
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE approval_request SET status = ?, reason = ?, decided_at = ?"
                             + " WHERE alias = ? AND kind = ? AND target_id = ? AND status = 'pending'")) {
            ps.setString(1, status);
            ps.setString(2, reason);
            ps.setLong(3, System.currentTimeMillis());
            ps.setString(4, alias);
            ps.setString(5, kind);
            ps.setString(6, targetId);
            return ps.executeUpdate();
        } catch (Exception e) {
            log.warn("failed to settle approvals for {}: {}", targetId, e.toString());
            return 0;
        }
    }

    // --------------------------------------------------------- 批次（approval_batch）

    /** 开一个草稿批次，返回批次号；0 表示落库失败。 */
    public long createBatch(String alias, String kind, String intent) {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO approval_batch (alias, kind, intent, status, recoverable, created_at)"
                             + " VALUES (?,?,?,'draft',1,?)")) {
            ps.setString(1, alias);
            ps.setString(2, kind);
            ps.setString(3, intent);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : 0L;
            }
        } catch (Exception e) {
            log.warn("failed to create approval batch for alias {}: {}", alias, e.toString());
            return 0L;
        }
    }

    public ApprovalBatchRow findBatch(long id) {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(BATCH_COLUMNS + " WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readBatch(rs) : null;
            }
        } catch (Exception e) {
            log.debug("failed to read batch {}", id, e);
            return null;
        }
    }

    /** Newest first；null 过滤条件忽略。 */
    public List<ApprovalBatchRow> listBatches(String alias, String status, int limit) {
        StringBuilder sql = new StringBuilder(BATCH_COLUMNS).append(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        appendFilter(sql, args, " AND alias = ?", alias);
        appendFilter(sql, args, " AND status = ?", status);
        sql.append(" ORDER BY id DESC LIMIT ?");
        args.add(Math.max(1, Math.min(limit, 500)));
        List<ApprovalBatchRow> rows = new ArrayList<>();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < args.size(); i++) ps.setObject(i + 1, args.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows.add(readBatch(rs));
            }
        } catch (Exception e) {
            log.debug("failed to list batches", e);
        }
        return rows;
    }

    /** 一批里的条目，按 seq 升序——SQL 批次的执行顺序就是它。 */
    public List<ApprovalRow> listBatchItems(long batchId) {
        List<ApprovalRow> rows = new ArrayList<>();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     APPROVAL_COLUMNS + " WHERE batch_id = ? ORDER BY seq, id")) {
            ps.setLong(1, batchId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows.add(readApproval(rs));
            }
        } catch (Exception e) {
            log.debug("failed to list items of batch {}", batchId, e);
        }
        return rows;
    }

    /** 这批的下一个序号。批次是一个进程串行往里加条目，不为它上锁。 */
    public int nextBatchSeq(long batchId) {
        return count("SELECT COALESCE(MAX(seq), 0) + 1 FROM approval_request WHERE batch_id = ?",
                List.of(batchId));
    }

    /**
     * 封口：草稿条目一起转 pending，批次转 pending。
     *
     * <p>{@code status} 写在 WHERE 里：submit 两次的第二次应该什么都不做，
     * 而不是把已经裁决过的条目打回队列。
     *
     * @return 转进队列的条目数；-1 表示批次状态不对（不是 draft）
     */
    public int submitBatch(long batchId, boolean recoverable) {
        try (Connection conn = connect()) {
            long now = System.currentTimeMillis();
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE approval_batch SET status = 'pending', submitted_at = ?, recoverable = ?"
                            + " WHERE id = ? AND status = 'draft'")) {
                ps.setLong(1, now);
                ps.setInt(2, recoverable ? 1 : 0);
                ps.setLong(3, batchId);
                if (ps.executeUpdate() == 0) return -1;
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE approval_request SET status = 'pending' WHERE batch_id = ? AND status = 'draft'")) {
                ps.setLong(1, batchId);
                return ps.executeUpdate();
            }
        } catch (Exception e) {
            log.warn("failed to submit batch {}: {}", batchId, e.toString());
            return -1;
        }
    }

    /** 裁决完之后记结果。{@code applied} / {@code partial} / {@code rejected} / {@code failed}。 */
    public boolean finishBatch(long batchId, String status, String decidedBy, String reason) {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE approval_batch SET status = ?, decided_at = ?, decided_by = ?, reason = ?"
                             + " WHERE id = ?")) {
            ps.setString(1, status);
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, decidedBy);
            ps.setString(4, reason);
            ps.setLong(5, batchId);
            return ps.executeUpdate() > 0;
        } catch (Exception e) {
            log.warn("failed to finish batch {}: {}", batchId, e.toString());
            return false;
        }
    }

    /** 丢弃一个还没提交的草稿批次连同它的条目。已提交的批次不动，返回 false。 */
    public boolean discardDraftBatch(long batchId) {
        try (Connection conn = connect()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM approval_batch WHERE id = ? AND status = 'draft'")) {
                ps.setLong(1, batchId);
                if (ps.executeUpdate() == 0) return false;
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM approval_request WHERE batch_id = ? AND status = 'draft'")) {
                ps.setLong(1, batchId);
                ps.executeUpdate();
            }
            return true;
        } catch (Exception e) {
            log.warn("failed to discard batch {}: {}", batchId, e.toString());
            return false;
        }
    }

    private static final String BATCH_COLUMNS =
            "SELECT id, alias, kind, intent, status, recoverable, created_at, submitted_at,"
                    + " decided_at, decided_by, reason FROM approval_batch";

    private static ApprovalBatchRow readBatch(ResultSet rs) throws SQLException {
        return new ApprovalBatchRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getInt(6) != 0, rs.getLong(7), nullableLong(rs, 8),
                nullableLong(rs, 9), rs.getString(10), rs.getString(11));
    }

    /** 批次里的一条 SQL 跑完之后，把它指向那条执行记录。 */
    public void attachExecution(long approvalId, long executionId) {
        if (approvalId <= 0 || executionId <= 0) return;
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE approval_request SET execution_id = ? WHERE id = ?")) {
            ps.setLong(1, executionId);
            ps.setLong(2, approvalId);
            ps.executeUpdate();
        } catch (Exception e) {
            log.debug("failed to attach execution {} to approval {}", executionId, approvalId, e);
        }
    }

    /** 这个对象上还挂着待审批吗。建审批前查一次，重复建的结果是同一件事排两遍队。 */
    public boolean hasPendingApproval(String alias, String kind, String targetId) {
        if (targetId == null) return false;
        return count("SELECT COUNT(*) FROM approval_request WHERE alias = ? AND kind = ?"
                        + " AND target_id = ? AND status = 'pending'",
                List.of(alias, kind, targetId)) > 0;
    }

    /** 这个对象有没有被人批准过（apply 或 publish 都算）：候选补定级的判据。 */
    public boolean hasApprovedApproval(String alias, String kind, String targetId) {
        if (targetId == null) return false;
        return count("SELECT COUNT(*) FROM approval_request WHERE alias = ? AND kind = ?"
                        + " AND target_id = ? AND status = 'approved'",
                List.of(alias, kind, targetId)) > 0;
    }

    /**
     * 最后一列是关联写任务的终态。相关子查询而不是 JOIN：WHERE 里的 alias / status / kind
     * 都是两张表同名的列，JOIN 进来每一处都要加前缀，而这个列表只多读一个标量。
     */
    private static final String APPROVAL_COLUMNS =
            "SELECT id, alias, kind, summary, detail, status, reason, created_at, decided_at, task_run_id,"
                    + " target_id, payload, batch_id, seq, session_id, agent_id, execution_id,"
                    + " (SELECT status FROM task_run WHERE task_run.id = approval_request.task_run_id)"
                    + " FROM approval_request";

    /** 一组 WHERE 条件：列表和 COUNT 必须用同一份，否则总页数和翻出来的内容对不上。 */
    private record Filter(String clause, List<Object> args) {
    }

    private int count(String sql, List<Object> args) {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.size(); i++) ps.setObject(i + 1, args.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (Exception e) {
            log.debug("count failed: {}", sql, e);
            return 0;
        }
    }

    private static ApprovalRow readApproval(ResultSet rs) throws SQLException {
        // wasNull() 说的是「上一次 get 读到的那一列」，所以 seq 必须先读完再构造，
        // 塞进参数列表里会被后面的 nullableLong(13) 抢走含义。
        int seqValue = rs.getInt(14);
        Integer seq = rs.wasNull() ? null : seqValue;
        return new ApprovalRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getLong(8), nullableLong(rs, 9),
                nullableLong(rs, 10), rs.getString(11), rs.getString(12), nullableLong(rs, 13),
                seq, rs.getString(15), rs.getString(16), nullableLong(rs, 17), rs.getString(18));
    }

    /** 一条写任务；不存在返回 null。 */
    public TaskRunRow findTaskRun(long id) {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, task_type, actor, alias, status, created_at, updated_at"
                             + " FROM task_run WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? new TaskRunRow(rs.getLong(1), rs.getString(2), rs.getString(3),
                                rs.getString(4), rs.getString(5), rs.getLong(6), rs.getLong(7))
                        : null;
            }
        } catch (Exception e) {
            log.debug("failed to read task run {}", id, e);
            return null;
        }
    }

    /**
     * 最近的写任务，最新在前。Agent 把命令丢后台之后靠它找回自己那条任务的 id
     * （`sql-cli task list`），再用 `task status <id>` 看跑到哪一步了。
     */
    public List<TaskRunRow> listTaskRuns(String alias, String status, int limit) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, task_type, actor, alias, status, created_at, updated_at FROM task_run WHERE 1=1");
        List<Object> args = new ArrayList<>();
        appendFilter(sql, args, " AND alias = ?", alias);
        appendFilter(sql, args, " AND status = ?", status);
        sql.append(" ORDER BY id DESC LIMIT ?");
        args.add(Math.max(1, Math.min(limit, 200)));
        List<TaskRunRow> rows = new ArrayList<>();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < args.size(); i++) ps.setObject(i + 1, args.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new TaskRunRow(rs.getLong(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getLong(6), rs.getLong(7)));
                }
            }
        } catch (Exception e) {
            log.debug("failed to list task runs for alias {}", alias, e);
        }
        return rows;
    }

    /** 一次任务的事件，按发生顺序（id 升序）。 */
    public List<TaskEventRow> listTaskEvents(long taskRunId) {
        List<TaskEventRow> rows = new ArrayList<>();
        if (taskRunId <= 0) return rows;
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, event_type, payload, created_at FROM task_event"
                             + " WHERE task_run_id = ? ORDER BY id ASC")) {
            ps.setLong(1, taskRunId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new TaskEventRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4)));
                }
            }
        } catch (Exception e) {
            log.debug("failed to list task events for run {}", taskRunId, e);
        }
        return rows;
    }

    public void recordGraphChange(String alias, String operation, String targetId, String actor,
                                  long revisionBefore, long revisionAfter) {
        recordGraphChange(alias, operation, targetId, actor, revisionBefore, revisionAfter,
                null, null, null);
    }

    /**
     * 一次图谱更新的可追溯记录。
     *
     * <p>原来这张表只记「谁在哪个 revision 改了哪个 id」，回头查「到底改成了什么」只能
     * 去翻 git——而图谱本体是 YAML 目录，多半也没进版本库。现在把 before/after 一起写进来，
     * 每条记录自己就能回答「改了什么」；开了图谱审批的话还带上放行它的审批 id。
     *
     * @param payload    {@code GraphChangePayload} 的 JSON；null 表示这次没采集（老记录）
     * @param approvalId 放行这次变更的审批；null = 没开图谱审批，直接生效
     * @param reason     变更原因，跟审批理由是两码事：这个是提交时写的
     */
    public void recordGraphChange(String alias, String operation, String targetId, String actor,
                                  long revisionBefore, long revisionAfter,
                                  String payload, Long approvalId, String reason) {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO graph_change_log (alias, operation, target_id, actor,"
                             + " revision_before, revision_after, created_at, payload, approval_id,"
                             + " reason, session_id, agent_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, alias);
            ps.setString(2, operation);
            ps.setString(3, targetId);
            ps.setString(4, actor);
            ps.setLong(5, revisionBefore);
            ps.setLong(6, revisionAfter);
            ps.setLong(7, System.currentTimeMillis());
            ps.setString(8, payload);
            setNullableLong(ps, 9, approvalId);
            ps.setString(10, reason);
            // 溯源：这次变更出自哪次会话、哪个 agent。原来只有 actor（agent/human/system
            // 三个值），答不出「哪一次会话」——于是「agent 读了什么 → 有没有写回」
            // 这条反馈链在两端之间断着，而那正是反哺图谱要回答的问题。
            ps.setString(11, com.sqlcli.session.SessionContext.sessionId());
            ps.setString(12, com.sqlcli.session.SessionContext.agentId());
            ps.executeUpdate();
        } catch (Exception e) {
            // A graph mutation that already committed must not be reported as failed.
            log.debug("failed to record graph change for alias {}", alias, e);
        }
    }

    private static final String GRAPH_CHANGE_COLUMNS =
            // 不取 session_id / agent_id：没有读者。它们是给「读了什么 → 有没有写回」
            // 那类分析查询用的（直接查库），UI 上没有展示位；取了不用只是白读两列。
            "SELECT id, alias, operation, target_id, actor, revision_before, revision_after,"
                    + " created_at, payload, approval_id, reason FROM graph_change_log";

    /** 图谱变更流水，最新在前。{@code alias} 为 null 时跨数据源。 */
    public List<GraphChangeRow> listGraphChanges(String alias, String targetId, int limit, int offset) {
        Filter filter = graphChangeFilter(alias, targetId);
        List<Object> args = new ArrayList<>(filter.args());
        args.add(Math.max(1, Math.min(limit, 500)));
        args.add(Math.max(0, offset));
        List<GraphChangeRow> rows = new ArrayList<>();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     GRAPH_CHANGE_COLUMNS + filter.clause() + " ORDER BY id DESC LIMIT ? OFFSET ?")) {
            for (int i = 0; i < args.size(); i++) ps.setObject(i + 1, args.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new GraphChangeRow(rs.getLong(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getLong(6), rs.getLong(7),
                            rs.getLong(8), rs.getString(9), nullableLong(rs, 10), rs.getString(11)));
                }
            }
        } catch (Exception e) {
            log.debug("failed to list graph changes for alias {}", alias, e);
        }
        return rows;
    }

    public int countGraphChanges(String alias, String targetId) {
        Filter filter = graphChangeFilter(alias, targetId);
        return count("SELECT COUNT(*) FROM graph_change_log" + filter.clause(), filter.args());
    }

    private static Filter graphChangeFilter(String alias, String targetId) {
        StringBuilder sql = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        appendFilter(sql, args, " AND alias = ?", alias);
        appendFilter(sql, args, " AND target_id = ?", targetId);
        return new Filter(sql.toString(), args);
    }

    // --------------------------------------------- 交互日志（agent_interaction）

    /**
     * 记一次 agent 与 sql-cli 的交互。**由 {@code SqlCli.run} 统一调用，一条命令一行**，
     * 包括失败的那些——失败才是反哺最值钱的信号，而运行库其余表按定义都装不下它们
     * （变更日志里没有「没发生的变更」）。
     *
     * <p>写入失败绝不能让命令失败——日志是附带品，它挂了不该影响 agent 干活，
     * 所以这里吞掉全部异常，只留 debug 日志（跟 {@link #recordExecution} 同一个理由）。
     *
     * @param action  命令名，如 {@code schema search} / {@code schema edit} / {@code query}
     * @param status  {@code ok} / {@code failed}；退出码非 0 即 failed
     * @param detail  读类命令的命中明细，由 {@link InteractionDetail} 从命令里带上来，可为 null
     */
    public void recordInteraction(String alias, String action, String status, int exitCode,
            String errorSummary, Long elapsedMs, InteractionDetail detail) {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO agent_interaction (alias, action, query, hit_count, top_score,"
                             + " target_ids, status, exit_code, error_summary, elapsed_ms,"
                             + " session_id, agent_id, created_at)"
                             + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            List<String> targetIds = detail == null ? null : detail.targetIds();
            ps.setString(1, alias);
            ps.setString(2, action);
            ps.setString(3, detail == null ? null : detail.query());
            setNullableInt(ps, 4, detail == null ? null : detail.hitCount());
            if (detail == null || detail.topScore() == null) ps.setNull(5, java.sql.Types.REAL);
            else ps.setDouble(5, detail.topScore());
            ps.setString(6, targetIds == null || targetIds.isEmpty() ? null : toJsonArray(targetIds));
            ps.setString(7, status);
            ps.setInt(8, exitCode);
            // 错误摘要截断：一条 SQL 报错可能带回整段堆栈，而这里要的是「错在哪一类」，
            // 完整原文在 sql_execution.error_summary 里
            ps.setString(9, errorSummary == null ? null
                    : errorSummary.substring(0, Math.min(500, errorSummary.length())));
            if (elapsedMs == null) ps.setNull(10, java.sql.Types.INTEGER);
            else ps.setLong(10, elapsedMs);
            ps.setString(11, com.sqlcli.session.SessionContext.sessionId());
            ps.setString(12, com.sqlcli.session.SessionContext.agentId());
            ps.setLong(13, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (Exception e) {
            log.debug("failed to record interaction for alias {}", alias, e);
        }
    }

    /** 一次失败的交互：命令、当时的检索词/对象名、错误摘要。 */
    public record FailedInteraction(String action, String query, String errorSummary,
                                    long createdAt) {
    }

    /**
     * 某个别名在 {@code since} 之后的失败交互，最近的在前。
     *
     * <p><b>只看窗口内的</b>：全历史累计的话，修好的问题会一直重复出现在评估报告里，
     * 最后变成第二个 {@code policy}（1487 条违规、豁免 0 条、十天没人再跑）。
     * 窗口由调用方给——通常是「上一次评估之后」。
     */
    public List<FailedInteraction> failedInteractionsSince(String alias, long since, int limit) {
        List<FailedInteraction> out = new ArrayList<>();
        String sql = "SELECT action, query, error_summary, created_at FROM agent_interaction"
                + " WHERE alias = ? AND status <> 'ok' AND error_summary IS NOT NULL"
                + " AND created_at >= ? ORDER BY created_at DESC LIMIT ?";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, alias);
            ps.setLong(2, since);
            ps.setInt(3, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new FailedInteraction(rs.getString(1), rs.getString(2),
                            rs.getString(3), rs.getLong(4)));
                }
            }
        } catch (Exception e) {
            log.debug("failed to list failed interactions for alias {}", alias, e);
        }
        return out;
    }

    /** 上一次图谱评估的开始时间；没跑过返回 0。交互探针用它当窗口起点。 */
    public long lastEvaluationAt(String alias, String source) {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT MAX(started_at) FROM graph_evaluation WHERE alias = ? AND source = ?")) {
            ps.setString(1, alias);
            ps.setString(2, source);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (Exception e) {
            log.debug("failed to read last evaluation time for alias {}", alias, e);
            return 0L;
        }
    }

    private static void setNullableInt(PreparedStatement ps, int index, Integer value)
            throws SQLException {
        if (value == null) ps.setNull(index, java.sql.Types.INTEGER);
        else ps.setInt(index, value);
    }

    private static String toJsonArray(List<String> values) {
        try {
            return new ObjectMapper().writeValueAsString(values);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 某个具体对象（表、列……）在 {@code agent_interaction} 里被命中过多少次。
     * {@code action} 为 null 时不筛动作类型；{@code describe} 完整度那一行用它报「被搜到 N 次」。
     *
     * <p>先用 LIKE 粗筛缩小范围（target_ids 是 JSON 文本，SQLite 没有 json1 也能跑），
     * 再在 Java 侧解析确认精确相等——避免 {@code table:a:app.order} 误命中
     * {@code table:a:app.orders} 这种子串重叠。
     */
    public int countTargetHits(String alias, String action, String targetId) {
        if (targetId == null || targetId.isBlank()) return 0;
        StringBuilder sql = new StringBuilder(
                "SELECT target_ids FROM agent_interaction WHERE alias = ? AND target_ids LIKE ?");
        if (action != null) sql.append(" AND action = ?");
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            ps.setString(1, alias);
            ps.setString(2, "%" + targetId + "%");
            if (action != null) ps.setString(3, action);
            int count = 0;
            ObjectMapper mapper = new ObjectMapper();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String json = rs.getString(1);
                    if (json == null) continue;
                    try {
                        for (JsonNode node : mapper.readTree(json)) {
                            if (targetId.equals(node.asText())) {
                                count++;
                                break;
                            }
                        }
                    } catch (Exception malformed) {
                        // 一行坏数据不该让整个统计失败
                    }
                }
            }
            return count;
        } catch (Exception e) {
            log.debug("failed to count graph read hits for {}", targetId, e);
            return 0;
        }
    }

    /**
     * 每个对象 id 在 {@code agent_interaction} 里出现在 target_ids 的总次数，供 {@code schema gaps}
     * 排序用。{@code action} 为 null 时不筛动作类型。
     *
     * <p>在另一个 agent 接上 {@code search} 的埋点之前，这里只有 describe/path 的数据——
     * 已知且已接受，不是 bug。
     */
    public Map<String, Integer> targetHitCounts(String alias, String action) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        String sql = "SELECT target_ids FROM agent_interaction WHERE alias = ? AND target_ids IS NOT NULL"
                + (action == null ? "" : " AND action = ?");
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, alias);
            if (action != null) ps.setString(2, action);
            ObjectMapper mapper = new ObjectMapper();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String json = rs.getString(1);
                    if (json == null || json.isBlank()) continue;
                    try {
                        for (JsonNode node : mapper.readTree(json)) {
                            counts.merge(node.asText(), 1, Integer::sum);
                        }
                    } catch (Exception malformed) {
                        // 一行坏数据不该让整个聚合失败
                    }
                }
            }
        } catch (Exception e) {
            log.debug("failed to aggregate graph read target hits for alias {}", alias, e);
        }
        return counts;
    }

    /** 一条搜空的查询词：出现次数、最后一次出现的时间。 */
    public record MissedQuery(String query, int count, long lastSeenAt) {
    }

    /**
     * 搜空的查询词（{@code hit_count = 0} 的 search），按出现次数降序——这是最准的
     * 补图谱优先级信号，不是猜的：搜的人真的要这个词，图谱里就是没有能匹配上的对象。
     */
    public List<MissedQuery> missedSearchQueries(String alias, int limit) {
        List<MissedQuery> out = new ArrayList<>();
        String sql = "SELECT query, COUNT(*), MAX(created_at) FROM agent_interaction"
                + " WHERE alias = ? AND action = ? AND hit_count = 0 AND query IS NOT NULL"
                + " GROUP BY query ORDER BY 2 DESC, 3 DESC LIMIT ?";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, alias);
            ps.setString(2, InteractionDetail.schemaAction("search"));
            ps.setInt(3, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new MissedQuery(rs.getString(1), rs.getInt(2), rs.getLong(3)));
                }
            }
        } catch (Exception e) {
            log.debug("failed to list missed search queries for alias {}", alias, e);
        }
        return out;
    }

    // ---------------------------------------------------------------- reads

    /** Newest first. Null filters are ignored. */
    public List<SqlExecutionRow> listExecutions(String alias, String sqlType, String status,
                                                Long startedAfter, int limit, int offset) {
        return listExecutions(alias, sqlType, status, startedAfter, null, null, limit, offset);
    }

    /**
     * Newest first. Null filters are ignored.
     *
     * @param startedBefore 时间范围的右端（不含），配合 {@code startedAfter} 用
     * @param targetSchema  见 {@link #targetSchema}；没标注 schema 的历史不会命中任何值
     */
    public List<SqlExecutionRow> listExecutions(String alias, String sqlType, String status,
                                                Long startedAfter, Long startedBefore, String targetSchema,
                                                int limit, int offset) {
        Filter filter = executionFilter(alias, sqlType, status, startedAfter, startedBefore, targetSchema);
        StringBuilder sql = new StringBuilder(
                "SELECT id, alias, sql_type, masked_sql, raw_sql, status, error_summary, affected_rows,"
                        + " elapsed_ms, started_at, source, rerun_of, target_schema FROM sql_execution")
                .append(filter.clause());
        List<Object> args = new ArrayList<>(filter.args());
        sql.append(" ORDER BY started_at DESC, id DESC LIMIT ? OFFSET ?");
        args.add(Math.max(1, Math.min(limit, 500)));
        args.add(Math.max(0, offset));

        List<SqlExecutionRow> rows = new ArrayList<>();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < args.size(); i++) ps.setObject(i + 1, args.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new SqlExecutionRow(rs.getLong(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                            nullableLong(rs, 8), rs.getLong(9), rs.getLong(10), rs.getString(11),
                            nullableLong(rs, 12), rs.getString(13)));
                }
            }
        } catch (Exception e) {
            log.debug("failed to list executions for alias {}", alias, e);
        }
        return rows;
    }

    /** 同 {@link #listExecutions} 条件下的总条数，分页器算总页数用。 */
    public int countExecutions(String alias, String sqlType, String status,
                               Long startedAfter, Long startedBefore, String targetSchema) {
        Filter filter = executionFilter(alias, sqlType, status, startedAfter, startedBefore, targetSchema);
        return count("SELECT COUNT(*) FROM sql_execution" + filter.clause(), filter.args());
    }

    private static Filter executionFilter(String alias, String sqlType, String status,
                                          Long startedAfter, Long startedBefore, String targetSchema) {
        StringBuilder sql = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        appendFilter(sql, args, " AND alias = ?", alias);
        appendFilter(sql, args, " AND sql_type = ?", sqlType == null ? null : sqlType.toUpperCase(Locale.ROOT));
        appendFilter(sql, args, " AND status = ?", status);
        appendFilter(sql, args, " AND started_at >= ?", startedAfter);
        appendFilter(sql, args, " AND started_at < ?", startedBefore);
        appendFilter(sql, args, " AND target_schema = ?",
                targetSchema == null ? null : targetSchema.toLowerCase(Locale.ROOT));
        return new Filter(sql.toString(), args);
    }

    /** 历史里出现过的 schema，给筛选器当选项用。 */
    public List<String> listExecutionSchemas(String alias) {
        List<String> out = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "SELECT DISTINCT target_schema FROM sql_execution WHERE target_schema IS NOT NULL");
        List<Object> args = new ArrayList<>();
        appendFilter(sql, args, " AND alias = ?", alias);
        sql.append(" ORDER BY 1");
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < args.size(); i++) ps.setObject(i + 1, args.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
        } catch (Exception e) {
            log.debug("failed to list execution schemas for alias {}", alias, e);
        }
        return out;
    }

    /**
     * 供 describe 带出「这张表最近被怎么查过」：成功执行、按 masked_sql 去重、最新优先。
     * LIKE 只做粗筛，词边界由 Java 侧正则保证（order 不会命中 order_item 的记录）。
     */
    public List<String> listRecentSqlForTable(String alias, String table, int limit) {
        String sql = "SELECT masked_sql, MAX(started_at) FROM sql_execution"
                + " WHERE alias = ? AND status = 'success' AND masked_sql LIKE ?"
                + " GROUP BY masked_sql ORDER BY MAX(started_at) DESC LIMIT 50";
        java.util.regex.Pattern boundary = java.util.regex.Pattern.compile(
                "(?i)(?<![A-Za-z0-9_])" + java.util.regex.Pattern.quote(table) + "(?![A-Za-z0-9_])");
        List<String> out = new ArrayList<>();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, alias);
            ps.setString(2, "%" + table + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next() && out.size() < limit) {
                    String masked = rs.getString(1);
                    if (masked != null && boundary.matcher(masked).find()) {
                        out.add(masked);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("failed to list recent sql for table {}", table, e);
        }
        return out;
    }

    /** SQL 里第一个 {@code schema.table} 的 schema；没有就用 {@code fallback}，都没有返回 null。 */
    public static String targetSchema(String sql, String fallback) {
        if (sql != null) {
            java.util.regex.Matcher matcher = SCHEMA_QUALIFIED.matcher(sql);
            if (matcher.find()) {
                return matcher.group(1).toLowerCase(Locale.ROOT);
            }
        }
        return fallback == null || fallback.isBlank() ? null : fallback.toLowerCase(Locale.ROOT);
    }

    /** Replaces values inside quoted literals and collapses whitespace. */
    public static String maskSql(String sql) {
        if (sql == null) return "";
        return sql.replaceAll("'([^']|'')*'", "'<redacted>'").replaceAll("\\s+", " ").trim();
    }

    // ---------------------------------------------------------------- schema

    private Connection connect() throws SQLException, IOException {
        ensureSchema();
        return open();
    }

    private Connection open() throws SQLException {
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA busy_timeout=" + BUSY_TIMEOUT_MS);
        }
        return conn;
    }

    /**
     * synchronized 只挡住同一个实例——CLI 与 UI 是两个进程，测试里也常见每个线程各建一个
     * store，所以建表/加列可能被并发执行。DDL 撞上另一个 DDL 时 SQLite 会报锁冲突，
     * 而调用方（审计写入）会把异常吞掉，表现成"这条运行记录莫名其妙没了"。
     * 重试一次就够：对方那次迁移做完之后，这次进去只是确认 user_version。
     */
    private synchronized void ensureSchema() throws SQLException, IOException {
        if (ready) return;
        Path parent = dbPath.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        try {
            runMigration();
        } catch (SQLException first) {
            log.debug("schema migration failed, retrying once", first);
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw first;
            }
            runMigration();
        }
        ready = true;
    }

    private void runMigration() throws SQLException {
        try (Connection conn = open()) {
            enableWal(conn);
            migrate(conn);
        }
    }

    /**
     * Journal mode is a persistent property, so the first opener wins and later ones only confirm it.
     * Switching it needs an exclusive lock and returns SQLITE_BUSY <em>without</em> honouring
     * {@code busy_timeout} while another connection is attached, which must not abort the caller's
     * write: whoever gets there next sets it.
     */
    private void enableWal(Connection conn) {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA journal_mode=WAL")) {
            if (rs.next() && !"wal".equalsIgnoreCase(rs.getString(1))) {
                log.debug("run state database is in {} mode, not WAL", rs.getString(1));
            }
        } catch (SQLException e) {
            log.debug("could not switch run state database to WAL", e);
        }
    }

    private void migrate(Connection conn) throws SQLException {
        if (userVersion(conn) < 1) {
            try (Statement st = conn.createStatement()) {
                for (String ddl : DDL) st.execute(ddl);
                st.execute("PRAGMA user_version=1");
            }
        }
        if (userVersion(conn) < LEGACY_IMPORT_VERSION) importLegacyHistory(conn);
        // 每条 DDL 都是 IF NOT EXISTS，整组重跑就等于"补上新加的表"，
        // 不用为每个版本手写一段 ALTER。新加的**列** CREATE TABLE 补不了，
        // 走 ADDED_COLUMNS 的 ALTER；两者都幂等，重跑一遍不会丢数据。
        if (userVersion(conn) < SCHEMA_VERSION) {
            try (Statement st = conn.createStatement()) {
                for (String ddl : DDL) st.execute(ddl);
            }
            for (String[] column : ADDED_COLUMNS) {
                addColumnIfMissing(conn, column[0], column[1], column[2]);
            }
            try (Statement st = conn.createStatement()) {
                for (String ddl : POST_MIGRATION_DDL) st.execute(ddl);
            }
            renameGraphReadToInteraction(conn);
            backfillRecoveryScripts(conn);
            // 回填之后 file_path / checksum 就再没有读者了。留着它们的代价不是磁盘，
            // 是「NOT NULL 列里装着空串」会让人以为脚本还存在文件里——这个误导真实发生过。
            dropColumnIfPresent(conn, "recovery_artifact", "file_path");
            dropColumnIfPresent(conn, "recovery_artifact", "checksum");
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA user_version=" + SCHEMA_VERSION);
            }
        }
    }

    /**
     * v12 一次性搬迁：{@code graph_read} → {@code agent_interaction}。
     *
     * <p>改名不是洁癖：这张表从 v12 起要记写操作和失败，继续叫「read」就是在骗下一个读代码的人。
     * 表建于 v11、只活了两天，搬迁成本接近零，越晚改越贵。
     *
     * <p>只搬不删数据：老表的六列原样进新表，新列留 null——历史行本来就没有成败信息，
     * 硬填一个 {@code ok} 会让「v12 之前没有失败记录」这个事实消失。
     */
    private void renameGraphReadToInteraction(Connection conn) throws SQLException {
        if (!tableExists(conn, "graph_read")) return;
        try (Statement st = conn.createStatement()) {
            st.execute("INSERT INTO agent_interaction"
                    + " (alias, action, query, hit_count, top_score, target_ids,"
                    + "  session_id, agent_id, created_at)"
                    + " SELECT alias, action, query, hit_count, top_score, target_ids,"
                    + "  session_id, agent_id, created_at FROM graph_read");
            st.execute("DROP TABLE graph_read");
            log.info("交互日志已从 graph_read 搬到 agent_interaction");
        }
    }

    private static boolean tableExists(Connection conn, String table) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * 把升级前留在 {@code ~/.sql-cli/recovery/*.sql} 里的回滚脚本读进库，走完那次没做完的迁移。
     *
     * <p>回滚脚本改存 SQLite 时只切换了写路径，老记录仍然只有一个文件路径——
     * 于是「文件必须永远留着」和「兼容读分支必须永远留着」这两笔债一直挂着。
     * 这里一次性把内容搬进来，之后文件和分支都可以删。
     *
     * <p>文件读不到就只把状态标成 {@code missing}：那条记录本来就已经不可用了，
     * 记下这个事实比留一个指向空气的路径有用。
     */
    private void backfillRecoveryScripts(Connection conn) throws SQLException {
        if (!hasColumn(conn, "recovery_artifact", "file_path")) return;
        List<Long> ids = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, file_path FROM recovery_artifact"
                     + " WHERE (rollback_sql IS NULL OR rollback_sql = '')"
                     + " AND file_path IS NOT NULL AND file_path <> ''")) {
            while (rs.next()) {
                ids.add(rs.getLong(1));
                paths.add(rs.getString(2));
            }
        }
        if (ids.isEmpty()) return;
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE recovery_artifact SET rollback_sql = ?, backup_text = ?, status = ? WHERE id = ?")) {
            for (int i = 0; i < ids.size(); i++) {
                String[] parsed = parseLegacyRecoveryFile(paths.get(i));
                ps.setString(1, parsed[0]);
                ps.setString(2, parsed[1]);
                ps.setString(3, parsed[0] == null ? "missing" : "saved");
                ps.setLong(4, ids.get(i));
                ps.addBatch();
            }
            ps.executeBatch();
        }
        log.info("回滚脚本回填完成：{} 条老记录已从文件搬进运行库", ids.size());
    }

    /**
     * 老恢复文件切成 {@code [rollbackSql, backupText]}；读不到返回两个 null。
     * 段落标记与原来 {@code RecoveryExecutor} 的兼容读路径一致。
     */
    private static String[] parseLegacyRecoveryFile(String filePath) {
        try {
            Path file = Path.of(filePath);
            if (!Files.isReadable(file)) return new String[]{null, null};
            String content = Files.readString(file);
            int rollbackAt = content.indexOf("-- Recovery SQL:");
            if (rollbackAt < 0) return new String[]{null, null};
            String rollback = content.substring(rollbackAt + "-- Recovery SQL:".length()).trim();
            String backup = null;
            int backupAt = content.indexOf("-- Original Data:");
            if (backupAt >= 0 && backupAt < rollbackAt) {
                String block = content.substring(backupAt + "-- Original Data:".length(), rollbackAt);
                StringBuilder sb = new StringBuilder();
                for (String line : block.lines().toList()) {
                    String text = line.startsWith("-- ") ? line.substring(3) : line;
                    if (!text.isBlank()) sb.append(text).append(System.lineSeparator());
                }
                backup = sb.toString().trim();
                if (backup.isEmpty()) backup = null;
            }
            return new String[]{rollback.isEmpty() ? null : rollback, backup};
        } catch (Exception e) {
            log.debug("failed to read legacy recovery file {}", filePath, e);
            return new String[]{null, null};
        }
    }

    private boolean hasColumn(Connection conn, String table, String column) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) return true;
            }
        }
        return false;
    }

    /** SQLite 3.35+ 支持 DROP COLUMN；不支持就留着，多两个没人读的列不影响正确性。 */
    private void dropColumnIfPresent(Connection conn, String table, String column) throws SQLException {
        if (!hasColumn(conn, table, column)) return;
        try (Statement st = conn.createStatement()) {
            st.execute("ALTER TABLE " + table + " DROP COLUMN " + column);
        } catch (SQLException e) {
            // 不能吞：file_path 是 NOT NULL 且无默认值，删不掉的话后续 INSERT 会全部失败，
            // 而「存不下回滚脚本」会让每一次写操作都被中止。响一次好过静默瘫痪。
            throw new SQLException("无法删除 " + table + "." + column
                    + "（SQLite 3.35+ 才支持 DROP COLUMN）：" + e.getMessage(), e);
        }
    }

    private void addColumnIfMissing(Connection conn, String table, String column, String type)
            throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) return;
            }
        }
        try (Statement st = conn.createStatement()) {
            st.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
        } catch (SQLException e) {
            // 两个进程/线程同时迁移时，PRAGMA 检查和 ALTER 之间会插进另一次 ALTER；
            // 对方已经加好了列就等于成功，其他错误照抛。
            if (!String.valueOf(e.getMessage()).contains("duplicate column")) throw e;
        }
    }

    private int userVersion(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA user_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /**
     * One-shot import of the old {@code execution-history/*.jsonl} files. The marker is
     * {@code PRAGMA user_version} itself, bumped inside the same transaction as the inserts, so a
     * crash or a second process re-runs the whole import or none of it. Old files are kept.
     */
    private void importLegacyHistory(Connection conn) throws SQLException {
        conn.setAutoCommit(false);
        try {
            if (userVersion(conn) >= LEGACY_IMPORT_VERSION) return; // another process won the race
            if (Files.isDirectory(legacyHistoryDir)) {
                try (Stream<Path> files = Files.list(legacyHistoryDir);
                     PreparedStatement ps = conn.prepareStatement(
                             "INSERT INTO sql_execution (alias, sql_type, masked_sql, raw_sql, status,"
                                     + " elapsed_ms, started_at, source) VALUES (?,?,?,NULL,'success',?,?,'migrated')")) {
                    ObjectMapper mapper = new ObjectMapper();
                    for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".jsonl")).toList()) {
                        importLegacyFile(mapper, ps, file);
                    }
                }
            }
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA user_version=" + LEGACY_IMPORT_VERSION);
            }
            conn.commit();
        } catch (SQLException | IOException | RuntimeException e) {
            conn.rollback();
            log.warn("JSONL history import failed, will retry on next start: {}", e.toString());
        } finally {
            conn.setAutoCommit(true);
        }
    }

    private void importLegacyFile(ObjectMapper mapper, PreparedStatement ps, Path file)
            throws IOException, SQLException {
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            JsonNode node;
            try {
                node = mapper.readTree(line);
            } catch (IOException damaged) {
                continue; // one bad line must not abort the import
            }
            String masked = node.path("sql").asText("");
            ps.setString(1, node.path("alias").asText(""));
            ps.setString(2, firstWord(masked));
            ps.setString(3, masked);
            ps.setLong(4, node.path("elapsedMs").asLong(0));
            ps.setLong(5, node.path("startedAt").asLong(0));
            ps.addBatch();
        }
        ps.executeBatch();
    }

    private static String firstWord(String sql) {
        String trimmed = sql.trim();
        if (trimmed.isEmpty()) return null;
        int end = trimmed.indexOf(' ');
        return (end < 0 ? trimmed : trimmed.substring(0, end)).toUpperCase(Locale.ROOT);
    }

    private static void appendFilter(StringBuilder sql, List<Object> args, String clause, Object value) {
        if (value == null || (value instanceof String s && s.isBlank())) return;
        sql.append(clause);
        args.add(value);
    }

    private static void setNullableLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) ps.setNull(index, java.sql.Types.INTEGER);
        else ps.setLong(index, value);
    }

    private static Long nullableLong(ResultSet rs, int index) throws SQLException {
        long value = rs.getLong(index);
        return rs.wasNull() ? null : value;
    }
}
