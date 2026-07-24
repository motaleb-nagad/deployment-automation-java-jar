import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';
import { C, rule1, rule2 } from '../theme/colors';
import type { DeploymentView } from '../api/types';

const mono = 'var(--mono)';

export function History() {
  const [q, setQ] = useState('');
  const [open, setOpen] = useState<string | null>(null);
  const { data } = useQuery({ queryKey: ['deployments'], queryFn: () => api.get<DeploymentView[]>('/deployments') });

  const rows = useMemo(() => {
    const ql = q.trim().toLowerCase();
    return (data ?? []).filter((r) => !ql || [r.startedBy, r.group, r.apps, r.actions, r.result].join(' ').toLowerCase().includes(ql));
  }, [data, q]);

  const cols = '150px 100px 110px 130px 170px 150px 90px 80px 20px';
  const outFg = (o: string | null) => (o === 'ok' ? C.runFg : o === 'failed' ? C.stop : C.warn);

  return (
    <main style={{ padding: '0 24px 48px', overflowX: 'auto' }}>
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 16, padding: '20px 0 12px' }}>
        <h3 style={{ margin: 0, color: 'var(--color-neutral-100)' }}>History</h3>
        <div style={{ fontSize: 12, color: 'var(--color-neutral-500)' }}>Every action ever run — the audit trail.</div>
        <div style={{ flex: 1 }} />
        <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="filter: user, group, app, outcome…"
          style={{ width: 300, background: 'var(--color-neutral-900)', border: '1px solid color-mix(in srgb, var(--color-neutral-100) 25%, transparent)', color: 'var(--color-neutral-100)', padding: '7px 10px', fontFamily: mono, fontSize: 12 }} />
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: cols, gap: 12, padding: '6px 8px', borderTop: rule2, borderBottom: rule1, fontFamily: 'var(--font-heading)', fontWeight: 600, fontSize: 9.5, letterSpacing: '.1em', color: 'var(--color-neutral-500)' }}>
        <div>WHEN</div><div>WHO</div><div>GROUP</div><div>HOSTS</div><div>APPS</div><div>ACTIONS</div><div>OUTCOME</div><div>DURATION</div><div />
      </div>
      {rows.map((hr) => {
        const isOpen = open === hr.id;
        return (
          <div key={hr.id} style={{ borderBottom: rule1 }}>
            <button onClick={() => setOpen(isOpen ? null : hr.id)} style={{ display: 'grid', gridTemplateColumns: cols, gap: 12, width: '100%', textAlign: 'left', padding: '9px 8px', background: 'transparent', border: 0, cursor: 'pointer', color: 'inherit', fontFamily: mono, fontSize: 11.5, alignItems: 'center' }}>
              <div style={{ color: 'var(--color-neutral-400)' }}>{hr.startedAt}</div>
              <div style={{ color: 'var(--color-neutral-200)' }}>{hr.startedBy}</div>
              <div style={{ color: 'var(--color-neutral-200)' }}>{hr.group}</div>
              <div style={{ color: 'var(--color-neutral-400)' }}>{hr.hosts}</div>
              <div style={{ color: 'var(--color-neutral-200)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{hr.apps}</div>
              <div style={{ color: 'var(--color-neutral-400)' }}>{hr.actions}</div>
              <div style={{ color: outFg(hr.result), fontWeight: 700 }}>{hr.result}</div>
              <div style={{ color: 'var(--color-neutral-500)' }}>{hr.duration}</div>
              <div style={{ color: 'var(--color-neutral-500)', fontSize: 14 }}>{isOpen ? '▾' : '▸'}</div>
            </button>
            {isOpen && (
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 24, padding: '12px 8px 18px 32px', background: 'color-mix(in srgb, black 25%, var(--color-text))' }}>
                <div>
                  <h6 style={{ color: 'var(--color-neutral-400)', margin: '0 0 6px' }}>BEFORE / AFTER</h6>
                  <div style={{ fontFamily: mono, fontSize: 11.5, color: 'var(--color-neutral-300)', padding: '3px 0' }}>{hr.beforeAfter ?? '—'}</div>
                </div>
                <div>
                  <h6 style={{ color: 'var(--color-neutral-400)', margin: '0 0 6px' }}>LOG EXCERPT</h6>
                  <div style={{ background: 'color-mix(in srgb, black 45%, var(--color-text))', padding: 10 }}>
                    <div style={{ fontFamily: mono, fontSize: 11, color: 'var(--color-neutral-400)', lineHeight: 1.5 }}>{hr.logExcerpt ?? '—'}</div>
                  </div>
                </div>
              </div>
            )}
          </div>
        );
      })}
    </main>
  );
}
