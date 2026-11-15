import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';

import { App } from '../App';
import { resetSession } from '../api/client';
import { signedIn } from '../test/fakeServer';

function openAccount() {
  window.history.pushState({}, '', '/account');
  render(<App />);
  return screen.findByRole('form', { name: 'Change password' });
}

describe('the account page', () => {
  beforeEach(() => resetSession());

  it('changes the password, clears both fields, and says other sessions are signed out', async () => {
    const server = signedIn('maria').on('POST /api/v1/me/password', { status: 204 });
    const form = await openAccount();

    const current = within(form).getByLabelText('Current password');
    const next = within(form).getByLabelText('New password');
    expect(current).toHaveAttribute('type', 'password');
    expect(current).toHaveAttribute('autocomplete', 'current-password');
    expect(next).toHaveAttribute('autocomplete', 'new-password');
    expect(next).toHaveAccessibleDescription('12 to 64 characters, not your username, and not the one you have now.');
    // Shown as text, they are still not spell-checked or capitalised.
    for (const field of [current, next]) {
      expect(field).toHaveAttribute('spellcheck', 'false');
      expect(field).toHaveAttribute('autocapitalize', 'none');
    }
    expect(screen.getByText('Signed in as maria.')).toBeInTheDocument();

    fireEvent.change(current, { target: { value: 'the old password' } });
    fireEvent.change(next, { target: { value: 'a brand new password' } });
    // Show reveals both, so they can be checked before saving.
    fireEvent.click(within(form).getByRole('button', { name: 'Show passwords' }));
    expect(current).toHaveAttribute('type', 'text');
    expect(next).toHaveAttribute('type', 'text');
    fireEvent.click(within(form).getByRole('button', { name: 'Change password' }));

    expect(
      await screen.findByText('Your password is changed. Anywhere else you were signed in, you are signed out.'),
    ).toBeInTheDocument();
    const sent = server.requests.find((request) => request.url === '/api/v1/me/password');
    expect(sent?.method).toBe('POST');
    expect(sent?.body).toEqual({ currentPassword: 'the old password', newPassword: 'a brand new password' });
    expect(current).toHaveValue('');
    expect(next).toHaveValue('');
    expect(current).toHaveAttribute('type', 'password');
    expect(JSON.stringify({ ...window.localStorage })).not.toContain('a brand new password');
  });

  it('marks and focuses the field a refused change names, keeping what was typed', async () => {
    signedIn('maria').on('POST /api/v1/me/password', {
      status: 400,
      problem: {
        status: 400,
        code: 'VALIDATION_FAILED',
        detail: 'The password change is invalid',
        errors: [{ field: 'currentPassword', code: 'WRONG_PASSWORD', message: 'the current password is not right' }],
      },
    });
    const form = await openAccount();
    fireEvent.change(within(form).getByLabelText('Current password'), { target: { value: 'a guess' } });
    fireEvent.change(within(form).getByLabelText('New password'), { target: { value: 'a brand new password' } });
    fireEvent.click(within(form).getByRole('button', { name: 'Change password' }));

    expect(await screen.findByText('the current password is not right')).toBeInTheDocument();
    const current = within(form).getByLabelText('Current password');
    expect(current).toHaveAttribute('aria-invalid', 'true');
    expect(current).toHaveAccessibleDescription('the current password is not right');
    expect(within(form).getByLabelText('New password')).not.toHaveAttribute('aria-invalid');
    expect(current).toHaveValue('a guess');
    await waitFor(() => expect(current).toHaveFocus());
  });

  it('does not call a change refused when its answer was lost', async () => {
    signedIn('maria').on('POST /api/v1/me/password', { status: 502 });
    const form = await openAccount();
    fireEvent.change(within(form).getByLabelText('Current password'), { target: { value: 'the old password' } });
    fireEvent.change(within(form).getByLabelText('New password'), { target: { value: 'a brand new password' } });
    fireEvent.click(within(form).getByRole('button', { name: 'Change password' }));
    expect(await screen.findByText(/Not confirmed: the exchange did not answer/)).toBeInTheDocument();
    // Kept, so the trader can try again or remember what they typed.
    expect(within(form).getByLabelText('New password')).toHaveValue('a brand new password');
  });

  it('says when too many wrong passwords mean waiting', async () => {
    signedIn('maria').on('POST /api/v1/me/password', {
      status: 429,
      problem: { status: 429, code: 'LOGIN_RATE_LIMITED', detail: 'Too many wrong passwords. Try again in 12 seconds.' },
    });
    const form = await openAccount();
    fireEvent.click(within(form).getByRole('button', { name: 'Change password' }));
    expect(await screen.findByText('Too many wrong passwords. Try again in 12 seconds.')).toBeInTheDocument();
  });

  it('is reached from the trader’s name in the top bar', async () => {
    signedIn('maria').on('GET /api/v1/portfolio', { status: 200, json: { cash: null, holdings: [] } });
    window.history.pushState({}, '', '/help');
    render(<App />);
    const link = await screen.findByRole('link', { name: 'maria, account' });
    expect(link).toHaveAttribute('href', '/account');
  });

  it('is not offered to the operator, whose password comes from configuration', async () => {
    signedIn('operator', ['OPERATOR']);
    window.history.pushState({}, '', '/help');
    render(<App />);
    expect(await screen.findByText('operator')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /account/ })).toBeNull();
  });
});
