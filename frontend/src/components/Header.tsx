import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';
import { useApp, type Screen } from '../store/app';
import { roleMeta } from '../theme/colors';
import type { PromotionView } from '../api/types';

const TABS: { label: string; screen: Screen; needsSA?: boolean }[] = [
  { label: 'FLEET', screen: 'fleet' },
  { label: 'PROMOTE', screen: 'promote' },
  { label: 'DEPLOY', screen: 'deploy' },
  { label: 'APPROVALS', screen: 'approvals', needsSA: true },
  { label: 'REGISTRY', screen: 'registry' },
  { label: 'HISTORY', screen: 'history' },
  { label: 'ADMIN', screen: 'admin', needsSA: true },
  { label: 'SYSTEM', screen: 'system' },
];

export function Header() {
  const { me, screen, go, scenario, setScenario, signOut, flash } = useApp();
  const isSA = me?.role === 'superadmin';

  const { data: pending } = useQuery({
    queryKey: ['approvals', 'pending'],
    queryFn: () => api.get<PromotionView[]>('/approvals/pending'),
    enabled: isSA,
    refetchInterval: 20_000,
  });
  const pendingCount = pending?.length ?? 0;

  async function doLogout() {
    try { await api.post('/auth/logout'); } catch { /* ignore */ }
    signOut();
    flash('Signed out');
  }

  if (!me) return null;
  const rm = roleMeta[me.role];

  return (
    <header style={{ display: 'flex', alignItems: 'center', gap: 16, minHeight: 52, padding: '6px 24px',
      flexWrap: 'wrap', borderBottom: '2px solid color-mix(in srgb, var(--color-neutral-100) 30%, transparent)' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, flex: 'none' }}>
        <div style={{ width: 14, height: 14, background: 'var(--color-accent)' }} />
        <div style={{ fontFamily: 'var(--font-heading)', fontWeight: 800, fontSize: 14, letterSpacing: '.05em', color: 'var(--color-neutral-100)', whiteSpace: 'nowrap' }}>
          NAGAD <span style={{ color: 'var(--color-neutral-500)', fontWeight: 600 }}>DEPLOY</span>
        </div>
      </div>

      <nav style={{ display: 'flex', alignItems: 'stretch', gap: 2, alignSelf: 'stretch', flexWrap: 'wrap' }}>
        {TABS.filter((t) => !t.needsSA || isSA).map((t) => {
          const active = screen === t.screen || (t.screen === 'fleet' && screen === 'detail');
          const badge = t.screen === 'approvals' && pendingCount > 0;
          return (
            <button key={t.screen} onClick={() => go(t.screen)} style={{ border: 0, background: 'transparent',
              cursor: 'pointer', padding: '0 13px', display: 'inline-flex', alignItems: 'center', gap: 7,
              fontFamily: 'var(--font-heading)', fontWeight: 600, fontSize: 11, letterSpacing: '.11em',
              color: active ? 'var(--color-neutral-100)' : 'var(--color-neutral-500)',
              boxShadow: active ? 'inset 0 -2px 0 var(--color-accent)' : 'none' }}>
              {t.label}
              {badge && <span style={{ minWidth: 16, height: 16, padding: '0 4px', display: 'inline-flex',
                alignItems: 'center', justifyContent: 'center', background: 'var(--color-accent)',
                color: 'var(--color-neutral-100)', fontSize: 9.5, fontWeight: 800 }}>{pendingCount}</span>}
            </button>
          );
        })}
      </nav>

      <div style={{ flex: 1 }} />

      <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
        <div style={{ display: 'flex', border: '1px solid color-mix(in srgb, var(--color-neutral-100) 30%, transparent)' }}>
          {(['incident', 'healthy'] as const).map((s) => (
            <button key={s} onClick={() => setScenario(s)} title="demo scenario"
              style={{ border: 0, cursor: 'pointer', padding: '5px 10px', fontFamily: 'var(--font-heading)',
                fontWeight: 600, fontSize: 9.5, letterSpacing: '.1em',
                background: scenario === s ? 'var(--color-neutral-100)' : 'transparent',
                color: scenario === s ? 'var(--color-text)' : 'var(--color-neutral-500)' }}>{s.toUpperCase()}</button>
          ))}
        </div>

        <div title="persistent store" style={{ display: 'flex', alignItems: 'center', gap: 6, fontFamily: 'var(--mono)',
          fontSize: 10.5, color: 'var(--color-neutral-500)', whiteSpace: 'nowrap' }}>
          <span style={{ width: 7, height: 7, background: 'oklch(0.72 0.15 152)' }} />registry-db · postgres
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: 8, border: '1px solid color-mix(in srgb, var(--color-neutral-100) 30%, transparent)', padding: '5px 10px' }}>
          <span style={{ fontFamily: 'var(--mono)', fontSize: 11, color: 'var(--color-neutral-100)' }}>{me.username}</span>
          <span style={{ padding: '1px 6px', background: rm.bg, color: rm.fg, fontFamily: 'var(--font-heading)', fontWeight: 800, fontSize: 8.5, letterSpacing: '.1em' }}>{rm.tag}</span>
          <span style={{ fontFamily: 'var(--mono)', fontSize: 11, letterSpacing: '.15em', color: 'var(--color-accent-400)' }}>{me.perms}</span>
        </div>

        <button onClick={doLogout} title="sign out" style={{ border: '1px solid color-mix(in srgb, var(--color-neutral-100) 30%, transparent)',
          background: 'transparent', color: 'var(--color-neutral-400)', cursor: 'pointer', padding: '6px 12px',
          fontFamily: 'var(--font-heading)', fontWeight: 600, fontSize: 10, letterSpacing: '.12em' }}>LOG OUT</button>
      </div>
    </header>
  );
}
