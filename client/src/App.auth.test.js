import '@testing-library/jest-dom';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import App from './App';

// ── fetch route table ──────────────────────────────────────────────────────────
// apiRequest() does fetch → response.text() → JSON.parse, and throws data.message
// on non-2xx. Each mock response mirrors that contract.
function jsonResponse(status, body) {
  return Promise.resolve({
    ok: status >= 200 && status < 300,
    status,
    text: () => Promise.resolve(body == null ? '' : JSON.stringify(body)),
  });
}

let routes;
function setupFetch(extra = []) {
  routes = [
    // default: not authenticated (GET /api/me on mount → clearSession)
    { method: 'GET', path: '/api/me', status: 401, body: { message: 'Unauthorized' } },
    ...extra,
  ];
  global.fetch = jest.fn((url, opts = {}) => {
    const method = (opts.method || 'GET').toUpperCase();
    const match = routes.find(
      (r) => r.method === method &&
        (r.path instanceof RegExp ? r.path.test(url) : String(url).endsWith(r.path)),
    );
    if (match) return jsonResponse(match.status ?? 200, match.body ?? null);
    return jsonResponse(404, { message: `no route for ${method} ${url}` });
  });
}

const dialog = () => screen.getByRole('dialog');
const submitBtn = () => dialog().querySelector('button[type="submit"]');
const callsTo = (path) =>
  global.fetch.mock.calls.filter(([url]) => String(url).endsWith(path));

async function openAuthModal() {
  await userEvent.click(await screen.findByRole('button', { name: 'Sign In' }));
  return screen.findByRole('dialog');
}

beforeEach(() => {
  localStorage.clear();
  document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT';
});

afterEach(() => {
  jest.restoreAllMocks();
});

describe('App auth — apiRequest contract', () => {
  test('login attaches CSRF header + credentials, sends JSON body, persists session', async () => {
    document.cookie = 'XSRF-TOKEN=tok-123';
    setupFetch([
      { method: 'POST', path: '/api/auth/login', status: 200, body: { id: 1, name: 'Alice', email: 'alice@example.com', role: 'USER' } },
    ]);
    render(<App />);
    await openAuthModal();

    await userEvent.type(within(dialog()).getByLabelText('Email'), 'alice@example.com');
    await userEvent.type(within(dialog()).getByLabelText('Password'), 'Password1');
    await userEvent.click(submitBtn());

    await waitFor(() => expect(callsTo('/api/auth/login')).toHaveLength(1));
    const [, opts] = callsTo('/api/auth/login')[0];
    expect(opts.method).toBe('POST');
    expect(opts.credentials).toBe('include');
    expect(opts.headers['X-XSRF-TOKEN']).toBe('tok-123');
    expect(JSON.parse(opts.body)).toEqual({ email: 'alice@example.com', password: 'Password1' });

    // session persisted + modal closed
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(JSON.parse(localStorage.getItem('authUser'))).toMatchObject({ email: 'alice@example.com', role: 'USER' });
    expect(await screen.findByRole('button', { name: 'My Profile' })).toBeInTheDocument();
  });

  test('GET requests do NOT carry the CSRF header', async () => {
    document.cookie = 'XSRF-TOKEN=tok-xyz';
    setupFetch();
    render(<App />);
    await screen.findByRole('button', { name: 'Sign In' });

    await waitFor(() => expect(callsTo('/api/me').length).toBeGreaterThan(0));
    const [, opts] = callsTo('/api/me')[0];
    expect(opts.headers['X-XSRF-TOKEN']).toBeUndefined();
  });
});

