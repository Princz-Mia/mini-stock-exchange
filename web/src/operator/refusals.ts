import { type RefObject, useEffect } from 'react';

import type { CommandOutcome } from './useCommand';

/** The fields a refusal names, so each can be marked invalid and focused. */
export function refusedFields(outcome: CommandOutcome<unknown>): ReadonlySet<string> {
  return new Set(outcome.kind === 'refused' ? (outcome.problem?.errors ?? []).map((error) => error.field) : []);
}

/**
 * The instruments whose shares a refusal names. Its fields point into the holdings that were sent, as
 * `holdings[1].quantity`, so they are read back against that list rather than the fields on screen.
 */
export function refusedHoldings(
  fields: ReadonlySet<string>,
  sent: readonly { instrumentId: string }[],
): ReadonlySet<string> {
  const named = new Set<string>();
  for (const field of fields) {
    const match = /^holdings\[(\d+)\]/.exec(field);
    const holding = match === null ? undefined : sent[Number(match[1])];
    if (holding !== undefined) {
      named.add(holding.instrumentId);
    }
  }
  return named;
}

/**
 * Once a command is refused, focus goes to the first field the refusal marked invalid. Sending disabled the form, so
 * focus was lost to the page; focus the user has moved elsewhere in the meantime stays where it is.
 */
export function useFocusOnRefusal(kind: CommandOutcome<unknown>['kind'], form: RefObject<HTMLFormElement | null>) {
  useEffect(() => {
    const lost = document.activeElement === null || document.activeElement === document.body;
    if (kind === 'refused' && lost) {
      form.current?.querySelector<HTMLElement>('[aria-invalid="true"]')?.focus();
    }
  }, [kind, form]);
}
