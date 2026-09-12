/**
 * 规则表单的字段规格。
 *
 * 14 个类别的 statement 结构各不相同，与其写 14 份表单，不如把「每个类别有哪些字段」
 * 声明成数据，由 RuleForm 统一渲染。字段清单对应 PolicyEvaluator 里各 case 实际读取的键，
 * 改评估器时这里要一起改。
 */
import type { PolicyRuleDto, SemanticType } from '../../types/api';
import { SEMANTIC_TYPE_GROUPS } from '../../types/api';

export type FieldKind = 'text' | 'tags' | 'columns' | 'concepts' | 'typeGroups';

export interface FieldSpec {
  key: string;
  label: string;
  kind: FieldKind;
  placeholder?: string;
  help?: string;
}

/**
 * when 的作用范围。
 *
 * none = 该类别的评估代码根本不读 when（写了也不生效），表单直接不显示。
 * table = 只按表筛选；column = 表和字段都能筛。
 */
export type WhenScope = 'none' | 'table' | 'column';

/** design = 只读图谱事实；sql = 需要 SQL 或变更集输入，只在 migration lint 生效。 */
export type RulePhase = 'design' | 'sql';

export interface CategorySpec {
  label: string;
  phase: RulePhase;
  whenScope: WhenScope;
  statement: FieldSpec[];
  defaultStatement: Record<string, unknown>;
  defaultRemediation: string;
  /** whenScope 为 none 时解释原因 */
  whenNote?: string;
}

export const CATEGORIES: Record<string, CategorySpec> = {
  naming_convention: {
    label: '命名规范',
    phase: 'design',
    whenScope: 'column',
    statement: [
      { key: 'tableNameRegex', label: '表名正则', kind: 'text', placeholder: '^[a-z][a-z0-9_]*$' },
      { key: 'columnNameRegex', label: '字段名正则', kind: 'text', placeholder: '^[a-z][a-z0-9_]*$' },
      { key: 'indexNameRegex', label: '索引名正则', kind: 'text', placeholder: '^(pk|uk|idx)_[a-z0-9_]+$' },
    ],
    defaultStatement: { tableNameRegex: '^[a-z][a-z0-9_]*$', columnNameRegex: '^[a-z][a-z0-9_]*$' },
    defaultRemediation: '使用小写 snake_case 名称',
  },
  required_business_columns: {
    label: '必备字段',
    phase: 'design',
    whenScope: 'table',
    statement: [{ key: 'columns', label: '必备字段', kind: 'columns' }],
    defaultStatement: {
      columns: [
        { name: 'created_at', type: 'datetime', nullable: false, comment: '创建时间' },
        { name: 'updated_at', type: 'datetime', nullable: false, comment: '更新时间' },
      ],
    },
    defaultRemediation: '补齐必备字段',
  },
  primary_key_required: {
    label: '必须有主键',
    phase: 'design',
    whenScope: 'table',
    statement: [],
    defaultStatement: {},
    defaultRemediation: '为业务表增加稳定主键',
  },
  money_type_decimal: {
    label: '字段类型白名单',
    phase: 'design',
    whenScope: 'column',
    statement: [
      {
        key: 'allowedTypes',
        label: '允许的类型',
        kind: 'tags',
        help: '留空时默认 decimal / numeric / number',
      },
    ],
    defaultStatement: { allowedTypes: ['decimal', 'numeric'] },
    defaultRemediation: '将字段改为允许的类型',
  },
  sensitive_column_policy: {
    label: '敏感字段策略',
    phase: 'design',
    whenScope: 'column',
    statement: [
      {
        key: 'policyAttribute',
        label: '策略属性名',
        kind: 'text',
        placeholder: 'sensitivePolicy',
        help: '该属性目前没有写入入口，启用后无法修复，建议先设为仅提示',
      },
    ],
    defaultStatement: { policyAttribute: 'sensitivePolicy' },
    defaultRemediation: '标注语义类型并声明保护策略',
  },
  controlled_vocabulary: {
    label: '标准词表',
    phase: 'design',
    whenScope: 'column',
    statement: [{ key: 'concepts', label: '标准名与同义词', kind: 'concepts' }],
    defaultStatement: { concepts: [{ standard: 'phone', aliases: ['tel', 'mobile', 'telephone'] }] },
    defaultRemediation: '改用标准字段名',
  },
  business_unique_index: {
    label: '业务唯一键',
    phase: 'design',
    whenScope: 'column',
    statement: [
      {
        key: 'columns',
        label: '组合唯一键',
        kind: 'tags',
        help: '留空时改按上面的字段条件逐列检查单列唯一索引',
      },
    ],
    defaultStatement: {},
    defaultRemediation: '为业务唯一键建唯一索引',
  },
  status_field_dictionary: {
    label: '状态字段字典',
    phase: 'design',
    whenScope: 'column',
    statement: [
      {
        key: 'dictionaryTablePattern',
        label: '字典表名匹配',
        kind: 'text',
        placeholder: '%dict%',
        help: 'SQL LIKE 写法。字段有一条出边指向名字匹配的表，就算说清楚了取值来源',
      },
      { key: 'descriptionAttribute', label: '说明属性名', kind: 'text', placeholder: 'dictionaryDescription' },
    ],
    defaultStatement: { dictionaryTablePattern: '%dict%' },
    defaultRemediation: '补充枚举取值，或建一条指向字典表的关系',
  },
  relation_column_index: {
    label: '关联字段索引',
    phase: 'design',
    whenScope: 'table',
    statement: [],
    defaultStatement: {},
    defaultRemediation: '为关联字段补索引',
  },
  join_column_type_match: {
    label: '关联字段类型一致',
    phase: 'design',
    whenScope: 'none',
    whenNote: '该类别作用于图谱里的全部关系，不支持按表或字段筛选。',
    statement: [{ key: 'compatibleTypeGroups', label: '兼容类型组', kind: 'typeGroups' }],
    defaultStatement: { compatibleTypeGroups: [['int', 'bigint', 'smallint']] },
    defaultRemediation: '统一关联两端的字段类型',
  },
  dangerous_dml_guard: {
    label: '危险 DML 保护',
    phase: 'sql',
    whenScope: 'none',
    whenNote: '该类别作用于送进来的 SQL 语句，不支持按表或字段筛选。',
    statement: [],
    defaultStatement: {},
    defaultRemediation: '补 WHERE 条件与验证 SQL',
  },
  migration_safety_check: {
    label: '迁移安全检查',
    phase: 'sql',
    whenScope: 'none',
    whenNote: '该类别作用于迁移的变更集，不支持按表或字段筛选。',
    statement: [],
    defaultStatement: {},
    defaultRemediation: '补充回滚方案与前后校验 SQL',
  },
  dialect_sql_pattern: {
    label: 'SQL 写法提示',
    phase: 'sql',
    whenScope: 'none',
    whenNote: '该类别作用于送进来的 SQL 文本，不支持按表或字段筛选。',
    statement: [
      { key: 'matchRegex', label: '命中正则', kind: 'text', placeholder: '(?i)FETCH\\s+FIRST' },
      { key: 'topic', label: '主题', kind: 'text', placeholder: 'pagination' },
      { key: 'message', label: '提示文案', kind: 'text' },
    ],
    defaultStatement: {},
    defaultRemediation: '改用本数据库支持的写法',
  },
  fact_table_grain_required: {
    label: '事实表须声明粒度',
    phase: 'design',
    whenScope: 'table',
    statement: [],
    defaultStatement: {},
    defaultRemediation: "用 sql-cli <alias> schema edit --grain '一行代表…' 声明表粒度",
  },
};

