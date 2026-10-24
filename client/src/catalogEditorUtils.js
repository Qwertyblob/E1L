// Pure helpers for the admin catalog editor (CatalogEditor.jsx). The server (CatalogValidator) is
// the authority on what can be saved/published; the checks here mirror it so the admin sees
// problems inline before a round-trip.

export const SECTIONS = [
  { key: 'NAIL_SERVICES', label: 'Services', singular: 'service', kind: 'service' },
  { key: 'NAIL_ART', label: 'Nail art', singular: 'nail art option', kind: 'addon' },
  { key: 'REMOVAL', label: 'Removal', singular: 'removal option', kind: 'addon' },
  { key: 'REPAIRS', label: 'Repairs', singular: 'repair', kind: 'repair' },
];

export const LIMITS = {
  name: 80,
  label: 80,
  sub: 80,
  desc: 400,
  priceNote: 30,
  price: 2000,
  duration: 600,
  // Matches the booking repair multi-select cap (BookingService.MAX_REPAIRS) and the 255-char
  // column that stores a booking's joined repair names.
  visibleRepairs: 10,
  joinedRepairNames: 255,
  // Raw photo upload cap (CatalogService.MAX_UPLOAD_BYTES); the server re-encodes and shrinks it.
  photoBytes: 10 * 1024 * 1024,
};

export const PHOTO_TYPES = ['image/jpeg', 'image/png'];

// The free default option of nail art / removal: always first, free, visible, and its name is fixed
// (booking emails recognise it), so the editor shows it read-only.
export const NONE_ID = 'none';

