/* ── API type definitions matching Java backend DTOs ── */

/**
 * 关系类型（与 Java 枚举一致）。
 *
 * foreign_key 由数据库导入维护，人和 Agent 不能创建或编辑；
 * join_observed 和 term_mapping 是人与 Agent 维护的，导入不会覆盖。
 */
export type RelationType =
  | 'foreign_key'
  | 'join_observed'
  | 'term_mapping';

/** 字段业务语义类型（与 Java SemanticType 枚举一致） */
export type SemanticType =
  | 'phone' | 'id_card' | 'email' | 'person_name' | 'address' | 'bank_card' | 'account'
  | 'amount' | 'quantity' | 'status' | 'code' | 'name' | 'description'
  | 'primary_id' | 'ref_id' | 'created_at' | 'updated_at' | 'created_by' | 'updated_by'
  | 'deleted_flag' | 'version';

/** 语义类型下拉选项，按用途分组。 */
export const SEMANTIC_TYPE_GROUPS: { label: string; options: { value: SemanticType; label: string }[] }[] = [
  {
    label: '敏感信息',
    options: [
      { value: 'phone', label: '手机号' },
      { value: 'id_card', label: '身份证号' },
      { value: 'email', label: '邮箱' },
      { value: 'person_name', label: '姓名' },
      { value: 'address', label: '地址' },
      { value: 'bank_card', label: '银行卡号' },
      { value: 'account', label: '账号' },
    ],
  },
  {
    label: '业务含义',
    options: [
      { value: 'amount', label: '金额' },
      { value: 'quantity', label: '数量' },
      { value: 'status', label: '状态' },
      { value: 'code', label: '业务编码' },
      { value: 'name', label: '名称' },
      { value: 'description', label: '描述' },
    ],
  },
  {
    label: '系统字段',
    options: [
      { value: 'primary_id', label: '主键 ID' },
      { value: 'ref_id', label: '关联 ID' },
      { value: 'created_at', label: '创建时间' },
      { value: 'updated_at', label: '更新时间' },
      { value: 'created_by', label: '创建人' },
      { value: 'updated_by', label: '更新人' },
      { value: 'deleted_flag', label: '逻辑删除标记' },
      { value: 'version', label: '版本号' },
    ],
  },
];

/** Relation cardinality (Java enum values) */
export type RelationCardinality =
  | 'one_to_one'
  | 'one_to_many'
  | 'many_to_one'
  | 'many_to_many'
  | 'unknown';

/** Validation severity (Java enum values) */
export type ValidationSeverity = 'error' | 'warning' | 'info';

// ── Session ──

export interface SessionDto {
  alias: string;
  token?: string;
  revision: number;
  readOnly: boolean;
  capabilities: string[];
}

export interface AliasSummaryDto {
  name: string;
  dbType?: string;
  description?: string;
  readOnly: boolean;
  graphAvailable: boolean;
  tables?: number;
  relations?: number;
  updatedAt?: string;
  /** 三个审批开关：打开后对应操作要在评审页放行才继续 */
  approveQuery: boolean;
  approveUpdate: boolean;
  approveGraph: boolean;
}

export interface AliasDirectoryDto {
  aliases: AliasSummaryDto[];
}

/** `GET /api/aliases/{name}`：secretRef 只回 scheme（`keyring:***`），密码值永不回显。 */
export interface AliasDetailDto {
  name: string;
  dbType?: string;
  driverRef?: string;
  jdbcUrl?: string;
  /** sqlite 的连接目标：.db 文件绝对路径 */
  database?: string;
  username?: string;
  secretRef: string;
  secretScheme: string;
  description?: string;
  /** 默认 schema：SQL 不写前缀时用它 */
  defaultSchema?: string;
  accessMode?: string;
  yearningHost?: string;
  yearningIdc?: string;
  yearningDatabase?: string;
  readonly: boolean;
  approveQuery: boolean;
  approveUpdate: boolean;
  approveGraph: boolean;
  sm4PrivateTag?: string;
  sm4Version?: string;
  decryptColumns: string[];
  /** 仅 sqlite：文件不存在时是否允许连接时新建空库 */
  sqliteCreateIfMissing?: boolean;
}

