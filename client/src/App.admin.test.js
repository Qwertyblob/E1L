import '@testing-library/jest-dom';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import App from './App';

// Slot-builder calendar shows the current month, so a mid-month day cell is
// always reachable without navigation.
const NOW = new Date();
const Y = NOW.getFullYear();
const M = String(NOW.getMonth() + 1).padStart(2, '0');
const PICKED_DATE = `${Y}-${M}-15`;

const ADMIN = { id: 9, name: 'Admin', email: 'admin@every1luvs.com', role: 'ADMIN' };

function jsonResponse(status, body) {
  return Promise.resolve({
    ok: status >= 200 && status < 300,
    status,
    text: () => Promise.resolve(body == null ? '' : JSON.stringify(body)),
  });
}

// Paginated admin endpoints now return a PageResponse envelope, not a bare array.
function page(content) {
  return { content, number: 0, totalPages: content.length ? 1 : 0, totalElements: content.length };
}

let routes;
function setupFetch(extra = []) {
  routes = [
    // overrides first so they win over the defaults below
    ...extra,
    { method: 'GET', path: '/api/me', status: 200, body: ADMIN },
    { method: 'GET', path: '/api/bookings/my', status: 200, body: [] },
    { method: 'GET', path: '/api/admin/bookings', status: 200, body: page([]) },
    { method: 'GET', path: '/api/slots', status: 200, body: page([]) },
  ];
  global.fetch = jest.fn((url, opts = {}) => {
    const method = (opts.method || 'GET').toUpperCase();
    // Paginated endpoints carry ?page=&size=, so match on the path without its query string.
    const pathOf = (u) => String(u).split('?')[0];
    const match = routes.find(
      (r) => r.method === method &&
        (r.path instanceof RegExp ? r.path.test(url) : pathOf(url).endsWith(r.path)),
    );
    if (match) return jsonResponse(match.status ?? 200, match.body ?? null);
    return jsonResponse(404, { message: `no route for ${method} ${url}` });
  });
}

const callsTo = (matcher) =>
  global.fetch.mock.calls.filter(([url]) =>
    matcher instanceof RegExp ? matcher.test(url) : String(url).split('?')[0].endsWith(matcher));

async function gotoSchedule() {
  await userEvent.click(await screen.findByRole('button', { name: 'My Profile' }));
  await userEvent.click(await screen.findByRole('button', { name: 'Schedule' }));
}

beforeEach(() => {
  localStorage.setItem('authUser', JSON.stringify(ADMIN));
  document.cookie = 'XSRF-TOKEN=admin-tok';
});

afterEach(() => {
  jest.restoreAllMocks();
  localStorage.clear();
});

describe('App admin — schedule view loads data', () => {
  test('opening Schedule fetches admin bookings and slots', async () => {
    setupFetch([
      { method: 'GET', path: '/api/slots', status: 200, body: page([
        { id: 101, title: 'Morning Mani', startTime: `${PICKED_DATE}T09:00:00`, endTime: `${PICKED_DATE}T10:00:00`, capacity: 1, bookedCount: 0, createdAt: `${PICKED_DATE}T08:00:00Z` },
      ]) },
    ]);
    render(<App />);
    await gotoSchedule();

    await waitFor(() => expect(callsTo('/api/admin/bookings').length).toBeGreaterThan(0));
    expect(callsTo('/api/slots').length).toBeGreaterThan(0);
    expect(await screen.findByText('Morning Mani')).toBeInTheDocument();
  });

  test('archived slots load on demand into their own tab', async () => {
    setupFetch([
      { method: 'GET', path: '/api/admin/slots/archived', status: 200, body: page([
        { id: 101, title: 'Old Mani', startTime: '2025-01-15T09:00:00', endTime: '2025-01-15T10:00:00', capacity: 1, bookedCount: 1, archived: true, createdAt: '2025-01-01T08:00:00Z' },
      ]) },
      { method: 'GET', path: '/api/slots', status: 200, body: page([
        { id: 102, title: 'Fresh Mani', startTime: `${PICKED_DATE}T09:00:00`, endTime: `${PICKED_DATE}T10:00:00`, capacity: 1, bookedCount: 0, archived: false, createdAt: `${PICKED_DATE}T08:00:00Z` },
      ]) },
    ]);
    render(<App />);
    await gotoSchedule();

    // The archive is never fetched alongside the regular slot list…
    expect(await screen.findByText('Fresh Mani')).toBeInTheDocument();
    expect(callsTo('/api/admin/slots/archived')).toHaveLength(0);

    // …only when the Archived tab is opened.
    await userEvent.click(screen.getByRole('button', { name: 'Archived' }));
    expect(await screen.findByText('Old Mani')).toBeInTheDocument();
    expect(callsTo('/api/admin/slots/archived')).toHaveLength(1);
    expect(document.querySelector('.archived-pill')).toHaveTextContent('Archived');
    // The archived tab shows only archived slots.
    expect(screen.queryByText('Fresh Mani')).not.toBeInTheDocument();
  });
});

