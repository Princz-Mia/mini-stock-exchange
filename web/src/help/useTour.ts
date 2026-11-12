import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useLocation, useNavigate } from 'react-router';

import { api } from '../api/client';
import type { Identity } from '../api/types';
import { TOUR_VERSION, type TourRole } from './tours';

/** The tour for the page on screen: each role's tour belongs to its own start page. */
function roleOf(pathname: string): TourRole | null {
  if (pathname.startsWith('/terminal')) {
    return 'TRADER';
  }
  if (pathname.startsWith('/operator')) {
    return 'OPERATOR';
  }
  return null;
}

/**
 * When to show the tour, and how to show it again. It shows by itself once, on the user's start page, until they
 * finish or skip a tour of the current version; the server remembers that for every browser. A failed read never
 * shows it: a tour that cannot be marked seen would come back on every visit.
 *
 * A tour belongs to the page it opened on. Leaving that page (Back, say) closes it for this visit without recording
 * it, so it never reappears later unasked, nor turns into the other role's tour.
 */
export function useTour(identity: Identity) {
  const location = useLocation();
  const navigate = useNavigate();
  const client = useQueryClient();
  // Per user, so a record that lands after logout cannot answer for the next user in this tab.
  const key = ['tour', identity.username] as const;
  const seen = useQuery({ queryKey: key, queryFn: api.tourSeen, staleTime: Infinity, retry: false });
  const record = useMutation({
    mutationFn: () => api.seeTour(TOUR_VERSION),
    onSuccess: (stored) => client.setQueryData(key, stored),
  });
  const [open, setOpen] = useState<TourRole | null>(null);
  // A replay asked for from another page: it opens once that page is on screen.
  const [wanted, setWanted] = useState<TourRole | null>(null);
  // Ended in this visit, so a failed save, or leaving mid-tour, does not bring it straight back.
  const [ended, setEnded] = useState(false);

  const page = roleOf(location.pathname);
  const role = page !== null && identity.roles.includes(page) ? page : null;
  const due = seen.data !== undefined && seen.data.seenVersion < TOUR_VERSION;

  // State that follows the page, updated during render.
  if (open !== null && role !== open) {
    setOpen(null);
    setEnded(true);
  } else if (wanted !== null && role === wanted) {
    setWanted(null);
    setOpen(wanted);
  } else if (open === null && wanted === null && !ended && due && role !== null) {
    setOpen(role);
  }

  return {
    /** The tour to show now, if any. */
    showing: open !== null && open === role ? open : null,
    end() {
      setOpen(null);
      setEnded(true);
      record.mutate();
    },
    /** From Help: the tour for the user's start page, going there first if needed. */
    replay() {
      const target: TourRole = role ?? (identity.roles.includes('TRADER') ? 'TRADER' : 'OPERATOR');
      if (role === target) {
        setOpen(target);
      } else {
        setWanted(target);
        navigate(target === 'TRADER' ? '/terminal' : '/operator');
      }
    },
  };
}
