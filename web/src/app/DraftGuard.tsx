import { useCallback, useState, type ReactNode } from 'react';
import { useBeforeUnload, useBlocker } from 'react-router-dom';
import { Button } from '../ui/Button';
import { Dialog } from '../ui/Dialog';
import { DraftContext } from './useDraftGuard';

export function DraftGuard({ children }: { children: ReactNode }) {
  const [drafts, setDrafts] = useState<Set<string>>(() => new Set());
  const [action, setAction] = useState<(() => void) | null>(null);
  const register = useCallback((id: string) => {
    setDrafts((current) => new Set(current).add(id));
    return () => setDrafts((current) => {
      const next = new Set(current);
      next.delete(id);
      return next;
    });
  }, []);
  const confirm = useCallback((next: () => void) => setAction(() => next), []);
  const blocker = useBlocker(({ currentLocation, nextLocation }) => drafts.size > 0 && (
    currentLocation.pathname !== nextLocation.pathname || currentLocation.search !== nextLocation.search
  ));
  useBeforeUnload(useCallback((event) => {
    if (drafts.size === 0) return;
    event.preventDefault();
    event.returnValue = '';
  }, [drafts.size]));

  function cancel() {
    setAction(null);
    if (blocker.state === 'blocked') blocker.reset();
  }

  return <DraftContext.Provider value={{ register, confirm, dirty: drafts.size > 0 }}>
    {children}
    <Dialog open={action !== null || blocker.state === 'blocked'}
      onOpenChange={(open) => { if (!open) cancel(); }} title="放弃未保存的修改？"
      description="离开后，本次未保存的输入将丢失。密码和密钥不会保存到浏览器存储。">
      <div className="dialog-actions">
        <Button onClick={cancel}>继续编辑</Button>
        <Button variant="danger" onClick={() => {
          setAction(null);
          if (blocker.state === 'blocked') blocker.proceed();
          else action?.();
        }}>放弃修改</Button>
      </div>
    </Dialog>
  </DraftContext.Provider>;
}

