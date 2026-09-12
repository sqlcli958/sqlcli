package com.sqlcli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlcli.cli.AliasActionCommand;
import com.sqlcli.cli.ApprovalCommand;
import com.sqlcli.cli.AliasCommand;
import com.sqlcli.cli.CryptoCommand;
import com.sqlcli.cli.DriverCommand;
import com.sqlcli.cli.ListCommand;
import com.sqlcli.cli.BatchCommand;
import com.sqlcli.cli.SchemaActionCommand;
import com.sqlcli.cli.SessionCommand;
import com.sqlcli.cli.UiCommand;
import com.sqlcli.config.AliasResolver;
import com.sqlcli.config.DatabaseConfig;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import com.sqlcli.config.JdbcUrlParser;
import com.sqlcli.runstate.InteractionDetail;
import com.sqlcli.runstate.RunStateStore;
import com.sqlcli.connection.QueryExecutionOptions;
import com.sqlcli.connection.QueryResultRenderer;
import com.sqlcli.task.SqlTaskModule;
import com.sqlcli.task.SqlTaskRequest;
import com.sqlcli.task.SqlTaskResult;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;


import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * SQL CLI 主入口
 *
 * 命令结构：
 *   sql-cli <alias> "SQL"              # 快速查询
 *   sql-cli <alias> --file <path>      # 执行SQL文件
 *   sql-cli <alias> test               # 测试连接
 *   sql-cli <alias> info               # 获取数据库运行时详细信息
 *   sql-cli <alias> ddl <table>        # 获取DDL
 *   sql-cli <alias> tables             # 列出表
 *   sql-cli <alias> secret set/status  # 密码管理
 *   sql-cli list                       # 列出别名
 *   sql-cli alias add/update/remove    # 别名管理
 *   sql-cli driver add/update/remove   # 驱动管理
 *   sql-cli crypto                     # 加密工具
 */
@Command(name = "sql-cli",
         mixinStandardHelpOptions = true,
         versionProvider = SqlCli.VersionProvider.class,
         description = "A lightweight multi-database query CLI tool",
         subcommands = {
                 ListCommand.class,
                 AliasCommand.class,
                 DriverCommand.class,
                 CryptoCommand.class,
                 UiCommand.class,
                 SessionCommand.class
         })
public class SqlCli {
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    // 系统命令列表
    private static final Set<String> SYSTEM_COMMANDS = Set.of(
            "list", "alias", "driver", "crypto", "ui", "session", "--help", "-h", "--version"
    );

    // Alias 操作列表
    private static final Set<String> ALIAS_ACTIONS = Set.of(
            "test", "info", "ddl", "tables", "secret"
    );

    private static final Set<String> SCHEMA_ACTIONS = Set.of(
            "list", "describe", "query", "path", "search", "export", "import", "edit",
            "stats", "validate", "eval", "gaps", "add-term", "add-relation", "lineage", "add-lineage", "remove-lineage",
            "add-metric", "metrics", "metric", "expand-metric",
            "search-eval", "task-eval", "value-domain", "data-quality", "index", "diagram", "policy",
            "design", "migration"
    );

    public static void main(String[] args) {
        System.exit(run(args));
    }

    /**
     * Execute the CLI without terminating the JVM. This is the single entry point used by
     * {@link #main(String[])} and by command contract tests.
     */
    /**
     * 所有命令的唯一入口，也是**交互日志唯一的埋点处**。
     *
     * <h2>为什么埋在这里而不是各个命令里</h2>
     * 只有这里同时知道「敲的是什么命令」和「最后成没成」。在 28 个 schema action
     * 里各埋一次的结果是**漏掉那些根本走不到 handler 的失败**：参数不合法、
     * 别名不存在、`describe` 找不到表（它在解析出表之前就 return 了）——
     * 而这些恰恰是「图谱不够用 / agent 心智模型跟规则对不上」最直接的证据。
     * 实测运行库里 `graph_change_log` 一万条全是成功操作，它是变更日志，
     * 按定义装不下失败；失败在 v12 之前没有任何落点。
     *
     * <p>命中明细（搜到几条、命中哪些对象）入口看不见，由命令通过
     * {@link InteractionDetail} 带上来，在这里拼成同一行——**不是写两行**，
     * 否则「交互了几次」这个最基本的问题都要先去重。
     */
    public static int run(String... args) {
        String[] safeArgs = args == null ? new String[0] : args;
        long startedAt = System.currentTimeMillis();
        int exitCode = 1;
        String error = null;
        PrintStream originalErr = System.err;
        // 用平台默认编码而不是写死 UTF-8：这里替换的是 System.err，写死会改掉控制台
        // 的输出编码（GBK 终端上中文会花屏）。捕获侧用同一个 charset 解码即可。
        Charset errCharset = Charset.defaultCharset();
        StderrTail tail = new StderrTail(originalErr, errCharset);
        System.setErr(new PrintStream(tail, true, errCharset));
        try {
            exitCode = runInternal(safeArgs);
            return exitCode;
        } catch (IllegalArgumentException e) {
            error = e.getMessage();
            System.err.println("Error: " + JdbcUrlParser.redactSecrets(error));
            exitCode = 2;
            return exitCode;
        } catch (Exception e) {
            error = e.getMessage();
            System.err.println("Error: " + JdbcUrlParser.redactSecrets(error));
            exitCode = 1;
            return exitCode;
        } finally {
            System.setErr(originalErr);
            // 命令**返回**非零（而不是抛异常）时上面两个 catch 都没走到，error 是 null——
            // 而图谱类命令的失败绝大多数是 return：`Table not found`、`拒绝写入空壳术语`、
            // 参数不合法……最值钱的字段在最该有值的场景下反而空着。所以退回去取 stderr 的尾巴。
            if (error == null && exitCode != 0) error = tail.lastLine();
            // **退出码非 0 但一个字都没往 stderr 写 = 这个退出码是结论，不是失败。**
            // schema eval 有硬错误就退 1、policy 有违规就退 1——那是它们的报告方式。
            // 记成 failed 的话「失败了几次」会被自己的评估刷爆，而真正的失败被淹掉。
            String status = exitCode == 0 || error == null ? "ok" : "failed";
            recordInteraction(safeArgs, status, exitCode, error,
                    System.currentTimeMillis() - startedAt);
        }
    }

