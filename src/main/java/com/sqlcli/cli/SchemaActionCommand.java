package com.sqlcli.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sqlcli.config.AliasResolver;
import com.sqlcli.config.DatabaseConfig;
import com.sqlcli.config.JdbcUrlParser;
import com.sqlcli.connection.ConnectionManager;
import com.sqlcli.connection.TableRowCounter;
import com.sqlcli.profile.ColumnProfile;
import com.sqlcli.profile.ColumnSpec;
import com.sqlcli.profile.DataQualityProbes;
import com.sqlcli.profile.ProfileGraphWriter;
import com.sqlcli.profile.ProfileOptions;
import com.sqlcli.profile.TableProfile;
import com.sqlcli.profile.TableProfiler;
import com.sqlcli.profile.ValueDomainCrosscheck;
import com.sqlcli.profile.ValueDomainVerdict;
import com.sqlcli.graph.eval.GraphEvaluation;
import com.sqlcli.graph.eval.GraphEvalRunner;
import com.sqlcli.graph.eval.GraphEvaluator;
import com.sqlcli.graph.eval.GraphFinding;
import com.sqlcli.graph.ui.service.WorkspaceLockException;
import com.sqlcli.graph.ui.service.WorkspaceLockManager;
import com.sqlcli.graph.ui.service.WorkspaceMutationService;
import com.sqlcli.graph.workspace.*;
import com.sqlcli.graph.workspace.diagram.WorkspaceDiagramGenerator;
import com.sqlcli.graph.workspace.index.WorkspaceIndexSnapshot;
import com.sqlcli.graph.workspace.index.WorkspaceIndexManifest;
import com.sqlcli.graph.workspace.index.WorkspaceIndexStore;
import com.sqlcli.graph.workspace.index.WorkspaceIndexedSearchEngine;
import com.sqlcli.graph.workspace.index.WorkspaceIndexer;
import com.sqlcli.graph.workspace.index.IndexStatus;
import com.sqlcli.graph.eval.TaskEval;
import com.sqlcli.approval.ApprovalGate;
import com.sqlcli.graph.policy.*;
import com.sqlcli.graph.review.CandidateDdlProjector;
import com.sqlcli.metric.MetricExpansionException;
import com.sqlcli.metric.MetricSqlExpander;
import com.sqlcli.metric.MetricSqlRequest;
import com.sqlcli.parser.SqlStatementAnalyzer;
import com.sqlcli.runstate.InteractionDetail;
import com.sqlcli.runstate.RunStateStore;
import com.sqlcli.strategy.DatabaseStrategy;
import com.sqlcli.strategy.DatabaseStrategies;
import com.sqlcli.strategy.WorkspaceMetadataProviderFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Schema workspace command handler.
 */
public class SchemaActionCommand implements Runnable {

    private final AliasResolver aliasResolver = new AliasResolver();
    private final ConnectionManager connectionManager = new ConnectionManager();
    private final GraphWorkspaceStore workspaceStore;
    private final WorkspaceValidator validator = new WorkspaceValidator();
    private final GraphWorkspaceMerger workspaceMerger = new GraphWorkspaceMerger();
    private final WorkspaceIndexer workspaceIndexer = new WorkspaceIndexer();
    private final WorkspaceIndexStore indexStore = new WorkspaceIndexStore();
    private final WorkspaceImportService importService = new WorkspaceImportService();
    private final WorkspaceDiagramGenerator diagramGenerator = new WorkspaceDiagramGenerator();
    private final RelationValidator relationValidator = new RelationValidator();
    private final WorkspaceMutationService mutationService;
    private final PolicyService policyService;
    private final CandidateDdlProjector ddlProjector = new CandidateDdlProjector();
    private final SqlStatementAnalyzer sqlStatementAnalyzer = new SqlStatementAnalyzer();
    private final ObjectMapper jsonMapper;

    private String alias;
    private String action;
    private String tableName;
    private String tableName2;
    private String keyword;
    private String subAction;
    private boolean jsonOutput;
    private String outputPath;
    private String inputPath;
    private boolean merge;
    private boolean fromDb;
    private boolean force;
    private boolean refreshRowCount;
    /** search 条数上限：null=默认（文本 15 组 / JSON 50 条），0=不限。 */
    private Integer searchLimit;
    private int depth = 1;

    private String editTable;
    private String editColumn;
    private String description;
    private String example;
    private String enumValuesCsv;
    private String valueFormat;
    private String addTag;
    private String addConstraint;
    private String editBusinessName;
    private String editSemanticType;
    private String editRedundantOf;
    private String displayName;
    private String owner;
    private String termName;
    private String relationType;
    private String fromRef;
    private String toRef;
    private String joinExpression;
    private Double confidence;
    private Boolean verifiedFlag;
    private String aliasesCsv;
    private String negativeAliasesCsv;
    private String mappedRefsCsv;
    private String primaryTargetRef;
    private List<String> termFilters = new ArrayList<>();
    private String targetRef;
    private String sourceRefsCsv;
    /** `schema value-domain --code-enum`：值域三源里的第三源，Agent 读完代码带进来。 */
    private String codeEnumCsv;
    /** `--basis`：这份代码枚举**从哪读来的**。带了第三源就必须给，否则审批人无从判断。 */
    private String codeEnumBasis;
    /** `schema data-quality --yes`：已经看过将要执行的查询清单并放行（skill 铁律 2）。 */
    private boolean assumeYes;
    /** `schema data-quality --months`：最后一次写入早于这么多个月就报「表可能已死」。 */
    private Integer deadMonths;
    private String expression;
    private String through;
    private boolean lineageDownstream;
    /** `add-lineage --kind`：identity / transformation / aggregation / rule，不给就按形状推断。 */
    private String lineageKindArg;
    /** `remove-lineage --id`：给 UI 和 --json 输出用；人敲的是 --target [--through]。 */
    private String lineageId;
    private String casesPath;
    /** `schema task-eval --answers`：agent 的作答，control / cheat 两组。 */
    private String answersPath;
    private Double boost;
    private String importSchema;
    private String importTable;
    private Integer batchSize;
    private boolean forceOverwrite;
    private boolean helpRequested;
    private List<String> commandArgs = List.of();

    // ---- metric（BI 语义层）----
    private String metricName;
    private String filters;
    private String grainColumnRef;
    private String grainsCsv;
    private String dimensionsCsv;
    private String joinPathCsv;
    private String requestedGrain;
    private String timeFrom;
    private String timeTo;

    public SchemaActionCommand() {
        this(new GraphWorkspaceStore());
    }

