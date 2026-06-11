// Single source of truth for service pricing, durations, and add-ons lives in
// services.json. The backend (BookingCatalog) reads a build-time copy of that
// same JSON, so prices/options only ever need to be edited in one place.
// Consumed by both the landing page (App.jsx) and the booking flow (BookingModal.jsx).

import catalog from './services.json';

export const NAIL_SERVICES = catalog.NAIL_SERVICES;
export const COOL_SERVICES = catalog.COOL_SERVICES;
export const NAIL_ART = catalog.NAIL_ART;
export const REMOVAL = catalog.REMOVAL;
