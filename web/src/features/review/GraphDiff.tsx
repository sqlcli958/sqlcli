import type { GraphChangePayloadDto } from '../../types/api';

/** 对象 id 里人能读的那一段：`column:demo:APP.ORDERS.USER_ID` → `APP.ORDERS.USER_ID`。 */
export function objectLabel(targetId?: string | null): string {
  if (!targetId) return '未知对象';
  const parts = targetId.split(':');
  return parts.length >= 3 ? parts.slice(2).join(':') : targetId;
}

/** id 前缀就是对象类型，直接翻译成中文——它不是 CLI 契约的一部分，只是个前缀。 */
const KIND_LABEL: Record<string, string> = {
  schema: 'schema',
  table: '表',
  column: '字段',
  relation: '关系',
  term: '业务术语',
  lineage: '血缘',
  metric: '指标',
  /** agent 提议的结构规则（policy:<alias>:<file>/<ruleId>），批准 = 写进规则集并启用 */
  policy: '规则',
};

function kindLabel(targetId?: string | null): string {
  const prefix = targetId?.split(':')[0] ?? '';
  return KIND_LABEL[prefix] ?? prefix;
}

/** 这些字段每次写入都会动，摆进 diff 只会淹没真正改了的那一两行。 */
const NOISE_FIELDS = new Set(['updatedAt', 'createdAt', 'updatedBy', 'createdBy', 'id']);

function render(value: unknown): string {
  if (value == null) return '—';
  if (typeof value === 'string') return value || '（空）';
  if (Array.isArray(value) || typeof value === 'object') return JSON.stringify(value);
  return String(value);
}

/**
 * 「批的到底是什么」：按字段列出 before → after。
 *
 * 只列真正变了的字段。整份对象摊开的话，一次改描述会摆出四十行里三十九行没变的，
 * 人得自己去找那一行——评审要的恰恰是那一行。
 *
 * 新增（before 为空）时全部字段都算变化，但仍然过滤掉时间戳这类记账字段。
 */
export function GraphDiff({ payload }: { payload: GraphChangePayloadDto }) {
  const before = payload.before ?? {};
  const after = payload.after ?? {};
  const keys = Array.from(new Set([...Object.keys(before), ...Object.keys(after)]))
    .filter((key) => !NOISE_FIELDS.has(key))
    .filter((key) => JSON.stringify(before[key]) !== JSON.stringify(after[key]));

  const removed = payload.after == null;
  const added = payload.before == null;

  return (
    <div className="review-panel">
      <section>
        <h3>
          {added ? '新增' : removed ? '删除' : '修改'}
          {kindLabel(payload.targetId)}
        </h3>
        <p className="review-hint" title={payload.targetId}>{objectLabel(payload.targetId)}</p>

        {keys.length === 0 ? (
          <p className="review-hint">没有字段级差异。</p>
        ) : (
          <dl className="review-facts review-diff">
            {keys.map((key) => (
              <div key={key} className="review-diff-row">
                <dt>{key}</dt>
                <dd>
                  <del>{render(before[key])}</del>
                  <span aria-hidden="true">→</span>
                  <ins>{render(after[key])}</ins>
                </dd>
              </div>
            ))}
          </dl>
        )}
      </section>
    </div>
  );
}
