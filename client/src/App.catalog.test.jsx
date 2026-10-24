import '@testing-library/jest-dom';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import App from './App';
import CATALOG from './__fixtures__/catalog.json';
import { diffCatalog } from './catalogEditorUtils';

const ADMIN = { id: 9, name: 'Admin', email: 'admin@every1luvs.com', role: 'ADMIN' };

// The live catalog content (an admin view carries it without the public `version`).
const { version: LIVE_ID, ...LIVE } = CATALOG;
const clone = (value) => JSON.parse(JSON.stringify(value));
const withClassicPrice = (price) => {
  const doc = clone(LIVE);
  doc.NAIL_SERVICES.find((s) => s.id === 'classic').price = price;
  return doc;
};

const liveView = (draft = null) => ({
  published: { id: LIVE_ID, content: LIVE, publishedAt: '2026-10-01T02:00:00Z', publishedBy: 'seed' },
  draft,
});
const draftView = (content, version = 3) => liveView({
  id: 9, version, basePublishedId: LIVE_ID, content, updatedAt: '2026-10-20T02:00:00Z', updatedBy: ADMIN.email,
});

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
    ...extra,
    { method: 'GET', path: '/api/me', status: 200, body: ADMIN },
    { method: 'GET', path: '/api/bookings/my', status: 200, body: [] },
    { method: 'GET', path: '/api/catalog', status: 200, body: CATALOG },
  ];
  global.fetch = jest.fn((url, opts = {}) => {
    const method = (opts.method || 'GET').toUpperCase();
    const path = String(url).split('?')[0];
    const match = routes.find((r) => r.method === method && path.endsWith(r.path));
    if (!match) return jsonResponse(404, { message: `no route for ${method} ${url}` });
    const body = typeof match.body === 'function' ? match.body(opts) : match.body;
    return jsonResponse(match.status ?? 200, body);
  });
}

const callsTo = (method, path) => global.fetch.mock.calls.filter(
  ([url, opts = {}]) => String(url).split('?')[0].endsWith(path) && (opts.method || 'GET').toUpperCase() === method,
);
const bodyOf = (call) => JSON.parse(call[1].body);
const field = (section, index, name) => document.getElementById(`catalog-${section}-${index}-${name}`);

async function gotoServicesTab() {
  render(<App />);
  await userEvent.click(await screen.findByRole('button', { name: 'My Profile' }));
  await userEvent.click(await screen.findByRole('button', { name: 'Services & Prices' }));
  await screen.findByText('Services & prices');
}

async function setNumber(input, value) {
  await userEvent.clear(input);
  await userEvent.type(input, String(value));
}

beforeEach(() => {
  localStorage.setItem('authUser', JSON.stringify(ADMIN));
  document.cookie = 'XSRF-TOKEN=admin-tok';
});

afterEach(() => {
  jest.restoreAllMocks();
  localStorage.clear();
});

