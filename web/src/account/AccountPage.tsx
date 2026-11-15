import { useMutation } from '@tanstack/react-query';
import { type FormEvent, useEffect, useId, useRef, useState } from 'react';

import { ApiError, api } from '../api/client';
import type { Identity } from '../api/types';
import { useSession } from '../auth/session';

/**
 * What a failed change says, and the fields it names. With no answer, or a server error, the change may have been saved
 * before the answer was lost, so it is not called refused.
 */
function refusal(error: unknown): { message: string; fields: ReadonlySet<string> } {
  if (error instanceof ApiError && error.status < 500) {
    const errors = error.problem?.errors ?? [];
    return {
      message: errors.length > 0 ? errors.map((each) => each.message).join('; ') : error.message,
      fields: new Set(errors.map((each) => each.field)),
    };
  }
  return {
    message:
      'Not confirmed: the exchange did not answer, so the new password may or may not be saved. If you are signed ' +
      'out, sign in with the new password, and if that fails, with the current one.',
    fields: new Set(),
  };
}

/**
 * The signed-in trader's account: for now, changing their password. The current one is asked for first; once the new
 * one is saved, every other place the trader was signed in is signed out. Neither password is kept anywhere in the
 * browser, and both fields are cleared once the change is made.
 */
export function AccountPage() {
  const session = useSession();
  const identity: Identity | null | undefined = session.data;
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [shown, setShown] = useState(false);
  const form = useRef<HTMLFormElement>(null);
  const outcomeId = useId();
  const hintId = useId();
  const change = useMutation({
    mutationFn: () => api.changePassword({ currentPassword: current, newPassword: next }),
    onSuccess: () => {
      setCurrent('');
      setNext('');
      setShown(false);
    },
  });
  const refused = change.isError ? refusal(change.error) : null;
  const invalid = refused?.fields ?? new Set<string>();
  const marked = (field: string, hint?: string) => ({
    'aria-invalid': invalid.has(field) || undefined,
    'aria-describedby': [hint, invalid.has(field) ? outcomeId : undefined].filter(Boolean).join(' ') || undefined,
  });

  // A refusal puts focus on the first field it names, unless the user has moved focus meanwhile.
  useEffect(() => {
    const lost = document.activeElement === null || document.activeElement === document.body;
    if (change.isError && lost) {
      form.current?.querySelector<HTMLElement>('[aria-invalid="true"]')?.focus();
    }
  }, [change.isError, change.error]);

  const submit = (event: FormEvent) => {
    event.preventDefault();
    change.mutate();
  };

  return (
    <section className="panel account-page" aria-labelledby="password-title">
      <h2 id="password-title">Change password</h2>
      {identity && <p className="muted">Signed in as {identity.username}.</p>}
      <form ref={form} onSubmit={submit} className="setup-form" aria-label="Change password">
        <fieldset disabled={change.isPending}>
          <div className="field-row">
            <label>
              Current password
              <input
                type={shown ? 'text' : 'password'}
                value={current}
                onChange={(event) => setCurrent(event.target.value)}
                autoComplete="current-password"
                autoCapitalize="none"
                spellCheck={false}
                {...marked('currentPassword')}
              />
            </label>
          </div>
          <div className="field-row">
            <label>
              New password
              <input
                type={shown ? 'text' : 'password'}
                value={next}
                onChange={(event) => setNext(event.target.value)}
                autoComplete="new-password"
                autoCapitalize="none"
                spellCheck={false}
                {...marked('newPassword', hintId)}
              />
            </label>
          </div>
          <p id={hintId} className="muted hint">
            12 to 64 characters, not your username, and not the one you have now.
          </p>
          <div className="password-actions">
            <button type="button" className="quiet" aria-pressed={shown} onClick={() => setShown(!shown)}>
              Show passwords
            </button>
          </div>
          <button type="submit" className="primary">
            Change password
          </button>
        </fieldset>
      </form>
      <div id={outcomeId}>
        {change.isSuccess && (
          <p role="status" className="outcome outcome-ok">
            Your password is changed. Anywhere else you were signed in, you are signed out.
          </p>
        )}
        {refused !== null && (
          <p role="alert" className="outcome outcome-rejected">
            {refused.message}
          </p>
        )}
      </div>
      <p className="muted hint">
        A forgotten password can't be reset, so keep the new one somewhere safe.
      </p>
    </section>
  );
}