describe('App admin — create slots', () => {
  test('builds slots from title + date and POSTs the batch with CSRF', async () => {
    let batchBody = null;
    setupFetch([
      { method: 'POST', path: '/api/admin/slots/batch', status: 200, body: null },
    ]);
    // wrap the route-table fetch to capture the batch payload
    const routedFetch = global.fetch;
    global.fetch = jest.fn((url, opts = {}) => {
      if (String(url).endsWith('/api/admin/slots/batch')) batchBody = JSON.parse(opts.body);
      return routedFetch(url, opts);
    });

    render(<App />);
    await gotoSchedule();

    // title (preview stays empty until a title is present)
    await userEvent.type(document.querySelector('input[name="title"]'), 'Morning Mani');
    // pick a specific date on the builder calendar (default dateMode = specific)
    await userEvent.click(await screen.findByRole('button', { name: '15' }));

    // default slotMode=single, 09:00 / 60min → exactly 1 preview slot
    const createBtn = await screen.findByRole('button', { name: /Create 1 slot/ });
    await userEvent.click(createBtn);

    await waitFor(() => expect(callsTo('/api/admin/slots/batch').length).toBe(1));
    const [, opts] = callsTo('/api/admin/slots/batch')[0];
    expect(opts.method).toBe('POST');
    expect(opts.headers['X-XSRF-TOKEN']).toBe('admin-tok');
    expect(Array.isArray(batchBody.slots)).toBe(true);
    expect(batchBody.slots).toHaveLength(1);
    expect(batchBody.slots[0]).toMatchObject({ title: 'Morning Mani' });
    expect(batchBody.slots[0].startTime).toContain(`${PICKED_DATE}T09:00`);

    expect(await screen.findByText('1 slot created.')).toBeInTheDocument();
  });

  test('create button is disabled until there is a previewable slot', async () => {
    setupFetch();
    render(<App />);
    await gotoSchedule();

    // no title, no date → "Create slots" disabled
    const createBtn = await screen.findByRole('button', { name: 'Create slots' });
    expect(createBtn).toBeDisabled();
  });
});

describe('App admin — manage slots', () => {
  test('deleting a slot calls DELETE then reloads the list', async () => {
    setupFetch([
      { method: 'GET', path: '/api/slots', status: 200, body: page([
        { id: 101, title: 'Morning Mani', startTime: `${PICKED_DATE}T09:00:00`, endTime: `${PICKED_DATE}T10:00:00`, capacity: 1, bookedCount: 0, createdAt: `${PICKED_DATE}T08:00:00Z` },
      ]) },
      { method: 'DELETE', path: /\/api\/admin\/slots\/\d+$/, status: 200, body: null },
    ]);
    render(<App />);
    await gotoSchedule();

    await userEvent.click(await screen.findByRole('button', { name: 'Delete' }));

    await waitFor(() => expect(callsTo(/\/api\/admin\/slots\/101$/).length).toBe(1));
    const [, opts] = callsTo(/\/api\/admin\/slots\/101$/)[0];
    expect(opts.method).toBe('DELETE');
    // reload: /api/slots fetched again after the delete
    await waitFor(() => expect(callsTo('/api/slots').length).toBeGreaterThanOrEqual(2));
  });

  test('surfaces an error when loading slots fails', async () => {
    setupFetch([
      { method: 'GET', path: '/api/slots', status: 500, body: { message: 'Could not load slots.' } },
    ]);
    render(<App />);
    await gotoSchedule();

    expect(await screen.findByText('Could not load slots.')).toBeInTheDocument();
  });
});
