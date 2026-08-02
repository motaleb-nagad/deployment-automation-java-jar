import { useEffect, useMemo, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError, openDeployStream, uploadFile, type ResultRow } from '../api/client';
import { useApp } from '../store/app';
import { C, rule1, rule2, TERM } from '../theme/colors';
import type { StgCatalog, StgUploadResponse } from '../api/types';
import { StgProperties } from './StgProperties';
import { StgHistory } from './StgHistory';

const mono = 'var(--mono)';
type StgView = 'deploy' | 'properties' | 'history';
type Channel = 'app' | 'portal-ui';
type Step = 'build' | 'confirm' | 'running' | 'result';
interface TermLine { level: string; text: string; }

// The staging flow is four ordered phases; `upload` stages the file(s) on the ops host, then
// the run.sh phases run on the target. Each is independently selectable — pick only `upload`
// to just stage a build, or add stop/deploy/start to also run it.
const APP_PHASES = ['upload', 'stop', 'deploy', 'start'];
const UI_PHASES = ['upload', 'deploy'];
const RUN_ACTIONS = new Set(['stop', 'deploy', 'start']);

/** SHA-256 of a file, lower-case hex — computed in the browser so the jar hash can be shown
 *  in the review before anything is uploaded. Matches the sha256 the backend records on stage. */
async function sha256File(file: File): Promise<string> {
  const buf = await file.arrayBuffer();
  const digest = await crypto.subtle.digest('SHA-256', buf);
  return Array.from(new Uint8Array(digest)).map((b) => b.toString(16).padStart(2, '0')).join('');
}
const shortHash = (h: string) => (h ? h.slice(0, 12) + '…' + h.slice(-8) : '');

/**
 * The STAGING deployment console (maps to /stg-deployment). Upload-driven: files are selected
 * in the build step and only staged onto the jump host when you EXECUTE — as the first phase —
 * followed by the stop/deploy/start (or portal-ui deploy) run. `upload` is a first-class,
 * selectable phase, so an upload-only run just stages the build without touching the service.
 */
