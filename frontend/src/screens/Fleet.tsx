import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';
import { useApp } from '../store/app';
import { C, rule1, rule2 } from '../theme/colors';
import type { FleetView, GroupView, ServiceCell } from '../api/types';

const mono = 'var(--mono)';

export function Fleet() {
  const { scenario, openDetail } = useApp();
  const [search, setSearch] = useState('');
  const [filter, setFilter] = useState<'all' | 'problems' | 'down' | 'drift'>('all');
  const [expanded, setExpanded] = useState<Record<string, boolean>>({ 'nagad-app': true, 'nagad-ussd': true });

  const { data, isLoading } = useQuery({
    queryKey: ['fleet', scenario],
    queryFn: () => api.get<FleetView>(`/fleet?scenario=${scenario}`),
  });

  const stats = useMemo(() => {
    if (!data) return [];
    return [
      { label: 'services down', v: data.servicesDown, fg: data.servicesDown ? C.stop : 'var(--color-neutral-100)', sub: data.servicesDown ? 'expected running — act now' : 'all expected services up' },
      { label: 'hash drift', v: data.driftGroups, fg: data.driftGroups ? C.warn : 'var(--color-neutral-100)', sub: data.driftGroups ? 'nagad-app · apigw' : 'groups internally consistent' },
      { label: 'recent restarts', v: data.restarts, fg: data.restarts ? C.warn : 'var(--color-neutral-100)', sub: data.restarts ? 'low uptime vs peers' : 'uptimes nominal' },
      { label: 'unknown status', v: data.unknowns, fg: data.unknowns ? 'var(--color-neutral-400)' : 'var(--color-neutral-100)', sub: data.unknowns ? 'collector read failed' : 'all hosts reporting' },
    ];
  }, [data]);

  if (isLoading || !data) return <main style={{ padding: 24, color: 'var(--color-neutral-500)' }}>loading fleet…</main>;

  const q = search.trim().toLowerCase();
  const matches = (g: GroupView) => !q || g.key.includes(q) || g.hosts.some((h) => h.includes(q)) || g.cells.some((c) => c.svc.includes(q));
  const passFilter = (g: GroupView) =>
    filter === 'all' ? true :
    filter === 'problems' ? g.issues.length > 0 :
    g.issues.includes(filter);

  const fleetMeta = `${data.hostsTotal} hosts · ${data.servicesTotal} services · ${data.instancesTotal} instances`;
  const zones = ['DC', 'DMZ', 'STG'];

  return (
    <main style={{ padding: '0 24px 48px' }}>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4,minmax(170px,200px)) minmax(240px,1fr)', gap: 24,
        padding: '20px 0', borderBottom: rule2, alignItems: 'end' }}>
        {stats.map((s) => (
          <div key={s.label} style={{ borderLeft: '2px solid color-mix(in srgb, var(--color-neutral-100) 30%, transparent)', paddingLeft: 14 }}>
            <div style={{ fontFamily: 'var(--font-heading)', fontWeight: 600, fontSize: 10, letterSpacing: '.12em', textTransform: 'uppercase', color: 'var(--color-neutral-500)' }}>{s.label}</div>
            <div style={{ fontFamily: 'var(--font-heading)', fontWeight: 800, fontSize: 34, lineHeight: 1.05, color: s.fg, marginTop: 4 }}>{s.v}</div>
            <div style={{ fontSize: 11, color: 'var(--color-neutral-500)', marginTop: 2 }}>{s.sub}</div>
          </div>
        ))}
        <div style={{ textAlign: 'right', fontFamily: mono, fontSize: 11, color: 'var(--color-neutral-500)', paddingBottom: 4 }}>
          {fleetMeta}<br />collector interval 3 min · collected {data.collectedAt} · {data.age}
        </div>
      </div>

      <div style={{ display: 'flex', alignItems: 'center', gap: 14, padding: '12px 0', borderBottom: rule1 }}>
        <input value={search} onChange={(e) => setSearch(e.target.value)} placeholder="filter: group, host, service…"
          style={{ width: 280, background: 'var(--color-neutral-900)', border: '1px solid color-mix(in srgb, var(--color-neutral-100) 25%, transparent)', color: 'var(--color-neutral-100)', padding: '7px 10px', fontFamily: mono, fontSize: 12 }} />
        <div style={{ display: 'flex', border: '1px solid color-mix(in srgb, var(--color-neutral-100) 25%, transparent)' }}>
          {(['all', 'problems', 'down', 'drift'] as const).map((f) => (
            <button key={f} onClick={() => setFilter(f)} style={{ border: 0, cursor: 'pointer', padding: '6px 12px',
              fontFamily: 'var(--font-heading)', fontWeight: 600, fontSize: 10, letterSpacing: '.1em',
              background: filter === f ? 'var(--color-neutral-100)' : 'transparent',
              color: filter === f ? 'var(--color-text)' : 'var(--color-neutral-500)' }}>{f.toUpperCase()}</button>
          ))}
        </div>
        <div style={{ flex: 1 }} />
        <Legend />
      </div>

      {data.attention.length > 0 && (
        <section style={{ marginTop: 22 }}>
          <h6 style={{ color: 'var(--color-neutral-400)', margin: '0 0 6px' }}>NEEDS ATTENTION — {data.attention.length}</h6>
          <div style={{ borderTop: rule2 }}>
            {data.attention.map((a, i) => {
              const bg = a.kind === 'DOWN' ? C.stop : a.kind === 'UNKNOWN' ? C.unk : C.warn;
              return (
                <button key={i} onClick={() => openDetail(a.group, a.svc, a.host)} style={{ display: 'grid',
                  gridTemplateColumns: '12px 90px 170px 210px 1fr auto', gap: 16, alignItems: 'center', width: '100%',
                  textAlign: 'left', padding: '10px 4px', background: 'transparent', border: 0, borderBottom: rule1,
                  cursor: 'pointer', color: 'inherit', font: 'inherit', fontSize: 12.5 }}>
                  <div style={{ width: 12, height: 12, background: bg }} />
                  <div style={{ fontFamily: 'var(--font-heading)', fontWeight: 800, fontSize: 10.5, letterSpacing: '.12em', color: bg }}>{a.kind}</div>
                  <div style={{ fontFamily: mono, fontSize: 12.5, color: 'var(--color-neutral-100)' }}>{a.title}</div>
                  <div style={{ fontFamily: mono, fontSize: 11.5, color: 'var(--color-neutral-400)' }}>{a.sub}</div>
                  <div style={{ color: 'var(--color-neutral-400)', fontSize: 12 }}>{a.desc}</div>
                  <div style={{ fontFamily: 'var(--font-heading)', fontWeight: 600, fontSize: 10, letterSpacing: '.12em', color: 'var(--color-neutral-500)' }}>OPEN →</div>
                </button>
              );
            })}
          </div>
        </section>
      )}
      {!data.incident && (
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginTop: 22, padding: '14px 16px', background: 'var(--color-neutral-900)' }}>
          <div style={{ width: 12, height: 12, background: C.run }} />
          <div style={{ fontSize: 13, color: 'var(--color-neutral-200)' }}>All clear — {fleetMeta}. No services down, no hash drift, no unexpected restarts.</div>
        </div>
      )}

      {zones.map((zn) => {
        const gs = data.groups.filter((g) => g.zone === zn && matches(g) && passFilter(g));
        if (gs.length === 0) return null;
        return (
          <section key={zn} style={{ marginTop: 28 }}>
            <div style={{ display: 'flex', alignItems: 'baseline', gap: 12, borderBottom: rule2, paddingBottom: 6 }}>
              <h6 style={{ color: 'var(--color-neutral-100)', margin: 0 }}>{zoneLabel(zn)}</h6>
              <div style={{ fontFamily: mono, fontSize: 11, color: 'var(--color-neutral-500)' }}>{gs.length} groups</div>
            </div>
            {gs.map((g) => (
              <GroupRow key={g.key} g={g} expanded={!!expanded[g.key]}
                toggle={() => setExpanded((e) => ({ ...e, [g.key]: !e[g.key] }))}
                openDetail={openDetail} />
            ))}
          </section>
        );
      })}
    </main>
  );
}

