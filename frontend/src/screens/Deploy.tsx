import { useEffect, useMemo, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError, openDeployStream, type ResultRow } from '../api/client';
import { useApp } from '../store/app';
import { C, rule1, rule2, TERM } from '../theme/colors';
import type { DeployGroup, DeployPair, ConsolidatedPairView } from '../api/types';

const mono = 'var(--mono)';
const MULTI_INSTANCE = new Set(['apigw', 'apigw-summary']);
type Step = 'build' | 'confirm' | 'running' | 'result';
type Mode = 'group' | 'consolidated';
interface TermLine { level: string; text: string; }

/** A row in the review step: one service, its actions, and (deploy only) the hash change. */
interface ReviewRow { service: string; prodHash: string; targetHash: string | null; approved: boolean; }

export function Deploy() {
  const { me, flash } = useApp();
  const qc = useQueryClient();
  const { data: groups } = useQuery({ queryKey: ['deploy', 'groups'], queryFn: () => api.get<DeployGroup[]>('/deploy/groups') });

  const [mode, setMode] = useState<Mode>('group');
  const [step, setStep] = useState<Step>('build');
  const [groupKey, setGroupKey] = useState<string>('');
  const [hosts, setHosts] = useState<string[]>([]);
  const [apps, setApps] = useState<string[]>([]);
  const [actions, setActions] = useState<string[]>(['stop', 'deploy', 'start']);
  const [typed, setTyped] = useState('');
  const [lines, setLines] = useState<TermLine[]>([]);
  const [rail, setRail] = useState<Record<string, Record<string, string>>>({});
  const [result, setResult] = useState<ResultRow[]>([]);
  const [done, setDone] = useState(false);
  const termRef = useRef<HTMLDivElement>(null);

  // ---- consolidated (mixed-group) state ----
  const [pairs, setPairs] = useState<DeployPair[]>([]);
  const [cGroupKey, setCGroupKey] = useState<string>('');
  const [cHost, setCHost] = useState('');
  const [cApp, setCApp] = useState('');
  const [pairReview, setPairReview] = useState<ConsolidatedPairView[]>([]);

  const scopeList = me?.scope === 'all' ? null : me?.scope?.split(',').map((x) => x.trim()) ?? [];
  const scopedGroups = useMemo(() => (groups ?? []).filter((g) => !scopeList || scopeList.includes(g.cmd)), [groups, me]); // eslint-disable-line

  const group = useMemo(() => groups?.find((g) => g.key === groupKey) ?? groups?.[0], [groups, groupKey]);
  const cGroup = useMemo(() => scopedGroups.find((g) => g.key === cGroupKey) ?? scopedGroups[0], [scopedGroups, cGroupKey]);

  useEffect(() => { if (group && hosts.length === 0 && step === 'build') setHosts(group.hosts); }, [group]); // eslint-disable-line
  useEffect(() => { if (termRef.current) termRef.current.scrollTop = termRef.current.scrollHeight; }, [lines]);

  if (!groups || !group) return <main style={{ padding: 24, color: 'var(--color-neutral-500)' }}>loading…</main>;

  const scoped = me?.scope === 'all' || me?.scope?.split(',').map((x) => x.trim()).includes(group.cmd);
  const isDeploy = actions.includes('deploy');
  const sharedNoStop = isDeploy && !actions.includes('stop') && apps.some((a) => MULTI_INSTANCE.has(a));

  const hostExpr = hosts.length === 0 ? 'all' : hosts.length === 1 ? hosts[0] : `${hosts[0]}..${hosts[hosts.length - 1]}`;
  const fullCmd = `./run.sh ${group.cmd} ${hostExpr} ${apps.join(',') || '<apps>'} ${actions.join(',')}`;

  // consolidated derived
  const pairTokens = pairs.map((p) => `${p.host}:${p.app}`).join(' ');
  const consolidatedCmd = `./deploy.sh "${pairTokens || '<host:app …>'}" ${actions.join(',')}`;
  const distinctPairHosts = [...new Set(pairs.map((p) => p.host))];

  const canReview = mode === 'group'
    ? (apps.length > 0 && actions.length > 0 && hosts.length > 0 && scoped)
    : (pairs.length > 0 && actions.length > 0);

  const railHosts = mode === 'group' ? hosts : distinctPairHosts;
  const needType = railHosts.length > 1;
  const armToken = mode === 'group' ? group.cmd : 'deploy';
  const canExec = !!me?.x && (!needType || typed.trim() === armToken);

  const toggle = <T,>(arr: T[], v: T): T[] => (arr.includes(v) ? arr.filter((x) => x !== v) : [...arr, v]);

  function reset() {
    setStep('build'); setApps([]); setActions(['stop', 'deploy', 'start']); setTyped('');
    setLines([]); setRail({}); setResult([]); setDone(false); setPairs([]); setPairReview([]);
  }

  function addPair() {
    if (!cHost || !cApp) return;
    if (pairs.some((p) => p.host === cHost && p.app === cApp)) return;
    setPairs((p) => [...p, { host: cHost, app: cApp }]);
  }

  // Build the review rows for the confirm step.
  const reviewRows: ReviewRow[] = mode === 'group'
    ? apps.map((a) => {
        const meta = group.apps.find((x) => x.key === a);
        return { service: a, prodHash: meta?.prodHash ?? '—', targetHash: meta?.approvedHash ?? null, approved: !!meta?.approved };
      })
    : pairReview.map((p) => ({ service: `${p.app} @ ${p.host}`, prodHash: p.prodHash, targetHash: p.targetHash, approved: p.approved }));

  async function goConfirm() {
    if (mode === 'consolidated') {
      try {
        const resolved = await api.post<ConsolidatedPairView[]>('/deploy/consolidated/resolve', { pairs, actions });
        setPairReview(resolved);
      } catch (e) {
        flash(e instanceof ApiError ? e.message : 'could not resolve pairs', C.stop); return;
      }
    }
    setTyped(''); setStep('confirm');
  }

  async function execute() {
    setStep('running'); setLines([]); setRail({}); setDone(false);
    try {
      const body = mode === 'group'
        ? { group: group!.cmd, hosts, apps, actions }
        : { pairs, actions };
      const path = mode === 'group' ? '/deploy' : '/deploy/consolidated';
      const { streamTicket } = await api.post<{ deploymentId: string; streamTicket: string }>(path, body);
      openDeployStream(streamTicket, {
        onLine: (l) => setLines((p) => [...p, l]),
        onHost: (h) => setRail((p) => ({ ...p, [h.host]: { ...(p[h.host] ?? {}), [h.action]: h.state } })),
        onComplete: (c) => { setResult(c.rows); setDone(true); qc.invalidateQueries(); },
        onError: (m) => { flash(m, C.stop); setDone(true); },
      });
    } catch (e) {
      flash(e instanceof ApiError ? e.message : 'deploy failed', C.stop);
      setStep('confirm');
    }
  }

  const modeSwitch = (
    <div style={{ display: 'flex', border: '1px solid color-mix(in srgb, var(--color-neutral-100) 30%, transparent)', flex: 'none' }}>
      {([['group', 'PER-GROUP'], ['consolidated', 'MIXED-GROUP']] as const).map(([m, label]) => (
        <button key={m} onClick={() => { setMode(m); setStep('build'); }} style={{ border: 0, cursor: 'pointer', padding: '6px 12px',
          fontFamily: 'var(--font-heading)', fontWeight: 700, fontSize: 9.5, letterSpacing: '.1em',
          background: mode === m ? 'var(--color-neutral-100)' : 'transparent', color: mode === m ? 'var(--color-text)' : 'var(--color-neutral-500)' }}>{label}</button>
      ))}
    </div>
  );

  // ---------- BUILD ----------
  if (step === 'build') return (
    <main style={{ padding: '0 24px 48px', maxWidth: 1500 }}>
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 14, padding: '20px 0 14px', flexWrap: 'wrap' }}>
        <h3 style={{ margin: 0, color: 'var(--color-neutral-100)' }}>Deploy to production</h3>
        <div style={{ fontFamily: 'var(--font-heading)', fontWeight: 600, fontSize: 10, letterSpacing: '.12em', color: 'var(--color-neutral-500)' }}>
          STEP 1 OF 3 — {mode === 'group' ? 'BUILD · MAPS TO ./run.sh' : 'BUILD · MAPS TO ./deploy.sh'}
        </div>
        <div style={{ flex: 1 }} />
        {modeSwitch}
      </div>

      {mode === 'group' ? (
        <GroupBuild {...{ group, groups, hosts, setHosts, apps, setApps, actions, setActions, setGroupKey, hostExpr, fullCmd, scoped, me, canReview, sharedNoStop, toggle, goConfirm }} />
      ) : (
        <ConsolidatedBuild {...{ scopedGroups, cGroup, setCGroupKey, cHost, setCHost, cApp, setCApp, addPair, pairs, setPairs, actions, setActions, consolidatedCmd, distinctPairHosts, canReview, me, toggle, goConfirm }} />
      )}
    </main>
  );

  // ---------- CONFIRM ----------
  if (step === 'confirm') return (
    <main style={{ padding: '0 24px 48px', maxWidth: 940, margin: '0 auto' }}>
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 14, padding: '20px 0 14px' }}>
        <h3 style={{ margin: 0, color: 'var(--color-neutral-100)' }}>Review</h3>
        <div style={{ fontFamily: 'var(--font-heading)', fontWeight: 600, fontSize: 10, letterSpacing: '.12em', color: 'var(--color-neutral-500)' }}>STEP 2 OF 3 — NOTHING HAS RUN YET</div>
      </div>
      <div style={{ borderTop: rule2, paddingTop: 20, display: 'grid', gap: 20 }}>
        <div style={{ background: 'color-mix(in srgb, black 40%, var(--color-text))', padding: 14, fontFamily: mono, fontSize: 12.5, color: 'var(--color-neutral-100)', wordBreak: 'break-all' }}>$ {mode === 'group' ? fullCmd : consolidatedCmd}</div>

        <div>
          <h6 style={{ color: 'var(--color-neutral-400)', margin: '0 0 4px' }}>ACTION PER SERVICE</h6>
          <div style={{ fontSize: 12, color: 'var(--color-neutral-500)', marginBottom: 8 }}>
            Actions run <span style={{ fontFamily: mono, color: 'var(--color-neutral-200)' }}>{actions.join(' → ')}</span>.
            {isDeploy ? ' Hash comparison shown for the deploy phase (prod now → after deploy).' : ' Stop / start only — no jar change, so no hash.'}
          </div>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 150px 1.4fr', gap: 12, padding: '6px 8px', borderTop: rule2, borderBottom: rule1, fontFamily: 'var(--font-heading)', fontWeight: 600, fontSize: 9.5, letterSpacing: '.1em', color: 'var(--color-neutral-500)' }}>
            <div>SERVICE</div><div>ACTIONS</div><div>{isDeploy ? 'HASH — BEFORE → AFTER' : 'HASH'}</div>
          </div>
          {reviewRows.map((r) => (
            <div key={r.service} style={{ display: 'grid', gridTemplateColumns: '1fr 150px 1.4fr', gap: 12, padding: '9px 8px', borderBottom: rule1, fontFamily: mono, fontSize: 12, alignItems: 'center' }}>
              <div style={{ color: 'var(--color-neutral-100)' }}>{r.service}</div>
              <div style={{ color: 'var(--color-neutral-400)' }}>{actions.join(', ')}</div>
              <div>
                {!isDeploy ? (
                  <span style={{ color: 'var(--color-neutral-600)' }}>— no jar change</span>
                ) : r.approved && r.targetHash ? (
                  <><span style={{ color: 'var(--color-neutral-400)' }}>{r.prodHash}</span>
                    <span style={{ color: 'var(--color-neutral-600)' }}> → </span>
                    <span style={{ color: r.targetHash === r.prodHash ? 'var(--color-neutral-400)' : '#22c55e', fontWeight: 700 }}>{r.targetHash}</span></>
                ) : (
                  <span style={{ color: 'var(--color-neutral-500)' }}>{r.prodHash} <span style={{ color: C.warn }}>· no staged build — unchanged</span></span>
                )}
              </div>
            </div>
          ))}
        </div>

        <div>
          {[`Runs on ${railHosts.length} production host(s): ${railHosts.join(', ')}.`,
            actions.includes('stop') ? 'Stopped services go down during the window.' : 'No downtime — no stop phase.',
            `Report emailed to devops-team@nagad.com.bd and written to the audit log.`].map((r, i) => (
            <div key={i} style={{ display: 'flex', gap: 12, padding: '8px 2px', borderBottom: rule1, fontSize: 13, color: 'var(--color-neutral-300)' }}>
              <div style={{ width: 12, height: 12, background: 'var(--color-neutral-700)', marginTop: 3, flex: 'none' }} />{r}</div>
          ))}
        </div>

        {needType && (
          <div>
            <div style={{ fontSize: 12.5, color: 'var(--color-neutral-300)', marginBottom: 6 }}>This touches <strong>{railHosts.length} production hosts</strong>. Type <span style={{ fontFamily: mono, color: 'var(--color-neutral-100)' }}>{armToken}</span> to arm:</div>
            <input value={typed} onChange={(e) => setTyped(e.target.value)} placeholder={armToken}
              style={{ width: 240, background: 'var(--color-neutral-900)', border: '1px solid color-mix(in srgb, var(--color-neutral-100) 25%, transparent)', color: 'var(--color-neutral-100)', padding: '9px 10px', fontFamily: mono, fontSize: 13 }} />
          </div>
        )}
        <div style={{ display: 'flex', gap: 12, alignItems: 'center' }}>
          <button onClick={() => setStep('build')} style={{ border: '1px solid color-mix(in srgb, var(--color-neutral-100) 30%, transparent)', background: 'transparent', color: 'var(--color-neutral-200)', cursor: 'pointer', padding: '12px 18px', fontFamily: 'var(--font-heading)', fontWeight: 600, fontSize: 11, letterSpacing: '.1em' }}>← EDIT</button>
          <button onClick={execute} disabled={!canExec} style={{ border: 0, background: 'var(--color-accent)', color: 'var(--color-neutral-100)', cursor: 'pointer', padding: '12px 22px', fontFamily: 'var(--font-heading)', fontWeight: 800, fontSize: 12, letterSpacing: '.1em', opacity: canExec ? 1 : 0.5 }}>EXECUTE →</button>
          <div style={{ fontSize: 11, color: 'var(--color-neutral-500)' }}>{!me?.x ? 'Your role can build but not execute (no x permission).' : 'Streams live output; you can watch every host.'}</div>
        </div>
      </div>
    </main>
  );

  // ---------- RUNNING / RESULT ----------
  return (
    <main style={{ padding: '0 24px 48px', maxWidth: 1500 }}>
      {step === 'running' && (
        <>
          <div style={{ display: 'flex', alignItems: 'baseline', gap: 14, padding: '20px 0 14px' }}>
            <h3 style={{ margin: 0, color: 'var(--color-neutral-100)' }}>Running</h3>
            <div style={{ fontFamily: 'var(--font-heading)', fontWeight: 600, fontSize: 10, letterSpacing: '.12em', color: 'var(--color-neutral-500)' }}>STEP 3 OF 3</div>
            <div style={{ flex: 1 }} />
            {done && <button onClick={() => setStep('result')} style={{ border: 0, background: 'var(--color-neutral-100)', color: 'var(--color-text)', cursor: 'pointer', padding: '10px 18px', fontFamily: 'var(--font-heading)', fontWeight: 800, fontSize: 11, letterSpacing: '.1em' }}>VIEW RESULT →</button>}
          </div>
          <div style={{ display: 'grid', gridTemplateColumns: '280px 1fr', gap: 2, borderTop: rule2, paddingTop: 16, alignItems: 'start' }}>
            <div style={{ background: 'var(--color-neutral-900)', padding: 12 }}>
              <h6 style={{ color: 'var(--color-neutral-400)', margin: '0 0 8px' }}>PER HOST</h6>
              {railHosts.map((h) => (
                <div key={h} style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '7px 0', borderBottom: rule1 }}>
                  <div style={{ fontFamily: mono, fontSize: 12, color: 'var(--color-neutral-100)', width: 90 }}>{h}</div>
                  {actions.map((a) => { const st = rail[h]?.[a] ?? 'pending';
                    const bg = st === 'done' ? C.run : st === 'active' ? C.warn : 'transparent';
                    const bd = st === 'pending' ? '1px solid var(--color-neutral-600)' : '1px solid transparent';
                    return <div key={a} style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                      <div style={{ width: 10, height: 10, background: bg, border: bd, animation: st === 'active' ? 'pulse 1s infinite' : 'none' }} />
                      <span style={{ fontFamily: mono, fontSize: 9.5, color: 'var(--color-neutral-500)' }}>{a}</span></div>;
                  })}
                </div>
              ))}
            </div>
            <div ref={termRef} style={{ background: '#dcdad5', padding: 14, height: 480, overflow: 'auto' }}>
              {lines.map((ln, i) => <div key={i} style={{ color: TERM[ln.level] ?? '#1c1917', fontFamily: mono, fontSize: 11.5, lineHeight: 1.55, whiteSpace: 'pre-wrap' }}>{ln.text}</div>)}
              {!done && <div style={{ color: '#1c1917', fontFamily: mono, fontSize: 11.5, animation: 'blinkc 1s steps(1) infinite' }}>█</div>}
            </div>
          </div>
        </>
      )}

      {step === 'result' && (
        <div style={{ maxWidth: 1000 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 14, padding: '20px 0 14px' }}>
            <div style={{ width: 14, height: 14, background: C.run }} />
            <h3 style={{ margin: 0, color: 'var(--color-neutral-100)' }}>Completed</h3>
            <div style={{ fontFamily: mono, fontSize: 12, color: 'var(--color-neutral-400)' }}>{result.length} host×app · {result.filter((r) => r.verdict === 'changed').length} changed</div>
          </div>
          <div style={{ display: 'grid', gridTemplateColumns: '100px 160px 1fr 130px', gap: 12, padding: '6px 8px', borderTop: rule2, borderBottom: rule1, fontFamily: 'var(--font-heading)', fontWeight: 600, fontSize: 9.5, letterSpacing: '.1em', color: 'var(--color-neutral-500)' }}>
            <div>HOST</div><div>SERVICE</div><div>BEFORE → AFTER</div><div>VERDICT</div>
          </div>
          {result.map((rw, i) => (
            <div key={i} style={{ display: 'grid', gridTemplateColumns: '100px 160px 1fr 130px', gap: 12, padding: 8, borderBottom: rule1, fontFamily: mono, fontSize: 12, alignItems: 'center' }}>
              <div style={{ color: 'var(--color-neutral-100)' }}>{rw.host}</div>
              <div style={{ color: 'var(--color-neutral-300)' }}>{rw.app}</div>
              <div><span style={{ color: 'var(--color-neutral-500)' }}>{rw.before}</span> <span style={{ color: 'var(--color-neutral-500)' }}>→</span> <span style={{ color: rw.verdict === 'changed' ? '#22c55e' : 'var(--color-neutral-400)', fontWeight: 700 }}>{rw.after}</span></div>
              <div style={{ justifySelf: 'start', padding: '2px 8px', background: rw.verdict === 'changed' ? C.run : 'transparent', color: rw.verdict === 'changed' ? C.inkOnGreen : 'var(--color-neutral-500)', border: rw.verdict === 'changed' ? 'none' : '1px solid var(--color-neutral-700)', fontFamily: 'var(--font-heading)', fontWeight: 800, fontSize: 9.5, letterSpacing: '.1em' }}>{rw.verdict.toUpperCase()}</div>
            </div>
          ))}
          <div style={{ display: 'flex', gap: 12, alignItems: 'center', marginTop: 18 }}>
            <button onClick={reset} style={{ border: '1px solid color-mix(in srgb, var(--color-neutral-100) 30%, transparent)', background: 'transparent', color: 'var(--color-neutral-200)', cursor: 'pointer', padding: '11px 16px', fontFamily: 'var(--font-heading)', fontWeight: 600, fontSize: 11, letterSpacing: '.1em' }}>NEW ACTION</button>
            <div style={{ fontSize: 11.5, color: 'var(--color-neutral-500)' }}>Same report emailed to devops-team@nagad.com.bd · logged to the audit trail · registry updated.</div>
          </div>
        </div>
      )}
    </main>
  );
}

