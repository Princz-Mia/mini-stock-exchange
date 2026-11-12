import { useEffect, useRef } from 'react';
import { NavLink, Navigate, Outlet, useLocation } from 'react-router';

import { isOperator, isTrader, useLogout, useSession } from '../auth/session';
import type { Identity } from '../api/types';
import { HelpMenu } from '../help/HelpMenu';
import { Tour } from '../help/Tour';
import { TOURS } from '../help/tours';
import { useTour } from '../help/useTour';
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

  return <SignedIn identity={session.data} logout={() => logout.mutate()} loggingOut={logout.isPending} />;
}

/** The frame itself, for a user who is signed in, with the tour on their start page. */
function SignedIn({ identity, logout, loggingOut }: { identity: Identity; logout: () => void; loggingOut: boolean }) {
  const tour = useTour(identity);
  const help = useRef<HTMLButtonElement>(null);
  const shown = useRef(tour.showing);
  // When a tour closes, focus goes to Help, where it can be found again, rather than to the top of the page.
  useEffect(() => {
    if (shown.current !== null && tour.showing === null) {
      help.current?.focus();
    }
    shown.current = tour.showing;
  }, [tour.showing]);
  return (
    <div className="shell">
      <header className="topbar">
        <span className="brand">Mini Stock Exchange</span>
        <span className="context-badge">Simulation · Virtual funds</span>
        <nav aria-label="Main">
          {isTrader(identity) && (
            <>
              <NavLink to="/terminal">Terminal</NavLink>
              <NavLink to="/portfolio">Portfolio</NavLink>
            </>
          )}
          {isOperator(identity) && <NavLink to="/operator">Operator</NavLink>}
        </nav>
        <div className="topbar-tools">
          <HelpMenu onTour={tour.replay} button={help} />
          <ThemeSwitch />
        </div>
        <span className="user">{identity.username}</span>
        <button type="button" className="quiet" onClick={logout} disabled={loggingOut}>
          Log out
        </button>
      </header>
      <main className="content">
        <Outlet />
      </main>
      {tour.showing !== null && <Tour key={tour.showing} script={TOURS[tour.showing]} onEnd={tour.end} />}
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