export interface AliasTestResultDto {
  name: string;
  success: boolean;
  message?: string;
  rootCause?: string;
  hints: string[];
  serverVersion?: string;
}

export type ExecutionStatus = 'success' | 'failed' | 'rejected';

export interface SqlExecutionRecordDto {
  id: number;
  alias: string;
  sqlType?: string;
  /** 脱敏文本，列表里显示这个 */
  sql: string;
  status?: ExecutionStatus | string;
  /** 失败或被拒绝的原因；点开记录看的就是它 */
  errorSummary?: string | null;
  startedAt: number;
  elapsedMs: number;
  affectedRows?: number | null;
  /** 原文 SQL；老库里的写语句没存，缺失时只能拿到脱敏的 sql，无法重放 */
  rawSql?: string;
  rawSqlAvailable?: boolean;
  /** 语句命中的 schema，筛选用；解析不出来是 null */
  targetSchema?: string | null;
  source?: string;
}

// ── 回滚与备份 ──

export interface RecoveryPreviewDto {
  executionId: number;
  /** 脚本来源：运行库 #id，或升级前留下的恢复文件路径 */
  source: string;
  /** 执行前的原始行快照 */
  backup: string;
  /** 反向语句：UPDATE 的反向 UPDATE，DELETE 的补回 INSERT */
  rollback: string;
}

// ── 审批 ──

export type ApprovalKind = 'query' | 'update' | 'graph' | 'recovery';
export type ApprovalStatus = 'pending' | 'approved' | 'rejected' | 'expired';

/** 批次状态。draft 不出现在任何列表里——它还没提交，也没发生过。 */
export type BatchStatus =
  | 'draft' | 'pending' | 'applied' | 'partial' | 'rejected' | 'failed'
  /** 被同一对象的新提交取代——没有人裁决过它，跟 rejected 分开 */
  | 'superseded';

/**
 * 一批：一个业务意图下的一组条目。人在待审批队列里裁决的是 intent 这件事，
 * 不是里面的 40 个对象。
 */
export interface ApprovalBatchDto {
  id: number;
  alias: string;
  /** graph 走一次 load/apply/save，sql 走单连接单事务 */
  kind: 'graph' | 'sql';
  intent: string;
  status: BatchStatus;
  /** SQL 批次含 DDL 时为 false：DDL 隐式提交，rollback 对它无效 */
  recoverable: boolean;
  createdAt: number;
  submittedAt?: number | null;
  decidedAt?: number | null;
  reason?: string | null;
}

export interface ApprovalDto {
  id: number;
  alias: string;
  kind: ApprovalKind;
  summary: string;
  detail?: string | null;
  status: ApprovalStatus;
  reason?: string | null;
  createdAt: number;
  decidedAt?: number | null;
  /** 关联的写任务；null 表示没有（读操作、图谱变更） */
  taskRunId?: number | null;
  /** 审批针对的对象 id（图谱关系 relation:.../执行记录 execution:...）；null 表示没有明确对象 */
  targetId?: string | null;
  /** 只有 kind=graph 有：待批准的变更本身。图谱先落 db、批准后才写图谱 */
  payload?: GraphChangePayloadDto | null;
  /** 归到哪一批；null 就是一条独立条目（升级前的记录全是这样） */
  batchId?: number | null;
  /** 批次内序号，SQL 批次的执行顺序 */
  seq?: number | null;
  /**
   * 关联写任务的终态。不是这条审批的状态，是批准之后实际发生了什么——
   * 批准了但执行被拒 / 失败时，只看 status 会以为它跑成功了。
   */
  taskStatus?: string | null;
  /** 溯源：出自哪次会话、哪个 agent。取不到就是 null，不会有占位 id */
  sessionId?: string | null;
  agentId?: string | null;
  /** 批次里的 SQL 条目跑完之后指向的执行记录 */
  executionId?: number | null;
}

