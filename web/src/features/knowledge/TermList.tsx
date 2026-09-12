import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useSearchParams } from 'react-router-dom';
import { deleteTerm, getTerms } from '../../api/workspace';
import { onMutationSuccess } from '../../api/mutationHelpers';
import { useSessionStore } from '../../state/sessionStore';
import { ActionIcon } from '../../ui/ActionIcon';
import { StatusDot } from '../../ui/StatusDot';
import { Button } from '../../ui/Button';
import { Dialog } from '../../ui/Dialog';
import type { TermDto } from '../../types/api';

/**
 * 术语子视图：列表 + 同义词 + 映射目标，**并且能筛表目录**。
 *
 * 术语的**增改**只有 CLI `add-term`（拍板过：Agent 批量补术语，人偶尔核对，
 * 不值得为这个建一整套 UI 表单）；**删在这里**——「这条是垃圾」是人的判断，
 * 而 CLI 里根本没有删术语的命令，UI 不给入口就等于删不掉。
 *
 * 有入口表的术语是**场景**，点它就把表目录筛到这个场景的表上（`?term=` 进 URL，
 * 可分享、刷新不丢）。之前这里只显示「映射：a、b、c」三个点不动的字符串——
 * 看完还得自己回表目录一张张找，等于写入口做完了、消费侧没做。
 */
export function TermList({ onPick }: { onPick?: () => void }) {
  const [searchParams, setSearchParams] = useSearchParams();
  const activeTermId = searchParams.get('term');
  const [confirming, setConfirming] = useState<string | null>(null);
  const [detail, setDetail] = useState<TermDto | null>(null);
  const queryClient = useQueryClient();
  const workspaceRevision = useSessionStore((s) => s.revision);
  const { data, isLoading, isError } = useQuery({
    queryKey: ['terms', workspaceRevision],
    queryFn: ({ signal }) => getTerms(signal),
    staleTime: 30_000,
  });

  if (isLoading) return <p className="term-list-hint">加载中…</p>;
  if (isError) return <p className="term-list-hint">术语加载失败</p>;
  const terms = data?.terms ?? [];
  if (terms.length === 0) {
    return (
      <p className="term-list-hint">
        还没有术语。用 <code>sql-cli &lt;alias&gt; schema add-term</code> 添加。
      </p>
    );
  }

  return (
    <>
    <ul className="term-list">
      {terms.map((term) => (
        <li key={term.id} className="term-list-item" data-active={term.id === activeTermId || undefined}>
          <div className="term-list-head">
            {term.scenarioTables.length > 0 ? (
              <button
                type="button"
                className="term-list-name is-scenario"
                title={`筛出这个场景的 ${term.scenarioTables.length} 张表`}
                onClick={() => {
                  setSearchParams((params) => {
                    if (term.id === activeTermId) params.delete('term');
                    else params.set('term', term.id);
                    return params;
                  });
                  onPick?.();
                }}
              >
                {term.displayName || term.name}
              </button>
            ) : (
              <span className="term-list-name">{term.displayName || term.name}</span>
            )}
            {term.scenarioTables.length > 0 && (
              <span className="term-list-badge">{term.scenarioTables.length} 表</span>
            )}
            {/* 状态用灯不用文字：每行都写一遍 candidate 是把同一个信息复述 N 遍，
                而三态一眼可辨（CLAUDE.md「状态色」「图标优先」） */}
            {term.status && (
              <StatusDot
                tone={term.status === 'verified' ? 'ok' : 'warn'}
                label={term.status === 'verified' ? '已确认' : '候选，未经人确认'}
              />
            )}
            <span className="term-list-actions">
              <ActionIcon
                action="more"
                label="术语详情"
                onClick={() => setDetail(term)}
              />
              <ActionIcon
                action="delete"
                label="删除这条术语"
                onClick={() => setConfirming(term.id)}
              />
            </span>
          </div>
          {confirming === term.id && (
            <TermDeleteConfirm
              termId={term.id}
              mappingCount={term.mappedTargets.length}
              onCancel={() => setConfirming(null)}
              onDone={() => {
                setConfirming(null);
                queryClient.invalidateQueries({ queryKey: ['terms'] });
                // 正被筛的术语被删了，筛选条件要跟着撤，否则表目录空着而没人知道为什么
                if (term.id === activeTermId) {
                  setSearchParams((params) => {
                    params.delete('term');
                    return params;
                  });
                }
              }}
            />
          )}
          {/* 只留一行说明，其余全进详情弹窗。同义词 / 映射 / 过滤四段铺开之后
              一条术语占七八行，二十条就翻不动了——列表要能扫，细节点开看 */}
          {term.description && (
            <p className="term-list-line term-list-desc" title={term.description}>
              {term.description}
            </p>
          )}
        </li>
      ))}
    </ul>
    {detail && <TermDetailDialog term={detail} onClose={() => setDetail(null)} />}
    </>
  );
}