    public SchemaActionCommand(GraphWorkspaceStore workspaceStore) {
        this.workspaceStore = workspaceStore;
        this.mutationService = new WorkspaceMutationService(
                workspaceStore, validator, new WorkspaceLockManager());
        this.policyService = new PolicyService(workspaceStore);
        jsonMapper = new ObjectMapper();
        jsonMapper.registerModule(new JavaTimeModule());
        jsonMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        jsonMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    public void setAlias(String alias) {
        this.alias = alias;
    }

    public void setRefreshRowCount(boolean refreshRowCount) {
        this.refreshRowCount = refreshRowCount;
    }

    public void setSearchLimit(Integer searchLimit) {
        this.searchLimit = searchLimit;
    }

    public void setAction(String action) {
        this.action = action;
    }

    @Override
    public void run() {
        executeCommand();
    }

    public int executeCommand() {
        try {
            return execute();
        } catch (IllegalArgumentException e) {
            return usageError(JdbcUrlParser.redactSecrets(e.getMessage()));
        } catch (Exception e) {
            String message = JdbcUrlParser.redactSecrets(e.getMessage());
            if (jsonOutput) {
                CliJson.printFailure("COMMAND_FAILED", message);
            } else {
                System.err.println("Error: " + message);
            }
            return 1;
        }
    }

    private int execute() throws Exception {
        String action = safeAction();
        if (helpRequested) {
            if ("help".equals(action)) {
                printHelp();
                return 0;
            }
            return printActionHelp(action) ? 0 : 2;
        }
        if ("help".equals(action)) {
            printHelp();
            return 0;
        }
        GraphWorkspace workspace = loadWorkspaceIfExists();
        int exitCode;
        switch (action) {
            case "list" -> exitCode = listTables(workspace);
            case "describe" -> exitCode = describeTable(workspace);
            case "query" -> exitCode = queryTable(workspace);
            case "path" -> exitCode = findPath(workspace);
            case "search" -> exitCode = search(workspace);
            case "export" -> exitCode = exportWorkspace(workspace);
            case "import" -> exitCode = importWorkspace();
            case "edit" -> exitCode = editWorkspace(workspace);
            case "stats" -> exitCode = showStats(workspace);
            case "validate" -> exitCode = validateWorkspace(workspace);
            case "eval" -> exitCode = evaluateWorkspace(workspace);
            case "gaps" -> exitCode = gapsAction(workspace);
            case "add-term" -> exitCode = addTerm(workspace);
            case "add-relation" -> exitCode = addRelation(workspace);
            case "add-lineage" -> exitCode = addLineage(workspace);
            case "remove-lineage" -> exitCode = removeLineage(workspace);
            case "lineage" -> exitCode = showLineage(workspace);
            case "add-metric" -> exitCode = addMetric(workspace);
            case "metrics" -> exitCode = listMetrics(workspace);
            case "metric" -> exitCode = showMetric(workspace);
            case "expand-metric" -> exitCode = expandMetric(workspace);
            case "search-eval" -> exitCode = searchEval(workspace);
            case "task-eval" -> exitCode = taskEval();
            case "value-domain" -> exitCode = valueDomain(workspace);
            case "data-quality" -> exitCode = dataQuality(workspace);
            case "index" -> exitCode = handleIndex(workspace);
            case "diagram" -> exitCode = generateDiagram(workspace);
            case "policy" -> exitCode = handlePolicy(workspace);
            case "design" -> exitCode = reviewDesign(workspace);
            case "migration" -> exitCode = lintMigration(workspace);
            default -> {
                System.err.println("Unknown action: " + action);
                System.err.println();
                printHelp();
                exitCode = 1;
            }
        }
        return exitCode;
    }

    private String safeAction() {
        return action == null ? "help" : action.toLowerCase(Locale.ROOT);
    }

    /** edit --help 里列出 --semantic-type 的合法取值；跟 {@link #parseSemanticType} 共用同一份枚举，改枚举不用改这里。 */
    private static String semanticTypeOptionsHelp() {
        return SemanticType.optionLabels().entrySet().stream()
                .map(entry -> entry.getKey() + "(" + entry.getValue() + ")")
                .collect(Collectors.joining(", "));
    }

    void printHelp() {
        String a = alias != null ? alias : "<alias>";
        System.out.println("Usage: sql-cli " + a + " schema <action> [options]");
        System.out.println();
        System.out.println("Actions:");
        System.out.println("  list                          列出所有表");
        System.out.println("  describe <schema.table>       查看表详情（列、关系、注释、行数）");
        System.out.println("  query <schema.table>          查看表关联关系（可指定深度）");
        System.out.println("  path <table1> <table2>        查找两个表之间的关联路径");
        System.out.println("  search <keyword>              搜索表、列、术语（按名称/注释匹配）");
        System.out.println("  import                        从数据库或文件导入图谱");
        System.out.println("  export                        导出图谱为 JSON 快照");
        System.out.println("  edit                          编辑表或列的注释、业务名、语义类型、标签");
        System.out.println("  stats                         查看图谱统计信息");
        System.out.println("  validate                      校验图谱数据完整性");
        System.out.println("  eval                          评估图谱质量：硬错误 + 覆盖率，每条发现自带修复命令");
        System.out.println("  gaps                          补图谱前先跑一次：被搜到多但语义缺失的对象、搜空的查询词");
        System.out.println("  add-term                      添加业务术语");
        System.out.println("  add-relation                  添加关系");
        System.out.println("  lineage <schema.table.column> 查询字段血缘（上游/下游）");
        System.out.println("  add-lineage                   写入一条字段血缘（候选状态），--kind 四类：抄 / 算 / 汇总 / 按条件写");
        System.out.println("  remove-lineage                删除一条字段血缘");
        System.out.println("  add-metric <name>              创建/更新一条 BI 指标定义（候选状态）");
        System.out.println("  metrics                        列出全部指标");
        System.out.println("  metric <name>                  查看指标详情");
        System.out.println("  expand-metric <name>           把指标按粒度/维度展开成可执行 SQL");
        System.out.println("  search-eval                   用评估集度量搜索命中率");
        System.out.println("  task-eval                     给 agent 的作答判分并归因（图谱侧 / skill 侧）");
        System.out.println("  value-domain                  值域三源交叉（库分布×注释×代码枚举），产出待审批");
        System.out.println("  data-quality                  数据层质量探针（连库只读聚合）：字段能删吗 / 表还活着吗");
        System.out.println("  index                         管理搜索索引");
        System.out.println("  diagram                       生成开发者关联图");
        System.out.println("  policy                        执行和查询结构规则审计");
        System.out.println("  design review                 审查候选 DDL，不修改图谱");
        System.out.println("  migration lint                审查 Migration，不执行 SQL");
        System.out.println();
        System.out.println("查看某个 action 的详细参数:");
        System.out.println("  sql-cli " + a + " schema <action> --help");
        System.out.println();
        System.out.println("详细用法:");
        System.out.println("  sql-cli " + a + " schema describe <schema.table> [--json]");
        System.out.println("  sql-cli " + a + " schema query <schema.table> [--depth N] [--json]");
        System.out.println("  sql-cli " + a + " schema path <schema.table> <schema.table> [--json]");
        System.out.println("  sql-cli " + a + " schema search <keyword> [--limit N] [--json]");
        System.out.println("  sql-cli " + a + " schema gaps [--limit N] [--json]");
        System.out.println();
        System.out.println("  sql-cli " + a + " schema import --from-db [--schema S] [--table S.T] [--batch-size N] [--force-overwrite]");
        System.out.println("  sql-cli " + a + " schema import --input <snapshot.json> [--merge]");
        System.out.println("  sql-cli " + a + " schema import status|resume|reset");
        System.out.println();
        System.out.println("  sql-cli " + a + " schema export [--output <path>] [--force]");
        System.out.println();
        System.out.println("  sql-cli " + a + " schema edit --table <name> --description '...' [--business-name N] [--add-tag T] [--grain TEXT]");
        System.out.println("  sql-cli " + a + " schema edit --column <schema.table.column> --description '...' [--business-name N] [--semantic-type TYPE] [--enum-values '0=待付款,1=已付款'] [--format T] [--example E] [--add-tag T] [--add-constraint C]");
        System.out.println();
        System.out.println("  sql-cli " + a + " schema add-term <name> [--display-name N] [--aliases A,B]"
                + " [--map ...] [--primary-target REF] [--filter COND]");
        System.out.println("  sql-cli " + a + " schema add-relation --type <type> --from <ref> --to <ref> [--join EXPR] [--confidence 0-1] [--verified]");
        System.out.println();
        System.out.println("  sql-cli " + a + " schema lineage <schema.table.column> [--upstream|--downstream] [--depth N] [--json]");
        System.out.println("  sql-cli " + a + " schema add-lineage --target <ref> --source <ref>[,<ref>...] --through T [--kind K] [--expression E]");
        System.out.println("  sql-cli " + a + " schema remove-lineage --target <ref> [--through T] | --id <lineage id>");
        System.out.println();
        System.out.println("  sql-cli " + a + " schema add-metric <name> --expression EXPR [--filters F] [--business-name N] [--aliases A,B]");
        System.out.println("      [--grain-column <ref>] [--grains day,week,month] [--dimensions <ref>,...] [--join-path relId|type,...] [--additivity V]");
        System.out.println("  sql-cli " + a + " schema add-metric <name> --numerator EXPR --denominator EXPR [--numerator-filters F] [--denominator-filters F]  # 比率口径");
        System.out.println("  sql-cli " + a + " schema metrics [--json]");
        System.out.println("  sql-cli " + a + " schema metric <name> [--json]");
        System.out.println("  sql-cli " + a + " schema expand-metric <name> [--grain day] [--dimensions <ref>,...] [--time-from D] [--time-to D] [--json]");
        System.out.println("  sql-cli " + a + " schema search-eval --cases <file.yaml> [--json]");
        System.out.println("  sql-cli " + a + " schema task-eval --cases <tasks.yaml> --answers <answers.yaml> [--json]");
        System.out.println("  sql-cli " + a + " schema data-quality --table <schema.table> --yes [--months N] [--json]");
        System.out.println();
        System.out.println("  sql-cli " + a + " schema index rebuild");
        System.out.println("  sql-cli " + a + " schema index status");
        System.out.println("  sql-cli " + a + " schema diagram [--output <dir>]");
        System.out.println("  sql-cli " + a + " schema design review --ddl <file> --rules <ruleset.yaml> [--json]");
        System.out.println("  sql-cli " + a + " schema migration lint --file <migration.sql> --rules <ruleset.yaml> [--json]");
        System.out.println();
    }

    public boolean printActionHelp(String requestedAction) {
        String a = alias != null ? alias : "<alias>";
        String action = requestedAction == null ? "" : requestedAction.toLowerCase(Locale.ROOT);
        String help = switch (action) {
            case "list" -> """
                    用途: 列出图谱工作区中的全部表，按 schema 分组显示。
                    用法: sql-cli %s schema list [--json]

                    选项:
                      -j, --json    输出 JSON 数组，适合脚本或 Agent 消费。

                    示例:
                      sql-cli %s schema list
                      sql-cli %s schema list --json
                    """.formatted(a, a, a);
            case "describe" -> """
                    用途: 查看一张表的字段、主键、注释、行数以及关联关系。候选关系带 [候选] 前缀。
                    用法: sql-cli %s schema describe <schema.table> [--column C] [--refresh-row-count] [--json]

                    位置参数:
                      <schema.table>  完整表名，例如 qm_pct.pct_contract_case。

                    选项:
                      --column C           只看这一列，展开它的全部细节：格式、示例值、默认值、
                                           置信度、是否有人复核过。紧凑列表为了排得下砍掉了这些，
                                           而它们正是「要不要信这条语义」的依据。
                      --refresh-row-count  对该表跑一次 COUNT(*)，结果写回图谱的 rowEstimate。
                      -j, --json           输出包含 table、columns、relations、completeness 的 JSON。

                    读输出时注意三处（它们是给 agent 判断「这张表能不能直接用」的）:
                      语义: 一行完整度。**结尾那句最重要**——「从未有人补充」表示图谱里只有
                            库注释，没人写过业务语义，直接按字段名理解之前先去读代码；
                            「最近 X 由 Y 补充」才说明这张表被人整理过。
                            图谱是按需生长的，没有描述不等于这张表简单。
                      Grain: 一行代表什么。它和下面的关系基数一起决定 join 之后还能不能直接 SUM。
                      关系:  每条带基数（N:1 / 1:N / 基数未知）。**基数未知就是真的不知道**，
                            不要默认按 N:1 处理。本表处在两条以上一对多关系的「一」侧时会
                            额外提示扇形陷阱——同时 join 两条会把行数乘开，SQL 照跑不报错。

                    示例:
                      sql-cli %s schema describe qm_pct.pct_contract_case
                      sql-cli %s schema describe qm_pct.pct_contract_case --column status
                      sql-cli %s schema describe qm_pct.pct_contract_case --refresh-row-count
                    """.formatted(a, a, a, a, a);
            case "query" -> """
                    用途: 从指定表开始，按关系边向外展开关联表。
                    用法: sql-cli %s schema query <schema.table> [--depth N] [--json]

                    位置参数:
                      <schema.table>  展开的起点表。

                    选项:
                      -d, --depth N   展开深度；默认 1。例如 2 表示继续展开相邻表的关系。
                      -j, --json      输出关系边 JSON。

                    示例:
                      sql-cli %s schema query qm_pct.pct_contract_case --depth 2
                      sql-cli %s schema query qm_pct.pct_contract_case -d 2 --json
                    """.formatted(a, a, a);
            case "path" -> """
                    用途: 查找两张表之间可达的关系路径。
                    用法: sql-cli %s schema path <schema.table1> <schema.table2> [--json]

                    位置参数:
                      <schema.table1>  起点表。
                      <schema.table2>  终点表。

                    选项:
                      -j, --json       输出路径及每一跳的结构化 JSON。

                    示例:
                      sql-cli %s schema path qm_pct.orders qm_pct.users
                    """.formatted(a, a);
            case "search" -> """
                    用途: 按名称、数据库注释、业务描述和术语搜索图谱；存在索引时优先使用索引。
                          中文按二元切词，多个词可跨字段命中（「买家 手机」能命中业务名「买家手机号」）。
                          结果默认包含候选对象，带 [候选] 标记。
                    用法: sql-cli %s schema search <keyword> [--limit N] [--json]

                    位置参数:
                      <keyword>      搜索词；包含空格时请使用引号。

                    选项:
                      --limit N      条数上限：文本输出默认 15 组、JSON 默认 50 条，0 表示不限。
                      -j, --json     输出分数、命中字段 matchedField / 命中原文 matchedText、
                                     数据库注释 comment、业务描述 description、业务名 businessName、
                                     语义类型 semanticType 和候选标记 candidate。comment 只由导入写，
                                     description 是人/Agent 补的解读，两者分字段返回。

                    文本输出按表折叠：表命中一行（业务名 | 描述 | 注释 | 列数），该表的命中列折叠成摘要行；
                    要看完整列结构用 schema describe。

                    示例:
                      sql-cli %s schema search "订单买家"
                      sql-cli %s schema search buyer --json
                    """.formatted(a, a, a);
            case "import" -> """
                    用途: 从实时数据库或 JSON 快照创建/更新图谱工作区，也可管理导入任务。
                    用法:
                      sql-cli %s schema import --from-db [options]
                      sql-cli %s schema import --input <snapshot.json> [--merge]
                      sql-cli %s schema import status|resume|reset [--json]

                    数据库导入选项:
                      --from-db            从 alias 指向的数据库读取元数据。
                      -s, --schema NAME    只导入指定 schema；Oracle 默认使用 alias 用户名。
                      --table S.T          只导入指定表，例如 qm_pct.pct_contract_case。
                      --batch-size N       每批处理的表数量；默认 20。
                      --merge              与现有工作区合并。
                      --force-overwrite    允许覆盖已有对象。

                    快照导入选项:
                      -i, --input FILE     输入 workspace export JSON 文件。
                      --merge              与现有工作区合并；不指定时使用快照内容。

                    任务动作枚举:
                      status               查看最近一次导入状态；可配合 --json。
                      resume               从检查点恢复失败或中断的数据库导入。
                      reset                清除导入任务状态。

                    示例:
                      sql-cli %s schema import --from-db --schema qm_pct --batch-size 20
                      sql-cli %s schema import --from-db --table qm_pct.pct_contract_case
                      sql-cli %s schema import --input workspace-export.json --merge
                      sql-cli %s schema import status --json
                    """.formatted(a, a, a, a, a, a, a);
            case "export" -> """
                    用途: 将当前图谱工作区导出为可再次导入的 JSON 快照。
                    用法: sql-cli %s schema export [--output FILE] [--force]

                    选项:
                      -o, --output FILE  输出路径；默认写入当前工作区的 workspace-export.json。
                      -f, --force        目标文件存在时覆盖；未指定时拒绝覆盖。

                    示例:
                      sql-cli %s schema export
                      sql-cli %s schema export -o /tmp/tour-prd-graph.json --force
                    """.formatted(a, a, a);
            case "edit" -> """
                    用途: 人工补充或修正表、字段的业务元数据。一次只能选择 --table 或 --column。
                    用法:
                      sql-cli %s schema edit --table <schema.table> [options]
                      sql-cli %s schema edit --column <schema.table.column> [options]

                    目标选项:
                      --table S.T        编辑表，例如 qm_pct.orders。
                      --column S.T.C     编辑字段，例如 qm_pct.orders.buyer_id。

                    修改选项:
                      --description TEXT 设置业务描述，写入 description 字段；不影响数据库注释
                                         comment——那一份只由导入维护。发现库注释是错的，
                                         正确修法是去库里改再重新导入；同时把正确的业务含义
                                         写在这里是这个字段的正当用途，两者会并排显示，
                                         冲突一眼可见。别做的是只写这里却不告诉人库注释有问题。
                      --business-name N  设置业务名（简短称呼，如「买家ID」），--table/--column 都可用。
                      --semantic-type T  设置语义类型；仅用于 --column，取值见下方合法列表。
                                         固定枚举而非自由文本——规则引擎（PolicyEvaluator 的
                                         semanticTypeAny）和搜索加权都靠它做精确匹配，写自由文本
                                         等于没写，一条规则都命中不了。传空串清空。
                      --add-tag TAG      添加标签，例如 PII、核心表。
                      --add-constraint C 添加字段约束说明；仅用于 --column。
                      --redundant-of S.T.C  标注本列是 S.T.C 的冗余副本，权威源在那一列；
                                         仅用于 --column。传空串清空。不做规范化建议
                                         （不会提示"应该拆表"），只标注权威源在哪，
                                         search 命中这一列时会带出这条提示。
                      --boost N          人工搜索权重（>0，默认 1.0），只影响 search 排序；
                                         核心表/高频字段调高（如 1.5），废弃或干扰项调低（如 0.5），
                                         传 1 恢复默认。
                      --grain TEXT       表粒度，说明"一行代表什么"，例如"一行一个订单商品"；
                                         仅用于 --table。自由文本，人工维护，导入不覆盖。
                                         policy 规则 fact_table_grain_required 的校验对象——事实表
                                         （打了 fact 标签）建议都声明。传空串清空。
                                         （expand-metric 的扇形陷阱检查尚未实现，见 9 月清单 F5。）

                    --semantic-type 合法取值（值(中文标签)）:
                      %s

                    值域选项（仅用于 --column，三选一，按字段实际情况填）:
                      --enum-values LIST 封闭值域，逗号分隔，每项 值=含义，例如
                                         "0=待付款,1=已付款,2=已取消"；含义不知道时只写值。
                                         整体替换而不是追加，传空串表示清空。
                                         值多到不该内联的（字典表）不要写这里，改为建一条
                                         指向字典表的关系，见 add-relation --help。
                      --format TEXT      值无限但有格式时的说明，例如 "SM4 密文，前缀 ENC#240606#"。
                      --example VALUE    追加一个样例值，适合无枚举也无固定格式的字段。

                    值域从哪来（按成本排序）: 列注释常常已经写了「0-待付款 1-已付款」，
                    直接抄；没有就读代码里的枚举类或字典表；都没有就
                    SELECT <列>, COUNT(*) ... GROUP BY <列> 采样，采样只能给值、不给含义。

                    示例:
                      sql-cli %s schema edit --table qm_pct.orders --description "订单主表" --business-name "订单" --add-tag core
                      sql-cli %s schema edit --table qm_pct.order_item --grain "一行一个订单商品" --add-tag fact
                      sql-cli %s schema edit --column qm_pct.orders.buyer_id --description "买家ID" --business-name "买家ID" --semantic-type ref_id --example 10001 --add-constraint "非空"
                      sql-cli %s schema edit --column qm_pct.orders.status --enum-values "0=待付款,1=已付款,2=已发货,3=已完成,4=已取消"
                    """.formatted(a, a, semanticTypeOptionsHelp(), a, a, a, a);
            case "stats" -> """
                    用途: 查看 schema、表、字段、术语、关系和校验问题数量。
                    用法: sql-cli %s schema stats [--json]

                    选项:
                      -j, --json    输出结构化统计数据。

                    示例:
                      sql-cli %s schema stats --json
                    """.formatted(a, a);
            case "validate" -> """
                    用途: 校验图谱对象、关系端点和引用完整性，并保存校验结果。
                    用法: sql-cli %s schema validate [--json]

                    选项:
                      -j, --json    输出 validation issue 数组。

                    示例:
                      sql-cli %s schema validate
                      sql-cli %s schema validate --json
                    """.formatted(a, a, a);
            case "eval" -> """
                    用途: 评估图谱质量，产出一份可以直接执行的工作队列。结果落运行库，绑当前 revision，
                          能回答「这次比上次好了还是坏了」。不连数据库。
                    用法: sql-cli %s schema eval [--json]

                    报什么:
                      硬错误   term.orphan（术语既无描述也无映射）、desc.dead-ref（描述提到图谱里
                               不存在的对象）、rel.endpoint-mismatch（关系两端类型不兼容）。
                               每条自带一条可粘贴执行的修复命令。**只有硬错误判失败（退出码 1）**。
                      覆盖率   表描述 / 列描述 / 列值域 / 术语有映射四个比例，只报告不判定，
                               没有阈值也没有综合评分——它们是给趋势用的。

                    单个探针报超过 50 条时不再逐条列，改报一条「这个探针用错了对象」：
                    一类发现多到人处理不完，说的就不是图谱有那么多处错。

                    选项:
                      -j, --json    输出 evaluationId / metrics / findings 结构。

                    示例:
                      sql-cli %s schema eval
                      sql-cli %s schema eval --json
                    """.formatted(a, a, a);
            case "gaps" -> """
                    用途: 补图谱之前先跑一次——按「被搜到次数多但语义缺失」排序，
                          列出最该补的对象；搜空的查询词单列一段。
                          数据来自交互日志（agent_interaction）里 describe/path/search 的命中记录，
                          没有人碰过的对象不出现在这里，不是「没有缺口」，是「没人问过」。
                    用法: sql-cli %s schema gaps [--limit N] [--json]

                    报什么:
                      缺口对象   被读取过但从没被人整理过的表（无描述、无业务名、无 grain），
                                 按被读取次数降序；每条自带一条可粘贴执行的修复命令。
                      搜空查询   命中数为 0 的 search 关键词，按出现次数降序——这是最准的
                                 补图谱优先级信号，搜的人真的要这个词，图谱里就是没有。

                    选项:
                      --limit N    每段最多列几条（默认 20）。
                      -j, --json   输出 gaps / missedQueries 结构。

                    示例:
                      sql-cli %s schema gaps
                      sql-cli %s schema gaps --limit 10 --json
                    """.formatted(a, a, a);
            case "add-term" -> """
                    用途: 新增或更新业务术语，并将术语映射到表或字段。
                    用法: sql-cli %s schema add-term <name> [options]

                    位置参数:
                      <name>               稳定的术语标识，例如 buyer；重复执行会更新同一术语。

                    选项:
                      --display-name TEXT  展示名称，例如 "买家"。
                      --description TEXT   业务定义。
                      --aliases A,B        逗号分隔的同义词，例如 "购买人,客户"。
                      --negative-aliases A,B  逗号分隔的负向词：查询恰好等于其中一条时，
                                           这条术语不参与匹配——用来挡跨域撞词
                                           （比如「扫码」挂着巡检域点位，但「扫码打卡」
                                           在别的域另有所指，负向词让它别抢那条查询）。
                      --map REF,...        逗号分隔的映射目标；支持 schema.table 或 schema.table.column。
                      --primary-target REF 入口对象。**指向一张表这条术语就是一个场景**，
                                           搜索命中时展开成子图（入口表 + 映射的表 + 它们之间的关系）；
                                           指向列或不给，就退化成同义词路由。
                      --filter COND        场景专属过滤，可重复。一个条件一个 --filter，
                                           不要用逗号拼——条件里本来就有逗号。

                    说明:
                      --map 会创建 type=term_mapping 的关系，from 为术语，to 为表或字段。

                      --filter 只放**换个场景就不成立**的条件:
                        进  state IN (0,1,6)         未完结的报事；回访场景要的是 IN (6,7)
                        进  subject_id = :subjectId  必填参数，值运行时给，但位置必须有——
                                                     漏掉项目隔离列是查到别的项目的数据
                        不进 del_flag = 0            换任何场景都成立，属于全库约定，走 policy 规则

                      --filter 是整体替换不是追加：一组条件要共同成立，追加会把上次写错的
                      永久留下。不给 --filter 就不动原有的。

                      同义词、映射**两样全空**的术语会被拒收——只有 --description 没有
                      --map / --aliases 跟 businessName 完全重叠，而且多一个对象要维护，
                      还占着检索最高权重档。报错会给出改用 schema edit --business-name
                      的具体命令。

                    示例:
                      sql-cli %s schema add-term buyer --display-name "买家" --description "下单用户" --aliases "购买人,客户" --map qm_pct.orders.buyer_id
                      sql-cli %s schema add-term 报事 --aliases "工单,报修" --map app.report,app.report_assign \
                          --primary-target app.report --filter "state IN (0,1,6)" --filter "subject_id = :subjectId"
                    """.formatted(a, a, a);
            case "add-relation" -> """
                    用途: 新增或更新两个图谱节点之间的关系。
                    用法: sql-cli %s schema add-relation --type TYPE --from REF --to REF [options]

                    必填选项:
                      --type TYPE          关系类型枚举，见下方列表；区分大小写，使用小写。
                      --from REF           起点。常用格式 schema.table 或 schema.table.column，也支持 term 名称/完整节点 ID。
                      --to REF             终点，格式同 --from。

                    可选项:
                      --join EXPR          保存关联表达式作为元数据，例如 "o.buyer_id = u.id"；不会执行该 SQL。
                      --confidence N       置信度，范围 0.0~1.0；默认 max(0.7, 该类型最低值)。
                      --verified           标记该关系已人工确认。

                    TYPE 枚举、合法端点和最低置信度:
                      join_observed         column -> column，0.5；人和 Agent 维护的表间关联，导入不会覆盖。
                      term_mapping          term -> table|column，0.5；通常使用 add-term --map 创建。
                      foreign_key           column -> column，1.0；数据库声明的外键，仅导入维护，不允许手工创建。

                    REF 示例:
                      表:   qm_pct.orders
                      字段: qm_pct.orders.buyer_id
                      术语: buyer 或 term:%s:buyer

                    示例:
                      sql-cli %s schema add-relation --type join_observed --from qm_pct.orders.buyer_id --to qm_user.users.id --confidence 0.9 --verified
                      sql-cli %s schema add-relation --type join_observed --from qm_pct.orders.buyer_id --to qm_user.users.id --join "o.buyer_id = u.id"
                    """.formatted(a, a, a, a);
            case "lineage" -> """
                    用途: 查询字段级血缘，从指定列沿血缘记录向上游（默认）或下游展开。
                    用法: sql-cli %s schema lineage <schema.table.column> [--upstream|--downstream] [--depth N] [--json]

                    位置参数:
                      <schema.table.column>  起点列，也接受完整列 id（column:alias:...）。

                    选项:
                      --upstream       查上游：该列由哪些列推导而来（默认方向）。
                      --downstream     查下游：该列参与推导了哪些列。
                      -d, --depth N    展开深度，默认 1。
                      -j, --json       输出血缘记录 JSON（target/sources/expression/through/status）。

                    示例:
                      sql-cli %s schema lineage app.order_summary.total_amount --depth 2
                      sql-cli %s schema lineage app.orders.price --downstream --json
                    """.formatted(a, a, a);
            case "add-lineage" -> """
                    用途: 写入一条字段级血缘：这一列的值是哪段代码、从哪几列写进来的。
                          以 agent 身份写入，初始状态为候选（candidate），等待人工发布。
                          id 由 (target, through) 决定：一段代码写一列只有一条记录，重跑是更新不是新增。
                    用法: sql-cli %s schema add-lineage --target <ref> --source <ref>[,<ref>...] --through T [--kind K] [--expression E]

                    必填选项:
                      --target REF       派生列，恰好 1 个；格式 schema.table.column。
                      --source REF,...   改了它 target 会变的全部列，逗号分隔。rule 的条件列、
                                         CASE 的选择列都算源，不单独区分。
                      --through TEXT     靠什么产生：视图名 / ETL 作业 / 类名.方法 / Mapper statement id。
                                         它是 identity 和 rule 的全部价值——没有它 agent 知道「会影响」
                                         却不知道去哪改。

                    可选项:
                      --kind K           怎么写的，四选一。判据是读它的 agent 下一步做什么不同：
                                           identity        原样抄来（b.setX(a.getX())）→ 当快照，当前值去源列
                                           transformation  同一行算出（prefix+date+seq、CASE 三选一）→ 用公式别猜
                                           aggregation     多行汇总（遍历子表、SUM/COUNT）→ 会与明细漂移，核对时重算
                                           rule            值不来自任何列（now()/常量/用户输入），源列只决定写不写
                                                           → 改源列前先看 through，否则这处写入静默失效
                                         不给只推断两种：单源无表达式 → identity，有表达式 → transformation。
                                         aggregation / rule 从形状认不出来，必须显式。
                      --expression TEXT  transformation / aggregation 必填，写实际表达式，CASE WHEN 照抄；
                                         rule 写规则原话；identity 不写。

                    闸门（退出码 1）:
                      源含目标本身          列不是自己的上游；「谁在写」这类事实写进那一列的 --description
                      identity 多源或带表达式   改用 --kind transformation
                      transformation / aggregation 无表达式
                      缺 --through

                    示例:
                      sql-cli %s schema add-lineage --target app.plan_point.sort --source app.task_point.sort \\
                        --through "ErpPropTaskPlanServiceImpl.generatorPlan 任务生成快照"
                      sql-cli %s schema add-lineage --target app.plan_record.inspect_end_time --source app.plan_point.is_result \\
                        --kind rule --expression "该任务下全部点位 is_result=1 时写入当前时间" \\
                        --through "ErpPropPlanPointServiceImpl.submit"
                    """.formatted(a, a, a);
            case "remove-lineage" -> """
                    用途: 删除一条字段血缘。走同一条变更管线：graphApproval 为 manual 的别名上排成审批。
                    用法: sql-cli %s schema remove-lineage --target <ref> [--through T]
                          sql-cli %s schema remove-lineage --id <lineage id>

                    选项:
                      --target REF     目标列。该列只有一条血缘时直接删；多于一条会列出来让你带 --through 挑。
                      --through TEXT   与 --target 配合，精确到那一段代码。
                      --id ID          `schema lineage --json` 或 UI 里的 lineage:... id。

                    示例:
                      sql-cli %s schema remove-lineage --target app.plan_point.sort
                      sql-cli %s schema remove-lineage --id lineage:app:app.plan_point.sort:1a2b3c4d
                    """.formatted(a, a, a, a);
            case "add-metric" -> """
                    用途: 创建或更新一条 BI 指标定义（GMV、复购率……）。以 agent 身份写入，
                          初始状态为候选（candidate），等待人工发布。同名重写是幂等更新。
                          expression / filters 是不透明字符串，原样存下，不做语法校验——
                          解析属于 expand-metric 消费的时候，不属于这里。
                    用法: sql-cli %s schema add-metric <name> --expression <EXPR> [options]
                          sql-cli %s schema add-metric <name> --numerator EXPR --denominator EXPR [options]

                    位置参数:
                      <name>               稳定标识，如 gmv_paid；重复执行会更新同一条指标。

                    必填选项（首次创建时，二选一）:
                      --expression EXPR    聚合表达式，如 "SUM(order.amount)"；单一口径用这个。
                      --numerator/--denominator EXPR  比率口径（复购率、转化率……）分子/分母各自
                                           的聚合表达式；两者都声明才算比率指标，见下方"比率口径"。

                    可选项:
                      --filters TEXT       口径过滤条件，如 "status IN (2,3)"；决定"算不算取消单"
                                           这类争议，是整个指标的存在理由，建议明确写。
                                           单一口径（--expression）用这个；比率口径见下面
                                           --numerator-filters/--denominator-filters。
                      --business-name N    业务名，如 "已支付GMV"。
                      --aliases A,B        同义词，逗号分隔，检索靠它命中业务黑话。
                      --grain-column REF   时间列，格式 schema.table.column。
                      --grains G,G,...     该时间列支持展开到的粒度，如 "day,week,month"；
                                           自由文本，不是枚举，跟着业务习惯写。
                      --dimensions REF,... 允许按哪些列切，逗号分隔的 schema.table.column 列表；
                                           整体替换，传空串清空。
                      --join-path STEP,... 跨表时的 JOIN 路径，人工权威声明，不是自动发现。
                                           每步格式 relationId|joinType（joinType 为 inner 或 left），
                                           relationId 从 schema describe/path --json 的关系对象里取；
                                           整体替换，传空串清空。
                      --additivity V       可加性标注，取值 additive / semi_additive / non_additive；
                                           比率指标（声明了 numerator/denominator）不用填，
                                           expand-metric 会强制按 non_additive 处理，填了也不生效。
                                           真正需要人填的是 semi_additive（库存余额这类跨时间
                                           不可加的快照）——expand-metric 遇到 semi_additive 指标
                                           请求 --grain 时间分桶会直接报错，而不是生成一段
                                           看起来对、实际把快照值错误相加的 SQL。

                    比率口径（分子/分母）:
                      --numerator EXPR           分子聚合表达式，如 "COUNT(DISTINCT user_id)"。
                      --numerator-filters TEXT   只卡分子的过滤条件，如 "order_count >= 2"；
                                                 不影响分母。
                      --denominator EXPR         分母聚合表达式，通常和分子同源但不加限定
                                                 （"全部用户"）。
                      --denominator-filters TEXT 只卡分母的过滤条件，一般留空。
                      两者必须成对声明才是完整的比率指标；只声明一个 expand-metric 会报错。
                      展开时分子分母各自按自己的 filters 独立聚合再按 grain/dimensions JOIN
                      回同一行，不会互相污染对方的过滤条件。

                    示例:
                      sql-cli %s schema add-metric gmv_paid --expression "SUM(orders.amount)" \\
                        --filters "status IN (2,3)" --business-name "已支付GMV" \\
                        --grain-column app.orders.created_at --grains day,week,month \\
                        --dimensions app.orders.channel
                      sql-cli %s schema add-metric repeat_purchase_rate \\
                        --numerator "COUNT(DISTINCT app.orders.user_id)" --numerator-filters "order_count >= 2" \\
                        --denominator "COUNT(DISTINCT app.orders.user_id)" \\
                        --business-name "复购率" --dimensions app.orders.channel
                    """.formatted(a, a, a, a);
            case "metrics" -> """
                    用途: 列出图谱工作区中的全部指标。
                    用法: sql-cli %s schema metrics [--json]

                    示例:
                      sql-cli %s schema metrics --json
                    """.formatted(a, a);
            case "metric" -> """
                    用途: 查看一条指标的完整定义。
                    用法: sql-cli %s schema metric <name> [--json]

                    示例:
                      sql-cli %s schema metric gmv_paid --json
                    """.formatted(a, a);
            case "expand-metric" -> """
                    用途: 把一条指标按请求的粒度和维度展开成可执行 SQL 骨架——这是指标定义
                          唯一兑现价值的一步。expression / filters 原样拼进 SELECT / WHERE，
                          JOIN 直接使用关系边上的 joinExpression，不重新拼、不解析。
                          引用的表/列/关系在图谱里不存在时明确报错，不生成半截 SQL。
                    用法: sql-cli %s schema expand-metric <name> [options]

                    位置参数:
                      <name>               指标标识。

                    可选项:
                      --grain G            请求的时间粒度，必须在该指标声明的 grains 范围内；
                                           不传则不按时间分桶。
                      --dimensions REF,... 要切的维度，必须是该指标声明的 dimensions 子集；
                                           不传则不按维度拆分。
                      --time-from D        时间下界（含），拼进 WHERE；需要该指标声明了
                                           grain.timeColumn 才生效。
                      --time-to D          时间上界（不含）。
                      -j, --json           输出 {metric, sql} 结构化结果。

                    示例:
                      sql-cli %s schema expand-metric gmv_paid --grain month --dimensions app.orders.channel \\
                        --time-from 2026-01-01 --time-to 2026-02-01
                    """.formatted(a, a);
            case "search-eval" -> """
                    用途: 用评估集度量 schema search 的命中率（Top1/Top5/MRR），列出未命中 case。
                          全部 case 有命中退出码 0，存在完全未命中的 case 退出码 1（方便 CI）。
                    用法: sql-cli %s schema search-eval --cases <file.yaml> [--json]

                    评估集格式 (YAML):
                      cases:
                        - query: "买家手机"
                          expect:
                            - column:%s:app.orders.buyer_phone
                      expect 是图谱对象 id 列表，任意一个命中即算该 case 命中。
                      模板见 docs/search-eval-cases.example.yaml。

                    选项:
                      --cases FILE  评估集 YAML 文件路径。
                      -j, --json    输出结构化结果（每 case 的 bestRank/top1/top5/hit 与汇总指标）。

                    示例:
                      sql-cli %s schema search-eval --cases docs/search-eval-cases.example.yaml
                    """.formatted(a, a, a);
            case "task-eval" -> """
                    用途: 任务级评估的**评分器**。测的是「找到之后用对了没有」——
                          agent 选的表集合 / JOIN 对不对、有没有踩中已知的坑。
                          全部 case 通过退出码 0，否则 1（方便 CI）。
                    用法: sql-cli %s schema task-eval --cases <tasks.yaml> --answers <answers.yaml> [--json]

                    这条命令不跑 agent。跑 agent 是外部 harness 的事:
                      每个 case 起两个 agent，产出填进 answers.yaml 的两组——
                        control  只给图谱和 skill
                        cheat    额外给正确的表集合（只藏 JOIN 和值域）
                      两组的意义是归因。没有对照，通过率是个看了也不知道该做什么的数字:
                        对照✗ 作弊✓  图谱信息不足 → 补描述 / 术语 / 检索
                        对照✗ 作弊✗  skill 问题   → 改 references/ 里对应那一节
                        对照✓        通过

                    作答格式 (YAML):
                      answers:
                        <case id>:
                          control:
                            tables: [erp_prop_task_plan, erp_prop_plan_point]
                            joins:  ["erp_prop_plan_point.plan_id → erp_prop_task_plan.id"]
                            sql: "SELECT ..."            # 可选
                            values:                      # 关键字段取值，可选
                              erp_prop_task_plan.is_scan: "1=必须扫码"
                          cheat: { ... }
                      模板见 docs/task-eval-answers.example.yaml，评估集见
                      docs/task-eval-erp-inspection.yaml。

                    判分口径:
                      tables    期望表集合是作答的子集（多带一张不算错，少一张算错），按表名末段比
                      joins     每条期望连接的两端都出现在作答的同一条 JOIN 或 SQL 里，且左端在前
                      pitfalls  一条都不能踩中——这是最要紧的一项。只比表和 JOIN 测不出
                                「选错了名字相近的那张表」，因为那种 SQL 照样能跑

                    选项:
                      --cases FILE    评估集 YAML（tasks 数组，每条带 expect.pitfalls）。
                      --answers FILE  作答 YAML（answers 映射，每个 case 下 control / cheat）。
                      -j, --json      输出结构化结果（逐 case 的归因、缺表、缺 JOIN、踩中的坑）。

                    示例:
                      sql-cli %s schema task-eval --cases docs/task-eval-erp-inspection.yaml \\
                        --answers docs/task-eval-answers.example.yaml
                    """.formatted(a, a);
            case "value-domain" -> """
                    用途: 值域三源交叉——库里的实际分布 × 字段注释 × 代码枚举，
                          交叉出一份可信值域，**提交审批**而不是直接写图谱。
                    用法: sql-cli %s schema value-domain --table <schema.table>
                                 [--column <name> --code-enum '0=待付款,1=已付款'] [--json]

                    三个来源各有短板，单独一个都不够:
                      库里实际分布  是事实，但不含语义（只知道有 0 和 1，不知道哪个是已付款）
                      字段注释      有语义，但可能过期、可能不全
                      代码枚举      有语义且通常最新，但 CLI 读不到代码——你读完用 --code-enum 带进来

                    交叉后四种结论，每种都是一个可操作的信号:
                      三源一致              值域确认
                      库里有、声明里没有     注释过期，或者有脏数据    ← 最要紧
                      声明里有、库里没出现   该值从未使用（也可能是测试库数据不全）
                      全 NULL / 只有一个值   这个字段实际没在用，别拿它做判断

                    这是机器推断，不是你读过代码:
                      这份值域是机器从一次采样推断的，采样看不到的值它不知道。
                      值域错了的后果是 Agent 按错的条件写 WHERE——SQL 照跑、数字照出、不报错。
                      要不要人裁决由别名的 graphApproval 决定（manual 排队 / auto 直接写），
                      这个命令不自己开小灶。只为**有发现**的字段建条目，三源一致的不打扰人。

                    选项:
                      --table  T   要分析的表（必填），schema.table
                      --column C   只分析这一列；不给就整表扫，高基数列自动跳过
                      --code-enum  这一列在代码里的枚举，格式同 --enum-values；必须配 --column
                      --basis      这份枚举**从哪读来的**，一句话。给了 --code-enum 就必填——
                                   审批人看不到你读的代码，不交代来源他只能盲批或盲拒
                      -j, --json   输出结构化结果（每列的判定 + 审批号）

                    示例:
                      sql-cli %s schema value-domain --table app.orders
                      sql-cli %s schema value-domain --table app.orders --column status \\
                          --code-enum '0=待付款,1=已付款,2=已发货'
                    """.formatted(a, a, a);
            case "data-quality" -> """
                    用途: 数据层质量探针——连库跑只读聚合，回答重构最常问的三句话。
                          这三句**代码永远查不到答案**：代码只能说有没有人写这行，
                          不能说这行还跑不跑。
                    用法: sql-cli %s schema data-quality --table <schema.table> --yes [--months N] [--json]

                    四条探针:
                      data.column-unused    整列全 NULL，或非空值 ≥99%% 是同一个取值
                                            → 这个字段能删吗（warning，提议标「未启用」，不自动写）
                      data.enum-undeclared  库里出现图谱 enumValues 之外的取值
                                            → **error**，agent 按图谱写 WHERE ... IN 会静默漏数据
                      data.orphan-fk        图谱里已验证的关系边，from 端的值在对端找不到
                                            → 按它写 INNER JOIN 会静默丢行（warning，给比例和样本数）
                      data.table-dead       行数为 0，或 create_time 类列的最大值早于 N 个月
                                            → 这张表还活着吗（warning）

                    和 schema eval 不合并: eval 问「图谱够不够准」，纯计算不连库；
                    这条问「库里的值对不对」，非连库不可。三层三套工具，别混成一个。

                    授权: 连库之前会把**将要执行的每一条查询**打出来。--yes 表示你看过了；
                          没有 --yes 且没有交互终端时直接退出（退出码 2），不会偷偷跑。

                    选项:
                      --table  T   要分析的表（必填），schema.table
                      --months N   多少个月没有新数据算「表可能已死」，默认 %d
                      --yes        跳过确认，直接执行上面列出的查询
                      -j, --json   输出结构化结果（evaluationId + findings）

                    结果落 graph_evaluation / graph_finding（source=data-quality，不带覆盖率指标，
                    趋势图不该拿它跟 schema eval 的点连线）。有 error 时退出码 1。

                    示例:
                      sql-cli %s schema data-quality --table app.orders --yes
                      sql-cli %s schema data-quality --table app.orders --months 6 --yes --json
                    """.formatted(a, com.sqlcli.profile.DataQualityProbes.DEFAULT_DEAD_MONTHS, a, a);
            case "index" -> """
                    用途: 管理图谱全文搜索索引。
                    用法: sql-cli %s schema index <ACTION> [--json]

                    ACTION 枚举:
                      rebuild    根据当前工作区完整重建索引。
                      status     查看索引版本、分片和文档数量；未指定 ACTION 时默认 status。

                    选项:
                      -j, --json  status 时输出索引 manifest JSON。

                    示例:
                      sql-cli %s schema index rebuild
                      sql-cli %s schema index status --json
                    """.formatted(a, a, a);
            case "diagram" -> """
                    用途: 根据图谱生成开发者关系图、概览和 manifest 文件。
                    用法: sql-cli %s schema diagram [--output DIR] [--json]

                    选项:
                      -o, --output DIR  输出目录；默认是工作区下的 developer-graph。
                      -j, --json        输出生成结果 manifest。

                    示例:
                      sql-cli %s schema diagram -o /tmp/tour-prd-graph
                      sql-cli %s schema diagram --json
                    """.formatted(a, a, a);
            case "policy" -> """
                    用途: 执行结构规则并查询 evaluation、violation 和 waiver 审计记录。
                    用法: sql-cli %s schema policy <command> [options]

                    命令:
                      check [--rules FILE] [--all] [--json]

                    check 未提供 --rules 时读取工作区 policy/bindings.yaml；显式提供时仅执行该规则文件。
                    规则的 scope 决定它跑在哪里：change 只对 design review / migration lint 送进来的
                    新 DDL 生效，all 还会检查存量图谱。命名类规则（naming_convention）默认是 change——
                    存量库上的表名不会为了规则去改，报出来也没人处理。--all 让这一次连它们一起跑。
                      evaluation list|show <evaluation-id> [--json]
                      violation list [--evaluation ID] [--json]
                      waiver add --rule ID --target ID --reason TEXT --expires-at DATETIME
                      waiver list [--active] [--json]
                      waiver revoke <waiver-id> --reason TEXT
                      rule add --category CAT --reason TEXT [--column SPEC]... [options] [--json]

                    rule add：把你从存量表里归纳出来的约定提议成规则。规则一旦启用就会在
                    CREATE / ALTER / DROP 执行前拦截（required / blocking 拒绝并回修法，advisory 只提示），
                    所以它跟写图谱一样走别名的 graphApproval：manual 进待审批、批准后才写入并启用；
                    auto 当场写入并留底。写进规则页固定分组对应的文件（结构规范 structure.yaml）。
                      --category   目前支持 required_business_columns；其余类别在规则页配置
                      --reason     必填。人在待审批队列里裁决的就是这句话，写清楚从哪几张表归纳的
                      --column     可重复。写法 name [type] [notnull|nullable] [default=X] [comment=X] [onupdate=X]
                                   字段名必须完全一致，没有别名——规则要的是新表统一叫这个名字
                                   例: --column "created_at datetime notnull comment=创建时间"
                      --enforcement advisory|required（默认 advisory：先看提示一段时间再升级成拦截）
                      --severity   info|warning|error（默认 warning）
                      --table-regex 只管名字匹配的表；--table-type base_table|view（默认只管 base_table）
                      --id / --title / --remediation 不给时按类别生成
                    示例:
                      sql-cli %s schema policy rule add --category required_business_columns \\
                        --reason "抽查 12 张业务表全部带 create_time/update_time，新表应沿用" \\
                        --column "created_at datetime notnull comment=创建时间" \\
                        --column "updated_at datetime notnull comment=更新时间"
                    """.formatted(a, a);
            case "design" -> """
                    用途: 将候选 DDL 投影到内存副本并执行结构规则审查；不会修改正式图谱。
                    用法: sql-cli %s schema design review --ddl <file> --rules <ruleset.yaml> [--json]
                    """.formatted(a);
            case "migration" -> """
                    用途: 审查 Migration 的结构变更和危险 DML；不会执行任何 SQL。
                    用法: sql-cli %s schema migration lint --file <migration.sql> --rules <ruleset.yaml>
                          [--rollback <file>] [--precheck <file>] [--postcheck <file>] [--json]
                    """.formatted(a);
            default -> null;
        };
        if (help == null) {
            System.err.println("Unknown schema action: " + requestedAction);
            System.err.println("Run 'sql-cli " + a + " schema --help' to list available actions.");
            return false;
        }
        System.out.println(help.stripTrailing());
        return true;
    }

    private GraphWorkspace loadWorkspaceIfExists() throws Exception {
        if (!workspaceStore.exists(alias)) {
            return null;
        }
        return workspaceStore.load(alias);
    }

    private int listTables(GraphWorkspace workspace) throws Exception {
        if (workspace == null) {
            return missingWorkspace();
        }
        if (jsonOutput) {
            CliJson.printSuccess(workspace.getTables().values());
            return 0;
        }

        Map<String, List<TableWorkspaceNode>> bySchema = new TreeMap<>();
        for (TableWorkspaceNode table : workspace.getTables().values()) {
            bySchema.computeIfAbsent(table.getSchema(), k -> new ArrayList<>()).add(table);
        }
        for (Map.Entry<String, List<TableWorkspaceNode>> entry : bySchema.entrySet()) {
            System.out.println("Schema: " + entry.getKey());
            entry.getValue().stream()
                    .sorted(Comparator.comparing(TableWorkspaceNode::getName, String.CASE_INSENSITIVE_ORDER))
                    .forEach(table -> {
                        String comment = table.getComment() != null ? " - " + table.getComment() : "";
                        System.out.printf("  %s (%s)%s%n", table.getName(), table.getTableType(), comment);
                    });
            System.out.println();
        }
        return 0;
    }

    private int describeTable(GraphWorkspace workspace) throws Exception {
        if (workspace == null) {
            return missingWorkspace();
        }
        if (tableName == null) {
            return usageError(
                    "Usage: sql-cli " + alias + " schema describe <table> [--json]");
        }

        TableWorkspaceNode table = workspace.getTableByQualifiedName(tableName);
        if (table == null) {
            return commandFailure("Table not found: " + tableName + similarTablesHint(workspace, tableName));
        }

        if (refreshRowCount) {
            int failure = refreshRowEstimate(workspace, table);
            if (failure != 0) {
                return failure;
            }
            workspace = workspaceStore.load(alias);
            table = workspace.getTableByQualifiedName(tableName);
        }

        List<ColumnWorkspaceNode> columns = findColumns(workspace, table);
        List<RelationWorkspaceEdge> relations = findRelations(workspace, table);
        Set<String> sm4Columns = resolveSm4Columns();
        RunStateStore runState = new RunStateStore();
        List<String> recentSql = runState.listRecentSqlForTable(alias, table.getName(), 5);
        List<QueryConventions.Hint> conventionHints = queryConventions().forTable(workspace, table);
        List<String> scenarios = scenariosContaining(workspace, table);
        // 读之前先取历史命中数——这次 describe 本身也会记进交互日志，
        // 顺序反了的话这张表会先把自己算进这次的命中数里。
        // action 名必须跟 SqlCli.actionOf 产出的一致（"schema " + 动作）。
        // 这里写 "search" 而入口写 "schema search" 的话，这个数永远是 0 而且不报错——
        // 正是那种「照跑、有结果、数是错的」静默错误，回归见 SchemaReadTelemetryTest。
        int searchHits = runState.countTargetHits(alias, ACTION_SEARCH, table.getId());
        DescribeCompleteness completeness = DescribeCompleteness.of(workspace, table, columns, relations, searchHits);
        // 交互明细：这次 describe 命中了哪张表，供 `schema gaps` 排优先级用。
        // 只填明细不落库——落库由 SqlCli.run 统一做，它才知道这次命令成没成。
        InteractionDetail.set(tableName, 1, null, List.of(table.getId()));

        // --column：只看一列的全部细节。全表输出已经 9000 字符，逐列铺开出处等于
        // 每次都替一个可能不发生的追问付费——所以细节走单列追问这条路。
        if (editColumn != null && !jsonOutput) {
            return describeSingleColumn(workspace, table, columns, sm4Columns);
        }

        if (jsonOutput) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("table", table);
            payload.put("columns", columns);
            payload.put("relations", relations);
            payload.put("sm4Columns", new ArrayList<>(sm4Columns));
            payload.put("recentSql", recentSql);
            payload.put("conventions", conventionHints.stream()
                    .map(QueryConventions.Hint::filter).toList());
            payload.put("scenarios", scenarios);
            payload.put("completeness", completeness.toMap());
            CliJson.printSuccess(payload);
            return 0;
        }

        System.out.println("Table: " + table.getName());
        System.out.println("Schema: " + table.getSchema());
        System.out.println("Type: " + table.getTableType());
        if (table.getComment() != null) {
            System.out.println("Comment(db): " + table.getComment());
        }
        if (table.getDescription() != null) {
            System.out.println("Description: " + table.getDescription());
        }
        if (table.getBusinessName() != null) {
            System.out.println("Business: " + table.getBusinessName());
        }
        System.out.println("Rows: " + (table.getRowEstimate() == null
                ? "未知（用 --refresh-row-count 采集）" : table.getRowEstimate()));
        if (nonBlank(table.getGrain())) {
            // 粒度决定 join 之后还能不能直接 SUM，跟行数放在一起看才有意义
            System.out.println("Grain: " + table.getGrain());
        }
        System.out.println(completeness.line());
        // 约定紧跟表头：它是要被写进 WHERE 的，埋在字段列表后面就等于没有
        for (QueryConventions.Hint hint : conventionHints) {
            System.out.println("约定过滤: " + hint.filter() + "    [规则 " + hint.ruleId() + "]");
        }
        // 反向索引：Agent 直接 describe 一张表时，得知道自己踩在哪个业务场景里，
        // 那个场景带着 join 路径和过滤，比在这里逐条关系去猜省事
        if (!scenarios.isEmpty()) {
            System.out.println("所属场景: " + String.join("、", scenarios)
                    + "（schema search <场景名> 看完整子图）");
        }
        if (isNonDefaultBoost(table.getBoost())) {
            System.out.println("Boost: " + table.getBoost());
        }

        // 血缘后缀跟 search 同一份：命中列的那一刻就知道这值是抄的 / 算的 / 汇总的 / 按规则写的。
        // 下游只给个数——「改它影响谁」是 --column 追问时才展开的细节。
        Map<String, String> describeLineageHints = lineageHints(workspace);
        LineageGraph describeLineageGraph = new LineageGraph(workspace.getLineage().values());
        System.out.println("\nColumns:");
        for (ColumnWorkspaceNode column : columns) {
            String pk = column.isPrimaryKey() ? " [PK]" : "";
            String nullable = column.isNullable() ? "" : " NOT NULL";
            String sm4 = sm4Columns.contains(column.getName().toLowerCase(Locale.ROOT)) ? " [SM4]" : "";
            // comment 是数据库注释镜像，description 是人/Agent 写的业务描述——两者来源不同，
            // 标签分开写，不合并成一句，免得看着像同一份东西。
            String comment = nonBlank(column.getComment()) ? " - [注释]" + column.getComment() : "";
            // 业务名 / 语义类型 / 值域早就在图谱里，文本输出一直没渲染——Agent 用文本
            // 入口时等于白存了这些字段。
            StringBuilder extra = new StringBuilder();
            if (column.getDescription() != null) {
                extra.append(" | 描述:").append(column.getDescription());
            }
            if (column.getBusinessName() != null) {
                extra.append(" | 业务:").append(column.getBusinessName());
            }
            if (column.getSemanticType() != null) {
                extra.append(" | 语义:").append(column.getSemanticType());
            }
            if (column.getValueHints() != null && column.getValueHints().getEnumValues() != null
                    && !column.getValueHints().getEnumValues().isEmpty()) {
                extra.append(" | 值域:").append(String.join(",", column.getValueHints().getEnumValues()));
            }
            if (isNonDefaultBoost(column.getBoost())) {
                extra.append(" | boost:").append(column.getBoost());
            }
            if (column.getRedundantOf() != null) {
                extra.append(" | 冗余副本，权威源:").append(columnDisplayName(column.getRedundantOf()));
            }
            // 字段级的闸门是 verified 位（见 ColumnWorkspaceNode 类头）：值照读，但要看得出
            // 是谁写的、有没有人认过。不标出来，闸门就等于不存在。
            if (column.hasUnconfirmedSemantics()) {
                extra.append(" [待确认]");
            }
            String lineageTag = lineageSuffix(describeLineageHints, table.getSchema(), table.getName(), column.getName());
            if (!lineageTag.isEmpty()) {
                extra.append(" ").append(lineageTag);
            }
            int downstreamCount = describeLineageGraph.downstream(
                    column.computeId(table.getSourceAlias(), table.getSchema(), table.getName()), 1).size();
            if (downstreamCount > 0) {
                extra.append(" | 下游 ").append(downstreamCount).append(" 处写入");
            }
            System.out.printf("  %s %s%s%s%s%s%s%n",
                    column.getName(),
                    column.getDataType().getRaw(),
                    pk,
                    nullable,
                    sm4,
                    comment,
                    extra);
        }
        if (!sm4Columns.isEmpty()) {
            System.out.println("  注: [SM4] 列已配置加密，仅支持等值/IN 查询，LIKE 与范围比较不会命中");
        }

        if (!relations.isEmpty()) {
            System.out.println("\nRelationships:");
            for (RelationWorkspaceEdge relation : relations) {
                ColumnWorkspaceNode fromColumn = workspace.findColumnById(relation.getFrom());
                ColumnWorkspaceNode toColumn = workspace.findColumnById(relation.getTo());
                if (fromColumn == null || toColumn == null) {
                    continue;
                }
                System.out.printf("  %s%s.%s -> %s.%s (%s, %s)%n",
                        candidateMark(relation.getStatus()),
                        tableNameForColumnId(relation.getFrom()),
                        fromColumn.getName(),
                        tableNameForColumnId(relation.getTo()),
                        toColumn.getName(),
                        relation.getType(),
                        cardinalityLabel(relation.getCardinality(), fromColumn, toColumn));
                // 可直接复制的 JOIN 写法，Agent 不用自己把关系翻译成语法
                System.out.printf("    JOIN %s ON %s.%s = %s.%s%n",
                        qualifiedTableForColumnId(relation.getTo()),
                        tableNameForColumnId(relation.getFrom()), fromColumn.getName(),
                        tableNameForColumnId(relation.getTo()), toColumn.getName());
            }
            int fanIn = fanInCount(workspace, table, relations);
            if (fanIn >= 2) {
                // 扇形陷阱：本表在「一」侧，同时 join 两张「多」侧的表，行数被乘开，
                // SUM 出来的数偏大而 SQL 照跑、不报错。这里只报事实，判断留给 agent——
                // 它比展开器更清楚这次到底要不要聚合。
                System.out.println("  注: 本表处在 " + fanIn + " 条一对多关系的「一」侧，"
                        + "同时 join 其中两条会放大行数（扇形陷阱），聚合前先分别汇总或去重");
            }
        }

        if (!recentSql.isEmpty()) {
            System.out.println("\nRecent SQL:");
            for (String sql : recentSql) {
                System.out.println("  " + sql);
            }
        }
        return 0;
    }

