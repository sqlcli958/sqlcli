import { useState, useCallback } from 'react';
import { memo } from 'react';
import { createRelation, patchRelation } from '../../api/relations';
import { ApiError } from '../../api/client';
import type {
  RelationEdgeDto,
  RelationType,
  RelationCardinality,
  MutationResultDto,
} from '../../types/api';
import { Button } from '../../ui/Button';
import { Select } from '../../ui/Select';

// ── Props ──

interface CreateModeProps {
  mode: 'create';
  defaultFrom?: string;
  defaultTo?: string;
  defaultType?: RelationType;
  defaultJoinExpression?: string;
  title?: string;
  lockEndpoints?: boolean;
  revision: number;
  onSaved: (result: MutationResultDto) => void;
  onCancel: () => void;
}

interface EditModeProps {
  mode: 'edit';
  relation: RelationEdgeDto;
  revision: number;
  onSaved: (result: MutationResultDto) => void;
  onCancel: () => void;
}

export type RelationEditorProps = CreateModeProps | EditModeProps;

// ── Constants ──

/**
 * 可手工创建的类型。
 *
 * foreign_key 不在列表里：它由数据库导入维护，手工建的会在下次刷新时被冲掉。
 */
const CREATEABLE_TYPES: RelationType[] = ['join_observed', 'term_mapping'];



const CARDINALITY_OPTIONS: { value: RelationCardinality; label: string }[] = [
  { value: 'unknown', label: '未知' },
  { value: 'one_to_one', label: '一对一（1 : 1）' },
  { value: 'one_to_many', label: '一对多（1 : N）' },
  { value: 'many_to_one', label: '多对一（N : 1）' },
  { value: 'many_to_many', label: '多对多（N : N）' },
];

function isCardinality(value: string): value is RelationCardinality {
  return CARDINALITY_OPTIONS.some((o) => o.value === value);
}

function endpointSummary(id: string): string {
  const parts = id.split(':');
  const last = parts[parts.length - 1] ?? id;
  const dots = last.split('.');
  if (dots.length >= 2) {
    return dots.slice(-2).join('.');
  }
  return last;
}

// ── Component ──

