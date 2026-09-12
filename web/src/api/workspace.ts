import { get, patch, del } from './client';
import type {
  WorkspaceDto,
  WorkspaceStatsDto,
  TablesResponseDto,
  TableDetailDto,
  TablePatchDto,
  ColumnPatchDto,
  MutationResultDto,
  TermsResponseDto,
  LineageGraphDto,
  LineageOverviewDto,
  WorkspaceCompletenessDto,
} from '../types/api';

/** List business terms (read-only; maintained via CLI `add-term`). */
export async function getTerms(signal?: AbortSignal): Promise<TermsResponseDto> {
  return get<TermsResponseDto>('/terms', undefined, signal);
}

/**
 * 删除一条术语。术语的增改只有 CLI，**删是人的判断**——「这条是垃圾」只有人下得了结论，
 * 而 CLI 里根本没有删术语的命令。
 *
 * 还挂着映射的术语会被后端拒绝（返回原因）：审批 payload 是单对象补丁，
 * 在这里级联删边会在批准时丢掉，留下悬空关系。
 */
export async function deleteTerm(
  termId: string,
  expectedRevision: number,
  reason?: string,
  signal?: AbortSignal,
): Promise<MutationResultDto> {
  return del<MutationResultDto>(
    `/terms/${encodeURIComponent(termId)}`,
    { expectedRevision, reason },
    signal,
  );
}

/**
 * 本库全部血缘记录。回答「这库里哪些字段是推导来的」——
 * `/lineage` 那个要先选表和列，问不出这个。
 */
export async function getLineageOverview(signal?: AbortSignal): Promise<LineageOverviewDto> {
  return get<LineageOverviewDto>('/lineage/overview', undefined, signal);
}

/** 全库血缘大图：全部列、边、记录，外加每张表的总列数（画「其余 N 列」用）。布局在前端。 */
export async function getLineageNetworkGraph(signal?: AbortSignal): Promise<LineageGraphDto> {
  return get<LineageGraphDto>('/lineage/graph', undefined, signal);
}

/**
 * 删一条血缘。UI 只删不增不改：增改是 agent 读代码的活（CLI `add-lineage`），
 * 人在 UI 里看到错的能当场删掉就够了。manual 别名上返回 pendingApprovalId，并没有真删。
 */
export async function deleteLineage(
  lineageId: string,
  expectedRevision: number,
  reason?: string,
  signal?: AbortSignal,
): Promise<MutationResultDto> {
  return del<MutationResultDto>(
    `/lineage/${encodeURIComponent(lineageId)}`,
    { expectedRevision, reason },
    signal,
  );
}

/** Get workspace info */
export async function getWorkspace(signal?: AbortSignal): Promise<WorkspaceDto> {
  return get<WorkspaceDto>('/workspace', undefined, signal);
}

/** Get workspace stats */
export async function getWorkspaceStats(signal?: AbortSignal): Promise<WorkspaceStatsDto> {
  return get<WorkspaceStatsDto>('/workspace/stats', undefined, signal);
}

/**
 * 图谱自身的完整性：覆盖率 / 待办量 / 关系健康。数的是图谱这份元数据本身，不碰业务数据，
 * 跟 {@link getWorkspaceStats} 报的规模数字是两件事。
 */
export async function getWorkspaceCompleteness(signal?: AbortSignal): Promise<WorkspaceCompletenessDto> {
  return get<WorkspaceCompletenessDto>('/workspace/completeness', undefined, signal);
}

/** List tables with optional filters */
export async function getTables(
  schema?: string,
  offset?: number,
  limit?: number,
  q?: string,
  signal?: AbortSignal,
): Promise<TablesResponseDto> {
  return get<TablesResponseDto>('/tables', { schema, offset, limit, q }, signal);
}

/** Get a single table detail by qualified name (e.g. "schema.table") */
export async function getTableDetail(
  tableId: string,
  signal?: AbortSignal,
): Promise<TableDetailDto> {
  return get<TableDetailDto>(`/tables/${encodeURIComponent(tableId)}`, undefined, signal);
}

/** Patch table metadata */
export async function patchTable(
  tableId: string,
  body: TablePatchDto,
  signal?: AbortSignal,
): Promise<MutationResultDto> {
  return patch<MutationResultDto>(`/tables/${encodeURIComponent(tableId)}`, body, signal);
}

/** Patch column metadata */
export async function patchColumn(
  tableId: string,
  columnName: string,
  body: ColumnPatchDto,
  signal?: AbortSignal,
): Promise<MutationResultDto> {
  return patch<MutationResultDto>(
    `/tables/${encodeURIComponent(tableId)}/columns/${encodeURIComponent(columnName)}`,
    body,
    signal,
  );
}

/**
 * 实时行数。
 *
 * 走后端的 COUNT(*)，大表可能要等几秒——只在用户点刷新时调用，不做预取也不缓存。
 */
export async function getRowCount(
  schema: string,
  table: string,
  signal?: AbortSignal,
): Promise<{ schema: string; table: string; rowCount: number; elapsedMs: number }> {
  return get('/tables/row-count', { schema, table }, signal);
}

/** 单字段血缘的多跳视图，入口只有「某一个字段」，不提供全库血缘。 */
export async function getColumnLineageGraph(
  tableId: string,
  columnName: string,
  depth: number,
  signal?: AbortSignal,
): Promise<LineageGraphDto> {
  return get<LineageGraphDto>('/lineage', { table: tableId, column: columnName, depth }, signal);
}

export interface SchemaCatalogItemDto {
  name: string;
  /** 系统 schema，默认折叠不导入 */
  system: boolean;
  imported: boolean;
  tableCount: number;
  /** 图谱里有但数据库里已经没了 */
  missingInDatabase?: boolean;
}

/** 数据源里的全部 schema，标出哪些已进图谱（连数据库问，不是列图谱里已有的）。 */
export async function getSchemaCatalog(
  alias: string,
  signal?: AbortSignal,
): Promise<SchemaCatalogItemDto[]> {
  return get('/schemas/catalog', { alias }, signal);
}
