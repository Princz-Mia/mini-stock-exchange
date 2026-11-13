import { type Page, expect, test } from '@playwright/test';

// Runs first, against the fresh stack, before trading-flow sets up its market: test files run in name order on one
// worker. The operator adds a named scratch instrument through the setup editor, changes its name and price, removes
// the name, and deletes it, each answered by the real Execution and shown once Market Data has it. It leaves nothing
// behind but the market itself, still in SETUP with no instruments, so trading-flow finds a clean catalogue.

function required(name: string): string {
  const value = process.env[name];
  if (value === undefined || value === '') {
    throw new Error(`Set ${name}`);
  }
  return value;
}

async function signInAsOperator(page: Page): Promise<void> {
  await page.goto('/');
  await page.getByLabel('Username').fill('operator');
  await page.getByLabel('Password').fill(required('E2E_OPERATOR_PASSWORD'));
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('heading', { name: 'Market' })).toBeVisible();
}

test('the setup editor adds, edits and deletes an instrument, leaving nothing behind', async ({ page }) => {
  await signInAsOperator(page);
  const instruments = page.getByRole('region', { name: 'Instruments' });
  await expect(instruments.getByText('No instruments yet.')).toBeVisible();

  // Added through the form with a name: the first instrument also creates the market.
  const add = instruments.getByRole('form', { name: 'Add an instrument' });
  await add.getByLabel('Symbol').fill('ZEDIT');
  await add.getByLabel('Reference price').fill('10.00');
  await add.getByLabel('Name (optional)').fill('Zed Editing');
  await add.getByRole('button', { name: 'Add instrument' }).click();
  await expect(instruments.getByText('Added ZEDIT (Zed Editing) at 10.00.', { exact: false })).toBeVisible();
  const row = instruments.getByRole('row').filter({ has: page.getByRole('rowheader', { name: /^ZEDIT/ }) });
  // The list comes from Market Data, so the row appears once the snapshot is projected, name included.
  await expect(row).toContainText('10.00', { timeout: 15_000 });
  await expect(row.getByRole('rowheader')).toHaveAccessibleName('ZEDIT Zed Editing');

  // A new name and price, saved together by Execution and then shown in the list.
  await row.getByRole('button', { name: 'Edit ZEDIT' }).click();
  const form = instruments.getByRole('form', { name: 'Edit ZEDIT' });
  await expect(form.getByLabel('Name')).toHaveValue('Zed Editing');
  await form.getByLabel('Name').fill('Zed Editing Group');
  await form.getByLabel('Reference price').fill('12.50');
  await form.getByRole('button', { name: 'Save ZEDIT' }).click();
  await expect(instruments.getByText('Saved ZEDIT as Zed Editing Group at 12.50.', { exact: false })).toBeVisible();
  await expect(row).toContainText('12.50', { timeout: 15_000 });
  await expect(row.getByRole('rowheader')).toHaveAccessibleName('ZEDIT Zed Editing Group', { timeout: 15_000 });

  // An empty name removes it, through Execution and Market Data both.
  await row.getByRole('button', { name: 'Edit ZEDIT' }).click();
  await form.getByLabel('Name').fill('');
  await form.getByRole('button', { name: 'Save ZEDIT' }).click();
  await expect(instruments.getByText('Saved ZEDIT, with no name, at 12.50.', { exact: false })).toBeVisible();
  await expect(row.getByRole('rowheader')).toHaveAccessibleName('ZEDIT', { timeout: 15_000 });

  // Deleted once confirmed: Execution removes it and Market Data drops it from the list.
  await row.getByRole('button', { name: 'Delete ZEDIT' }).click();
  await instruments.getByRole('button', { name: 'Confirm delete of ZEDIT' }).click();
  await expect(instruments.getByText('ZEDIT is deleted.', { exact: false })).toBeVisible();
  await expect(add.getByLabel('Symbol')).toBeFocused();
  await expect(instruments.getByText('No instruments yet.')).toBeVisible({ timeout: 15_000 });

  // Nothing is left for trading-flow but an empty market in SETUP.
  const listed = (await (await page.request.get('/api/v1/instruments')).json()) as { instruments: unknown[] };
  expect(listed.instruments).toEqual([]);
  const market = (await (await page.request.get('/api/v1/market')).json()) as { state: string };
  expect(market.state).toBe('SETUP');
});