// ============================ PER-GROUP BUILD ============================
/* eslint-disable @typescript-eslint/no-explicit-any */
function GroupBuild({ group, groups, hosts, setHosts, apps, setApps, actions, setActions, setGroupKey, hostExpr, fullCmd, scoped, me, canReview, sharedNoStop, toggle, goConfirm }: any) {
  return (
    <>
      <div style={{ display: 'flex', gap: 10, alignItems: 'center', padding: '10px 12px', marginBottom: 4, background: 'var(--color-neutral-900)', fontSize: 12, color: 'var(--color-neutral-300)' }}>
        <span style={{ width: 9, height: 9, background: C.run, flex: 'none' }} />The <span style={{ fontFamily: mono, color: 'var(--color-neutral-100)' }}>deploy</span> phase installs jars fetched from staging. Fetched apps show ✓ with the staged hash. Stop / start alone change no jar.
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 420px', gap: 36, borderTop: rule2, paddingTop: 22, alignItems: 'start' }}>
        <div style={{ display: 'grid', gap: 26 }}>
          <div>
            <h6 style={{ color: 'var(--color-neutral-400)', margin: '0 0 8px' }}>1 — GROUP</h6>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3,1fr)', gap: 2 }}>
              {groups.map((g: DeployGroup) => {
                const on = g.key === group.key;
                return <button key={g.key} onClick={() => { setGroupKey(g.key); setHosts(g.hosts); setApps([]); }}
                  style={{ border: '1px solid color-mix(in srgb, var(--color-neutral-100) 20%, transparent)', background: on ? 'var(--color-neutral-100)' : 'transparent',
                    color: on ? 'var(--color-text)' : 'var(--color-neutral-300)', cursor: 'pointer', padding: '8px 10px', textAlign: 'left', fontFamily: mono, fontSize: 11 }}>
                  {g.cmd}<br /><span style={{ fontSize: 9.5, opacity: 0.65 }}>{g.key} · {g.hosts.length}h</span></button>;
              })}
            </div>
          </div>

          <div>
            <div style={{ display: 'flex', alignItems: 'baseline', gap: 12, marginBottom: 8 }}>
              <h6 style={{ color: 'var(--color-neutral-400)', margin: 0 }}>2 — HOSTS</h6>
              <button onClick={() => setHosts(hosts.length === group.hosts.length ? [] : group.hosts)} style={linkBtn}>{hosts.length === group.hosts.length ? 'CLEAR' : 'SELECT ALL'}</button>
              <div style={{ fontFamily: mono, fontSize: 11, color: 'var(--color-neutral-500)' }}>→ {hostExpr}</div>
            </div>
            <div style={{ display: 'flex', gap: 2, flexWrap: 'wrap' }}>
              {group.hosts.map((h: string) => { const on = hosts.includes(h); return (
                <button key={h} onClick={() => setHosts((p: string[]) => toggle(p, h))} style={{ border: '1px solid color-mix(in srgb, var(--color-neutral-100) 20%, transparent)',
                  background: on ? 'var(--color-neutral-100)' : 'transparent', color: on ? 'var(--color-text)' : 'var(--color-neutral-300)', cursor: 'pointer', padding: '7px 12px', fontFamily: mono, fontSize: 11.5 }}>{h}</button>
              ); })}
            </div>
          </div>

          <div>
            <h6 style={{ color: 'var(--color-neutral-400)', margin: '0 0 8px' }}>3 — APPS <span style={{ fontWeight: 400, letterSpacing: 0, textTransform: 'none', color: 'var(--color-neutral-500)' }}>— validated against what runs in {group.cmd}</span></h6>
            <div style={{ display: 'flex', gap: 2, flexWrap: 'wrap' }}>
              {group.apps.map((a: any) => { const on = apps.includes(a.key); return (
                <button key={a.key} onClick={() => setApps((p: string[]) => toggle(p, a.key))} title={a.jar} style={{ border: '1px solid color-mix(in srgb, var(--color-neutral-100) 20%, transparent)',
                  background: on ? 'var(--color-neutral-100)' : 'transparent', color: on ? 'var(--color-text)' : 'var(--color-neutral-300)', cursor: 'pointer', padding: '7px 12px', fontFamily: mono, fontSize: 11.5 }}>
                  {a.key}{a.approved && <span style={{ fontSize: 9, color: on ? '#0a7d34' : '#22c55e' }}> ✓ {a.approvedHash}</span>}</button>
              ); })}
            </div>
          </div>

          <ActionPicker actions={actions} setActions={setActions} toggle={toggle} />
        </div>

        <div style={{ display: 'grid', gap: 18, position: 'sticky', top: 20 }}>
          <CommandBox cmd={fullCmd} />
          <div>
            <h6 style={{ color: 'var(--color-neutral-400)', margin: '0 0 6px' }}>BLAST RADIUS</h6>
            <div style={{ borderTop: rule2 }}>
              {[`${hosts.length} host(s): ${hostExpr}`, `${apps.length} app(s): ${apps.join(', ') || '—'}`, `actions: ${actions.join(' → ')}`].map((b, i) => (
                <div key={i} style={{ padding: '8px 2px', borderBottom: rule1, fontSize: 12.5, color: 'var(--color-neutral-300)' }}>{b}</div>
              ))}
            </div>
            <div style={{ fontSize: 12, color: actions.includes('stop') ? C.warn : 'var(--color-neutral-500)', marginTop: 8 }}>
              {actions.includes('stop') ? 'Causes downtime on the stopped services during the window.' : 'No stop phase — rolling / no downtime.'}
            </div>
          </div>
          {sharedNoStop && (
            <div style={{ display: 'flex', gap: 10, alignItems: 'flex-start', padding: 12, borderLeft: `2px solid ${C.warn}`, background: 'color-mix(in srgb, oklch(0.8 0.14 82) 8%, transparent)' }}>
              <div style={{ width: 10, height: 10, background: C.warn, marginTop: 3, flex: 'none' }} />
              <div style={{ fontSize: 12, color: 'var(--color-neutral-200)' }}><strong style={{ fontFamily: 'var(--font-heading)', fontSize: 10, letterSpacing: '.12em', color: C.warn }}>SHARED JAR, NO STOP</strong><br />Deploying without stopping first replaces a jar that running JVMs hold open. Add the stop phase.</div>
            </div>
          )}
          <button onClick={goConfirm} disabled={!canReview} style={reviewBtn(canReview)}>REVIEW →</button>
          <div style={{ fontSize: 11, color: 'var(--color-neutral-500)' }}>{!scoped ? `You are not scoped to group ${group.cmd}.` : !me?.x ? 'Your role can build but not execute (no x permission).' : 'Nothing runs until you confirm on the next step.'}</div>
        </div>
      </div>
    </>
  );
}