describe('App auth — login flow', () => {
  test('shows the server error message and keeps the modal open on failure', async () => {
    setupFetch([
      { method: 'POST', path: '/api/auth/login', status: 401, body: { message: 'Invalid email or password.' } },
    ]);
    render(<App />);
    await openAuthModal();

    await userEvent.type(within(dialog()).getByLabelText('Email'), 'alice@example.com');
    await userEvent.type(within(dialog()).getByLabelText('Password'), 'wrongpass');
    await userEvent.click(submitBtn());

    expect(await within(dialog()).findByText('Invalid email or password.')).toBeInTheDocument();
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(localStorage.getItem('authUser')).toBeNull();
  });

  test('an unverified account switches the modal into verify mode', async () => {
    setupFetch([
      { method: 'POST', path: '/api/auth/login', status: 403, body: { message: 'Account is not verified.' } },
    ]);
    render(<App />);
    await openAuthModal();

    await userEvent.type(within(dialog()).getByLabelText('Email'), 'alice@example.com');
    await userEvent.type(within(dialog()).getByLabelText('Password'), 'Password1');
    await userEvent.click(submitBtn());

    // verify mode reveals the OTP field + heading
    expect(await within(dialog()).findByRole('heading', { name: 'Verify account' })).toBeInTheDocument();
    expect(within(dialog()).getByLabelText('Verification code')).toBeInTheDocument();
  });
});

describe('App auth — register → verify → signed in', () => {
  test('register transitions to verify mode, then verifying signs the user in', async () => {
    setupFetch([
      { method: 'POST', path: '/api/auth/register', status: 200, body: { message: 'Verification code sent.', email: 'bob@example.com' } },
      { method: 'POST', path: '/api/auth/verify-account', status: 200, body: { id: 2, name: 'Bob', email: 'bob@example.com', role: 'USER' } },
    ]);
    render(<App />);
    await openAuthModal();

    // switch to Register tab
    await userEvent.click(within(dialog()).getByRole('button', { name: 'Register' }));
    await userEvent.type(within(dialog()).getByLabelText('Name'), 'Bob');
    await userEvent.type(within(dialog()).getByLabelText('Email'), 'bob@example.com');
    await userEvent.type(within(dialog()).getByLabelText('Password'), 'Password1');
    await userEvent.click(submitBtn());

    await waitFor(() => expect(callsTo('/api/auth/register')).toHaveLength(1));
    expect(JSON.parse(callsTo('/api/auth/register')[0][1].body)).toEqual({
      name: 'Bob', email: 'bob@example.com', password: 'Password1',
    });

    // now in verify mode showing the target email
    expect(await within(dialog()).findByText('Verification code sent.')).toBeInTheDocument();
    expect(within(dialog()).getByText('bob@example.com')).toBeInTheDocument();

    // verify
    await userEvent.type(within(dialog()).getByLabelText('Verification code'), '123456');
    await userEvent.click(submitBtn());

    await waitFor(() => expect(callsTo('/api/auth/verify-account')).toHaveLength(1));
    expect(JSON.parse(callsTo('/api/auth/verify-account')[0][1].body)).toEqual({
      email: 'bob@example.com', otp: '123456',
    });
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(JSON.parse(localStorage.getItem('authUser'))).toMatchObject({ email: 'bob@example.com' });
  });

  test('resend code re-requests an OTP and shows the confirmation', async () => {
    setupFetch([
      { method: 'POST', path: '/api/auth/register', status: 200, body: { message: 'Verification code sent.', email: 'bob@example.com' } },
      { method: 'POST', path: '/api/auth/resend-verification-otp', status: 200, body: { message: 'A new code is on its way.' } },
    ]);
    render(<App />);
    await openAuthModal();
    await userEvent.click(within(dialog()).getByRole('button', { name: 'Register' }));
    await userEvent.type(within(dialog()).getByLabelText('Name'), 'Bob');
    await userEvent.type(within(dialog()).getByLabelText('Email'), 'bob@example.com');
    await userEvent.type(within(dialog()).getByLabelText('Password'), 'Password1');
    await userEvent.click(submitBtn());

    await within(dialog()).findByRole('heading', { name: 'Verify account' });
    await userEvent.click(within(dialog()).getByRole('button', { name: 'Resend code' }));

    await waitFor(() => expect(callsTo('/api/auth/resend-verification-otp')).toHaveLength(1));
    expect(JSON.parse(callsTo('/api/auth/resend-verification-otp')[0][1].body)).toEqual({ email: 'bob@example.com' });
    expect(await within(dialog()).findByText('A new code is on its way.')).toBeInTheDocument();
  });
});