/**
 * 一条待批准（或已记入流水）的图谱变更：改的哪个对象、从什么样变成什么样。
 *
 * before 为 null 表示新增，after 为 null 表示删除。变更流水里只有 after——
 * 同一个 target 上一条流水的 after 就是这一条的 before。
 */
export interface GraphChangePayloadDto {
  targetId: string;
  operation: string;
  actor?: string | null;
  baseRevision: number;
  before?: Record<string, unknown> | null;
  after?: Record<string, unknown> | null;
  /**
   * `apply`（缺省）= 变更还没进图谱，批准时才写；
   * `publish` = 候选边已经在图谱里，批准 = 发布，拒绝 = 转 ignored（没有 before/after）。
   */
  action?: 'apply' | 'publish' | null;
}

/** 一次图谱更新的可追溯记录（graph_change_log）。 */
export interface GraphChangeDto {
  id: number;
  alias: string;
  operation: string;
  targetId?: string | null;
  actor?: string | null;
  revisionBefore: number;
  revisionAfter: number;
  createdAt: number;
  /** 非空表示这次更新是被那条审批放行的 */
  approvalId?: number | null;
  reason?: string | null;
  payload?: GraphChangePayloadDto | null;
}

/** kind=graph 且挂了关系 targetId 时的候选详情：字段、证据、校验结果。 */
export interface GraphCandidateDto {
  relationId: string;
  found: boolean;
  note?: string | null;
  status?: string | null;
  changeType?: string | null;
  fields?: Record<string, unknown> | null;
  evidence?: { sourceType: string; sourceRef: string; observedAt: string | null }[];
  validation?: { severity: string | null; code: string; message: string }[];
}

/** 写操作执行前采集的预检结论，来自 precheck 事件的 payload */
export interface PrecheckDto {
  table?: string | null;
  recoverySupported: boolean;
  primaryKey: string[];
  estimatedRows?: number | null;
  /** 非空表示预检没做完，这里是原因 */
  skippedReason?: string | null;
}

export interface ApprovalTaskRunDto {
  id: number;
  status: string;
  createdAt: number;
  updatedAt: number;
}

export interface ApprovalEventDto {
  /** submitted / guard / precheck / approval / cipher / dialect / executed */
  eventType: string;
  createdAt: number;
  /** 已解析的 JSON 对象；只有 precheck 和 executed 有 */
  payload?: Record<string, unknown> | null;
}

export interface ApprovalDetailDto extends ApprovalDto {
  taskRun?: ApprovalTaskRunDto | null;
  events: ApprovalEventDto[];
  graphCandidate?: GraphCandidateDto | null;
}

// ── SQL 重执行 ──

export interface SqlColumnDto {
  name: string;
  type: string;
}

export type SqlCellValue = string | number | boolean | null;

/**
 * 结果列的业务含义，来自图谱。只在 SELECT 能解析出单一表、且列名（无 AS 别名）
 * 精确命中该表图谱列时才有——对不上就没有条目，UI 保持裸列名，绝不猜。
 */
export interface ColumnMetaDto {
  businessName?: string;
  /** 语义类型枚举原值（status/phone/…），与 CLI 一致，不翻译 */
  semanticType?: string;
  /** 值域，形如 `0=待付款` */
  enumValues?: string[];
}

export interface SqlExecuteResultDto {
  columns: SqlColumnDto[];
  rows: SqlCellValue[][];
  rowCount: number;
  elapsedMs: number;
  truncated: boolean;
  /** 列名 → 业务含义；历史重放端点不返回它 */
  columnMeta?: Record<string, ColumnMetaDto> | null;
}

// ── SQL 工作台 ──

/**
 * 工作台执行结果。REJECTED 是正常业务结果（readonly / 无 WHERE / 审批被拒），
 * HTTP 仍然是 200。字段是 SqlExecuteResultDto 的超集，因此能直接喂给 ResultTable。
 */