export const CATEGORY_KEYS = Object.keys(CATEGORIES);

export const TABLE_TYPES: { value: string; label: string }[] = [
  { value: 'base_table', label: '普通表' },
  { value: 'view', label: '视图' },
  { value: 'materialized_view', label: '物化视图' },
  { value: 'temporary', label: '临时表' },
  { value: 'system_table', label: '系统表' },
  { value: 'external', label: '外部表' },
  { value: 'unknown', label: '未知' },
];

export const SEMANTIC_TYPES: { value: SemanticType; label: string }[] =
  SEMANTIC_TYPE_GROUPS.flatMap((group) => group.options);

export const SEVERITIES: { value: PolicyRuleDto['severity']; label: string }[] = [
  { value: 'info', label: 'info 提示' },
  { value: 'warning', label: 'warning 警告' },
  { value: 'error', label: 'error 错误' },
  { value: 'critical', label: 'critical 严重' },
];

/**
 * 作用域：这条规则跑在哪里。
 *
 * 命名类默认 change —— 遗留库上的表名不会为了规则去改，实测一次全量跑出 1215 条命名违规、
 * 豁免 0 条，之后没人再跑。留空由服务端按类别兜底。
 */
export const SCOPES: { value: 'change' | 'all'; label: string }[] = [
  { value: 'change', label: 'change 只查这次 DDL 新增/改动的表' },
  { value: 'all', label: 'all 连存量图谱一起查' },
];

/** blocking 与 required 在评估器里行为一致，标注出来免得以为有三档强度。 */
export const ENFORCEMENTS: { value: PolicyRuleDto['enforcement']; label: string }[] = [
  { value: 'advisory', label: 'advisory 仅提示，不影响检查结果' },
  { value: 'required', label: 'required 检查不通过' },
  { value: 'blocking', label: 'blocking 检查不通过（与 required 一致）' },
];

/** when 里表级和字段级各有哪些条件，供表单按 whenScope 取用。 */
export const TABLE_CONDITIONS: FieldSpec[] = [
  { key: 'tableNameRegex', label: '表名匹配', kind: 'text', placeholder: '(?i)^erp_' },
  { key: 'tableTagsAny', label: '表标签任一', kind: 'tags' },
];

export const COLUMN_CONDITIONS: FieldSpec[] = [
  { key: 'columnNameRegex', label: '字段名匹配', kind: 'text', placeholder: '(?i)(amount|price)' },
  { key: 'columnTagsAny', label: '字段标签任一', kind: 'tags' },
];

/** 规则 ID 由类别生成，同类别多条时加序号 —— 人不需要发明机器键。 */
export function nextRuleId(category: string, existing: PolicyRuleDto[]): string {
  const used = new Set(existing.map((rule) => rule.id));
  if (!used.has(category)) return category;
  for (let i = 2; ; i += 1) {
    if (!used.has(`${category}-${i}`)) return `${category}-${i}`;
  }
}

export function newRule(category: string, existing: PolicyRuleDto[]): PolicyRuleDto {
  const spec = CATEGORIES[category];
  return {
    id: nextRuleId(category, existing),
    title: spec.label,
    category,
    when: {},
    statement: structuredClone(spec.defaultStatement),
    severity: spec.phase === 'sql' ? 'error' : 'warning',
    enforcement: 'advisory',
    remediation: spec.defaultRemediation,
    tags: [],
    sources: [],
  };
}
