import { useEffect, useMemo, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError, openDeployStream, type ResultRow } from '../api/client';
import { useApp } from '../store/app';
import { C, rule1, rule2, TERM } from '../theme/colors';
import type { DeployGroup } from '../api/types';

const mono = 'var(--mono)';
const MULTI_INSTANCE = new Set(['apigw', 'apigw-summary']);
type Step = 'build' | 'confirm' | 'running' | 'result';
interface TermLine { level: string; text: string; }

export function Deploy() {
  const { me, flash } = useApp();
  const qc = useQueryClient();
  const { data: groups } = useQuery({ queryKey: ['deploy', 'groups'], queryFn: () => api.get<DeployGroup[]>('/deploy/groups') });

  const [step, setStep] = useState<Step>('build');
  const [groupKey, setGroupKey] = useState<string>('');
  const [hosts, setHosts] = useState<string[]>([]);
  const [apps, setApps] = useState<string[]>([]);
  const [actions, setActions] = useState<string[]>(['stop', 'deploy', 'start']);
  const [sudo, setSudo] = useState('');
  const [typed, setTyped] = useState('');
  const [lines, setLines] = useState<TermLine[]>([]);
  const [rail, setRail] = useState<Record<string, Record<string, string>>>({});
  const [result, setResult] = useState<ResultRow[]>([]);
  const [done, setDone] = useState(false);
  const termRef = useRef<HTMLDivElement>(null);

  const group = useMemo(() => groups?.find((g) => g.key === groupKey) ?? groups?.[0], [groups, groupKey]);

  useEffect(() => { if (group && hosts.length === 0 && step === 'build') setHosts(group.hosts); }, [group]); // eslint-disable-line
  useEffect(() => { if (termRef.current) termRef.current.scrollTop = termRef.current.scrollHeight; }, [lines]);

  if (!groups || !group) return <main style={{ padding: 24, color: 'var(--color-neutral-500)' }}>loading…</main>;

  const scoped = me?.scope === 'all' || me?.scope?.split(',').map((x) => x.trim()).includes(group.cmd);
  const approvedFor = (app: string) => group.apps.find((a) => a.key === app)?.approved ?? false;
  const isDeploy = actions.includes('deploy');
  const unapproved = isDeploy ? apps.filter((a) => !approvedFor(a)) : [];
  const sharedNoStop = isDeploy && !actions.includes('stop') && apps.some((a) => MULTI_INSTANCE.has(a));

  const hostExpr = hosts.length === 0 ? 'all' : hosts.length === 1 ? hosts[0] : `${hosts[0]}..${hosts[hosts.length - 1]}`;
  const fullCmd = `./run.sh ${group.cmd} ${hostExpr} ${apps.join(',') || '<apps>'} ${actions.join(',')} -K`;

  const canReview = apps.length > 0 && actions.length > 0 && hosts.length > 0 && unapproved.length === 0 && scoped;
  const needType = hosts.length > 1;
  const canExec = !!me?.x && (!needType || typed.trim() === group.cmd);

  const toggle = <T,>(arr: T[], v: T): T[] => (arr.includes(v) ? arr.filter((x) => x !== v) : [...arr, v]);

  function reset() {
    setStep('build'); setApps([]); setActions(['stop', 'deploy', 'start']); setSudo(''); setTyped('');
    setLines([]); setRail({}); setResult([]); setDone(false);
  }

  async function execute() {
    setStep('running'); setLines([]); setRail({}); setDone(false);
    try {
      const { deploymentId } = await api.post<{ deploymentId: string }>('/deploy', {
        group: group!.cmd, hosts, apps, actions, sudoPassword: sudo,
      });
      openDeployStream(deploymentId, {
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

  // ---------- BUILD ----------
  if (step === 'build') return (
    <main style={{ padding: '0 24px 48px', maxWidth: 1500 }}>
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 14, padding: '20px 0 14px' }}>
        <h3 style={{ margin: 0, color: 'var(--color-neutral-100)' }}>Deploy to production</h3>
        <div style={{ fontFamily: 'var(--font-heading)', fontWeight: 600, fontSize: 10, letterSpacing: '.12em', color: 'var(--color-neutral-500)' }}>STEP 1 OF 3 — BUILD · MAPS TO ./run.sh</div>
      </div>
      <div style={{ display: 'flex', gap: 10, alignItems: 'center', padding: '10px 12px', marginBottom: 4, background: 'var(--color-neutral-900)', fontSize: 12, color: 'var(--color-neutral-300)' }}>
        <span style={{ width: 9, height: 9, background: C.run, flex: 'none' }} />The <span style={{ fontFamily: mono, color: 'var(--color-neutral-100)' }}>deploy</span> phase installs only jars fetched from staging and <strong style={{ color: 'var(--color-neutral-100)' }}>approved by a super admin</strong>. Approved apps show ✓. Stop / start alone need no approval.
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 420px', gap: 36, borderTop: rule2, paddingTop: 22, alignItems: 'start' }}>
        <div style={{ display: 'grid', gap: 26 }}>
          <div>
            <h6 style={{ color: 'var(--color-neutral-400)', margin: '0 0 8px' }}>1 — GROUP</h6>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3,1fr)', gap: 2 }}>
              {groups.map((g) => {
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
              {group.hosts.map((h) => { const on = hosts.includes(h); return (
                <button key={h} onClick={() => setHosts((p) => toggle(p, h))} style={{ border: '1px solid color-mix(in srgb, var(--color-neutral-100) 20%, transparent)',
                  background: on ? 'var(--color-neutral-100)' : 'transparent', color: on ? 'var(--color-text)' : 'var(--color-neutral-300)', cursor: 'pointer', padding: '7px 12px', fontFamily: mono, fontSize: 11.5 }}>{h}</button>
              ); })}
            </div>
          </div>

          <div>
            <h6 style={{ color: 'var(--color-neutral-400)', margin: '0 0 8px' }}>3 — APPS <span style={{ fontWeight: 400, letterSpacing: 0, textTransform: 'none', color: 'var(--color-neutral-500)' }}>— validated against what runs in {group.cmd}</span></h6>
            <div style={{ display: 'flex', gap: 2, flexWrap: 'wrap' }}>
              {group.apps.map((a) => { const on = apps.includes(a.key); return (
                <button key={a.key} onClick={() => setApps((p) => toggle(p, a.key))} title={a.jar} style={{ border: '1px solid color-mix(in srgb, var(--color-neutral-100) 20%, transparent)',
                  background: on ? 'var(--color-neutral-100)' : 'transparent', color: on ? 'var(--color-text)' : 'var(--color-neutral-300)', cursor: 'pointer', padding: '7px 12px', fontFamily: mono, fontSize: 11.5 }}>
                  {a.key}{a.approved && <span style={{ fontSize: 9, color: on ? '#0a7d34' : '#22c55e' }}> ✓ {a.approvedHash}</span>}</button>
              ); })}
            </div>
          </div>

          <div>
            <h6 style={{ color: 'var(--color-neutral-400)', margin: '0 0 8px' }}>4 — ACTIONS</h6>
            <div style={{ display: 'flex', gap: 2 }}>
              {(['stop', 'deploy', 'start'] as const).map((ac) => { const on = actions.includes(ac); return (
                <button key={ac} onClick={() => setActions((p) => toggle(p, ac))} style={{ display: 'flex', alignItems: 'center', gap: 10,
                  border: '1px solid color-mix(in srgb, var(--color-neutral-100) 20%, transparent)', background: 'transparent', cursor: 'pointer', padding: '12px 18px', minWidth: 140, justifyContent: 'flex-start' }}>
                  <div style={{ width: 14, height: 14, border: '1px solid var(--color-neutral-400)', background: on ? 'var(--color-neutral-100)' : 'transparent' }} />
                  <div style={{ fontFamily: mono, fontSize: 13, color: on ? 'var(--color-neutral-100)' : 'var(--color-neutral-500)' }}>{ac}</div></button>
              ); })}
            </div>
            <div style={{ fontSize: 11, color: 'var(--color-neutral-500)', marginTop: 6 }}>Order is fixed: stop → deploy → start. Deselect to skip a phase.</div>
          </div>

          <div>
            <h6 style={{ color: 'var(--color-neutral-400)', margin: '0 0 8px' }}>5 — SUDO PASSWORD</h6>
            <input type="password" value={sudo} onChange={(e) => setSudo(e.target.value)} placeholder="your sudo password (-K)"
              style={{ width: 320, background: 'var(--color-neutral-900)', border: '1px solid color-mix(in srgb, var(--color-neutral-100) 25%, transparent)', color: 'var(--color-neutral-100)', padding: '9px 10px', fontFamily: mono, fontSize: 12 }} />
            <div style={{ fontSize: 11, color: 'var(--color-neutral-500)', marginTop: 6 }}>Used once for this action. Never stored, never remembered.</div>
          </div>
        </div>

        <div style={{ display: 'grid', gap: 18, position: 'sticky', top: 20 }}>
          <div>
            <h6 style={{ color: 'var(--color-neutral-400)', margin: '0 0 6px' }}>COMMAND</h6>
            <div style={{ background: 'color-mix(in srgb, black 40%, var(--color-text))', padding: 14, fontFamily: mono, fontSize: 12.5, color: 'var(--color-neutral-100)', lineHeight: 1.5, wordBreak: 'break-all' }}>$ {fullCmd}</div>
          </div>
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
          {unapproved.length > 0 && (
            <div style={{ display: 'flex', gap: 10, alignItems: 'flex-start', padding: 12, borderLeft: '2px solid var(--color-accent)', background: 'color-mix(in srgb, var(--color-accent) 8%, transparent)' }}>
              <div style={{ width: 10, height: 10, background: 'var(--color-accent)', marginTop: 3, flex: 'none' }} />
              <div style={{ fontSize: 12, color: 'var(--color-neutral-200)' }}>Deploy is locked for {unapproved.join(', ')} — no approved promotion in {group.cmd}. Fetch and get it approved first.</div>
            </div>
          )}

          <button onClick={() => setStep('confirm')} disabled={!canReview} style={{ border: 0, background: 'var(--color-neutral-100)', color: 'var(--color-text)', cursor: 'pointer', padding: '13px 18px',
            fontFamily: 'var(--font-heading)', fontWeight: 800, fontSize: 12, letterSpacing: '.1em', display: 'flex', justifyContent: 'flex-start', opacity: canReview ? 1 : 0.5 }}>REVIEW →</button>
          <div style={{ fontSize: 11, color: 'var(--color-neutral-500)' }}>{!scoped ? `You are not scoped to group ${group.cmd}.` : !me?.x ? 'Your role can build but not execute (no x permission).' : 'Nothing runs until you confirm on the next step.'}</div>
        </div>
      </div>
    </main>
  );

  // ---------- CONFIRM ----------
  if (step === 'confirm') return (
    <main style={{ padding: '0 24px 48px', maxWidth: 860, margin: '0 auto' }}>
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 14, padding: '20px 0 14px' }}>
        <h3 style={{ margin: 0, color: 'var(--color-neutral-100)' }}>Confirm</h3>
        <div style={{ fontFamily: 'var(--font-heading)', fontWeight: 600, fontSize: 10, letterSpacing: '.12em', color: 'var(--color-neutral-500)' }}>STEP 2 OF 3 — NOTHING HAS RUN YET</div>
      </div>
      <div style={{ borderTop: rule2, paddingTop: 20, display: 'grid', gap: 18 }}>
        <div style={{ background: 'color-mix(in srgb, black 40%, var(--color-text))', padding: 14, fontFamily: mono, fontSize: 12.5, color: 'var(--color-neutral-100)', wordBreak: 'break-all' }}>$ {fullCmd}</div>
        <div>
          {[`This runs stop/deploy/start (${actions.join(', ')}) on ${hosts.length} production host(s) in ${group.cmd}.`,
            `Services affected: ${apps.join(', ')}.`,
            actions.includes('stop') ? 'Those services go down during the window.' : 'No downtime — no stop phase.',
            `Report will be emailed to devops-team@nagad.com.bd and written to the audit log.`].map((r, i) => (
            <div key={i} style={{ display: 'flex', gap: 12, padding: '10px 2px', borderBottom: rule1, fontSize: 13.5, color: 'var(--color-neutral-200)' }}>
              <div style={{ width: 12, height: 12, background: 'var(--color-neutral-700)', marginTop: 4, flex: 'none' }} />{r}</div>
          ))}
        </div>
        {needType && (
          <div>
            <div style={{ fontSize: 12.5, color: 'var(--color-neutral-300)', marginBottom: 6 }}>This touches <strong>{hosts.length} production hosts</strong>. Type the group short-name <span style={{ fontFamily: mono, color: 'var(--color-neutral-100)' }}>{group.cmd}</span> to arm:</div>
            <input value={typed} onChange={(e) => setTyped(e.target.value)} placeholder={group.cmd}
              style={{ width: 240, background: 'var(--color-neutral-900)', border: '1px solid color-mix(in srgb, var(--color-neutral-100) 25%, transparent)', color: 'var(--color-neutral-100)', padding: '9px 10px', fontFamily: mono, fontSize: 13 }} />
          </div>
        )}
        <div style={{ display: 'flex', gap: 12, alignItems: 'center' }}>
          <button onClick={() => setStep('build')} style={{ border: '1px solid color-mix(in srgb, var(--color-neutral-100) 30%, transparent)', background: 'transparent', color: 'var(--color-neutral-200)', cursor: 'pointer', padding: '12px 18px', fontFamily: 'var(--font-heading)', fontWeight: 600, fontSize: 11, letterSpacing: '.1em' }}>← EDIT</button>
          <button onClick={execute} disabled={!canExec} style={{ border: 0, background: 'var(--color-accent)', color: 'var(--color-neutral-100)', cursor: 'pointer', padding: '12px 22px', fontFamily: 'var(--font-heading)', fontWeight: 800, fontSize: 12, letterSpacing: '.1em', opacity: canExec ? 1 : 0.5 }}>EXECUTE →</button>
          <div style={{ fontSize: 11, color: 'var(--color-neutral-500)' }}>Streams live output; you can watch every host.</div>
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
              {hosts.map((h) => (
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

const linkBtn: React.CSSProperties = { border: 0, background: 'transparent', color: 'var(--color-accent-400)', cursor: 'pointer', fontFamily: 'var(--font-heading)', fontWeight: 600, fontSize: 10, letterSpacing: '.1em', padding: 0 };