export interface WorkbenchExecuteResultDto extends SqlExecuteResultDto {
  /** 不改变 status 的提示：advisory 级规则违规、规则检查为什么没跑 */
  notices?: string[];
  status: 'SUCCEEDED' | 'REJECTED' | 'FAILED';
  sqlType: string | null;
  affectedRows: number | null;
  /** 回滚脚本在运行库里的 id；null 表示这次写没有可回滚的脚本 */
  recoveryId: number | null;
  taskId: string | null;
  precheck: PrecheckDto | null;
  errorSummary: string | null;
}

export interface PolicyRuleDto {
  id: string;
  title: string;
  category: string;
  when: Record<string, unknown>;
  statement: Record<string, unknown>;
  severity: 'info' | 'warning' | 'error' | 'critical';
  enforcement: 'advisory' | 'required' | 'blocking';
  /** change = 只对 design review / migration lint 的新 DDL 生效；all = 还查存量图谱 */
  scope?: 'change' | 'all';
  remediation?: string;
  tags: string[];
  sources: string[];
}

export interface PolicyRuleSetDto {
  kind: string;
  id: string;
  title: string;
  version: string;
  dbType?: string;
  match: Record<string, unknown>;
  defaults: Record<string, unknown>;
  rules: PolicyRuleDto[];
  sources: string[];
}

export interface PolicyRuleSetFileDto {
  fileName: string;
  ruleSet: PolicyRuleSetDto;
  enabled: boolean;
}

// ── Workspace ──

export interface WorkspaceStatsDto {
  schemas: number;
  tables: number;
  columns: number;
  relations: number;
  validationIssues: number;
  terms?: number;
}

export interface WorkspaceDto {
  alias: string;
  revision: number;
  modelVersion: string;
  storageVersion: string;
  stats: WorkspaceStatsDto;
}

// ── Schema ──

export interface SchemaDto {
  id: string;
  name: string;
  displayName: string;
  description: string;
  tableCount: number;
}

// ── Terms ──

/** 业务术语（只读，CLI `add-term` 维护）。mappedTargets 来自 term_mapping 关系反查。 */
export interface TermDto {
  id: string;
  name: string;
  displayName: string;
  aliases: string[];
  negativeAliases: string[];
  description: string | null;
  status: string | null;
  confidence: number | null;
  mappedTargets: string[];
  /** 入口表；指向一张表这条术语就是场景，指向列或 null 就是纯同义词路由 */
  primaryTarget: string | null;
  /** 场景专属过滤，含 `:name` 占位符的是必填参数 */
  filters: string[];
  /** 子图里的表（qualifiedName）。UI 用它把表目录筛到这个场景；非场景术语为空 */
  scenarioTables: string[];
  /** 其中为了连通补进来的——术语并没有映射它们，要标出来 */
  bridgeTables: string[];
}

export interface TermsResponseDto {
  terms: TermDto[];
  total: number;
}

// ── BI 指标（口径声明，不是数据）──

export interface MetricJoinStepDto {
  relationId: string;
  joinType: 'inner' | 'left';
}

export interface MetricDto {
  id: string;
  name: string;
  businessName: string | null;
  aliases: string[];
  /** 聚合表达式，如 SUM(app.orders.amount) */
  expression: string | null;
  /** 口径过滤条件——同一个「订单金额」算不算取消单，90% 的争议在这一行 */
  filters: string | null;
  grain: { timeColumn: string | null; grains: string[] } | null;
  /** 列 id 数组（column:alias:schema.table.column） */
  dimensions: string[];
  joinPath: MetricJoinStepDto[];
  status: string | null;
  verified: boolean | null;
  confidence: number | null;
  updatedBy: string | null;
}

export interface MetricsResponseDto {
  metrics: MetricDto[];
  revision: number;
}

export interface MetricSqlDto {
  metric: string;
  sql: string;
}

