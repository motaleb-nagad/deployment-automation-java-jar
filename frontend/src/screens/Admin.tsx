import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';
import { roleMeta, rule1, rule2 } from '../theme/colors';
import type { AdminRow } from '../api/types';

const mono = 'var(--mono)';

export function Admin() {
  const { data } = useQuery({ queryKey: ['admin', 'users'], queryFn: () => api.get<AdminRow[]>('/admin/users') });
  const cols = '130px 130px 1fr 44px 44px 44px';
  const mk = (on: boolean) => (on ? { c: 'var(--status-running-fg)', m: '✓' } : { c: 'var(--color-neutral-600)', m: '·' });

  return (
    <main style={{ padding: '0 24px 56px', overflowX: 'auto' }}>
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 14, padding: '20px 0 12px', flexWrap: 'wrap' }}>
        <h3 style={{ margin: 0, color: 'var(--color-neutral-100)' }}>Access control</h3>
        <div style={{ fontSize: 12.5, color: 'var(--color-neutral-500)' }}>Roles and per-group r / w / x permissions. Super-admin only.</div>
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: cols, gap: 12, padding: '6px 8px', borderTop: rule2, borderBottom: rule1, fontFamily: 'var(--font-heading)', fontWeight: 600, fontSize: 9.5, letterSpacing: '.1em', color: 'var(--color-neutral-500)' }}>
        <div>USER</div><div>ROLE</div><div>SCOPE (GROUPS)</div><div>R</div><div>W</div><div>X</div>
      </div>
      {data?.map((ar) => {
        const rm = roleMeta[ar.role];
        const r = mk(ar.r), w = mk(ar.w), x = mk(ar.x);
        return (
          <div key={ar.username} style={{ display: 'grid', gridTemplateColumns: cols, gap: 12, padding: '11px 8px', borderBottom: rule1, alignItems: 'center', fontFamily: mono, fontSize: 12 }}>
            <div style={{ color: 'var(--color-neutral-100)' }}>{ar.username}</div>
            <div><span style={{ padding: '2px 7px', background: rm.bg, color: rm.fg, fontFamily: 'var(--font-heading)', fontWeight: 800, fontSize: 9, letterSpacing: '.1em' }}>{rm.label.toUpperCase()}</span></div>
            <div style={{ color: 'var(--color-neutral-400)' }}>{ar.scope === 'all' ? 'all groups' : ar.scope}</div>
            <div style={{ color: r.c, fontWeight: 700 }}>{r.m}</div>
            <div style={{ color: w.c, fontWeight: 700 }}>{w.m}</div>
            <div style={{ color: x.c, fontWeight: 700 }}>{x.m}</div>
          </div>
        );
      })}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3,1fr)', gap: 2, marginTop: 24 }}>
        {[
          ['SUPER ADMIN', 'All groups, rwx. The only role that approves or denies jar promotions and manages users. Every decision is attributed and logged.'],
          ['OPERATOR', 'Scoped to named groups. w = fetch/promote from staging; x = execute deploy of an approved jar. An operator with rw but no x can request, not deploy.'],
          ['VIEWER', 'Read-only across the fleet, registry and history. Cannot fetch, approve or deploy.'],
        ].map(([t, d]) => (
          <div key={t} style={{ background: 'var(--color-neutral-900)', padding: 16 }}>
            <div style={{ fontFamily: 'var(--font-heading)', fontWeight: 800, fontSize: 11, letterSpacing: '.1em', color: 'var(--color-neutral-100)' }}>{t}</div>
            <div style={{ fontSize: 12, color: 'var(--color-neutral-400)', marginTop: 8 }}>{d}</div>
          </div>
        ))}
      </div>
    </main>
  );
}
