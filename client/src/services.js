// Single source of truth for service pricing, durations, and add-ons lives in
// services.json. The backend (BookingCatalog) reads a build-time copy of that
// same JSON, so prices/options only ever need to be edited in one place.
// Consumed by both the landing page (App.jsx) and the booking flow (BookingModal.jsx).

import catalog from './services.json';

export const NAIL_SERVICES = catalog.NAIL_SERVICES;
export const NAIL_ART = catalog.NAIL_ART;
export const REMOVAL = catalog.REMOVAL;
// Multi-select repair add-ons. They are quoted in person, so they carry price 0 and
// durationMin 0 and contribute nothing to the estimate (see BookingModal).
export const REPAIRS = catalog.REPAIRS;
