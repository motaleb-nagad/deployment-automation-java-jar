// Semantic status palette, mirroring the prototype's colour object. Colour carries
// meaning only: green running, red stopped/destructive, amber drift/warn, grey unknown.
export const C = {
  run: 'oklch(0.72 0.15 152)',
  runTint: 'color-mix(in srgb, oklch(0.72 0.15 152) 15%, transparent)',
  runFg: 'oklch(0.8 0.1 152)',
  stop: 'var(--color-accent-500)',
  warn: 'oklch(0.8 0.14 82)',
  unk: 'var(--color-neutral-600)',
  inkOnRed: '#1c0d09',
  inkOnAmber: '#241c05',
  inkOnGreen: '#0d1f14',
  approve: 'oklch(0.62 0.16 150)',
} as const;

// Terminal (ash-ground) console colours for the live deploy stream.
export const TERM: Record<string, string> = {
  user: '#12100f',
  dim: '#78716c',
  ink: '#1c1917',
  task: '#c2410c',
  ok: '#15803d',
  ch: '#a16207',
  fatal: '#c0341a',
};

export const rule2 = '2px solid color-mix(in srgb, var(--color-neutral-100) 30%, transparent)';
export const rule1 = '1px solid color-mix(in srgb, var(--color-neutral-100) 14%, transparent)';
export const border = '1px solid color-mix(in srgb, var(--color-neutral-100) 25%, transparent)';

export const roleMeta: Record<string, { tag: string; bg: string; fg: string; label: string }> = {
  superadmin: { tag: 'SA', bg: 'var(--color-neutral-100)', fg: 'var(--color-text)', label: 'super admin' },
  operator: { tag: 'OPS', bg: 'oklch(0.72 0.15 152)', fg: '#0d1f14', label: 'operator' },
  viewer: { tag: 'VIEW', bg: 'var(--color-neutral-700)', fg: 'var(--color-neutral-100)', label: 'viewer' },
};

export const statusColor = (s: string) =>
  s === 'stopped' ? C.stop : s === 'unknown' ? C.unk : s === 'warn' ? C.warn : C.run;

// ---- deploy result verdicts ----
// A run's per-service outcome. `changed`/`unchanged` come from the deploy (jar) phase; the
// live-state verdicts (running / stopped / not-running / still-running / unverified) come from
// the independent post-action process check that reads the real process table on the host.
export type VerdictKind = 'good' | 'bad' | 'warn' | 'neutral';
export function verdictMeta(v: string): { label: string; kind: VerdictKind } {
  switch (v) {
    case 'running': return { label: 'RUNNING', kind: 'good' };
    case 'stopped': return { label: 'STOPPED', kind: 'good' };
    case 'changed': return { label: 'CHANGED', kind: 'good' };
    case 'unchanged': return { label: 'UNCHANGED', kind: 'neutral' };
    case 'not-running': return { label: 'NOT RUNNING', kind: 'bad' };
    case 'still-running': return { label: 'STILL RUNNING', kind: 'bad' };
    case 'unverified': return { label: 'UNVERIFIED', kind: 'warn' };
    case 'skipped': return { label: 'SKIPPED', kind: 'warn' };
    default: return { label: (v || '').toUpperCase(), kind: 'neutral' };
  }
}
/** A hard live-state failure — the service did not reach the intended end-state. */
export const verdictBad = (v: string) => v === 'not-running' || v === 'still-running';
/** Worth offering a one-click retry: a hard failure, or a state we could not confirm. */
export const verdictNeedsRetry = (v: string) => verdictBad(v) || v === 'unverified';

/** Badge colours for a verdict kind (background / text / optional border). */
export function verdictStyle(kind: VerdictKind): { background: string; color: string; border: string } {
  switch (kind) {
    case 'good': return { background: C.run, color: C.inkOnGreen, border: 'none' };
    case 'bad': return { background: C.stop, color: 'var(--color-neutral-100)', border: 'none' };
    case 'warn': return { background: 'transparent', color: C.warn, border: `1px solid ${C.warn}` };
    default: return { background: 'transparent', color: 'var(--color-neutral-500)', border: '1px solid var(--color-neutral-700)' };
  }
}
