import { useEffect, useRef, useState } from 'react';

/**
 * Tells a screen reader how a long process goes at its milestones, in a live region that is on the page from the
 * first: never on every read. `said` is the milestone in words: undefined until the process has been read, null when
 * the read found none. The first read is the panel as it opened, so a process found going then is shown, not
 * announced; one started after a first read that found none is announced from its start. The caller words its
 * milestones so they change only at the moments worth saying: its start, each quarter, a change of phase, its end.
 */
export function Announcer({ said }: { said: string | null | undefined }) {
  const last = useRef<string | null | undefined>(undefined);
  const [announced, setAnnounced] = useState('');
  useEffect(() => {
    if (said === undefined) {
      return;
    }
    const before = last.current;
    last.current = said;
    if (before !== undefined && said !== null && said !== before) {
      setAnnounced(said);
    }
  }, [said]);
  return (
    <p role="status" className="visually-hidden">
      {announced}
    </p>
  );
}