export interface MetricUpsertRequest {
  name: string;
  businessName?: string;
  aliases?: string[];
  expression: string;
  filters?: string;
  /** schema.table.column，服务端与 CLI 走同一份解析 */
  grainColumn?: string;
  grains?: string[];
  dimensions?: string[];
  joinPath?: MetricJoinStepDto[];
  expectedRevision: number;
  reason?: string;
}

// ── Tables ──

export interface TableListItemDto {
  id: string;
  schema: string;
  name: string;
  qualifiedName: string;
  /** 数据库注释镜像，只由导入写。 */
  comment: string;
  /** 业务描述，人和 Agent 维护，导入不覆盖。 */
  description?: string | null;
  tableType: string;
  columnCount: number;
  tags: string[];
}

export interface TablesResponseDto {
  total: number;
  offset: number;
  limit: number;
  items: TableListItemDto[];
}

export interface TableInfoDto {
  id: string;
  schema: string;
  name: string;
  qualifiedName: string;
  /** 业务名——短标签，用于检索和列头，不放长句。 */
  businessName: string;
  /** 数据库注释镜像，只由导入写；人和 Agent 不写这个字段。 */
  comment: string;
  /** 业务描述——口径、坑、来源，人和 Agent 维护，导入不覆盖。 */
  description?: string | null;
  tableType: string;
  tags: string[];
  primaryKey: string[];
  indexes?: TableIndexDto[];
}

/** 数据库索引。索引「类型」不是存出来的，由 unique + 是否等于主键推出来。 */
export interface TableIndexDto {
  name: string;
  unique: boolean;
  columns: string[];
}

export interface ColumnDataTypeDto {
  raw: string;
  normalized: string;
  length?: number;
  precision?: number;
  scale?: number;
}

/**
 * 字段值域。三个槽位互不重叠，按字段的实际情况填其一：
 * 值能穷举用 `enumValues`（形如 `0=待付款`），值无限但有格式用 `format`，
 * 都不适用但看几个例子有帮助用 `sampleValues`。
 *
 * 值多到不该内联的（字典表）不在这里——那是一条指向字典表的关系边。
 */
export interface ColumnValueHintsDto {
  enumValues?: string[];
  format?: string;
  sampleValues?: string[];
}

export interface ColumnDto {
  name: string;
  dataType: ColumnDataTypeDto;
  /** null 表示数据库没给出可空性（JDBC columnNullableUnknown） */
  nullable?: boolean | null;
  defaultValue: string;
  ordinal: number;
  primaryKey: boolean;
  unique: boolean;
  indexed: boolean;
  /** 数据库注释镜像，只由导入写；人和 Agent 不写这个字段。 */
  comment: string;
  /** 业务名——短标签，用于检索和列头，不放长句。 */
  businessName: string;
  /** 业务描述——口径、坑、来源，人和 Agent 维护，导入不覆盖。 */
  description?: string | null;
  semanticType?: SemanticType | null;
  valueHints?: ColumnValueHintsDto;
  confidence?: number;
  verified?: boolean;
  attributes?: Record<string, unknown>;
}

export interface RelationEdgeDto {
  id: string;
  kind: string;
  version?: number;
  status?: string;
  type: string;
  from: string;
  to: string;
  direction?: string;
  cardinality?: RelationCardinality;
  joinExpression?: string;
  confidence?: number;
  verified?: boolean;
  sourceAlias?: string;
  createdAt?: string;
  updatedAt?: string;
  createdBy?: string;
  updatedBy?: string;
  attributes?: Record<string, unknown>;
}

export interface ValidationIssueDto {
  id: string;
  severity: ValidationSeverity;
  code: string;
  message: string;
  targetId: string;
  field: string;
  producer?: string;
  runId?: string;
  status?: string;
  createdAt?: string;
}

export interface ChangeRecordDto {
  id: string;
  operation: string;
  targetId: string;
  actor: string;
  timestamp: string;
}

