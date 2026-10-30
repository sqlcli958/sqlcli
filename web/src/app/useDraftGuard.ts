import { createContext, useContext, useEffect, useId } from 'react';

export const DraftContext = createContext<{
  register: (id: string) => () => void;
  confirm: (action: () => void) => void;
  dirty: boolean;
} | null>(null);

export function useDraftNavigation() {
  const context = useContext(DraftContext);
  return (action: () => void) => {
    if (context?.dirty) context.confirm(action);
    else action();
  };
}

/** 草稿只在内存中登记，不把表单内容（尤其是密码）写入浏览器存储。 */
export function useDraftGuard(dirty: boolean) {
  const context = useContext(DraftContext);
  const register = context?.register;
  const id = useId();
  useEffect(() => dirty ? register?.(id) : undefined, [dirty, id, register]);
  return (action: () => void) => {
    if (!dirty) action();
    else if (context) context.confirm(action);
    else if (window.confirm('放弃未保存的修改？')) action();
  };
}
