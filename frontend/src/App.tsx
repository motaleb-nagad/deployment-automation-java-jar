import { useEffect, useState } from 'react';
import { useApp } from './store/app';
import { api, getToken, ApiError } from './api/client';
import type { Me } from './store/app';
import { Login } from './screens/Login';
import { ChangePassword } from './screens/ChangePassword';
import { Header } from './components/Header';
import { Toast } from './components/Toast';
import { Fleet } from './screens/Fleet';
import { ServiceDetail } from './screens/ServiceDetail';
import { Promote } from './screens/Promote';
import { Deploy } from './screens/Deploy';
import { PortalUi } from './screens/PortalUi';
import { Approvals } from './screens/Approvals';
import { Registry } from './screens/Registry';
import { History } from './screens/History';
import { Admin } from './screens/Admin';
import { System } from './screens/System';

export function App() {
  const { me, screen, setMe } = useApp();
  const [booting, setBooting] = useState(true);

  // Restore a session from a prior page load if the bearer token is still valid.
  useEffect(() => {
    const token = getToken();
    if (!token) {
      setBooting(false);
      return;
    }
    api
      .get<Me>('/auth/me')
      .then((m) => setMe(m))
      .catch((e) => {
        if (e instanceof ApiError) setMe(null);
      })
      .finally(() => setBooting(false));
  }, [setMe]);

  if (booting) {
    return (
      <div style={{ minHeight: '100vh', display: 'grid', placeItems: 'center', color: 'var(--color-neutral-500)' }}>
        <span style={{ fontFamily: 'var(--mono)', fontSize: 12 }}>loading console…</span>
      </div>
    );
  }

  if (!me) return <Login />;

  // First-login gate: until the provisioned password is replaced, the console is off-limits
  // (the backend enforces this too — every other endpoint returns 403 while it's pending).
  if (me.mustChangePassword) return <ChangePassword />;

  return (
    <div style={{ minHeight: '100vh', background: 'var(--color-text)', color: 'var(--color-neutral-200)', fontSize: 13 }}>
      <Header />
      {screen === 'fleet' && <Fleet />}
      {screen === 'detail' && <ServiceDetail />}
      {screen === 'promote' && <Promote />}
      {screen === 'deploy' && <Deploy />}
      {screen === 'rollback' && <PortalUi modes={['rollback']} heading="Roll back a portal UI" subtitle="PORTAL-UI · RESTORE A SERVER-SIDE BACKUP" />}
      {screen === 'approvals' && <Approvals />}
      {screen === 'registry' && <Registry />}
      {screen === 'history' && <History />}
      {screen === 'admin' && <Admin />}
      {screen === 'system' && <System />}
      <Toast />
    </div>
  );
}
