import { useState } from 'react';
import { api, ApiError } from '../api/client';
import { useApp, type Me } from '../store/app';
import { border } from '../theme/colors';

const labelCap: React.CSSProperties = {
  display: 'block', fontFamily: 'var(--font-heading)', fontWeight: 600, fontSize: 10,
  letterSpacing: '.12em', color: 'var(--color-neutral-400)', marginBottom: 6,
};
const inputStyle: React.CSSProperties = {
  width: '100%', background: 'var(--color-text)', border, color: 'var(--color-neutral-100)',
  padding: '11px 10px', fontFamily: 'var(--mono)', fontSize: 13,
};
const primaryBtn: React.CSSProperties = {
  width: '100%', marginTop: 20, border: 0, background: 'var(--color-accent)', color: 'var(--color-neutral-100)',
  cursor: 'pointer', padding: '13px 16px', fontFamily: 'var(--font-heading)', fontWeight: 800,
  fontSize: 12, letterSpacing: '.1em', display: 'flex', justifyContent: 'flex-start',
};

/**
 * First-login password change. The account is provisioned by the super admin with a
 * temporary, shared password; the console is gated (server-side too) until the user
 * replaces it here. Once set, no one else — not even the super admin — knows it.
 */
export function ChangePassword() {
  const me = useApp((s) => s.me);
  const setMe = useApp((s) => s.setMe);
  const signOut = useApp((s) => s.signOut);
  const flash = useApp((s) => s.flash);
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [confirm, setConfirm] = useState('');
  const [err, setErr] = useState('');
  const [busy, setBusy] = useState(false);

  const tooShort = next.length > 0 && next.length < 8;
  const mismatch = confirm.length > 0 && confirm !== next;
  const canSubmit = current.length > 0 && next.length >= 8 && next === confirm;

  async function submit() {
    if (!canSubmit) return;
    setErr(''); setBusy(true);
    try {
      const updated = await api.post<Me>('/auth/change-password', { currentPassword: current, newPassword: next });
      setMe(updated);
      flash('Password updated — welcome in');
    } catch (e) {
      setErr(e instanceof ApiError ? e.message : 'could not change password');
    } finally { setBusy(false); }
  }

  return (
    <div style={{ position: 'fixed', inset: 0, background: 'var(--color-text)', display: 'flex' }}>
      <div style={{ flex: 1, borderRight: '2px solid color-mix(in srgb, var(--color-neutral-100) 20%, transparent)',
        padding: 48, display: 'flex', flexDirection: 'column', justifyContent: 'space-between', minWidth: 0 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          <div style={{ width: 18, height: 18, background: 'var(--color-accent)' }} />
          <div style={{ fontFamily: 'var(--font-heading)', fontWeight: 800, fontSize: 18, letterSpacing: '.05em', color: 'var(--color-neutral-100)' }}>
            NAGAD <span style={{ color: 'var(--color-neutral-500)', fontWeight: 600 }}>DEPLOY CONSOLE</span>
          </div>
        </div>
        <div>
          <div style={{ fontFamily: 'var(--font-heading)', fontWeight: 800, fontSize: 44, lineHeight: 1.05, color: 'var(--color-neutral-100)', maxWidth: 520 }}>
            Set a password only you know.
          </div>
          <div style={{ fontSize: 14, color: 'var(--color-neutral-400)', marginTop: 16, maxWidth: 460 }}>
            Your account was provisioned with a temporary password. Before you can use the console,
            replace it with one of your own — the super admin does not keep it, and no one else can see it.
          </div>
        </div>
        <div style={{ fontFamily: 'var(--mono)', fontSize: 11, color: 'var(--color-neutral-600)' }}>
          signed in as {me?.username} · first sign-in · password change required
        </div>
      </div>

      <div style={{ width: 460, flex: 'none', padding: 48, display: 'flex', flexDirection: 'column', justifyContent: 'center', background: 'var(--color-neutral-900)' }}>
        <div style={{ fontFamily: 'var(--font-heading)', fontWeight: 600, fontSize: 10, letterSpacing: '.14em', color: 'var(--color-neutral-500)' }}>FIRST SIGN-IN</div>
        <h2 style={{ margin: '6px 0 24px', color: 'var(--color-neutral-100)' }}>Change your password</h2>
        <label style={labelCap}>CURRENT (TEMPORARY) PASSWORD</label>
        <input type="password" value={current} onChange={(e) => setCurrent(e.target.value)}
          placeholder="the password you were given" autoComplete="current-password" style={{ ...inputStyle, marginBottom: 16 }} />
        <label style={labelCap}>NEW PASSWORD</label>
        <input type="password" value={next} onChange={(e) => setNext(e.target.value)}
          placeholder="at least 8 characters" autoComplete="new-password" style={{ ...inputStyle, marginBottom: 16 }} />
        <label style={labelCap}>CONFIRM NEW PASSWORD</label>
        <input type="password" value={confirm} onChange={(e) => setConfirm(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && canSubmit && submit()}
          placeholder="re-enter the new password" autoComplete="new-password" style={inputStyle} />
        {tooShort && <div style={{ color: 'var(--color-neutral-500)', fontSize: 12, marginTop: 10 }}>New password must be at least 8 characters.</div>}
        {mismatch && <div style={{ color: 'var(--color-accent-400)', fontSize: 12, marginTop: 10 }}>Passwords do not match.</div>}
        {err && <div style={{ color: 'var(--color-accent-400)', fontSize: 12, marginTop: 10 }}>{err}</div>}
        <button onClick={submit} disabled={busy || !canSubmit} style={{ ...primaryBtn, opacity: canSubmit ? 1 : 0.5 }}>UPDATE PASSWORD →</button>
        <button onClick={signOut} style={{ marginTop: 14, border: 0, background: 'transparent', color: 'var(--color-neutral-500)', cursor: 'pointer', fontFamily: 'var(--font-heading)', fontWeight: 600, fontSize: 11, letterSpacing: '.1em', textAlign: 'left', padding: 0 }}>
          ← SIGN OUT
        </button>
      </div>
    </div>
  );
}