describe('App auth — forgot password', () => {
  test('sends a reset request and surfaces the success message', async () => {
    setupFetch([
      { method: 'POST', path: '/api/auth/forgot-password', status: 200, body: { message: 'A temporary password has been emailed.' } },
    ]);
    render(<App />);
    await openAuthModal();

    await userEvent.click(within(dialog()).getByRole('button', { name: 'Forgot password?' }));
    await userEvent.type(within(dialog()).getByLabelText('Email'), 'alice@example.com');
    await userEvent.click(submitBtn());

    await waitFor(() => expect(callsTo('/api/auth/forgot-password')).toHaveLength(1));
    expect(JSON.parse(callsTo('/api/auth/forgot-password')[0][1].body)).toEqual({ email: 'alice@example.com' });
    expect(await within(dialog()).findByText('A temporary password has been emailed.')).toBeInTheDocument();
  });
});

describe('App auth — change password', () => {
  // The default setupFetch forces /api/me → 401; authenticated render needs a 200 there,
  // so this block installs its own route table (me first, so it matches).
  function setupAuthedFetch(extra = []) {
    const authedRoutes = [
      { method: 'GET', path: '/api/me', status: 200, body: { id: 1, name: 'Alice', email: 'alice@example.com', role: 'USER' } },
      { method: 'GET', path: '/api/bookings/my', status: 200, body: [] },
      ...extra,
    ];
    global.fetch = jest.fn((url, opts = {}) => {
      const method = (opts.method || 'GET').toUpperCase();
      const match = authedRoutes.find(
        (r) => r.method === method && String(url).endsWith(r.path),
      );
      return match ? jsonResponse(match.status ?? 200, match.body ?? null)
        : jsonResponse(404, { message: `no route for ${method} ${url}` });
    });
  }

  test('a successful change logs the user out and prompts a fresh sign-in', async () => {
    localStorage.setItem('authUser', JSON.stringify({ id: 1, name: 'Alice', email: 'alice@example.com', role: 'USER' }));
    document.cookie = 'XSRF-TOKEN=tok-1';
    setupAuthedFetch([
      { method: 'POST', path: '/api/me/change-password', status: 200, body: { message: 'Password changed successfully.' } },
    ]);
    render(<App />);

    // Signed in → open the profile, switch to the Account tab.
    await userEvent.click(await screen.findByRole('button', { name: 'My Profile' }));
    await userEvent.click(await screen.findByRole('button', { name: 'Account' }));

    await userEvent.type(screen.getByLabelText('Current password'), 'OldPass1');
    await userEvent.type(screen.getByLabelText('New password'), 'NewPass1');
    await userEvent.click(screen.getByRole('button', { name: 'Change password' }));

    await waitFor(() => expect(callsTo('/api/me/change-password')).toHaveLength(1));

    // Force re-login: session torn down, sign-in modal shown with the prompt, no logout call.
    expect(await screen.findByText('Your password was changed. Please sign in again.')).toBeInTheDocument();
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(localStorage.getItem('authUser')).toBeNull();
    expect(screen.queryByRole('button', { name: 'My Profile' })).not.toBeInTheDocument();
    expect(callsTo('/api/auth/logout')).toHaveLength(0);
  });
});

