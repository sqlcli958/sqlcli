/**
 * 单条规则的编辑表单。
 *
 * 字段由 ruleSchema 的类别规格驱动，不为每个类别写一份表单。
 * 写回 statement / when 时一律 spread 原值，只覆盖认识的键 —— 文件里可能有
 * 表单还没覆盖的字段（如 length / precision），编辑一次不能把它们抹掉。
 */
import type { PolicyRuleDto } from '../../types/api';
import { ActionIcon } from '../../ui/ActionIcon';
import { Select } from '../../ui/Select';
import {
  CATEGORIES,
  COLUMN_CONDITIONS,
  ENFORCEMENTS,
  type FieldSpec,
  SCOPES,
  SEMANTIC_TYPES,
  SEVERITIES,
  TABLE_CONDITIONS,
  TABLE_TYPES,
} from './ruleSchema';

type Dict = Record<string, unknown>;

function strings(value: unknown): string[] {
  return Array.isArray(value) ? value.map((item) => String(item)) : [];
}

/** 逗号分隔的标签输入。空值从对象里删掉而不是留空数组，免得 YAML 里堆一堆 []。 */
function TagsField({ label, help, value, onChange }: {
  label: string; help?: string; value: string[]; onChange: (next: string[]) => void;
}) {
  return <label className="rf-field">
    <span>{label}{help && <small title={help}> ?</small>}</span>
    <input
      value={value.join(', ')}
      placeholder="逗号分隔"
      onChange={(event) => onChange(event.target.value.split(',').map((item) => item.trim()).filter(Boolean))}
    />
    {help && <small className="rf-help">{help}</small>}
  </label>;
}

function TextField({ spec, value, onChange }: {
  spec: FieldSpec; value: string; onChange: (next: string) => void;
}) {
  return <label className="rf-field">
    <span>{spec.label}</span>
    <input value={value} placeholder={spec.placeholder} onChange={(event) => onChange(event.target.value)} />
    {spec.help && <small className="rf-help">{spec.help}</small>}
  </label>;
}

function CheckGroup({ label, options, value, onChange }: {
  label: string;
  options: { value: string; label: string }[];
  value: string[];
  onChange: (next: string[]) => void;
}) {
  return <div className="rf-field rf-wide">
    <span>{label}</span>
    <div className="rf-checks">
      {options.map((option) => <label key={option.value}>
        <input
          type="checkbox"
          checked={value.includes(option.value)}
          onChange={(event) => onChange(event.target.checked
            ? [...value, option.value]
            : value.filter((item) => item !== option.value))}
        />
        {option.label}
      </label>)}
    </div>
  </div>;
}

/**
 * required_business_columns 的字段清单：一行一个字段，读法跟 DDL 一样。
 *
 * 原来每个字段是七个带标签的输入框摊成一块，三个字段就是一屏；现在是一张表，
 * 一眼能看完「要哪几列、什么类型、可不可空」。空单元格 = 不检查该项。
 * NOT NULL 是勾选框：没人会「要求一列必须可空」，去掉那个三态下拉。
 * 没有「可接受的别名」：字段名必须完全一致，规则要的就是新表统一叫这个名字。
 * onUpdate / length / precision / scale 不进表格，YAML 里写了照常保留、照常生效。
 */
