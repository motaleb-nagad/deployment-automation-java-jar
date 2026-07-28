import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../api/client';
import { useApp } from '../store/app';
import { roleMeta, rule1, rule2, border } from '../theme/colors';
import type { AdminRow } from '../api/types';

const mono = 'var(--mono)';

type Role = 'superadmin' | 'operator' | 'viewer';

export function Admin() {
  const qc = useQueryClient();
  const { me, flash } = useApp();
  const { data } = useQuery({ queryKey: ['admin', 'users'], queryFn: () => api.get<AdminRow[]>('/admin/users') });
  const cols = '1fr 120px 130px 40px 40px 40px 70px';
  const mk = (on: boolean) => (on ? { c: 'var(--status-running-fg)', m: '✓' } : { c: 'var(--color-neutral-600)', m: '·' });

  const [username, setUsername] = useState('');
  const [name, setName] = useState('');
  const [password, setPassword] = useState('');
  const [role, setRole] = useState<Role>('operator');
  const [scope, setScope] = useState('all');
  const [r, setR] = useState(true);
  const [w, setW] = useState(true);
  const [x, setX] = useState(false);
  const [busy, setBusy] = useState(false);

  const canCreate = username.trim().length > 0 && password.length >= 6;

  async function create() {
    if (!canCreate) return;
    setBusy(true);
    try {
      await api.post('/admin/users', {
        username: username.trim(), name: name.trim() || username.trim(), email: username.trim(),
        role, scope: scope.trim() || 'all', r, w, x, password,
      });
      flash(`User ${username.trim()} created`);
      setUsername(''); setName(''); setPassword(''); setScope('all'); setRole('operator'); setR(true); setW(true); setX(false);
      qc.invalidateQueries({ queryKey: ['admin', 'users'] });
    } catch (e) {
      flash(e instanceof ApiError ? e.message : 'create failed', 'var(--color-accent)');
    } finally { setBusy(false); }
  }

  async function remove(u: string) {
    if (!confirm(`Delete user ${u}?`)) return;
    try {
      await api.del(`/admin/users/${encodeURIComponent(u)}`);
      flash(`User ${u} removed`);
      qc.invalidateQueries({ queryKey: ['admin', 'users'] });
    } catch (e) {
      flash(e instanceof ApiError ? e.message : 'delete failed', 'var(--color-accent)');
    }
  }

  const inp: React.CSSProperties = { background: 'var(--color-text)', border, color: 'var(--color-neutral-100)', padding: '9px 10px', fontFamily: mono, fontSize: 12 };
  const cap: React.CSSProperties = { fontFamily: 'var(--font-heading)', fontWeight: 600, fontSize: 9, letterSpacing: '.1em', color: 'var(--color-neutral-500)', marginBottom: 5, display: 'block' };

  return (
    <main style={{ padding: '0 24px 56px', overflowX: 'auto' }}>
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 14, padding: '20px 0 12px', flexWrap: 'wrap' }}>
        <h3 style={{ margin: 0, color: 'var(--color-neutral-100)' }}>Access control</h3>
        <div style={{ fontSize: 12.5, color: 'var(--color-neutral-500)' }}>Provision accounts and per-group r / w / x permissions. Super-admin only.</div>
      </div>

      {/* Create user */}
      <div style={{ background: 'var(--color-neutral-900)', padding: 16, marginBottom: 22 }}>
        <div style={{ fontFamily: 'var(--font-heading)', fontWeight: 800, fontSize: 11, letterSpacing: '.1em', color: 'var(--color-neutral-100)', marginBottom: 14 }}>CREATE USER</div>
        <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', alignItems: 'flex-end' }}>
          <div style={{ display: 'flex', flexDirection: 'column', minWidth: 220 }}>
            <label style={cap}>ACCOUNT (EMAIL)</label>
            <input value={username} onChange={(e) => setUsername(e.target.value)} placeholder="user@nagad.com.bd" autoComplete="off" style={inp} />
          </div>
          <div style={{ display: 'flex', flexDirection: 'column', minWidth: 160 }}>
            <label style={cap}>NAME</label>
            <input value={name} onChange={(e) => setName(e.target.value)} placeholder="Full name" style={inp} />
          </div>
          <div style={{ display: 'flex', flexDirection: 'column', minWidth: 160 }}>
            <label style={cap}>PASSWORD</label>
            <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} placeholder="min 6 chars" autoComplete="new-password" style={inp} />
          </div>
          <div style={{ display: 'flex', flexDirection: 'column', minWidth: 130 }}>
            <label style={cap}>ROLE</label>
            <select value={role} onChange={(e) => setRole(e.target.value as Role)} style={inp}>
              <option value="operator">operator</option>
              <option value="viewer">viewer</option>
              <option value="superadmin">superadmin</option>
            </select>
          </div>
          <div style={{ display: 'flex', flexDirection: 'column', minWidth: 180 }}>
            <label style={cap}>SCOPE (GROUPS)</label>
            <input value={scope} onChange={(e) => setScope(e.target.value)} placeholder="all or core,web,ussd" style={inp} />
          </div>
          <div style={{ display: 'flex', gap: 14, alignItems: 'center', padding: '0 4px 8px' }}>
            {([['r', r, setR], ['w', w, setW], ['x', x, setX]] as const).map(([lbl, val, set]) => (
              <label key={lbl} style={{ display: 'flex', alignItems: 'center', gap: 5, cursor: 'pointer', fontFamily: mono, fontSize: 12, color: 'var(--color-neutral-300)' }}>
                <input type="checkbox" checked={val} onChange={(e) => set(e.target.checked)} /> {lbl}
              </label>
            ))}
          </div>
          <button onClick={create} disabled={busy || !canCreate} style={{ border: 0, background: 'var(--color-accent)', color: 'var(--color-neutral-100)', cursor: 'pointer', padding: '10px 18px', fontFamily: 'var(--font-heading)', fontWeight: 800, fontSize: 11, letterSpacing: '.1em', opacity: canCreate ? 1 : 0.5, marginBottom: 8 }}>CREATE →</button>
        </div>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: cols, gap: 12, padding: '6px 8px', borderTop: rule2, borderBottom: rule1, fontFamily: 'var(--font-heading)', fontWeight: 600, fontSize: 9.5, letterSpacing: '.1em', color: 'var(--color-neutral-500)' }}>
        <div>USER</div><div>ROLE</div><div>SCOPE (GROUPS)</div><div>R</div><div>W</div><div>X</div><div />
      </div>
      {data?.map((ar) => {
        const rm = roleMeta[ar.role];
        const rr = mk(ar.r), ww = mk(ar.w), xx = mk(ar.x);
        return (
          <div key={ar.username} style={{ display: 'grid', gridTemplateColumns: cols, gap: 12, padding: '11px 8px', borderBottom: rule1, alignItems: 'center', fontFamily: mono, fontSize: 12 }}>
            <div style={{ color: 'var(--color-neutral-100)' }}>{ar.username}</div>
            <div><span style={{ padding: '2px 7px', background: rm.bg, color: rm.fg, fontFamily: 'var(--font-heading)', fontWeight: 800, fontSize: 9, letterSpacing: '.1em' }}>{rm.label.toUpperCase()}</span></div>
            <div style={{ color: 'var(--color-neutral-400)' }}>{ar.scope === 'all' ? 'all groups' : ar.scope}</div>
            <div style={{ color: rr.c, fontWeight: 700 }}>{rr.m}</div>
            <div style={{ color: ww.c, fontWeight: 700 }}>{ww.m}</div>
            <div style={{ color: xx.c, fontWeight: 700 }}>{xx.m}</div>
            <div>
              {ar.username !== me?.username && (
                <button onClick={() => remove(ar.username)} style={{ border: '1px solid color-mix(in srgb, var(--color-neutral-100) 25%, transparent)', background: 'transparent', color: 'var(--color-neutral-400)', cursor: 'pointer', padding: '3px 8px', fontFamily: 'var(--font-heading)', fontWeight: 600, fontSize: 9, letterSpacing: '.1em' }}>DELETE</button>
              )}
            </div>
          </div>
        );
      })}

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3,1fr)', gap: 2, marginTop: 24 }}>
        {[
          ['SUPER ADMIN', 'All groups, rwx. Manages user accounts. Every action is attributed and logged.'],
          ['OPERATOR', 'Scoped to named groups. w = fetch jars from staging; x = execute stop / deploy / start.'],
          ['VIEWER', 'Read-only across services status, registry and history. Cannot fetch or deploy.'],
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