    /** describe/query 打错表名时给相近候选，省一轮 search。 */
    private static String similarTablesHint(GraphWorkspace workspace, String tableName) {
        String bare = tableName.contains(".")
                ? tableName.substring(tableName.lastIndexOf('.') + 1) : tableName;
        List<String> similar = GraphQueryHints.similarTables(workspace, bare, 3);
        return similar.isEmpty() ? "" : "，相近的表: " + String.join(", ", similar);
    }

    /**
     * 别名配置的 SM4 解密列（小写）。解析失败（密钥环境变量缺失等）不影响 describe，
     * 只是不标注。
     */
    /**
     * 全库查询约定。读不到（没绑规则集、文件坏了）就是空——它是一份可选提示，
     * 不能因为它让 describe / search 失败。
     */
    private QueryConventions queryConventions() {
        try {
            return QueryConventions.load(workspaceStore.workspacePath(alias),
                    new PolicyStore(workspaceStore).listWaivers(alias));
        } catch (Exception e) {
            return QueryConventions.empty();
        }
    }

    private Set<String> resolveSm4Columns() {
        try {
            DatabaseConfig config = aliasResolver.resolve(alias);
            if (config.getSm4Key() == null || config.getDecryptColumns() == null) {
                return Set.of();
            }
            Set<String> out = new LinkedHashSet<>();
            for (String column : config.getDecryptColumns()) {
                out.add(column.toLowerCase(Locale.ROOT));
            }
            return out;
        } catch (Exception e) {
            return Set.of();
        }
    }

    /** column:alias:schema.table.column -> schema.table */
    private static String qualifiedTableForColumnId(String columnId) {
        String[] parts = columnId.split(":", 3);
        if (parts.length < 3) {
            return null;
        }
        int lastDot = parts[2].lastIndexOf('.');
        return lastDot < 0 ? null : parts[2].substring(0, lastDot);
    }

    /**
     * 候选/已忽略对象在文本输出里必须一眼看出来，JSON 侧靠 status 字段。
     * describe 是给人看的关系列表，已忽略的边不过滤——过滤掉就等于把"人拒过"这件事
     * 藏起来，与 Web UI 显式分组展示已忽略关系的做法不一致（同一张表的关系，CLI 和
     * Web UI 不该给出不同答案）。
     */
    private static boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 关系基数，声明缺失时按主键/唯一约束推断。
     *
     * <p><b>为什么要推断</b>：实测 `erp_prop_report` 的 14 条关系基数<b>全是 unknown</b>——
     * agent 写 `add-relation` 时基本不填它。不推断的话扇形陷阱提示永远不会触发，
     * 这一整条消费路径就是死的。
     *
     * <p>推断规则只有一条，而且是结构上确定的：指向<b>主键或唯一列</b>的那一端天然是「一」，
     * 另一端天然是「多」。它不猜业务，只读 DDL 已经声明过的约束。
     * 推断不出就老实返回 {@code unknown}——**「未知」必须说出来**，
     * 省略它等于让 agent 默认按 N:1 处理。
     */
    private static RelationCardinality effectiveCardinality(RelationCardinality declared,
                                                            ColumnWorkspaceNode fromColumn,
                                                            ColumnWorkspaceNode toColumn) {
        if (declared != null && declared != RelationCardinality.unknown) {
            return declared;
        }
        if (fromColumn == null || toColumn == null) {
            return RelationCardinality.unknown;
        }
        boolean toUnique = toColumn.isPrimaryKey() || toColumn.isUnique();
        boolean fromUnique = fromColumn.isPrimaryKey() || fromColumn.isUnique();
        if (toUnique && !fromUnique) {
            return RelationCardinality.many_to_one;
        }
        if (fromUnique && !toUnique) {
            return RelationCardinality.one_to_many;
        }
        if (fromUnique) {
            return RelationCardinality.one_to_one;
        }
        return RelationCardinality.unknown;
    }

    private static String cardinalityLabel(RelationCardinality declared,
                                           ColumnWorkspaceNode fromColumn,
                                           ColumnWorkspaceNode toColumn) {
        RelationCardinality effective = effectiveCardinality(declared, fromColumn, toColumn);
        String suffix = declared == null || declared == RelationCardinality.unknown ? "推断" : "";
        String label = switch (effective) {
            case one_to_one -> "1:1";
            case one_to_many -> "1:N";
            case many_to_one -> "N:1";
            case many_to_many -> "N:N";
            default -> "基数未知";
        };
        return suffix.isEmpty() || effective == RelationCardinality.unknown
                ? label : label + "(" + suffix + ")";
    }

    /**
     * 本表处在几条一对多关系的「一」侧——两条以上同时 join 就是扇形陷阱。
     *
     * <p>只数方向确定的，{@code unknown} 不算。宁可漏报也不误报：
     * 一个总在喊扇形陷阱的提示，agent 两次之后就不看了。
     */
    private int fanInCount(GraphWorkspace workspace, TableWorkspaceNode table,
                           List<RelationWorkspaceEdge> relations) {
        int count = 0;
        for (RelationWorkspaceEdge relation : relations) {
            RelationCardinality cardinality = effectiveCardinality(relation.getCardinality(),
                    workspace.findColumnById(relation.getFrom()),
                    workspace.findColumnById(relation.getTo()));
            if (cardinality == RelationCardinality.unknown) {
                continue;
            }
            boolean fromHere = belongsTo(table, relation.getFrom());
            boolean toHere = belongsTo(table, relation.getTo());
            if (toHere && !fromHere && cardinality == RelationCardinality.many_to_one) {
                count++;   // 对面是「多」：join 过来会放大行数
            } else if (fromHere && !toHere && cardinality == RelationCardinality.one_to_many) {
                count++;
            }
        }
        return count;
    }

    private boolean belongsTo(TableWorkspaceNode table, String columnId) {
        String owner = tableNameForColumnId(columnId);
        return owner != null && owner.equalsIgnoreCase(table.getName());
    }

    /**
     * 单列全展开：{@code describe <table> --column <name>}。
     *
     * <p>紧凑那行为了让几十列排得下，砍掉了格式 / 示例值 / 默认值 / 置信度 / 索引与唯一性。
     * <b>这些恰恰是「要不要信这条语义」的依据</b>，所以它们不是被删掉了，是被挪到追问这一步——
     * 见 dev-checklist-2026-09 的 D1「上下文预算」那一节。
     */
    private int describeSingleColumn(GraphWorkspace workspace,
                                     TableWorkspaceNode table,
                                     List<ColumnWorkspaceNode> columns,
                                     Set<String> sm4Columns) {
        String wanted = editColumn.contains(".")
                ? editColumn.substring(editColumn.lastIndexOf('.') + 1) : editColumn;
        ColumnWorkspaceNode column = columns.stream()
                .filter(c -> c.getName().equalsIgnoreCase(wanted))
                .findFirst().orElse(null);
        if (column == null) {
            return commandFailure("Column not found: " + editColumn + "，本表有 "
                    + columns.size() + " 列，用 schema describe "
                    + table.getQualifiedName() + " 看完整列表");
        }
        System.out.println("Column: " + table.getQualifiedName() + "." + column.getName());
        System.out.println("Type: " + column.getDataType().getRaw()
                + (column.isPrimaryKey() ? " [PK]" : "")
                + (column.isNullable() ? "" : " NOT NULL")
                + (column.isUnique() ? " UNIQUE" : "")
                + (column.isIndexed() ? " INDEXED" : "")
                + (sm4Columns.contains(column.getName().toLowerCase(Locale.ROOT)) ? " [SM4]" : ""));
        if (column.getDefaultValue() != null) {
            System.out.println("Default: " + column.getDefaultValue());
        }
        if (nonBlank(column.getComment())) {
            System.out.println("注释(库): " + column.getComment());
        }
        if (nonBlank(column.getDescription())) {
            System.out.println("描述: " + column.getDescription());
        }
        if (nonBlank(column.getBusinessName())) {
            System.out.println("业务名: " + column.getBusinessName());
        }
        if (column.getSemanticType() != null) {
            System.out.println("语义类型: " + column.getSemanticType());
        }
        ColumnValueHints hints = column.getValueHints();
        if (hints != null) {
            if (hints.getEnumValues() != null && !hints.getEnumValues().isEmpty()) {
                System.out.println("值域: " + String.join(", ", hints.getEnumValues()));
            }
            if (nonBlank(hints.getFormat())) {
                System.out.println("格式: " + hints.getFormat());
            }
            if (hints.getSampleValues() != null && !hints.getSampleValues().isEmpty()) {
                System.out.println("示例值: " + String.join(", ", hints.getSampleValues()));
            }
        }
        if (column.getRedundantOf() != null) {
            System.out.println("冗余副本，权威源: "
                    + columnDisplayName(column.getRedundantOf()));
        }
        // 血缘两个方向都给：上游是「这值从哪来、公式是什么」，下游是「改它会影响谁、去哪改」。
        // 下游是重构目的下的主视角——rule 类的全部读者就在这里。
        LineageGraph lineageGraph = new LineageGraph(workspace.getLineage().values());
        String columnId = column.computeId(table.getSourceAlias(), table.getSchema(), table.getName());
        List<LineageRecord> upstream = lineageGraph.upstream(columnId, 1);
        if (!upstream.isEmpty()) {
            System.out.println("上游血缘:");
            for (LineageRecord record : upstream) {
                System.out.println("  " + candidateMark(record.getStatus()) + "[" + lineageKindLabel(record.getLineageKind())
                        + "] <- " + record.getSources().stream().map(SchemaActionCommand::columnDisplayName)
                        .collect(Collectors.joining(", ")));
                if (nonBlank(record.getExpression())) {
                    System.out.println("    expression: " + record.getExpression());
                }
                if (nonBlank(record.getThrough())) {
                    System.out.println("    through: " + record.getThrough());
                }
            }
        }
        List<LineageRecord> downstream = lineageGraph.downstream(columnId, 1);
        if (!downstream.isEmpty()) {
            System.out.println("下游影响（改这一列前先看）:");
            for (LineageRecord record : downstream) {
                System.out.println("  " + candidateMark(record.getStatus()) + columnDisplayName(record.getTarget())
                        + " [" + lineageKindLabel(record.getLineageKind()) + "] through: " + record.getThrough());
                if (record.getLineageKind() == LineageKind.rule && nonBlank(record.getExpression())) {
                    System.out.println("    规则: " + record.getExpression());
                }
            }
        }
        // 这两行是「信到什么程度」的全部依据。图谱按需生长，任何一条语义都可能是
        // 三周前某次任务顺手写的——不标出来，agent 只能全信或全不信。
        System.out.println("置信度: " + (column.getConfidence() == null
                ? "未标注" : column.getConfidence()));
        System.out.println("人工确认: " + (Boolean.TRUE.equals(column.getVerified())
                ? "是" : "否——这条语义没有人复核过"));
        if (!column.hasSemantics()) {
            System.out.println("注: 这一列只有库注释，"
                    + "没有任何人写过业务语义——"
                    + "按字段名理解它之前，先去代码里确认");
        }
        if (!column.getAttributes().isEmpty()) {
            System.out.println("其他: " + column.getAttributes());
        }
        return 0;
    }

    /**
     * 一行表级完整度：这张表的语义够不够 agent 直接用。
     *
     * <p><b>它回答的不是「覆盖了百分之多少」，是「这张表能不能信」。</b>图谱是按需生长的
     * （技能里明确禁止全仓扫描），所以任何时刻都是部分完整的。agent 面对一张没有描述的表时
     * 分不清两种情况——「这张表确实简单」和「没人干过这块」——而这两种情况下该做的事
     * 完全相反：一个直接用，一个必须先读代码。<b>决定性的信号是有没有人碰过，不是百分比。</b>
     *
     * <p>补充时间取表节点：列是表节点里的内联对象，没有自己的 {@code updatedBy} /
     * {@code updatedAt}，改任何一列都会把表节点顶上去。所以表级是这份信息<b>唯一可得的粒度</b>。
     */
    private record DescribeCompleteness(
            int columns, int withDescription, int withEnumValues, int withBusinessName,
            int relations, int unknownCardinality,
            boolean curated, java.time.LocalDateTime updatedAt, int searchHits) {

        static DescribeCompleteness of(GraphWorkspace workspace, TableWorkspaceNode table,
                                       List<ColumnWorkspaceNode> columns,
                                       List<RelationWorkspaceEdge> relations, int searchHits) {
            int described = 0;
            int withEnums = 0;
            int named = 0;
            for (ColumnWorkspaceNode column : columns) {
                if (nonBlank(column.getDescription())) {
                    described++;
                }
                if (column.getValueHints() != null && column.getValueHints().getEnumValues() != null
                        && !column.getValueHints().getEnumValues().isEmpty()) {
                    withEnums++;
                }
                if (nonBlank(column.getBusinessName())) {
                    named++;
                }
            }
            // 按**推断后**的基数统计，否则这个数会和关系列表里显示的 N:1(推断) 对不上
            long unknown = relations.stream()
                    .filter(r -> effectiveCardinality(r.getCardinality(),
                            workspace.findColumnById(r.getFrom()),
                            workspace.findColumnById(r.getTo())) == RelationCardinality.unknown)
                    .count();
            // 判据是**内容**，不是 updatedBy。实测 erp_prop_report 被 agent 写满了描述、
            // 值域和 grain，而它的 updatedBy 是 `system`——回写行数那次记账把它盖掉了
            // （CLAUDE.md：system 的记账动作包括建索引、跑校验、回写行数）。
            // 拿 actor 当整理痕迹会把做透的表报成「从未有人补充」，正好把这条提示的
            // 唯一用处反过来。
            boolean curated = described > 0 || withEnums > 0 || named > 0
                    || nonBlank(table.getDescription()) || nonBlank(table.getBusinessName())
                    || nonBlank(table.getGrain());
            return new DescribeCompleteness(columns.size(), described, withEnums, named,
                    relations.size(), (int) unknown, curated, table.getUpdatedAt(), searchHits);
        }

        /**
         * 「被搜到 N 次」跟在整理状态后面，回答的是读侧遥测那条闭环存在的理由：
         * 「12 次搜到、0 次整理」和「0 次搜到、0 次整理」该做的事完全相反——前者是有真实
         * 需求但没人补过，该读代码补图谱；后者没人关心，别管。数字本身不判断，只报告，
         * 判断留给 agent。
         */
        String line() {
            String tail = curated
                    ? "有人整理过（最近变更 " + (updatedAt == null ? "未知" : updatedAt.toLocalDate()) + "）"
                    : "从未有人补充——只有库注释，直接用之前先读代码";
            String cardinality = unknownCardinality == 0
                    ? "" : "（基数未知 " + unknownCardinality + "）";
            return String.format(
                    "语义: 描述 %d/%d · 值域 %d/%d · 业务名 %d/%d · 关系 %d%s · 被搜到 %d 次 · %s",
                    withDescription, columns, withEnumValues, columns,
                    withBusinessName, columns, relations, cardinality, searchHits, tail);
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("columns", columns);
            map.put("withDescription", withDescription);
            map.put("withEnumValues", withEnumValues);
            map.put("withBusinessName", withBusinessName);
            map.put("relations", relations);
            map.put("unknownCardinality", unknownCardinality);
            map.put("curated", curated);
            map.put("updatedAt", updatedAt == null ? null : updatedAt.toString());
            map.put("searchHits", searchHits);
            map.put("summary", line());
            return map;
        }
    }

    private static String candidateMark(GraphStatus status) {
        if (status == GraphStatus.candidate) return "[候选] ";
        if (status == GraphStatus.ignored) return "[已忽略] ";
        return "";
    }

    /**
     * 对目标表跑一次 COUNT(*) 并写回 rowEstimate。
     * 以 system 身份写入、只改这一个字段，不动 status，因此不会把表变成候选；
     * 也不 {@code touch}——记账动作不认领 {@code updatedBy}/{@code updatedAt}，
     * 见 {@link com.sqlcli.graph.workspace.BaseGraphObject#touch}。
     */
    private int refreshRowEstimate(GraphWorkspace workspace, TableWorkspaceNode table) {
        long count;
        try {
            DatabaseConfig config = aliasResolver.resolve(alias);
            try (Connection connection = connectionManager.getConnection(config)) {
                count = TableRowCounter.count(connection, config.getType(),
                        table.getSchema(), table.getName());
            }
        } catch (Exception e) {
            return commandFailure("行数采集失败: " + e.getMessage());
        }
        return persistRowEstimate(workspace, table.getId(), count);
    }

    int persistRowEstimate(GraphWorkspace workspace, String tableId, long count) {
        WorkspaceMutationService.MutationResult result = mutationService.mutate(alias,
                workspace.getManifest().getRevision(), GraphActor.system,
                "schema describe --refresh-row-count", current -> {
                    TableWorkspaceNode target = current.getTables().get(tableId);
                    if (target == null) {
                        throw new IllegalArgumentException("table not found: " + tableId);
                    }
                    target.setRowEstimate(count);
                    return new WorkspaceMutationService.MutationOutcome(tableId, ChangeOperation.update);
                });
        if (!result.isSuccess()) {
            return commandFailure("行数写回失败: " + String.join("; ", result.getErrors()));
        }
        return 0;
    }

    private int queryTable(GraphWorkspace workspace) throws Exception {
        if (workspace == null) {
            return missingWorkspace();
        }
        if (tableName == null) {
            return usageError(
                    "Usage: sql-cli " + alias
                            + " schema query <table> [--depth N] [--json]");
        }
        TableWorkspaceNode table = workspace.getTableByQualifiedName(tableName);
        if (table == null) {
            return commandFailure("Table not found: " + tableName + similarTablesHint(workspace, tableName));
        }

        List<RelationWorkspaceEdge> related = expandRelations(workspace, table, depth);
        if (jsonOutput) {
            CliJson.printSuccess(related);
            return 0;
        }

        if (related.isEmpty()) {
            System.out.println("No related tables found");
            return 0;
        }

        System.out.println("Related tables (depth " + depth + "):");
        for (RelationWorkspaceEdge relation : related) {
            ColumnWorkspaceNode fromColumn = workspace.findColumnById(relation.getFrom());
                ColumnWorkspaceNode toColumn = workspace.findColumnById(relation.getTo());
                if (fromColumn == null || toColumn == null) {
                    continue;
                }
                System.out.printf("  %s.%s -> %s.%s (%s)%n",
                        tableNameForColumnId(relation.getFrom()), fromColumn.getName(),
                        tableNameForColumnId(relation.getTo()), toColumn.getName(),
                        relation.getType());
        }
        return 0;
    }

    private int findPath(GraphWorkspace workspace) throws Exception {
        if (workspace == null) {
            return missingWorkspace();
        }
        if (tableName == null || tableName2 == null) {
            return usageError(
                    "Usage: sql-cli " + alias
                            + " schema path <table1> <table2> [--json]");
        }
        List<WorkspacePathFinder.PathResult> paths = new WorkspacePathFinder(workspace).findPaths(tableName, tableName2);
        recordPathRead(workspace, paths);
        if (jsonOutput) {
            CliJson.printSuccess(paths);
            return 0;
        }
        if (paths.isEmpty()) {
            System.out.println("No path found between " + tableName + " and " + tableName2);
            return 0;
        }
        System.out.println("Found " + paths.size() + " path(s):");
        for (int i = 0; i < paths.size(); i++) {
            WorkspacePathFinder.PathResult path = paths.get(i);
            System.out.printf("%n--- Path %d (hops: %d) ---%n", i + 1, path.getHops());
            for (WorkspacePathFinder.PathSegment seg : path.getPath()) {
                System.out.printf("  %s.%s -> %s.%s [%s]%s%n",
                        seg.getFromTable(), seg.getFromColumn(),
                        seg.getToTable(), seg.getToColumn(),
                        seg.getRelationshipType(),
                        joinSummary(seg));
            }
        }
        return 0;
    }

