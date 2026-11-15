import { type Page, expect, test } from '@playwright/test';

// Runs first, against the fresh stack, before trading-flow sets up its market: test files run in name order on one
// worker. The operator adds a named scratch instrument through the setup editor, changes its name and price, removes
// the name, and deletes it, each answered by the real Execution and shown once Market Data has it. Then the operator
// adds a trader login, which signs in at once. It leaves behind the market, still in SETUP with no instruments, so
// trading-flow finds a clean catalogue, and that login, without an account.

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

  // Added through the form with a name.
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

test('the operator adds a trader with a generated password, who signs in at once', async ({ page, browser }) => {
  await signInAsOperator(page);
  const accounts = page.getByRole('region', { name: 'Accounts' });
  const add = accounts.getByRole('form', { name: 'Add a trader' });
  // A name of its own on each run, so the test can run again on the same stack.
  const username = `e2e-${Date.now().toString(36)}`;
  await add.getByLabel('Username').fill(username);
  await add.getByRole('button', { name: 'Generate' }).click();
  const password = await add.getByLabel('Password').inputValue();
  expect(password).toMatch(/^[a-km-np-zA-HJ-NP-Z2-9]{20}$/);
  await add.getByRole('button', { name: 'Add trader' }).click();
  await expect(accounts.getByText(`Added ${username}, without an account yet`, { exact: false })).toBeVisible();
  await expect(add.getByLabel('Password')).toHaveValue('');
  await expect(
    accounts.getByRole('table', { name: 'Traders without an account' }).getByRole('rowheader', { name: username }),
  ).toBeVisible();

  // The new trader signs in at once, in a browser of their own, and is greeted by the tour.
  const context = await browser.newContext();
  const trader = await context.newPage();
  await trader.goto('/');
  await trader.getByLabel('Username').fill(username);
  await trader.getByLabel('Password').fill(password);
  await trader.getByRole('button', { name: 'Sign in' }).click();
  await expect(trader.getByRole('dialog')).toBeVisible();
  await context.close();
});
