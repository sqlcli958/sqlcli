import { create } from 'zustand';

export interface SessionState {
  alias: string | null;
  token: string | null;
  revision: number;
  readOnly: boolean;
  capabilities: string[];
  isConnected: boolean;
  /**
   * 最近一次写操作没有生效、而是进了审批队列时的审批号。
   *
   * 别名开了图谱审批之后，UI 里保存一个表描述**不会改图谱**，只会排队。
   * 不把这件事说出来，编辑器关掉、看着像存成功了，而图谱纹丝不动——
   * 这是「先落 db、批准后才写图谱」唯一会骗到人的地方，所以顶栏必须提一句。
   */
  pendingApproval: number | null;
}

export interface SessionActions {
  setSession: (alias: string, token: string | null, revision: number, readOnly: boolean, capabilities?: string[]) => void;
  clearSession: () => void;
  dismissPendingApproval: () => void;
}

export type SessionStore = SessionState & SessionActions;

export const useSessionStore = create<SessionStore>((set) => ({
  alias: null,
  token: null,
  revision: 0,
  readOnly: true,
  capabilities: [],
  isConnected: false,
  pendingApproval: null,

  dismissPendingApproval: () => set({ pendingApproval: null }),

  setSession: (alias, token, revision, readOnly, capabilities = []) => {
    if (token) {
      sessionStorage.setItem('sql-cli-session-token', token);
    } else {
      sessionStorage.removeItem('sql-cli-session-token');
    }
    set({ alias, token, revision, readOnly, capabilities, isConnected: true, pendingApproval: null });
  },

  clearSession: () => {
    sessionStorage.removeItem('sql-cli-session-token');
    set({ alias: null, token: null, revision: 0, readOnly: true, capabilities: [], isConnected: false,
      pendingApproval: null });
  },
}));