    /**
     * 读侧遥测产出侧：这次 path 查询涉及的两张表，供 {@code schema gaps} 排优先级用。
     * 表名解析不出来（打错、还没导入）就只记 id 列表里存在的那一个，不因为查不到而不记。
     */
    private void recordPathRead(GraphWorkspace workspace, List<WorkspacePathFinder.PathResult> paths) {
        List<String> targetIds = new ArrayList<>();
        TableWorkspaceNode t1 = workspace.getTableByQualifiedName(tableName);
        TableWorkspaceNode t2 = workspace.getTableByQualifiedName(tableName2);
        if (t1 != null) targetIds.add(t1.getId());
        if (t2 != null) targetIds.add(t2.getId());
        InteractionDetail.set(tableName + " -> " + tableName2, paths.size(), null, targetIds);
    }

    /**
     * schema path 纯文本每段的 join 提示：INNER/LEFT 结论 + 可执行的 join 条件。
     *
     * <p>结论只看 {@code fromOptional}：外键列（fromTable 侧）允许为空时，这一段的
     * fromTable 行不保证在 toTable 里有匹配，INNER JOIN 会把它们静默过滤掉——这正是
     * 复合外键分组那条改动要防的问题，所以在文本里直接给出 LEFT/INNER 结论，不留给人翻译
     * 裸的布尔值。{@code toOptional} 目前恒为保守猜测的 {@code true}（见
     * WorkspaceMetadataExtractor#addForeignKeyRelation 的注释），不对应任何一个 JOIN
     * 关键字，不在这里打印，省得制造一个没有结论的信号。
     *
     * <p>括号里标的是这个结论<b>怎么来的</b>：「推断」= 只看 nullable 声明，「实测」= 看
     * {@code schema value-domain} 采到的空值率。声明说可空而实测一个空值都没有是常态
     * （历史列、后来补的非空约束没加上），两者给出的 JOIN 结论不同，不标来源的话
     * agent 无从判断这条 INNER 能不能信。人工确认的第三条来源还不存在，出现了再加分支。
     */
    private static String joinSummary(WorkspacePathFinder.PathSegment seg) {
        StringBuilder sb = new StringBuilder();
        if (seg.getFromOptional() != null) {
            String basis = RelationWorkspaceEdge.OPTIONALITY_SOURCE_PROFILED
                    .equals(seg.getOptionalitySource()) ? "实测" : "推断";
            sb.append(seg.getFromOptional()
                    ? "  LEFT JOIN（" + basis + "：" + seg.getFromTable() + " 可能无匹配，INNER 会静默丢行）"
                    : "  INNER JOIN（" + basis + "）");
        }
        if (seg.getJoinExpression() != null && !seg.getJoinExpression().isBlank()) {
            sb.append("  ON ").append(seg.getJoinExpression());
        }
        return sb.toString();
    }

    /** 当前动作名。测试用它模拟入口那一步的 action 命名。 */
    public String getAction() {
        return action;
    }

    /** 交互日志里 search 这一类的 action 名，与 {@code SqlCli.actionOf} 的产出对齐。 */
    static final String ACTION_SEARCH = InteractionDetail.schemaAction("search");

    private int search(GraphWorkspace workspace) throws Exception {
        if (workspace == null) {
            return missingWorkspace();
        }
        if (keyword == null) {
            return usageError(
                    "Usage: sql-cli " + alias + " schema search <keyword> [--limit N] [--json]");
        }
        IndexStatus indexStatus = indexStore.getIndexStatus(alias, workspace.getManifest().getRevision());
        if (indexStatus == IndexStatus.ready) {
            WorkspaceIndexSnapshot snapshot = indexStore.load(alias);
            if (snapshot != null) {
                List<WorkspaceIndexedSearchEngine.SearchHit> hits = new WorkspaceIndexedSearchEngine(snapshot).search(keyword);
                List<FoldableHit> folded = hits.stream().map(FoldableHit::from).toList();
                recordSearchRead(folded);
                if (jsonOutput) {
                    return printSearchJson(workspace, hits, folded);
                }
                printFoldedSearch(workspace, folded);
                return 0;
            }
            System.err.println("索引格式已过时，请运行: sql-cli " + alias + " schema index rebuild");
        } else if (indexStatus == IndexStatus.stale) {
            System.err.println("搜索索引已过期，改用工作区实时搜索；请运行: sql-cli " + alias + " schema index rebuild");
        }

        List<WorkspaceSearchEngine.SearchResult> results = new WorkspaceSearchEngine(workspace).search(keyword);
        List<FoldableHit> folded = results.stream().map(FoldableHit::from).toList();
        recordSearchRead(folded);
        if (jsonOutput) {
            return printSearchJson(workspace, results, folded);
        }
        printFoldedSearch(workspace, folded);
        return 0;
    }

    /**
     * 读侧遥测的 search 埋点。**搜空（{@code hits.isEmpty()}）也要记，而且它才是重点**——
     * 「有人用业务词找过、没找到」是最准的补图谱优先级信号，是真实需求不是猜的，
     * {@code schema gaps} 和 E6 的评估集供给都靠它。
     *
     * <p>只记命中对象的 id，不记标题和分数明细：{@code target_ids} 是给
     * {@code schema gaps} 数「哪张表被搜到过多少次」用的，其余的图谱里都有。
     */
    private void recordSearchRead(List<FoldableHit> folded) {
        InteractionDetail.set(keyword, folded.size(),
                folded.isEmpty() ? null : folded.get(0).score(),
                folded.stream().map(FoldableHit::id).toList());
    }

    /**
     * JSON 保持引擎原生结构（契约不动，字段名不改、不删），只加两个字段：
     * {@code origin}（"term" | "term_expansion" | "keyword"）和命中是哪条术语带出来的
     * {@code expandedByTermId}——T3 要求 agent 能分清"这条是术语明确指过去的"还是
     * "关键词凑巧命中的"，两者可信度差一个量级。只做实时截断；截断提示走 stderr 不污染 stdout。
     */
    private <T> int printSearchJson(GraphWorkspace workspace, List<T> hits, List<FoldableHit> folded) {
        int limit = effectiveSearchLimit(50);
        boolean truncated = hits.size() > limit;
        List<T> hitsShown = truncated ? hits.subList(0, limit) : hits;
        List<FoldableHit> foldedShown = truncated ? folded.subList(0, limit) : folded;

        // 场景术语带出的表：key 是 schema.table（小写），value 是带出它的术语 id
        Map<String, String> expandedBy = new LinkedHashMap<>();
        for (FoldableHit hit : folded) {
            TermScenario scenario = scenarioOf(workspace, hit);
            if (scenario == null) continue;
            for (TermScenario.ScenarioTable table : scenario.tables()) {
                expandedBy.putIfAbsent(lower(table.qualifiedName()), hit.id());
            }
        }

        List<Object> enriched = new ArrayList<>();
        for (int i = 0; i < hitsShown.size(); i++) {
            FoldableHit fh = foldedShown.get(i);
            com.fasterxml.jackson.databind.node.ObjectNode node =
                    (com.fasterxml.jackson.databind.node.ObjectNode) jsonMapper.valueToTree(hitsShown.get(i));
            String key = fh.schema() != null && fh.table() != null ? lower(fh.schema() + "." + fh.table()) : null;
            String viaTermId = key == null ? null : expandedBy.get(key);
            if (scenarioOf(workspace, fh) != null) {
                node.put("origin", "term");
            } else if (viaTermId != null) {
                node.put("origin", "term_expansion");
                node.put("expandedByTermId", viaTermId);
            } else {
                node.put("origin", "keyword");
            }
            enriched.add(node);
        }
        CliJson.printSuccess(enriched);
        if (truncated) {
            System.err.println("提示: 命中 " + hits.size() + " 条，仅返回前 " + limit
                    + " 条；--limit 0 返回全部，或加词缩小范围");
        }
        return 0;
    }

    private int effectiveSearchLimit(int defaultLimit) {
        if (searchLimit == null) return defaultLimit;
        return searchLimit == 0 ? Integer.MAX_VALUE : searchLimit;
    }

    /** 两个引擎的命中归一化后按表折叠渲染，取代逐条平铺 + 表命中全文展开的旧输出。 */
    private record FoldableHit(String id, String type, String schema, String table, String column, String title,
                               double score, String businessName, String semanticType, String comment,
                               String description, boolean candidate, String redundantOf) {

        static FoldableHit from(WorkspaceIndexedSearchEngine.SearchHit hit) {
            return new FoldableHit(hit.getId(), hit.getType(), hit.getSchema(), hit.getTable(), hit.getColumn(),
                    hit.getTitle(), hit.getScore(), hit.getBusinessName(), hit.getSemanticType(),
                    hit.getComment(), hit.getDescription(), hit.isCandidate(), hit.getRedundantOf());
        }

        static FoldableHit from(WorkspaceSearchEngine.SearchResult result) {
            return new FoldableHit(result.getId(), result.getType(), result.getSchema(), result.getTableName(),
                    result.getColumnName(), result.getTitle(), result.getScore(),
                    result.getBusinessName(), result.getSemanticType(), result.getComment(),
                    result.getDescription(), result.isCandidate(), result.getRedundantOf());
        }

        String groupKey() {
            // 表/列按所属表折叠；term / metric 没有 schema.table 结构，各自一组
            // （漏了 metric 分支时会落到 schema+"."+table，两者都是 null，命中全部塞进同一个
            // "null.null" 组——不崩但把不相干的指标全揉一起，加一行按类型分组即可）。
            return "term".equals(type) || "metric".equals(type) ? type + ":" + title : schema + "." + table;
        }
    }

    private void printFoldedSearch(GraphWorkspace workspace, List<FoldableHit> hits) {
        if (hits.isEmpty()) {
            System.out.println("No results found for: " + keyword);
            return;
        }
        Map<String, List<FoldableHit>> groups = new LinkedHashMap<>();
        for (FoldableHit hit : hits) {
            groups.computeIfAbsent(hit.groupKey(), k -> new ArrayList<>()).add(hit);
        }
        List<List<FoldableHit>> ordered = new ArrayList<>(groups.values());
        ordered.sort(Comparator.comparingDouble(
                (List<FoldableHit> g) -> g.stream().mapToDouble(FoldableHit::score).max().orElse(0)).reversed());

        // 场景术语先算出来：它带出的表不再单独成组（那就是「术语不占结果位」——
        // 它不是一条和表并列的结果，它把表带出来）。**必须先算再打表头**，
        // 否则表头报的组数是折叠前的，跟屏幕上看到的对不上。
        Map<String, TermScenario> scenarios = new LinkedHashMap<>();
        Set<String> coveredTables = new LinkedHashSet<>();
        for (List<FoldableHit> group : ordered) {
            TermScenario scenario = scenarioOf(workspace, group.get(0));
            if (scenario == null) continue;
            scenarios.put(group.get(0).id(), scenario);
            scenario.tables().forEach(table -> coveredTables.add(lower(table.qualifiedName())));
        }
        List<List<FoldableHit>> visible = ordered.stream()
                .filter(group -> scenarios.containsKey(group.get(0).id())
                        || !coveredTables.contains(lower(group.get(0).schema() + "." + group.get(0).table())))
                .toList();

        int limit = effectiveSearchLimit(15);
        int shown = Math.min(limit, visible.size());
        System.out.println("命中 " + hits.size() + " 条，按表折叠为 " + visible.size() + " 组"
                + (shown < visible.size() ? "，显示前 " + shown + " 组（--limit N 调整）" : "") + "：");

        Set<String> sm4Columns = resolveSm4Columns();
        Map<String, String> lineageHints = lineageHints(workspace);
        for (List<FoldableHit> group : visible.subList(0, shown)) {
            TermScenario scenario = scenarios.get(group.get(0).id());
            if (scenario != null) {
                printScenarioGroup(workspace, group.get(0), scenario);
            } else {
                printSearchGroup(workspace, group, sm4Columns, lineageHints);
            }
        }
        if (shown < visible.size()) {
            System.out.println("还有 " + (visible.size() - shown)
                    + " 组未显示（--limit 0 看全部）。要看列结构用 schema describe <schema.table>");
        }
    }

    /**
     * 每个「有上游」的字段一句话提示，键是小写 {@code schema.table.column}。
     *
     * <p>为什么标在搜索结果里而不是做成 {@code schema lineage --list}：Agent 不需要知道
     * 这库共有多少条血缘，它需要的是**搜到某个字段的那一刻**就知道这字段是推导来的。
     * 跟旁边 {@link #redundantOfSuffix} 是同一类信号（"别把这当原始值"），所以写法也照它。
     *
     * <p>只标上游不标下游：上游是"这个值不是录进来的"的警告，下游是治理视角，查数时用不上。
     */
    private static Map<String, String> lineageHints(GraphWorkspace workspace) {
        Map<String, String> hints = new LinkedHashMap<>();
        for (LineageRecord record : workspace.getLineage().values()) {
            List<String> sources = record.getSources();
            if (record.getTarget() == null || sources == null || sources.isEmpty()) continue;
            hints.put(lower(columnDisplayName(record.getTarget())), lineageHint(record));
        }
        return hints;
    }

    /**
     * 四类各一句，说的是「拿这个值时该怎么办」，不是「它是什么类别」。
     * identity 与 {@link #redundantOfSuffix} 用同一个标记：快照和冗余副本对读的人是同一件事——
     * 别把这当当前值。
     */
    private static String lineageHint(LineageRecord record) {
        LineageKind kind = record.getLineageKind();
        List<String> sources = record.getSources();
        if (kind == null) {
            return sources.size() == 1
                    ? "[派生自:" + columnDisplayName(sources.get(0)) + "]"
                    : "[派生自 " + sources.size() + " 个上游]";
        }
        // 四类各一句，句子的结构不同（快照说权威源、派生说公式、汇总说来源表、规则说去哪看），
        // 类别的中文名从枚举上取，不在这里再写一遍
        return switch (kind) {
            case identity -> "[" + kind.label() + "，权威源:" + columnDisplayName(sources.get(0)) + "]";
            case transformation -> "[派生:" + clip(record.getExpression(), 40) + "]";
            case aggregation -> "[汇总自:" + sources.stream()
                    .map(SchemaActionCommand::qualifiedTableForColumnId).distinct()
                    .collect(Collectors.joining(",")) + "，可能与明细漂移]";
            case rule -> "[" + kind.label() + "，改 " + sources.stream().map(SchemaActionCommand::columnDisplayName)
                    .collect(Collectors.joining(",")) + " 前先看 " + clip(record.getThrough(), 40) + "]";
        };
    }

