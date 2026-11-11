import { NavLink, Navigate, Outlet, useLocation } from 'react-router';

import { isOperator, isTrader, useLogout, useSession } from '../auth/session';
import { ThemeSwitch } from './ThemeSwitch';
import { useAccountEvents } from './useAccountEvents';

/** The frame around every signed-in page: the simulation context stays visible wherever the user goes. */
export function AppShell() {
  const session = useSession();
  const logout = useLogout();
  const location = useLocation();
  // A trader's own change notifications, for as long as they are signed in.
  useAccountEvents(isTrader(session.data));

  if (session.isPending) {
    return <p className="page-status">Loading…</p>;
  }
  if (session.isError) {
    return (
      <p role="alert" className="page-status">
        The exchange is not reachable. Try again in a moment.
      </p>
    );
  }
  if (session.data === null) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  }

  return (
    <div className="shell">
      <header className="topbar">
        <span className="brand">Mini Stock Exchange</span>
        <span className="context-badge">Simulation · Virtual funds</span>
        <nav aria-label="Main">
          {isTrader(session.data) && (
            <>
              <NavLink to="/terminal">Terminal</NavLink>
              <NavLink to="/portfolio">Portfolio</NavLink>
            </>
          )}
          {isOperator(session.data) && <NavLink to="/operator">Operator</NavLink>}
        </nav>
        <ThemeSwitch />
        <span className="user">{session.data.username}</span>
        <button type="button" className="quiet" onClick={() => logout.mutate()} disabled={logout.isPending}>
          Log out
        </button>
      </header>
      <main className="content">
        <Outlet />
      </main>
    </div>
  );
}

/**
 * Pages for one role. Anyone else is sent to their own start page, so an operator who follows a terminal link lands
 * on the operator screen; a user with neither role is told so. The server enforces the same split on every request.
 */
export function RequireRole({ role }: { role: 'TRADER' | 'OPERATOR' }) {
  const session = useSession();
  const roles = session.data?.roles ?? [];
  if (roles.includes(role)) {
    return <Outlet />;
  }
  if (roles.includes('OPERATOR')) {
    return <Navigate to="/operator" replace />;
  }
  if (roles.includes('TRADER')) {
    return <Navigate to="/terminal" replace />;
  }
  return (
    <p role="alert" className="page-status">
      This account has no access to the exchange.
    </p>
  );
}