export const RelationEditor = memo(function RelationEditor(props: RelationEditorProps) {
  const { revision, onSaved, onCancel } = props;
  const isEdit = props.mode === 'edit';
  const relationId = isEdit ? props.relation.id : null;
  const lockEndpoints = !isEdit && Boolean(props.lockEndpoints);
  const title = !isEdit && props.title ? props.title : (isEdit ? '编辑关系' : '新建关系');
  const availableTypes = !isEdit && props.defaultFrom?.startsWith('column:') && props.defaultTo?.startsWith('column:')
    ? CREATEABLE_TYPES.filter((relationType) => relationType !== 'term_mapping')
    : CREATEABLE_TYPES;

  // Form state
  const [type, setType] = useState<string>(
    // 默认值必须是 CREATEABLE_TYPES 里的一个，否则下拉框显示的和 state 对不上。
    // 这里原来写的是已经删掉的 foreign_key_inferred，靠后端的旧名兼容映射才没出事。
    isEdit ? props.relation.type : (props.defaultType ?? (props.defaultFrom ? 'join_observed' : '')),
  );
  const [from, setFrom] = useState<string>(
    isEdit ? props.relation.from : (props.defaultFrom ?? ''),
  );
  const [to, setTo] = useState<string>(
    isEdit ? props.relation.to : (props.defaultTo ?? ''),
  );
  const [cardinality, setCardinality] = useState<RelationCardinality>(
    isEdit ? (props.relation.cardinality ?? 'unknown') : 'unknown',
  );
  const [joinExpression, setJoinExpression] = useState<string>(
    isEdit ? (props.relation.joinExpression ?? '') : (props.defaultJoinExpression ?? ''),
  );
  const [confidence, setConfidence] = useState<string>(
    isEdit ? String(props.relation.confidence ?? '') : '',
  );
  const [verified, setVerified] = useState<boolean>(
    isEdit ? (props.relation.verified ?? false) : false,
  );
  const [reason, setReason] = useState<string>('');

  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const isFormValid =
    type.trim().length > 0 &&
    from.trim().length > 0 &&
    to.trim().length > 0 &&
    from !== to &&
    reason.trim().length > 0;

  const handleSave = useCallback(async () => {
    if (!isFormValid) return;
    setSaving(true);
    setError(null);

    try {
      let result: MutationResultDto;
      if (isEdit) {
        result = await patchRelation(relationId!, {
          expectedRevision: revision,
          patch: {
            cardinality,
            joinExpression: joinExpression || undefined,
            confidence: confidence ? parseFloat(confidence) : undefined,
            verified,
          },
          reason: reason.trim(),
        });
      } else {
        result = await createRelation({
          type,
          from: from.trim(),
          to: to.trim(),
          expectedRevision: revision,
          cardinality,
          joinExpression: joinExpression || undefined,
          confidence: confidence ? parseFloat(confidence) : undefined,
          verified,
          reason: reason.trim(),
        });
      }
      onSaved(result);
    } catch (err: unknown) {
      if (err instanceof Error) {
        const msg = err.message;
        if ((err instanceof ApiError && err.status === 409)
            || msg.includes('409') || msg.toLowerCase().includes('conflict')) {
          setError('冲突：工作区已被其他会话修改，请刷新后重试。');
        } else if (err instanceof ApiError && err.status === 423) {
          setError('已锁定：工作区正在被修改，请稍后重试。');
        } else if (err instanceof TypeError) {
          setError('网络错误：无法保存，请检查连接后重试。');
        } else {
          setError(msg);
        }
      } else {
        setError('保存关系失败');
      }
    } finally {
      setSaving(false);
    }
  }, [isEdit, isFormValid, type, from, to, cardinality, joinExpression, confidence,
    verified, reason, onSaved, revision, relationId]);

  const handleConfidenceChange = useCallback((value: string) => {
    // Allow empty or numbers 0.0-1.0
    if (value === '') {
      setConfidence('');
      return;
    }
    const num = parseFloat(value);
    if (!isNaN(num) && num >= 0 && num <= 1) {
      setConfidence(value);
    }
  }, []);

  return (
    <div className="relation-editor">
      <h4 className="relation-editor-title">
        {title}
      </h4>

      {error && (
        <div className="relation-editor-error">{error}</div>
      )}

      <div className="relation-editor-form">
        {/* Type */}
        <label className="relation-editor-field">
          <span className="relation-editor-label">类型</span>
          {isEdit ? (
            <input
              className="relation-editor-input"
              type="text"
              value={type}
              readOnly
              disabled
            />
          ) : (
            <Select
              value={type}
              onChange={(e) => setType(e.target.value)}
              disabled={saving}
            >
              <option value="">— 选择关系类型 —</option>
              {availableTypes.map((t) => (
                <option key={t} value={t}>{t}</option>
              ))}
            </Select>
          )}
        </label>

        {lockEndpoints ? (
          <div className="relation-editor-endpoints">
            <div className="relation-editor-endpoint-card">
              <span className="relation-editor-label">起点</span>
              <strong>{endpointSummary(from)}</strong>
              <code className="relation-editor-endpoint-code">{from}</code>
            </div>
            <div className="relation-editor-endpoint-card">
              <span className="relation-editor-label">终点</span>
              <strong>{endpointSummary(to)}</strong>
              <code className="relation-editor-endpoint-code">{to}</code>
            </div>
          </div>
        ) : (
          <>
            {/* From */}
            <label className="relation-editor-field">
              <span className="relation-editor-label">起点</span>
              <input
                className="relation-editor-input"
                type="text"
                value={from}
                onChange={(e) => setFrom(e.target.value)}
                readOnly={isEdit}
                disabled={isEdit || saving}
                placeholder="column:alias:SCHEMA.TABLE.COL"
              />
            </label>

            {/* To */}
            <label className="relation-editor-field">
              <span className="relation-editor-label">终点</span>
              <input
                className="relation-editor-input"
                type="text"
                value={to}
                onChange={(e) => setTo(e.target.value)}
                readOnly={isEdit}
                disabled={isEdit || saving}
                placeholder="column:alias:SCHEMA.TABLE.COL"
              />
            </label>
          </>
        )}

        {/* Cardinality */}
        <label className="relation-editor-field">
          <span className="relation-editor-label">基数</span>
          <Select
            value={cardinality}
            onChange={(e) => {
              if (isCardinality(e.target.value)) {
                setCardinality(e.target.value);
              }
            }}
            disabled={saving}
          >
            {CARDINALITY_OPTIONS.map((opt) => (
              <option key={opt.value} value={opt.value}>{opt.label}</option>
            ))}
          </Select>
        </label>

        {/* Join Expression */}
        <label className="relation-editor-field">
          <span className="relation-editor-label">JOIN 表达式</span>
          <input
            className="relation-editor-input relation-editor-mono"
            type="text"
            value={joinExpression}
            onChange={(e) => setJoinExpression(e.target.value)}
            disabled={saving}
            placeholder="a.col1 = b.col2"
          />
        </label>

        {/* Confidence */}
        <label className="relation-editor-field">
          <span className="relation-editor-label">置信度</span>
          <input
            className="relation-editor-input"
            type="number"
            value={confidence}
            onChange={(e) => handleConfidenceChange(e.target.value)}
            disabled={saving}
            min="0"
            max="1"
            step="0.01"
            placeholder="0.0 - 1.0"
          />
        </label>

        {/* Verified */}
        <label className="relation-editor-field relation-editor-field-row">
          <input
            className="relation-editor-checkbox"
            type="checkbox"
            checked={verified}
            onChange={(e) => setVerified(e.target.checked)}
            disabled={saving}
          />
          <span className="relation-editor-label">已核实</span>
        </label>

        {/* Reason */}
        <label className="relation-editor-field">
          <span className="relation-editor-label">
            变更原因 <span className="relation-editor-required">*</span>
          </span>
          <input
            className="relation-editor-input"
            type="text"
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            disabled={saving}
            placeholder="填写本次变更的原因（必填）"
          />
        </label>
      </div>

      <div className="relation-editor-actions">
        <Button
          onClick={handleSave}
          disabled={saving || !isFormValid}
          variant="primary"
          size="sm"
        >
          {saving ? (
            <>
              <span className="spinner" />
              保存中…
            </>
          ) : (
            '保存'
          )}
        </Button>
        <Button
          onClick={onCancel}
          disabled={saving}
          size="sm"
        >
          取消
        </Button>
      </div>
    </div>
  );
});