    private static String clip(String text, int max) {
        if (text == null) return "";
        String oneLine = text.replace('\n', ' ').trim();
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max - 1) + "…";
    }

    /** 中文标签给文本输出；--json 里仍是枚举名，那是 CLI 契约。 */
    private static String lineageKindLabel(LineageKind kind) {
        return kind == null ? "未分类" : kind + "（" + kind.label() + "）";
    }

    /** 命中列有上游血缘时的后缀；没有就是空串。 */
    private static String lineageSuffix(Map<String, String> hints, String schema, String table, String column) {
        if (hints.isEmpty() || column == null) return "";
        return hints.getOrDefault(lower(schema + "." + table + "." + column), "");
    }

    /** 这张表命中的全库约定过滤；没绑规则集就是空。 */
    private List<String> conventionFilters(GraphWorkspace workspace, String tableId) {
        TableWorkspaceNode table = tableId == null ? null : workspace.getTables().get(tableId);
        if (table == null) return List.of();
        return queryConventions().forTable(workspace, table).stream()
                .map(QueryConventions.Hint::filter).toList();
    }

    /** 这张表属于哪些场景（术语侧的反向索引）。 */
    private static List<String> scenariosContaining(GraphWorkspace workspace, TableWorkspaceNode table) {
        List<String> names = new ArrayList<>();
        for (TermWorkspaceNode term : workspace.getTerms().values()) {
            TermScenario scenario = TermScenario.of(workspace, term);
            if (scenario == null) continue;
            if (scenario.tables().stream().anyMatch(one -> one.tableId().equals(table.getId()))) {
                names.add(term.getDisplayName() == null ? term.getName() : term.getDisplayName());
            }
        }
        return names;
    }

    /** 这条命中是不是一个能展开的场景；不是就返回 null（普通术语、表、列都走这里）。 */
    private static TermScenario scenarioOf(GraphWorkspace workspace, FoldableHit hit) {
        if (!"term".equals(hit.type())) return null;
        TermWorkspaceNode term = workspace.getTerms().get(hit.id());
        return term == null ? null : TermScenario.of(workspace, term);
    }

    /**
     * 场景块：术语命中时给出「完成这件事要用哪些表、怎么连、要带哪些过滤」。
     *
     * <p>为什么不是打印一行 {@code [TERM] 报事 [映射: a、b]} 就完事——那样 agent 拿到的是
     * 几个孤立的表名，还得自己 {@code schema path} 拼 join，而拼错的后果是跨租户笛卡尔积、
     * 漏项目隔离，**SQL 照跑、有结果、数是错的**。
     */
    private void printScenarioGroup(GraphWorkspace workspace, FoldableHit first, TermScenario scenario) {
        TermWorkspaceNode term = workspace.getTerms().get(first.id());

        System.out.printf("  ⌘ 场景「%s」%s%n", first.title(),
                term.getDescription() == null || term.getDescription().isBlank()
                        ? "" : " — " + term.getDescription());
        List<String> meta = new ArrayList<>();
        if (!term.getAliases().isEmpty()) meta.add("同义词: " + String.join("、", term.getAliases()));
        meta.add(scenario.tables().size() + " 张表");
        if (!meta.isEmpty()) System.out.println("    " + String.join(" · ", meta));
        // 过滤单独一行且顶格：写 WHERE 的时候漏掉它就是静默少数据，不能挤在元信息里
        if (!scenario.filters().isEmpty()) {
            System.out.println("    过滤: " + String.join("  AND  ", scenario.filters()));
        }
        // 全库约定和场景过滤都要进 WHERE，并排显示；分开标是因为来源不同——
        // 一个来自规则（换场景也成立），一个是这个场景专属的
        List<String> conventions = conventionFilters(workspace, scenario.entryTableId());
        if (!conventions.isEmpty()) {
            System.out.println("    约定: " + String.join("  AND  ", conventions));
        }

        Map<String, WorkspacePathFinder.PathSegment> byTarget = new LinkedHashMap<>();
        for (WorkspacePathFinder.PathSegment hop : scenario.hops()) {
            byTarget.putIfAbsent(lower(hop.getToTable()), hop);
        }
        for (TermScenario.ScenarioTable table : scenario.tables()) {
            String name = table.qualifiedName();
            WorkspacePathFinder.PathSegment hop = byTarget.get(lower(name));
            System.out.printf("    %s %s%s%s%n",
                    table.role() == TermScenario.Role.primary ? "★" : " ",
                    name,
                    table.role() == TermScenario.Role.bridge ? "  [桥接]" : "",
                    hop == null ? "" : joinSummary(hop));
        }
        for (String missing : scenario.unreachable()) {
            // 如实说连不上，别假装子图是完整的——多半是关系还没录进图谱
            System.out.println("      " + missing + "  [连不上入口表，关系可能还没录进图谱]");
        }
    }

    private void printSearchGroup(GraphWorkspace workspace, List<FoldableHit> group, Set<String> sm4Columns,
                                  Map<String, String> lineageHints) {
        FoldableHit first = group.get(0);
        if ("term".equals(first.type())) {
            System.out.printf("  [TERM] %s (%.2f)%s%s%s%n", first.title(), first.score(),
                    detailSuffix(first.businessName(), first.comment(), first.description()),
                    termMappingSuffix(workspace, first.id()),
                    first.candidate() ? " [候选]" : "");
            return;
        }
        if ("metric".equals(first.type())) {
            System.out.printf("  [METRIC] %s (%.2f)%s%s%n", first.title(), first.score(),
                    detailSuffix(first.businessName(), first.comment(), first.description()),
                    first.candidate() ? " [候选]" : "");
            return;
        }
        FoldableHit tableHit = group.stream().filter(h -> "table".equals(h.type())).findFirst().orElse(null);
        List<FoldableHit> columnHits = group.stream().filter(h -> "column".equals(h.type())).toList();

        if (tableHit != null) {
            TableWorkspaceNode table = workspace.getTableByQualifiedName(
                    tableHit.schema() + "." + tableHit.table());
            String columnCount = table == null ? "" : " | " + table.getColumns().size() + "列";
            List<String> conventions = table == null ? List.<String>of()
                    : conventionFilters(workspace, table.getId());
            System.out.printf("  [TABLE] %s.%s (%.2f)%s%s%s%s%n",
                    tableHit.schema(), tableHit.table(), tableHit.score(),
                    detailSuffix(tableHit.businessName(), tableHit.comment(), tableHit.description()),
                    columnCount,
                    conventions.isEmpty() ? "" : " [约定:" + String.join(" AND ", conventions) + "]",
                    tableHit.candidate() ? " [候选]" : "");
            if (!columnHits.isEmpty()) {
                System.out.println("    命中列: " + columnSummary(columnHits, sm4Columns, lineageHints));
            }
            return;
        }
        if (columnHits.size() == 1) {
            FoldableHit hit = columnHits.get(0);
            System.out.printf("  [COLUMN] %s (%.2f)%s%s%s%s%n", hit.title(), hit.score(),
                    detailSuffix(hit.businessName(), hit.comment(), hit.description()),
                    sm4Columns.contains(lower(hit.column())) ? " [SM4]" : "",
                    hit.candidate() ? " [候选]" : "",
                    redundantOfSuffix(hit.redundantOf())
                            + lineageSuffix(lineageHints, hit.schema(), hit.table(), hit.column()));
            return;
        }
        double best = columnHits.stream().mapToDouble(FoldableHit::score).max().orElse(0);
        System.out.printf("  [COLUMN] %s.%s 命中 %d 列 (最高 %.2f): %s%n",
                first.schema(), first.table(), columnHits.size(), best,
                columnSummary(columnHits, sm4Columns, lineageHints));
    }

    /** 最多列 3 个：列名[SM4](业务名或描述或注释)，多的收成"另有N列命中"。 */
    private static String columnSummary(List<FoldableHit> columnHits, Set<String> sm4Columns,
                                        Map<String, String> lineageHints) {
        List<String> parts = new ArrayList<>();
        for (FoldableHit hit : columnHits.subList(0, Math.min(3, columnHits.size()))) {
            String label = hit.businessName() != null && !hit.businessName().isBlank()
                    ? hit.businessName()
                    : (hit.description() != null && !hit.description().isBlank() ? hit.description() : hit.comment());
            parts.add(hit.column()
                    + (sm4Columns.contains(lower(hit.column())) ? "[SM4]" : "")
                    + (label == null || label.isBlank() ? "" : "(" + truncate(label, 20) + ")")
                    + redundantOfSuffix(hit.redundantOf())
                    + lineageSuffix(lineageHints, hit.schema(), hit.table(), hit.column()));
        }
        String summary = String.join(", ", parts);
        if (columnHits.size() > 3) {
            summary += " …另有" + (columnHits.size() - 3) + "列命中";
        }
        return summary;
    }

    /**
     * 术语命中时把它映射到的表 / 字段一起打出来。
     *
     * <p>不打的话术语就是个死路：Agent 搜到 `[TERM] 巡检计划` 之后**无处可去**——
     * 它不知道这个词对应哪张表，还得再猜一次或再敲一条命令。而术语在检索里权重最高
     * （term 8 分档），占着 Top1 却不给出口，是这套机制里最亏的一处。
     *
     * <p>没有映射时明确标 `[未映射]`，不要让它看起来像一条正常结果：
     * 一条既没描述也没映射的术语是纯负收益——它只是把真正的表压低一名。
     */
    private static String termMappingSuffix(GraphWorkspace workspace, String termId) {
        if (termId == null) return " [未映射]";
        List<String> targets = new ArrayList<>();
        for (RelationWorkspaceEdge relation : workspace.getRelations()) {
            if (relation.getType() != RelationType.term_mapping) continue;
            // 被拒绝的映射不算——术语页也不显示它，这里跟着一致
            if (relation.getStatus() == GraphStatus.ignored) continue;
            if (termId.equals(relation.getFrom())) {
                targets.add(columnDisplayName(relation.getTo()));
            }
        }
        if (targets.isEmpty()) return " [未映射]";
        String shown = targets.size() <= 3
                ? String.join(", ", targets)
                : String.join(", ", targets.subList(0, 3)) + " 等 " + targets.size() + " 个";
        return " → " + shown;
    }

    /** 命中列是冗余快照时提示权威源在哪一列——不能让 Agent 挑中它却不知道这不是权威值。 */
    private static String redundantOfSuffix(String redundantOf) {
        return redundantOf == null ? "" : "[冗余，权威源:" + columnDisplayName(redundantOf) + "]";
    }

    /** 业务名、业务描述、数据库注释合成一段展示文本；命中原文就在其中，不再单独重复打印
     * matchedText。comment 和 description 标签分开写——一个是库里的原文，一个是人/Agent
     * 补的解读，混在一起看不出哪句是哪句。 */
    private static String detailSuffix(String businessName, String comment, String description) {
        List<String> parts = new ArrayList<>();
        if (businessName != null && !businessName.isBlank()) parts.add("业务:" + businessName);
        if (description != null && !description.isBlank()) parts.add("描述:" + truncate(description, 60));
        if (comment != null && !comment.isBlank()) parts.add("注释:" + truncate(comment, 60));
        return parts.isEmpty() ? "" : " " + String.join(" | ", parts);
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private int exportWorkspace(GraphWorkspace workspace) throws Exception {
        if (workspace == null) {
            return missingWorkspace();
        }
        if (outputPath == null) {
            outputPath = GraphWorkspaceStore.getWorkspacePath(alias).resolve("workspace-export.json").toString();
        }
        Path path = Path.of(outputPath);
        if (Files.exists(path) && !force) {
            System.err.println("File exists: " + path + " (use --force to overwrite)");
            return 1;
        }
        workspaceStore.exportSnapshot(workspace, path);
        System.out.println("图谱已导出到: " + path);
        return 0;
    }

    private int importWorkspace() throws Exception {
        if ("status".equalsIgnoreCase(subAction)) {
            return showImportStatus();
        }
        if ("reset".equalsIgnoreCase(subAction)) {
            importService.reset(alias);
            System.out.println("导入任务状态已重置");
            return 0;
        }
        if ("resume".equalsIgnoreCase(subAction)) {
            DatabaseConfig config = aliasResolver.resolve(alias);
            try (Connection conn = connectionManager.getConnection(config)) {
                WorkspaceMetadataProvider provider = WorkspaceMetadataProviderFactory.create(conn, config);
                ImportJob job = importService.resume(alias, provider);
                printImportJob(job);
                return job.getStatus() == ImportJobStatus.completed ? 0 : 1;
            }
        }

        if (inputPath != null) {
            Path path = Path.of(inputPath);
            if (!Files.exists(path)) {
                System.err.println("File not found: " + path);
                return 1;
            }
            GraphWorkspace imported = workspaceStore.importSnapshot(path);
            GraphWorkspace toSave = imported;
            GraphWorkspace existing = loadWorkspaceIfExists();
            if (merge) {
                if (existing != null) {
                    toSave = workspaceMerger.merge(imported, existing);
                }
            }
            validator.validate(toSave);
            if (existing == null) {
                workspaceStore.save(toSave);
            } else {
                GraphWorkspace replacement = toSave;
                WorkspaceMutationService.MutationResult result = mutationService.mutate(alias,
                        existing.getManifest().getRevision(), GraphActor.human, "schema import snapshot", current -> {
                            current.setDataSource(replacement.getDataSource());
                            current.setSchemas(new LinkedHashMap<>(replacement.getSchemas()));
                            current.setTables(new LinkedHashMap<>(replacement.getTables()));
                            current.setTerms(new LinkedHashMap<>(replacement.getTerms()));
                            current.setLineage(new LinkedHashMap<>(replacement.getLineage()));
                            current.setRelations(new ArrayList<>(replacement.getRelations()));
                            return new WorkspaceMutationService.MutationOutcome(
                                    current.getManifest().getId(), ChangeOperation.update);
                        });
                if (!result.isSuccess()) {
                    result.getErrors().forEach(System.err::println);
                    return 1;
                }
            }
            System.out.println("图谱已导入到: " + GraphWorkspaceStore.getWorkspacePath(toSave.getManifest().getAlias()));
            return 0;
        }

        if (fromDb) {
            DatabaseConfig config = aliasResolver.resolve(alias);
            try (Connection conn = connectionManager.getConnection(config)) {
                WorkspaceMetadataProvider provider = WorkspaceMetadataProviderFactory.create(conn, config);
                ImportOptions options = new ImportOptions();
                // Oracle 数据库如果没有指定 schema，默认使用用户名
                String effectiveSchema = importSchema;
                if ((effectiveSchema == null || effectiveSchema.isBlank()) && "oracle".equalsIgnoreCase(config.getType())) {
                    effectiveSchema = config.getUsername();
                }
                options.setSchemaFilter(effectiveSchema);
                options.setTableFilter(importTable);
                options.setBatchSize(batchSize == null ? 20 : batchSize);
                options.setMerge(merge);
                options.setForceOverwrite(forceOverwrite);
                ImportJob job = importService.start(alias, provider, options);
                printImportJob(job);
                return job.getStatus() == ImportJobStatus.completed ? 0 : 1;
            }
        }

        return usageError("""
                Usage:
                  sql-cli %s schema import --from-db [--schema S] [--force-overwrite]
                  sql-cli %s schema import status|resume|reset
                  sql-cli %s schema import --input <snapshot.json>
                """.formatted(alias, alias, alias).stripTrailing());
    }

    private int showImportStatus() throws Exception {
        ImportJob job = importService.status(alias);
        if (job == null) {
            if (jsonOutput) {
                CliJson.printSuccess(null);
            } else {
                System.out.println("没有进行中的导入任务");
            }
            return 0;
        }
        if (jsonOutput) {
            CliJson.printSuccess(job);
            return 0;
        }
        printImportJob(job);
        return 0;
    }

    private int editWorkspace(GraphWorkspace workspace) throws Exception {
        if (workspace == null) {
            return missingWorkspace();
        }
        if (editColumn == null && editTable == null) {
            return usageError("""
                    Usage:
                      sql-cli %s schema edit --table <name> --description '...' [--business-name '...']
                      sql-cli %s schema edit --column <schema.table.column> --description '...' [--business-name '...'] [--semantic-type TYPE]
                    """.formatted(alias, alias).stripTrailing());
        }
        WorkspaceMutationService.MutationResult result = mutationService.mutate(alias,
                workspace.getManifest().getRevision(), GraphActor.agent, "schema edit", current -> {
                    if (editColumn != null) {
                        ColumnWorkspaceNode column = resolveColumn(current, editColumn);
                        if (column == null) throw new IllegalArgumentException("Column not found: " + editColumn);
                        if (description != null) {
                            column.setDescription(description);
                        }
                        if (example != null) {
                            ColumnValueHints hints = valueHints(column);
                            if (!hints.getSampleValues().contains(example)) {
                                hints.getSampleValues().add(example);
                            }
                        }
                        // 值域是封闭集合，整体替换而不是追加：域变了要的是新的域，不是并集。
                        // 传空串表示清空。
                        if (enumValuesCsv != null) {
                            valueHints(column).setEnumValues(parseEnumValues(enumValuesCsv));
                        }
                        if (valueFormat != null) {
                            valueHints(column).setFormat(valueFormat.isBlank() ? null : valueFormat);
                        }
                        if (addTag != null) column.getAttributes().put("tag", addTag);
                        if (addConstraint != null) column.getAttributes().put("constraint", addConstraint);
                        if (editBusinessName != null) column.setBusinessName(editBusinessName);
                        if (editSemanticType != null) column.setSemanticType(parseSemanticType(editSemanticType));
                        if (boost != null) column.setBoost(normalizeBoost(boost));
                        String id = resolveColumnId(current, editColumn);
                        if (editRedundantOf != null) {
                            applyRedundantOf(current, column, id, editRedundantOf);
                        }
                        // CLI 的写入者是 agent（本次 mutate 的 actor），它不能给自己盖「已确认」章。
                        // 字段级没有候选态，verified 位就是闸门（理由见 ColumnWorkspaceNode 类头）：
                        // Agent 写进去的值立刻可读，但标着未确认，看板会把它算进待确认数。
                        // 值改了，上一次人工确认就过期了，连同 confidence 一起清掉而不是留着——
                        // 留着等于用旧值的确认给新值背书。确认动作在 Web UI 的字段编辑里做。
                        column.setVerified(null);
                        column.setConfidence(null);
                        return new WorkspaceMutationService.MutationOutcome(id, ChangeOperation.upsert_column);
                    }
                    TableWorkspaceNode table = current.getTableByQualifiedName(editTable);
                    if (table == null) throw new IllegalArgumentException("Table not found: " + editTable);
                    if (description != null) {
                        table.setDescription(description);
                    }
                    // F1：表粒度，人工维护、导入不覆盖，与 description 同组（见
                    // GraphWorkspaceMerger 类头字段分类）。走 commandArgs 自解析，
                    // 理由同 add-metric 的 --numerator：不去改共享的 SqlCli.java 映射表。
                    String grainText = option(commandArgs, "--grain");
                    if (grainText != null) table.setGrain(grainText.isBlank() ? null : grainText);
                    if (editBusinessName != null) table.setBusinessName(editBusinessName);
                    if (addTag != null && !table.getTags().contains(addTag)) table.getTags().add(addTag);
                    if (boost != null) table.setBoost(normalizeBoost(boost));
                    // 同上：agent 写入不自封已确认。表有 status 但不动它——status 说的是
                    // 「这张表存不存在」（导入建的，早就存在），描述改没改跟它无关。
                    table.setVerified(null);
                    table.setConfidence(null);
                    return new WorkspaceMutationService.MutationOutcome(table.getId(), ChangeOperation.upsert_table);
                });
        return printMutationResult(result, "图谱已更新");
    }

    private static boolean isNonDefaultBoost(Double boost) {
        return boost != null && boost != 1.0;
    }

    /** boost 必须 > 0；传 1 视为恢复默认，存 null 保持文件干净。 */
    private static Double normalizeBoost(double value) {
        if (value <= 0) {
            throw new IllegalArgumentException("--boost 必须大于 0: " + value);
        }
        return value == 1.0 ? null : value;
    }

    /** 按需建 valueHints：只有真写了值域的字段才带这个对象，其余字段保持 null 不落盘。 */
    private static ColumnValueHints valueHints(ColumnWorkspaceNode column) {
        if (column.getValueHints() == null) {
            column.setValueHints(new ColumnValueHints());
        }
        return column.getValueHints();
    }

    /**
     * 解析 {@code --enum-values}，每项形如 {@code 0=待付款}。
     * 拆完 CSV 后校验与规范化委托给 {@link ColumnValueHints#normalizeEnumValues}，
     * 和 UI 的值域编辑共用同一份规则。
     */
    private static List<String> parseEnumValues(String csv) {
        return ColumnValueHints.normalizeEnumValues(csvValues(csv));
    }

    /**
     * 解析 {@code --semantic-type}，与 UI 侧 {@code WorkspaceMutationService.applyColumnPatch}
     * 走同一个 {@link SemanticType#fromValue}。
     *
     * {@code fromValue} 本身解析不出来时返回 null（那是给旧图谱文件反序列化兜底的，
     * 见 {@link SemanticType} javadoc）——但命令行输入非法值不该被这个兜底悄悄吞掉，
     * 否则 Agent 写错一个词，图谱里留下的是"看起来改了、其实没改"的字段，比报错更难查。
     * 所以这里必须显式抛出，并把全部合法取值连中文标签一起列出来，
     * 不然 Agent 只能对着一个英文 enum 名字猜。
     */
    private static SemanticType parseSemanticType(String value) {
        if (value.isBlank()) {
            return null;
        }
        SemanticType parsed = SemanticType.fromValue(value);
        if (parsed != null) {
            return parsed;
        }
        throw new IllegalArgumentException(
                "未知的 --semantic-type: " + value + "；合法取值: " + semanticTypeOptionsHelp());
    }

    private int showStats(GraphWorkspace workspace) throws Exception {
        if (workspace == null) {
            return missingWorkspace();
        }
        validator.validate(workspace);
        if (jsonOutput) {
            CliJson.printSuccess(statsPayload(workspace));
            return 0;
        }
        printStats(workspace);
        return 0;
    }

    /** stats 载荷：manifest 里的五个计数 + 术语数 + 候选计数（候选按 status 现算，不落盘）。 */
    private Map<String, Object> statsPayload(GraphWorkspace workspace) {
        WorkspaceStats stats = workspace.getManifest().getStats();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemas", stats.getSchemas());
        payload.put("tables", stats.getTables());
        payload.put("columns", stats.getColumns());
        payload.put("terms", workspace.getTerms().size());
        payload.put("relations", stats.getRelations());
        payload.put("candidateRelations", candidateRelations(workspace));
        payload.put("ignoredRelations", stats.getIgnoredRelations());
        payload.put("candidateTerms", candidateTerms(workspace));
        payload.put("validationIssues", stats.getValidationIssues());
        return payload;
    }

    private static long candidateRelations(GraphWorkspace workspace) {
        return workspace.getRelations().stream()
                .filter(relation -> relation.getStatus() == GraphStatus.candidate).count();
    }

    private static long candidateTerms(GraphWorkspace workspace) {
        return workspace.getTerms().values().stream()
                .filter(term -> term.getStatus() == GraphStatus.candidate).count();
    }

    private int addTerm(GraphWorkspace workspace) throws Exception {
        if (workspace == null) {
            return missingWorkspace();
        }
        if (termName == null || termName.isBlank()) {
            return usageError("Usage: sql-cli " + alias
                    + " schema add-term <name> [--display-name ...] [--aliases a,b]"
                    + " [--map schema.table|schema.table.column,...]");
        }
        // 写入闸门：不带 --map 也不带 --aliases 就拒绝。判据只看这两样——
        // 术语要么挂 ≥2 个对象（一词多物），要么有同义词（多词一物）；两者都没有时
        // 这条术语跟 businessName 完全重叠，而且更差：多一个对象要维护，
        // 还占着检索的最高权重档（真库上就躺着一条这样的，`顺序巡检`）。
        // description 不算数：一条只有描述的术语，内容原样搬去 businessName 就是它的替代品。
        if (isShellTerm(workspace)) {
            System.err.println("拒绝写入空壳术语：不带 --map 也不带 --aliases 的术语与 businessName 完全重叠。");
            System.err.println("如果「" + termName + "」说的是某个表/字段的另一种叫法，改用：");
            System.err.println("  sql-cli " + alias + " schema edit --table <schema.table> --business-name \""
                    + termName + "\"");
            System.err.println("  sql-cli " + alias + " schema edit --column <schema.table.column> --business-name \""
                    + termName + "\"");
            System.err.println("如果它确实要挂多个对象或有同义词，带上 --map 或 --aliases 重试。");
            return 1;
        }
        WorkspaceMutationService.MutationResult result = mutationService.mutate(alias,
                workspace.getManifest().getRevision(), GraphActor.agent, "schema add-term", current -> {
                    List<String> mappedTargetIds = new ArrayList<>();
                    for (String ref : csvValues(mappedRefsCsv)) {
                        String targetId = resolveTermTargetId(current, ref);
                        if (targetId == null) throw new IllegalArgumentException(
                                "Unable to resolve mapped table or column: " + ref);
                        mappedTargetIds.add(targetId);
                    }
                    // 入口的死引用必须硬失败：一个指向不存在对象的 primaryTarget，
                    // 之后每次展开子图都会静默地展不开，而没有任何地方会报错。
                    // 与 add-lineage 的 Column not found 同一条规矩。
                    String primaryTargetId = null;
                    if (primaryTargetRef != null && !primaryTargetRef.isBlank()) {
                        primaryTargetId = resolveTermTargetId(current, primaryTargetRef);
                        if (primaryTargetId == null) throw new IllegalArgumentException(
                                "Unable to resolve primary target: " + primaryTargetRef);
                    }
                    String id = "term:" + alias + ":" + termName;
                    TermWorkspaceNode term = current.getTerms().computeIfAbsent(id,
                            ignored -> TermWorkspaceNode.create(alias, termName, GraphActor.agent));
                    if (displayName != null) term.setDisplayName(displayName);
                    if (description != null) term.setDescription(description);
                    if (primaryTargetId != null) term.setPrimaryTarget(primaryTargetId);
                    // filters 整体替换而不是追加：它是一组共同成立的条件，追加会把上一次
                    // 写错的条件永久留在里面，而且没有删除入口。不给 --filter 就不动。
                    if (!termFilters.isEmpty()) term.setFilters(new ArrayList<>(termFilters));
                    mergeCsv(term.getAliases(), aliasesCsv);
                    mergeCsv(term.getNegativeAliases(), negativeAliasesCsv);
                    // 新建的映射边必须报给 MutationOutcome。开了图谱审批时，暂存走的是
                    // 单对象补丁——不声明的话这些边进不了 payload，批准之后一条都不会落地，
                    // 而且术语本身没变时连审批都建不出来，直接报「没有修改」。
                    List<String> createdEdges = new ArrayList<>();
                    for (String targetId : mappedTargetIds) {
                        RelationWorkspaceEdge relation = RelationWorkspaceEdge.create(
                                alias, RelationType.term_mapping, term.getId(), targetId, GraphActor.agent);
                        if (current.getRelations().stream().noneMatch(edge -> edge.getId().equals(relation.getId()))) {
                            current.getRelations().add(relation);
                            createdEdges.add(relation.getId());
                        }
                    }
                    return new WorkspaceMutationService.MutationOutcome(
                            term.getId(), ChangeOperation.upsert, createdEdges);
                });
        return printMutationResult(result, "Term 已更新: " + result.getTargetId());
    }

    /**
     * 这次调用之后，这条术语会不会「同义词、映射」两样全空。
     *
     * <p>看的是**合并后的结果**而不是本次入参：给一条已经挂了映射的术语补 `--display-name`
     * 是正当的，不该被拦。{@code description} 不参与判断——见 {@link #addTerm} 里的注释。
     */
    private boolean isShellTerm(GraphWorkspace workspace) {
        if (!csvValues(aliasesCsv).isEmpty()) return false;
        if (!csvValues(mappedRefsCsv).isEmpty()) return false;
        TermWorkspaceNode existing = workspace.getTerms().get("term:" + alias + ":" + termName);
        if (existing == null) return true;
        return existing.getAliases().isEmpty()
                && workspace.getRelations().stream().noneMatch(edge ->
                        edge.getType() == RelationType.term_mapping
                                && edge.getFrom().equals(existing.getId()));
    }

    private int addRelation(GraphWorkspace workspace) throws Exception {
        if (workspace == null) {
            return missingWorkspace();
        }
        if (relationType == null || fromRef == null || toRef == null) {
            return usageError("Usage: sql-cli " + alias
                    + " schema add-relation --type <type> --from <ref> --to <ref>");
        }
        String fromId = resolveNodeRef(workspace, fromRef);
        String toId = resolveNodeRef(workspace, toRef);
        if (fromId == null || toId == null) {
            System.err.println("Unable to resolve relation endpoints");
            return 1;
        }
        RelationType type;
        try {
            type = RelationType.valueOf(relationType);
        } catch (IllegalArgumentException e) {
            System.err.println("Unknown relation type: " + relationType);
            return 1;
        }
        GraphObjectKind fromKind = workspace.resolveNodeKind(fromId);
        GraphObjectKind toKind = workspace.resolveNodeKind(toId);
        if (fromKind == null || toKind == null) {
            System.err.println("Relation endpoint does not exist");
            return 1;
        }
        // 推断类关系没有默认置信度，缺失时 effectiveConfidence 为 null，校验会给出明确报错
        Double effectiveConfidence = confidence != null ? confidence : type.defaultConfidence();
        RelationValidator.ValidationResult validation = relationValidator.validate(
                type, fromKind, toKind, effectiveConfidence, verifiedFlag);
        if (!validation.isValid()) {
            validation.getErrors().forEach(System.err::println);
            return 1;
        }
        if (!relationValidator.isUserCreatable(type)) {
            System.err.println("Relation type cannot be created manually: " + type);
            return 1;
        }

        WorkspaceMutationService.MutationResult result = mutationService.mutate(alias,
                workspace.getManifest().getRevision(), GraphActor.agent, "schema add-relation", current -> {
                    RelationWorkspaceEdge candidate = RelationWorkspaceEdge.create(
                            alias, type, fromId, toId, GraphActor.agent);
                    RelationWorkspaceEdge relation = current.getRelations().stream()
                            .filter(existing -> existing.getId().equals(candidate.getId()))
                            .findFirst().orElse(candidate);
                    if (effectiveConfidence != null) relation.setConfidence(effectiveConfidence);
                    if (joinExpression != null) relation.setJoinExpression(joinExpression);
                    if (verifiedFlag != null) relation.setVerified(verifiedFlag);
                    if (relation == candidate) current.getRelations().add(relation);
                    return new WorkspaceMutationService.MutationOutcome(
                            relation.getId(), ChangeOperation.upsert_relation);
                });
        return printMutationResult(result, "Relation 已更新: " + result.getTargetId());
    }

    /**
     * 写入一条字段级血缘。agent 身份 → 候选状态；id 由 (target, through) 决定，重跑是更新。
     *
     * <p>闸门在这里而不在模型里：模型层 {@link LineageRecord#create} 仍允许 through 为空
     * （导入老快照要能过），只有新写入才被要求把「去哪改」说清楚。
     */
    private int addLineage(GraphWorkspace workspace) throws Exception {
        if (workspace == null) {
            return missingWorkspace();
        }
        if (targetRef == null || sourceRefsCsv == null || csvValues(sourceRefsCsv).isEmpty()) {
            return usageError("Usage: sql-cli " + alias
                    + " schema add-lineage --target <ref> --source <ref>[,<ref>...]"
                    + " --through T [--kind K] [--expression E]");
        }
        if (through == null || through.isBlank()) {
            return usageError("--through 必填：视图名 / ETL 作业 / 类名.方法 / Mapper statement id。"
                    + "没有它 agent 知道「会影响」却不知道去哪改");
        }
        LineageKind explicitKind;
        try {
            explicitKind = lineageKindArg == null ? null
                    : LineageKind.valueOf(lineageKindArg.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return usageError("--kind 只认 identity / transformation / aggregation / rule，实际: " + lineageKindArg);
        }
        List<String> sourceRefs = csvValues(sourceRefsCsv);
        LineageKind kind = explicitKind != null ? explicitKind : LineageKind.infer(sourceRefs, expression);
        // 形状校验先按引用做一遍：源含目标、类别与形状不符都不用等解析列 id
        String shapeError = LineageKind.validate(kind, targetRef, sourceRefs, expression);
        if (shapeError != null) {
            return usageError(shapeError);
        }
        WorkspaceMutationService.MutationResult result = mutationService.mutate(alias,
                workspace.getManifest().getRevision(), GraphActor.agent, "schema add-lineage", current -> {
                    String targetId = resolveColumnId(current, targetRef);
                    if (targetId == null) {
                        throw new IllegalArgumentException("Column not found: " + targetRef);
                    }
                    List<String> sourceIds = new ArrayList<>();
                    for (String ref : sourceRefs) {
                        String sourceId = resolveColumnId(current, ref);
                        if (sourceId == null) {
                            throw new IllegalArgumentException("Column not found: " + ref);
                        }
                        sourceIds.add(sourceId);
                    }
                    // 引用解析成 id 之后再验一次：a.b.c 与 A.B.C 在引用层看着不同，id 层是同一列
                    String idShapeError = LineageKind.validate(kind, targetId, sourceIds, expression);
                    if (idShapeError != null) {
                        throw new IllegalArgumentException(idShapeError);
                    }
                    LineageRecord record = LineageRecord.create(
                            alias, targetId, sourceIds, expression, through, kind, GraphActor.agent);
                    current.getLineage().put(record.getId(), record);
                    return new WorkspaceMutationService.MutationOutcome(record.getId(), ChangeOperation.upsert);
                });
        return printMutationResult(result, "Lineage 已更新: " + result.getTargetId() + " [" + kind + "]");
    }

    /**
     * 删除一条血缘。按 --id 精确删；按 --target 时该列只有一条就删，多于一条列出来让人带 --through 挑——
     * 「删掉这一列的全部血缘」不提供，两段代码写同一列是两条独立事实，不该一次抹掉。
     */
    private int removeLineage(GraphWorkspace workspace) throws Exception {
        if (workspace == null) {
            return missingWorkspace();
        }
        if ((lineageId == null || lineageId.isBlank()) && (targetRef == null || targetRef.isBlank())) {
            return usageError("Usage: sql-cli " + alias
                    + " schema remove-lineage --target <ref> [--through T] | --id <lineage id>");
        }
        String id;
        if (lineageId != null && !lineageId.isBlank()) {
            id = lineageId;
            if (!workspace.getLineage().containsKey(id)) {
                return commandFailure("Lineage not found: " + id);
            }
        } else {
            String targetId = resolveColumnId(workspace, targetRef);
            if (targetId == null) {
                return commandFailure("Column not found: " + targetRef);
            }
            List<LineageRecord> candidates = workspace.getLineage().values().stream()
                    .filter(r -> targetId.equalsIgnoreCase(r.getTarget()))
                    .filter(r -> through == null || through.isBlank() || through.equals(r.getThrough()))
                    .toList();
            if (candidates.isEmpty()) {
                return commandFailure("该列没有血缘记录: " + targetRef
                        + (through == null ? "" : "（through = " + through + "）"));
            }
            if (candidates.size() > 1) {
                StringBuilder message = new StringBuilder("该列有 " + candidates.size()
                        + " 条血缘，带 --through 或 --id 指定一条：");
                for (LineageRecord record : candidates) {
                    message.append("\n  --id ").append(record.getId())
                            .append("    [").append(record.getLineageKind()).append("] through: ")
                            .append(record.getThrough());
                }
                return commandFailure(message.toString());
            }
            id = candidates.get(0).getId();
        }
        WorkspaceMutationService.MutationResult result = mutationService.deleteLineage(
                alias, id, workspace.getManifest().getRevision(), GraphActor.agent, "schema remove-lineage");
        return printMutationResult(result, "Lineage 已删除: " + id);
    }

    /** 查询字段血缘：默认上游（该列由谁推导而来），--downstream 查下游。 */
    private int showLineage(GraphWorkspace workspace) {
        if (workspace == null) {
            return missingWorkspace();
        }
        if (tableName == null) {
            return usageError("Usage: sql-cli " + alias
                    + " schema lineage <schema.table.column> [--upstream|--downstream] [--depth N] [--json]");
        }
        String columnId = resolveColumnId(workspace, tableName);
        if (columnId == null) {
            return commandFailure("Column not found: " + tableName);
        }
        LineageGraph graph = new LineageGraph(workspace.getLineage().values());
        List<LineageRecord> records = lineageDownstream
                ? graph.downstream(columnId, depth) : graph.upstream(columnId, depth);
        String direction = lineageDownstream ? "downstream" : "upstream";
        if (jsonOutput) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("column", columnId);
            payload.put("direction", direction);
            payload.put("depth", depth);
            // openLineage 是 lineageKind 的纯函数，只在输出里算，不落盘。
            // 用类里配好的 jsonMapper：另起一个 ObjectMapper 会把 updatedAt 序列化成时间戳数组，
            // 跟别的接口的 ISO 字符串对不上
            List<Map<String, Object>> rows = new ArrayList<>();
            for (LineageRecord record : records) {
                Map<String, Object> row = jsonMapper
                        .convertValue(record, new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {});
                row.put("openLineage", record.getLineageKind() == null ? null : record.getLineageKind().openLineage());
                rows.add(row);
            }
            payload.put("records", rows);
            CliJson.printSuccess(payload);
            return 0;
        }
        if (records.isEmpty()) {
            System.out.println("No lineage found for: " + columnId + " (" + direction + ")");
            return 0;
        }
        System.out.printf("Lineage (%s, depth %d) for %s:%n", direction, depth, columnId);
        for (LineageRecord record : records) {
            List<String> sourceNames = record.getSources().stream().map(this::columnRefForId).toList();
            System.out.printf("  %s%s <- %s%n", candidateMark(record.getStatus()),
                    columnRefForId(record.getTarget()), String.join(", ", sourceNames));
            System.out.println("    kind: " + lineageKindLabel(record.getLineageKind()));
            if (record.getExpression() != null) {
                System.out.println("    expression: " + record.getExpression());
            }
            if (record.getThrough() != null) {
                System.out.println("    through: " + record.getThrough());
            }
        }
        return 0;
    }

    /**
     * 创建/更新一条 metric。以 agent 身份写入 -> 候选状态；id 由 name 决定，重复写入是幂等更新。
     * dimensions / joinPath 整体替换而不是追加（跟 --enum-values 同一个约定）：传了就是新值，
     * 不传就保持原值，传空串清空——避免"追加"语义下想删一项还得先读出全量再拼回去。
     */
    private int addMetric(GraphWorkspace workspace) throws Exception {
        if (workspace == null) {
            return missingWorkspace();
        }
        if (metricName == null || metricName.isBlank()) {
            return usageError("Usage: sql-cli " + alias
                    + " schema add-metric <name> --expression <EXPR> [--filters F] [--business-name N]"
                    + " [--aliases A,B] [--grain-column <ref>] [--grains day,week,month]"
                    + " [--dimensions <ref>,...] [--join-path relId|type,...]"
                    + " [--numerator EXPR --denominator EXPR] [--numerator-filters F] [--denominator-filters F]"
                    + " [--additivity additive|semi_additive|non_additive]");
        }
        List<MetricRecord.MetricJoinStep> joinSteps;
        try {
            joinSteps = parseJoinPath(joinPathCsv);
        } catch (IllegalArgumentException e) {
            return usageError(e.getMessage());
        }
        // F4b：分子/分母走 commandArgs 自解析（跟 policy/design 子命令同一套路子），不占用
        // SqlCli.java 那张 --flag -> 字段 的手工映射表——这条命令归属这一份改动独占，
        // 不去动共享的入口文件。
        String numeratorExpr = option(commandArgs, "--numerator");
        String numeratorFilters = option(commandArgs, "--numerator-filters");
        String denominatorExpr = option(commandArgs, "--denominator");
        String denominatorFilters = option(commandArgs, "--denominator-filters");
        String additivityToken = option(commandArgs, "--additivity");
        MetricRecord.Additivity additivityValue;
        try {
            additivityValue = parseAdditivity(additivityToken);
        } catch (IllegalArgumentException e) {
            return usageError(e.getMessage());
        }

        WorkspaceMutationService.MutationResult result = mutationService.mutate(alias,
                workspace.getManifest().getRevision(), GraphActor.agent, "schema add-metric", current -> {
                    String grainColumnId = null;
                    if (grainColumnRef != null && !grainColumnRef.isBlank()) {
                        grainColumnId = resolveColumnId(current, grainColumnRef);
                        if (grainColumnId == null) {
                            throw new IllegalArgumentException("Column not found (grain-column): " + grainColumnRef);
                        }
                    }
                    List<String> dimensionColumnIds = new ArrayList<>();
                    for (String ref : csvValues(dimensionsCsv)) {
                        String columnId = resolveColumnId(current, ref);
                        if (columnId == null) {
                            throw new IllegalArgumentException("Column not found (dimensions): " + ref);
                        }
                        dimensionColumnIds.add(columnId);
                    }
                    for (MetricRecord.MetricJoinStep step : joinSteps) {
                        boolean exists = current.getRelations().stream()
                                .anyMatch(edge -> edge.getId().equals(step.getRelationId()));
                        if (!exists) {
                            throw new IllegalArgumentException("Relation not found (join-path): " + step.getRelationId());
                        }
                    }

                    String id = GraphIds.metricId(alias, metricName);
                    MetricRecord metric = current.getMetrics().computeIfAbsent(id,
                            ignored -> MetricRecord.create(alias, metricName, GraphActor.agent));
                    if (expression != null) metric.setExpression(expression);
                    if (filters != null) metric.setFilters(filters);
                    if (editBusinessName != null) metric.setBusinessName(editBusinessName);
                    mergeCsv(metric.getAliases(), aliasesCsv);
                    if (grainColumnId != null || grainsCsv != null) {
                        MetricRecord.MetricGrain grain = metric.getGrain() != null
                                ? metric.getGrain() : new MetricRecord.MetricGrain();
                        if (grainColumnId != null) grain.setTimeColumn(grainColumnId);
                        if (grainsCsv != null) grain.setGrains(new ArrayList<>(csvValues(grainsCsv)));
                        metric.setGrain(grain);
                    }
                    if (dimensionsCsv != null) {
                        metric.setDimensions(dimensionColumnIds);
                    }
                    if (joinPathCsv != null) {
                        metric.setJoinPath(joinSteps);
                    }
                    if (numeratorExpr != null) {
                        MetricRecord.MetricComponent numerator = metric.getNumerator() != null
                                ? metric.getNumerator() : new MetricRecord.MetricComponent();
                        numerator.setExpression(numeratorExpr);
                        if (numeratorFilters != null) {
                            numerator.setFilters(numeratorFilters.isBlank() ? null : numeratorFilters);
                        }
                        metric.setNumerator(numerator);
                    } else if (numeratorFilters != null) {
                        if (metric.getNumerator() == null) {
                            throw new IllegalArgumentException("--numerator-filters 需要先用 --numerator 声明分子表达式");
                        }
                        metric.getNumerator().setFilters(numeratorFilters.isBlank() ? null : numeratorFilters);
                    }
                    if (denominatorExpr != null) {
                        MetricRecord.MetricComponent denominator = metric.getDenominator() != null
                                ? metric.getDenominator() : new MetricRecord.MetricComponent();
                        denominator.setExpression(denominatorExpr);
                        if (denominatorFilters != null) {
                            denominator.setFilters(denominatorFilters.isBlank() ? null : denominatorFilters);
                        }
                        metric.setDenominator(denominator);
                    } else if (denominatorFilters != null) {
                        if (metric.getDenominator() == null) {
                            throw new IllegalArgumentException("--denominator-filters 需要先用 --denominator 声明分母表达式");
                        }
                        metric.getDenominator().setFilters(denominatorFilters.isBlank() ? null : denominatorFilters);
                    }
                    if (additivityValue != null) {
                        metric.setAdditivity(additivityValue);
                    }
                    metric.touch(GraphActor.agent);
                    return new WorkspaceMutationService.MutationOutcome(metric.getId(), ChangeOperation.upsert);
                });
        warnIfGrainsCannotExpand(workspace);
        return printMutationResult(result, "Metric 已更新: " + result.getTargetId());
    }

    /**
     * 声明了 grains 但这个库展不开时间粒度，在**定义的时候**就说，别等 expand-metric 才炸。
     *
     * <p>定义指标的人才是能决定怎么办的人（换库、改口径、或者接受只能不带粒度展开）；
     * 等到几天后别人来消费才发现，那个人往往既不知道原因也不知道该找谁。
     * 只是提醒不是失败——指标定义本身是有效知识，库以后可能会换，
     * 而且不带 {@code --grain} 的展开一直是可用的。
     */
    private void warnIfGrainsCannotExpand(GraphWorkspace workspace) {
        if (grainsCsv == null || grainsCsv.isBlank() || workspace == null
                || workspace.getDataSource() == null) {
            return;
        }
        String dbType = workspace.getDataSource().getDbType();
        if (!com.sqlcli.metric.GrainSqlDialect.supportsDialect(dbType)) {
            System.err.println("[提醒] 已记下 grains，但当前库类型 '" + dbType
                    + "' 还不支持时间粒度展开，expand-metric 带 --grain 会失败；"
                    + "不带 --grain 的展开不受影响。");
        }
    }

    /** {@code relId1|inner,relId2|left}：relationId 本身含冒号（{@code relation:alias:type:from->to}），
     * 不能拿冒号当分隔符，用竖线区分 relationId 和 joinType。 */
    private List<MetricRecord.MetricJoinStep> parseJoinPath(String csv) {
        List<MetricRecord.MetricJoinStep> steps = new ArrayList<>();
        for (String raw : csvValues(csv)) {
            int sep = raw.lastIndexOf('|');
            if (sep < 0) {
                throw new IllegalArgumentException(
                        "join-path 格式应为 relationId|joinType（joinType 为 inner 或 left），收到: " + raw);
            }
            String relationId = raw.substring(0, sep).trim();
            String typeToken = raw.substring(sep + 1).trim().toLowerCase(Locale.ROOT);
            MetricRecord.MetricJoinType joinType;
            try {
                joinType = MetricRecord.MetricJoinType.valueOf(typeToken);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("未知 joinType: " + typeToken + "（合法值: inner, left）");
            }
            MetricRecord.MetricJoinStep step = new MetricRecord.MetricJoinStep();
            step.setRelationId(relationId);
            step.setJoinType(joinType);
            steps.add(step);
        }
        return steps;
    }

    /** {@code null} 表示没传 {@code --additivity}，跟"传了但清空"（本字段没有清空语义，
     * 枚举没有空值这个概念）区分开——不传就不动已有值。 */
    private MetricRecord.Additivity parseAdditivity(String token) {
        if (token == null) {
            return null;
        }
        try {
            return MetricRecord.Additivity.valueOf(token.trim().toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知 --additivity: " + token
                    + "（合法值: additive, semi_additive, non_additive）");
        }
    }

    private int listMetrics(GraphWorkspace workspace) {
        if (workspace == null) {
            return missingWorkspace();
        }
        if (jsonOutput) {
            CliJson.printSuccess(workspace.getMetrics().values());
            return 0;
        }
        if (workspace.getMetrics().isEmpty()) {
            System.out.println("没有已定义的指标，用 add-metric 创建。");
            return 0;
        }
        System.out.println("Metrics:");
        workspace.getMetrics().values().stream()
                .sorted(Comparator.comparing(MetricRecord::getName, String.CASE_INSENSITIVE_ORDER))
                .forEach(metric -> {
                    String business = metric.getBusinessName() != null ? " (" + metric.getBusinessName() + ")" : "";
                    String expr;
                    if (metric.isRatio()) {
                        expr = " = " + metric.getNumerator().getExpression() + " / " + metric.getDenominator().getExpression();
                    } else {
                        expr = metric.getExpression() != null ? " = " + metric.getExpression() : "";
                    }
                    MetricRecord.Additivity additivity = metric.effectiveAdditivity();
                    String additivityTag = additivity != null ? " [" + additivity + "]" : "";
                    System.out.printf("  %s%s%s%s%s%n", candidateMark(metric.getStatus()), metric.getName(), business,
                            expr, additivityTag);
                });
        return 0;
    }

    private int showMetric(GraphWorkspace workspace) {
        if (workspace == null) {
            return missingWorkspace();
        }
        if (metricName == null || metricName.isBlank()) {
            return usageError("Usage: sql-cli " + alias + " schema metric <name> [--json]");
        }
        MetricRecord metric = workspace.getMetrics().get(GraphIds.metricId(alias, metricName));
        if (metric == null) {
            return commandFailure("Metric not found: " + metricName);
        }
        if (jsonOutput) {
            CliJson.printSuccess(metric);
            return 0;
        }
        System.out.println("Metric: " + metric.getName());
        System.out.println("Status: " + metric.getStatus());
        if (metric.getBusinessName() != null) System.out.println("Business: " + metric.getBusinessName());
        if (!metric.getAliases().isEmpty()) System.out.println("Aliases: " + String.join(", ", metric.getAliases()));
        if (metric.isRatio()) {
            System.out.println("Numerator: " + metric.getNumerator().getExpression()
                    + (metric.getNumerator().getFilters() != null ? "  filters: " + metric.getNumerator().getFilters() : ""));
            System.out.println("Denominator: " + metric.getDenominator().getExpression()
                    + (metric.getDenominator().getFilters() != null ? "  filters: " + metric.getDenominator().getFilters() : ""));
        } else {
            System.out.println("Expression: " + metric.getExpression());
        }
        if (metric.getFilters() != null) System.out.println("Filters: " + metric.getFilters());
        if (metric.effectiveAdditivity() != null) System.out.println("Additivity: " + metric.effectiveAdditivity());
        MetricRecord.MetricGrain grain = metric.getGrain();
        if (grain != null && grain.getTimeColumn() != null) {
            System.out.println("Grain: " + columnRefForId(grain.getTimeColumn())
                    + " [" + String.join(",", grain.getGrains()) + "]");
        }
        if (!metric.getDimensions().isEmpty()) {
            System.out.println("Dimensions: " + metric.getDimensions().stream()
                    .map(this::columnRefForId).collect(Collectors.joining(", ")));
        }
        if (!metric.getJoinPath().isEmpty()) {
            System.out.println("JoinPath:");
            for (MetricRecord.MetricJoinStep step : metric.getJoinPath()) {
                System.out.println("  " + step.getJoinType() + " " + step.getRelationId());
            }
        }
        return 0;
    }

    /**
     * 把指标展开成 SQL——metric 价值兑现的唯一时刻。方言类型直接读图谱自带的
     * {@code dataSource.dbType}（导入时记下的），不需要连库、也不需要本机配了这个别名。
     */
    private int expandMetric(GraphWorkspace workspace) throws Exception {
        if (workspace == null) {
            return missingWorkspace();
        }
        if (metricName == null || metricName.isBlank()) {
            return usageError("Usage: sql-cli " + alias
                    + " schema expand-metric <name> [--grain G] [--dimensions <ref>,...]"
                    + " [--time-from D] [--time-to D] [--json]");
        }
        MetricRecord metric = workspace.getMetrics().get(GraphIds.metricId(alias, metricName));
        if (metric == null) {
            return commandFailure("Metric not found: " + metricName);
        }
        List<String> dimensionColumnIds = new ArrayList<>();
        for (String ref : csvValues(dimensionsCsv)) {
            String columnId = resolveColumnId(workspace, ref);
            if (columnId == null) {
                return commandFailure("Column not found (dimensions): " + ref);
            }
            dimensionColumnIds.add(columnId);
        }
        // 方言类型直接读图谱自带的 dataSource.dbType（导入时记下的），不用再连一次库或
        // 依赖本机是否配了这个别名的连接信息——SQL 展开本来就该是纯离线操作。
        String dbType = workspace.getDataSource() != null ? workspace.getDataSource().getDbType() : null;
        DatabaseStrategy dialect = DatabaseStrategies.resolve(dbType);
        String sql;
        try {
            sql = MetricSqlExpander.expand(workspace, metric, dialect,
                    new MetricSqlRequest(requestedGrain, timeFrom, timeTo, dimensionColumnIds));
        } catch (MetricExpansionException e) {
            return commandFailure(e.getMessage());
        }
        if (jsonOutput) {
            CliJson.printSuccess(Map.of("metric", metric.getName(), "sql", sql));
            return 0;
        }
        System.out.println(sql);
        return 0;
    }

    /** column:alias:schema.table.column -> schema.table.column；非列 id 原样返回。 */
    private String columnRefForId(String columnId) {
        if (columnId == null || !columnId.startsWith("column:")) {
            return columnId;
        }
        String[] parts = columnId.split(":", 3);
        return parts.length < 3 ? columnId : parts[2];
    }

    /**
     * 检索评估：对每个 case 跑一次 search（索引可用走索引，与 search 命令同一选择逻辑），
     * 输出 Top1/Top5/MRR 与 badcase。全部命中退出 0，存在完全未命中的 case 退出 1。
     */
    private int searchEval(GraphWorkspace workspace) throws Exception {
        if (workspace == null) {
            return missingWorkspace();
        }
        if (casesPath == null) {
            return usageError("Usage: sql-cli " + alias + " schema search-eval --cases <file.yaml> [--json]");
        }
        Path file = Path.of(casesPath);
        if (!Files.isRegularFile(file)) {
            return usageError("评估集文件不存在: " + casesPath);
        }
        List<SearchEval.EvalCase> cases = parseEvalCases(file);
        if (cases.isEmpty()) {
            return usageError("评估集文件没有可用的 cases: " + casesPath);
        }

        java.util.function.Function<String, List<String>> searchIds;
        String engine = "workspace";
        WorkspaceIndexSnapshot snapshot = null;
        if (indexStore.getIndexStatus(alias, workspace.getManifest().getRevision()) == IndexStatus.ready) {
            snapshot = indexStore.load(alias);
        }
        if (snapshot != null) {
            WorkspaceIndexedSearchEngine indexed = new WorkspaceIndexedSearchEngine(snapshot);
            searchIds = query -> indexed.search(query).stream()
                    .map(WorkspaceIndexedSearchEngine.SearchHit::getId).toList();
            engine = "index";
        } else {
            WorkspaceSearchEngine realtime = new WorkspaceSearchEngine(workspace);
            searchIds = query -> realtime.search(query).stream()
                    .map(WorkspaceSearchEngine.SearchResult::getId).toList();
        }

        SearchEval.Report report = SearchEval.evaluate(cases, searchIds);
        if (jsonOutput) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("cases", cases.size());
            payload.put("engine", engine);
            payload.put("top1Rate", report.top1Rate());
            payload.put("top5Rate", report.top5Rate());
            payload.put("mrr", report.mrr());
            payload.put("results", report.results());
            payload.put("badcases", report.badcases());
            CliJson.printSuccess(payload);
        } else {
            long top1 = report.results().stream().filter(SearchEval.CaseResult::top1).count();
            long top5 = report.results().stream().filter(SearchEval.CaseResult::top5).count();
            System.out.printf(Locale.ROOT, "检索评估: %d cases (engine=%s)%n", cases.size(), engine);
            System.out.printf(Locale.ROOT, "  Top1: %.1f%% (%d/%d)%n",
                    report.top1Rate() * 100, top1, cases.size());
            System.out.printf(Locale.ROOT, "  Top5: %.1f%% (%d/%d)%n",
                    report.top5Rate() * 100, top5, cases.size());
            System.out.printf(Locale.ROOT, "  MRR:  %.3f%n", report.mrr());
            if (!report.badcases().isEmpty()) {
                System.out.println("未命中 case (badcase):");
                for (SearchEval.CaseResult badcase : report.badcases()) {
                    System.out.printf("  \"%s\" -> %s%n", badcase.query(), String.join(", ", badcase.expect()));
                }
            }
        }
        return report.allHit() ? 0 : 1;
    }

    /** 评估集 YAML: cases: [{query: "...", expect: [id, ...]}]。 */
    private List<SearchEval.EvalCase> parseEvalCases(Path file) throws Exception {
        ObjectMapper yaml = new ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        com.fasterxml.jackson.databind.JsonNode root = yaml.readTree(file.toFile());
        com.fasterxml.jackson.databind.JsonNode casesNode = root.path("cases");
        if (!casesNode.isArray()) {
            throw new IllegalArgumentException("评估集文件需要顶层 cases 数组: " + file);
        }
        List<SearchEval.EvalCase> cases = new ArrayList<>();
        for (com.fasterxml.jackson.databind.JsonNode caseNode : casesNode) {
            String query = caseNode.path("query").asText(null);
            List<String> expect = new ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode idNode : caseNode.path("expect")) {
                if (!idNode.asText().isBlank()) {
                    expect.add(idNode.asText());
                }
            }
            if (query == null || query.isBlank() || expect.isEmpty()) {
                throw new IllegalArgumentException("每个 case 需要 query 和非空 expect 列表: " + caseNode);
            }
            cases.add(new SearchEval.EvalCase(query, expect));
        }
        return cases;
    }

    /**
     * 任务级评估：给 agent 的作答判分并归因。**不跑 agent**——起 agent 是外部 harness 的事，
     * 这里只吃两份 YAML（评估集 + 作答），所以它没有 LLM 依赖，能进 CI。
     *
     * <p>报告按归因分三段（图谱侧 / skill 侧 / 无法归因）而不是按 case 顺序平铺：
     * 「图谱不够」和「agent 不行」的修法完全相反，混在一起列出来的通过率
     * 是一个看了也不知道该做什么的数字。
     */
    private int taskEval() throws Exception {
        if (casesPath == null || answersPath == null) {
            return usageError("Usage: sql-cli " + alias
                    + " schema task-eval --cases <tasks.yaml> --answers <answers.yaml> [--json]");
        }
        Path cases = Path.of(casesPath);
        Path answers = Path.of(answersPath);
        if (!Files.isRegularFile(cases)) {
            return usageError("评估集文件不存在: " + casesPath);
        }
        if (!Files.isRegularFile(answers)) {
            return usageError("作答文件不存在: " + answersPath);
        }
        List<TaskEval.TaskCase> taskCases = TaskEval.parseCases(cases);
        Map<String, Map<String, TaskEval.Answer>> taskAnswers = TaskEval.parseAnswers(answers);
        TaskEval.Report report = TaskEval.evaluate(taskCases, taskAnswers);

        // 作答里写了评估集没有的 id：多半是打错了 id，不说出来会表现成「全部未作答」。
        Set<String> knownIds = taskCases.stream().map(TaskEval.TaskCase::id).collect(Collectors.toSet());
        List<String> unknownIds = taskAnswers.keySet().stream().filter(id -> !knownIds.contains(id)).toList();

        if (jsonOutput) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("cases", taskCases.size());
            payload.put("passRate", report.passRate());
            payload.put("passed", report.byVerdict(TaskEval.Verdict.passed).size());
            payload.put("graphFailures", report.byVerdict(TaskEval.Verdict.graph).size());
            payload.put("skillFailures", report.byVerdict(TaskEval.Verdict.skill).size());
            payload.put("unattributable", report.byVerdict(TaskEval.Verdict.unattributable).size());
            payload.put("results", report.results());
            payload.put("unknownAnswerIds", unknownIds);
            CliJson.printSuccess(payload);
            return report.allPassed() ? 0 : 1;
        }

        System.out.printf(Locale.ROOT, "任务评估: %d cases%n", taskCases.size());
        printTaskEvalSection(report, TaskEval.Verdict.graph,
                "图谱信息不足（对照组失败、作弊组通过 → 补描述 / 术语 / 检索）:");
        printTaskEvalSection(report, TaskEval.Verdict.skill,
                "skill 问题（两组都失败 → 改 references/ 里对应那一节）:");
        printTaskEvalSection(report, TaskEval.Verdict.unattributable, "无法归因:");
        List<TaskEval.CaseResult> passed = report.byVerdict(TaskEval.Verdict.passed);
        if (!passed.isEmpty()) {
            System.out.println("通过: " + passed.stream().map(TaskEval.CaseResult::id)
                    .collect(Collectors.joining(", ")));
        }
        if (!unknownIds.isEmpty()) {
            System.out.println("作答里有评估集没有的 id（是不是打错了）: " + String.join(", ", unknownIds));
        }
        System.out.printf(Locale.ROOT, "  通过率: %.1f%% (%d/%d)%n",
                report.passRate() * 100, passed.size(), taskCases.size());
        System.out.printf(Locale.ROOT, "  图谱侧失败: %d%n", report.byVerdict(TaskEval.Verdict.graph).size());
        System.out.printf(Locale.ROOT, "  skill 侧失败: %d%n", report.byVerdict(TaskEval.Verdict.skill).size());
        return report.allPassed() ? 0 : 1;
    }

    private void printTaskEvalSection(TaskEval.Report report, TaskEval.Verdict verdict, String title) {
        List<TaskEval.CaseResult> section = report.byVerdict(verdict);
        if (section.isEmpty()) {
            return;
        }
        System.out.println(title);
        for (TaskEval.CaseResult result : section) {
            // skillRef 只在 skill 侧失败时打：它是「该改哪份技能文档」，图谱侧失败改的是图谱。
            boolean showSkillRef = verdict == TaskEval.Verdict.skill && result.skillRef() != null;
            System.out.printf("  - %s  %s%s%n", result.id(), result.task() == null ? "" : result.task(),
                    showSkillRef ? "  → 改 " + result.skillRef() : "");
            if (result.note() != null) {
                System.out.println("      " + result.note());
            }
            printTaskEvalGroup("对照组", result.control());
            printTaskEvalGroup("作弊组", result.cheat());
        }
    }

    private void printTaskEvalGroup(String label, TaskEval.GroupResult group) {
        if (group == null || group.pass()) {
            return;
        }
        if (!group.missingTables().isEmpty()) {
            System.out.printf("      %s 缺表: %s%n", label, String.join(", ", group.missingTables()));
        }
        if (!group.missingJoins().isEmpty()) {
            System.out.printf("      %s 缺 JOIN: %s%n", label, String.join("; ", group.missingJoins()));
        }
        for (String pitfall : group.pitfalls()) {
            System.out.printf("      %s 踩中: %s%n", label, pitfall);
        }
    }

    /**
     * 值域三源交叉：库里的实际分布 × 字段注释 × 代码枚举 → **提议**一份值域，等人批准。
     *
     * <h2>为什么产出的是审批而不是直接写</h2>
     * 这份值域是**机器从一次采样里推断**出来的。采样看不到的值、测试库里没造的数据，
     * 它都不知道。直接写进图谱意味着一次剖析就能把几十个字段的值域按采样结果改掉，
     * 而值域错了的后果是 Agent 按错的条件写 WHERE——SQL 照跑、数字照出、不报错。
     *
     * <p>但**它不自己决定要不要审批**。原来这里无条件排队、{@code schema edit} 看开关，
     * 一条直通一条排队，中间的时间差把审批基线改掉了（#154）。风险高低表达成
     * 「这是机器推断的」写进理由，要不要人裁决由别名的 {@code graphApproval} 统一定。
     * 要保护就把那个别名配成 {@code manual}。
     *
     * <h2>为什么只为「有发现」的列建审批</h2>
     * 一张表几十个字段，三源本来就一致的那些没有任何需要人决定的东西，
     * 给它们各建一条审批只是往队列里塞噪音。判据见 {@link ValueDomainVerdict#hasFinding()}。
     *
     * <p>第三源（代码枚举）CLI 自己读不到，由 Agent 读完代码用 {@code --code-enum} 带进来；
     * 不带就是两源交叉，仍然能抓出「库里有、注释没写」这类硬发现。
     */
    private int valueDomain(GraphWorkspace workspace) {
        if (workspace == null) {
            return missingWorkspace();
        }
        if (editTable == null || editTable.isBlank()) {
            return commandFailure("需要 --table <schema.table> 指定要分析的表");
        }
        TableWorkspaceNode table = workspace.getTableByQualifiedName(editTable);
        if (table == null) {
            return commandFailure("Table not found: " + editTable);
        }

        List<String> codeEnum = List.of();
        if (codeEnumCsv != null && !codeEnumCsv.isBlank()) {
            if (editColumn == null || editColumn.isBlank()) {
                return commandFailure("--code-enum 是某一列的代码枚举，必须同时给 --column");
            }
            if (codeEnumBasis == null || codeEnumBasis.isBlank()) {
                // 代码枚举是 Agent 断言的、机器无法验证的语义。不交代来源，
                // 审批人看到一串 `0=待付款,1=已付款` 无从判断这是读出来的还是猜的，
                // 只能盲批或盲拒——两种都不是审批。
                return commandFailure("--code-enum 必须同时给 --basis 说明取值依据"
                        + "（一句话：从哪个类 / 哪个文件 / 哪张字典表读到的），否则审批人无法判断");
            }
            try {
                codeEnum = ColumnValueHints.normalizeEnumValues(csvValues(codeEnumCsv));
            } catch (IllegalArgumentException e) {
                return commandFailure("--code-enum 解析失败: " + e.getMessage());
            }
        }

        // --column 给了就只看那一列；否则整表扫，高基数列由 profiler 自己跳过
        String onlyColumn = columnLeafName(editColumn);
        List<ColumnSpec> specs = new ArrayList<>();
        for (ColumnWorkspaceNode column : table.getColumns()) {
            if (onlyColumn != null && !onlyColumn.equalsIgnoreCase(column.getName())) continue;
            // 显式点名的列照采；否则先滤掉结构上就不可能是枚举的，省掉一半聚合查询
            if (onlyColumn == null && cannotBeEnum(column)) continue;
            specs.add(new ColumnSpec(column.getName(), column.getSemanticType()));
        }
        if (specs.isEmpty()) {
            return commandFailure(onlyColumn == null ? "这张表没有字段" : "Column not found: " + editColumn);
        }

        TableProfile profile;
        try {
            DatabaseConfig config = aliasResolver.resolve(alias);
            System.err.println("正在采样 " + table.getSchema() + "." + table.getName()
                    + " 的 " + specs.size() + " 个字段（只读聚合查询）…");
            try (Connection connection = connectionManager.getConnection(config)) {
                profile = TableProfiler.profile(connection, config.getType(),
                        table.getSchema(), table.getName(), specs, ProfileOptions.defaults());
            }
        } catch (Exception e) {
            return commandFailure("采样失败: " + e.getMessage());
        }

        // 剖析已经跑完了，实测空值率是白拿的——顺手把外键可选性从「按声明推断」升级成「按实测」。
        // 跟值域一样提条目、走同一条管线；不在这里做的话 schema path 永远只能读 nullable 声明。
        List<String> optionalityApplied = new ArrayList<>();
        List<Long> optionalityApprovals = proposeOptionality(workspace, profile, optionalityApplied);

        List<ValueDomainVerdict> verdicts = new ArrayList<>();
        for (ColumnProfile columnProfile : profile.columns()) {
            ColumnWorkspaceNode column = table.findColumn(columnProfile.columnName());
            if (column == null) continue;
            List<String> declared = column.getValueHints() == null
                    ? List.of() : column.getValueHints().getEnumValues();
            // 点名了某一列就是调用方已经断言「它是枚举」，启发式不再否决
            verdicts.add(ValueDomainCrosscheck.cross(columnProfile, column.getComment(),
                    codeEnum, declared, onlyColumn != null));
        }

        List<ValueDomainVerdict> findings = verdicts.stream()
                .filter(ValueDomainVerdict::hasFinding).toList();
        // 「有发现但提不出审批」（整列全 NULL 这类）的落点：graph_finding 的 value.unused。
        // 两种输出都要落——只在文本模式记，等于 agent 用 --json 跑就把这些发现丢了。
        String recorded = recordUnusedColumns(workspace, table, findings);
        if (jsonOutput) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("table", table.getId());
            payload.put("analyzed", verdicts.size());
            payload.put("verdicts", verdicts);
            List<String> autoApplied = new ArrayList<>(optionalityApplied);
            // 值域和外键可选性是同一条命令提交的两批条目，合成一份报给调用方——
            // 拆两个键等于让 agent 学一套新结构，而它要做的事一样：去评审页裁决。
            List<Long> approvals = new ArrayList<>(optionalityApprovals);
            approvals.addAll(proposeValueDomains(workspace, table, findings, autoApplied));
            payload.put("approvals", approvals);
            payload.put("applied", autoApplied);
            CliJson.printSuccess(payload);
            return 0;
        }

        System.out.println("分析了 " + verdicts.size() + " 个字段，" + findings.size() + " 个有发现：");
        for (ValueDomainVerdict verdict : verdicts) {
            // 点名单列时把跳过原因也打出来：调用方问的就是这一列，沉默等于没回答
            if (verdict.hasFinding() || (onlyColumn != null && verdict.skipped())) {
                System.out.println("  " + verdict.summary());
            }
        }
        printOptionalityOutcome(optionalityApprovals, optionalityApplied);
        if (findings.isEmpty()) {
            // 「都跳过了」和「三源一致」是两回事，说反了会让人以为核对过了
            long skipped = verdicts.stream().filter(ValueDomainVerdict::skipped).count();
            System.out.println(skipped == verdicts.size()
                    ? "全部字段都不是枚举列（见上），没有可交叉的内容。"
                    : "三源一致，图谱无需改动。");
            return 0;
        }
        List<String> autoApplied = new ArrayList<>();
        List<Long> approvals = proposeValueDomains(workspace, table, findings, autoApplied);
        if (!approvals.isEmpty()) {
            System.out.println("已提交 " + approvals.size() + " 条待审批：" + approvals);
        }
        if (!autoApplied.isEmpty()) {
            System.out.println("已直接写入 " + autoApplied.size() + " 个字段的值域："
                    + String.join("、", autoApplied)
                    + "（别名的 graphApproval 是 auto；要人工裁决改成 manual）");
        }
        if (recorded != null) {
            System.out.println(recorded);
        }
        if (!approvals.isEmpty()) {
            System.out.println("这些条目图谱尚未改动，到 Web UI 评审页 · 待审批裁决后才写入值域。");
        }
        return 0;
    }

    /**
     * `schema data-quality`：连库跑只读聚合，产出数据层的 finding。
     *
     * <h2>为什么不并进 {@code schema eval}</h2>
     * `eval` 不连库、纯计算；这条非连库不可。合成一个命令的结果是「跑一次评估」既可能是
     * 毫秒级的纯计算、也可能是往生产库上打几百条聚合查询——同一个命令名两种代价，
     * 没人敢在生产别名上跑它。三层三套工具（CLAUDE.md），不合并。
     *
     * <h2>授权在连库之前，而且授权的是查询本身</h2>
     * skill 铁律 2 要的是「明确授权」，而一个命令名不足以让人判断代价。所以先把
     * <b>将要执行的每一条查询</b>打出来，再要 {@code --yes}；没有交互终端又没给 --yes
     * 就退出，不偷偷跑。
     */
    private int dataQuality(GraphWorkspace workspace) throws Exception {
        if (workspace == null) {
            return missingWorkspace();
        }
        if (editTable == null || editTable.isBlank()) {
            return commandFailure("需要 --table <schema.table> 指定要分析的表");
        }
        TableWorkspaceNode table = workspace.getTableByQualifiedName(editTable);
        if (table == null) {
            return commandFailure("Table not found: " + editTable);
        }
        if (table.getColumns().isEmpty()) {
            return commandFailure("图谱里这张表没有字段，先跑一次 sql-cli " + alias
                    + " schema import --from-db --table " + editTable);
        }
        int months = deadMonths == null ? DataQualityProbes.DEFAULT_DEAD_MONTHS : deadMonths;
        if (months <= 0) {
            return commandFailure("--months 必须是正整数");
        }

        // dbType 取自图谱自己的 datasource 节点：授权发生在解析别名和建连接之前，
        // 这时还不该碰凭据
        String dbType = workspace.getDataSource() == null ? null : workspace.getDataSource().getDbType();
        int authorized = authorizeDataQuality(dbType, workspace, table);
        if (authorized != 0) {
            return authorized;
        }

        DatabaseConfig config = aliasResolver.resolve(alias);
        try (Connection connection = connectionManager.getConnection(config)) {
            return reportDataQuality(connection, config.getType(), workspace, table, months);
        }
    }

    /** 打印查询清单并取得放行。0 = 可以跑，非 0 = 直接作为命令退出码。 */
    private int authorizeDataQuality(String dbType, GraphWorkspace workspace, TableWorkspaceNode table) {
        List<String> queries = DataQualityProbes.plannedQueries(dbType, workspace, table,
                ProfileOptions.defaults());
        if (assumeYes) {
            return 0;
        }
        // 清单走 stderr：--json 时 stdout 必须只有那一份 JSON
        System.err.println("即将在 " + alias + " 上执行以下只读查询：");
        queries.forEach(query -> System.err.println("  " + query));
        if (System.console() == null) {
            // 非交互（agent、CI、管道）下没有「问一句」这回事，装作问过更糟
            System.err.println("非交互环境：确认以上查询后加 --yes 重跑。");
            return 2;
        }
        String answer = com.sqlcli.secret.ConsolePrompts.readLine("执行这些查询？(y/N): ");
        if (!"y".equalsIgnoreCase(answer.trim()) && !"yes".equalsIgnoreCase(answer.trim())) {
            System.err.println("已取消，一条查询都没有执行。");
            return 2;
        }
        return 0;
    }

    /**
     * 连库之后的全部流程：跑探针 → 落库 → 打印。
     *
     * <p>package-private 是给测试用的：连接由测试直接喂一条临时 SQLite 进来，
     * 不必为跑一次端到端去配一个别名。
     */
    int reportDataQuality(Connection connection, String dbType, GraphWorkspace workspace,
            TableWorkspaceNode table, int months) throws Exception {
        long startedAt = System.currentTimeMillis();
        List<GraphFinding> findings = DataQualityProbes.run(connection, dbType, workspace, table, months,
                ProfileOptions.defaults());
        // metrics 为 null：数据层没有覆盖率这回事，硬凑一个百分比就是虚荣指标
        GraphEvaluation evaluation = new GraphEvaluation(Map.of(), findings);
        String evaluationId = "eval-" + UUID.randomUUID();
        saveEvaluation(evaluationId, "data-quality", workspace.getManifest().getRevision(), startedAt,
                evaluation.status(), findings, null);

        if (jsonOutput) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("evaluationId", evaluationId);
            payload.put("alias", alias);
            payload.put("table", table.getId());
            payload.put("status", evaluation.status());
            payload.put("errorCount", evaluation.errorCount());
            payload.put("warningCount", evaluation.warningCount());
            payload.put("findings", findings);
            CliJson.printSuccess(payload);
            return evaluation.failed() ? 1 : 0;
        }

        System.out.println("数据质量 " + evaluationId + "（" + table.getQualifiedName() + "）");
        if (findings.isEmpty()) {
            System.out.println("四条探针都没有发现。");
        } else {
            System.out.println(evaluation.errorCount() + " 条硬错误、"
                    + evaluation.warningCount() + " 条值得看：");
            printFindings(findings);
        }
        return evaluation.failed() ? 1 : 0;
    }

    /**
     * 「有发现、但没有值域可写」的那几条（整列全 NULL、非空行只有一个取值）落进
     * {@code graph_finding} 的 {@code value.unused}。
     *
     * <p>这类发现提不出审批——没有值域可写就没有可提议的变更——但它对 agent 恰恰最有用：
     * 它说的是「这个字段虽然存在，但不要拿它做判断」。以前只打印一行就没了，
     * 现在跟 `schema eval` 的 finding 排在同一个工作队列里。
     *
     * <p>{@code source=value-domain} 的评估行不带指标，趋势图不该拿它连线（见
     * {@link RunStateStore.GraphEvaluationRow}）。
     *
     * @return 一句给人看的结论；没有这类发现时返回 null
     */
    private String recordUnusedColumns(GraphWorkspace workspace, TableWorkspaceNode table,
            List<ValueDomainVerdict> verdicts) {
        List<GraphFinding> findings = new ArrayList<>();
        for (ValueDomainVerdict verdict : verdicts) {
            if (!verdict.reportOnly()) continue;
            String ref = table.getSchema() + "." + table.getName() + "." + verdict.columnName();
            findings.add(GraphFinding.warning("value.unused",
                    GraphIds.columnId(alias, table.getSchema(), table.getName(), verdict.columnName()),
                    verdict.summary() + "——agent 不该拿这个字段做判断",
                    "sql-cli " + alias + " schema edit --column " + ref
                            + " --description '未启用：<确认是废弃字段还是这个环境没有数据>'"));
        }
        if (findings.isEmpty()) return null;
        String evaluationId = "eval-" + UUID.randomUUID();
        try {
            saveEvaluation(evaluationId, "value-domain", workspace.getManifest().getRevision(),
                    System.currentTimeMillis(), "pass", findings, null);
        } catch (java.io.IOException e) {
            return "另有 " + findings.size() + " 条只能报告的发现，落库失败：" + e.getMessage();
        }
        return "另有 " + findings.size() + " 条只能报告、提不出审批（没有值域可写，如整列全 NULL），"
                + "已记进评估 " + evaluationId + "（探针 value.unused）。";
    }

    /**
     * 每个有发现的字段提一条条目。别名是 manual 就等人批，是 auto 就当场落地。
     *
     * @param applied 别名是 auto 时当场落地的列名，调用方据此报告「已写入」而不是「待审批」
     * @return 待审批的审批号
     */
    private List<Long> proposeValueDomains(GraphWorkspace workspace, TableWorkspaceNode table,
            List<ValueDomainVerdict> findings, List<String> applied) {
        List<Long> approvals = new ArrayList<>();
        for (ValueDomainVerdict verdict : findings) {
            // 只是「字段没在用」而没有值域可写时，没有可提议的变更
            if (verdict.proposedEnumValues().isEmpty()) continue;
            String columnId = GraphIds.columnId(alias, table.getSchema(), table.getName(),
                    verdict.columnName());
            long revision = workspace.getManifest().getRevision();
            String reason = "值域三源交叉：" + verdict.summary()
                    + (codeEnumBasis == null || codeEnumBasis.isBlank() ? ""
                       : "；语义依据：" + codeEnumBasis.trim());
            WorkspaceMutationService.MutationResult result = mutationService.mutate(alias, revision,
                    GraphActor.agent, reason, columnId, current -> {
                        TableWorkspaceNode target = current.getTables().get(table.getId());
                        if (target == null) throw new IllegalArgumentException("table not found");
                        ColumnWorkspaceNode column = target.findColumn(verdict.columnName());
                        if (column == null) throw new IllegalArgumentException("column not found");
                        if (column.getValueHints() == null) column.setValueHints(new ColumnValueHints());
                        column.getValueHints().setEnumValues(new ArrayList<>(verdict.proposedEnumValues()));
                        return new WorkspaceMutationService.MutationOutcome(columnId,
                                ChangeOperation.upsert_column);
                    });
            if (result.isSuccess() && result.getPendingApprovalId() != null) {
                approvals.add(result.getPendingApprovalId());
            } else if (result.isSuccess()) {
                // 别名是 auto：这条已经落地了，没有审批号可报
                applied.add(verdict.columnName());
            } else {
                System.err.println("  " + verdict.columnName() + " 提交失败: "
                        + String.join("; ", result.getErrors()));
            }
            syncRevision(workspace, result);
        }
        return approvals;
    }

    /**
     * 外键可选性按实测空值率升级——{@code value-domain} 顺手做的第二件事。
     *
     * <p>剖析已经在这条命令里跑完了，空值率是白拿的。不接的话 {@code schema path} 永远
     * 只能读 nullable 声明：声明可空但实测零空值的边一直给 LEFT JOIN，多查出一批本该被
     * 过滤掉的行；而这份数据早就躺在剖析结果里。
     *
     * <p>走的是和值域提议同一条 {@code mutate} 管线，要不要审批由别名策略裁决——
     * 命令自己不许决定（CLAUDE.md「一个别名上只能有一种行为」）。
     *
     * @param applied 别名是 auto 时当场落地的列名，调用方据此报「已写入」而不是「待审批」
     */
    List<Long> proposeOptionality(GraphWorkspace workspace, TableProfile profile, List<String> applied) {
        List<Long> approvals = new ArrayList<>();
        for (ProfileGraphWriter.OptionalityUpgrade upgrade
                : ProfileGraphWriter.planOptionalityUpgrades(workspace, alias, profile)) {
            WorkspaceMutationService.MutationResult result = mutationService.mutate(alias,
                    workspace.getManifest().getRevision(), GraphActor.extractor, upgrade.reason(),
                    upgrade.relationId(), current -> {
                        ProfileGraphWriter.applyOptionality(current, upgrade);
                        return new WorkspaceMutationService.MutationOutcome(upgrade.relationId(),
                                ChangeOperation.update);
                    });
            if (result.isSuccess() && result.getPendingApprovalId() != null) {
                approvals.add(result.getPendingApprovalId());
            } else if (result.isSuccess()) {
                applied.add(upgrade.column() + " 可选性");
            } else {
                System.err.println("  " + upgrade.relationId() + " 提交失败: "
                        + String.join("; ", result.getErrors()));
            }
            syncRevision(workspace, result);
        }
        return approvals;
    }

    private void printOptionalityOutcome(List<Long> approvals, List<String> applied) {
        if (!approvals.isEmpty()) {
            System.out.println("外键可选性已提交 " + approvals.size() + " 条待审批：" + approvals);
        }
        if (!applied.isEmpty()) {
            System.out.println("已按实测更新外键可选性：" + String.join("、", applied)
                    + "（schema path 的 INNER/LEFT 结论随之改按实测）");
        }
    }

    /**
     * 把命令持有的这份工作区的 revision 对齐到刚落盘的值。
     *
     * <p>{@code mutate} 每次都自己 load 一份最新的，落盘后 revision 加一；命令手里这份
     * 停在旧值上。同一条命令连提两条以上时，第二条就会拿旧 revision 撞上乐观锁，
     * 报 {@code revision mismatch} 而不是写进去——auto 别名（默认）上必然发生。
     */
    private static void syncRevision(GraphWorkspace workspace, WorkspaceMutationService.MutationResult result) {
        if (result.isSuccess()) {
            workspace.getManifest().setRevision(result.getNewRevision());
        }
    }

    /**
     * 结构上就不可能是枚举的列，采样前直接滤掉。
     *
     * <p>主键 / 唯一列的取值按定义不重复；时间、日期、二进制、大文本也从来不是枚举。
     * 这一层滤的是**元数据能判定**的，剩下的靠 {@code ValueDomainCrosscheck} 按实际分布判。
     * 两层都要有：元数据可能不准（没建唯一约束的业务唯一列），分布也可能骗人（小表）。
     */
    private static boolean cannotBeEnum(ColumnWorkspaceNode column) {
        if (column.isPrimaryKey() || column.isUnique()) return true;
        String type = column.getDataType() == null ? null : column.getDataType().getNormalized();
        if (type == null) return false;
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "date", "time", "datetime", "timestamp", "year",
                 "blob", "clob", "text", "longtext", "mediumtext", "json", "binary", "varbinary" -> true;
            default -> false;
        };
    }

    /** `schema.table.col` / `col` 都取最后一段。 */
    private static String columnLeafName(String ref) {
        if (ref == null || ref.isBlank()) return null;
        int lastDot = ref.lastIndexOf('.');
        return lastDot < 0 ? ref : ref.substring(lastDot + 1);
    }

    private int printMutationResult(WorkspaceMutationService.MutationResult result, String successMessage) {
        if (!result.isSuccess()) {
            result.getErrors().forEach(System.err::println);
            return 1;
        }
        // pendingApprovalId 有两种来路，报给调用方的话不一样，别混：
        //   changeId 为空 = 开了图谱审批，变更还没进图谱，批准了才写；
        //   changeId 非空 = 变更已经写进去了，但落成了候选边，等人发布才算数。
        Long approvalId = result.getPendingApprovalId();
        Long batchId = com.sqlcli.session.SessionContext.batchId();
        if (result.getChangeId() == null) {
            if (approvalId == null) {
                System.out.println("没有修改");
            } else if (batchId != null) {
                // 批次里的条目还没提交：说成「待审批」会让人去队列里找一个不存在的东西
                System.out.println("已加入批次 #" + batchId + "（条目 #" + approvalId + "）。"
                        + "图谱未改动——sql-cli " + alias + " batch submit " + batchId + " 提交后才进待审批。");
            } else {
                System.out.println("待审批（审批 #" + approvalId + "）：变更已提交，批准后才写入图谱。"
                        + "到 Web UI 评审页裁决。");
            }
            return 0;
        }
        System.out.println(successMessage);
        if (approvalId != null) {
            System.out.println("待发布（审批 #" + approvalId + "）：已作为候选写入，"
                    + "发布后才进入正式图谱。到 Web UI 评审页裁决。");
        }
        return 0;
    }

    private int handleIndex(GraphWorkspace workspace) throws Exception {
        if (workspace == null) {
            return missingWorkspace();
        }
        String mode = subAction == null ? "status" : subAction.toLowerCase(Locale.ROOT);
        if ("rebuild".equals(mode)) {
            WorkspaceIndexSnapshot[] built = new WorkspaceIndexSnapshot[1];
            WorkspaceMutationService.MutationResult mutation = mutationService.mutate(alias,
                    workspace.getManifest().getRevision(), GraphActor.system, "schema index rebuild", current -> {
                        WorkspaceIndexSnapshot snapshot = workspaceIndexer.rebuild(
                                current, current.getManifest().getRevision() + 1);
                        indexStore.save(alias, snapshot);
                        current.getManifest().setLastIndexedAt(snapshot.getBuiltAt()
                                .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime());
                        built[0] = snapshot;
                        return new WorkspaceMutationService.MutationOutcome(
                                current.getManifest().getId(), ChangeOperation.update);
                    });
            if (!mutation.isSuccess()) {
                mutation.getErrors().forEach(System.err::println);
                return 1;
            }
            WorkspaceIndexSnapshot snapshot = built[0];
            WorkspaceIndexManifest manifest = indexStore.loadManifest(alias);
            System.out.printf("Index rebuilt: %d shards, %d tables, %d columns, %d terms%n",
                    manifest.getShards().size(), snapshot.tableCount(), snapshot.columnCount(),
                    snapshot.termCount());
            return 0;
        }
        if ("status".equals(mode)) {
            if (!indexStore.exists(alias)) {
                if (jsonOutput) {
                    CliJson.printSuccess(Map.of("status", "not_built"));
                } else {
                    System.out.println("Index not built");
                }
                return 0;
            }
            WorkspaceIndexManifest manifest = indexStore.loadManifest(alias);
            IndexStatus status = indexStore.getIndexStatus(alias, workspace.getManifest().getRevision());
            if (manifest == null) {
                System.out.println("索引格式已过时，请运行: sql-cli " + alias + " schema index rebuild");
                return 1;
            }
            if (jsonOutput) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("status", status);
                payload.put("manifest", manifest);
                CliJson.printSuccess(payload);
            } else {
                System.out.printf("Status: %s%n", status);
                System.out.printf("Index built at: %s%n", manifest.getBuiltAt());
                System.out.printf("Version: %d%n", manifest.getVersion());
                System.out.printf("Shards: %d (max %d bytes each)%n",
                        manifest.getShards().size(), manifest.getMaxShardBytes());
                System.out.printf("Documents: %d tables, %d columns, %d terms%n",
                        manifest.getTableCount(), manifest.getColumnCount(),
                        manifest.getTermCount());
            }
            return 0;
        }
        return usageError(
                "Usage: sql-cli " + alias + " schema index [rebuild|status]");
    }

    private int generateDiagram(GraphWorkspace workspace) throws Exception {
        if (workspace == null) {
            return missingWorkspace();
        }
        Path output = outputPath == null || outputPath.isBlank()
                ? GraphWorkspaceStore.getWorkspacePath(alias).resolve("developer-graph")
                : Path.of(outputPath);
        WorkspaceDiagramGenerator.DiagramResult result = diagramGenerator.generate(workspace, output);
        if (jsonOutput) {
            CliJson.printSuccess(result.manifest());
        } else {
            System.out.printf("Developer graph generated: %s%n", result.outputDir());
            System.out.printf("Relations: %d aggregated, tables: %d related / %d isolated%n",
                    result.manifest().tableRelations,
                    result.manifest().relatedTables,
                    result.manifest().isolatedTables);
        }
        return 0;
    }


    private int handlePolicy(GraphWorkspace workspace) throws Exception {
        if (workspace == null) {
            return missingWorkspace();
        }
        List<String> args = commandArgs.stream().dropWhile(value -> !"policy".equals(value)).skip(1).toList();
        if (args.isEmpty()) {
            printActionHelp("policy");
            return 2;
        }
        String group = args.get(0);
        if ("check".equals(group)) {
            String rules = option(args, "--rules");
            // --all：这一次连 scope=change 的规则（默认是命名类）也在存量图谱上跑一遍
            Map<String, Object> input = args.contains("--all") ? Map.of("scope", "all") : Map.of();
            if (rules == null) {
                List<PolicyCheckResult> results;
                try {
                    results = policyService.checkBound(alias, GraphActor.human, input);
                } catch (IllegalArgumentException e) {
                    return usageError(e.getMessage());
                }
                if (jsonOutput) {
                    List<Map<String, Object>> output = results.stream().map(this::policyResultValue).toList();
                    CliJson.printSuccess(Map.of("results", output, "bound", output.size()));
                } else if (results.isEmpty()) {
                    System.out.println("未绑定任何规则集，检查通过。"
                            + "可用 --rules <file> 指定规则文件，或在 policy/bindings.yaml 中绑定。");
                } else {
                    results.forEach(this::printPolicyResult);
                }
                return results.stream().mapToInt(PolicyCheckResult::exitCode).max().orElse(0);
            }
            PolicyCheckResult result = policyService.check(alias, Path.of(rules), GraphActor.human, input);
            if (jsonOutput) {
                CliJson.printSuccess(policyResultValue(result));
            } else {
                printPolicyResult(result);
            }
            return result.exitCode();
        }
        if ("evaluation".equals(group)) {
            String operation = positional(args, 1);
            if ("list".equals(operation)) {
                List<RuleEvaluation> evaluations = policyService.listEvaluations(alias);
                printPolicyValue(evaluations);
                return 0;
            }
            if ("show".equals(operation)) {
                String id = positional(args, 2);
                if (id == null) throw new IllegalArgumentException("evaluation id is required");
                printPolicyValue(policyService.showEvaluation(alias, id));
                return 0;
            }
        }
        if ("violation".equals(group) && "list".equals(positional(args, 1))) {
            printPolicyValue(policyService.listViolations(alias, option(args, "--evaluation")));
            return 0;
        }
        if ("waiver".equals(group)) {
            String operation = positional(args, 1);
            if ("add".equals(operation)) {
                String rule = requiredOption(args, "--rule");
                String target = requiredOption(args, "--target");
                String reason = requiredOption(args, "--reason");
                String expires = requiredOption(args, "--expires-at");
                RuleWaiver waiver = policyService.addWaiver(alias, rule, target, reason,
                        java.time.LocalDateTime.parse(expires), GraphActor.human);
                printPolicyValue(waiver);
                return 0;
            }
            if ("list".equals(operation)) {
                printPolicyValue(policyService.listWaivers(alias, args.contains("--active")));
                return 0;
            }
            if ("revoke".equals(operation)) {
                String id = positional(args, 2);
                if (id == null) throw new IllegalArgumentException("waiver id is required");
                printPolicyValue(policyService.revokeWaiver(alias, id,
                        requiredOption(args, "--reason"), GraphActor.human));
                return 0;
            }
        }
        if ("rule".equals(group) && "add".equals(positional(args, 1))) {
            return proposePolicyRule(args);
        }
        System.err.println("Unknown policy command. Use 'schema policy --help'");
        return 2;
    }

    /**
     * {@code policy rule add}：agent 归纳出的约定 → 审批 → 写进规则集并启用。
     * 落地与审批判据见 {@link PolicyRuleProposals}；这里只把命令行翻译成一条 {@link PolicyRule}。
     */
    private int proposePolicyRule(List<String> args) throws Exception {
        String category = requiredOption(args, "--category");
        String reason = requiredOption(args, "--reason");
        PolicyRule rule = new PolicyRule();
        rule.setCategory(category);
        rule.setId(option(args, "--id") != null ? option(args, "--id") : category);
        rule.setSeverity(PolicySeverity.valueOf(
                option(args, "--severity") != null ? option(args, "--severity") : "warning"));
        rule.setEnforcement(PolicyEnforcement.valueOf(
                option(args, "--enforcement") != null ? option(args, "--enforcement") : "advisory"));
        rule.setRemediation(option(args, "--remediation"));
        if (option(args, "--table-regex") != null) rule.getWhen().put("tableNameRegex", option(args, "--table-regex"));
        rule.getWhen().put("tableTypeAny", List.of(
                option(args, "--table-type") != null ? option(args, "--table-type") : "base_table"));
        switch (category) {
            case "required_business_columns" -> {
                List<String> specs = options(args, "--column");
                if (specs.isEmpty()) return usageError("required_business_columns 至少要一个 --column");
                rule.setTitle(option(args, "--title") != null ? option(args, "--title") : "必备字段");
                if (rule.getRemediation() == null) rule.setRemediation("补齐必备字段");
                rule.getStatement().put("columns",
                        specs.stream().map(PolicyRuleProposals::parseColumnSpec).toList());
            }
            default -> {
                return usageError("类别 " + category + " 暂不支持从 CLI 提议，请在 Web UI 规则页配置");
            }
        }
        GraphActor actor = com.sqlcli.session.SessionContext.agentId() != null ? GraphActor.agent : GraphActor.human;
        boolean manual = ApprovalGate.isEnabled(alias, ApprovalGate.Kind.GRAPH);
        PolicyRuleProposals.Proposal proposal;
        try {
            proposal = new PolicyRuleProposals(workspaceStore).propose(alias, rule, actor, reason, manual);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return usageError(e.getMessage());
        }
        if (jsonOutput) {
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("approvalId", proposal.approvalId());
            output.put("applied", proposal.applied());
            output.put("fileName", proposal.fileName());
            output.put("rule", rule);
            CliJson.printSuccess(output);
        } else if (proposal.applied()) {
            System.out.println("规则 " + rule.getId() + " 已写入 " + proposal.fileName() + " 并启用（审批 #"
                    + proposal.approvalId() + " 留底）。之后的 CREATE / ALTER / DROP 执行前都会按它检查。");
        } else {
            System.out.println("待审批（审批 #" + proposal.approvalId() + "）：规则已提交，批准后才写入 "
                    + proposal.fileName() + " 并启用。到 Web UI 评审页裁决。");
        }
        return 0;
    }

    private Map<String, Object> policyResultValue(PolicyCheckResult result) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("evaluation", result.evaluation());
        output.put("violations", result.violations());
        return output;
    }

    private void printPolicyResult(PolicyCheckResult result) {
        System.out.printf("Evaluation: %s (%s)%n", result.evaluation().getId(), result.evaluation().getStatus());
        for (PolicyViolation violation : result.violations()) {
            System.out.printf("  [%s/%s] %s %s %s%s%n", violation.getSeverity(),
                    violation.getEnforcement(), violation.getRuleId(), violation.getTargetId(),
                    violation.getStatus(), violation.getWaiverId() == null ? "" : " waiver=" + violation.getWaiverId());
            System.out.printf("    %s%s%n", violation.getMessage(),
                    violation.getRemediation() == null ? "" : " | " + violation.getRemediation());
        }
        if (result.skippedRules() > 0) {
            System.out.printf("  跳过 %d 条只对新 DDL 生效的规则（design review / migration lint 里照跑；"
                    + "存量图谱上要跑加 --all，或在规则里写 scope: all）%n", result.skippedRules());
        }
        if (result.evaluation().getErrorMessage() != null) {
            System.err.println(result.evaluation().getErrorMessage());
        }
    }

    private int reviewDesign(GraphWorkspace workspace) throws Exception {
        if (workspace == null) return missingWorkspace();
        if (!"review".equals(positional(commandArgs, 1))) {
            return usageError("Usage: schema design review --ddl <file> --rules <ruleset.yaml>");
        }
        Path ddl = requiredFile("--ddl");
        Path rules = requiredFile("--rules");
        CandidateDdlProjector.ProjectionResult projection = ddlProjector.projectFile(workspace, ddl);
        Map<String, Object> input = reviewInput("design", "ddl", ddl, projection.changes());
        input.put("ddlSql", Files.readString(ddl, StandardCharsets.UTF_8));
        input.put("ddlRef", ddl.toString());
        input.put("similarObjects", similarObjects(workspace, projection.changes()));
        return finishReview(projection, rules, input);
    }

    private int lintMigration(GraphWorkspace workspace) throws Exception {
        if (workspace == null) return missingWorkspace();
        if (!"lint".equals(positional(commandArgs, 1))) {
            return usageError("Usage: schema migration lint --file <migration.sql> --rules <ruleset.yaml>");
        }
        Path migration = requiredFile("--file");
        Path rules = requiredFile("--rules");
        CandidateDdlProjector.ProjectionResult projection = ddlProjector.projectMigrationFile(workspace, migration);
        Map<String, Object> input = reviewInput("migration", "migration", migration, projection.changes());
        String sql = Files.readString(migration, StandardCharsets.UTF_8);
        input.put("sql", sql);
        input.put("migrationSql", sql);
        input.put("migrationRef", migration.toString());
        List<SqlStatementAnalyzer.StatementSlice> dml = sqlStatementAnalyzer.splitStatementsWithLines(sql).stream()
                .filter(statement -> {
                    String type = sqlStatementAnalyzer.detectSqlType(statement.sql());
                    return "UPDATE".equals(type) || "DELETE".equals(type);
                }).toList();
        input.put("sqlStatements", dml.stream().map(SqlStatementAnalyzer.StatementSlice::sql).toList());
        input.put("sqlSourceRefs", dml.stream()
                .map(statement -> migration + ":" + statement.line()).toList());
        putOptionalReviewFile(input, "rollback", "--rollback", false);
        putOptionalReviewFile(input, "precheck", "--precheck", true);
        putOptionalReviewFile(input, "postcheck", "--postcheck", true);
        input.put("verificationSql", input.get("postcheckSql"));
        input.put("similarObjects", List.of());
        return finishReview(projection, rules, input);
    }

    private int finishReview(CandidateDdlProjector.ProjectionResult projection, Path rules,
            Map<String, Object> input) {
        if (!projection.diagnostics().isEmpty()) {
            printReviewFailure(projection);
            return 2;
        }
        PolicyCheckResult result = policyService.checkCandidate(alias, rules, GraphActor.human,
                projection.workspace(), input);
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("evaluation", result.evaluation());
        output.put("violations", result.violations());
        output.put("diagnostics", projection.diagnostics());
        output.put("similarObjects", input.getOrDefault("similarObjects", List.of()));
        output.put("changes", projection.changes());
        if (jsonOutput) {
            CliJson.printSuccess(output);
        } else {
            printPolicyResult(result);
            if (input.get("similarObjects") instanceof Collection<?> similar && !similar.isEmpty()) {
                System.out.println("Similar objects:");
                similar.forEach(item -> System.out.println("  " + item));
            }
            projection.changes().forEach(change -> System.out.printf("  %s %s %s%n",
                    change.changeType(), change.objectType(), change.sourceRef()));
        }
        return result.exitCode();
    }

    private Map<String, Object> reviewInput(String reviewType, String inputType, Path inputPath,
            List<CandidateDdlProjector.ProjectionChange> changes) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("reviewType", reviewType);
        input.put("inputType", inputType);
        input.put("inputRef", inputPath.toString());
        input.put("changes", changes.stream().map(change -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("changeType", change.changeType());
            item.put("objectType", change.objectType());
            item.put("targetId", change.targetId());
            item.put("sourceRef", change.sourceRef());
            item.put("destructive", change.destructive());
            if (change.before() != null) item.put("before", change.before());
            if (change.after() != null) item.put("after", change.after());
            return item;
        }).toList());
        return input;
    }

    private List<WorkspaceSearchEngine.SearchResult> similarObjects(GraphWorkspace workspace,
            List<CandidateDdlProjector.ProjectionChange> changes) {
        WorkspaceSearchEngine search = new WorkspaceSearchEngine(workspace);
        Map<String, WorkspaceSearchEngine.SearchResult> result = new LinkedHashMap<>();
        for (CandidateDdlProjector.ProjectionChange change : changes) {
            if (change.after() instanceof TableWorkspaceNode table) {
                addSimilar(result, search.search(table.getName()));
                table.getColumns().forEach(column -> addSimilar(result, search.search(column.getName())));
            } else if (change.after() instanceof ColumnWorkspaceNode column) {
                addSimilar(result, search.search(column.getName()));
            }
            if (result.size() >= 20) break;
        }
        return result.values().stream().limit(20).toList();
    }

    private void addSimilar(Map<String, WorkspaceSearchEngine.SearchResult> result,
            List<WorkspaceSearchEngine.SearchResult> candidates) {
        for (WorkspaceSearchEngine.SearchResult candidate : candidates) {
            result.putIfAbsent(candidate.getId(), candidate);
        }
    }

    private Path requiredFile(String option) {
        String value = requiredOption(commandArgs, option);
        Path path = Path.of(value);
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("file not found: " + value);
        return path;
    }

    private void putOptionalReviewFile(Map<String, Object> input, String name,
            String option, boolean readOnly) {
        String value = option(commandArgs, option);
        if (value == null) return;
        try {
            Path path = requiredFile(option);
            String sql = Files.readString(path, StandardCharsets.UTF_8);
            List<SqlStatementAnalyzer.StatementSlice> statements =
                    sqlStatementAnalyzer.splitStatementsWithLines(sql);
            if (statements.isEmpty()) throw new IllegalArgumentException(option + " must not be empty");
            for (SqlStatementAnalyzer.StatementSlice statement : statements) {
                String type = sqlStatementAnalyzer.detectSqlType(
                        sqlStatementAnalyzer.parseStatement(statement.sql()));
                if (readOnly && !Set.of("SELECT", "WITH", "SHOW", "DESC", "DESCRIBE", "EXPLAIN")
                        .contains(type)) {
                    throw new IllegalArgumentException(option + " must contain read-only SQL");
                }
            }
            input.put(name + "Sql", sql);
            input.put(name + "Ref", path.toString());
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException(option + " is invalid: " + e.getMessage(), e);
        }
    }

    private void printReviewFailure(CandidateDdlProjector.ProjectionResult projection) {
        String message = projection.diagnostics().stream().map(diagnostic -> diagnostic.sourceRef()
                + ": " + diagnostic.message()).collect(java.util.stream.Collectors.joining("; "));
        if (jsonOutput) {
            CliJson.printFailure("REVIEW_FAILED", message, Map.of(
                    "diagnostics", projection.diagnostics(), "changes", projection.changes()));
        } else {
            projection.diagnostics().forEach(diagnostic -> System.err.println(
                    diagnostic.sourceRef() + ": " + diagnostic.message()));
        }
    }

    private void printPolicyValue(Object value) throws Exception {
        if (jsonOutput) {
            CliJson.printSuccess(value);
        } else if (value instanceof Collection<?> values) {
            for (Object item : values) System.out.println(jsonMapper.writeValueAsString(item));
        } else {
            System.out.println(jsonMapper.writeValueAsString(value));
        }
    }

    private String option(List<String> args, String name) {
        int index = args.indexOf(name);
        return index >= 0 && index + 1 < args.size() ? args.get(index + 1) : null;
    }

    /** 可重复的选项，按出现顺序返回；没有就是空列表。 */
    private List<String> options(List<String> args, String name) {
        List<String> values = new ArrayList<>();
        for (int i = 0; i + 1 < args.size(); i++) {
            if (name.equals(args.get(i))) values.add(args.get(++i));
        }
        return values;
    }

    private String requiredOption(List<String> args, String name) {
        String value = option(args, name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    private String positional(List<String> args, int index) {
        return index < args.size() && !args.get(index).startsWith("-") ? args.get(index) : null;
    }

    private int validateWorkspace(GraphWorkspace workspace) throws Exception {
        if (workspace == null) {
            return missingWorkspace();
        }
        WorkspaceMutationService.MutationResult result = mutationService.mutate(alias,
                workspace.getManifest().getRevision(), GraphActor.system, "schema validate", current -> {
                    validator.validate(current);
                    return new WorkspaceMutationService.MutationOutcome(
                            current.getManifest().getId(), ChangeOperation.verify);
                });
        if (!result.isSuccess()) {
            result.getErrors().forEach(System.err::println);
            return 1;
        }
        workspace = workspaceStore.load(alias);
        List<ValidationIssueRecord> issues = new ArrayList<>(workspace.getValidationIssues());
        issues.addAll(policyService.currentValidationIssues(alias));
        boolean hasErrors = issues.stream()
                .anyMatch(issue -> issue.getSeverity() == ValidationSeverity.error);
        if (jsonOutput) {
            CliJson.printSuccess(issues);
            return hasErrors ? 1 : 0;
        }
        if (issues.isEmpty()) {
            System.out.println("Validation passed");
            return 0;
        }
        System.out.println("Validation issues:");
        for (ValidationIssueRecord issue : issues) {
            System.out.printf("  [%s] %s (%s)%n", issue.getSeverity(), issue.getMessage(), issue.getCode());
        }
        return hasErrors ? 1 : 0;
    }

    /**
     * `schema eval`：跑探针 → 落库 → 打印工作队列。硬错误 > 0 退出码 1。
     *
     * <p>报告的价值不在那几个百分比，在于每条 finding 后面跟着的那条命令——
     * 只说「这里不对」的报告没人动，`schema policy` 那 1487 条就是这么躺了十天的。
     */
    private int evaluateWorkspace(GraphWorkspace workspace) throws Exception {
        if (workspace == null) {
            return missingWorkspace();
        }
        // 跑 + 落库都在 GraphEvalRunner 里，CLI 与 Web UI 共用同一份——
        // 评估页那个「跑一次」按钮调的是同一段代码，所以两个入口算不出两个结果。
        GraphEvalRunner.Result result = GraphEvalRunner.runAndSave(alias, workspace);
        GraphEvaluation evaluation = result.evaluation();
        String evaluationId = result.evaluationId();

        if (jsonOutput) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("evaluationId", evaluationId);
            payload.put("alias", alias);
            payload.put("revision", workspace.getManifest().getRevision());
            payload.put("status", evaluation.status());
            payload.put("errorCount", evaluation.errorCount());
            payload.put("warningCount", evaluation.warningCount());
            payload.put("metrics", evaluation.metrics());
            payload.put("findings", evaluation.findings());
            CliJson.printSuccess(payload);
            return evaluation.failed() ? 1 : 0;
        }

        System.out.println("评估 " + evaluationId + "（revision "
                + workspace.getManifest().getRevision() + "）");
        if (evaluation.findings().isEmpty()) {
            System.out.println("没有硬错误。");
        } else {
            System.out.println(evaluation.errorCount() + " 条硬错误、"
                    + evaluation.warningCount() + " 条改进项：");
            printFindings(evaluation.findings());
        }
        System.out.println("覆盖率（只报告，不判定）：" + evaluation.metrics().entrySet().stream()
                .map(entry -> entry.getKey() + " " + Math.round(entry.getValue() * 1000) / 10.0 + "%")
                .collect(Collectors.joining("  ")));
        return evaluation.failed() ? 1 : 0;
    }

    /**
     * `schema gaps`：交互日志（{@code agent_interaction}）的消费侧，补图谱之前先跑一次。
     *
     * <p>按「被读取次数多但语义缺失」给缺口排序，外加搜空的查询词单列一段——这两个信号
     * 加起来才是「先补哪」的答案，光看覆盖率百分比（{@code schema eval}）分不出「这张表
     * 确实没人关心」和「这张表有真实需求但没人补过」，读侧遥测正是为了补上这个分母。
     *
     * <p>排序用的读取次数目前只有 describe/path 的数据——search 的埋点由另一个 agent接，
     * 在那之前这里天然缺一部分信号，不是 bug；等 search 接上之后，历史遥测里已经在跑的
     * describe/path 数据不需要回填，直接一起参与排序。
     */
    private int gapsAction(GraphWorkspace workspace) throws Exception {
        if (workspace == null) {
            return missingWorkspace();
        }
        int limit = effectiveSearchLimit(20);
        RunStateStore runState = new RunStateStore();
        Map<String, Integer> hits = runState.targetHitCounts(alias, null);

        record GapCandidate(int hits, GraphFinding finding) {
        }
        List<GapCandidate> candidates = new ArrayList<>();
        for (TableWorkspaceNode table : workspace.getTables().values()) {
            int tableHits = hits.getOrDefault(table.getId(), 0);
            if (tableHits == 0) {
                continue; // 没人读过就不是「缺口」，是「没人问过」——两者该做的事不同
            }
            boolean curated = nonBlank(table.getDescription()) || nonBlank(table.getBusinessName())
                    || nonBlank(table.getGrain())
                    || table.getColumns().stream().anyMatch(c -> nonBlank(c.getDescription()));
            if (curated) {
                continue;
            }
            candidates.add(new GapCandidate(tableHits, GraphFinding.warning("read-gap", table.getId(),
                    table.getQualifiedName() + " 被读取 " + tableHits + " 次，但从未有人整理过",
                    "sql-cli " + alias + " schema edit --table " + table.getQualifiedName()
                            + " --description '...'")));
        }
        candidates.sort(Comparator.comparingInt(GapCandidate::hits).reversed());
        List<GraphFinding> gaps = candidates.stream().limit(limit).map(GapCandidate::finding).toList();
        List<RunStateStore.MissedQuery> missed = runState.missedSearchQueries(alias, limit);

        if (jsonOutput) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("alias", alias);
            payload.put("gaps", gaps);
            payload.put("missedQueries", missed);
            CliJson.printSuccess(payload);
            return 0;
        }

        if (gaps.isEmpty() && missed.isEmpty()) {
            System.out.println("没有缺口——读得越多这里才越准，眼下还没有被读过又没人整理的对象。");
            return 0;
        }
        if (!gaps.isEmpty()) {
            System.out.println(gaps.size() + " 个缺口对象（按被读取次数降序）：");
            printFindings(gaps);
        }
        if (!missed.isEmpty()) {
            System.out.println();
            System.out.println(missed.size() + " 个搜空的查询词（按出现次数降序）：");
            for (RunStateStore.MissedQuery query : missed) {
                System.out.printf("  \"%s\" 搜空 %d 次（最近一次 %s）%n", query.query(), query.count(),
                        java.time.Instant.ofEpochMilli(query.lastSeenAt()));
            }
        }
        return 0;
    }

    /**
     * finding 的打印格式只有一份：`schema eval` 和 `schema data-quality` 共用。
     * 「修：」那一行是报告存在的全部理由——只说「这里不对」的报告没人动。
     */
    private void printFindings(List<GraphFinding> findings) {
        for (GraphFinding finding : findings) {
            System.out.printf("  [%s] %s %s%n", finding.severity(), finding.probe(),
                    finding.targetId() == null ? "" : finding.targetId());
            System.out.println("      " + finding.message());
            if (finding.remediation() != null) {
                System.out.println("      修：" + finding.remediation());
            }
        }
    }

    /** 评估结果落运行库。findings 独立成行，能排序能分页。 */
    /** 评估结果落运行库。实现在 {@link GraphEvalRunner#save}，UI 那条入口共用同一份。 */
    private void saveEvaluation(String evaluationId, String source, long revision, long startedAt,
            String status, List<GraphFinding> findings, String metricsJson) throws java.io.IOException {
        GraphEvalRunner.save(evaluationId, alias, source, revision, startedAt, status,
                findings, metricsJson);
    }

    private int missingWorkspace() {
        String message = "图谱工作区不存在，请先运行: sql-cli " + alias
                + " schema import --from-db";
        if (jsonOutput) {
            CliJson.printFailure("WORKSPACE_NOT_FOUND", message);
        } else {
            System.err.println(message);
        }
        return 1;
    }

    private int usageError(String message) {
        if (jsonOutput) {
            CliJson.printFailure("USAGE_ERROR", message);
        } else {
            System.err.println(message);
        }
        return 2;
    }

    private int commandFailure(String message) {
        if (jsonOutput) {
            CliJson.printFailure("COMMAND_FAILED", JdbcUrlParser.redactSecrets(message));
        } else {
            System.err.println(message);
        }
        return 1;
    }

    private void printImportJob(ImportJob job) {
        System.out.println("导入任务:");
        System.out.printf("  JobId: %s%n", job.getJobId());
        System.out.printf("  Status: %s%n", job.getStatus());
        System.out.printf("  Phase: %s%n", job.getCurrentPhase());
        if (job.getMessage() != null) {
            System.out.printf("  Message: %s%n", job.getMessage());
        }
        System.out.printf("  Tables: %d/%d completed, %d failed%n",
                job.getStats().getCompletedTables(),
                job.getStats().getTotalTables(),
                job.getStats().getFailedTables());
        System.out.printf("  Tasks: %d/%d completed, %d failed%n",
                job.getStats().getCompletedTasks(),
                job.getStats().getTotalTasks(),
                job.getStats().getFailedTasks());
        if (job.getCheckpoint() != null && job.getCheckpoint().getTableCursor() != null) {
            System.out.printf("  Checkpoint: %s.%s (%s)%n",
                    job.getCheckpoint().getSchemaCursor(),
                    job.getCheckpoint().getTableCursor(),
                    job.getCheckpoint().getPhase());
        }
        if (job.getUpdatedAt() != null) {
            System.out.printf("  Updated: %s%n", job.getUpdatedAt());
        }
    }

    private List<ColumnWorkspaceNode> findColumns(GraphWorkspace workspace, TableWorkspaceNode table) {
        List<ColumnWorkspaceNode> columns = new ArrayList<>(table.getColumns());
        columns.sort(Comparator.comparing(ColumnWorkspaceNode::getOrdinal, Comparator.nullsLast(Integer::compareTo)));
        return columns;
    }

    /**
     * {@code schema describe} 的关系列表：不过滤 ignored。这里是给人看的表详情，
     * 判据见 WorkspaceMutationService#isIgnored javadoc 的三分表——"显式展示"一类，
     * 过滤掉等于把已拒绝的关系悄悄藏起来，调用方 {@link #describeTable} 用 candidateMark
     * 打 [已忽略] 标注即可。
     */
    private List<RelationWorkspaceEdge> findRelations(GraphWorkspace workspace, TableWorkspaceNode table) {
        List<RelationWorkspaceEdge> relations = new ArrayList<>();
        for (RelationWorkspaceEdge relation : workspace.getRelations()) {
            String fromTableId = tableIdForColumnId(relation.getFrom());
            String toTableId = tableIdForColumnId(relation.getTo());
            boolean fromMatches = fromTableId != null && fromTableId.equalsIgnoreCase(table.getId());
            boolean toMatches = toTableId != null && toTableId.equalsIgnoreCase(table.getId());
            if (fromMatches || toMatches) {
                relations.add(relation);
            }
        }
        return relations;
    }

    /**
     * {@code schema query --depth N} 的关系 BFS：必须过滤 ignored。
     * 这是第二套独立的关系遍历（不走 WorkspacePathFinder），--json 输出会被 Agent
     * 直接消费来判断一张表跟什么相关——人已经明确拒绝过的边留在这里，等于"拒绝"这个
     * 动作对 Agent 不生效。ignored 过滤、按表分桶现在都在共享的 {@link RelationAdjacency}
     * 里做一次，这里只管 BFS 怎么走（限深、去重访问过的表）。
     */
    private List<RelationWorkspaceEdge> expandRelations(GraphWorkspace workspace, TableWorkspaceNode table, int maxDepth) {
        RelationAdjacency adjacency = RelationAdjacency.build(workspace);
        Set<String> visitedTables = new HashSet<>();
        Queue<String> queue = new ArrayDeque<>();
        List<RelationWorkspaceEdge> results = new ArrayList<>();
        queue.add(table.getId());
        visitedTables.add(table.getId());
        int level = 0;
        while (!queue.isEmpty() && level < Math.max(1, maxDepth)) {
            int size = queue.size();
            for (int i = 0; i < size; i++) {
                String tableId = queue.poll();
                // 老实现按 if/else-if 互斥分支，保证自引用边（from/to 落在同一张表）
                // 一次弹出只算一次；共享邻接表按表分桶后，自引用边的正向、反向记录
                // 会落进同一个桶，这里用 edge id 去重复刻同样效果——注意这个 Set 只在
                // "这一次弹出"内生效，同一条边在它的另一端被弹出时仍然会再算一次，
                // 那是原来就有的行为（不同弹出各自独立扫描），不在这次去重的范围内。
                Set<String> addedThisPop = new HashSet<>();
                for (RelationAdjacency.Entry entry : adjacency.neighbors(tableId)) {
                    if (!addedThisPop.add(entry.getEdge().getId())) {
                        continue;
                    }
                    results.add(entry.getEdge());
                    String neighborTableId = entry.getNeighborTableId();
                    if (neighborTableId != null && visitedTables.add(neighborTableId)) {
                        queue.add(neighborTableId);
                    }
                }
            }
            level++;
        }
        return results;
    }

    private ColumnWorkspaceNode resolveColumn(GraphWorkspace workspace, String ref) {
        // Try schema.table.column format
        String[] parts = ref.split("\\.");
        if (parts.length == 3) {
            TableWorkspaceNode table = workspace.getTableByQualifiedName(parts[0] + "." + parts[1]);
            if (table != null) {
                return table.findColumn(parts[2]);
            }
        }
        if (parts.length == 2) {
            TableWorkspaceNode table = workspace.getTableByQualifiedName(parts[0]);
            if (table != null) {
                return table.findColumn(parts[1]);
            }
        }
        return null;
    }

    /**
     * 写入「此列是彼列的冗余副本」标注，权威源指向另一列。传空串清空标注（与
     * {@code --format}/{@code --enum-values} 清空的写法一致）。
     *
     * <p>这里只做结构校验（权威源列必须存在、不能指向自己），不做规范化建议——
     * 判断"这个字段是不是真的冗余"是人/Agent 读代码或读数据得出的结论，不是这条命令
     * 能推导的事。
     */
    private void applyRedundantOf(GraphWorkspace workspace, ColumnWorkspaceNode column, String selfId,
            String redundantOfRef) {
        if (redundantOfRef.isBlank()) {
            column.getAttributes().remove(ColumnWorkspaceNode.ATTR_REDUNDANT_OF);
            return;
        }
        String targetId = resolveColumnId(workspace, redundantOfRef);
        if (targetId == null) {
            throw new IllegalArgumentException("Redundant-of target column not found: " + redundantOfRef);
        }
        if (targetId.equals(selfId)) {
            throw new IllegalArgumentException("--redundant-of 不能指向自身: " + redundantOfRef);
        }
        column.getAttributes().put(ColumnWorkspaceNode.ATTR_REDUNDANT_OF, targetId);
    }

    /** 列 id（{@code column:alias:schema.table.column}）取出人可读的 {@code schema.table.column}；
     * 解析失败（id 格式不对）原样返回，不让展示层因为一条脏数据崩掉。 */
    private static String columnDisplayName(String columnId) {
        if (columnId == null) {
            return null;
        }
        String[] parts = columnId.split(":", 3);
        return parts.length == 3 ? parts[2] : columnId;
    }

    private String tableIdForColumnId(String columnId) {
        if (columnId == null || !columnId.startsWith("column:")) {
            return null;
        }
        String[] parts = columnId.split(":", 3);
        if (parts.length < 3) {
            return null;
        }
        String qualifiedName = parts[2];
        int lastDot = qualifiedName.lastIndexOf('.');
        if (lastDot < 0) {
            return null;
        }
        return "table:" + parts[1] + ":" + qualifiedName.substring(0, lastDot);
    }

    private String tableNameForColumnId(String columnId) {
        if (columnId == null || !columnId.startsWith("column:")) {
            return null;
        }
        String[] parts = columnId.split(":", 3);
        if (parts.length < 3) {
            return null;
        }
        String qualifiedName = parts[2];
        int lastDot = qualifiedName.lastIndexOf('.');
        if (lastDot < 0) {
            return null;
        }
        int firstDot = qualifiedName.indexOf('.');
        if (firstDot >= lastDot) {
            return null;
        }
        return qualifiedName.substring(firstDot + 1, lastDot);
    }

    private void printStats(GraphWorkspace workspace) {
        WorkspaceStats stats = workspace.getManifest().getStats();
        System.out.println("图谱统计:");
        System.out.printf("  Schemas: %d%n", stats.getSchemas());
        System.out.printf("  Tables: %d%n", stats.getTables());
        System.out.printf("  Columns: %d%n", stats.getColumns());
        System.out.printf("  Terms: %d (候选 %d)%n",
                workspace.getTerms().size(), candidateTerms(workspace));
        System.out.printf("  Relationships: %d (候选 %d, 已忽略 %d)%n",
                stats.getRelations(), candidateRelations(workspace), stats.getIgnoredRelations());
        System.out.printf("  ValidationIssues: %d%n", stats.getValidationIssues());
        if (workspace.getManifest().getUpdatedAt() != null) {
            System.out.printf("  Updated: %s%n", workspace.getManifest().getUpdatedAt());
        }
    }

    /** 委托给 {@link GraphWorkspace#resolveColumnId}——UI 的 metric 表单走同一份解析。 */
    private String resolveColumnId(GraphWorkspace workspace, String ref) {
        return workspace.resolveColumnId(alias, ref);
    }

    private String resolveTermTargetId(GraphWorkspace workspace, String ref) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        if (ref.startsWith("table:")) {
            return workspace.getTables().containsKey(ref) ? ref : null;
        }
        if (ref.startsWith("column:")) {
            return workspace.findColumnById(ref) != null ? ref : null;
        }
        TableWorkspaceNode table = workspace.getTableByQualifiedName(ref);
        if (table != null) {
            return table.getId();
        }
        return resolveColumnId(workspace, ref);
    }

    private String resolveNodeRef(GraphWorkspace workspace, String ref) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        if (ref.startsWith("table:") || ref.startsWith("column:") || ref.startsWith("term:")) {
            return ref;
        }
        ColumnWorkspaceNode column = resolveColumn(workspace, ref);
        if (column != null) {
            TableWorkspaceNode colTable = workspace.getTableByQualifiedName(ref.contains(".") ? ref.substring(0, ref.lastIndexOf('.')) : ref);
            return column.computeId(alias, colTable != null ? colTable.getSchema() : "", colTable != null ? colTable.getName() : "");
        }
        TableWorkspaceNode table = workspace.getTableByQualifiedName(ref);
        if (table != null) {
            return table.getId();
        }
        if (workspace.getTerms().containsKey("term:" + alias + ":" + ref)) {
            return "term:" + alias + ":" + ref;
        }
        return null;
    }

    private void mergeCsv(List<String> target, String csv) {
        for (String value : csvValues(csv)) {
            if (!target.contains(value)) {
                target.add(value);
            }
        }
    }

    private static List<String> csvValues(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public void setTableName2(String tableName2) {
        this.tableName2 = tableName2;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public void setSubAction(String subAction) {
        this.subAction = subAction;
    }

    public void setDepth(int depth) {
        this.depth = depth;
    }

    public void setJsonOutput(boolean jsonOutput) {
        this.jsonOutput = jsonOutput;
    }

    public void setOutputPath(String outputPath) {
        this.outputPath = outputPath;
    }

    public void setInputPath(String inputPath) {
        this.inputPath = inputPath;
    }

    public void setMerge(boolean merge) {
        this.merge = merge;
    }

    public void setFromDb(boolean fromDb) {
        this.fromDb = fromDb;
    }

    public void setForce(boolean force) {
        this.force = force;
    }

    public void setEditTable(String editTable) {
        this.editTable = editTable;
    }

    public void setEditColumn(String editColumn) {
        this.editColumn = editColumn;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setCodeEnumCsv(String codeEnumCsv) {
        this.codeEnumCsv = codeEnumCsv;
    }

    public void setCodeEnumBasis(String codeEnumBasis) {
        this.codeEnumBasis = codeEnumBasis;
    }

    public void setAssumeYes(boolean assumeYes) {
        this.assumeYes = assumeYes;
    }

    public void setDeadMonths(Integer deadMonths) {
        this.deadMonths = deadMonths;
    }

    public void setEnumValuesCsv(String enumValuesCsv) {
        this.enumValuesCsv = enumValuesCsv;
    }

    public void setValueFormat(String valueFormat) {
        this.valueFormat = valueFormat;
    }

    public void setExample(String example) {
        this.example = example;
    }

    public void setAddTag(String addTag) {
        this.addTag = addTag;
    }

    public void setAddConstraint(String addConstraint) {
        this.addConstraint = addConstraint;
    }

    public void setEditBusinessName(String editBusinessName) {
        this.editBusinessName = editBusinessName;
    }

    public void setEditSemanticType(String editSemanticType) {
        this.editSemanticType = editSemanticType;
    }

    public void setEditRedundantOf(String editRedundantOf) {
        this.editRedundantOf = editRedundantOf;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public void setTermName(String termName) {
        this.termName = termName;
    }

    public void setRelationType(String relationType) {
        this.relationType = relationType;
    }

    public void setFromRef(String fromRef) {
        this.fromRef = fromRef;
    }

    public void setToRef(String toRef) {
        this.toRef = toRef;
    }

    public void setJoinExpression(String joinExpression) {
        this.joinExpression = joinExpression;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }

    public void setVerifiedFlag(Boolean verifiedFlag) {
        this.verifiedFlag = verifiedFlag;
    }

    public void setAliasesCsv(String aliasesCsv) {
        this.aliasesCsv = aliasesCsv;
    }

    public void setNegativeAliasesCsv(String negativeAliasesCsv) {
        this.negativeAliasesCsv = negativeAliasesCsv;
    }

    public void setPrimaryTargetRef(String primaryTargetRef) {
        this.primaryTargetRef = primaryTargetRef;
    }

    public void setTermFilters(List<String> termFilters) {
        this.termFilters = termFilters == null ? new ArrayList<>() : termFilters;
    }

    public void setMappedRefsCsv(String mappedRefsCsv) {
        this.mappedRefsCsv = mappedRefsCsv;
    }

    public void setTargetRef(String targetRef) {
        this.targetRef = targetRef;
    }

    public void setSourceRefsCsv(String sourceRefsCsv) {
        this.sourceRefsCsv = sourceRefsCsv;
    }

    public void setLineageKindArg(String lineageKindArg) {
        this.lineageKindArg = lineageKindArg;
    }

    public void setLineageId(String lineageId) {
        this.lineageId = lineageId;
    }

    public void setExpression(String expression) {
        this.expression = expression;
    }

    public void setThrough(String through) {
        this.through = through;
    }

    public void setLineageDownstream(boolean lineageDownstream) {
        this.lineageDownstream = lineageDownstream;
    }

    public void setCasesPath(String casesPath) {
        this.casesPath = casesPath;
    }

    public void setAnswersPath(String answersPath) {
        this.answersPath = answersPath;
    }

    public void setBoost(Double boost) {
        this.boost = boost;
    }

    public void setImportSchema(String importSchema) {
        this.importSchema = importSchema;
    }

    public void setImportTable(String importTable) {
        this.importTable = importTable;
    }

    public void setBatchSize(Integer batchSize) {
        this.batchSize = batchSize;
    }

    public void setForceOverwrite(boolean forceOverwrite) {
        this.forceOverwrite = forceOverwrite;
    }


    public void setHelpRequested(boolean helpRequested) {
        this.helpRequested = helpRequested;
    }

    public void setCommandArgs(List<String> commandArgs) {
        this.commandArgs = commandArgs == null ? List.of() : List.copyOf(commandArgs);
    }

    public void setMetricName(String metricName) {
        this.metricName = metricName;
    }

    public void setFilters(String filters) {
        this.filters = filters;
    }

    public void setGrainColumnRef(String grainColumnRef) {
        this.grainColumnRef = grainColumnRef;
    }

    public void setGrainsCsv(String grainsCsv) {
        this.grainsCsv = grainsCsv;
    }

    public void setDimensionsCsv(String dimensionsCsv) {
        this.dimensionsCsv = dimensionsCsv;
    }

    public void setJoinPathCsv(String joinPathCsv) {
        this.joinPathCsv = joinPathCsv;
    }

    public void setRequestedGrain(String requestedGrain) {
        this.requestedGrain = requestedGrain;
    }

    public void setTimeFrom(String timeFrom) {
        this.timeFrom = timeFrom;
    }

    public void setTimeTo(String timeTo) {
        this.timeTo = timeTo;
    }
}
