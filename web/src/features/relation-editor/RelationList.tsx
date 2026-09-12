import { useState, useCallback } from 'react';
import { memo } from 'react';
import { Link } from 'react-router-dom';
import { deleteRelation } from '../../api/relations';
import { onMutationSuccess } from '../../api/mutationHelpers';
import { useDeleteConfirm } from '../../api/useDeleteConfirm';
import { useSessionStore } from '../../state/sessionStore';
import { navPath } from '../../app/navigation';
import { RelationTypeBadge } from './RelationTypeBadge';
import { RelationEditor } from './RelationEditor';
import type { MutationResultDto, RelationEdgeDto } from '../../types/api';
import { ActionIcon } from '../../ui/ActionIcon';
import { Button } from '../../ui/Button';

interface RelationListProps {
  title: string;
  relations: RelationEdgeDto[];
  direction: 'in' | 'out';
  revision: number;
  onRefresh: () => void;
}

/** 从 "column:alias:SCHEMA.TABLE.COL" 这类端点 ID 里取出可读的短名。 */
function endpointSummary(id: string): string {
  const parts = id.split(':');
  const last = parts[parts.length - 1] ?? id;
  const dots = last.split('.');
  if (dots.length >= 3) {
    return dots.slice(-2).join('.');
  }
  return last;
}

function isDeclared(relation: RelationEdgeDto): boolean {
  return relation.type === 'foreign_key';
}

function isEditable(relation: RelationEdgeDto): boolean {
  return !isDeclared(relation);
}

function isCandidate(relation: RelationEdgeDto): boolean {
  return relation.status === 'candidate';
}

function isIgnored(relation: RelationEdgeDto): boolean {
  return relation.status === 'ignored';
}

/** 属性面板里去掉拒绝原因（它已经有专门一行展示），剩下的才落进通用 JSON 块；没有剩余就不渲染整行。 */
function otherAttributes(relation: RelationEdgeDto): Record<string, unknown> | null {
  if (!relation.attributes) return null;
  const rest = Object.fromEntries(
    Object.entries(relation.attributes).filter(([key]) => key !== 'rejectionReason'),
  );
  return Object.keys(rest).length > 0 ? rest : null;
}

/** 图谱页的候选 / 已忽略分组都链到这里——评审动作只有一个入口。 */
function reviewPath(alias: string | null): string {
  return `${navPath('reviews', alias)}${alias ? '&' : '?'}tab=graph`;
}

/**
 * 关系列表。
 *
 * 候选（待评审）、正式、已忽略三类分开渲染：混在一起看不出哪些还等着放行、
 * 哪些已经生效、哪些是人工拒绝过不该再挖出来的。已忽略默认折叠：
 * 不需要引起注意，只需要能翻到。
 *
 * **这里只展示，不评审。** 发布 / 拒绝 / 撤销都搬去了评审页的图谱标签：
 * 清空候选队列要的是「这个数据源还剩哪些」，而这个组件一次只知道一张表的关系，
 * 把动作留在这里等于让人挨张表翻。CLAUDE.md 的「入口唯一」也是这个意思。
 */
export const RelationList = memo(function RelationList({
  title,
  relations,
  direction,
  revision,
  onRefresh,
}: RelationListProps) {
  const candidates = relations.filter(isCandidate);
  const ignored = relations.filter(isIgnored);
  const published = relations.filter((rel) => !isCandidate(rel) && !isIgnored(rel));

  return (
    <>
      {candidates.length > 0 && (
        <RelationGroup
          title={`${title} · 待评审`}
          relations={candidates}
          direction={direction}
          revision={revision}
          onRefresh={onRefresh}
          candidateGroup
        />
      )}
      {published.length > 0 && (
        <RelationGroup
          title={title}
          relations={published}
          direction={direction}
          revision={revision}
          onRefresh={onRefresh}
        />
      )}
      {ignored.length > 0 && (
        <RelationGroup
          title={`${title} · 已忽略`}
          relations={ignored}
          direction={direction}
          revision={revision}
          onRefresh={onRefresh}
          ignoredGroup
        />
      )}
    </>
  );
});