    /**
     * 转发 stderr 并留下最后一行，给交互日志当错误摘要。
     *
     * <h2>为什么接 stderr 而不是在每个失败点埋一次</h2>
     * 失败信息本来就只去 stderr 一个地方。在 {@code usageError} / {@code commandFailure} /
     * 各种 {@code System.err.println} 上逐个补调用，今天补得全，明天新加的失败路径
     * 又漏一条——而漏掉是静默的（那一行的 error_summary 是空的，没人会发现）。
     * 接在这里是**结构上不可能漏**。
     *
     * <p>只留最后一行、且只留 {@link #LIMIT} 个字符：`sql-cli <alias> ui` 会在这个
     * 进程里一挂几小时，无界缓冲就是内存泄漏。错误摘要要的也就是最后那句话，
     * 完整原文该去 {@code sql_execution.error_summary} 或日志里找。
     */
    private static final class StderrTail extends OutputStream {
        private static final int LIMIT = 500;

        private final PrintStream delegate;
        private final Charset charset;
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();
        private String lastLine;

        StderrTail(PrintStream delegate, Charset charset) {
            this.delegate = delegate;
            this.charset = charset;
        }

        @Override
        public void write(int b) {
            delegate.write(b);
            if (b == '\n') {
                // 换行即定稿：留住这一行，为下一行清空。空行不算（很多命令用它排版）
                String text = decode();
                if (!text.isEmpty()) lastLine = text;
                line.reset();
                return;
            }
            // **按字节缓冲，最后统一解码**：逐字节 (char) b 会把 UTF-8 的多字节汉字
            // 拆成一个个乱码字符——这个 bug 真写出来过，第一版存进去的中文报错全是乱码。
            if (line.size() < LIMIT) line.write(b);
        }

        @Override
        public void flush() {
            delegate.flush();
        }

        private String decode() {
            // 截断可能切在半个汉字上，解码器会把残字节替换成 U+FFFD，不抛异常
            return new String(line.toByteArray(), charset).trim();
        }

        /** 最后一行非空 stderr；没有输出过就返回 null。未换行的残留也算。 */
        String lastLine() {
            String pending = decode();
            if (!pending.isEmpty()) return pending;
            return lastLine == null || lastLine.isEmpty() ? null : lastLine;
        }
    }

    private static void recordInteraction(String[] args, String status, int exitCode,
            String error, long elapsed) {
        try {
            new RunStateStore().recordInteraction(
                    aliasOf(args), actionOf(args), status, exitCode,
                    error == null ? null : JdbcUrlParser.redactSecrets(error),
                    elapsed, InteractionDetail.take());
        } catch (Exception ignored) {
            // 见上：日志失败不能影响命令
        }
    }

    /** 第一个参数是 alias，除非它是系统命令（`list` / `alias` / `driver` …）。 */
    private static String aliasOf(String[] args) {
        if (args.length == 0) return null;
        String first = args[0];
        if (isSystemCommand(first) || "task".equalsIgnoreCase(first)
                || "help".equalsIgnoreCase(first) || "schema".equalsIgnoreCase(first)) {
            return null;
        }
        return first;
    }

    /**
     * 命令名，别名替换成占位符——**按 action 聚合是这张表的主要用法**
     * （「schema search 失败了几次」），把别名混进去会让同一个命令散成 N 行。
     *
     * <p>裸 SQL 归成 {@code query} 一类：SQL 原文在 {@code sql_execution} 里，
     * 这里再存一份既重复又会把长语句灌进日志。
     */
    private static String actionOf(String[] args) {
        if (args.length == 0) return "(empty)";
        if (aliasOf(args) == null) {
            return args.length > 1 && !args[1].startsWith("-")
                    ? args[0] + " " + args[1] : args[0];
        }
        if (args.length == 1) return "(alias only)";
        String second = args[1];
        if ("schema".equalsIgnoreCase(second)) {
            return args.length > 2 && !args[2].startsWith("-")
                    ? InteractionDetail.schemaAction(args[2]) : "schema";
        }
        // ddl / tables / test / secret / batch / approval / ui 这些二级动作原样留着；
        // 其余（第二个参数是 SQL 本身或 --file）都归成 query
        return second.startsWith("-") || second.contains(" ") ? "query" : second;
    }