/** 血缘对端：另一张表的一个列，加上产生它的表达式/来源（如果有）。 */
export interface LineageEndpointDto {
  schema?: string;
  table?: string;
  column?: string;
  expression?: string;
  through?: string;
  /** identity / transformation / aggregation / rule；旧记录为空 */
  lineageKind?: string | null;
}

export interface ColumnLineageDto {
  upstream: LineageEndpointDto[];
  downstream: LineageEndpointDto[];
}

/** 血缘图节点：schema.table.column 一个字段，level 是相对起点的跳数（负=上游，正=下游）。 */
export interface LineageGraphNodeDto {
  id: string;
  schema?: string;
  table?: string;
  column?: string;
  level: number;
}

export interface LineageGraphEdgeDto {
  from: string;
  to: string;
  expression?: string;
  through?: string;
  /** 血缘记录 id：一条记录展开成多条边时相同，按它聚回「本列的记录」 */
  id?: string;
  lineageKind?: string | null;
}

/** 写进起点这一列的一条血缘记录：详情与删除的单位（图上的边是按源列展开的） */
export interface LineageRecordDto {
  id: string;
  /** 全局图才有：这条记录写的列，schema.table.column */
  target?: string;
  lineageKind?: string | null;
  sources: string[];
  expression?: string | null;
  through?: string | null;
  status?: string | null;
  confidence?: number | null;
  updatedAt?: string | null;
}

/** GET /api/lineage 的返回体：以一个字段为起点，多跳展开。 */
export interface LineageGraphDto {
  center: string;
  depth: number;
  truncated: boolean;
  nodes: LineageGraphNodeDto[];
  edges: LineageGraphEdgeDto[];
  records?: LineageRecordDto[];
  /** 全局图才有：schema.table → 表的总列数，画「其余 N 列」用 */
  tableColumns?: Record<string, number>;
}

/** 一条血缘记录（GET /api/lineage/overview 的元素）。 */
export interface LineageOverviewItemDto {
  id: string;
  /** 图谱 id，形如 column:alias:SCHEMA.TABLE.COL */
  target: string;
  /** 去掉 id 前缀的可读名，SCHEMA.TABLE.COL */
  targetLabel: string;
  sources: string[];
  sourceLabels: string[];
  expression?: string | null;
  through?: string | null;
  lineageKind?: string | null;
  updatedAt?: string | null;
  status?: string | null;
  confidence?: number | null;
}

export interface LineageOverviewDto {
  lineage: LineageOverviewItemDto[];
  total: number;
}

export interface TableDetailDto {
  table: TableInfoDto;
  columns: ColumnDto[];
  inEdges: RelationEdgeDto[];
  outEdges: RelationEdgeDto[];
  validationIssues: ValidationIssueDto[];
  recentChanges: ChangeRecordDto[];
  /** 列名 -> 直接上下游血缘（一跳）。只有有血缘的列才出现在这里。 */
  lineage?: Record<string, ColumnLineageDto>;
}

/** GET /api/workspace/completeness：图谱自身的完整性，不碰用户业务数据。 */
export interface WorkspaceCompletenessDto {
  tables: {
    total: number;
    withComment: number;
    withBusinessName: number;
  };
  columns: {
    total: number;
    withComment: number;
    withBusinessName: number;
    withSemanticType: number;
    withValueHints: number;
  };
  backlog: {
    candidateTables: number;
    candidateRelations: number;
    candidateTerms: number;
    ignoredTables: number;
    ignoredRelations: number;
    ignoredTerms: number;
    /** key 是 ValidationSeverity 原值：error / warning / info。 */
    openIssuesBySeverity: Record<string, number>;
    ignoredIssues: number;
    /** 有语义内容但没人确认过的字段数——字段级没有候选态，闸门是 verified 位 */
    unverifiedColumnSemantics: number;
  };
  relations: {
    totalTables: number;
    isolatedTables: number;
    fkOnlyTables: number;
  };
}

// ── Graph ──

