import { create } from 'zustand';
import { setToken } from '../api/client';

export interface Me {
  username: string;
  name: string;
  email: string;
  role: string;
  scope: string;
  r: boolean;
  w: boolean;
  x: boolean;
  perms: string;
  mustChangePassword: boolean;
}

export type Screen =
  | 'fleet' | 'detail' | 'promote' | 'deploy' | 'approvals'
  | 'registry' | 'history' | 'admin' | 'system';

interface AppState {
  me: Me | null;
  screen: Screen;
  scenario: 'incident' | 'healthy';
  toast: { msg: string; color: string } | null;
  detail: { group: string; svc: string; host: string | null } | null;

  signIn: (token: string, me: Me) => void;
  signOut: () => void;
  setMe: (me: Me | null) => void;
  go: (s: Screen) => void;
  openDetail: (group: string, svc: string, host: string | null) => void;
  setScenario: (s: 'incident' | 'healthy') => void;
  flash: (msg: string, color?: string) => void;
}

export const useApp = create<AppState>((set) => ({
  me: null,
  screen: 'fleet',
  scenario: 'incident',
  toast: null,
  detail: null,

  signIn: (token, me) => {
    setToken(token);
    set({ me, screen: 'fleet' });
  },
  signOut: () => {
    setToken(null);
    set({ me: null, screen: 'fleet', detail: null });
  },
  setMe: (me) => set({ me }),
  go: (screen) => set({ screen }),
  openDetail: (group, svc, host) => set({ detail: { group, svc, host }, screen: 'detail' }),
  setScenario: (scenario) => set({ scenario }),
  flash: (msg, color = 'oklch(0.72 0.15 152)') => {
    set({ toast: { msg, color } });
    setTimeout(() => set((s) => (s.toast?.msg === msg ? { toast: null } : {})), 4200);
  },
}));
