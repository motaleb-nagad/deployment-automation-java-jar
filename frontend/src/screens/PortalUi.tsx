import { useEffect, useMemo, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError, openDeployStream, type ResultRow } from '../api/client';
import { useApp } from '../store/app';
import { C, rule1, rule2, TERM } from '../theme/colors';
import type { PortalUiCatalog } from '../api/types';

const mono = 'var(--mono)';
type Step = 'build' | 'confirm' | 'running' | 'result';
interface TermLine { level: string; text: string; }

const MODE_LABEL: Record<string, string> = {
  fetch: 'FETCH — from staging', deploy: 'DEPLOY — to prod DMZ', rollback: 'ROLLBACK — restore backup', verify: 'VERIFY — vs staging',
};
const phasesFor = (m: string): string[] =>
  m === 'fetch' ? ['fetch'] : m === 'rollback' ? ['snapshot', 'rollback'] : m === 'verify' ? ['verify'] : ['backup', 'deploy', 'fixes'];

/**
 * Self-contained lifecycle (build → review → run → result) for the portal-UI channel,
 * restricted to the given {@code modes}. Used under Deploy (deploy), the Fetch tab
 * (fetch/verify) and the Rollback tab (rollback). Maps to portalui-deployment/run.sh.
 */
export function PortalUi({ modes, heading, subtitle }: { modes: string[]; heading: string; subtitle: string }) {
  const { me, flash } = useApp();
  const qc = useQueryClient();
  const { data: cat } = useQuery({ queryKey: ['deploy', 'portal-ui'], queryFn: () => api.get<PortalUiCatalog>('/deploy/portal-ui') });

  const [step, setStep] = useState<Step>('build');
  const [mode, setMode] = useState<string>(modes[0]);
  const [uis, setUis] = useState<string[]>([]);
  const [hosts, setHosts] = useState<string[]>([]);
  const [fixUrl, setFixUrl] = useState(true);
  const [fixSize, setFixSize] = useState(true);
  const [date, setDate] = useState('');
  const [typed, setTyped] = useState('');
  const [lines, setLines] = useState<TermLine[]>([]);
  const [rail, setRail] = useState<Record<string, Record<string, string>>>({});
  const [result, setResult] = useState<ResultRow[]>([]);
  const [done, setDone] = useState(false);
  const termRef = useRef<HTMLDivElement>(null);

  useEffect(() => { if (termRef.current) termRef.current.scrollTop = termRef.current.scrollHeight; }, [lines]);
  useEffect(() => { if (!modes.includes(mode)) setMode(modes[0]); }, [modes, mode]);

  const toggle = <T,>(arr: T[], v: T): T[] => (arr.includes(v) ? arr.filter((x) => x !== v) : [...arr, v]);

  const allHosts = useMemo(() => cat?.prodHosts.map((h) => h.host) ?? [], [cat]);
  const staging = cat?.staging.host ?? '';
  const scopeHosts = mode === 'deploy' || mode === 'rollback';
  const showFixes = mode === 'deploy';
  const showDate = mode === 'deploy' || mode === 'rollback';

  const railHosts = (mode === 'fetch' || mode === 'verify') ? (staging ? [staging] : []) : (hosts.length ? hosts : allHosts);
  const phases = phasesFor(mode);

  const cmd = useMemo(() => {
    let s = `./run.sh ${uis.join(',') || '<ui>'}`;
    if (date) s += ` ${date}`;
    if (mode === 'fetch') s += ' --fetch'; else if (mode === 'rollback') s += ' --rollback'; else if (mode === 'verify') s += ' --verify';
    if (mode === 'deploy') { if (!fixUrl) s += ' --no-url-fix'; if (!fixSize) s += ' --no-size-fix'; }
    if (scopeHosts && hosts.length) s += ` -h ${hosts.join(',')}`;
    return s;
  }, [uis, date, mode, fixUrl, fixSize, hosts, scopeHosts]);

  const canReview = uis.length > 0;
  const needType = railHosts.length > 1;
  const canExec = !!me?.x && (!needType || typed.trim() === mode);

  function reset() {
    setStep('build'); setUis([]); setHosts([]); setDate(''); setTyped('');
    setLines([]); setRail({}); setResult([]); setDone(false);
  }

  async function execute() {
    setStep('running'); setLines([]); setRail({}); setDone(false);
    try {
      const { streamTicket } = await api.post<{ deploymentId: string; streamTicket: string }>('/deploy/portal-ui',
        { mode, uis, hosts, fixUrl, fixSize, date });
      openDeployStream(streamTicket, {
        onLine: (l) => setLines((p) => [...p, l]),
        onHost: (h) => setRail((p) => ({ ...p, [h.host]: { ...(p[h.host] ?? {}), [h.action]: h.state } })),
        onComplete: (c) => { setResult(c.rows); setDone(true); qc.invalidateQueries(); },
        onError: (m) => { flash(m, C.stop); setDone(true); },
      });
    } catch (e) {
      flash(e instanceof ApiError ? e.message : 'run failed', C.stop);
      setStep('confirm');
    }
  }

  if (!cat) return <main style={{ padding: 24, color: 'var(--color-neutral-500)' }}>loading…</main>;
  const allUisOn = uis.length === cat.uis.length && cat.uis.length > 0;
  const allHostsOn = hosts.length === allHosts.length && allHosts.length > 0;

  // ---------- BUILD ----------
  if (step === 'build') return (
    <main style={{ padding: '0 24px 48px', maxWidth: 1500 }}>
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 14, padding: '20px 0 14px', flexWrap: 'wrap' }}>
        <h3 style={{ margin: 0, color: 'var(--color-neutral-100)' }}>{heading}</h3>
        <div style={{ fontFamily: 'var(--font-heading)', fontWeight: 600, fontSize: 10, letterSpacing: '.12em', color: 'var(--color-neutral-500)' }}>{subtitle}</div>
      </div>
      <div style={{ display: 'flex', gap: 10, alignItems: 'center', padding: '10px 12px', marginBottom: 4, background: 'var(--color-neutral-900)', fontSize: 12, color: 'var(--color-neutral-300)' }}>
        <span style={{ width: 9, height: 9, background: C.run, flex: 'none' }} />Portal UIs: <span style={{ fontFamily: mono, color: 'var(--color-neutral-100)' }}>{cat.uis.join(' · ')}</span>. Maps to <span style={{ fontFamily: mono, color: 'var(--color-neutral-100)' }}>portalui/run.sh</span>.
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 420px', gap: 36, borderTop: rule2, paddingTop: 22, alignItems: 'start' }}>
        <div style={{ display: 'grid', gap: 24 }}>
          <div>
            <div style={{ display: 'flex', alignItems: 'baseline', gap: 12, marginBottom: 8 }}>
              <h6 style={{ color: 'var(--color-neutral-400)', margin: 0 }}>1 — UIs <span style={{ fontWeight: 400, letterSpacing: 0, textTransform: 'none', color: 'var(--color-neutral-500)' }}>— multi-select</span></h6>
              <button onClick={() => setUis(allUisOn ? [] : [...cat.uis])} style={linkBtn}>{allUisOn ? 'CLEAR' : 'SELECT ALL'}</button>
            </div>
            <div style={{ display: 'flex', gap: 2, flexWrap: 'wrap' }}>
              {cat.uis.map((u) => { const on = uis.includes(u); return (
                <button key={u} onClick={() => setUis((p) => toggle(p, u))} style={pill(on, '7px 14px', 12)}>{u}</button>
              ); })}
            </div>
          </div>

          {modes.length > 1 && (
            <div>
              <h6 style={{ color: 'var(--color-neutral-400)', margin: '0 0 8px' }}>2 — MODE</h6>
              <div style={{ display: 'flex', gap: 2, flexWrap: 'wrap' }}>
                {modes.map((m) => { const on = m === mode; return (
                  <button key={m} onClick={() => { setMode(m); setHosts([]); }} style={pill(on, '9px 14px', 11.5)}>{MODE_LABEL[m] ?? m}</button>
                ); })}
              </div>
            </div>
          )}

          {scopeHosts && (
            <div>
              <div style={{ display: 'flex', alignItems: 'baseline', gap: 12, marginBottom: 8 }}>
                <h6 style={{ color: 'var(--color-neutral-400)', margin: 0 }}>{modes.length > 1 ? '3' : '2'} — HOSTS <span style={{ fontWeight: 400, letterSpacing: 0, textTransform: 'none', color: 'var(--color-neutral-500)' }}>— empty = all DMZ hosts</span></h6>
                <button onClick={() => setHosts(allHostsOn ? [] : [...allHosts])} style={linkBtn}>{allHostsOn ? 'CLEAR' : 'SELECT ALL'}</button>
              </div>
              <div style={{ display: 'flex', gap: 2, flexWrap: 'wrap' }}>
                {cat.prodHosts.map((h) => { const on = hosts.includes(h.host); return (
                  <button key={h.host} onClick={() => setHosts((p) => toggle(p, h.host))} title={h.ip} style={pill(on, '7px 12px', 11.5)}>{h.host}</button>
                ); })}
              </div>
            </div>
          )}
          {!scopeHosts && (
            <div style={{ fontSize: 12, color: 'var(--color-neutral-500)' }}>
              {mode === 'fetch' ? 'Fetch reads from staging' : 'Verify compares the fetched tar against live staging'} — <span style={{ fontFamily: mono, color: 'var(--color-neutral-300)' }}>{cat.staging.host}</span> ({cat.staging.ip}).
            </div>
          )}

          {showFixes && (
            <div>
              <h6 style={{ color: 'var(--color-neutral-400)', margin: '0 0 8px' }}>{modes.length > 1 ? '4' : '3'} — FIXES</h6>
              <div style={{ display: 'flex', gap: 2 }}>
                {([['url-fix', fixUrl, setFixUrl, 'rewrite test→prod hosts in main-*.js'], ['size-fix', fixSize, setFixSize, 'set MAX_FILE_SIZE to 10 MB']] as const).map(([label, on, set, desc]) => (
                  <button key={label} onClick={() => set(!on)} title={desc} style={{ display: 'flex', alignItems: 'center', gap: 10, border: '1px solid color-mix(in srgb, var(--color-neutral-100) 20%, transparent)', background: 'transparent', cursor: 'pointer', padding: '12px 18px', minWidth: 170, justifyContent: 'flex-start' }}>
                    <div style={{ width: 14, height: 14, border: '1px solid var(--color-neutral-400)', background: on ? 'var(--color-neutral-100)' : 'transparent' }} />
                    <div style={{ fontFamily: mono, fontSize: 13, color: on ? 'var(--color-neutral-100)' : 'var(--color-neutral-500)' }}>{label}</div>
                  </button>
                ))}
              </div>
              <div style={{ fontSize: 11, color: 'var(--color-neutral-500)', marginTop: 6 }}>Deselect both = raw deploy (<span style={{ fontFamily: mono }}>--no-fix</span>).</div>
            </div>
          )}

          {showDate && (
            <div>
              <h6 style={{ color: 'var(--color-neutral-400)', margin: '0 0 8px' }}>{mode === 'rollback' ? 'RESTORE BACKUP' : 'BACKUP DATE'} <span style={{ fontWeight: 400, letterSpacing: 0, textTransform: 'none', color: 'var(--color-neutral-500)' }}>— DDMMYYYY, optional ({mode === 'rollback' ? 'newest' : 'today'} if blank)</span></h6>
              <input value={date} onChange={(e) => setDate(e.target.value.replace(/[^0-9]/g, '').slice(0, 8))} placeholder={mode === 'rollback' ? 'newest backup' : 'today'}
                style={inputStyle} />
            </div>
          )}
        </div>

        <div style={{ display: 'grid', gap: 18, position: 'sticky', top: 20 }}>
          <div>
            <h6 style={{ color: 'var(--color-neutral-400)', margin: '0 0 6px' }}>COMMAND</h6>
            <div style={{ background: 'color-mix(in srgb, black 40%, var(--color-text))', padding: 14, fontFamily: mono, fontSize: 12.5, color: 'var(--color-neutral-100)', lineHeight: 1.5, wordBreak: 'break-all' }}>$ {cmd}</div>
          </div>
          <div>
            <h6 style={{ color: 'var(--color-neutral-400)', margin: '0 0 6px' }}>SUMMARY</h6>
            <div style={{ borderTop: rule2 }}>
              {[`mode: ${mode}`, `UIs: ${uis.join(', ') || '—'}`, `${scopeHosts ? 'hosts' : 'source'}: ${railHosts.join(', ') || '—'}`,
                ...(showFixes ? [`fixes: ${fixUrl ? 'url-fix' : '—'}${fixSize ? ' · size-fix' : ''}`] : [])].map((b, i) => (
                <div key={i} style={{ padding: '8px 2px', borderBottom: rule1, fontSize: 12.5, color: 'var(--color-neutral-300)' }}>{b}</div>
              ))}
            </div>
          </div>
          <button onClick={() => { setTyped(''); setStep('confirm'); }} disabled={!canReview} style={reviewBtn(canReview)}>REVIEW →</button>
          <div style={{ fontSize: 11, color: 'var(--color-neutral-500)' }}>{!me?.x ? 'Your role can build but not execute (no x permission).' : 'Nothing runs until you confirm on the next step.'}</div>
        </div>
      </div>
    </main>
  );

  // ---------- CONFIRM ----------
  if (step === 'confirm') return (
    <main style={{ padding: '0 24px 48px', maxWidth: 940, margin: '0 auto' }}>
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 14, padding: '20px 0 14px' }}>
        <h3 style={{ margin: 0, color: 'var(--color-neutral-100)' }}>Review</h3>
        <div style={{ fontFamily: 'var(--font-heading)', fontWeight: 600, fontSize: 10, letterSpacing: '.12em', color: 'var(--color-neutral-500)' }}>NOTHING HAS RUN YET</div>
      </div>
      <div style={{ borderTop: rule2, paddingTop: 20, display: 'grid', gap: 20 }}>
        <div style={{ background: 'color-mix(in srgb, black 40%, var(--color-text))', padding: 14, fontFamily: mono, fontSize: 12.5, color: 'var(--color-neutral-100)', wordBreak: 'break-all' }}>$ {cmd}</div>
        <div>
          <h6 style={{ color: 'var(--color-neutral-400)', margin: '0 0 8px' }}>PORTAL-UI {mode.toUpperCase()}</h6>
          <div style={{ borderTop: rule2 }}>
            {[['Mode', mode], ['UIs', uis.join(', ')], [scopeHosts ? 'Hosts' : 'Source', railHosts.join(', ')],
              ...(showFixes ? [['Fixes', `${fixUrl ? 'url-fix' : 'no url-fix'} · ${fixSize ? 'MAX_FILE_SIZE=10MB' : 'no size-fix'}`] as [string, string]] : []),
              ...(date ? [[mode === 'rollback' ? 'Restore backup' : 'Backup date', date] as [string, string]] : []),
            ].map(([k, v], i) => (
              <div key={i} style={{ display: 'grid', gridTemplateColumns: '150px 1fr', gap: 12, padding: '8px 2px', borderBottom: rule1, fontFamily: mono, fontSize: 12.5 }}>
                <div style={{ color: 'var(--color-neutral-500)' }}>{k}</div><div style={{ color: 'var(--color-neutral-100)' }}>{v || '—'}</div>
              </div>
            ))}
          </div>
        </div>
        <div>
          {[`Runs ${mode} on ${railHosts.length} ${scopeHosts ? 'DMZ' : 'staging'} host(s): ${railHosts.join(', ')}.`,
            ...(mode === 'deploy' ? ['Each host is backed up before extract; url-fix rewrites test→prod hosts in main-*.js.']
              : mode === 'rollback' ? ['Current UI is snapshotted before the backup is restored.'] : []),
            'Report emailed to devops-team@nagad.com.bd and written to the audit log.'].map((r, i) => (
            <div key={i} style={{ display: 'flex', gap: 12, padding: '8px 2px', borderBottom: rule1, fontSize: 13, color: 'var(--color-neutral-300)' }}>
              <div style={{ width: 12, height: 12, background: 'var(--color-neutral-700)', marginTop: 3, flex: 'none' }} />{r}</div>
          ))}
        </div>
        {needType && (
          <div>
            <div style={{ fontSize: 12.5, color: 'var(--color-neutral-300)', marginBottom: 6 }}>This touches <strong>{railHosts.length} hosts</strong>. Type <span style={{ fontFamily: mono, color: 'var(--color-neutral-100)' }}>{mode}</span> to arm:</div>
            <input value={typed} onChange={(e) => setTyped(e.target.value)} placeholder={mode} style={{ ...inputStyle, width: 240 }} />
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
            <h3 style={{ margin: 0, color: 'var(--color-neutral-100)' }}>Running — {mode}</h3>
            <div style={{ flex: 1 }} />
            {done && <>
              <button onClick={reset} style={{ border: '1px solid color-mix(in srgb, var(--color-neutral-100) 30%, transparent)', background: 'transparent', color: 'var(--color-neutral-200)', cursor: 'pointer', padding: '10px 16px', fontFamily: 'var(--font-heading)', fontWeight: 600, fontSize: 11, letterSpacing: '.1em' }}>↺ NEW ACTION</button>
              <button onClick={() => setStep('result')} style={{ border: 0, background: 'var(--color-neutral-100)', color: 'var(--color-text)', cursor: 'pointer', padding: '10px 18px', fontFamily: 'var(--font-heading)', fontWeight: 800, fontSize: 11, letterSpacing: '.1em' }}>VIEW RESULT →</button>
            </>}
          </div>
          <div style={{ display: 'grid', gridTemplateColumns: '280px 1fr', gap: 2, borderTop: rule2, paddingTop: 16, alignItems: 'start' }}>
            <div style={{ background: 'var(--color-neutral-900)', padding: 12 }}>
              <h6 style={{ color: 'var(--color-neutral-400)', margin: '0 0 8px' }}>PER HOST</h6>
              {railHosts.map((h) => (
                <div key={h} style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '7px 0', borderBottom: rule1 }}>
                  <div style={{ fontFamily: mono, fontSize: 12, color: 'var(--color-neutral-100)', width: 120, overflow: 'hidden', textOverflow: 'ellipsis' }}>{h}</div>
                  {phases.map((a) => { const st = rail[h]?.[a] ?? 'pending';
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
            <h3 style={{ margin: 0, color: 'var(--color-neutral-100)' }}>Completed — {mode}</h3>
            <div style={{ fontFamily: mono, fontSize: 12, color: 'var(--color-neutral-400)' }}>{result.length} host×ui</div>
          </div>
          <div style={{ display: 'grid', gridTemplateColumns: '160px 160px 1fr 120px', gap: 12, padding: '6px 8px', borderTop: rule2, borderBottom: rule1, fontFamily: 'var(--font-heading)', fontWeight: 600, fontSize: 9.5, letterSpacing: '.1em', color: 'var(--color-neutral-500)' }}>
            <div>HOST</div><div>UI</div><div>OUTCOME</div><div>RESULT</div>
          </div>
          {result.map((rw, i) => (
            <div key={i} style={{ display: 'grid', gridTemplateColumns: '160px 160px 1fr 120px', gap: 12, padding: 8, borderBottom: rule1, fontFamily: mono, fontSize: 12, alignItems: 'center' }}>
              <div style={{ color: 'var(--color-neutral-100)' }}>{rw.host}</div>
              <div style={{ color: 'var(--color-neutral-300)' }}>{rw.app}</div>
              <div style={{ color: 'var(--color-neutral-300)' }}>{rw.after}</div>
              <div style={{ justifySelf: 'start', padding: '2px 8px', background: rw.verdict === 'changed' ? C.run : 'transparent', color: rw.verdict === 'changed' ? C.inkOnGreen : 'var(--color-neutral-500)', border: rw.verdict === 'changed' ? 'none' : '1px solid var(--color-neutral-700)', fontFamily: 'var(--font-heading)', fontWeight: 800, fontSize: 9.5, letterSpacing: '.1em' }}>{rw.verdict === 'changed' ? 'DONE' : rw.verdict.toUpperCase()}</div>
            </div>
          ))}
          <div style={{ display: 'flex', gap: 12, alignItems: 'center', marginTop: 18 }}>
            <button onClick={reset} style={{ border: '1px solid color-mix(in srgb, var(--color-neutral-100) 30%, transparent)', background: 'transparent', color: 'var(--color-neutral-200)', cursor: 'pointer', padding: '11px 16px', fontFamily: 'var(--font-heading)', fontWeight: 600, fontSize: 11, letterSpacing: '.1em' }}>NEW ACTION</button>
            <div style={{ fontSize: 11.5, color: 'var(--color-neutral-500)' }}>Report emailed to devops-team@nagad.com.bd · logged to the audit trail.</div>
          </div>
        </div>
      )}
    </main>
  );
}

const pill = (on: boolean, padding: string, fontSize: number): React.CSSProperties => ({
  border: '1px solid color-mix(in srgb, var(--color-neutral-100) 20%, transparent)',
  background: on ? 'var(--color-neutral-100)' : 'transparent', color: on ? 'var(--color-text)' : 'var(--color-neutral-300)',
  cursor: 'pointer', padding, fontFamily: mono, fontSize, textAlign: 'left',
});
const inputStyle: React.CSSProperties = { width: 200, background: 'var(--color-neutral-900)', border: '1px solid color-mix(in srgb, var(--color-neutral-100) 25%, transparent)', color: 'var(--color-neutral-100)', padding: '9px 10px', fontFamily: mono, fontSize: 13 };
const reviewBtn = (enabled: boolean): React.CSSProperties => ({ border: 0, background: 'var(--color-neutral-100)', color: 'var(--color-text)', cursor: 'pointer', padding: '13px 18px', fontFamily: 'var(--font-heading)', fontWeight: 800, fontSize: 12, letterSpacing: '.1em', display: 'flex', justifyContent: 'flex-start', opacity: enabled ? 1 : 0.5 });
const linkBtn: React.CSSProperties = { border: 0, background: 'transparent', color: 'var(--color-accent-400)', cursor: 'pointer', fontFamily: 'var(--font-heading)', fontWeight: 600, fontSize: 10, letterSpacing: '.1em', padding: 0 };