export function ColumnsEditor({ value, onChange }: { value: Dict[]; onChange: (next: Dict[]) => void }) {
  const set = (index: number, key: string, next: unknown) => onChange(value.map((item, i) => {
    if (i !== index) return item;
    const row = { ...item };
    if (next === '' || next === undefined || (Array.isArray(next) && next.length === 0)) delete row[key];
    else row[key] = next;
    return row;
  }));
  const text = (index: number, key: string, placeholder?: string) => <input
    value={String(value[index][key] ?? '')}
    placeholder={placeholder}
    aria-label={`第 ${index + 1} 行 ${key}`}
    onChange={(e) => set(index, key, e.target.value)}
  />;
  return <table className="rf-columns">
    <thead><tr>
      <th>字段名</th><th>类型</th><th title="勾选 = 必须 NOT NULL；不勾 = 不检查">NOT NULL</th>
      <th>默认值</th><th>注释必须是</th><th />
    </tr></thead>
    <tbody>
      {value.map((column, index) => <tr key={index}>
        <td>{text(index, 'name', 'created_at')}</td>
        <td>{text(index, 'type', 'datetime')}</td>
        <td className="rf-columns-check"><input
          type="checkbox"
          checked={column.nullable === false}
          aria-label={`第 ${index + 1} 行 NOT NULL`}
          onChange={(e) => set(index, 'nullable', e.target.checked ? false : undefined)}
        /></td>
        <td>{text(index, 'defaultValue', 'CURRENT_TIMESTAMP')}</td>
        <td>{text(index, 'comment', '创建时间')}</td>
        <td><ActionIcon action="delete" label={`删除第 ${index + 1} 行`}
          onClick={() => onChange(value.filter((_, i) => i !== index))} /></td>
      </tr>)}
    </tbody>
    <tfoot><tr><td colSpan={6}>
      <ActionIcon action="add" label="添加必备字段" onClick={() => onChange([...value, { name: '' }])} />
    </td></tr></tfoot>
  </table>;
}

function ConceptsEditor({ value, onChange }: { value: Dict[]; onChange: (next: Dict[]) => void }) {
  return <div className="rf-list">
    {value.map((concept, index) => <div className="rf-row" key={index}>
      <div className="rf-grid">
        <label className="rf-field"><span>标准名</span>
          <input value={String(concept.standard ?? '')} placeholder="phone"
            onChange={(e) => onChange(value.map((item, i) =>
              i === index ? { ...item, standard: e.target.value } : item))} /></label>
        <label className="rf-field rf-wide"><span>禁止使用的同义词</span>
          <input value={strings(concept.aliases).join(', ')} placeholder="tel, mobile, telephone"
            onChange={(e) => onChange(value.map((item, i) => i === index
              ? { ...item, aliases: e.target.value.split(',').map((v) => v.trim()).filter(Boolean) }
              : item))} /></label>
      </div>
      <ActionIcon action="delete" label="删除该概念"
        onClick={() => onChange(value.filter((_, i) => i !== index))} />
    </div>)}
    <ActionIcon action="add" label="添加概念"
      onClick={() => onChange([...value, { standard: '', aliases: [] }])} />
  </div>;
}

function TypeGroupsEditor({ value, onChange }: { value: string[][]; onChange: (next: string[][]) => void }) {
  return <div className="rf-list">
    {value.map((group, index) => <div className="rf-row" key={index}>
      <label className="rf-field rf-wide"><span>第 {index + 1} 组（组内类型视为兼容）</span>
        <input value={group.join(', ')} placeholder="int, bigint, smallint"
          onChange={(e) => onChange(value.map((item, i) => i === index
            ? e.target.value.split(',').map((v) => v.trim()).filter(Boolean) : item))} /></label>
      <ActionIcon action="delete" label="删除该组"
        onClick={() => onChange(value.filter((_, i) => i !== index))} />
    </div>)}
    <ActionIcon action="add" label="添加兼容组" onClick={() => onChange([...value, []])} />
  </div>;
}

