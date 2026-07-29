import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../api/client';
import { useApp } from '../store/app';
import { C, rule1, rule2 } from '../theme/colors';
import type { PromotionView, StagingSource } from '../api/types';
import { PortalUi } from './PortalUi';

const mono = 'var(--mono)';

export function Promote() {
  const { me, flash } = useApp();
  const qc = useQueryClient();
  const [channel, setChannel] = useState<'jar' | 'portal-ui'>('jar');
  const [src, setSrc] = useState('staging-core');
  const [apps, setApps] = useState<string[]>([]);
  const [result, setResult] = useState<PromotionView[] | null>(null);

  const canFetch = !!me?.w;
  const { data: sources } = useQuery({ queryKey: ['staging'], queryFn: () => api.get<StagingSource[]>('/promotions/staging') });
  const { data: mine } = useQuery({ queryKey: ['promotions', 'mine'], queryFn: () => api.get<PromotionView[]>('/promotions/mine') });

  const source = sources?.find((s) => s.key === src);
  const toggle = (a: string) => setApps((p) => (p.includes(a) ? p.filter((x) => x !== a) : [...p, a]));

  const fetchMut = useMutation({
    mutationFn: () => api.post<PromotionView[]>('/promotions/fetch', { srcGroup: src, apps }),
    onSuccess: (created) => {
      setResult(created);
      setApps([]);
      qc.invalidateQueries({ queryKey: ['promotions'] });
      qc.invalidateQueries({ queryKey: ['approvals'] });
      flash(`Fetched ${created.length} jar(s) — approval requested, super admin emailed`);
    },
    onError: (e) => flash(e instanceof ApiError ? e.message : 'fetch failed', C.stop),
  });

  const fetchCmd = `./fetch.sh ${src} ${apps.join(',') || '<apps>'} --check-hash --record`;

  const channelSwitch = (
    <div style={{ display: 'flex', border: '1px solid color-mix(in srgb, var(--color-neutral-100) 30%, transparent)', flex: 'none' }}>
      {([['jar', 'JAR'], ['portal-ui', 'PORTAL UI']] as const).map(([c, label]) => (
        <button key={c} onClick={() => setChannel(c)} style={{ border: 0, cursor: 'pointer', padding: '6px 12px',
          fontFamily: 'var(--font-heading)', fontWeight: 700, fontSize: 9.5, letterSpacing: '.1em',
          background: channel === c ? 'var(--color-neutral-100)' : 'transparent', color: channel === c ? 'var(--color-text)' : 'var(--color-neutral-500)' }}>{label}</button>
      ))}
    </div>
  );

  if (channel === 'portal-ui') return (
    <>
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 14, padding: '20px 24px 0', flexWrap: 'wrap' }}>
        <h3 style={{ margin: 0, color: 'var(--color-neutral-100)' }}>Fetch from staging</h3>
        <div style={{ flex: 1 }} />
        {channelSwitch}
      </div>
      <PortalUi modes={['fetch', 'verify']} heading="Fetch portal UI from staging" subtitle="FETCH / VERIFY · MAPS TO portalui/run.sh" />
    </>
  );

  return (
    <main style={{ padding: '0 24px 56px', maxWidth: 1500 }}>
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 14, padding: '20px 0 14px', flexWrap: 'wrap' }}>
        <h3 style={{ margin: 0, color: 'var(--color-neutral-100)' }}>Promote a jar from staging</h3>
        <div style={{ fontFamily: 'var(--font-heading)', fontWeight: 600, fontSize: 10, letterSpacing: '.12em', color: 'var(--color-neutral-500)' }}>FETCH · MAPS TO ./fetch.sh</div>
        <div style={{ flex: 1 }} />
        {channelSwitch}
      </div>

      {!canFetch && (
        <div style={{ display: 'flex', gap: 10, alignItems: 'flex-start', padding: '14px 16px', borderLeft: '2px solid var(--color-accent)',
          background: 'color-mix(in srgb, var(--color-accent) 8%, transparent)', fontSize: 13, color: 'var(--color-neutral-200)' }}>
          <span style={{ width: 10, height: 10, background: 'var(--color-accent)', marginTop: 3, flex: 'none' }} />
          You are signed in with read-only access. Fetching a build from staging needs the write (w) permission. Ask a super admin to grant it.
        </div>
      )}

      {canFetch && (
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 420px', gap: 36, borderTop: rule2, paddingTop: 22, alignItems: 'start' }}>
          <div style={{ display: 'grid', gap: 26 }}>
            <div>
              <h6 style={{ color: 'var(--color-neutral-400)', margin: '0 0 8px' }}>1 — STAGING SOURCE</h6>
              <div style={{ display: 'flex', gap: 2 }}>
                {sources?.map((s) => (
                  <button key={s.key} onClick={() => { setSrc(s.key); setApps([]); }} style={{ border: '1px solid color-mix(in srgb, var(--color-neutral-100) 20%, transparent)',
                    background: src === s.key ? 'var(--color-neutral-100)' : 'transparent', color: src === s.key ? 'var(--color-text)' : 'var(--color-neutral-300)',
                    cursor: 'pointer', padding: '10px 14px', textAlign: 'left', fontFamily: mono, fontSize: 12, minWidth: 200 }}>
                    {s.key}<br /><span style={{ fontSize: 9.5, opacity: 0.65 }}>{s.host} · {s.ip}</span>
                  </button>
                ))}
              </div>
            </div>
            <div>
              <h6 style={{ color: 'var(--color-neutral-400)', margin: '0 0 8px' }}>2 — APPS <span style={{ fontWeight: 400, letterSpacing: 0, textTransform: 'none', color: 'var(--color-neutral-500)' }}>— built jars available on {source?.host}</span></h6>
              <div style={{ display: 'flex', gap: 2, flexWrap: 'wrap' }}>
                {source?.apps.map((a) => {
                  const on = apps.includes(a);
                  return <button key={a} onClick={() => toggle(a)} style={{ border: '1px solid color-mix(in srgb, var(--color-neutral-100) 20%, transparent)',
                    background: on ? 'var(--color-neutral-100)' : 'transparent', color: on ? 'var(--color-text)' : 'var(--color-neutral-300)',
                    cursor: 'pointer', padding: '7px 12px', fontFamily: mono, fontSize: 11.5 }}>{a}</button>;
                })}
              </div>
            </div>
          </div>

          <div style={{ display: 'grid', gap: 18, position: 'sticky', top: 20 }}>
            <div>
              <h6 style={{ color: 'var(--color-neutral-400)', margin: '0 0 6px' }}>COMMAND</h6>
              <div style={{ background: 'color-mix(in srgb, black 40%, var(--color-text))', padding: 14, fontFamily: mono, fontSize: 12.5, color: 'var(--color-neutral-100)', lineHeight: 1.5, wordBreak: 'break-all' }}>$ {fetchCmd}</div>
            </div>
            <div style={{ display: 'flex', gap: 10, alignItems: 'flex-start', padding: 12, borderLeft: `2px solid ${C.run}`, background: 'color-mix(in srgb, oklch(0.72 0.15 152) 8%, transparent)', fontSize: 12, color: 'var(--color-neutral-200)' }}>
              <span style={{ width: 10, height: 10, background: C.run, marginTop: 3, flex: 'none' }} />
              Fetching pulls the jar, reads its git hash, records it in registry-db, and emails the details to the super admin. It does <strong>not</strong> touch production — deploy stays locked until the request is approved.
            </div>
            <button onClick={() => fetchMut.mutate()} disabled={apps.length === 0 || fetchMut.isPending}
              style={{ border: 0, background: 'var(--color-neutral-100)', color: 'var(--color-text)', cursor: 'pointer', padding: '13px 18px',
                fontFamily: 'var(--font-heading)', fontWeight: 800, fontSize: 12, letterSpacing: '.1em', display: 'flex', justifyContent: 'flex-start',
                opacity: apps.length === 0 ? 0.5 : 1 }}>
              {fetchMut.isPending ? 'FETCHING…' : 'FETCH & REQUEST APPROVAL →'}
            </button>
          </div>
        </div>
      )}

      {result && (
        <div style={{ borderTop: rule2, paddingTop: 20, marginTop: 24, maxWidth: 1100, overflowX: 'auto' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 14 }}>
            <span style={{ width: 13, height: 13, background: C.run }} />
            <h4 style={{ margin: 0, color: 'var(--color-neutral-100)' }}>Fetched from staging</h4>
            <div style={{ fontSize: 12, color: 'var(--color-neutral-500)' }}>hash read from the staged build, shown against what production runs now.</div>
          </div>
          <FetchHeader />
          {result.map((pr) => <FetchRow key={pr.id} pr={pr} />)}
        </div>
      )}

      <section style={{ marginTop: 34, overflowX: 'auto' }}>
        <h6 style={{ color: 'var(--color-neutral-400)', margin: '0 0 6px' }}>FETCHED JARS — STAGING vs PRODUCTION</h6>
        <FetchHeader />
        {mine?.map((mr) => <FetchRow key={mr.id} pr={mr} />)}
        {mine?.length === 0 && <div style={{ padding: '10px 6px', fontSize: 12, color: 'var(--color-neutral-500)' }}>Nothing fetched yet.</div>}
      </section>
    </main>
  );
}