export interface GraphNodeDto {
  id: string;
  kind: string;
  label: string;
  schema: string;
  description: string;
  relationCount: number;
  /** 字段数，与 relationCount 一起决定节点大小 */
  columnCount?: number;
  validationSeverity: string;
}

export interface FieldPairDto {
  from: string;
  to: string;
}

export interface GraphEdgeDto {
  id: string;
  source: string;
  target: string;
  type: string;
  confidence?: number;
  verified?: boolean;
  fieldPairs?: FieldPairDto[];
  relationIds?: string[];
}

export interface GraphViewStatsDto {
  totalNodes: number;
  totalEdges: number;
  returnedNodes: number;
  returnedEdges: number;
}

export interface GraphViewDto {
  revision: number;
  truncated: boolean;
  stats: GraphViewStatsDto;
  nodes: GraphNodeDto[];
  edges: GraphEdgeDto[];
}

// ── Search ──

export interface SearchHitDto {
  id: string;
  type: string;
  schema: string;
  table: string;
  column?: string;
  name: string;
  score: number;
  matchField?: string;
  /** 数据库注释镜像，只由导入写。 */
  comment?: string | null;
  /** 业务描述，人和 Agent 维护，与 comment 分字段展示。 */
  description?: string | null;
  businessName?: string | null;
  semanticType?: string | null;
  candidate?: boolean;
}

export interface SearchResponseDto {
  results: SearchHitDto[];
  total: number;
  indexStatus: string;
}

// ── Mutations ──

export interface MutationResultDto {
  newRevision: number;
  changeId: string;
  targetId?: string;
  /** 非空表示变更已生效、但还挂着一条待人裁决的图谱审批（评审页 · 图谱标签） */
  pendingApprovalId?: number | null;
}

/**
 * 表只能改业务名、业务描述和标签：comment 不在白名单里——它是数据库注释的镜像，
 * 只由导入写，注释错了去库里改、重新导入，不是在这里改一份跟库不一致的文本。
 */
export interface TablePatchDto {
  expectedRevision: number;
  patch: {
    businessName?: string;
    description?: string;
    tags?: string[];
  };
  reason?: string;
}

/** 字段只能改业务名、业务描述和语义类型：注释、类型、可空性由导入维护。 */
export interface ColumnPatchDto {
  expectedRevision: number;
  patch: {
    businessName?: string;
    description?: string;
    semanticType?: SemanticType | null;
    enumValues?: string[];
    format?: string | null;
    sampleValues?: string[];
  };
  reason?: string;
}

export interface RelationCreateDto {
  type: string;
  from: string;
  to: string;
  expectedRevision: number;
  cardinality?: RelationCardinality;
  joinExpression?: string;
  confidence?: number;
  verified?: boolean;
  reason?: string;
}

export interface RelationPatchDto {
  expectedRevision: number;
  patch: {
    cardinality?: RelationCardinality;
    joinExpression?: string;
    confidence?: number;
    verified?: boolean;
  };
  reason?: string;
}

// ── Validation ──

export interface ValidationResponseDto {
  issues: ValidationIssueDto[];
  errorCount: number;
  warningCount: number;
}

export interface ValidationIssuesResponseDto {
  issues: ValidationIssueDto[];
  total: number;
}

// ── Index ──

export interface IndexStatusDto {
  status: string;
  sourceRevision?: string;
  tableCount?: number;
  columnCount?: number;
  builtAt?: string;
}

export interface IndexRebuildDto {
  status: string;
  tableCount: number;
  columnCount: number;
  termCount: number;
  sourceRevision: string;
}

// ── Error ──

export interface ApiErrorBody {
  code: string;
  message: string;
  details?: unknown;
}

// ── Request params ──

export interface GraphParams {
  schema?: string;
  table?: string;
  depth?: number;
  relationType?: string;
  includeIsolated?: boolean;
  maxNodes?: number;
  maxEdges?: number;
}

export interface SearchParams {
  q: string;
  type?: string;
  limit?: number;
}