    private static int runInternal(String[] args) {
        // 设置 debug
        for (String arg : args) {
            if ("-d".equals(arg) || "--debug".equals(arg)) {
                System.setProperty("SQLCLI_DEBUG", "DEBUG");
                break;
            }
        }
        validateGlobalFlagPosition(args);
        args = consumeProvenanceFlags(args);

        // 检查是否是系统命令
        if (args.length == 0) {
            CommandLine.usage(new SqlCli(), System.out);
            return 0;
        }
        if ("help".equalsIgnoreCase(args[0]) || "schema".equalsIgnoreCase(args[0])) {
            throw new IllegalArgumentException("Unknown command: " + args[0]
                    + ". Use '<command> --help' and place schema after an alias.");
        }
        if ("task".equalsIgnoreCase(args[0])) {
            return executeTaskCommand(args);
        }
        if (isSystemCommand(args[0])) {
            return new CommandLine(new SqlCli())
                    .setCaseInsensitiveEnumValuesAllowed(true)
                    .execute(args);
        }

        // 第一个参数是 alias
        String alias = args[0];

        // 检查后续参数
        if (args.length == 1) {
            // 只有 alias，显示帮助
            System.out.println("Alias: " + alias);
            System.out.println("Available actions: test, info, ddl, tables, secret, "
                    + "schema, batch, approval, ui");
            System.out.println("Or execute SQL: sql-cli " + alias + " \"<SQL>\"");
            System.out.println("Or execute SQL file: sql-cli " + alias + " --file <path>");
            return 0;
        }

        String secondArg = args[1];

        // 检查是否是 schema 操作
        if ("schema".equalsIgnoreCase(secondArg)) {
            requireBatchKind(alias, com.sqlcli.runstate.ApprovalBatchRow.KIND_GRAPH);
            return executeSchemaCommand(alias, args);
        }

        if ("batch".equalsIgnoreCase(secondArg)) {
            BatchCommand batchCmd = new BatchCommand();
            batchCmd.setAlias(alias);
            return new CommandLine(batchCmd)
                    .setCaseInsensitiveEnumValuesAllowed(true)
                    .execute(Arrays.copyOfRange(args, 2, args.length));
        }

        if ("approval".equalsIgnoreCase(secondArg)) {
            ApprovalCommand approvalCmd = new ApprovalCommand();
            approvalCmd.setAlias(alias);
            return new CommandLine(approvalCmd)
                    .setCaseInsensitiveEnumValuesAllowed(true)
                    .execute(Arrays.copyOfRange(args, 2, args.length));
        }

        // 检查是否是其他 alias 操作
        if (ALIAS_ACTIONS.contains(secondArg.toLowerCase())) {
            // 执行 alias 操作
            AliasActionCommand actionCmd = new AliasActionCommand();
            actionCmd.setAlias(alias);

            // action 作为第一个参数传给 picocli，后续参数跟随
            String[] cmdArgs = new String[args.length - 1];
            cmdArgs[0] = secondArg; // action
            for (int i = 2; i < args.length; i++) {
                cmdArgs[i - 1] = args[i];
            }

            return new CommandLine(actionCmd)
                    .setCaseInsensitiveEnumValuesAllowed(true)
                    .execute(cmdArgs);
        }

        QueryArguments queryArgs = parseQueryArguments(args);
        String sql = queryArgs.sql();

        // 如果指定了文件，读取文件内容
        if (queryArgs.sqlFile() != null) {
            try {
                sql = readSqlFile(queryArgs.sqlFile());
            } catch (IOException e) {
                System.err.println("Error reading SQL file: " + e.getMessage());
                return 1;
            }
        }

        if (sql == null || sql.isBlank()) {
            System.err.println("No SQL provided. Use either:");
            System.err.println("  sql-cli <alias> \"<SQL>\"");
            System.err.println("  sql-cli <alias> --file <path>");
            return 2;
        }
        // 多语句、readonly 等一律由 SqlTaskModule 的 GuardStage 统一拒绝，
        // 不在这里重复判断——单一入口的意义就是判断只写一遍。

        try {
            AliasResolver aliasResolver = new AliasResolver();
            DatabaseConfig config = aliasResolver.resolve(alias);

            Long batchId = com.sqlcli.session.SessionContext.batchId();
            if (batchId != null) {
                requireBatchKind(alias, com.sqlcli.runstate.ApprovalBatchRow.KIND_SQL);
                return stageSqlIntoBatch(alias, config, sql, batchId);
            }

            Set<String> extraDecryptColumns = queryArgs.decryptColumns() == null
                    ? Set.of() : new LinkedHashSet<>(Arrays.asList(queryArgs.decryptColumns()));
            QueryExecutionOptions options = QueryExecutionOptions.forAlias(
                    config, queryArgs.format(), extraDecryptColumns, queryArgs.noDecrypt());

            SqlTaskModule taskModule = new SqlTaskModule();
            SqlTaskRequest request = new SqlTaskRequest(config, sql, options, SqlTaskRequest.Origin.cli,
                    null, queryArgs.maxRows(), null, false);
            SqlTaskResult result = taskModule.execute(request);
            // 规则提示走 stderr：--json 的 stdout 契约只有那一个 JSON
            result.notices().forEach(System.err::println);

            String rendered = new QueryResultRenderer().render(result.columnNames(), result.rows(),
                    options.getFormat(), result.elapsedMs(), alias, config.getType(), result.sqlType(),
                    result.truncated());
            switch (result.status()) {
                case SUCCEEDED -> {
                    if (result.columns().isEmpty() && result.rows().isEmpty()) {
                        // 写操作/DDL：没有结果集，打印一行完成提示而不是空表格。
                        System.out.println(writeSummary(result));
                    } else {
                        System.out.print(rendered);
                    }
                    if (result.truncated()) {
                        System.err.println("警告: 结果已截断至 " + result.rows().size()
                                + " 行，--max-rows 0 可解除限制");
                    }
                    printEmptyResultHint(result, alias, sql);
                    return 0;
                }
                case REJECTED, FAILED -> {
                    if ("json".equals(queryArgs.format())) {
                        printJsonError("EXECUTION_FAILED", result.errorSummary());
                    } else {
                        System.err.println("Error: " + result.errorSummary());
                    }
                    return 1;
                }
                default -> throw new IllegalStateException("unreachable");
            }

        } catch (Exception e) {
            if ("json".equals(queryArgs.format())) {
                printJsonError("EXECUTION_FAILED", JdbcUrlParser.redactSecrets(e.getMessage()));
            } else {
                System.err.println("Error: " + JdbcUrlParser.redactSecrets(e.getMessage()));
            }
            return 1;
        }
    }

