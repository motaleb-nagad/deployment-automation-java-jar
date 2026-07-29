import { useEffect, useMemo, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError, openDeployStream, uploadFile, type ResultRow } from '../api/client';
import { useApp } from '../store/app';
import { C, rule1, rule2, TERM } from '../theme/colors';
import type { StgCatalog, StgUploadResponse } from '../api/types';

const mono = 'var(--mono)';
type Channel = 'app' | 'portal-ui';
type Step = 'build' | 'confirm' | 'running' | 'result';
interface TermLine { level: string; text: string; }
interface Upload { status: 'uploading' | 'done' | 'error'; fileName?: string; storedName?: string; error?: string; }

const UI_PHASES = ['copy', 'backup', 'deploy'];

/**
 * The STAGING deployment console (maps to /stg-deployment). Unlike the prod Deploy screen —
 * which fetches governed jars from staging — this channel is <em>upload-driven</em>: the operator
 * uploads a jar / application.properties / UI tarball, the portal stages it into the
 * {@code stg-deployment} bundle on the jump host, then runs the staging wrapper live.
 */
export function StgDeployment() {
  const { me, flash } = useApp();
  const qc = useQueryClient();
  const { data: cat } = useQuery({ queryKey: ['stg', 'catalog'], queryFn: () => api.get<StgCatalog>('/stg/catalog') });

  const [channel, setChannel] = useState<Channel>('app');
  const [step, setStep] = useState<Step>('build');
  const [group, setGroup] = useState<string>('core');
  const [apps, setApps] = useState<string[]>([]);
  const [actions, setActions] = useState<string[]>(['stop', 'deploy', 'start']);
  const [uis, setUis] = useState<string[]>([]);
  const [date, setDate] = useState('');

  // Uploads keyed by app (jar/cfg) or ui (tar). Placed on the jump host as they are selected.
  const [jarUp, setJarUp] = useState<Record<string, Upload>>({});
  const [cfgUp, setCfgUp] = useState<Record<string, Upload>>({});
  const [tarUp, setTarUp] = useState<Record<string, Upload>>({});

  const [lines, setLines] = useState<TermLine[]>([]);
  const [rail, setRail] = useState<Record<string, Record<string, string>>>({});
  const [result, setResult] = useState<ResultRow[]>([]);
  const [done, setDone] = useState(false);
  const termRef = useRef<HTMLDivElement>(null);

  useEffect(() => { if (termRef.current) termRef.current.scrollTop = termRef.current.scrollHeight; }, [lines]);

  const toggle = <T,>(arr: T[], v: T): T[] => (arr.includes(v) ? arr.filter((x) => x !== v) : [...arr, v]);

  const grp = useMemo(() => cat?.groups.find((g) => g.key === group) ?? cat?.groups[0], [cat, group]);
  const host = grp?.host ?? '';

  const cmd = channel === 'app'
    ? `./run.sh ${group} all ${apps.join(',') || '<apps>'} ${actions.join(',')}`
    : `./run.sh ${uis.join(',') || '<ui>'}${date ? ' ' + date : ''}`;

  const isDeploy = actions.includes('deploy');
  const missingJars = isDeploy ? apps.filter((a) => jarUp[a]?.status !== 'done') : [];
  const missingTars = uis.filter((u) => tarUp[u]?.status !== 'done');

  const canReview = channel === 'app'
    ? (apps.length > 0 && actions.length > 0 && missingJars.length === 0)
    : (uis.length > 0 && missingTars.length === 0);
  const canExec = !!me?.x;

  const railHosts = [host];
  const phases = channel === 'app' ? actions : UI_PHASES;

  function reset() {
    setStep('build'); setApps([]); setActions(['stop', 'deploy', 'start']); setUis([]); setDate('');
    setJarUp({}); setCfgUp({}); setTarUp({});
    setLines([]); setRail({}); setResult([]); setDone(false);
  }

  async function doUpload(kind: 'jar' | 'cfg' | 'portalui', target: string, file: File,
                         set: React.Dispatch<React.SetStateAction<Record<string, Upload>>>) {
    if (!me?.w) { flash('Uploading needs the write (w) permission', C.stop); return; }
    set((p) => ({ ...p, [target]: { status: 'uploading', fileName: file.name } }));
    try {
      const fields: Record<string, string> = { kind, target };
      if (kind !== 'portalui') fields.group = group;
      const res = await uploadFile<StgUploadResponse>('/stg/upload', file, fields);
      set((p) => ({ ...p, [target]: { status: 'done', fileName: file.name, storedName: res.storedName } }));
      flash(`Staged ${res.storedName}`);
    } catch (e) {
      const msg = e instanceof ApiError ? e.message : 'upload failed';
      set((p) => ({ ...p, [target]: { status: 'error', fileName: file.name, error: msg } }));
      flash(msg, C.stop);
    }
  }

  async function execute() {
    setStep('running'); setLines([]); setRail({}); setDone(false);
    try {
      const path = channel === 'app' ? '/stg/deploy' : '/stg/portal-ui';
      const body = channel === 'app' ? { group, apps, actions } : { uis, date };
      const { streamTicket } = await api.post<{ deploymentId: string; streamTicket: string }>(path, body);
      openDeployStream(streamTicket, {
        onLine: (l) => setLines((p) => [...p, l]),
        onHost: (h) => setRail((p) => ({ ...p, [h.host]: { ...(p[h.host] ?? {}), [h.action]: h.state } })),
        onComplete: (c) => { setResult(c.rows); setDone(true); qc.invalidateQueries(); },
        onError: (m) => { flash(m, C.stop); setDone(true); },
      }, '/stg/stream');
    } catch (e) {
      flash(e instanceof ApiError ? e.message : 'staging run failed', C.stop);
      setStep('confirm');
    }
  }

  if (!cat) return <main style={{ padding: 24, color: 'var(--color-neutral-500)' }}>loading…</main>;

  const allAppsOn = grp && apps.length === grp.apps.length && grp.apps.length > 0;
  const allUisOn = uis.length === cat.uis.length && cat.uis.length > 0;

  const channelSwitch = (
    <div style={{ display: 'flex', border: '1px solid color-mix(in srgb, var(--color-neutral-100) 30%, transparent)', flex: 'none' }}>
      {([['app', 'APP · JAR / CONFIG'], ['portal-ui', 'PORTAL-UI']] as const).map(([m, label]) => (
        <button key={m} onClick={() => { setChannel(m); setStep('build'); }} style={{ border: 0, cursor: 'pointer', padding: '6px 12px',
          fontFamily: 'var(--font-heading)', fontWeight: 700, fontSize: 9.5, letterSpacing: '.1em',
          background: channel === m ? 'var(--color-neutral-100)' : 'transparent', color: channel === m ? 'var(--color-text)' : 'var(--color-neutral-500)' }}>{label}</button>
      ))}
    </div>
  );

  // ---------- BUILD ----------
  if (step === 'build') return (
    <main style={{ padding: '0 24px 48px', maxWidth: 1500 }}>
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 14, padding: '20px 0 14px', flexWrap: 'wrap' }}>
        <h3 style={{ margin: 0, color: 'var(--color-neutral-100)' }}>Staging deployment</h3>
        <div style={{ fontFamily: 'var(--font-heading)', fontWeight: 600, fontSize: 10, letterSpacing: '.12em', color: 'var(--color-neutral-500)' }}>
          STEP 1 OF 3 — UPLOAD THEN RUN · {channel === 'app' ? 'MAPS TO ./run.sh' : 'MAPS TO portalui/run.sh'}
        </div>
        <div style={{ flex: 1 }} />
        {channelSwitch}
      </div>

      <div style={{ display: 'flex', gap: 10, alignItems: 'center', padding: '10px 12px', marginBottom: 4, background: 'var(--color-neutral-900)', fontSize: 12, color: 'var(--color-neutral-300)' }}>
        <span style={{ width: 9, height: 9, background: C.run, flex: 'none' }} />
        {channel === 'app'
          ? <>Uploads land in <span style={{ fontFamily: mono, color: 'var(--color-neutral-100)' }}>roles/deployment/files/jars</span> (jars) and <span style={{ fontFamily: mono, color: 'var(--color-neutral-100)' }}>roles/deployment/files/cfg</span> (config) on <span style={{ fontFamily: mono, color: 'var(--color-neutral-100)' }}>{host}</span>, then <span style={{ fontFamily: mono, color: 'var(--color-neutral-100)' }}>./run.sh</span> runs stop/deploy/start.</>
          : <>UI tarballs land in <span style={{ fontFamily: mono, color: 'var(--color-neutral-100)' }}>portalui/roles/portalui/files</span> on <span style={{ fontFamily: mono, color: 'var(--color-neutral-100)' }}>{cat.groups.find((g) => g.key === 'portal')?.host}</span>, then <span style={{ fontFamily: mono, color: 'var(--color-neutral-100)' }}>portalui/run.sh</span> deploys them as-is.</>}
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 420px', gap: 36, borderTop: rule2, paddingTop: 22, alignItems: 'start' }}>
        <div style={{ display: 'grid', gap: 24 }}>
          {channel === 'app' ? (
            <>
              <div>
                <h6 style={{ color: 'var(--color-neutral-400)', margin: '0 0 8px' }}>1 — GROUP</h6>
                <div style={{ display: 'flex', gap: 2 }}>
                  {cat.groups.map((g) => { const on = g.key === group; return (
                    <button key={g.key} onClick={() => { setGroup(g.key); setApps([]); }} title={`${g.host} · ${g.ip}`}
                      style={pill(on, '9px 16px', 12)}>{g.key}<span style={{ fontSize: 9.5, opacity: 0.65 }}> · {g.host}</span></button>
                  ); })}
                </div>
              </div>

              <div>
                <div style={{ display: 'flex', alignItems: 'baseline', gap: 12, marginBottom: 8 }}>
                  <h6 style={{ color: 'var(--color-neutral-400)', margin: 0 }}>2 — APPS <span style={{ fontWeight: 400, letterSpacing: 0, textTransform: 'none', color: 'var(--color-neutral-500)' }}>— validated against {group}.yml</span></h6>
                  <button onClick={() => setApps(allAppsOn ? [] : (grp?.apps.map((a) => a.key) ?? []))} style={linkBtn}>{allAppsOn ? 'CLEAR' : 'SELECT ALL'}</button>
                </div>
                <div style={{ display: 'flex', gap: 2, flexWrap: 'wrap' }}>
                  {grp?.apps.map((a) => { const on = apps.includes(a.key); return (
                    <button key={a.key} onClick={() => setApps((p) => toggle(p, a.key))} title={a.jar} style={pill(on, '7px 12px', 11.5)}>{a.key}</button>
                  ); })}
                </div>
              </div>

              <ActionPicker actions={actions} setActions={setActions} toggle={toggle} />

              {apps.length > 0 && (
                <div>
                  <h6 style={{ color: 'var(--color-neutral-400)', margin: '0 0 8px' }}>UPLOADS <span style={{ fontWeight: 400, letterSpacing: 0, textTransform: 'none', color: 'var(--color-neutral-500)' }}>— jar required for deploy · config optional</span></h6>
                  <div style={{ borderTop: rule1 }}>
                    {apps.map((a) => {
                      const jar = grp?.apps.find((x) => x.key === a)?.jar ?? '';
                      return (
                        <div key={a} style={{ display: 'grid', gridTemplateColumns: '140px 1fr 1fr', gap: 12, padding: '10px 2px', borderBottom: rule1, alignItems: 'center' }}>
                          <div style={{ fontFamily: mono, fontSize: 12.5, color: 'var(--color-neutral-100)' }}>{a}<div style={{ fontSize: 9.5, color: 'var(--color-neutral-500)' }}>{jar}</div></div>
                          <UploadCell label="jar" required={isDeploy} state={jarUp[a]} onFile={(f) => doUpload('jar', a, f, setJarUp)} disabled={!me?.w} />
                          <UploadCell label="application.properties" required={false} state={cfgUp[a]} onFile={(f) => doUpload('cfg', a, f, setCfgUp)} disabled={!me?.w} />
                        </div>
                      );
                    })}
                  </div>
                  {missingJars.length > 0 && (
                    <div style={{ fontSize: 11.5, color: C.warn, marginTop: 8 }}>Deploy needs a jar for: {missingJars.join(', ')}. Upload it, or drop the deploy action.</div>
                  )}
                </div>
              )}
            </>
          ) : (
            <>
              <div>
                <div style={{ display: 'flex', alignItems: 'baseline', gap: 12, marginBottom: 8 }}>
                  <h6 style={{ color: 'var(--color-neutral-400)', margin: 0 }}>1 — UIS <span style={{ fontWeight: 400, letterSpacing: 0, textTransform: 'none', color: 'var(--color-neutral-500)' }}>— multi-select</span></h6>
                  <button onClick={() => setUis(allUisOn ? [] : [...cat.uis])} style={linkBtn}>{allUisOn ? 'CLEAR' : 'SELECT ALL'}</button>
                </div>
                <div style={{ display: 'flex', gap: 2, flexWrap: 'wrap' }}>
                  {cat.uis.map((u) => { const on = uis.includes(u); return (
                    <button key={u} onClick={() => setUis((p) => toggle(p, u))} style={pill(on, '7px 14px', 12)}>{u}</button>
                  ); })}
                </div>
              </div>

              <div>
                <h6 style={{ color: 'var(--color-neutral-400)', margin: '0 0 8px' }}>BACKUP DATE <span style={{ fontWeight: 400, letterSpacing: 0, textTransform: 'none', color: 'var(--color-neutral-500)' }}>— DDMMYYYY, optional (today if blank)</span></h6>
                <input value={date} onChange={(e) => setDate(e.target.value.replace(/[^0-9]/g, '').slice(0, 8))} placeholder="today" style={inputStyle} />
              </div>

              {uis.length > 0 && (
                <div>
                  <h6 style={{ color: 'var(--color-neutral-400)', margin: '0 0 8px' }}>UPLOADS <span style={{ fontWeight: 400, letterSpacing: 0, textTransform: 'none', color: 'var(--color-neutral-500)' }}>— one .tar / .tar.gz per UI (stored as &lt;ui&gt;.tar)</span></h6>
                  <div style={{ borderTop: rule1 }}>
                    {uis.map((u) => (
                      <div key={u} style={{ display: 'grid', gridTemplateColumns: '160px 1fr', gap: 12, padding: '10px 2px', borderBottom: rule1, alignItems: 'center' }}>
                        <div style={{ fontFamily: mono, fontSize: 12.5, color: 'var(--color-neutral-100)' }}>{u}<div style={{ fontSize: 9.5, color: 'var(--color-neutral-500)' }}>{u}.tar</div></div>
                        <UploadCell label="tarball" required state={tarUp[u]} onFile={(f) => doUpload('portalui', u, f, setTarUp)} disabled={!me?.w} accept=".tar,.gz,.tgz" />
                      </div>
                    ))}
                  </div>
                  {missingTars.length > 0 && (
                    <div style={{ fontSize: 11.5, color: C.warn, marginTop: 8 }}>Upload a tarball for: {missingTars.join(', ')}.</div>
                  )}
                </div>
              )}
            </>
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
              {(channel === 'app'
                ? [`env: STAGING`, `group: ${group}`, `host: ${host}`, `apps: ${apps.join(', ') || '—'}`, `actions: ${actions.join(' → ')}`]
                : [`env: STAGING`, `host: ${cat.groups.find((g) => g.key === 'portal')?.host}`, `UIs: ${uis.join(', ') || '—'}`, `backup: ${date || 'today'}`]
              ).map((b, i) => (
                <div key={i} style={{ padding: '8px 2px', borderBottom: rule1, fontSize: 12.5, color: 'var(--color-neutral-300)' }}>{b}</div>
              ))}
            </div>
          </div>
          <button onClick={() => setStep('confirm')} disabled={!canReview} style={reviewBtn(canReview)}>REVIEW →</button>
          <div style={{ fontSize: 11, color: 'var(--color-neutral-500)' }}>
            {!me?.w ? 'Your role can view but not upload (no w permission).'
              : !me?.x ? 'Your role can build but not execute (no x permission).'
              : 'Nothing runs until you confirm on the next step.'}
          </div>
        </div>
      </div>
    </main>
  );

  // ---------- CONFIRM ----------
  if (step === 'confirm') {
    const uploadedRows = channel === 'app'
      ? apps.map((a) => [a, jarUp[a]?.storedName ?? (isDeploy ? '— missing' : 'no jar'), cfgUp[a]?.storedName ?? '—'] as [string, string, string])
      : uis.map((u) => [u, tarUp[u]?.storedName ?? '— missing', '—'] as [string, string, string]);
    return (
      <main style={{ padding: '0 24px 48px', maxWidth: 940, margin: '0 auto' }}>
        <div style={{ display: 'flex', alignItems: 'baseline', gap: 14, padding: '20px 0 14px' }}>
          <h3 style={{ margin: 0, color: 'var(--color-neutral-100)' }}>Review — staging</h3>
          <div style={{ fontFamily: 'var(--font-heading)', fontWeight: 600, fontSize: 10, letterSpacing: '.12em', color: 'var(--color-neutral-500)' }}>STEP 2 OF 3 — NOTHING HAS RUN YET</div>
        </div>
        <div style={{ borderTop: rule2, paddingTop: 20, display: 'grid', gap: 20 }}>
          <div style={{ background: 'color-mix(in srgb, black 40%, var(--color-text))', padding: 14, fontFamily: mono, fontSize: 12.5, color: 'var(--color-neutral-100)', wordBreak: 'break-all' }}>$ {cmd}</div>
          <div>
            <h6 style={{ color: 'var(--color-neutral-400)', margin: '0 0 8px' }}>STAGED FILES → {host}</h6>
            <div style={{ display: 'grid', gridTemplateColumns: '140px 1fr 1fr', gap: 12, padding: '6px 8px', borderTop: rule2, borderBottom: rule1, fontFamily: 'var(--font-heading)', fontWeight: 600, fontSize: 9.5, letterSpacing: '.1em', color: 'var(--color-neutral-500)' }}>
              <div>{channel === 'app' ? 'APP' : 'UI'}</div><div>{channel === 'app' ? 'JAR' : 'TARBALL'}</div><div>{channel === 'app' ? 'CONFIG' : ''}</div>
            </div>
            {uploadedRows.map(([k, v1, v2]) => (
              <div key={k} style={{ display: 'grid', gridTemplateColumns: '140px 1fr 1fr', gap: 12, padding: '9px 8px', borderBottom: rule1, fontFamily: mono, fontSize: 12, alignItems: 'center' }}>
                <div style={{ color: 'var(--color-neutral-100)' }}>{k}</div>
                <div style={{ color: v1.startsWith('—') ? C.warn : 'var(--color-neutral-300)' }}>{v1}</div>
                <div style={{ color: 'var(--color-neutral-400)' }}>{v2}</div>
              </div>
            ))}
          </div>
          <div>
            {[`Runs on the staging host ${host} (env STAGING).`,
              channel === 'app'
                ? (actions.includes('stop') ? 'Selected services go down during the deploy window.' : 'No stop phase — no downtime.')
                : 'Each UI is backed up on the server before the new tarball is extracted as-is.',
              'Report emailed to devops-team@nagad.com.bd and written to the audit log.'].map((r, i) => (
              <div key={i} style={{ display: 'flex', gap: 12, padding: '8px 2px', borderBottom: rule1, fontSize: 13, color: 'var(--color-neutral-300)' }}>
                <div style={{ width: 12, height: 12, background: 'var(--color-neutral-700)', marginTop: 3, flex: 'none' }} />{r}</div>
            ))}
          </div>
          <div style={{ display: 'flex', gap: 12, alignItems: 'center' }}>
            <button onClick={() => setStep('build')} style={editBtn}>← EDIT</button>
            <button onClick={execute} disabled={!canExec} style={{ border: 0, background: 'var(--color-accent)', color: 'var(--color-neutral-100)', cursor: 'pointer', padding: '12px 22px', fontFamily: 'var(--font-heading)', fontWeight: 800, fontSize: 12, letterSpacing: '.1em', opacity: canExec ? 1 : 0.5 }}>EXECUTE →</button>
            <div style={{ fontSize: 11, color: 'var(--color-neutral-500)' }}>{!me?.x ? 'Your role can build but not execute (no x permission).' : 'Streams live output from the staging host.'}</div>
          </div>
        </div>
      </main>
    );
  }

  // ---------- RUNNING / RESULT ----------
  return (
    <main style={{ padding: '0 24px 48px', maxWidth: 1500 }}>
      {step === 'running' && (
        <>
          <div style={{ display: 'flex', alignItems: 'baseline', gap: 14, padding: '20px 0 14px' }}>
            <h3 style={{ margin: 0, color: 'var(--color-neutral-100)' }}>Running — staging</h3>
            <div style={{ fontFamily: 'var(--font-heading)', fontWeight: 600, fontSize: 10, letterSpacing: '.12em', color: 'var(--color-neutral-500)' }}>STEP 3 OF 3</div>
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
                <div key={h} style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '7px 0', borderBottom: rule1, flexWrap: 'wrap' }}>
                  <div style={{ fontFamily: mono, fontSize: 12, color: 'var(--color-neutral-100)', width: 130, overflow: 'hidden', textOverflow: 'ellipsis' }}>{h}</div>
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
            <h3 style={{ margin: 0, color: 'var(--color-neutral-100)' }}>Completed — staging</h3>
            <div style={{ fontFamily: mono, fontSize: 12, color: 'var(--color-neutral-400)' }}>{result.length} {channel === 'app' ? 'service(s)' : 'ui(s)'}</div>
          </div>
          <div style={{ display: 'grid', gridTemplateColumns: '180px 200px 1fr 120px', gap: 12, padding: '6px 8px', borderTop: rule2, borderBottom: rule1, fontFamily: 'var(--font-heading)', fontWeight: 600, fontSize: 9.5, letterSpacing: '.1em', color: 'var(--color-neutral-500)' }}>
            <div>HOST</div><div>{channel === 'app' ? 'SERVICE' : 'UI'}</div><div>OUTCOME</div><div>RESULT</div>
          </div>
          {result.map((rw, i) => (
            <div key={i} style={{ display: 'grid', gridTemplateColumns: '180px 200px 1fr 120px', gap: 12, padding: 8, borderBottom: rule1, fontFamily: mono, fontSize: 12, alignItems: 'center' }}>
              <div style={{ color: 'var(--color-neutral-100)' }}>{rw.host}</div>
              <div style={{ color: 'var(--color-neutral-300)' }}>{rw.app}</div>
              <div style={{ color: 'var(--color-neutral-300)' }}>{rw.after}</div>
              <div style={{ justifySelf: 'start', padding: '2px 8px', background: rw.verdict === 'changed' ? C.run : 'transparent', color: rw.verdict === 'changed' ? C.inkOnGreen : 'var(--color-neutral-500)', border: rw.verdict === 'changed' ? 'none' : '1px solid var(--color-neutral-700)', fontFamily: 'var(--font-heading)', fontWeight: 800, fontSize: 9.5, letterSpacing: '.1em' }}>{rw.verdict === 'changed' ? 'DONE' : rw.verdict.toUpperCase()}</div>
            </div>
          ))}
          <div style={{ display: 'flex', gap: 12, alignItems: 'center', marginTop: 18 }}>
            <button onClick={reset} style={editBtn}>NEW ACTION</button>
            <div style={{ fontSize: 11.5, color: 'var(--color-neutral-500)' }}>Report emailed to devops-team@nagad.com.bd · logged to the audit trail.</div>
          </div>
        </div>
      )}
    </main>
  );
}