describe('Admin catalog editor — drafts', () => {
  test('the first save creates a draft based on the live catalog the admin started from', async () => {
    setupFetch([
      { method: 'GET', path: '/api/admin/catalog', body: liveView() },
      { method: 'PUT', path: '/api/admin/catalog/draft', body: draftView(withClassicPrice(62), 0) },
    ]);
    await gotoServicesTab();
    expect(screen.getByText('Live — no unpublished changes')).toBeInTheDocument();

    await setNumber(field('NAIL_SERVICES', 1, 'price'), 62);
    expect(screen.getByText('Unsaved changes')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Save draft' }));

    await waitFor(() => expect(callsTo('PUT', '/api/admin/catalog/draft')).toHaveLength(1));
    const [call] = callsTo('PUT', '/api/admin/catalog/draft');
    expect(call[1].headers['X-XSRF-TOKEN']).toBe('admin-tok');
    expect(bodyOf(call)).toMatchObject({ draftId: null, version: null, basePublishedId: LIVE_ID });
    expect(bodyOf(call).content.NAIL_SERVICES[1]).toMatchObject({ id: 'classic', price: 62 });
    expect(await screen.findByText('Draft saved — not live yet')).toBeInTheDocument();
  });

  test('saving an existing draft names its exact id and version', async () => {
    setupFetch([
      { method: 'GET', path: '/api/admin/catalog', body: draftView(withClassicPrice(62)) },
      { method: 'PUT', path: '/api/admin/catalog/draft', body: draftView(withClassicPrice(64), 4) },
    ]);
    await gotoServicesTab();

    await setNumber(field('NAIL_SERVICES', 1, 'price'), 64);
    await userEvent.click(screen.getByRole('button', { name: 'Save draft' }));

    await waitFor(() => expect(callsTo('PUT', '/api/admin/catalog/draft')).toHaveLength(1));
    expect(bodyOf(callsTo('PUT', '/api/admin/catalog/draft')[0])).toMatchObject({ draftId: 9, version: 3 });
  });

  test('adding a service derives its id from the name; hiding a live one keeps it in the catalog', async () => {
    setupFetch([
      { method: 'GET', path: '/api/admin/catalog', body: liveView() },
      { method: 'PUT', path: '/api/admin/catalog/draft', body: draftView(LIVE, 0) },
      { method: 'POST', path: '/api/admin/catalog/images', body: { id: 'abc123' } },
    ]);
    await gotoServicesTab();

    await userEvent.click(screen.getByRole('button', { name: '+ Add service' }));
    const newIndex = LIVE.NAIL_SERVICES.length;
    await userEvent.type(field('NAIL_SERVICES', newIndex, 'name'), 'Gel Pédicure');
    await setNumber(field('NAIL_SERVICES', newIndex, 'price'), 65);
    await userEvent.upload(field('NAIL_SERVICES', newIndex, 'imageId'), new File(['jpg'], 'gel.jpg', { type: 'image/jpeg' }));
    expect(await screen.findByAltText('Gel Pédicure picture')).toHaveAttribute('src', '/api/catalog/images/abc123');
    // Hide Express (a live service): it stays in the document, flagged hidden.
    const express = screen.getByText('Express Manicure', { selector: '.catalog-item-title' }).closest('li');
    await userEvent.click(within(express).getByRole('checkbox', { name: 'Shown to customers' }));
    await userEvent.click(screen.getByRole('button', { name: 'Save draft' }));

    await waitFor(() => expect(callsTo('PUT', '/api/admin/catalog/draft')).toHaveLength(1));
    const { content } = bodyOf(callsTo('PUT', '/api/admin/catalog/draft')[0]);
    expect(content.NAIL_SERVICES[newIndex]).toMatchObject({ id: 'gel-pedicure', name: 'Gel Pédicure', price: 65, imageId: 'abc123' });
    const [, uploadOpts] = callsTo('POST', '/api/admin/catalog/images')[0];
    expect(uploadOpts.headers['X-XSRF-TOKEN']).toBe('admin-tok');
    expect(JSON.parse(uploadOpts.body)).toEqual({ contentType: 'image/jpeg', data: 'anBn' }); // base64 "jpg"
    expect(content.NAIL_SERVICES[0]).toMatchObject({ id: 'express', hidden: true });
    expect(content.NAIL_SERVICES).toHaveLength(LIVE.NAIL_SERVICES.length + 1);
  });

  test('a new service needs a picture before it can be saved', async () => {
    setupFetch([{ method: 'GET', path: '/api/admin/catalog', body: liveView() }]);
    await gotoServicesTab();

    await userEvent.click(screen.getByRole('button', { name: '+ Add service' }));
    await userEvent.type(field('NAIL_SERVICES', LIVE.NAIL_SERVICES.length, 'name'), 'Gel Pedicure');

    expect(screen.getByText('A picture is required.')).toBeInTheDocument();
    expect(screen.getByText('No picture yet')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Save draft' })).toBeDisabled();
  });

  test('changing a picture rejects non-photos before uploading', async () => {
    setupFetch([{ method: 'GET', path: '/api/admin/catalog', body: liveView() }]);
    await gotoServicesTab();

    await userEvent.upload(field('NAIL_SERVICES', 1, 'imageId'), new File(['gif'], 'x.gif', { type: 'image/gif' }),
      { applyAccept: false });

    expect(await screen.findByText('Please choose a JPEG or PNG photo.')).toBeInTheDocument();
    expect(callsTo('POST', '/api/admin/catalog/images')).toHaveLength(0);
  });

  test('invalid fields are flagged inline and block saving', async () => {
    setupFetch([{ method: 'GET', path: '/api/admin/catalog', body: liveView() }]);
    await gotoServicesTab();

    await userEvent.clear(field('NAIL_SERVICES', 1, 'name'));

    expect(screen.getByText('Name is required.')).toBeInTheDocument();
    expect(field('NAIL_SERVICES', 1, 'name')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByRole('button', { name: 'Save draft' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Review & publish' })).toBeDisabled();
  });

  test('a 409 keeps the local edits and offers to load the latest version', async () => {
    setupFetch([
      { method: 'GET', path: '/api/admin/catalog', body: draftView(withClassicPrice(62)) },
      { method: 'PUT', path: '/api/admin/catalog/draft', status: 409,
        body: { message: 'This draft was changed by someone else since you loaded it.' } },
    ]);
    await gotoServicesTab();

    await setNumber(field('NAIL_SERVICES', 1, 'price'), 70);
    await userEvent.click(screen.getByRole('button', { name: 'Save draft' }));

    expect(await screen.findByText(/changed by someone else/)).toBeInTheDocument();
    expect(field('NAIL_SERVICES', 1, 'price')).toHaveValue(70);
    expect(screen.getByText('Unsaved changes')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Load latest/ })).toBeInTheDocument();
  });
});

describe('Admin catalog editor — delete', () => {
  test('deleting a live service asks first, then drops it from the saved draft', async () => {
    setupFetch([
      { method: 'GET', path: '/api/admin/catalog', body: liveView() },
      { method: 'PUT', path: '/api/admin/catalog/draft', body: draftView(LIVE, 0) },
    ]);
    await gotoServicesTab();

    const express = screen.getByText('Express Manicure', { selector: '.catalog-item-title' }).closest('li');
    await userEvent.click(within(express).getByRole('button', { name: 'Delete' }));
    const dialog = await screen.findByRole('dialog', { name: 'Delete Express Manicure?' });
    await userEvent.click(within(dialog).getByRole('button', { name: 'Delete' }));

    await waitFor(() => expect(screen.queryByText('Express Manicure', { selector: '.catalog-item-title' })).not.toBeInTheDocument());
    await userEvent.click(screen.getByRole('button', { name: 'Save draft' }));
    await waitFor(() => expect(callsTo('PUT', '/api/admin/catalog/draft')).toHaveLength(1));
    const { content } = bodyOf(callsTo('PUT', '/api/admin/catalog/draft')[0]);
    expect(content.NAIL_SERVICES.map((s) => s.id)).not.toContain('express');
  });

  test('a never-published item is removed straight away', async () => {
    setupFetch([{ method: 'GET', path: '/api/admin/catalog', body: liveView() }]);
    await gotoServicesTab();

    await userEvent.click(screen.getByRole('button', { name: '+ Add service' }));
    expect(screen.getByText('New service', { selector: '.catalog-item-title' })).toBeInTheDocument();
    const added = screen.getByText('New service', { selector: '.catalog-item-title' }).closest('li');
    await userEvent.click(within(added).getByRole('button', { name: 'Delete' }));

    expect(screen.queryByText('New service', { selector: '.catalog-item-title' })).not.toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
});

describe('Admin catalog editor — publish', () => {
  test('review shows the changes vs live, then publishes exactly the reviewed draft revision', async () => {
    setupFetch([
      { method: 'GET', path: '/api/admin/catalog', body: draftView(withClassicPrice(62)) },
      { method: 'POST', path: '/api/admin/catalog/publish',
        body: { published: { id: 9, content: withClassicPrice(62), publishedAt: '2026-10-24T02:00:00Z', publishedBy: ADMIN.email }, draft: null } },
    ]);
    await gotoServicesTab();
    const catalogFetchesBefore = callsTo('GET', '/api/catalog').length;

    await userEvent.click(screen.getByRole('button', { name: 'Review & publish' }));

    const dialog = await screen.findByRole('dialog', { name: 'Publish these changes?' });
    expect(within(dialog).getByText('Classic Manicure: S$58 → S$62')).toBeInTheDocument();
    expect(callsTo('PUT', '/api/admin/catalog/draft')).toHaveLength(0); // nothing unsaved to save first
    await userEvent.click(within(dialog).getByRole('button', { name: 'Publish' }));

    await waitFor(() => expect(callsTo('POST', '/api/admin/catalog/publish')).toHaveLength(1));
    expect(bodyOf(callsTo('POST', '/api/admin/catalog/publish')[0])).toEqual({ draftId: 9, version: 3 });
    expect(await screen.findByText(/Published — the website now shows/)).toBeInTheDocument();
    // The site's own catalog is refreshed so the new prices show without a reload.
    await waitFor(() => expect(callsTo('GET', '/api/catalog').length).toBeGreaterThan(catalogFetchesBefore));
  });

  test('unsaved edits are saved first, and the dialog binds to the saved revision', async () => {
    setupFetch([
      { method: 'GET', path: '/api/admin/catalog', body: liveView() },
      { method: 'PUT', path: '/api/admin/catalog/draft', body: draftView(withClassicPrice(62), 0) },
      { method: 'POST', path: '/api/admin/catalog/publish', body: liveView() },
    ]);
    await gotoServicesTab();

    await setNumber(field('NAIL_SERVICES', 1, 'price'), 62);
    await userEvent.click(screen.getByRole('button', { name: 'Review & publish' }));

    const dialog = await screen.findByRole('dialog', { name: 'Publish these changes?' });
    expect(callsTo('PUT', '/api/admin/catalog/draft')).toHaveLength(1);
    await userEvent.click(within(dialog).getByRole('button', { name: 'Publish' }));
    await waitFor(() => expect(callsTo('POST', '/api/admin/catalog/publish')).toHaveLength(1));
    expect(bodyOf(callsTo('POST', '/api/admin/catalog/publish')[0])).toEqual({ draftId: 9, version: 0 });
  });
});

describe('diffCatalog', () => {
  test('lists price, duration, visibility, new items and repair price-note changes', () => {
    const draft = clone(LIVE);
    draft.NAIL_SERVICES[1].price = 62;
    draft.NAIL_SERVICES[1].durationMin = 50;
    draft.NAIL_SERVICES[0].hidden = true;
    draft.NAIL_ART.push({ id: 'tier4', name: 'Tier 4 — Bespoke', sub: null, price: 70, durationMin: 90, hidden: false });
    draft.REPAIRS[0].priceNote = 'S$4/nail';
    draft.REMOVAL = draft.REMOVAL.filter((r) => r.id !== 'other-ext');
    draft.NAIL_SERVICES[2].imageId = 'abc123';

    expect(diffCatalog(LIVE, draft)).toEqual([
      'Express Manicure: hidden from customers',
      'Classic Manicure: S$58 → S$62',
      'Classic Manicure: 45 min → 50 min',
      'Structured Classic Manicure: picture changed',
      'Nail art: new — Tier 4 — Bespoke (S$70, 90 min)',
      'Removal: deleted — Extensions',
      'Nail Fix: price note "S$3/nail" → "S$4/nail"',
    ]);
  });
});

describe('Landing page photos', () => {
  test('service cards show the uploaded photo, falling back to the bundled one if it fails to load', async () => {
    localStorage.clear();
    setupFetch();
    render(<App />);

    const photo = await screen.findByAltText('Classic Manicure', { selector: '.service-card-photo' });
    expect(photo).toHaveAttribute('src', '/api/catalog/images/seed-classic');
    photo.dispatchEvent(new Event('error'));
    await waitFor(() => expect(photo.getAttribute('src')).not.toContain('/api/catalog/images/'));
  });
});

describe('Landing page when the backend is down', () => {
  test('still shows services and prices from the bundled copy', async () => {
    localStorage.clear();
    setupFetch([{ method: 'GET', path: '/api/catalog', status: 503, body: { message: 'down' } }]);
    render(<App />);

    const classic = (await screen.findByText('Classic Manicure', { selector: '.service-card-title' })).closest('.service-card');
    expect(within(classic).getByText('S$58')).toBeInTheDocument();
    expect(screen.getByText('S$3/nail')).toBeInTheDocument();
    expect(screen.queryByText('Loading services…')).not.toBeInTheDocument();
  });
});