    /**
     * 摘掉三个全局参数：{@code --session} / {@code --agent} / {@code --batch}。
     *
     * <p>它们对每条写命令都适用。把它们加进 {@code SchemaActionCommand} 那三十个动作、
     * 再加进查询参数解析，是同一件事写两遍还容易漏一处；在入口摘一次、放进
     * {@link com.sqlcli.session.SessionContext}，后面所有写路径都读得到。
     *
     * <p>{@code session} 命令自己要用 {@code --agent}，不能在这里被摘走。
     */
    private static String[] consumeProvenanceFlags(String[] args) {
        if (args.length > 0 && "session".equalsIgnoreCase(args[0])) {
            return args;
        }
        List<String> rest = new java.util.ArrayList<>(args.length);
        String session = null;
        String agent = null;
        Long batch = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--session" -> session = requireOptionValue(args, ++i, "--session");
                case "--agent" -> agent = requireOptionValue(args, ++i, "--agent");
                case "--batch" -> {
                    String value = requireOptionValue(args, ++i, "--batch");
                    try {
                        batch = Long.parseLong(value.trim());
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("--batch 必须是批次号（数字）：" + value);
                    }
                }
                default -> rest.add(args[i]);
            }
        }
        com.sqlcli.session.SessionContext.applyOverrides(session, agent, batch);
        return rest.toArray(new String[0]);
    }

    /**
     * 带了 {@code --batch} 时校验这一批收得下这条命令。
     *
     * <p>四件事都在这里查完：批次存在、属于同一个别名、还是草稿、类型对得上。
     * 放过任何一件的后果都是条目静默进了一个永远不会落地的地方。
     */
    private static void requireBatchKind(String alias, String kind) {
        Long batchId = com.sqlcli.session.SessionContext.batchId();
        if (batchId == null) return;
        com.sqlcli.runstate.ApprovalBatchRow batch =
                new com.sqlcli.runstate.RunStateStore().findBatch(batchId);
        if (batch == null) {
            throw new IllegalArgumentException("批次 #" + batchId + " 不存在。先 batch begin。");
        }
        if (!batch.alias().equals(alias)) {
            throw new IllegalArgumentException("批次 #" + batchId + " 属于别名 " + batch.alias()
                    + "，不是 " + alias + "。跨别名批次不支持。");
        }
        if (!com.sqlcli.runstate.ApprovalBatchRow.STATUS_DRAFT.equals(batch.status())) {
            throw new IllegalArgumentException("批次 #" + batchId + " 已经提交过（"
                    + batch.status() + "），不能再往里加条目。");
        }
        if (!batch.kind().equals(kind)) {
            throw new IllegalArgumentException("批次 #" + batchId + " 是 " + batch.kind()
                    + " 批次，装不下这条 " + kind + " 变更。一批只装同一类。");
        }
    }

    /**
     * {@code --batch} 下的 SQL：**不执行**，作为一条草稿条目进批次。
     *
     * <p>整批 submit → 批准之后才在一个连接、一个事务里按 seq 跑完。
     * 这里立刻返回，调用方不必挂着等。
     */
    private static int stageSqlIntoBatch(String alias, DatabaseConfig config, String sql, long batchId) {
        String sqlType = new com.sqlcli.parser.SqlStatementAnalyzer().detectSqlType(sql);
        boolean write = !("SELECT".equals(sqlType) || "WITH".equals(sqlType)
                || "SHOW".equals(sqlType) || "EXPLAIN".equals(sqlType) || "DESCRIBE".equals(sqlType));
        if (write && Boolean.TRUE.equals(config.getReadonly())) {
            System.err.println("Error: 别名 " + alias + " 配置为只读，不能把写语句加进批次");
            return 1;
        }
        com.sqlcli.runstate.RunStateStore runState = new com.sqlcli.runstate.RunStateStore();
        int seq = runState.nextBatchSeq(batchId);
        long id = runState.createApproval(alias, write ? "update" : "query",
                sqlType + " · " + com.sqlcli.approval.ApprovalGate.summarize(sql), sql,
                null, null, null, com.sqlcli.runstate.ApprovalRow.STATUS_DRAFT, batchId, seq);
        if (id <= 0) {
            System.err.println("Error: 条目无法写入运行库，未加入批次。检查 ~/.sql-cli/sqlcli.db 是否可写。");
            return 1;
        }
        System.out.println("已加入批次 #" + batchId + "（条目 #" + id + "，seq " + seq + "）。"
                + "未执行——sql-cli " + alias + " batch submit " + batchId + " 提交后等批准才跑。");
        return 0;
    }

    private static QueryArguments parseQueryArguments(String[] args) {
        String format = "csv";
        String[] decryptColumns = null;
        boolean noDecrypt = false;
        String sqlFile = null;
        String sql = null;
        Integer maxRows = null;

        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "-f", "--format" -> format = requireOptionValue(args, ++i, arg);
                case "--decrypt-cols" -> decryptColumns = requireOptionValue(args, ++i, arg).split(",");
                case "--no-decrypt" -> noDecrypt = true;
                case "--file", "-F" -> sqlFile = requireOptionValue(args, ++i, arg);
                case "--max-rows" -> maxRows = parseMaxRows(requireOptionValue(args, ++i, arg));
                case "-d", "--debug" -> { }
                default -> {
                    if (arg.startsWith("-")) {
                        throw new IllegalArgumentException("Unknown option: " + arg);
                    }
                    if (sql != null) {
                        throw new IllegalArgumentException("Multiple SQL arguments provided; quote the SQL as one argument");
                    }
                    sql = arg;
                }
            }
        }
        if (sql != null && sqlFile != null) {
            throw new IllegalArgumentException("SQL text and --file cannot be used together");
        }
        return new QueryArguments(QueryExecutionOptions.normalizeFormat(format), decryptColumns, noDecrypt,
                sqlFile, sql, maxRows);
    }

    /** {@code --max-rows 0} 表示不限制，别名/内置默认值都拦不住这个逃生口。 */
    private static Integer parseMaxRows(String value) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0) {
                throw new IllegalArgumentException("--max-rows must be >= 0");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--max-rows must be a number: " + value);
        }
    }

    /** 写操作 / DDL 没有结果集可渲染，打印一行完成提示，恢复文件有就带上路径。 */
    /**
     * SELECT 查回 0 行时对照图谱值域：WHERE 里的等值比较值不在已知值域内就在 stderr 提示
     * （典型：状态列写了中文而库里存数字）。stdout 契约不动，提示走 stderr，与截断警告同通道。
     */
    private static void printEmptyResultHint(SqlTaskResult result, String alias, String sql) {
        if (!result.rows().isEmpty() || !"SELECT".equalsIgnoreCase(String.valueOf(result.sqlType()))) {
            return;
        }
        try {
            com.sqlcli.graph.workspace.GraphWorkspaceStore store =
                    new com.sqlcli.graph.workspace.GraphWorkspaceStore();
            if (!store.exists(alias)) {
                return;
            }
            String hint = com.sqlcli.graph.workspace.GraphQueryHints.emptyResultHint(sql, store.load(alias));
            if (hint != null) {
                System.err.println(hint);
            }
        } catch (Exception ignored) {
            // 提示逻辑不影响主流程
        }
    }

    private static String writeSummary(SqlTaskResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append(result.sqlType()).append(" executed successfully.");
        if (result.affectedRows() != null) {
            sb.append(" Affected rows: ").append(result.affectedRows());
        }
        if (result.taskId() != null) {
            sb.append("\nTask: ").append(result.taskId())
                    .append("  (sql-cli task status ").append(result.taskId()).append(")");
        }
        if (result.recoveryId() != null) {
            sb.append("\n回滚 SQL 已保存，可在 Web UI 的评审页「执行记录」里查看或执行。");
        }
        return sb.toString();
    }

    private static String requireOptionValue(String[] args, int index, String option) {
        if (index >= args.length || args[index].startsWith("-")) {
            throw new IllegalArgumentException("Missing value for " + option);
        }
        return args[index];
    }

    /**
     * 读取SQL文件内容
     */
    private static String readSqlFile(String filePath) throws IOException {
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) {
            throw new IOException("File not found: " + filePath);
        }
        return Files.readString(path).trim();
    }

    private static void validateGlobalFlagPosition(String[] args) {
        if (args.length == 0) {
            return;
        }
        String first = args[0];
        if ("-d".equals(first) || "--debug".equals(first)) {
            throw new IllegalArgumentException("Place --debug after alias, for example: sql-cli yearning --debug \"SELECT 1\"");
        }
    }


    private static void printJsonError(String code, String message) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("ok", false);
        output.put("code", code);
        output.put("message", message == null ? "Unknown error" : message);
        try {
            System.out.println(JSON_MAPPER.writeValueAsString(output));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize JSON error", e);
        }
    }

    /**
     * 执行 schema 命令
     */
    private static int executeSchemaCommand(String alias, String[] args) {
        SchemaActionCommand schemaCmd = new SchemaActionCommand();
        schemaCmd.setAlias(alias);

        // 构建子命令参数
        // args: [alias, schema, action, ...]
        String[] subArgs = new String[args.length - 2];
        for (int i = 2; i < args.length; i++) {
            subArgs[i - 2] = args[i];
        }

        // 解析子命令参数并设置到 schemaCmd
        parseSchemaArgs(schemaCmd, subArgs);

        // 直接运行（参数已在 parseSchemaArgs 中设置）
        return schemaCmd.executeCommand();
    }

    /**
     * 解析 schema 命令参数
     */
    private static void parseSchemaArgs(SchemaActionCommand cmd, String[] args) {
        String action = null;
        String tableName = null;
        String tableName2 = null;
        String schemaName = null;
        String keyword = null;
        String subAction = null;
        int depth = 1;
        boolean jsonOutput = false;
        String outputPath = null;
        String inputPath = null;
        boolean merge = false;
        boolean fromDb = false;
        boolean force = false;
        String editTable = null;
        String editColumn = null;
        String description = null;
        String example = null;
        String enumValues = null;
        String codeEnum = null;
        String codeEnumBasis = null;
        String valueFormat = null;
        String addTag = null;
        String addConstraint = null;
        String editBusinessName = null;
        String editSemanticType = null;
        String editRedundantOf = null;
        String displayName = null;
        String owner = null;
        String termName = null;
        String relationType = null;
        String fromRef = null;
        String toRef = null;
        String joinExpression = null;
        Double confidence = null;
        Boolean verifiedFlag = null;
        String aliasesCsv = null;
        String negativeAliasesCsv = null;
        String mappedRefsCsv = null;
        String primaryTargetRef = null;
        java.util.List<String> termFilters = new java.util.ArrayList<>();
        String importSchema = null;
        String importTable = null;
        Integer batchSize = null;
        boolean forceOverwrite = false;
        boolean refreshRowCount = false;
        boolean helpRequested = false;
        Integer searchLimit = null;
        String targetRef = null;
        String sourceRefsCsv = null;
        String expression = null;
        String through = null;
        boolean lineageDownstream = false;
        String lineageKindArg = null;
        String lineageId = null;
        String casesPath = null;
        String answersPath = null;
        Double boost = null;
        String filters = null;
        String grainColumnRef = null;
        String grainsCsv = null;
        String dimensionsCsv = null;
        String joinPathCsv = null;
        String requestedGrain = null;
        String timeFrom = null;
        String timeTo = null;
        boolean assumeYes = false;
        Integer deadMonths = null;

        if (args.length > 0 && !args[0].startsWith("-") && !SCHEMA_ACTIONS.contains(args[0])) {
            if ("help".equals(args[0])) {
                throw new IllegalArgumentException(
                        "Unknown schema action: help. Use 'schema <action> --help'");
            }
            throw new IllegalArgumentException("Unknown schema action: " + args[0]);
        }

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (i == 0 && SCHEMA_ACTIONS.contains(arg)) {
                action = arg;
                continue;
            }
            switch (arg) {
                case "help":
                    throw new IllegalArgumentException(
                            "Unknown schema action: help. Use 'schema <action> --help'");
                case "--help":
                case "-h":
                    helpRequested = true;
                    break;
                case "rebuild":
                case "status":
                    if ("index".equals(action) || "import".equals(action)) subAction = arg;
                    break;
                case "resume":
                case "reset":
                    if ("import".equals(action)) subAction = arg;
                    break;
                case "--json":
                case "-j":
                    jsonOutput = true;
                    break;
                case "--schema":
                case "-s":
                    if (i + 1 < args.length) {
                        if ("import".equals(action)) importSchema = args[++i];
                        else schemaName = args[++i];
                    }
                    break;
                case "--depth":
                case "-d":
                    if (i + 1 < args.length) depth = Integer.parseInt(args[++i]);
                    break;
                case "--output":
                case "-o":
                    if (i + 1 < args.length) outputPath = args[++i];
                    break;
                case "--input":
                case "-i":
                    if (i + 1 < args.length) inputPath = args[++i];
                    break;
                case "--merge":
                    merge = true;
                    break;
                case "--from-db":
                    fromDb = true;
                    break;
                case "--resume":
                    if ("import".equals(action)) subAction = "resume";
                    break;
                case "--force":
                case "-f":
                    force = true;
                    break;
                case "--table":
                    if (i + 1 < args.length) {
                        if ("import".equals(action)) importTable = args[++i];
                        else editTable = args[++i];
                    }
                    break;
                case "--column":
                    if (i + 1 < args.length) editColumn = args[++i];
                    break;
                case "--description":
                    if (i + 1 < args.length) description = args[++i];
                    break;
                case "--example":
                    if (i + 1 < args.length) example = args[++i];
                    break;
                case "--enum-values":
                    if (i + 1 < args.length) enumValues = args[++i];
                    break;
                case "--code-enum":
                    if (i + 1 < args.length) codeEnum = args[++i];
                    break;
                case "--basis":
                    if (i + 1 < args.length) codeEnumBasis = args[++i];
                    break;
                case "--yes":
                    assumeYes = true;
                    break;
                case "--months":
                    if (i + 1 < args.length) deadMonths = Integer.parseInt(args[++i]);
                    break;
                case "--format":
                    if (i + 1 < args.length) valueFormat = args[++i];
                    break;
                case "--add-tag":
                    if (i + 1 < args.length) addTag = args[++i];
                    break;
                case "--add-constraint":
                    if (i + 1 < args.length) addConstraint = args[++i];
                    break;
                case "--business-name":
                    if (i + 1 < args.length) editBusinessName = args[++i];
                    break;
                case "--semantic-type":
                    if (i + 1 < args.length) editSemanticType = args[++i];
                    break;
                case "--redundant-of":
                    if (i + 1 < args.length) editRedundantOf = args[++i];
                    break;
                case "--display-name":
                    if (i + 1 < args.length) displayName = args[++i];
                    break;
                case "--owner":
                    if (i + 1 < args.length) owner = args[++i];
                    break;
                case "--type":
                    if (i + 1 < args.length) relationType = args[++i];
                    break;
                case "--from":
                    if (i + 1 < args.length) fromRef = args[++i];
                    break;
                case "--to":
                    if (i + 1 < args.length) toRef = args[++i];
                    break;
                case "--join":
                    if (i + 1 < args.length) joinExpression = args[++i];
                    break;
                case "--confidence":
                    if (i + 1 < args.length) confidence = Double.parseDouble(args[++i]);
                    break;
                case "--verified":
                    verifiedFlag = true;
                    break;
                case "--aliases":
                    if (i + 1 < args.length) aliasesCsv = args[++i];
                    break;
                case "--negative-aliases":
                    if (i + 1 < args.length) negativeAliasesCsv = args[++i];
                    break;
                case "--map":
                    if (i + 1 < args.length) mappedRefsCsv = args[++i];
                    break;
                case "--primary-target":
                    if (i + 1 < args.length) primaryTargetRef = args[++i];
                    break;
                case "--filter":
                    // 可重复：一个条件一个 --filter，不用逗号分隔——条件里本来就有逗号
                    // （`state IN (0,1,6)`），拆错比多敲几个字贵得多
                    if (i + 1 < args.length) termFilters.add(args[++i]);
                    break;
                case "--batch-size":
                    if (i + 1 < args.length) batchSize = Integer.parseInt(args[++i]);
                    break;
                case "--force-overwrite":
                    forceOverwrite = true;
                    break;
                case "--refresh-row-count":
                    refreshRowCount = true;
                    break;
                case "--limit":
                    if (i + 1 < args.length) searchLimit = Integer.parseInt(args[++i]);
                    break;
                case "--target":
                    if (i + 1 < args.length) targetRef = args[++i];
                    break;
                case "--source":
                    if (i + 1 < args.length) sourceRefsCsv = args[++i];
                    break;
                case "--expression":
                    if (i + 1 < args.length) expression = args[++i];
                    break;
                case "--through":
                    if (i + 1 < args.length) through = args[++i];
                    break;
                case "--kind":
                    if (i + 1 < args.length) lineageKindArg = args[++i];
                    break;
                case "--id":
                    if (i + 1 < args.length) lineageId = args[++i];
                    break;
                case "--upstream":
                    lineageDownstream = false;
                    break;
                case "--downstream":
                    lineageDownstream = true;
                    break;
                case "--cases":
                    if (i + 1 < args.length) casesPath = args[++i];
                    break;
                case "--answers":
                    if (i + 1 < args.length) answersPath = args[++i];
                    break;
                case "--boost":
                    if (i + 1 < args.length) boost = Double.parseDouble(args[++i]);
                    break;
                case "--filters":
                    if (i + 1 < args.length) filters = args[++i];
                    break;
                case "--grain-column":
                    if (i + 1 < args.length) grainColumnRef = args[++i];
                    break;
                case "--grains":
                    if (i + 1 < args.length) grainsCsv = args[++i];
                    break;
                case "--dimensions":
                    if (i + 1 < args.length) dimensionsCsv = args[++i];
                    break;
                case "--join-path":
                    if (i + 1 < args.length) joinPathCsv = args[++i];
                    break;
                case "--grain":
                    if (i + 1 < args.length) requestedGrain = args[++i];
                    break;
                case "--time-from":
                    if (i + 1 < args.length) timeFrom = args[++i];
                    break;
                case "--time-to":
                    if (i + 1 < args.length) timeTo = args[++i];
                    break;
            }
        }

        // 设置参数到 command
        cmd.setAction(action != null ? action : "help");
        cmd.setTableName(tableName);
        cmd.setTableName2(tableName2);
        cmd.setKeyword(keyword);
        cmd.setSubAction(subAction);
        cmd.setDepth(depth);
        cmd.setJsonOutput(jsonOutput);
        cmd.setOutputPath(outputPath);
        cmd.setInputPath(inputPath);
        cmd.setMerge(merge);
        cmd.setFromDb(fromDb);
        cmd.setForce(force);
        cmd.setEditTable(editTable);
        cmd.setEditColumn(editColumn);
        cmd.setDescription(description);
        cmd.setExample(example);
        cmd.setEnumValuesCsv(enumValues);
        cmd.setCodeEnumCsv(codeEnum);
        cmd.setCodeEnumBasis(codeEnumBasis);
        cmd.setAssumeYes(assumeYes);
        cmd.setDeadMonths(deadMonths);
        cmd.setValueFormat(valueFormat);
        cmd.setAddTag(addTag);
        cmd.setAddConstraint(addConstraint);
        cmd.setEditBusinessName(editBusinessName);
        cmd.setEditSemanticType(editSemanticType);
        cmd.setEditRedundantOf(editRedundantOf);
        cmd.setDisplayName(displayName);
        cmd.setOwner(owner);
        cmd.setRelationType(relationType);
        cmd.setFromRef(fromRef);
        cmd.setToRef(toRef);
        cmd.setJoinExpression(joinExpression);
        cmd.setConfidence(confidence);
        cmd.setVerifiedFlag(verifiedFlag);
        cmd.setAliasesCsv(aliasesCsv);
        cmd.setNegativeAliasesCsv(negativeAliasesCsv);
        cmd.setMappedRefsCsv(mappedRefsCsv);
        cmd.setPrimaryTargetRef(primaryTargetRef);
        cmd.setTermFilters(termFilters);
        cmd.setTargetRef(targetRef);
        cmd.setSourceRefsCsv(sourceRefsCsv);
        cmd.setExpression(expression);
        cmd.setThrough(through);
        cmd.setLineageKindArg(lineageKindArg);
        cmd.setLineageId(lineageId);
        cmd.setLineageDownstream(lineageDownstream);
        cmd.setCasesPath(casesPath);
        cmd.setAnswersPath(answersPath);
        cmd.setBoost(boost);
        cmd.setFilters(filters);
        cmd.setGrainColumnRef(grainColumnRef);
        cmd.setGrainsCsv(grainsCsv);
        cmd.setDimensionsCsv(dimensionsCsv);
        cmd.setJoinPathCsv(joinPathCsv);
        cmd.setRequestedGrain(requestedGrain);
        cmd.setTimeFrom(timeFrom);
        cmd.setTimeTo(timeTo);
        cmd.setImportSchema(importSchema);
        cmd.setImportTable(importTable);
        cmd.setBatchSize(batchSize);
        cmd.setForceOverwrite(forceOverwrite);
        cmd.setRefreshRowCount(refreshRowCount);
        cmd.setHelpRequested(helpRequested);
        cmd.setSearchLimit(searchLimit);
        cmd.setCommandArgs(Arrays.asList(args));

        // 非 list/describe 等命令需要解析位置参数
        if ("describe".equals(action) || "query".equals(action)) {
            List<String> positional = positionalArgs(args, action);
            cmd.setTableName(positional.isEmpty() ? null : positional.get(0));
        } else if ("path".equals(action)) {
            List<String> positional = positionalArgs(args, action);
            if (positional.size() >= 2) {
                cmd.setTableName(positional.get(0));
                cmd.setTableName2(positional.get(1));
            }
        } else if ("search".equals(action)) {
            List<String> positional = positionalArgs(args, action);
            cmd.setKeyword(positional.isEmpty() ? null : positional.get(0));
        } else if ("lineage".equals(action)) {
            List<String> positional = positionalArgs(args, action);
            cmd.setTableName(positional.isEmpty() ? null : positional.get(0));
        } else if ("add-term".equals(action)) {
            List<String> positional = positionalArgs(args, action);
            cmd.setTermName(positional.isEmpty() ? null : positional.get(0));
        } else if ("add-metric".equals(action) || "metric".equals(action) || "expand-metric".equals(action)) {
            List<String> positional = positionalArgs(args, action);
            cmd.setMetricName(positional.isEmpty() ? null : positional.get(0));
        }
    }

    /** 带值的选项：位置参数扫描时它们的值必须跳过，否则 --limit 20 的 20 会被当成关键字。 */
    private static final Set<String> SCHEMA_VALUE_OPTIONS = Set.of(
            "--schema", "-s", "--depth", "-d", "--output", "-o", "--input", "-i",
            "--table", "--column", "--description", "--example", "--enum-values", "--format",
            "--add-tag", "--add-constraint", "--business-name", "--semantic-type", "--redundant-of",
            "--display-name", "--owner", "--type",
            "--from", "--to", "--join", "--confidence", "--aliases", "--negative-aliases", "--map",
            "--primary-target", "--filter",
            "--batch-size", "--limit", "--cases", "--answers", "--months",
            "--expression", "--filters", "--grain-column", "--grains", "--dimensions",
            "--target", "--source", "--through", "--kind", "--id",
            "--join-path", "--grain", "--time-from", "--time-to");

    private static List<String> positionalArgs(String[] args, String action) {
        List<String> out = new java.util.ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (SCHEMA_VALUE_OPTIONS.contains(a)) {
                i++; // 连值一起跳过
                continue;
            }
            if (a.startsWith("-") || a.equals(action)) {
                continue;
            }
            out.add(a);
        }
        return out;
    }

    /**
     * {@code sql-cli task status <id> [--json]}：按 id 读 task_run + 关联事件，纯查询。
     * 数据由 SqlTaskModule 执行时写入，这里只是把它接出来。
     */
    private static int executeTaskCommand(String[] args) {
        boolean jsonOutput = Arrays.asList(args).contains("--json");
        if (args.length >= 2 && "list".equalsIgnoreCase(args[1])) {
            return executeTaskListCommand(args, jsonOutput);
        }
        if (args.length < 3 || !"status".equalsIgnoreCase(args[1])) {
            System.err.println("Usage: sql-cli task status <id> [--json]");
            System.err.println("       sql-cli task list [--alias <alias>] [--status <status>] [--limit N] [--json]");
            return 2;
        }
        long id;
        try {
            id = Long.parseLong(args[2]);
        } catch (NumberFormatException e) {
            System.err.println("Usage: sql-cli task status <id> [--json]");
            return 2;
        }

        com.sqlcli.runstate.RunStateStore store = new com.sqlcli.runstate.RunStateStore();
        com.sqlcli.runstate.TaskRunRow run = store.findTaskRun(id);
        if (run == null) {
            if (jsonOutput) {
                printJsonError("TASK_NOT_FOUND", "task run not found: " + id);
            } else {
                System.err.println("Error: task run not found: " + id);
            }
            return 1;
        }
        List<com.sqlcli.runstate.TaskEventRow> events = store.listTaskEvents(id);

        if (jsonOutput) {
            Map<String, Object> taskRun = new LinkedHashMap<>();
            taskRun.put("id", run.id());
            taskRun.put("taskType", run.taskType());
            taskRun.put("actor", run.actor());
            taskRun.put("alias", run.alias());
            taskRun.put("status", run.status());
            taskRun.put("createdAt", run.createdAt());
            taskRun.put("updatedAt", run.updatedAt());
            List<Map<String, Object>> eventList = new java.util.ArrayList<>();
            for (com.sqlcli.runstate.TaskEventRow event : events) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("eventType", event.eventType());
                item.put("createdAt", event.createdAt());
                item.put("payload", parsePayload(event.payload()));
                eventList.add(item);
            }
            try {
                System.out.println(JSON_MAPPER.writeValueAsString(
                        Map.of("ok", true, "data", Map.of("taskRun", taskRun, "events", eventList))));
            } catch (JsonProcessingException e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
            return 0;
        }

        System.out.println("Task: " + run.id() + " (" + run.taskType() + ")");
        System.out.println("Alias: " + run.alias());
        System.out.println("Actor: " + run.actor());
        System.out.println("Status: " + run.status());
        if (!events.isEmpty()) {
            System.out.println("Events:");
            for (com.sqlcli.runstate.TaskEventRow event : events) {
                String payload = event.payload() == null || event.payload().isBlank()
                        ? "" : " " + event.payload();
                System.out.println("  " + event.eventType() + payload);
            }
        }
        return 0;
    }

    /**
     * 最近的写任务列表。
     *
     * <p>存在的理由是「命令被丢到后台之后怎么找回来」：写操作命中审批开关时命令会挂着
     * 等人放行，调用方（尤其是 agent）应该把它放后台、继续做别的，回头用这里的 id
     * 去 {@code task status} 看结果，而不是干等。
     */
    private static int executeTaskListCommand(String[] args, boolean jsonOutput) {
        String alias = null;
        String status = null;
        int limit = 20;
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--alias" -> alias = requireOptionValue(args, ++i, "--alias");
                case "--status" -> status = requireOptionValue(args, ++i, "--status");
                case "--limit" -> limit = Integer.parseInt(requireOptionValue(args, ++i, "--limit"));
                case "--json", "-d", "--debug" -> { }
                default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
            }
        }
        List<com.sqlcli.runstate.TaskRunRow> runs =
                new com.sqlcli.runstate.RunStateStore().listTaskRuns(alias, status, limit);
        if (jsonOutput) {
            List<Map<String, Object>> items = new java.util.ArrayList<>();
            for (com.sqlcli.runstate.TaskRunRow run : runs) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", run.id());
                item.put("taskType", run.taskType());
                item.put("actor", run.actor());
                item.put("alias", run.alias());
                item.put("status", run.status());
                item.put("createdAt", run.createdAt());
                item.put("updatedAt", run.updatedAt());
                items.add(item);
            }
            try {
                System.out.println(JSON_MAPPER.writeValueAsString(
                        Map.of("ok", true, "data", Map.of("taskRuns", items))));
            } catch (JsonProcessingException e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
            return 0;
        }
        if (runs.isEmpty()) {
            System.out.println("No task runs.");
            return 0;
        }
        for (com.sqlcli.runstate.TaskRunRow run : runs) {
            System.out.printf("%-6d %-10s %-12s %-10s %s%n", run.id(), run.status(), run.alias(),
                    run.actor(), java.time.Instant.ofEpochMilli(run.createdAt()));
        }
        return 0;
    }

    /** 事件 payload 是 JSON 字符串就内联成对象，不是就原样返回——别让调用方吃字符串套字符串。 */
    private static Object parsePayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            return JSON_MAPPER.readTree(payload);
        } catch (Exception e) {
            return payload;
        }
    }

    private static boolean isSystemCommand(String arg) {
        return "-V".equals(arg) || SYSTEM_COMMANDS.contains(arg.toLowerCase(Locale.ROOT));
    }

    private record QueryArguments(String format, String[] decryptColumns, boolean noDecrypt,
                                  String sqlFile, String sql, Integer maxRows) {
    }

    public static class VersionProvider implements IVersionProvider {
        @Override
        public String[] getVersion() {
            String implementationVersion = SqlCli.class.getPackage().getImplementationVersion();
            if (implementationVersion != null && !implementationVersion.isBlank()) {
                return new String[]{implementationVersion};
            }
            try (InputStream in = SqlCli.class.getClassLoader().getResourceAsStream(
                    "META-INF/maven/com.sqlcli/sql-cli/pom.properties")) {
                if (in != null) {
                    Properties properties = new Properties();
                    properties.load(in);
                    return new String[]{properties.getProperty("version", "development")};
                }
            } catch (IOException ignored) {
                // Fall through to a deterministic development version.
            }
            return new String[]{"development"};
        }
    }
}