/** A single upload slot: pick a file → it is staged on the jump host immediately. */
function UploadCell({ label, required, state, onFile, disabled, accept }: {
  label: string; required: boolean; state?: Upload;
  onFile: (f: File) => void; disabled: boolean; accept?: string;
}) {
  const ref = useRef<HTMLInputElement>(null);
  const st = state?.status;
  const dot = st === 'done' ? C.run : st === 'error' ? C.stop : st === 'uploading' ? C.warn : 'var(--color-neutral-600)';
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
      <input ref={ref} type="file" accept={accept} style={{ display: 'none' }}
        onChange={(e) => { const f = e.target.files?.[0]; if (f) onFile(f); e.target.value = ''; }} />
      <button onClick={() => ref.current?.click()} disabled={disabled} style={{
        border: '1px solid color-mix(in srgb, var(--color-neutral-100) 25%, transparent)', background: 'transparent',
        color: disabled ? 'var(--color-neutral-600)' : 'var(--color-neutral-200)', cursor: disabled ? 'not-allowed' : 'pointer',
        padding: '7px 12px', fontFamily: 'var(--font-heading)', fontWeight: 600, fontSize: 10, letterSpacing: '.08em', whiteSpace: 'nowrap' }}>
        {st === 'done' ? 'REPLACE' : `UPLOAD ${label.toUpperCase()}`}{required && st !== 'done' ? ' *' : ''}
      </button>
      <div style={{ width: 8, height: 8, background: dot, flex: 'none' }} />
      <div style={{ fontFamily: mono, fontSize: 11, color: 'var(--color-neutral-400)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
        {st === 'uploading' ? `uploading ${state?.fileName}…`
          : st === 'done' ? state?.storedName
          : st === 'error' ? (state?.error ?? 'failed')
          : required ? 'required' : 'optional'}
      </div>
    </div>
  );
}

function ActionPicker({ actions, setActions, toggle }: {
  actions: string[]; setActions: React.Dispatch<React.SetStateAction<string[]>>;
  toggle: <T,>(arr: T[], v: T) => T[];
}) {
  return (
    <div>
      <h6 style={{ color: 'var(--color-neutral-400)', margin: '0 0 8px' }}>ACTIONS</h6>
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
  );
}

const pill = (on: boolean, padding: string, fontSize: number): React.CSSProperties => ({
  border: '1px solid color-mix(in srgb, var(--color-neutral-100) 20%, transparent)',
  background: on ? 'var(--color-neutral-100)' : 'transparent', color: on ? 'var(--color-text)' : 'var(--color-neutral-300)',
  cursor: 'pointer', padding, fontFamily: mono, fontSize, textAlign: 'left',
});
const inputStyle: React.CSSProperties = { width: 200, background: 'var(--color-neutral-900)', border: '1px solid color-mix(in srgb, var(--color-neutral-100) 25%, transparent)', color: 'var(--color-neutral-100)', padding: '9px 10px', fontFamily: mono, fontSize: 13 };
const reviewBtn = (enabled: boolean): React.CSSProperties => ({ border: 0, background: 'var(--color-neutral-100)', color: 'var(--color-text)', cursor: enabled ? 'pointer' : 'not-allowed', padding: '13px 18px', fontFamily: 'var(--font-heading)', fontWeight: 800, fontSize: 12, letterSpacing: '.1em', display: 'flex', justifyContent: 'flex-start', opacity: enabled ? 1 : 0.5 });
const editBtn: React.CSSProperties = { border: '1px solid color-mix(in srgb, var(--color-neutral-100) 30%, transparent)', background: 'transparent', color: 'var(--color-neutral-200)', cursor: 'pointer', padding: '12px 18px', fontFamily: 'var(--font-heading)', fontWeight: 600, fontSize: 11, letterSpacing: '.1em' };
const linkBtn: React.CSSProperties = { border: 0, background: 'transparent', color: 'var(--color-accent-400)', cursor: 'pointer', fontFamily: 'var(--font-heading)', fontWeight: 600, fontSize: 10, letterSpacing: '.1em', padding: 0 };
