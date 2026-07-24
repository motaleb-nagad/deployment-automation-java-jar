import { useState } from 'react';
import { api, ApiError } from '../api/client';
import { useApp, type Me } from '../store/app';
import { border } from '../theme/colors';

interface LoginResp { otpRequired: boolean; maskedEmail: string; step1Token: string; demoCode: string | null; }
interface SessionResp { token: string; user: Me; }

const DEMO_USERS = [
  { v: 's.rahman', label: 's.rahman — Shafiq Rahman · super admin (rwx)' },
  { v: 't.ahmed', label: 't.ahmed — Tanvir Ahmed · operator (rwx) · core,web,apigwdoc' },
  { v: 'm.hasan', label: 'm.hasan — Mahin Hasan · operator (rw·) · ussd,web-dmz,knotifypush' },
  { v: 'r.karim', label: 'r.karim — Rumana Karim · viewer (r··)' },
];

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
  const [step, setStep] = useState<'password' | 'otp'>('password');
  const [user, setUser] = useState('s.rahman');
  const [pass, setPass] = useState('');
  const [otp, setOtp] = useState('');
  const [ctx, setCtx] = useState<LoginResp | null>(null);
  const [err, setErr] = useState('');
  const [busy, setBusy] = useState(false);

  async function submitPassword() {
    setErr(''); setBusy(true);
    try {
      const resp = await api.post<LoginResp>('/auth/login', { username: user, password: pass });
      setCtx(resp); setStep('otp'); setOtp('');
    } catch (e) {
      setErr(e instanceof ApiError ? e.message : 'sign-in failed');
    } finally { setBusy(false); }
  }

  async function verifyOtp() {
    if (!ctx) return;
    setErr(''); setBusy(true);
    try {
      const s = await api.post<SessionResp>('/auth/verify', { step1Token: ctx.step1Token, code: otp });
      signIn(s.token, s.user);
      flash(`Signed in as ${s.user.username} — ${s.user.perms}`);
    } catch (e) {
      setErr(e instanceof ApiError ? e.message : 'verification failed');
    } finally { setBusy(false); }
  }

  async function resend() {
    setErr('');
    try {
      const resp = await api.post<LoginResp>('/auth/login', { username: user, password: pass });
      setCtx(resp);
      flash('New code sent');
    } catch { /* ignore */ }
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
            Fetch from staging, gate every jar behind super-admin approval, and run stop / deploy / start against
            production — all recorded to the registry. Access is role-based and every sign-in is two-factor.
          </div>
        </div>
        <div style={{ fontFamily: 'var(--mono)', fontSize: 11, color: 'var(--color-neutral-600)' }}>
          33 hosts · managed groups core / web / ussd / web-dmz / knotifypush / apigwdoc · registry-db postgres 15
        </div>
      </div>

      <div style={{ width: 460, flex: 'none', padding: 48, display: 'flex', flexDirection: 'column', justifyContent: 'center', background: 'var(--color-neutral-900)' }}>
        {step === 'password' ? (
          <>
            <div style={{ fontFamily: 'var(--font-heading)', fontWeight: 600, fontSize: 10, letterSpacing: '.14em', color: 'var(--color-neutral-500)' }}>STEP 1 OF 2 — CREDENTIALS</div>
            <h2 style={{ margin: '6px 0 24px', color: 'var(--color-neutral-100)' }}>Sign in</h2>
            <label style={labelCap}>ACCOUNT</label>
            <select value={user} onChange={(e) => setUser(e.target.value)} style={{ ...inputStyle, marginBottom: 16 }}>
              {DEMO_USERS.map((u) => <option key={u.v} value={u.v}>{u.label}</option>)}
            </select>
            <label style={labelCap}>PASSWORD</label>
            <input type="password" value={pass} onChange={(e) => setPass(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && pass.length >= 4 && submitPassword()}
              placeholder="account password" style={inputStyle} />
            {err && <div style={{ color: 'var(--color-accent-400)', fontSize: 12, marginTop: 10 }}>{err}</div>}
            <button onClick={submitPassword} disabled={busy || pass.length < 4} style={{ ...primaryBtn, opacity: pass.length < 4 ? 0.5 : 1 }}>CONTINUE →</button>
            <div style={{ fontSize: 11, color: 'var(--color-neutral-500)', marginTop: 14 }}>
              A one-time 6-digit code will be emailed to your Nagad address. Demo: any password of 4+ characters.
            </div>
          </>
        ) : (
          <>
            <div style={{ fontFamily: 'var(--font-heading)', fontWeight: 600, fontSize: 10, letterSpacing: '.14em', color: 'var(--color-neutral-500)' }}>STEP 2 OF 2 — TWO-FACTOR</div>
            <h2 style={{ margin: '6px 0 14px', color: 'var(--color-neutral-100)' }}>Enter the code</h2>
            <div style={{ fontSize: 13, color: 'var(--color-neutral-400)', marginBottom: 20 }}>
              We emailed a 6-digit code to <span style={{ fontFamily: 'var(--mono)', color: 'var(--color-neutral-100)' }}>{ctx?.maskedEmail}</span>. It expires in 5 minutes.
            </div>
            <label style={labelCap}>6-DIGIT CODE</label>
            <input value={otp} onChange={(e) => setOtp(e.target.value.replace(/\D/g, '').slice(0, 6))} inputMode="numeric"
              onKeyDown={(e) => e.key === 'Enter' && otp.length === 6 && verifyOtp()}
              placeholder="––––––" style={{ ...inputStyle, fontSize: 24, letterSpacing: '.5em', textAlign: 'center', padding: '13px 12px' }} />
            {err && <div style={{ color: 'var(--color-accent-400)', fontSize: 12, marginTop: 10 }}>{err}</div>}
            <button onClick={verifyOtp} disabled={busy || otp.length !== 6} style={{ ...primaryBtn, opacity: otp.length !== 6 ? 0.5 : 1 }}>VERIFY & SIGN IN →</button>
            <div style={{ display: 'flex', gap: 16, marginTop: 14 }}>
              <button onClick={() => { setStep('password'); setErr(''); }} style={ghost}>← BACK</button>
              <button onClick={resend} style={{ ...ghost, color: 'var(--color-accent-400)' }}>RESEND CODE</button>
            </div>
            {ctx?.demoCode && (
              <div style={{ marginTop: 22, padding: '10px 12px', background: 'var(--color-text)',
                border: '1px dashed color-mix(in srgb, var(--color-neutral-100) 25%, transparent)',
                fontFamily: 'var(--mono)', fontSize: 11, color: 'var(--color-neutral-500)' }}>
                demo — no live mail relay · your code is <span style={{ color: 'oklch(0.72 0.15 152)', fontSize: 13, letterSpacing: '.2em' }}>{ctx.demoCode}</span>
              </div>
            )}
          </>
        )}
      </div>
    </div>
  );
}

const ghost: React.CSSProperties = {
  border: 0, background: 'transparent', color: 'var(--color-neutral-400)', cursor: 'pointer',
  fontFamily: 'var(--font-heading)', fontWeight: 600, fontSize: 10, letterSpacing: '.1em', padding: 0,
};