describe('App auth — sign out (D3)', () => {
  // Authenticated render: /api/me must 200 so the app boots signed in (the default
  // setupFetch forces 401). Routes are matched first-wins, so /api/me leads.
  function setupAuthedFetch(extra = []) {
    const authedRoutes = [
      { method: 'GET', path: '/api/me', status: 200, body: { id: 1, name: 'Alice', email: 'alice@example.com', role: 'USER' } },
      { method: 'GET', path: '/api/bookings/my', status: 200, body: [] },
      ...extra,
    ];
    global.fetch = jest.fn((url, opts = {}) => {
      const method = (opts.method || 'GET').toUpperCase();
      const match = authedRoutes.find((r) => r.method === method && String(url).endsWith(r.path));
      return match ? jsonResponse(match.status ?? 200, match.body ?? null)
        : jsonResponse(404, { message: `no route for ${method} ${url}` });
    });
  }

  async function openAccountTab() {
    await userEvent.click(await screen.findByRole('button', { name: 'My Profile' }));
    await userEvent.click(await screen.findByRole('button', { name: 'Account' }));
  }

  test('a confirmed logout tears down the session and returns to the landing view', async () => {
    localStorage.setItem('authUser', JSON.stringify({ id: 1, name: 'Alice', email: 'alice@example.com', role: 'USER' }));
    document.cookie = 'XSRF-TOKEN=tok-1';
    setupAuthedFetch([
      { method: 'POST', path: '/api/auth/logout', status: 200, body: { message: 'Logged out.' } },
    ]);
    render(<App />);

    await openAccountTab();
    await userEvent.click(screen.getByRole('button', { name: 'Sign out' }));

    // The server-confirmed logout clears the session and drops us back to an anonymous landing.
    await waitFor(() => expect(callsTo('/api/auth/logout')).toHaveLength(1));
    expect(await screen.findByRole('button', { name: 'Sign In' })).toBeInTheDocument();
    expect(localStorage.getItem('authUser')).toBeNull();
  });

  test('a failed logout keeps the session and surfaces the error', async () => {
    localStorage.setItem('authUser', JSON.stringify({ id: 1, name: 'Alice', email: 'alice@example.com', role: 'USER' }));
    document.cookie = 'XSRF-TOKEN=tok-1';
    setupAuthedFetch([
      { method: 'POST', path: '/api/auth/logout', status: 500, body: { message: 'Logout failed. Please try again.' } },
    ]);
    render(<App />);

    await openAccountTab();
    await userEvent.click(screen.getByRole('button', { name: 'Sign out' }));

    await waitFor(() => expect(callsTo('/api/auth/logout')).toHaveLength(1));
    // The httpOnly cookie couldn't be cleared, so the session must NOT look torn down:
    // error surfaced, still signed in, stored user intact.
    expect(await screen.findByRole('alert')).toHaveTextContent('Logout failed. Please try again.');
    expect(screen.getByRole('button', { name: 'Sign out' })).toBeInTheDocument();
    expect(localStorage.getItem('authUser')).not.toBeNull();
  });
});

describe('App auth — profile refresh resilience (D2)', () => {
  test('a 5xx on /api/me does NOT sign the user out (transient failure)', async () => {
    localStorage.setItem('authUser', JSON.stringify({ id: 1, name: 'Alice', email: 'alice@example.com', role: 'USER' }));
    const transientRoutes = [
      { method: 'GET', path: '/api/me', status: 500, body: { message: 'Server error' } },
      { method: 'GET', path: '/api/bookings/my', status: 200, body: [] },
    ];
    global.fetch = jest.fn((url, opts = {}) => {
      const method = (opts.method || 'GET').toUpperCase();
      const match = transientRoutes.find((r) => r.method === method && String(url).endsWith(r.path));
      return match ? jsonResponse(match.status, match.body) : jsonResponse(404, { message: 'no route' });
    });
    render(<App />);

    // /api/me is hit on mount and 500s; the stored session must survive the blip.
    await waitFor(() => expect(callsTo('/api/me').length).toBeGreaterThan(0));
    expect(await screen.findByRole('button', { name: 'My Profile' })).toBeInTheDocument();
    expect(localStorage.getItem('authUser')).not.toBeNull();
  });

  test('a 401 on /api/me signs the user out (session truly gone)', async () => {
    localStorage.setItem('authUser', JSON.stringify({ id: 1, name: 'Alice', email: 'alice@example.com', role: 'USER' }));
    setupFetch(); // default route: /api/me -> 401
    render(<App />);

    await waitFor(() => expect(callsTo('/api/me').length).toBeGreaterThan(0));
    expect(await screen.findByRole('button', { name: 'Sign In' })).toBeInTheDocument();
    expect(localStorage.getItem('authUser')).toBeNull();
  });
});