export function RuleForm({ rule, onChange }: {
  rule: PolicyRuleDto;
  onChange: (next: PolicyRuleDto) => void;
}) {
  const spec = CATEGORIES[rule.category];
  const businessKey = rule.category === 'business_unique_index';
  const fixedKey = strings(rule.statement.columns).length > 0;
  const candidateKey = Boolean(rule.when.columnNameRegex)
    || strings(rule.when.columnTagsAny).length > 0 || strings(rule.when.semanticTypeAny).length > 0;
  const setStatement = (key: string, next: unknown) => {
    const statement: Dict = { ...rule.statement };
    if (next === '' || (Array.isArray(next) && next.length === 0)) delete statement[key];
    else statement[key] = next;
    onChange({ ...rule, statement });
  };
  const setWhen = (key: string, next: unknown) => {
    const when: Dict = { ...rule.when };
    if (next === '' || (Array.isArray(next) && next.length === 0)) delete when[key];
    else when[key] = next;
    onChange({ ...rule, when });
  };

  function statementField(field: FieldSpec) {
    const value = rule.statement[field.key];
    switch (field.kind) {
      case 'text':
        return <TextField key={field.key} spec={field} value={String(value ?? '')}
          onChange={(next) => setStatement(field.key, next)} />;
      case 'tags':
        return <TagsField key={field.key} label={field.label} help={field.help} value={strings(value)}
          onChange={(next) => setStatement(field.key, next)} />;
      case 'columns':
        return <div className="rf-block" key={field.key}><span className="rf-block-title">{field.label}</span>
          <ColumnsEditor value={Array.isArray(value) ? (value as Dict[]) : []}
            onChange={(next) => setStatement(field.key, next)} /></div>;
      case 'concepts':
        return <div className="rf-block" key={field.key}><span className="rf-block-title">{field.label}</span>
          <ConceptsEditor value={Array.isArray(value) ? (value as Dict[]) : []}
            onChange={(next) => setStatement(field.key, next)} /></div>;
      case 'typeGroups':
        return <div className="rf-block" key={field.key}><span className="rf-block-title">{field.label}</span>
          <TypeGroupsEditor value={Array.isArray(value) ? (value as string[][]) : []}
            onChange={(next) => setStatement(field.key, next)} /></div>;
    }
  }

  const conditions = spec.whenScope === 'column'
    ? [...TABLE_CONDITIONS, ...COLUMN_CONDITIONS]
    : spec.whenScope === 'table' ? TABLE_CONDITIONS : [];

  const settings = <div className="rf-grid">
      <label className="rf-field"><span>规则名称</span>
        <input value={rule.title} onChange={(event) => onChange({ ...rule, title: event.target.value })} /></label>
      <label className="rf-field"><span>规则 ID<small className="rf-help"> 按类别自动生成</small></span>
        <input value={rule.id} readOnly className="rf-readonly" /></label>
      <label className="rf-field"><span>严重级别<small className="rf-help"> 只影响展示</small></span>
        <Select value={rule.severity}
          onChange={(event) => onChange({ ...rule, severity: event.target.value as PolicyRuleDto['severity'] })}>
          {SEVERITIES.map((item) => <option key={item.value} value={item.value}>{item.label}</option>)}
        </Select></label>
      <label className="rf-field"><span>约束级别<small className="rf-help"> 决定检查是否通过</small></span>
        <Select value={rule.enforcement}
          onChange={(event) => onChange({ ...rule, enforcement: event.target.value as PolicyRuleDto['enforcement'] })}>
          {ENFORCEMENTS.map((item) => <option key={item.value} value={item.value}>{item.label}</option>)}
        </Select></label>
      {spec.phase === 'design' && <label className="rf-field">
        <span>作用域<small className="rf-help"> 决定要不要查存量图谱</small></span>
        <Select
          value={rule.scope ?? (rule.category === 'naming_convention' ? 'change' : 'all')}
          onChange={(event) => onChange({ ...rule, scope: event.target.value as PolicyRuleDto['scope'] })}
        >
          {SCOPES.map((item) => <option key={item.value} value={item.value}>{item.label}</option>)}
        </Select></label>}
      <label className="rf-field rf-wide"><span>修复建议<small className="rf-help"> 会随违规一起输出给使用者</small></span>
        <input value={rule.remediation ?? ''}
          onChange={(event) => onChange({ ...rule, remediation: event.target.value })} /></label>
    </div>;

  return <div className="rule-form">
    {businessKey ? <>
      <p className="rf-note">指定不能重复的业务字段，例如订单号。这与“每张表必须有主键”是两条独立规则。</p>
      <div className="rf-block rf-grid">
        <TextField spec={{ key: 'tableNameRegex', label: '检查哪些表（可选）', kind: 'text',
          placeholder: '^orders$', help: '按表名正则匹配；留空检查所有表。固定唯一键应限定到包含这些字段的表。' }}
          value={String(rule.when.tableNameRegex ?? '')} onChange={(next) => setWhen('tableNameRegex', next)} />
        <TagsField label="不能重复的字段" value={strings(rule.statement.columns)}
          help="一个字段表示单列唯一；多个字段用逗号分隔，表示组合唯一，例如 tenant_id, order_no。"
          onChange={(next) => setStatement('columns', next)} />
      </div>
      {!fixedKey && !candidateKey && <p className="rules-error" role="alert">请填写不能重复的字段，或展开下面的字段条件；两者都留空会导致检查无法完成。</p>}
      {fixedKey ? <p className="rf-note">将检查这些字段按填写顺序组成的唯一索引；不使用字段筛选条件。</p>
        : <details className="rf-block" open={candidateKey}>
          <summary>按字段条件检查（不填写固定唯一键时使用）</summary>
          <p className="rf-note">固定唯一键与字段条件至少填写一种。命中的每个字段分别要求单列唯一索引。</p>
          <div className="rf-grid">
            <TextField spec={{ key: 'columnNameRegex', label: '字段名匹配', kind: 'text', placeholder: '^order_no$' }}
              value={String(rule.when.columnNameRegex ?? '')} onChange={(next) => setWhen('columnNameRegex', next)} />
            <TagsField label="字段标签任一" value={strings(rule.when.columnTagsAny)}
              onChange={(next) => setWhen('columnTagsAny', next)} />
            <CheckGroup label="字段语义类型" options={SEMANTIC_TYPES}
              value={strings(rule.when.semanticTypeAny)} onChange={(next) => setWhen('semanticTypeAny', next)} />
          </div>
        </details>}
      <details className="rf-block">
        <summary>高级设置（规则信息、检查强度和表筛选）</summary>
        {settings}
        <div className="rf-grid rf-block">
          <CheckGroup label="表类型" options={TABLE_TYPES}
            value={strings(rule.when.tableTypeAny)} onChange={(next) => setWhen('tableTypeAny', next)} />
          <TagsField label="表标签任一" value={strings(rule.when.tableTagsAny)}
            onChange={(next) => setWhen('tableTagsAny', next)} />
        </div>
        <p className="rf-note">表类型和表标签会进一步限定检查范围；修改业务字段不会清除这些配置。</p>
      </details>
    </> : <>
    {settings}

    <div className="rf-block">
      <span className="rf-block-title">适用条件<small>留空表示不限</small></span>
      {conditions.length === 0
        ? <p className="rf-note">{spec.whenNote}</p>
        : <div className="rf-grid">
          {spec.whenScope !== 'none' && <CheckGroup label="表类型" options={TABLE_TYPES}
            value={strings(rule.when.tableTypeAny)} onChange={(next) => setWhen('tableTypeAny', next)} />}
          {conditions.map((field) => field.kind === 'tags'
            ? <TagsField key={field.key} label={field.label} value={strings(rule.when[field.key])}
              onChange={(next) => setWhen(field.key, next)} />
            : <TextField key={field.key} spec={field} value={String(rule.when[field.key] ?? '')}
              onChange={(next) => setWhen(field.key, next)} />)}
          {spec.whenScope === 'column' && <CheckGroup label="字段语义类型" options={SEMANTIC_TYPES}
            value={strings(rule.when.semanticTypeAny)} onChange={(next) => setWhen('semanticTypeAny', next)} />}
        </div>}
    </div>

    {spec.statement.length > 0 && <div className="rf-block">
      <span className="rf-block-title">检查配置</span>
      <div className="rf-grid">{spec.statement.filter((field) =>
        field.kind === 'text' || field.kind === 'tags').map(statementField)}</div>
      {spec.statement.filter((field) => field.kind !== 'text' && field.kind !== 'tags').map(statementField)}
    </div>}
    </>}
  </div>;
}