// ============================ CONSOLIDATED BUILD ============================
function ConsolidatedBuild({ scopedGroups, cGroup, setCGroupKey, cHost, setCHost, cApp, setCApp, addPair, pairs, setPairs, actions, setActions, consolidatedCmd, distinctPairHosts, canReview, me, toggle, goConfirm }: any) {
  return (
    <>
      <div style={{ display: 'flex', gap: 10, alignItems: 'center', padding: '10px 12px', marginBottom: 4, background: 'var(--color-neutral-900)', fontSize: 12, color: 'var(--color-neutral-300)' }}>
        <span style={{ width: 9, height: 9, background: C.run, flex: 'none' }} />Target <span style={{ fontFamily: mono, color: 'var(--color-neutral-100)' }}>host:app</span> pairs across <strong style={{ color: 'var(--color-neutral-100)' }}>different groups</strong> in one run. Add-on only — maps to <span style={{ fontFamily: mono, color: 'var(--color-neutral-100)' }}>./deploy.sh</span>, the per-group flow is untouched.
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 420px', gap: 36, borderTop: rule2, paddingTop: 22, alignItems: 'start' }}>
        <div style={{ display: 'grid', gap: 24 }}>
          <div>
            <h6 style={{ color: 'var(--color-neutral-400)', margin: '0 0 8px' }}>1 — PICK A GROUP <span style={{ fontWeight: 400, letterSpacing: 0, textTransform: 'none', color: 'var(--color-neutral-500)' }}>— narrows the host + app lists; pairs can mix groups</span></h6>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3,1fr)', gap: 2 }}>
              {scopedGroups.map((g: DeployGroup) => {
                const on = g.key === cGroup?.key;
                return <button key={g.key} onClick={() => { setCGroupKey(g.key); setCHost(''); setCApp(''); }}
                  style={{ border: '1px solid color-mix(in srgb, var(--color-neutral-100) 20%, transparent)', background: on ? 'var(--color-neutral-100)' : 'transparent',
                    color: on ? 'var(--color-text)' : 'var(--color-neutral-300)', cursor: 'pointer', padding: '8px 10px', textAlign: 'left', fontFamily: mono, fontSize: 11 }}>
                  {g.cmd}<br /><span style={{ fontSize: 9.5, opacity: 0.65 }}>{g.key} · {g.hosts.length}h</span></button>;
              })}
            </div>
          </div>

          <div>
            <h6 style={{ color: 'var(--color-neutral-400)', margin: '0 0 8px' }}>2 — HOST</h6>
            <div style={{ display: 'flex', gap: 2, flexWrap: 'wrap' }}>
              {cGroup?.hosts.map((h: string) => { const on = h === cHost; return (
                <button key={h} onClick={() => setCHost(h)} style={{ border: '1px solid color-mix(in srgb, var(--color-neutral-100) 20%, transparent)',
                  background: on ? 'var(--color-neutral-100)' : 'transparent', color: on ? 'var(--color-text)' : 'var(--color-neutral-300)', cursor: 'pointer', padding: '7px 12px', fontFamily: mono, fontSize: 11.5 }}>{h}</button>
              ); })}
            </div>
          </div>

          <div>
            <h6 style={{ color: 'var(--color-neutral-400)', margin: '0 0 8px' }}>3 — APP</h6>
            <div style={{ display: 'flex', gap: 2, flexWrap: 'wrap' }}>
              {cGroup?.apps.map((a: any) => { const on = a.key === cApp; return (
                <button key={a.key} onClick={() => setCApp(a.key)} title={a.jar} style={{ border: '1px solid color-mix(in srgb, var(--color-neutral-100) 20%, transparent)',
                  background: on ? 'var(--color-neutral-100)' : 'transparent', color: on ? 'var(--color-text)' : 'var(--color-neutral-300)', cursor: 'pointer', padding: '7px 12px', fontFamily: mono, fontSize: 11.5 }}>
                  {a.key}{a.approved && <span style={{ fontSize: 9, color: on ? '#0a7d34' : '#22c55e' }}> ✓</span>}</button>
              ); })}
            </div>
            <button onClick={addPair} disabled={!cHost || !cApp} style={{ marginTop: 10, border: '1px solid color-mix(in srgb, var(--color-neutral-100) 30%, transparent)', background: 'transparent', color: 'var(--color-neutral-100)', cursor: cHost && cApp ? 'pointer' : 'default', padding: '8px 14px', fontFamily: 'var(--font-heading)', fontWeight: 700, fontSize: 10.5, letterSpacing: '.1em', opacity: cHost && cApp ? 1 : 0.4 }}>
              + ADD PAIR {cHost && cApp ? `— ${cHost}:${cApp}` : ''}</button>
          </div>

          <ActionPicker actions={actions} setActions={setActions} toggle={toggle} />
        </div>

        <div style={{ display: 'grid', gap: 18, position: 'sticky', top: 20 }}>
          <CommandBox cmd={consolidatedCmd} />
          <div>
            <h6 style={{ color: 'var(--color-neutral-400)', margin: '0 0 6px' }}>PAIRS <span style={{ fontWeight: 400, color: 'var(--color-neutral-500)' }}>({pairs.length})</span></h6>
            <div style={{ borderTop: rule2 }}>
              {pairs.length === 0 && <div style={{ padding: '10px 2px', fontSize: 12, color: 'var(--color-neutral-500)' }}>No pairs yet — pick a host and app, then ADD PAIR.</div>}
              {pairs.map((p: DeployPair, i: number) => (
                <div key={`${p.host}:${p.app}`} style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '8px 2px', borderBottom: rule1, fontFamily: mono, fontSize: 12 }}>
                  <span style={{ color: 'var(--color-neutral-100)' }}>{p.host}</span><span style={{ color: 'var(--color-neutral-600)' }}>:</span><span style={{ color: 'var(--color-neutral-300)' }}>{p.app}</span>
                  <div style={{ flex: 1 }} />
                  <button onClick={() => setPairs(pairs.filter((_: DeployPair, j: number) => j !== i))} style={{ border: 0, background: 'transparent', color: C.stop, cursor: 'pointer', fontFamily: 'var(--font-heading)', fontWeight: 700, fontSize: 10, letterSpacing: '.1em' }}>REMOVE</button>
                </div>
              ))}
            </div>
            <div style={{ fontSize: 12, color: 'var(--color-neutral-500)', marginTop: 8 }}>{distinctPairHosts.length} host(s) · {pairs.length} pair(s) · {actions.join(' → ')}</div>
          </div>
          <button onClick={goConfirm} disabled={!canReview} style={reviewBtn(canReview)}>REVIEW →</button>
          <div style={{ fontSize: 11, color: 'var(--color-neutral-500)' }}>{!me?.x ? 'Your role can build but not execute (no x permission).' : 'Nothing runs until you confirm on the next step.'}</div>
        </div>
      </div>
    </>
  );
}

