import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';
import { C, rule1, rule2 } from '../theme/colors';
import type { StgHistoryRow } from '../api/types';

const mono = 'var(--mono)';
const COLS = '90px 150px 160px 1fr 150px 150px 90px';

/** What was done in staging — runs, portal-UI deploys and property edits — newest first. */
export function StgHistory() {
  const { data } = useQuery({ queryKey: ['stg', 'deployments'], queryFn: () => api.get<StgHistoryRow[]>('/stg/deployments'), refetchInterval: 15_000 });

  return (
    <>
      <div style={{ display: 'flex', gap: 10, alignItems: 'flex-start', padding: '11px 14px', margin: '4px 0 12px', background: 'var(--color-neutral-900)', fontSize: 12, color: 'var(--color-neutral-300)' }}>
        <span style={{ width: 9, height: 9, background: C.run, flex: 'none', marginTop: 3 }} />
        <span>Every staging action — stop / deploy / start runs, portal-UI deploys and <span style={{ fontFamily: mono }}>application.properties</span> edits — recorded to registry-db and the audit trail.</span>
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: COLS, gap: 12, padding: '6px 8px', borderTop: rule2, borderBottom: rule1, fontFamily: 'var(--font-heading)', fontWeight: 600, fontSize: 9.5, letterSpacing: '.1em', color: 'var(--color-neutral-500)' }}>
        <div>ID</div><div>CHANNEL</div><div>HOST</div><div>APPS / TARGET</div><div>ACTIONS</div><div>WHEN · BY</div><div>RESULT</div>
      </div>
      {(!data || data.length === 0) && (
        <div style={{ padding: '12px 8px', fontSize: 12, color: 'var(--color-neutral-500)' }}>Nothing done in staging yet.</div>
      )}
      {data?.map((r) => (
        <div key={r.id} style={{ display: 'grid', gridTemplateColumns: COLS, gap: 12, padding: '11px 8px', borderBottom: rule1, alignItems: 'center', fontFamily: mono, fontSize: 11.5 }}>
          <div style={{ color: 'var(--color-accent-400)', fontWeight: 700 }}>{r.id}</div>
          <div style={{ color: 'var(--color-neutral-300)' }}>{channelLabel(r.group)}</div>
          <div style={{ color: 'var(--color-neutral-400)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{r.host}</div>
          <div style={{ color: 'var(--color-neutral-100)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{r.apps}</div>
          <div style={{ color: 'var(--color-neutral-400)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{r.actions}</div>
          <div style={{ color: 'var(--color-neutral-400)' }}>{r.startedAt}<div style={{ fontSize: 9.5, color: 'var(--color-neutral-600)' }}>{r.startedBy}</div></div>
          <ResultTag result={r.result} />
        </div>
      ))}
    </>
  );
}

function channelLabel(group: string): string {
  if (group === 'stg-props') return 'properties';
  if (group === 'stg-portal-ui') return 'portal-ui';
  if (group?.startsWith('stg-')) return group.slice(4);
  return group;
}

function ResultTag({ result }: { result: string | null }) {
  const r = result ?? 'running';
  const map: Record<string, { bg: string; fg: string }> = {
    ok: { bg: C.run, fg: C.inkOnGreen },
    test: { bg: C.warn, fg: C.inkOnAmber },
    running: { bg: 'var(--color-neutral-700)', fg: 'var(--color-neutral-100)' },
    failed: { bg: C.stop, fg: C.inkOnRed },
  };
  const s = map[r] ?? { bg: 'var(--color-neutral-700)', fg: 'var(--color-neutral-100)' };
  return <div style={{ justifySelf: 'start', padding: '2px 8px', background: s.bg, color: s.fg, fontFamily: 'var(--font-heading)', fontWeight: 800, fontSize: 9, letterSpacing: '.1em' }}>{r.toUpperCase()}</div>;
}
