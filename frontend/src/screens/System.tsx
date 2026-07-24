import { C, rule1, rule2 } from '../theme/colors';

const mono = 'var(--mono)';

export function System() {
  return (
    <main style={{ padding: '0 24px 64px', maxWidth: 1200 }}>
      <div style={{ padding: '20px 0 12px' }}>
        <h3 style={{ margin: 0, color: 'var(--color-neutral-100)' }}>System</h3>
        <div style={{ fontSize: 12.5, color: 'var(--color-neutral-500)', marginTop: 4 }}>Dark interpretation of Modernist — Archivo, 2px rules, zero radius, flush-left. Colour carries meaning, never decoration.</div>
      </div>

      <section style={{ borderTop: rule2, padding: '18px 0' }}>
        <h6 style={{ color: 'var(--color-neutral-400)', margin: '0 0 12px' }}>STATUS COLOUR — THE ONLY SEMANTIC COLOURS</h6>
        <div style={{ display: 'grid', gap: 2 }}>
          {[
            [C.run, 'RUNNING', 'Process up. Rendered at ~15% tint in matrix cells so a healthy fleet reads calm.'],
            [C.stop, 'STOPPED', 'Down when it should be up — the loudest thing on any screen. Doubles as the destructive primary.'],
            [C.warn, 'DRIFT / WARN', 'Hash drift, unexpected restarts, shared-jar warnings. Always paired with the hash or uptime that explains it.'],
            ['transparent', 'UNKNOWN', 'Collector could not read status. Outlined, never filled — absence of data, not a state.'],
          ].map(([col, name, desc]) => (
            <div key={name} style={{ display: 'grid', gridTemplateColumns: '36px 130px 1fr', gap: 16, alignItems: 'center', padding: '8px 0', borderBottom: rule1 }}>
              <div style={{ width: 28, height: 28, background: col, border: col === 'transparent' ? '1px solid var(--color-neutral-600)' : 'none' }} />
              <div style={{ fontFamily: 'var(--font-heading)', fontWeight: 800, fontSize: 11, letterSpacing: '.1em', color: col === 'transparent' ? 'var(--color-neutral-500)' : col }}>{name}</div>
              <div style={{ fontSize: 12, color: 'var(--color-neutral-400)' }}>{desc}</div>
            </div>
          ))}
        </div>
      </section>

      <section style={{ borderTop: rule2, padding: '18px 0' }}>
        <h6 style={{ color: 'var(--color-neutral-400)', margin: '0 0 12px' }}>CONSOLE PALETTE — LIVE OUTPUT ON ASH</h6>
        <div style={{ background: '#dcdad5', padding: 14, maxWidth: 760, fontFamily: mono, fontSize: 11.5, lineHeight: 1.6 }}>
          <div style={{ color: '#12100f' }}>$ ./run.sh core app1..app6 apigw stop,deploy,start -K</div>
          <div style={{ color: '#78716c' }}>BECOME password: ********</div>
          <div style={{ color: '#1c1917' }}>PLAY [nagad-app] ****************************************</div>
          <div style={{ color: '#c2410c' }}>TASK [deployment : apigw] *******************************</div>
          <div style={{ color: '#15803d' }}>ok: [nagad-app1] =&gt; verify: running (7e51e9f)</div>
          <div style={{ color: '#a16207' }}>changed: [nagad-app1] =&gt; api-gateway-1.0.jar -&gt; /home/apigw/was/</div>
          <div style={{ color: '#c0341a' }}>fatal: [nagad-app6]: UNREACHABLE! =&gt; ssh timeout</div>
          <div style={{ color: '#1c1917' }}>PLAY RECAP **********************************************</div>
        </div>
        <div style={{ fontSize: 11.5, color: 'var(--color-neutral-500)', marginTop: 10 }}>The dark chrome frames the work; the live console inverts to an ash ground (#dcdad5) so Ansible output reads like a real terminal — prompt in ink, then green / yellow / orange / red carrying ok, changed, task and error.</div>
      </section>

      <section style={{ borderTop: rule2, padding: '18px 0' }}>
        <h6 style={{ color: 'var(--color-neutral-400)', margin: '0 0 12px' }}>GOVERNANCE — HOW A BUILD REACHES PRODUCTION</h6>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(5,1fr)', gap: 2, maxWidth: 960 }}>
          {[
            ['1', 'PROMOTE', 'var(--color-neutral-100)', 'Operator (w) fetches a jar from staging. Hash read, recorded to registry-db.'],
            ['2', 'EMAIL', C.warn, 'Details mailed to the super admin via the ops relay. Request enters PENDING.'],
            ['3', 'APPROVE', C.run, 'Super admin approves or denies in the Approvals queue. Decision logged.'],
            ['4', 'DEPLOY', 'var(--color-neutral-100)', 'Operator (x) deploys the approved jar — the deploy phase is locked until then.'],
            ['5', 'RECORD', C.run, 'Registry marks it deployed; before/after hashes and the run land in the audit log.'],
          ].map(([n, t, col, d]) => (
            <div key={n} style={{ background: 'var(--color-neutral-900)', padding: 14 }}>
              <div style={{ fontFamily: 'var(--font-heading)', fontWeight: 800, fontSize: 20, color: col }}>{n}</div>
              <div style={{ fontFamily: 'var(--font-heading)', fontWeight: 600, fontSize: 9.5, letterSpacing: '.1em', color: 'var(--color-neutral-400)', marginTop: 4 }}>{t}</div>
              <div style={{ fontSize: 11, color: 'var(--color-neutral-500)', marginTop: 6 }}>{d}</div>
            </div>
          ))}
        </div>
        <div style={{ fontSize: 11.5, color: 'var(--color-neutral-500)', marginTop: 10 }}>Every state and hash is persisted in registry-db (Postgres) — the console is a stateless view over it. The Spring Boot API enforces the gate server-side; the UI only reflects it.</div>
      </section>
    </main>
  );
}