const FETCH_COLS = '84px 150px 1fr 120px 120px 130px';

function FetchHeader() {
  return (
    <div style={{ display: 'grid', gridTemplateColumns: FETCH_COLS, gap: 14, padding: '6px 8px', borderTop: rule2, borderBottom: rule1,
      fontFamily: 'var(--font-heading)', fontWeight: 600, fontSize: 9.5, letterSpacing: '.1em', color: 'var(--color-neutral-500)' }}>
      <div>REQ</div><div>APP → GROUP</div><div>JAR</div><div>STAGING HASH</div><div>PROD HASH</div><div>DEPLOYED?</div>
    </div>
  );
}

function FetchRow({ pr }: { pr: PromotionView }) {
  const same = pr.hash === pr.prodHash;
  return (
    <div style={{ display: 'grid', gridTemplateColumns: FETCH_COLS, gap: 14, padding: '11px 8px', borderBottom: rule1, alignItems: 'center', fontFamily: mono, fontSize: 11.5 }}>
      <div style={{ color: 'var(--color-accent-400)', fontWeight: 700 }}>{pr.id}</div>
      <div style={{ color: 'var(--color-neutral-100)' }}>{pr.app} <span style={{ color: 'var(--color-neutral-500)' }}>→ {pr.group}</span></div>
      <div style={{ color: 'var(--color-neutral-400)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{pr.jar}</div>
      <div style={{ color: same ? 'var(--color-neutral-300)' : '#22c55e', fontWeight: 700 }}>{pr.hash}</div>
      <div style={{ color: 'var(--color-neutral-300)' }}>{pr.prodHash}</div>
      <DeployedFlag deployed={pr.deployed} />
    </div>
  );
}

function DeployedFlag({ deployed }: { deployed: boolean }) {
  return deployed
    ? <div style={{ justifySelf: 'start', padding: '2px 8px', background: 'var(--color-neutral-100)', color: 'var(--color-text)', fontFamily: 'var(--font-heading)', fontWeight: 800, fontSize: 9, letterSpacing: '.1em' }}>DEPLOYED</div>
    : <div style={{ justifySelf: 'start', padding: '2px 8px', border: '1px solid color-mix(in srgb, var(--color-neutral-100) 30%, transparent)', color: 'var(--color-neutral-400)', fontFamily: 'var(--font-heading)', fontWeight: 800, fontSize: 9, letterSpacing: '.1em' }}>NOT DEPLOYED</div>;
}

export function StatusTag({ status }: { status: string }) {
  const map: Record<string, { bg: string; fg: string }> = {
    pending: { bg: C.warn, fg: C.inkOnAmber },
    approved: { bg: C.run, fg: C.inkOnGreen },
    denied: { bg: C.stop, fg: C.inkOnRed },
    deployed: { bg: 'var(--color-neutral-100)', fg: 'var(--color-text)' },
  };
  const s = map[status] ?? { bg: 'var(--color-neutral-700)', fg: 'var(--color-neutral-100)' };
  return <div style={{ justifySelf: 'start', padding: '2px 8px', background: s.bg, color: s.fg, fontFamily: 'var(--font-heading)', fontWeight: 800, fontSize: 9, letterSpacing: '.1em' }}>{status.toUpperCase()}</div>;
}
