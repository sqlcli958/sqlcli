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

  return <div className="rule-form">
    <div className="rf-grid">
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
    </div>

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
  </div>;
}