function ActionPicker({ actions, setActions, toggle }: any) {
  return (
    <div>
      <h6 style={{ color: 'var(--color-neutral-400)', margin: '0 0 8px' }}>ACTIONS</h6>
      <div style={{ display: 'flex', gap: 2 }}>
        {(['stop', 'deploy', 'start'] as const).map((ac) => { const on = actions.includes(ac); return (
          <button key={ac} onClick={() => setActions((p: string[]) => toggle(p, ac))} style={{ display: 'flex', alignItems: 'center', gap: 10,
            border: '1px solid color-mix(in srgb, var(--color-neutral-100) 20%, transparent)', background: 'transparent', cursor: 'pointer', padding: '12px 18px', minWidth: 140, justifyContent: 'flex-start' }}>
            <div style={{ width: 14, height: 14, border: '1px solid var(--color-neutral-400)', background: on ? 'var(--color-neutral-100)' : 'transparent' }} />
            <div style={{ fontFamily: mono, fontSize: 13, color: on ? 'var(--color-neutral-100)' : 'var(--color-neutral-500)' }}>{ac}</div></button>
        ); })}
      </div>
      <div style={{ fontSize: 11, color: 'var(--color-neutral-500)', marginTop: 6 }}>Order is fixed: stop → deploy → start. Deselect to skip a phase.</div>
    </div>
  );
}

function CommandBox({ cmd }: { cmd: string }) {
  return (
    <div>
      <h6 style={{ color: 'var(--color-neutral-400)', margin: '0 0 6px' }}>COMMAND</h6>
      <div style={{ background: 'color-mix(in srgb, black 40%, var(--color-text))', padding: 14, fontFamily: mono, fontSize: 12.5, color: 'var(--color-neutral-100)', lineHeight: 1.5, wordBreak: 'break-all' }}>$ {cmd}</div>
    </div>
  );
}

const reviewBtn = (enabled: boolean): React.CSSProperties => ({ border: 0, background: 'var(--color-neutral-100)', color: 'var(--color-text)', cursor: 'pointer', padding: '13px 18px',
  fontFamily: 'var(--font-heading)', fontWeight: 800, fontSize: 12, letterSpacing: '.1em', display: 'flex', justifyContent: 'flex-start', opacity: enabled ? 1 : 0.5 });

const linkBtn: React.CSSProperties = { border: 0, background: 'transparent', color: 'var(--color-accent-400)', cursor: 'pointer', fontFamily: 'var(--font-heading)', fontWeight: 600, fontSize: 10, letterSpacing: '.1em', padding: 0 };