export function StgDeployment() {
  const { me, flash } = useApp();
  const [stgView, setStgView] = useState<StgView>('deploy');
  const qc = useQueryClient();
  const { data: cat } = useQuery({ queryKey: ['stg', 'catalog'], queryFn: () => api.get<StgCatalog>('/stg/catalog') });

  const [channel, setChannel] = useState<Channel>('app');
  const [step, setStep] = useState<Step>('build');
  const [group, setGroup] = useState<string>('core');
  const [apps, setApps] = useState<string[]>([]);
  const [actions, setActions] = useState<string[]>([...APP_PHASES]);
  const [uis, setUis] = useState<string[]>([]);
  const [date, setDate] = useState('');

  // Files CHOSEN in the browser (not yet uploaded) — staged during the run's upload phase.
  const [jarFile, setJarFile] = useState<Record<string, File>>({});
  const [cfgFile, setCfgFile] = useState<Record<string, File>>({});
  const [tarFile, setTarFile] = useState<Record<string, File>>({});
  // SHA-256 of each chosen file, keyed by `${kind}:${target}` ('' while still hashing).
  const [fileHash, setFileHash] = useState<Record<string, string>>({});

  const [lines, setLines] = useState<TermLine[]>([]);
  const [rail, setRail] = useState<Record<string, Record<string, string>>>({});
  const [result, setResult] = useState<ResultRow[]>([]);
  const [done, setDone] = useState(false);
  const termRef = useRef<HTMLDivElement>(null);

  useEffect(() => { if (termRef.current) termRef.current.scrollTop = termRef.current.scrollHeight; }, [lines]);

  const toggle = <T,>(arr: T[], v: T): T[] => (arr.includes(v) ? arr.filter((x) => x !== v) : [...arr, v]);

  // Record a chosen file and compute its SHA-256 in the background so the review can show the hash.
  function pick(kind: 'jar' | 'cfg' | 'tar', target: string,
                setter: React.Dispatch<React.SetStateAction<Record<string, File>>>, f: File) {
    setter((p) => ({ ...p, [target]: f }));
    setFileHash((p) => ({ ...p, [`${kind}:${target}`]: '' }));
    sha256File(f)
      .then((h) => setFileHash((p) => ({ ...p, [`${kind}:${target}`]: h })))
      .catch(() => setFileHash((p) => ({ ...p, [`${kind}:${target}`]: 'unavailable' })));
  }

  const grp = useMemo(() => cat?.groups.find((g) => g.key === group) ?? cat?.groups[0], [cat, group]);
  const portalHost = cat?.groups.find((g) => g.key === 'portal')?.host ?? 'ngd-dc-portal-01';
  const host = channel === 'app' ? (grp?.host ?? '') : portalHost;

  const phaseOrder = channel === 'app' ? APP_PHASES : UI_PHASES;
  const selectedPhases = phaseOrder.filter((p) => actions.includes(p));
  const runActions = actions.filter((a) => RUN_ACTIONS.has(a));

  const runCmd = channel === 'app'
    ? `./run.sh ${group} all ${apps.join(',') || '<apps>'} ${runActions.join(',')}`
    : `./run.sh ${uis.join(',') || '<ui>'}${date ? ' ' + date : ''}`;
  const willRun = channel === 'app' ? runActions.length > 0 : actions.includes('deploy');

  // upload-phase requirements
  const missingUploads = channel === 'app'
    ? (actions.includes('upload') ? apps.filter((a) => !jarFile[a] && !cfgFile[a]) : [])
    : (actions.includes('upload') ? uis.filter((u) => !tarFile[u]) : []);
  // deploy without a staged jar in this session AND without an upload phase → warn
  const deployNoJar = channel === 'app' && actions.includes('deploy') && !actions.includes('upload');

  const targets = channel === 'app' ? apps : uis;
  const canReview = targets.length > 0 && selectedPhases.length > 0 && missingUploads.length === 0;
  // upload needs write (w); running (stop/deploy/start) needs execute (x).
  const canExec = (!actions.includes('upload') || !!me?.w) && (!willRun || !!me?.x);

  const railHosts = [host];

  function reset() {
    setStep('build'); setApps([]); setActions([...(channel === 'app' ? APP_PHASES : UI_PHASES)]);
    setUis([]); setDate(''); setJarFile({}); setCfgFile({}); setTarFile({}); setFileHash({});
    setLines([]); setRail({}); setResult([]); setDone(false);
  }

  function switchChannel(c: Channel) {
    setChannel(c); setStep('build'); setActions([...(c === 'app' ? APP_PHASES : UI_PHASES)]);
    setApps([]); setUis([]); setJarFile({}); setCfgFile({}); setTarFile({}); setFileHash({});
  }

  const appendLine = (level: string, text: string) => setLines((p) => [...p, { level, text }]);
  const setPhase = (h: string, phase: string, state: string) =>
    setRail((p) => ({ ...p, [h]: { ...(p[h] ?? {}), [phase]: state } }));

  async function stageOne(kind: 'jar' | 'cfg' | 'portalui', target: string, file: File) {
    const fields: Record<string, string> = { kind, target };
    if (kind !== 'portalui') fields.group = group;
    const res = await uploadFile<StgUploadResponse>('/stg/upload', file, fields);
    appendLine('ch', `staged ${file.name} -> ${res.targetPath}`);
    if (res.sha256) appendLine('ch', `  sha256 ${res.sha256}`);
    return res;
  }

  async function execute() {
    setStep('running'); setLines([]); setRail({}); setResult([]); setDone(false);
    try {
      const staged: ResultRow[] = [];

      // ---- PHASE 1: upload (stage files onto the ops host) ----
      if (actions.includes('upload')) {
        setPhase(host, 'upload', 'active');
        appendLine('user', `$ stage files -> ${cat?.workingDir ?? 'stg-deployment'}`);
        if (channel === 'app') {
          for (const a of apps) {
            if (jarFile[a]) { const r = await stageOne('jar', a, jarFile[a]); staged.push({ host, app: a, before: '-', after: `jar staged (${r.storedName})`, verdict: 'changed' }); }
            if (cfgFile[a]) { const r = await stageOne('cfg', a, cfgFile[a]); staged.push({ host, app: a, before: '-', after: `config staged (${r.storedName})`, verdict: 'changed' }); }
          }
        } else {
          for (const u of uis) {
            if (tarFile[u]) { const r = await stageOne('portalui', u, tarFile[u]); staged.push({ host, app: u, before: '-', after: `tar staged (${r.storedName})`, verdict: 'changed' }); }
          }
        }
        setPhase(host, 'upload', 'done');
      }

      // ---- PHASE 2: run (stop/deploy/start, or portal-ui deploy) ----
      if (willRun) {
        const path = channel === 'app' ? '/stg/deploy' : '/stg/portal-ui';
        const body = channel === 'app' ? { group, apps, actions: runActions } : { uis, date };
        const { streamTicket } = await api.post<{ deploymentId: string; streamTicket: string }>(path, body);
        openDeployStream(streamTicket, {
          onLine: (l) => setLines((p) => [...p, l]),
          onHost: (h) => setRail((p) => ({ ...p, [h.host]: { ...(p[h.host] ?? {}), [h.action]: h.state } })),
          onComplete: (c) => { setResult(c.rows.length ? c.rows : staged); setDone(true); qc.invalidateQueries(); },
          onError: (m) => { flash(m, C.stop); setDone(true); },
        }, '/stg/stream');
      } else {
        // upload-only — nothing runs on the target
        appendLine('ok', 'Upload complete — files staged. No stop/deploy/start selected, nothing ran.');
        setResult(staged); setDone(true); qc.invalidateQueries();
      }
    } catch (e) {
      flash(e instanceof ApiError ? e.message : 'staging run failed', C.stop);
      setDone(true);
    }
  }

  const stgNav = (
    <div style={{ display: 'flex', gap: 2, border: '1px solid color-mix(in srgb, var(--color-neutral-100) 30%, transparent)', flex: 'none' }}>
      {([['deploy', 'DEPLOY'], ['properties', 'APPLICATION-PROPERTIES'], ['history', 'HISTORY']] as const).map(([v, label]) => (
        <button key={v} onClick={() => setStgView(v)} style={{ border: 0, cursor: 'pointer', padding: '6px 12px',
          fontFamily: 'var(--font-heading)', fontWeight: 700, fontSize: 9.5, letterSpacing: '.1em',
          background: stgView === v ? 'var(--color-neutral-100)' : 'transparent', color: stgView === v ? 'var(--color-text)' : 'var(--color-neutral-500)' }}>{label}</button>
      ))}
    </div>
  );

  // Application-properties / History views live under the same STG DEPLOYMENT tab.
  if (stgView === 'properties' || stgView === 'history') return (
    <main style={{ padding: '0 24px 48px', maxWidth: 1500 }}>
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 14, padding: '20px 0 14px', flexWrap: 'wrap' }}>
        <h3 style={{ margin: 0, color: 'var(--color-neutral-100)' }}>Staging — {stgView === 'properties' ? 'application properties' : 'history'}</h3>
        <div style={{ fontFamily: 'var(--font-heading)', fontWeight: 600, fontSize: 10, letterSpacing: '.12em', color: 'var(--color-neutral-500)' }}>
          {stgView === 'properties' ? 'SURGICAL IN-PLACE EDIT · MAPS TO ./properties.sh' : 'WHAT WAS DONE IN STAGING'}
        </div>
        <div style={{ flex: 1 }} />
        {stgNav}
      </div>
      {stgView === 'properties' ? <StgProperties /> : <StgHistory />}
    </main>
  );

  if (!cat) return <main style={{ padding: 24, color: 'var(--color-neutral-500)' }}>loading…</main>;

  const allAppsOn = grp && apps.length === grp.apps.length && grp.apps.length > 0;
  const allUisOn = uis.length === cat.uis.length && cat.uis.length > 0;

  const channelSwitch = (
    <div style={{ display: 'flex', border: '1px solid color-mix(in srgb, var(--color-neutral-100) 30%, transparent)', flex: 'none' }}>
      {([['app', 'APP · JAR / CONFIG'], ['portal-ui', 'PORTAL-UI']] as const).map(([m, label]) => (
        <button key={m} onClick={() => switchChannel(m)} style={{ border: 0, cursor: 'pointer', padding: '6px 12px',
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
          STEP 1 OF 3 — PHASES: {phaseOrder.join(' → ').toUpperCase()}
        </div>
        <div style={{ flex: 1 }} />
        {stgNav}
        {channelSwitch}
      </div>

      <div style={{ display: 'flex', gap: 10, alignItems: 'center', padding: '10px 12px', marginBottom: 4, background: 'var(--color-neutral-900)', fontSize: 12, color: 'var(--color-neutral-300)' }}>
        <span style={{ width: 9, height: 9, background: C.run, flex: 'none' }} />
        <span><strong style={{ color: 'var(--color-neutral-100)' }}>upload</strong> stages the file onto the ops host; <strong style={{ color: 'var(--color-neutral-100)' }}>deploy</strong> copies it to <span style={{ fontFamily: mono, color: 'var(--color-neutral-100)' }}>{host}</span> (backing up the live jar first). Pick only <span style={{ fontFamily: mono }}>upload</span> to just stage a build.</span>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 420px', gap: 36, borderTop: rule2, paddingTop: 22, alignItems: 'start' }}>
        <div style={{ display: 'grid', gap: 24 }}>
          {channel === 'app' ? (
            <>
              <div>
                <h6 style={{ color: 'var(--color-neutral-400)', margin: '0 0 8px' }}>1 — GROUP</h6>
                <div style={{ display: 'flex', gap: 2 }}>
                  {cat.groups.map((g) => { const on = g.key === group; return (
                    <button key={g.key} onClick={() => { setGroup(g.key); setApps([]); setJarFile({}); setCfgFile({}); }} title={`${g.host} · ${g.ip}`}
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

              <PhasePicker phases={APP_PHASES} actions={actions} setActions={setActions} toggle={toggle} />

              {apps.length > 0 && actions.includes('upload') && (
                <div>
                  <h6 style={{ color: 'var(--color-neutral-400)', margin: '0 0 8px' }}>FILES TO UPLOAD <span style={{ fontWeight: 400, letterSpacing: 0, textTransform: 'none', color: 'var(--color-neutral-500)' }}>— staged on EXECUTE · jar per app · config optional</span></h6>
                  <div style={{ borderTop: rule1 }}>
                    {apps.map((a) => {
                      const jar = grp?.apps.find((x) => x.key === a)?.jar ?? '';
                      return (
                        <div key={a} style={{ display: 'grid', gridTemplateColumns: '140px 1fr 1fr', gap: 12, padding: '10px 2px', borderBottom: rule1, alignItems: 'center' }}>
                          <div style={{ fontFamily: mono, fontSize: 12.5, color: 'var(--color-neutral-100)' }}>{a}<div style={{ fontSize: 9.5, color: 'var(--color-neutral-500)' }}>{jar}</div></div>
                          <FileCell label="jar" required file={jarFile[a]} hash={fileHash[`jar:${a}`]} onPick={(f) => pick('jar', a, setJarFile, f)} disabled={!me?.w} />
                          <FileCell label="application.properties" required={false} file={cfgFile[a]} hash={fileHash[`cfg:${a}`]} onPick={(f) => pick('cfg', a, setCfgFile, f)} disabled={!me?.w} />
                        </div>
                      );
                    })}
                  </div>
                  {missingUploads.length > 0 && (
                    <div style={{ fontSize: 11.5, color: C.warn, marginTop: 8 }}>Choose a jar for: {missingUploads.join(', ')} (or turn off the upload phase).</div>
                  )}
                </div>
              )}
              {deployNoJar && (
                <div style={{ fontSize: 11.5, color: C.warn }}>Deploy is selected without upload — it will use the jar already staged in <span style={{ fontFamily: mono }}>files/jars/</span> on the ops host.</div>
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

              <PhasePicker phases={UI_PHASES} actions={actions} setActions={setActions} toggle={toggle} />

              <div>
                <h6 style={{ color: 'var(--color-neutral-400)', margin: '0 0 8px' }}>BACKUP DATE <span style={{ fontWeight: 400, letterSpacing: 0, textTransform: 'none', color: 'var(--color-neutral-500)' }}>— DDMMYYYY, optional (today if blank)</span></h6>
                <input value={date} onChange={(e) => setDate(e.target.value.replace(/[^0-9]/g, '').slice(0, 8))} placeholder="today" style={inputStyle} />
              </div>

              {uis.length > 0 && actions.includes('upload') && (
                <div>
                  <h6 style={{ color: 'var(--color-neutral-400)', margin: '0 0 8px' }}>FILES TO UPLOAD <span style={{ fontWeight: 400, letterSpacing: 0, textTransform: 'none', color: 'var(--color-neutral-500)' }}>— one .tar / .tar.gz per UI (stored as &lt;ui&gt;.tar)</span></h6>
                  <div style={{ borderTop: rule1 }}>
                    {uis.map((u) => (
                      <div key={u} style={{ display: 'grid', gridTemplateColumns: '160px 1fr', gap: 12, padding: '10px 2px', borderBottom: rule1, alignItems: 'center' }}>
                        <div style={{ fontFamily: mono, fontSize: 12.5, color: 'var(--color-neutral-100)' }}>{u}<div style={{ fontSize: 9.5, color: 'var(--color-neutral-500)' }}>{u}.tar</div></div>
                        <FileCell label="tarball" required file={tarFile[u]} hash={fileHash[`tar:${u}`]} onPick={(f) => pick('tar', u, setTarFile, f)} disabled={!me?.w} accept=".tar,.gz,.tgz" />
                      </div>
                    ))}
                  </div>
                  {missingUploads.length > 0 && (
                    <div style={{ fontSize: 11.5, color: C.warn, marginTop: 8 }}>Choose a tarball for: {missingUploads.join(', ')}.</div>
                  )}
                </div>
              )}
            </>
          )}
        </div>

        <div style={{ display: 'grid', gap: 18, position: 'sticky', top: 20 }}>
          <div>
            <h6 style={{ color: 'var(--color-neutral-400)', margin: '0 0 6px' }}>PLAN</h6>
            <div style={{ background: 'color-mix(in srgb, black 40%, var(--color-text))', padding: 14, fontFamily: mono, fontSize: 12.5, color: 'var(--color-neutral-100)', lineHeight: 1.6, wordBreak: 'break-all' }}>
              {actions.includes('upload') && <div style={{ color: 'var(--color-neutral-400)' }}># stage files → {host}</div>}
              <div>$ {willRun ? runCmd : <span style={{ color: 'var(--color-neutral-500)' }}>(upload only — nothing runs)</span>}</div>
            </div>
          </div>
          <div>
            <h6 style={{ color: 'var(--color-neutral-400)', margin: '0 0 6px' }}>SUMMARY</h6>
            <div style={{ borderTop: rule2 }}>
              {(channel === 'app'
                ? [`env: STAGING`, `group: ${group}`, `host: ${host}`, `apps: ${apps.join(', ') || '—'}`, `phases: ${selectedPhases.join(' → ') || '—'}`]
                : [`env: STAGING`, `host: ${host}`, `UIs: ${uis.join(', ') || '—'}`, `phases: ${selectedPhases.join(' → ') || '—'}`, `backup: ${date || 'today'}`]
              ).map((b, i) => (
                <div key={i} style={{ padding: '8px 2px', borderBottom: rule1, fontSize: 12.5, color: 'var(--color-neutral-300)' }}>{b}</div>
              ))}
            </div>
          </div>
          <button onClick={() => setStep('confirm')} disabled={!canReview} style={reviewBtn(canReview)}>REVIEW →</button>
          <div style={{ fontSize: 11, color: 'var(--color-neutral-500)' }}>
            {actions.includes('upload') && !me?.w ? 'Uploading needs the write (w) permission.'
              : !me?.x && willRun ? 'Running needs the execute (x) permission.'
              : 'Nothing is uploaded or run until you confirm on the next step.'}
          </div>
        </div>
      </div>
    </main>
  );

  // ---------- CONFIRM ----------
  if (step === 'confirm') {
    type FileRow = { label: string; name: string; hash?: string };
    const fileRows: FileRow[] = channel === 'app'
      ? apps.flatMap((a) => {
          const r: FileRow[] = [];
          if (jarFile[a]) r.push({ label: `${a} · jar`, name: jarFile[a].name, hash: fileHash[`jar:${a}`] });
          if (cfgFile[a]) r.push({ label: `${a} · config`, name: cfgFile[a].name, hash: fileHash[`cfg:${a}`] });
          return r;
        })
      : uis.filter((u) => tarFile[u]).map((u) => ({ label: `${u} · tar`, name: tarFile[u].name, hash: fileHash[`tar:${u}`] }));
    return (
      <main style={{ padding: '0 24px 48px', maxWidth: 940, margin: '0 auto' }}>
        <div style={{ display: 'flex', alignItems: 'baseline', gap: 14, padding: '20px 0 14px' }}>
          <h3 style={{ margin: 0, color: 'var(--color-neutral-100)' }}>Review — staging</h3>
          <div style={{ fontFamily: 'var(--font-heading)', fontWeight: 600, fontSize: 10, letterSpacing: '.12em', color: 'var(--color-neutral-500)' }}>STEP 2 OF 3 — NOTHING HAS RUN YET</div>
        </div>
        <div style={{ borderTop: rule2, paddingTop: 20, display: 'grid', gap: 20 }}>
          <div>
            <h6 style={{ color: 'var(--color-neutral-400)', margin: '0 0 8px' }}>PHASES</h6>
            <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
              {selectedPhases.map((p, i) => (
                <span key={p} style={{ display: 'inline-flex', alignItems: 'center', gap: 8, fontFamily: mono, fontSize: 12.5, color: 'var(--color-neutral-100)' }}>
                  <span style={{ padding: '4px 10px', border: '1px solid color-mix(in srgb, var(--color-neutral-100) 25%, transparent)' }}>{p}</span>
                  {i < selectedPhases.length - 1 && <span style={{ color: 'var(--color-neutral-600)' }}>→</span>}
                </span>
              ))}
            </div>
          </div>
          <div style={{ background: 'color-mix(in srgb, black 40%, var(--color-text))', padding: 14, fontFamily: mono, fontSize: 12.5, color: 'var(--color-neutral-100)', wordBreak: 'break-all', lineHeight: 1.6 }}>
            {actions.includes('upload') && <div style={{ color: 'var(--color-neutral-400)' }}># stage files → {host}</div>}
            <div>$ {willRun ? runCmd : <span style={{ color: 'var(--color-neutral-500)' }}>(upload only — nothing runs)</span>}</div>
          </div>
          {actions.includes('upload') && (
            <div>
              <h6 style={{ color: 'var(--color-neutral-400)', margin: '0 0 8px' }}>FILES TO STAGE → {host} <span style={{ fontWeight: 400, letterSpacing: 0, textTransform: 'none', color: 'var(--color-neutral-500)' }}>— sha256 computed locally, before upload</span></h6>
              <div style={{ borderTop: rule2 }}>
                <div style={{ display: 'grid', gridTemplateColumns: '200px 1fr', gap: 12, padding: '6px 2px', borderBottom: rule1, fontFamily: 'var(--font-heading)', fontWeight: 600, fontSize: 9, letterSpacing: '.1em', color: 'var(--color-neutral-500)' }}>
                  <div>FILE</div><div>SHA-256</div>
                </div>
                {fileRows.length === 0 && <div style={{ padding: '8px 2px', fontSize: 12, color: C.warn }}>No files chosen.</div>}
                {fileRows.map((fr) => (
                  <div key={fr.label} style={{ display: 'grid', gridTemplateColumns: '200px 1fr', gap: 12, padding: '9px 2px', borderBottom: rule1, fontFamily: mono, fontSize: 12 }}>
                    <div style={{ color: 'var(--color-neutral-100)' }}>{fr.label}<div style={{ fontSize: 10, color: 'var(--color-neutral-500)' }}>{fr.name}</div></div>
                    <div style={{ color: 'var(--color-neutral-300)', wordBreak: 'break-all', alignSelf: 'center' }} title={fr.hash && fr.hash !== 'unavailable' ? fr.hash : undefined}>
                      {fr.hash === undefined || fr.hash === '' ? <span style={{ color: 'var(--color-neutral-500)' }}>computing…</span> : fr.hash === 'unavailable' ? <span style={{ color: C.warn }}>unavailable</span> : fr.hash}
                    </div>
                  </div>
                ))}
              </div>
            </div>
          )}
          <div>
            {[`Target staging host: ${host} (env STAGING).`,
              actions.includes('upload') ? 'Files are staged into the stg-deployment bundle on the ops host.' : 'No upload phase — a jar already staged on the ops host is used.',
              actions.includes('deploy') ? 'Deploy backs up the live jar as <jar>.bkp.<datetime> before copying the new one.' : '',
              willRun ? (runActions.includes('stop') || channel === 'portal-ui' ? 'The service/UI is affected during the run.' : 'No stop phase — no downtime.') : 'Upload only — the running service is not touched.',
              'Report emailed to devops-team@nagad.com.bd and written to the audit log.'].filter(Boolean).map((r, i) => (
              <div key={i} style={{ display: 'flex', gap: 12, padding: '8px 2px', borderBottom: rule1, fontSize: 13, color: 'var(--color-neutral-300)' }}>
                <div style={{ width: 12, height: 12, background: 'var(--color-neutral-700)', marginTop: 3, flex: 'none' }} />{r}</div>
            ))}
          </div>
          <div style={{ display: 'flex', gap: 12, alignItems: 'center' }}>
            <button onClick={() => setStep('build')} style={editBtn}>← EDIT</button>
            <button onClick={execute} disabled={!canExec} style={{ border: 0, background: 'var(--color-accent)', color: 'var(--color-neutral-100)', cursor: 'pointer', padding: '12px 22px', fontFamily: 'var(--font-heading)', fontWeight: 800, fontSize: 12, letterSpacing: '.1em', opacity: canExec ? 1 : 0.5 }}>{willRun ? 'EXECUTE →' : 'UPLOAD →'}</button>
            <div style={{ fontSize: 11, color: 'var(--color-neutral-500)' }}>{!canExec ? 'Missing the required permission (w to upload, x to run).' : willRun ? 'Stages files, then streams the run.' : 'Stages the selected files. Nothing runs.'}</div>
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
                  {selectedPhases.map((a) => { const st = rail[h]?.[a] ?? 'pending';
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
            <div style={{ fontFamily: mono, fontSize: 12, color: 'var(--color-neutral-400)' }}>{selectedPhases.join(' → ')}</div>
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

/** A file slot: pick a file (held in the browser, uploaded on EXECUTE). Once a file is chosen
 *  its SHA-256 is shown so the hash is visible before anything is uploaded. */
function FileCell({ label, required, file, hash, onPick, disabled, accept }: {
  label: string; required: boolean; file?: File; hash?: string;
  onPick: (f: File) => void; disabled: boolean; accept?: string;
}) {
  const ref = useRef<HTMLInputElement>(null);
  const dot = file ? C.run : 'var(--color-neutral-600)';
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
        <input ref={ref} type="file" accept={accept} style={{ display: 'none' }}
          onChange={(e) => { const f = e.target.files?.[0]; if (f) onPick(f); e.target.value = ''; }} />
        <button onClick={() => ref.current?.click()} disabled={disabled} style={{
          border: '1px solid color-mix(in srgb, var(--color-neutral-100) 25%, transparent)', background: 'transparent',
          color: disabled ? 'var(--color-neutral-600)' : 'var(--color-neutral-200)', cursor: disabled ? 'not-allowed' : 'pointer',
          padding: '7px 12px', fontFamily: 'var(--font-heading)', fontWeight: 600, fontSize: 10, letterSpacing: '.08em', whiteSpace: 'nowrap' }}>
          {file ? 'CHANGE' : `CHOOSE ${label.toUpperCase()}`}{required && !file ? ' *' : ''}
        </button>
        <div style={{ width: 8, height: 8, background: dot, flex: 'none' }} />
        <div style={{ fontFamily: mono, fontSize: 11, color: 'var(--color-neutral-400)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
          {file ? file.name : required ? 'required' : 'optional'}
        </div>
      </div>
      {file && (
        <div style={{ fontFamily: mono, fontSize: 10, color: 'var(--color-neutral-500)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
          {hash === undefined || hash === '' ? 'sha256 …' : hash === 'unavailable' ? 'sha256 unavailable' : <>sha256 <span style={{ color: 'var(--color-neutral-300)' }}>{shortHash(hash)}</span></>}
        </div>
      )}
    </div>
  );
}

/** Ordered, multi-select phase picker (upload → stop → deploy → start). */
function PhasePicker({ phases, actions, setActions, toggle }: {
  phases: string[]; actions: string[]; setActions: React.Dispatch<React.SetStateAction<string[]>>;
  toggle: <T,>(arr: T[], v: T) => T[];
}) {
  return (
    <div>
      <h6 style={{ color: 'var(--color-neutral-400)', margin: '0 0 8px' }}>PHASES <span style={{ fontWeight: 400, letterSpacing: 0, textTransform: 'none', color: 'var(--color-neutral-500)' }}>— multi-select · order fixed</span></h6>
      <div style={{ display: 'flex', gap: 2, flexWrap: 'wrap' }}>
        {phases.map((ac) => { const on = actions.includes(ac); return (
          <button key={ac} onClick={() => setActions((p) => toggle(p, ac))} style={{ display: 'flex', alignItems: 'center', gap: 10,
            border: '1px solid color-mix(in srgb, var(--color-neutral-100) 20%, transparent)', background: 'transparent', cursor: 'pointer', padding: '12px 18px', minWidth: 130, justifyContent: 'flex-start' }}>
            <div style={{ width: 14, height: 14, border: '1px solid var(--color-neutral-400)', background: on ? 'var(--color-neutral-100)' : 'transparent' }} />
            <div style={{ fontFamily: mono, fontSize: 13, color: on ? 'var(--color-neutral-100)' : 'var(--color-neutral-500)' }}>{ac}</div></button>
        ); })}
      </div>
      <div style={{ fontSize: 11, color: 'var(--color-neutral-500)', marginTop: 6 }}>Order is fixed: {phases.join(' → ')}. Pick only <span style={{ fontFamily: mono }}>upload</span> to just stage the build.</div>
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
