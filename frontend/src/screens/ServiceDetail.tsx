import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';
import { useApp } from '../store/app';
import { C, rule1, rule2 } from '../theme/colors';
import type { FleetView } from '../api/types';

const mono = 'var(--mono)';

export function ServiceDetail() {
  const { detail, scenario, go } = useApp();
  const { data } = useQuery({ queryKey: ['fleet', scenario], queryFn: () => api.get<FleetView>(`/fleet?scenario=${scenario}`) });

  if (!detail || !data) return <main style={{ padding: 24 }}><button onClick={() => go('fleet')} style={backBtn}>← FLEET</button></main>;

  const group = data.groups.find((g) => g.key === detail.group);
  const cells = group?.cells.filter((c) => c.svc === detail.svc) ?? [];
  if (!group || cells.length === 0) return <main style={{ padding: 24 }}><button onClick={() => go('fleet')} style={backBtn}>← FLEET</button><div style={{ marginTop: 16, color: 'var(--color-neutral-500)' }}>Service not found.</div></main>;

  // Majority hash = current expected.
  const counts: Record<string, number> = {};
  cells.forEach((c) => { counts[c.hash] = (counts[c.hash] ?? 0) + 1; });
  const curHash = Object.entries(counts).sort((a, b) => b[1] - a[1])[0][0];
  const jar = cells[0].jar;
  const driftHosts = cells.filter((c) => c.hash !== curHash).map((c) => c.host);

  const rollbacks = [
    { f: `${jar}.1753257821~`, d: '22 Jul 2026 22:58', h: curHash },
    { f: `${jar}.bkp.17062026`, d: '17 Jun 2026', h: 'manual' },
  ];

  return (
    <main style={{ padding: '0 24px 48px', overflowX: 'auto' }}>
      <button onClick={() => go('fleet')} style={{ ...backBtn, padding: '16px 0 0' }}>← FLEET</button>
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 16, padding: '10px 0 16px', borderBottom: rule2 }}>
        <h2 style={{ margin: 0, fontFamily: mono, fontWeight: 700, fontSize: 28, color: 'var(--color-neutral-100)' }}>{detail.svc}</h2>
        <div style={{ fontFamily: mono, fontSize: 12, color: 'var(--color-neutral-400)' }}>{detail.group} · {jar}</div>
        <div style={{ flex: 1 }} />
        <button onClick={() => go('deploy')} style={{ border: 0, background: 'var(--color-accent)', color: 'var(--color-neutral-100)',
          cursor: 'pointer', padding: '9px 16px', fontFamily: 'var(--font-heading)', fontWeight: 800, fontSize: 11, letterSpacing: '.1em' }}>STAGE ACTION →</button>
      </div>

      {driftHosts.length > 0 && (
        <div style={{ display: 'flex', gap: 10, alignItems: 'flex-start', marginTop: 14, padding: '10px 12px',
          borderLeft: `2px solid ${C.warn}`, background: 'color-mix(in srgb, oklch(0.8 0.14 82) 8%, transparent)', fontSize: 12.5, color: 'var(--color-neutral-200)' }}>
          <div style={{ width: 10, height: 10, background: C.warn, marginTop: 3, flex: 'none' }} />
          <div>Hash drift: {driftHosts.join(', ')} differ from the group majority ({curHash}). A deploy half-completed — reconcile before the next release.</div>
        </div>
      )}

      <div style={{ marginTop: 20 }}>
        <h6 style={{ color: 'var(--color-neutral-400)', margin: '0 0 4px' }}>PER HOST — GROUP {detail.group}</h6>
        <div style={{ display: 'grid', gridTemplateColumns: '90px 90px 70px 90px 120px 110px 120px 1fr', gap: 10, padding: '6px 8px',
          borderTop: rule2, borderBottom: rule1, fontFamily: 'var(--font-heading)', fontWeight: 600, fontSize: 9.5, letterSpacing: '.1em', color: 'var(--color-neutral-500)' }}>
          <div>HOST</div><div>STATUS</div><div>PID</div><div>UPTIME</div><div>INSTANCES</div><div>HASH</div><div>IP</div><div>LAST DEPLOYED</div>
        </div>
        {cells.map((r) => {
          const behind = r.hash !== curHash;
          const stFg = r.status === 'stopped' ? C.stop : r.status === 'unknown' ? 'var(--color-neutral-500)' : C.runFg;
          return (
            <div key={r.host} style={{ display: 'grid', gridTemplateColumns: '90px 90px 70px 90px 120px 110px 120px 1fr', gap: 10, padding: 8,
              borderBottom: rule1, borderLeft: behind ? `2px solid ${C.warn}` : '2px solid transparent', fontFamily: mono, fontSize: 11.5, alignItems: 'center' }}>
              <div style={{ color: 'var(--color-neutral-100)' }}>{r.host}</div>
              <div style={{ color: stFg, fontWeight: 700 }}>{r.status}</div>
              <div style={{ color: 'var(--color-neutral-400)' }}>{r.status === 'stopped' ? '—' : r.pid}</div>
              <div style={{ color: r.uptime === '0d 00:41' ? C.warn : 'var(--color-neutral-300)' }}>{r.uptime}</div>
              <div style={{ color: 'var(--color-neutral-400)' }}>{r.instances > 1 ? `INST_1..${r.instances}` : 'single'}</div>
              <div style={{ color: behind ? C.warn : 'var(--color-neutral-300)', fontWeight: 700 }}>{r.hash} {behind && <span style={{ fontSize: 9.5 }}>← behind</span>}</div>
              <div style={{ color: 'var(--color-neutral-400)' }}>{r.ip}</div>
              <div style={{ color: 'var(--color-neutral-500)' }}>{r.lastDeployed}</div>
            </div>
          );
        })}
        <div style={{ marginTop: 8, fontSize: 11, color: 'var(--color-neutral-500)' }}>
          Expected hash <span style={{ fontFamily: mono, color: 'var(--color-neutral-300)' }}>{curHash}</span> — majority of group. Rows marked <span style={{ color: C.warn }}>← behind</span> differ.
        </div>
      </div>

      <div style={{ marginTop: 24, maxWidth: 420, background: 'var(--color-neutral-900)', padding: 16 }}>
        <h6 style={{ color: 'var(--color-neutral-400)', margin: '0 0 10px' }}>ROLLBACK TARGETS</h6>
        {rollbacks.map((rb) => (
          <div key={rb.f} style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '8px 0', borderBottom: rule1 }}>
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ fontFamily: mono, fontSize: 11, color: 'var(--color-neutral-200)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{rb.f}</div>
              <div style={{ fontFamily: mono, fontSize: 10, color: 'var(--color-neutral-500)' }}>{rb.d} · {rb.h}</div>
            </div>
          </div>
        ))}
        <div style={{ fontSize: 10.5, color: 'var(--color-neutral-500)', marginTop: 8 }}>
          Ansible deploy keeps the previous jar as <span style={{ fontFamily: mono }}>&lt;jar&gt;.&lt;timestamp&gt;~</span> in <span style={{ fontFamily: mono }}>/home/&lt;user&gt;/was/</span>.
        </div>
      </div>
    </main>
  );
}

const backBtn: React.CSSProperties = {
  border: 0, background: 'transparent', color: 'var(--color-neutral-400)', cursor: 'pointer',
  fontFamily: 'var(--font-heading)', fontWeight: 600, fontSize: 10.5, letterSpacing: '.12em', padding: 0,
};
