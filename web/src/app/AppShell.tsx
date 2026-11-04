import { NavLink, Navigate, Outlet, useLocation } from 'react-router';

import { isTrader, useLogout, useSession } from '../auth/session';

/** The frame around every signed-in page: the simulation context stays visible wherever the user goes. */
export function AppShell() {
  const session = useSession();
  const logout = useLogout();
  const location = useLocation();

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
          <NavLink to="/terminal">Terminal</NavLink>
          <NavLink to="/portfolio">Portfolio</NavLink>
        </nav>
        <span className="user">{session.data.username}</span>
        <button type="button" className="quiet" onClick={() => logout.mutate()} disabled={logout.isPending}>
          Log out
        </button>
      </header>
      <main className="content">
        {isTrader(session.data) ? (
          <Outlet />
        ) : (
          <p role="alert" className="page-status">
            This account has no trading access.
          </p>
        )}
      </main>
    </div>
  );
}
