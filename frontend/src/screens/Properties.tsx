import { useEffect, useMemo, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api, ApiError } from '../api/client';
import { useApp } from '../store/app';
import { C, rule1, rule2, TERM } from '../theme/colors';
import type { PropertiesCatalog, PropertiesRequest, PropertiesResult } from '../api/types';

const mono = 'var(--mono)';

// Which operations take a pasted block, and which mutate the file (the rest are read-only).
const BLOCK_OPS = new Set(['append', 'insert']);
const WRITE_OPS = new Set(['update', 'add_csv', 'remove_csv', 'rename', 'append', 'insert', 'restore']);
// Test mode (--test) only makes sense for line/block edits, not for a restore.
const TESTABLE = new Set(['update', 'add_csv', 'remove_csv', 'rename', 'append', 'insert']);

const OP_HELP: Record<string, string> = {
  preview: 'Show the file (or grep one key). Read-only.',
  update: 'Replace one exact line with another. Must match exactly once.',
  add_csv: 'Append value(s) to a comma-separated key.',
  remove_csv: 'Remove value(s) from a comma-separated key.',
  rename: 'Rename a key, keeping its value.',
  append: 'Add a block of lines at the end of the file (with a dated header).',
  insert: 'Insert a block of lines right after a specific existing line.',
  diff: 'Show the difference between the live file and its last backup. Read-only.',
  restore: 'Restore the file from its .bkp.ansible backup.',
};

/**
 * The APPLICATION-PROPERTIES screen — surgical, per-host:app edits of
 * application.properties (maps to ./properties.sh). Pick a host + app (like Deploy), an
 * operation, fill the op's fields — for append/insert paste the block of lines into the box —
 * then apply. --test runs on a copy and shows the diff without touching the live file.
 */