export function slugify(name) {
  return String(name || '')
    .normalize('NFKD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 34)
    .replace(/-+$/g, '');
}

export function uniqueId(base, takenIds) {
  const root = base || 'new-item';
  if (!takenIds.has(root)) return root;
  for (let n = 2; ; n += 1) {
    const candidate = `${root}-${n}`;
    if (!takenIds.has(candidate)) return candidate;
  }
}

export function newItem(kind, id) {
  if (kind === 'service') {
    return { id, label: null, name: '', desc: null, price: 0, durationMin: 30, popular: false, hidden: false, imageId: null };
  }
  if (kind === 'repair') {
    return { id, name: '', sub: null, desc: null, priceNote: null, hidden: false };
  }
  return { id, name: '', sub: null, price: 0, durationMin: 0, hidden: false };
}

// Treat '' and null as the same so clearing a field and the server's normalized null compare equal.
function canonical(doc) {
  if (!doc) return null;
  const out = {};
  for (const { key } of SECTIONS) {
    out[key] = (doc[key] || []).map((item) => Object.fromEntries(
      Object.entries(item).map(([k, v]) => [k, v === '' ? null : v]).sort(([a], [b]) => a.localeCompare(b)),
    ));
  }
  return JSON.stringify(out);
}

export function sameDocument(a, b) {
  return canonical(a) === canonical(b);
}

const isWholeNumber = (v, max) => Number.isInteger(v) && v >= 0 && v <= max;
const textLength = (v) => (typeof v === 'string' ? v.trim().length : 0);

/**
 * Field-level problems in an edited document: [{ section, index, field, message }]. `field` is null
 * for a section-wide problem. Save/publish are blocked while any exist.
 */
export function validateCatalog(doc) {
  const issues = [];
  if (!doc) return issues;
  for (const { key, kind } of SECTIONS) {
    const items = doc[key] || [];
    items.forEach((item, index) => {
      const add = (field, message) => issues.push({ section: key, index, field, message });
      if (!textLength(item.name)) add('name', 'Name is required.');
      else if (textLength(item.name) > LIMITS.name) add('name', `Name must be ${LIMITS.name} characters or fewer.`);
      if (textLength(item.label) > LIMITS.label) add('label', `Label must be ${LIMITS.label} characters or fewer.`);
      if (textLength(item.sub) > LIMITS.sub) add('sub', `Short line must be ${LIMITS.sub} characters or fewer.`);
      if (textLength(item.desc) > LIMITS.desc) add('desc', `Description must be ${LIMITS.desc} characters or fewer.`);
      if (kind === 'repair') {
        if (textLength(item.priceNote) > LIMITS.priceNote) {
          add('priceNote', `Price note must be ${LIMITS.priceNote} characters or fewer.`);
        }
        return;
      }
      if (kind === 'service' && !item.imageId) add('imageId', 'A picture is required.');
      if (!isWholeNumber(item.price, LIMITS.price)) add('price', `Price must be a whole number from 0 to ${LIMITS.price}.`);
      if (!isWholeNumber(item.durationMin, LIMITS.duration)) {
        add('durationMin', `Duration must be a whole number of minutes from 0 to ${LIMITS.duration}.`);
      } else if (kind === 'service' && !item.hidden && item.durationMin < 1) {
        add('durationMin', 'A bookable service needs a duration of at least 1 minute.');
      }
    });
  }
  const services = doc.NAIL_SERVICES || [];
  if (!services.some((s) => !s.hidden)) {
    issues.push({ section: 'NAIL_SERVICES', index: null, field: null, message: 'At least one service must be visible.' });
  }
  const visibleRepairs = (doc.REPAIRS || []).filter((r) => !r.hidden);
  if (visibleRepairs.length > LIMITS.visibleRepairs) {
    issues.push({ section: 'REPAIRS', index: null, field: null,
      message: `At most ${LIMITS.visibleRepairs} repairs can be visible at once.` });
  }
  const joined = visibleRepairs.reduce((sum, r) => sum + textLength(r.name), 0)
    + Math.max(0, visibleRepairs.length - 1) * 2;
  if (joined > LIMITS.joinedRepairNames) {
    issues.push({ section: 'REPAIRS', index: null, field: null,
      message: 'The visible repair names are too long in total. Shorten some names or hide a repair.' });
  }
  return issues;
}

/** Human-readable changes from the live catalog to the draft, for the Review & publish dialog. */
export function diffCatalog(live, draft) {
  const lines = [];
  for (const { key, label, kind } of SECTIONS) {
    const before = live?.[key] || [];
    const after = draft?.[key] || [];
    const beforeById = new Map(before.map((item) => [item.id, item]));
    for (const item of after) {
      const old = beforeById.get(item.id);
      const name = item.name || '(unnamed)';
      if (!old) {
        const price = kind === 'repair'
          ? (item.priceNote ? ` (${item.priceNote})` : ' (quoted in person)')
          : ` (S$${item.price}, ${item.durationMin} min)`;
        lines.push(`${label}: new — ${name}${price}${item.hidden ? ', hidden' : ''}`);
        continue;
      }
      if (old.name !== item.name) lines.push(`${label}: renamed "${old.name}" → "${item.name}"`);
      if (kind !== 'repair' && old.price !== item.price) lines.push(`${name}: S$${old.price} → S$${item.price}`);
      if (kind !== 'repair' && old.durationMin !== item.durationMin) {
        lines.push(`${name}: ${old.durationMin} min → ${item.durationMin} min`);
      }
      if (kind === 'repair' && (old.priceNote || null) !== (item.priceNote || null)) {
        lines.push(`${name}: price note "${old.priceNote || '—'}" → "${item.priceNote || '—'}"`);
      }
      if (Boolean(old.hidden) !== Boolean(item.hidden)) {
        lines.push(item.hidden ? `${name}: hidden from customers` : `${name}: visible to customers again`);
      }
      if (kind === 'service' && (old.imageId || null) !== (item.imageId || null)) lines.push(`${name}: picture changed`);
      if (kind === 'service' && Boolean(old.popular) !== Boolean(item.popular)) {
        lines.push(item.popular ? `${name}: marked Popular` : `${name}: no longer marked Popular`);
      }
      const textFields = ['label', 'sub', 'desc'].filter((f) => (old[f] || null) !== (item[f] || null));
      if (textFields.length) lines.push(`${name}: wording updated`);
    }
    const afterIds = new Set(after.map((i) => i.id));
    for (const old of before) {
      if (!afterIds.has(old.id)) lines.push(`${label}: deleted — ${old.name}`);
    }
    // Compare the order of items present in both, so a deletion alone isn't reported as a reorder.
    const liveOrder = before.map((i) => i.id).filter((id) => afterIds.has(id)).join('|');
    const draftOrder = after.map((i) => i.id).filter((id) => beforeById.has(id)).join('|');
    if (draftOrder !== liveOrder) lines.push(`${label}: order changed`);
  }
  return lines;
}
