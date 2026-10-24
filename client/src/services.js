// The service catalog itself (services, nail art, removal, repairs, prices, durations) is
// admin-editable and served live by GET /api/catalog — see CatalogContext.jsx. Only the display
// helpers live here: the catalog stores numeric durationMin, and these derive the labels.

// "~45 min" — a service's appointment length.
export function formatServiceDuration(durationMin) {
  return `~${durationMin} min`;
}

// "+35 mins" — the extra time an add-on adds, or null for a no-time option (e.g. "none").
export function formatAddOnTime(durationMin) {
  return durationMin > 0 ? `+${durationMin} mins` : null;
}
