import { useEffect, useRef, useState } from 'react';
import { Link, NavLink, Navigate, Outlet, useLocation } from 'react-router';

import { homeOf, isOperator, isTrader, modeOf, type Mode, useLogout, useSession } from '../auth/session';
import type { Identity } from '../api/types';
import { HelpMenu } from '../help/HelpMenu';
import { Tour } from '../help/Tour';
import { TOURS } from '../help/tours';
import { useTour } from '../help/useTour';
import { FillNotice, FillWatch, useFillNotices } from './FillNotice';
import { ThemeSwitch } from './ThemeSwitch';
import { useAccountEvents } from './useAccountEvents';

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

  return <SignedIn identity={session.data} logout={() => logout.mutate()} loggingOut={logout.isPending} />;
}

/**
 * The mode on screen. The page decides it; help, which both modes share, keeps the one the user came from. A user with
 * one role is always in that role's mode.
 */
function useMode(identity: Identity): Mode {
  const location = useLocation();
  const operator = isOperator(identity);
  const trader = isTrader(identity);
  const page = modeOf(location.pathname);
  const [last, setLast] = useState<Mode>(page ?? (operator ? 'OPERATING' : 'TRADING'));
  if (page !== null && page !== last) {
    setLast(page);
  }
  if (!trader) {
    return 'OPERATING';
  }
  if (!operator) {
    return 'TRADING';
  }
  return page ?? last;
}

/** Trading or Operating, for a user holding both roles. It only navigates: the server keeps one session either way. */
function ModeSwitch({ mode }: { mode: Mode }) {
  return (
    <div className="mode-switch" role="group" aria-label="Mode">
      <Link to="/terminal" aria-current={mode === 'TRADING' ? 'true' : undefined}>
        Trading
      </Link>
      <Link to="/operator" aria-current={mode === 'OPERATING' ? 'true' : undefined}>
        Operating
      </Link>
    </div>
  );
}

/** The frame itself, for a user who is signed in, with the tour on their start page. */
function SignedIn({ identity, logout, loggingOut }: { identity: Identity; logout: () => void; loggingOut: boolean }) {
  const mode = useMode(identity);
  const tour = useTour(identity, mode === 'TRADING' ? 'TRADER' : 'OPERATOR');
  const trader = isTrader(identity);
  const operator = isOperator(identity);
  // Trading: the user's own account is on screen. An operator who also trades sees none of it while operating.
  const trading = trader && mode === 'TRADING';
  const notices = useFillNotices();
  // The trader's own change notifications while trading; each may bring a fill to tell them about.
  useAccountEvents(trading, undefined, notices.check);
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
        {trader && operator && <ModeSwitch mode={mode} />}
        <nav aria-label="Main">
          {trading ? (
            <>
              <NavLink to="/terminal">Terminal</NavLink>
              <NavLink to="/portfolio">Portfolio</NavLink>
            </>
          ) : (
            <NavLink to="/operator">Operator</NavLink>
          )}
        </nav>
        <div className="topbar-tools">
          <HelpMenu onTour={tour.replay} button={help} />
          <ThemeSwitch />
        </div>
        {trader && !operator ? (
          // A trader's own account, where they change their password; the name is the link, labelled with it. An
          // operator's password comes from configuration, so theirs is plain text.
          <NavLink to="/account" className="user" aria-label={`${identity.username}, account`}>
            {identity.username}
          </NavLink>
        ) : (
          <span className="user">{identity.username}</span>
        )}
        <button type="button" className="quiet" onClick={logout} disabled={loggingOut}>
          Log out
        </button>
      </header>
      <main className="content">
        <FillWatch.Provider value={trading ? notices.placed : null}>
          <Outlet />
        </FillWatch.Provider>
      </main>
      {trading && <FillNotice fills={notices.fills} onDismiss={notices.dismiss} held={tour.showing !== null} />}
      {tour.showing !== null && <Tour key={tour.showing} script={TOURS[tour.showing]} onEnd={tour.end} />}
    </div>
  );
}

type Role = 'TRADER' | 'OPERATOR';

/**
 * Pages for one role, and optionally not for users who also hold another. Anyone else is sent to their own start page,
 * so an operator who follows a terminal link lands on the operator screen; a user with neither role is told so. The
 * server enforces the same split on every request.
 */
export function RequireRole({ role, unless }: { role: Role; unless?: Role }) {
  const session = useSession();
  const identity = session.data;
  const roles = identity?.roles ?? [];
  if (roles.includes(role) && (unless === undefined || !roles.includes(unless))) {
    return <Outlet />;
  }
  return <Home />;
}

/** An address that is no page, or not one of this user's: their start page. */
export function Home() {
  const session = useSession();
  const identity = session.data;
  if (identity && (isOperator(identity) || isTrader(identity))) {
    return <Navigate to={homeOf(identity)} replace />;
  }
  return (
    <p role="alert" className="page-status">
      This account has no access to the exchange.
    </p>
  );
}
