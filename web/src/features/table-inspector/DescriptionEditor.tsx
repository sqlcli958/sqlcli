import { useState, useCallback, useRef, useEffect } from 'react';
import { memo } from 'react';
import { Button } from '../../ui/Button';

interface DescriptionEditorProps {
  /** Current value (can be undefined/null for empty) */
  value: string | undefined;
  /** Called when user saves. Should return a promise - resolves on success, rejects on error */
  onSave: (newValue: string) => Promise<void>;
  /** If true, editing is disabled */
  readOnly?: boolean;
  /** Label shown above the editor */
  label?: string;
  /** Placeholder shown when value is empty */
  placeholder?: string;
  /** Use a textarea instead of a single-line input */
  multiline?: boolean;
}

export const DescriptionEditor = memo(function DescriptionEditor({
  value,
  onSave,
  readOnly = false,
  label,
  placeholder = '点击编辑…',
  multiline = true,
}: DescriptionEditorProps) {
  const [editing, setEditing] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [draft, setDraft] = useState(value ?? '');
  const originalRef = useRef(value ?? '');

  // Sync external value changes when not editing
  useEffect(() => {
    if (!editing) {
      originalRef.current = value ?? '';
      setDraft(value ?? '');
      setError(null);
    }
  }, [value, editing]);

  const isDirty = draft !== originalRef.current;

  const handleEnterEdit = useCallback(() => {
    if (readOnly) return;
    setEditing(true);
    setError(null);
    setDraft(value ?? '');
    originalRef.current = value ?? '';
  }, [readOnly, value]);

  const handleCancel = useCallback(() => {
    setEditing(false);
    setDraft(originalRef.current);
    setError(null);
  }, []);

  const handleSave = useCallback(async () => {
    if (!isDirty) {
      setEditing(false);
      return;
    }
    setSaving(true);
    setError(null);
    try {
      await onSave(draft.trim());
      originalRef.current = draft.trim();
      setEditing(false);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : '保存失败';
      setError(msg);
    } finally {
      setSaving(false);
    }
  }, [isDirty, draft, onSave]);

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key === 'Enter' && !multiline && !e.shiftKey) {
        e.preventDefault();
        handleSave();
      }
      if (e.key === 'Escape') {
        handleCancel();
      }
    },
    [multiline, handleSave, handleCancel],
  );

  const displayValue = value || placeholder;
  const isEmpty = !value || value.trim().length === 0;

  return (
    <div className="desc-editor">
      {label && <label className="desc-editor-label">{label}</label>}

      {!editing ? (
        /* Display mode */
        <div
          className={`desc-editor-display ${isEmpty ? 'desc-editor-placeholder' : ''} ${readOnly ? 'desc-editor-readonly' : ''}`}
          onClick={handleEnterEdit}
          title={readOnly ? '' : '点击编辑'}
          role={readOnly ? undefined : 'button'}
          tabIndex={readOnly ? undefined : 0}
          onKeyDown={readOnly ? undefined : (e) => { if (e.key === 'Enter' || e.key === ' ') handleEnterEdit(); }}
          aria-label={readOnly ? undefined : `编辑${label || '描述'}`}
        >
          <span className="desc-editor-text">
            {displayValue}
          </span>
          {!readOnly && (
            <span className="desc-editor-edit-icon" aria-hidden="true">
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7" />
                <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z" />
              </svg>
            </span>
          )}
        </div>
      ) : (
        /* Edit mode */
        <div className="desc-editor-edit">
          {multiline ? (
            <textarea
              className="desc-editor-textarea"
              value={draft}
              onChange={(e) => setDraft(e.target.value)}
              onKeyDown={handleKeyDown}
              placeholder={placeholder}
              autoFocus
              rows={Math.max(2, draft.split('\n').length)}
              disabled={saving}
            />
          ) : (
            <input
              className="desc-editor-input"
              type="text"
              value={draft}
              onChange={(e) => setDraft(e.target.value)}
              onKeyDown={handleKeyDown}
              placeholder={placeholder}
              autoFocus
              disabled={saving}
            />
          )}

          {error && (
            <div className="desc-editor-error">{error}</div>
          )}

          <div className="desc-editor-actions">
            <Button
              onClick={handleSave}
              disabled={saving || !isDirty}
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
              onClick={handleCancel}
              disabled={saving}
              size="sm"
            >
              取消
            </Button>
          </div>
        </div>
      )}
    </div>
  );
});
