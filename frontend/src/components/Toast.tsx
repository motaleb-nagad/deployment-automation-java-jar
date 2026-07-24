import { useApp } from '../store/app';

export function Toast() {
  const toast = useApp((s) => s.toast);
  if (!toast) return null;
  return (
    <div style={{ position: 'fixed', left: '50%', bottom: 24, transform: 'translateX(-50%)', zIndex: 80,
      display: 'flex', gap: 12, alignItems: 'flex-start', maxWidth: 560, background: 'var(--color-neutral-100)',
      color: 'var(--color-text)', padding: '13px 18px', boxShadow: '0 14px 44px rgba(0,0,0,0.55)' }}>
      <span style={{ width: 11, height: 11, background: toast.color, marginTop: 3, flex: 'none' }} />
      <div style={{ fontSize: 12.5, lineHeight: 1.4 }}>{toast.msg}</div>
    </div>
  );
}
