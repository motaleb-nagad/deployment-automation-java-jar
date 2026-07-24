import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';
import { rule1, rule2 } from '../theme/colors';
import { StatusTag } from './Promote';
import type { RegistryRow } from '../api/types';

const mono = 'var(--mono)';

export function Registry() {
  const { data } = useQuery({ queryKey: ['registry'], queryFn: () => api.get<RegistryRow[]>('/registry') });
  const cols = '150px 90px 1fr 120px 120px 130px 120px';

  return (
    <main style={{ padding: '0 24px 56px', overflowX: 'auto' }}>
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 14, padding: '20px 0 12px', flexWrap: 'wrap' }}>
        <h3 style={{ margin: 0, color: 'var(--color-neutral-100)' }}>Jar registry</h3>
        <div style={{ fontSize: 12.5, color: 'var(--color-neutral-500)' }}>Persistent record of every governed jar — current production hash and the latest promotion. Backed by registry-db (Postgres).</div>
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: cols, gap: 12, padding: '6px 8px', borderTop: rule2, borderBottom: rule1, fontFamily: 'var(--font-heading)', fontWeight: 600, fontSize: 9.5, letterSpacing: '.1em', color: 'var(--color-neutral-500)' }}>
        <div>APP</div><div>GROUP</div><div>JAR</div><div>PROD HASH</div><div>LATEST HASH</div><div>STATUS</div><div>BY</div>
      </div>
      {data?.map((rg) => (
        <div key={rg.app + rg.group} style={{ display: 'grid', gridTemplateColumns: cols, gap: 12, padding: '9px 8px', borderBottom: rule1, fontFamily: mono, fontSize: 11.5, alignItems: 'center' }}>
          <div style={{ color: 'var(--color-neutral-100)' }}>{rg.app}</div>
          <div style={{ color: 'var(--color-neutral-500)' }}>{rg.group}</div>
          <div style={{ color: 'var(--color-neutral-400)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{rg.jar}</div>
          <div style={{ color: 'var(--color-neutral-200)', fontWeight: 700 }}>{rg.prodHash}</div>
          <div style={{ color: 'var(--color-neutral-300)' }}>{rg.latestHash}</div>
          <StatusTag status={rg.latestStatus} />
          <div style={{ color: 'var(--color-neutral-500)' }}>{rg.latestBy}</div>
        </div>
      ))}
      <div style={{ marginTop: 22, paddingTop: 16, borderTop: rule2 }}>
        <h6 style={{ color: 'var(--color-neutral-400)', margin: '0 0 8px' }}>SCHEMA — registry-db · postgres 15</h6>
        <div style={{ fontFamily: mono, fontSize: 11.5, color: 'var(--color-neutral-400)', lineHeight: 1.7 }}>
          <div><span style={{ color: 'var(--color-neutral-200)' }}>promotion</span> ( id, app, group_name, src_host, jar, git_hash, prev_hash, branch, size, requested_by, requested_at, status, decided_by, decided_at, note )</div>
          <div><span style={{ color: 'var(--color-neutral-200)' }}>jar_registry</span> ( app, group_name, jar, prod_hash, updated_at, updated_by ) — current deployed truth per app</div>
          <div><span style={{ color: 'var(--color-neutral-200)' }}>deployment</span> ( id, promotion_id, group_name, hosts, apps, actions, started_by, started_at, duration, result, before_after, log_excerpt )</div>
          <div><span style={{ color: 'var(--color-neutral-200)' }}>app_user</span> ( username, name, email, role, scope, perm_r, perm_w, perm_x )</div>
          <div><span style={{ color: 'var(--color-neutral-200)' }}>audit_log</span> ( ts, actor, verb, target, detail ) — append-only, DB-enforced</div>
        </div>
      </div>
    </main>
  );
}