/**
 * 术语详情弹窗：同义词、排除词、映射、过滤、场景表——列表里放不下的全在这。
 *
 * 用 `<Dialog>` 而不是行内展开：这些字段一条术语能有二十几个值（映射尤其），
 * 在两百像素宽的侧栏里展开等于把列表挤没。
 */
function TermDetailDialog({ term, onClose }: { term: TermDto; onClose: () => void }) {
  const rows: [string, React.ReactNode][] = [];
  if (term.status) rows.push(['状态', term.status === 'verified' ? '已确认' : '候选，未经人确认']);
  if (term.description) rows.push(['说明', term.description]);
  if (term.aliases.length > 0) rows.push(['同义词', term.aliases.join(' · ')]);
  if (term.negativeAliases.length > 0) rows.push(['排除词', term.negativeAliases.join(' · ')]);
  // 映射显示短名，完整 id 放 title——裸 id 又长又会把弹窗撑宽
  if (term.mappedTargets.length > 0) {
    rows.push([
      '映射',
      <span className="term-detail-mono" title={term.mappedTargets.join('、')}>
        {term.mappedTargets.map(shortRef).join('、')}
      </span>,
    ]);
  }
  if (term.filters.length > 0) {
    rows.push(['过滤', <span className="term-detail-mono">{term.filters.join(' AND ')}</span>]);
  }
  if (term.scenarioTables.length > 0) {
    rows.push([
      `场景表（${term.scenarioTables.length}）`,
      <span className="term-detail-mono">{term.scenarioTables.join('、')}</span>,
    ]);
  }

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()} title={term.displayName || term.name}>
      <dl className="term-detail">
        {rows.map(([label, value]) => (
          <div key={label} className="term-detail-row">
            <dt>{label}</dt>
            <dd>{value}</dd>
          </div>
        ))}
      </dl>
    </Dialog>
  );
}

/**
 * 图谱对象 id → 人读得懂的短名。
 *
 * `column:erp_plush_test:erp_plush_test.erp_prop_report_score.satisfaction`
 * → `erp_prop_report_score.satisfaction`。侧栏只有两百来像素宽，
 * 裸 id 占三行还会被截断，而被截掉的恰恰是表名和列名——信息量全在末尾。
 */
function shortRef(id: string): string {
  const qualified = id.split(':').slice(2).join(':') || id;
  const parts = qualified.split('.');
  // 列是 schema.table.column，表是 schema.table：两种都只留最后两段
  return parts.length > 2 ? parts.slice(-2).join('.') : parts[parts.length - 1];
}

/**
 * 删除确认条。**单独一行铺开，不塞进右上角那个图标槽**——
 * 侧栏只有两百来像素宽，两个按钮加一句错误信息挤进去必然溢出。
 *
 * 两步确认不是仪式感：术语占检索的最高权重档，删错了搜索会静默变差，
 * 而列表里每条长得都差不多，误点很容易。
 */
function TermDeleteConfirm({
  termId,
  mappingCount,
  onCancel,
  onDone,
}: {
  termId: string;
  /** 会被一起删掉的映射条数；后端按 from/to 两头收，这里只是先说一声 */
  mappingCount: number;
  onCancel: () => void;
  onDone: () => void;
}) {
  const revision = useSessionStore((s) => s.revision);
  const remove = useMutation({
    mutationFn: () => deleteTerm(termId, revision, '在图谱页删除'),
    onSuccess: (result) => {
      // manual 别名上术语并没有真被删，onMutationSuccess 会把待审批挂到顶栏提示上——
      // 少了这一步，人关掉页面就以为删成功了，而图谱纹丝不动
      onMutationSuccess(result);
      onDone();
    },
  });

  return (
    <div className="term-list-confirm">
      {/* 映射跟着术语一起删（留一条就是悬空引用），但要先说出来——
          比让人自己去关系列表里找出来删掉强，也比默默多删几条强 */}
      {mappingCount > 0 && (
        <p className="term-list-line">连同 {mappingCount} 条映射一起删除</p>
      )}
      <Button variant="danger" size="sm" disabled={remove.isPending} onClick={() => remove.mutate()}>
        {remove.isPending ? '删除中…' : '确认删除'}
      </Button>
      <Button variant="ghost" size="sm" onClick={onCancel}>取消</Button>
      {remove.isError && (
        <p className="term-list-error" role="alert">{remove.error.message}</p>
      )}
    </div>
  );
}
