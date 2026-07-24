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
