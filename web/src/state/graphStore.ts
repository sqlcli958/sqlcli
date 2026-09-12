import { create } from 'zustand';

/**
 * 上次选中的 schema，**按数据源分开存**。
 *
 * <p>图谱默认铺全库，而人几乎总是只关心其中一个 schema——每次进来重新点一遍
 * 是纯重复劳动。只存这一项：选中的表、缩放这些是「这一次看到哪了」，
 * 跨会话恢复反而让人困惑。
 *
 * <p><b>key 必须带 alias。</b>原来是一个全局 key，切数据源时筛选跟着串过去：
 * `erp_plush_test` 的 schema 叫 `erp_plush_test`，`erp_plush_prod` 的叫 `erp-plush`，
 * 于是在 test 上勾过之后切到 prod，页面发的是 `?schema=erp_plush_test`，
 * 服务端老实返回 0 个节点——画布空白，而图谱本身好好的。
 */
const SCHEMA_FILTER_KEY = 'sqlcli.graph.schemaFilters';

function filterKey(alias: string | null): string {
  return `${SCHEMA_FILTER_KEY}:${alias ?? ''}`;
}

function loadSchemaFilters(alias: string | null): string[] {
  try {
    // 全局 key 的老值对除一个数据源之外的所有别名都是错的，读到即清掉
    localStorage.removeItem(SCHEMA_FILTER_KEY);
    const raw = localStorage.getItem(filterKey(alias));
    const parsed = raw ? JSON.parse(raw) : null;
    return Array.isArray(parsed) ? parsed.filter((one) => typeof one === 'string') : [];
  } catch {
    // 存储被禁用或内容坏了都不该让图谱打不开
    return [];
  }
}

function saveSchemaFilters(alias: string | null, schemas: string[]): void {
  try {
    localStorage.setItem(filterKey(alias), JSON.stringify(schemas));
  } catch {
    // 忽略：记不住选择只是少了个便利，不是错误
  }
}

export type LayoutMode = 'force' | 'circular' | 'none';

export interface GraphState {
  /** 当前数据源；schema 筛选按它分开存 */
  alias: string | null;
  selectedNodeId: string | null;
  selectedEdgeId: string | null;
  hoveredNodeId: string | null;
  schemaFilters: string[];
  layoutMode: LayoutMode;
  isLayoutRunning: boolean;
  zoom: number;
  workspaceRevision: number;
}

export interface GraphActions {
  selectNode: (id: string | null) => void;
  selectEdge: (id: string | null) => void;
  hoverNode: (id: string | null) => void;
  setSchemaFilters: (schemas: string[]) => void;
  toggleSchemaFilter: (schema: string) => void;
  setLayoutMode: (mode: LayoutMode) => void;
  setLayoutRunning: (running: boolean) => void;
  setZoom: (zoom: number) => void;
  setWorkspaceRevision: (rev: number) => void;
  resetSelection: () => void;
  /** 切数据源：清掉这一次的选择，并换成新数据源自己的 schema 筛选 */
  switchAlias: (alias: string | null) => void;
}

export type GraphStore = GraphState & GraphActions;

export const useGraphStore = create<GraphStore>((set) => ({
  alias: null,
  selectedNodeId: null,
  selectedEdgeId: null,
  hoveredNodeId: null,
  schemaFilters: [],
  layoutMode: 'none',
  isLayoutRunning: false,
  zoom: 1,
  workspaceRevision: 0,

  selectNode: (id) => set({ selectedNodeId: id, selectedEdgeId: null }),
  selectEdge: (id) => set({ selectedEdgeId: id, selectedNodeId: null }),
  hoverNode: (id) => set({ hoveredNodeId: id }),
  setSchemaFilters: (schemas) =>
    set((state) => {
      saveSchemaFilters(state.alias, schemas);
      return { schemaFilters: schemas };
    }),
  toggleSchemaFilter: (schema) =>
    set((state) => {
      const has = state.schemaFilters.includes(schema);
      const next = has
        ? state.schemaFilters.filter((s) => s !== schema)
        : [...state.schemaFilters, schema];
      saveSchemaFilters(state.alias, next);
      return { schemaFilters: next };
    }),
  setLayoutMode: (mode) => set({ layoutMode: mode }),
  setLayoutRunning: (running) => set({ isLayoutRunning: running }),
  setZoom: (zoom) => set({ zoom }),
  setWorkspaceRevision: (rev) => set({ workspaceRevision: rev }),
  resetSelection: () => set({ selectedNodeId: null, selectedEdgeId: null, hoveredNodeId: null }),
  switchAlias: (alias) => set({
    alias,
    schemaFilters: loadSchemaFilters(alias),
    selectedNodeId: null,
    selectedEdgeId: null,
    hoveredNodeId: null,
  }),
}));
