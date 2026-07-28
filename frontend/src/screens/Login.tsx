import { useState } from 'react';
import { api, ApiError } from '../api/client';
import { useApp, type Me } from '../store/app';
import { border } from '../theme/colors';

interface SessionResp { token: string; user: Me; }

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

export function Login() {
  const signIn = useApp((s) => s.signIn);
  const flash = useApp((s) => s.flash);
  const [user, setUser] = useState('');
  const [pass, setPass] = useState('');
  const [err, setErr] = useState('');
  const [busy, setBusy] = useState(false);

  const canSubmit = user.trim().length > 0 && pass.length > 0;

  async function submit() {
    if (!canSubmit) return;
    setErr(''); setBusy(true);
    try {
      const s = await api.post<SessionResp>('/auth/login', { username: user.trim(), password: pass });
      signIn(s.token, s.user);
      flash(`Signed in as ${s.user.username} — ${s.user.perms}`);
    } catch (e) {
      setErr(e instanceof ApiError ? e.message : 'sign-in failed');
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
            Controlled deployments across the Nagad estate.
          </div>
          <div style={{ fontSize: 14, color: 'var(--color-neutral-400)', marginTop: 16, maxWidth: 460 }}>
            Fetch jars from staging and run stop / deploy / start against production — every action
            recorded to the registry. Access is role-based; accounts are provisioned by the super user.
          </div>
        </div>
        <div style={{ fontFamily: 'var(--mono)', fontSize: 11, color: 'var(--color-neutral-600)' }}>
          33 hosts · managed groups core / web / ussd / web-dmz / knotifypush / apigwdoc · registry-db postgres 15
        </div>
      </div>

      <div style={{ width: 460, flex: 'none', padding: 48, display: 'flex', flexDirection: 'column', justifyContent: 'center', background: 'var(--color-neutral-900)' }}>
        <div style={{ fontFamily: 'var(--font-heading)', fontWeight: 600, fontSize: 10, letterSpacing: '.14em', color: 'var(--color-neutral-500)' }}>CREDENTIALS</div>
        <h2 style={{ margin: '6px 0 24px', color: 'var(--color-neutral-100)' }}>Sign in</h2>
        <label style={labelCap}>ACCOUNT</label>
        <input value={user} onChange={(e) => setUser(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && canSubmit && submit()}
          placeholder="your account (email)" autoComplete="username" style={{ ...inputStyle, marginBottom: 16 }} />
        <label style={labelCap}>PASSWORD</label>
        <input type="password" value={pass} onChange={(e) => setPass(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && canSubmit && submit()}
          placeholder="account password" autoComplete="current-password" style={inputStyle} />
        {err && <div style={{ color: 'var(--color-accent-400)', fontSize: 12, marginTop: 10 }}>{err}</div>}
        <button onClick={submit} disabled={busy || !canSubmit} style={{ ...primaryBtn, opacity: canSubmit ? 1 : 0.5 }}>SIGN IN →</button>
        <div style={{ fontSize: 11, color: 'var(--color-neutral-500)', marginTop: 14 }}>
          Accounts are created by the super user. Contact them if you need access.
        </div>
      </div>
    </div>
  );
}