function RelationGroup({
  title,
  relations,
  direction,
  revision,
  onRefresh,
  candidateGroup = false,
  ignoredGroup = false,
}: RelationListProps & { candidateGroup?: boolean; ignoredGroup?: boolean }) {
  const alias = useSessionStore((state) => state.alias);
  const [collapsed, setCollapsed] = useState(ignoredGroup);
  const [expandedIds, setExpandedIds] = useState<Set<string>>(new Set());
  const [editingId, setEditingId] = useState<string | null>(null);
  const remove = useDeleteConfirm((id) => deleteRelation(id, revision, '手动删除'), onRefresh);
  const { deletingId, confirmId: deleteConfirmId, error: deleteError } = remove;

  const toggleExpand = useCallback((id: string) => {
    setExpandedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      return next;
    });
  }, []);

  const handleEditSaved = useCallback((result: MutationResultDto) => {
    onMutationSuccess(result);
    setEditingId(null);
    onRefresh();
  }, [onRefresh]);

  const handleEditCancel = useCallback(() => {
    setEditingId(null);
  }, []);

  return (
    <div className={`relation-list${candidateGroup ? ' is-candidate-group' : ''}${ignoredGroup ? ' is-ignored-group' : ''}`}>
      <button
        className="relation-list-header"
        onClick={() => setCollapsed(!collapsed)}
        type="button"
      >
        <span className={`chevron ${collapsed ? '' : 'expanded'}`}>&#9654;</span>
        <span className="relation-list-title">{title}</span>
        <span className="relation-list-count">{relations.length}</span>
      </button>

      {candidateGroup && !collapsed && (
        <p className="relation-list-candidate-hint">
          Agent 写入，放行后才进入正式图谱。去 <Link to={reviewPath(alias)}>评审页</Link> 处理。
        </p>
      )}

      {ignoredGroup && !collapsed && (
        <p className="relation-list-ignored-hint">
          人工拒绝，不会被重新挖掘。在 <Link to={reviewPath(alias)}>评审页</Link> 可撤销回待评审。
        </p>
      )}

      {!collapsed && (
        <ul className="relation-list-items">
          {relations.map((rel) => {
            const expanded = expandedIds.has(rel.id);
            const isEditing = editingId === rel.id;
            const isDeleting = deletingId === rel.id;
            const isConfirming = deleteConfirmId === rel.id;

            if (isEditing) {
              return (
                <li key={rel.id} className="relation-list-item">
                  <RelationEditor
                    mode="edit"
                    relation={rel}
                    revision={revision}
                    onSaved={handleEditSaved}
                    onCancel={handleEditCancel}
                  />
                </li>
              );
            }

            return (
              <li key={rel.id} className="relation-list-item">
                <div
                  className="relation-list-row"
                  onClick={() => toggleExpand(rel.id)}
                  role="button"
                  tabIndex={0}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' || e.key === ' ') toggleExpand(rel.id);
                  }}
                >
                  <RelationTypeBadge type={rel.type} size="sm" />

                  <span className="relation-list-endpoint">
                    {direction === 'in'
                      ? endpointSummary(rel.from)
                      : endpointSummary(rel.to)}
                  </span>

                  {rel.cardinality && rel.cardinality !== 'unknown' && (
                    <span className="relation-list-cardinality">
                      {cardinalityLabel(rel.cardinality)}
                    </span>
                  )}

                  {rel.confidence != null && (
                    <span className="relation-list-confidence" title="置信度">
                      {Math.round(rel.confidence * 100)}%
                    </span>
                  )}

                  {rel.verified && (
                    <span className="relation-list-verified" title="已核实">
                      <svg width="12" height="12" viewBox="0 0 24 24" fill="currentColor">
                        <path d="M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z" />
                      </svg>
                    </span>
                  )}

                  {isDeclared(rel) && (
                    <span className="relation-list-lock" title="数据库声明的外键（只读）">
                      <svg width="12" height="12" viewBox="0 0 24 24" fill="currentColor">
                        <path d="M18 8h-1V6c0-2.76-2.24-5-5-5S7 3.24 7 6v2H6c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V10c0-1.1-.9-2-2-2zm-6 9c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2zm3.1-9H8.9V6c0-1.71 1.39-3.1 3.1-3.1s3.1 1.39 3.1 3.1v2z" />
                      </svg>
                    </span>
                  )}

                  <span className={`relation-list-chevron ${expanded ? 'expanded' : ''}`}>
                    &#9662;
                  </span>
                </div>

                {expanded && (
                  <div className="relation-list-details">
                    <dl className="relation-list-details-grid">
                      <dt>起点</dt>
                      <dd className="relation-list-mono">{rel.from}</dd>
                      <dt>终点</dt>
                      <dd className="relation-list-mono">{rel.to}</dd>
                      {rel.joinExpression && (
                        <>
                          <dt>JOIN</dt>
                          <dd className="relation-list-mono">{rel.joinExpression}</dd>
                        </>
                      )}
                      {isIgnored(rel) && typeof rel.attributes?.rejectionReason === 'string' && (
                        <>
                          <dt>拒绝原因</dt>
                          <dd>{rel.attributes.rejectionReason}</dd>
                        </>
                      )}
                      {otherAttributes(rel) && (
                        <>
                          <dt>属性</dt>
                          <dd className="relation-list-mono">
                            {JSON.stringify(otherAttributes(rel))}
                          </dd>
                        </>
                      )}
                    </dl>

                    {isEditable(rel) && (
                      <div className="relation-list-actions">
                        <ActionIcon
                          action="edit"
                          label="编辑该关系"
                          disabled={isDeleting}
                          onClick={(e) => {
                            e.stopPropagation();
                            setEditingId(rel.id);
                          }}
                        />
                        {isConfirming ? (
                          <span className="relation-list-confirm-delete">
                            <span>确认删除？</span>
                            <Button
                              onClick={(e) => {
                                e.stopPropagation();
                                void remove.confirm(rel.id);
                              }}
                              disabled={isDeleting}
                              variant="danger"
                              size="sm"
                            >
                              {isDeleting ? '删除中…' : '确认'}
                            </Button>
                            <Button
                              onClick={(e) => {
                                e.stopPropagation();
                                remove.cancel();
                              }}
                              disabled={isDeleting}
                              size="sm"
                            >
                              取消
                            </Button>
                          </span>
                        ) : (
                          <ActionIcon
                            action="delete"
                            label={isDeleting ? '删除中…' : '删除该关系'}
                            disabled={isDeleting}
                            onClick={(e) => {
                              e.stopPropagation();
                              remove.ask(rel.id);
                            }}
                          />
                        )}
                      </div>
                    )}

                    {deleteError && deleteConfirmId === rel.id && (
                      <div className="relation-list-delete-error">{deleteError}</div>
                    )}
                  </div>
                )}
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}

function cardinalityLabel(cardinality: string): string {
  switch (cardinality) {
    case 'one_to_one':
      return '1 : 1';
    case 'one_to_many':
      return '1 : N';
    case 'many_to_one':
      return 'N : 1';
    case 'many_to_many':
      return 'N : N';
    default:
      return cardinality.replace(/_/g, '-');
  }
}
