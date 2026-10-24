// The service catalog itself (services, nail art, removal, repairs, prices, durations) is
// admin-editable and served live by GET /api/catalog — see CatalogContext.jsx. Only the display
// helpers live here: the catalog stores numeric durationMin, and these derive the labels.

// "~45 min" — a service's appointment length.
export function formatServiceDuration(durationMin) {
  return `~${durationMin} min`;
}

// Bundled-photo lookup for a service card: its own seed photo id, else "seed-<service id>" so an
// original service whose catalog entry predates photos (no imageId) still finds its picture.
// Returns the first of `photos`' keys that matches, or null.
export function bundledPhotoKey(service, photos) {
  const candidates = [service?.imageId, service?.id && `seed-${service.id}`];
  return candidates.find((key) => key && Object.hasOwn(photos, key)) || null;
}

// "+35 mins" — the extra time an add-on adds, or null for a no-time option (e.g. "none").
export function formatAddOnTime(durationMin) {
  return durationMin > 0 ? `+${durationMin} mins` : null;
}