export function Properties() {
  const { me, flash } = useApp();
  const { data: cat } = useQuery({ queryKey: ['properties', 'catalog'], queryFn: () => api.get<PropertiesCatalog>('/properties/catalog') });

  const [groupKey, setGroupKey] = useState<string>('');
  const [host, setHost] = useState<string>('');
  const [app, setApp] = useState<string>('');
  const [op, setOp] = useState<string>('preview');
  const [testMode, setTestMode] = useState<boolean>(true);

  const [oldLine, setOldLine] = useState('');
  const [newLine, setNewLine] = useState('');
  const [key, setKey] = useState('');
  const [values, setValues] = useState('');
  const [oldKey, setOldKey] = useState('');
  const [newKey, setNewKey] = useState('');
  const [afterLine, setAfterLine] = useState('');
  const [block, setBlock] = useState('');

  const [result, setResult] = useState<PropertiesResult | null>(null);
  const [busy, setBusy] = useState(false);
  const termRef = useRef<HTMLDivElement>(null);

  const group = useMemo(() => cat?.groups.find((g) => g.key === groupKey) ?? cat?.groups[0], [cat, groupKey]);

  // Default the group/host/app once the catalog arrives.
  useEffect(() => {
    if (!cat || group == null) return;
    if (!groupKey) setGroupKey(group.key);
    if (!host && group.hosts[0]) setHost(group.hosts[0]);
    if (!app && group.apps[0]) setApp(group.apps[0].key);
  }, [cat, group, groupKey, host, app]);

  useEffect(() => { if (termRef.current) termRef.current.scrollTop = termRef.current.scrollHeight; }, [result]);

  function selectGroup(k: string) {
    const g = cat?.groups.find((x) => x.key === k);
    setGroupKey(k);
    setHost(g?.hosts[0] ?? '');
    setApp(g?.apps[0]?.key ?? '');
    setResult(null);
  }

  const isWrite = WRITE_OPS.has(op);
  const isBlock = BLOCK_OPS.has(op);
  const showTest = TESTABLE.has(op);
  const effTest = showTest && testMode;

  // Client-side mirror of the wrapper command (for the plan box).
  const blockPath = `/tmp/props-block-${host || '<host>'}-${app || '<app>'}.txt`;
  const cmd = useMemo(() => {
    const dq = (s: string) => `"${s}"`;
    let c = `./properties.sh ${host || '<host>'} ${app || '<app>'} ${op}`;
    if (op === 'preview' && key.trim()) c += ` ${dq(key.trim())}`;
    else if (op === 'update') c += ` ${dq(oldLine)} ${dq(newLine)}`;
    else if (op === 'add_csv' || op === 'remove_csv') c += ` ${dq(key)} ${dq(values)}`;
    else if (op === 'rename') c += ` ${dq(oldKey)} ${dq(newKey)}`;
    else if (op === 'append') c += ` --file ${blockPath}`;
    else if (op === 'insert') c += ` ${dq(afterLine)} --file ${blockPath}`;
    if (effTest) c += ' --test';
    return c;
  }, [host, app, op, key, oldLine, newLine, values, oldKey, newKey, afterLine, effTest, blockPath]);

  // Permission the action needs: read-only ops → r; a real edit → x; a test edit → w.
  const permNeeded: 'r' | 'w' | 'x' = !isWrite ? 'r' : effTest ? 'w' : 'x';
  const hasPerm = permNeeded === 'r' ? !!me?.r : permNeeded === 'w' ? !!me?.w : !!me?.x;

  const blockLineCount = block.split(/\r?\n/).filter((l, i, a) => !(l === '' && i === a.length - 1)).length;
  const argsOk =
    op === 'update' ? oldLine.trim() !== '' && newLine.trim() !== ''
    : op === 'add_csv' || op === 'remove_csv' ? key.trim() !== '' && values.trim() !== ''
    : op === 'rename' ? oldKey.trim() !== '' && newKey.trim() !== ''
    : op === 'append' ? blockLineCount > 0
    : op === 'insert' ? afterLine.trim() !== '' && blockLineCount > 0
    : true;
  const canApply = !!host && !!app && argsOk && hasPerm && !busy;

  async function apply() {
    if (!canApply) return;
    setBusy(true);
    try {
      const body: PropertiesRequest = {
        host, app, op, testMode: effTest,
        oldLine, newLine, key, values, oldKey, newKey, afterLine, block,
      };
      const res = await api.post<PropertiesResult>('/properties/apply', body);
      setResult(res);
      flash(res.testMode ? 'Test run complete — real file untouched'
        : res.changed ? `Applied ${op} on ${host}:${app}` : `${op} complete`);
    } catch (e) {
      flash(e instanceof ApiError ? e.message : 'properties run failed', C.stop);
    } finally { setBusy(false); }
  }

  if (!cat) return <main style={{ padding: 24, color: 'var(--color-neutral-500)' }}>loading…</main>;

  const inp: React.CSSProperties = { width: '100%', background: 'var(--color-neutral-900)', border: '1px solid color-mix(in srgb, var(--color-neutral-100) 25%, transparent)', color: 'var(--color-neutral-100)', padding: '9px 10px', fontFamily: mono, fontSize: 12.5, boxSizing: 'border-box' };

  return (
    <main style={{ padding: '0 24px 56px', maxWidth: 1500 }}>
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 14, padding: '20px 0 12px', flexWrap: 'wrap' }}>
        <h3 style={{ margin: 0, color: 'var(--color-neutral-100)' }}>Application properties</h3>
        <div style={{ fontFamily: 'var(--font-heading)', fontWeight: 600, fontSize: 10, letterSpacing: '.12em', color: 'var(--color-neutral-500)' }}>SURGICAL IN-PLACE EDIT · MAPS TO ./properties.sh</div>
      </div>

      <div style={{ display: 'flex', gap: 10, alignItems: 'flex-start', padding: '11px 14px', marginBottom: 6, background: 'var(--color-neutral-900)', fontSize: 12, color: 'var(--color-neutral-300)' }}>
        <span style={{ width: 9, height: 9, background: C.warn, flex: 'none', marginTop: 3 }} />
        <span>Edits are <strong style={{ color: 'var(--color-neutral-100)' }}>surgical</strong> — one line or block, never a whole-file replace. Every write backs up to <span style={{ fontFamily: mono }}>.bkp.ansible</span> first. Always <strong style={{ color: 'var(--color-neutral-100)' }}>--test</strong> before a real apply, then verify with <span style={{ fontFamily: mono }}>diff</span>.</span>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 440px', gap: 32, borderTop: rule2, paddingTop: 20, alignItems: 'start' }}>
        {/* ------- build ------- */}
        <div style={{ display: 'grid', gap: 22 }}>
          <div>
            <h6 style={{ color: 'var(--color-neutral-400)', margin: '0 0 8px' }}>1 — GROUP</h6>
            <div style={{ display: 'flex', gap: 2, flexWrap: 'wrap' }}>
              {cat.groups.map((g) => (
                <button key={g.key} onClick={() => selectGroup(g.key)} title={`${g.tier} · ${g.zone}`} style={pill(g.key === group?.key, '8px 12px', 11.5)}>
                  {g.cmd}<span style={{ fontSize: 9, opacity: 0.6 }}> · {g.zone}</span>
                </button>
              ))}
            </div>
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 20 }}>
            <div>
              <h6 style={{ color: 'var(--color-neutral-400)', margin: '0 0 8px' }}>2 — HOST</h6>
              <div style={{ display: 'flex', gap: 2, flexWrap: 'wrap' }}>
                {group?.hosts.map((h) => (
                  <button key={h} onClick={() => { setHost(h); setResult(null); }} style={pill(h === host, '7px 11px', 11.5)}>{h}</button>
                ))}
              </div>
            </div>
            <div>
              <h6 style={{ color: 'var(--color-neutral-400)', margin: '0 0 8px' }}>3 — APP <span style={{ fontWeight: 400, letterSpacing: 0, textTransform: 'none', color: 'var(--color-neutral-500)' }}>— service user</span></h6>
              <div style={{ display: 'flex', gap: 2, flexWrap: 'wrap' }}>
                {group?.apps.map((a) => (
                  <button key={a.key} onClick={() => { setApp(a.key); setResult(null); }} title={a.cfgFile} style={pill(a.key === app, '7px 11px', 11.5)}>{a.key}</button>
                ))}
              </div>
            </div>
          </div>

          <div>
            <h6 style={{ color: 'var(--color-neutral-400)', margin: '0 0 8px' }}>4 — OPERATION</h6>
            <div style={{ display: 'flex', gap: 2, flexWrap: 'wrap' }}>
              {cat.ops.map((o) => (
                <button key={o} onClick={() => { setOp(o); setResult(null); }} style={pill(o === op, '7px 12px', 11.5)}>{o}</button>
              ))}
            </div>
            <div style={{ fontSize: 11.5, color: 'var(--color-neutral-500)', marginTop: 8 }}>{OP_HELP[op]}</div>
          </div>

          {/* op-specific fields */}
          <div style={{ display: 'grid', gap: 12 }}>
            {op === 'preview' && (
              <Field label="KEY (optional — grep, blank = whole file)"><input value={key} onChange={(e) => setKey(e.target.value)} placeholder="server.port" style={inp} /></Field>
            )}
            {op === 'update' && (<>
              <Field label="OLD LINE (must match exactly, once)"><input value={oldLine} onChange={(e) => setOldLine(e.target.value)} placeholder="server.port=10030" style={inp} /></Field>
              <Field label="NEW LINE"><input value={newLine} onChange={(e) => setNewLine(e.target.value)} placeholder="server.port=10031" style={inp} /></Field>
            </>)}
            {(op === 'add_csv' || op === 'remove_csv') && (<>
              <Field label="KEY"><input value={key} onChange={(e) => setKey(e.target.value)} placeholder="spring.redis.sentinel.nodes" style={inp} /></Field>
              <Field label={op === 'add_csv' ? 'VALUE(S) TO ADD (comma-separated)' : 'VALUE(S) TO REMOVE (comma-separated)'}><input value={values} onChange={(e) => setValues(e.target.value)} placeholder="redis4.kpp.com:26379" style={inp} /></Field>
            </>)}
            {op === 'rename' && (<>
              <Field label="OLD KEY"><input value={oldKey} onChange={(e) => setOldKey(e.target.value)} placeholder="item-error-code" style={inp} /></Field>
              <Field label="NEW KEY (value kept)"><input value={newKey} onChange={(e) => setNewKey(e.target.value)} placeholder="item-error-code-list" style={inp} /></Field>
            </>)}
            {op === 'insert' && (
              <Field label="AFTER LINE (block is inserted right after this existing line)"><input value={afterLine} onChange={(e) => setAfterLine(e.target.value)} placeholder="http.client.route.ias.max-per-route-connections=200" style={inp} /></Field>
            )}
            {isBlock && (
              <Field label={`BLOCK — paste the lines to ${op} (saved to a file on the server, previous removed)`}>
                <textarea value={block} onChange={(e) => setBlock(e.target.value)} rows={9} spellCheck={false}
                  placeholder={'##################### MPGS #############################\nbds.disbursement.retry.max-count=10\nbds.disbursement.retry.delay-in-millisec=30000'}
                  style={{ ...inp, resize: 'vertical', lineHeight: 1.5, whiteSpace: 'pre', overflowWrap: 'normal', overflowX: 'auto' }} />
                <div style={{ fontSize: 10.5, color: 'var(--color-neutral-500)', marginTop: 4, fontFamily: mono }}>{blockLineCount} line(s) → {blockPath}</div>
              </Field>
            )}
          </div>

          {showTest && (
            <label style={{ display: 'flex', alignItems: 'center', gap: 9, cursor: 'pointer', fontFamily: mono, fontSize: 12.5, color: 'var(--color-neutral-200)' }}>
              <input type="checkbox" checked={testMode} onChange={(e) => setTestMode(e.target.checked)} />
              <span>--test <span style={{ color: 'var(--color-neutral-500)' }}>— run on a copy, show the diff, don't touch the live file</span></span>
            </label>
          )}
        </div>

        {/* ------- plan / run ------- */}
        <div style={{ display: 'grid', gap: 16, position: 'sticky', top: 20 }}>
          <div>
            <h6 style={{ color: 'var(--color-neutral-400)', margin: '0 0 6px' }}>COMMAND</h6>
            <div style={{ background: 'color-mix(in srgb, black 40%, var(--color-text))', padding: 14, fontFamily: mono, fontSize: 12, color: 'var(--color-neutral-100)', lineHeight: 1.5, wordBreak: 'break-all' }}>$ {cmd}</div>
          </div>
          <div>
            <h6 style={{ color: 'var(--color-neutral-400)', margin: '0 0 6px' }}>TARGET</h6>
            <div style={{ borderTop: rule2 }}>
              {[`host: nagad-${host || '—'}`, `file: /home/${app || '<app>'}/cfg/application.properties`, `mode: ${effTest ? 'TEST (copy only)' : isWrite ? 'REAL — writes live file' : 'read-only'}`, `needs: ${permNeeded.toUpperCase()} permission`].map((b, i) => (
                <div key={i} style={{ padding: '7px 2px', borderBottom: rule1, fontSize: 12, color: 'var(--color-neutral-300)', fontFamily: mono }}>{b}</div>
              ))}
            </div>
          </div>
          <button onClick={apply} disabled={!canApply} style={{ border: 0, background: effTest || !isWrite ? 'var(--color-neutral-100)' : 'var(--color-accent)', color: effTest || !isWrite ? 'var(--color-text)' : 'var(--color-neutral-100)', cursor: canApply ? 'pointer' : 'not-allowed', padding: '13px 18px', fontFamily: 'var(--font-heading)', fontWeight: 800, fontSize: 12, letterSpacing: '.1em', opacity: canApply ? 1 : 0.5 }}>
            {busy ? 'RUNNING…' : effTest ? 'RUN TEST →' : isWrite ? 'APPLY →' : 'RUN →'}
          </button>
          <div style={{ fontSize: 11, color: 'var(--color-neutral-500)' }}>
            {!hasPerm ? `This action needs the ${permNeeded.toUpperCase()} permission — ask a super admin.`
              : !argsOk ? 'Fill in the operation fields above.'
              : effTest ? 'Runs on a copy — the live file is not touched.'
              : isWrite ? 'Backs up to .bkp.ansible, then writes the live file.'
              : 'Read-only — nothing is modified.'}
          </div>
        </div>
      </div>

      {/* ------- result ------- */}
      {result && (
        <div style={{ marginTop: 26 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 10, flexWrap: 'wrap' }}>
            <span style={{ width: 13, height: 13, background: result.changed ? C.run : result.testMode ? C.warn : 'var(--color-neutral-600)' }} />
            <h4 style={{ margin: 0, color: 'var(--color-neutral-100)' }}>{result.testMode ? 'Test run — live file untouched' : result.changed ? 'Applied' : 'Done'}</h4>
            <span style={{ fontFamily: mono, fontSize: 12, color: 'var(--color-neutral-500)' }}>{result.targetFile}</span>
            {result.savedBlockPath && <span style={{ fontFamily: mono, fontSize: 11, color: 'var(--color-neutral-500)' }}>· block → {result.savedBlockPath}</span>}
          </div>
          <div ref={termRef} style={{ background: '#dcdad5', padding: 14, maxHeight: 460, overflow: 'auto' }}>
            {result.lines.map((ln, i) => (
              <div key={i} style={{ color: termColor(ln.level), fontFamily: mono, fontSize: 11.5, lineHeight: 1.55, whiteSpace: 'pre-wrap', fontWeight: ln.level === 'add' || ln.level === 'del' ? 700 : 400 }}>{ln.text}</div>
            ))}
          </div>
        </div>
      )}
    </main>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column' }}>
      <label style={{ fontFamily: 'var(--font-heading)', fontWeight: 600, fontSize: 9, letterSpacing: '.1em', color: 'var(--color-neutral-500)', marginBottom: 5 }}>{label}</label>
      {children}
    </div>
  );
}

function termColor(level: string): string {
  if (level === 'add') return '#15803d';
  if (level === 'del') return '#c0341a';
  return TERM[level] ?? '#1c1917';
}

const pill = (on: boolean, padding: string, fontSize: number): React.CSSProperties => ({
  border: '1px solid color-mix(in srgb, var(--color-neutral-100) 20%, transparent)',
  background: on ? 'var(--color-neutral-100)' : 'transparent', color: on ? 'var(--color-text)' : 'var(--color-neutral-300)',
  cursor: 'pointer', padding, fontFamily: mono, fontSize, textAlign: 'left',
});