function GroupRow({ g, expanded, toggle, openDetail }: {
  g: GroupView; expanded: boolean; toggle: () => void;
  openDetail: (group: string, svc: string, host: string | null) => void;
}) {
  const worstBg = g.issues.includes('down') ? C.stop
    : g.issues.includes('drift') || g.issues.includes('restart') ? C.warn
    : g.issues.includes('unknown') ? C.unk : C.run;
  const cur: Record<string, string> = {};
  g.cells.forEach((c) => { if (!(c.svc in cur)) cur[c.svc] = c.hash; });
  const svcKeys = Array.from(new Set(g.cells.map((c) => c.svc)));

  const hostChip = (h: string) => {
    const cells = g.cells.filter((c) => c.host === h);
    let stt = 'running';
    for (const c of cells) { if (c.status === 'stopped') { stt = 'stopped'; break; } if (c.status === 'unknown') stt = 'unknown'; }
    if (stt === 'running' && cells.some((c) => c.hash !== cur[c.svc] || c.uptime === '0d 00:41')) stt = 'warn';
    const bg = stt === 'stopped' ? C.stop : stt === 'warn' ? C.warn : stt === 'unknown' ? 'transparent' : C.runTint;
    const fg = stt === 'stopped' ? C.inkOnRed : stt === 'warn' ? C.inkOnAmber : stt === 'unknown' ? 'var(--color-neutral-500)' : C.runFg;
    return { h, bg, fg, bd: stt === 'unknown' ? '1px solid var(--color-neutral-700)' : '1px solid transparent' };
  };

  const gridCols = `170px repeat(${g.hosts.length}, minmax(40px,1fr))`;

  return (
    <div style={{ borderBottom: rule1 }}>
      <button onClick={toggle} style={{ display: 'grid', gridTemplateColumns: '14px 220px 170px 1fr 150px 110px 18px',
        gap: 14, alignItems: 'center', width: '100%', textAlign: 'left', padding: '9px 4px', background: 'transparent',
        border: 0, cursor: 'pointer', color: 'inherit', font: 'inherit', fontSize: 12.5 }}>
        <div style={{ width: 12, height: 12, background: worstBg }} />
        <div style={{ fontFamily: mono, fontSize: 12.5, color: 'var(--color-neutral-100)' }}>{g.key}</div>
        <div style={{ fontSize: 11, color: 'var(--color-neutral-500)' }}>{g.tier}</div>
        <div style={{ display: 'flex', gap: 3, flexWrap: 'wrap' }}>
          {g.hosts.map((h) => { const c = hostChip(h); return (
            <div key={h} title={h} style={{ height: 17, padding: '0 6px', display: 'flex', alignItems: 'center',
              fontFamily: mono, fontSize: 9.5, background: c.bg, color: c.fg, border: c.bd }}>{h}</div>
          ); })}
        </div>
        <div style={{ textAlign: 'right', fontFamily: mono, fontSize: 11, color: 'var(--color-neutral-500)' }}>{svcKeys.length} svc · {g.hosts.length} hosts</div>
        <div style={{ textAlign: 'right', fontFamily: 'var(--font-heading)', fontWeight: 600, fontSize: 10, letterSpacing: '.08em', color: g.issues.length ? C.warn : 'var(--color-neutral-600)' }}>
          {g.issues.length ? Array.from(new Set(g.issues)).join(' · ') : ''}
        </div>
        <div style={{ color: 'var(--color-neutral-500)', fontSize: 15, textAlign: 'center' }}>{expanded ? '▾' : '▸'}</div>
      </button>
      {expanded && svcKeys.length > 0 && (
        <div style={{ padding: '12px 4px 18px 32px', background: 'color-mix(in srgb, black 25%, var(--color-text))' }}>
          <div style={{ display: 'grid', gridTemplateColumns: gridCols, gap: 2, marginBottom: 4 }}>
            <div />
            {g.hosts.map((h) => <div key={h} style={{ fontFamily: mono, fontSize: 9.5, color: 'var(--color-neutral-500)', padding: '0 2px' }}>{h}</div>)}
          </div>
          {svcKeys.map((svc) => (
            <div key={svc} style={{ display: 'grid', gridTemplateColumns: gridCols, gap: 2, marginTop: 2, alignItems: 'center' }}>
              <div style={{ fontFamily: mono, fontSize: 11, color: 'var(--color-neutral-300)', paddingRight: 10, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{svc}</div>
              {g.hosts.map((h) => {
                const c = g.cells.find((x) => x.svc === svc && x.host === h) as ServiceCell;
                return <Cell key={h} c={c} cur={cur[svc]} onOpen={() => openDetail(g.key, svc, h)} />;
              })}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function Cell({ c, cur, onOpen }: { c: ServiceCell; cur: string; onOpen: () => void }) {
  let bg: string = C.runTint, fg: string = C.runFg, label = '', bd = '0';
  if (c.status === 'stopped') { bg = C.stop; fg = C.inkOnRed; label = 'DOWN'; }
  else if (c.status === 'unknown') { bg = 'transparent'; fg = 'var(--color-neutral-500)'; label = '?'; bd = '1px solid var(--color-neutral-700)'; }
  else if (c.hash !== cur) { bg = C.warn; fg = C.inkOnAmber; label = c.hash; }
  if (c.uptime === '0d 00:41') { bg = C.warn; fg = C.inkOnAmber; label = '0d 00h'; }
  return (
    <button onClick={onOpen} title={`${c.host} · ${c.svc} · ${c.status} · ${c.hash}`} style={{ height: 20, border: bd,
      cursor: 'pointer', background: bg, color: fg, fontFamily: mono, fontSize: 9, display: 'flex',
      alignItems: 'center', justifyContent: 'center', padding: 0 }}>{label}</button>
  );
}

function Legend() {
  const item = (box: React.CSSProperties, label: string) => (
    <div style={{ display: 'flex', alignItems: 'center', gap: 5 }}><div style={{ width: 10, height: 10, ...box }} />{label}</div>
  );
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 14, fontSize: 10.5, color: 'var(--color-neutral-500)' }}>
      {item({ background: C.runTint, border: `1px solid ${C.run}` }, 'running')}
      {item({ background: C.stop }, 'stopped')}
      {item({ background: C.warn }, 'drift / restart')}
      {item({ border: '1px solid var(--color-neutral-600)' }, 'unknown')}
    </div>
  );
}

const zoneLabel = (z: string) => (z === 'DC' ? 'DATACENTRE — DC' : z === 'DMZ' ? 'DMZ SEGMENT' : 'STAGING');
