import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';

// Relative base so requests go through the dev-server proxy (same-origin). See App.jsx.
const API_BASE_URL = import.meta.env.VITE_API_URL || '';

const EMPTY_CATALOG = { NAIL_SERVICES: [], NAIL_ART: [], REMOVAL: [], REPAIRS: [] };

const CatalogContext = createContext(null);

// GET /api/catalog → { version, NAIL_SERVICES, NAIL_ART, REMOVAL, REPAIRS } (hidden items already
// removed server-side). `version` is the published catalog id the booking flow echoes back.
function normalize(data) {
  return {
    version: data?.version ?? null,
    catalog: {
      NAIL_SERVICES: data?.NAIL_SERVICES || [],
      NAIL_ART: data?.NAIL_ART || [],
      REMOVAL: data?.REMOVAL || [],
      REPAIRS: data?.REPAIRS || [],
    },
  };
}

/**
 * Serves the live service catalog (names, prices, durations) to the landing page, booking flow and
 * admin editor. Prices are admin-editable, so there is deliberately NO bundled fallback: while
 * loading the UI shows a placeholder, never a possibly-outdated price.
 *
 * The catalog is re-fetched whenever the tab regains focus/visibility, so a long-open tab picks up
 * a publish. `refresh()` resolves to the fresh `{ version, catalog }` (or null if the request failed)
 * so callers can compare versions without waiting for a re-render.
 *
 * `initial` (tests) starts the provider ready with that payload and skips the mount fetch.
 */
export function CatalogProvider({ children, initial = null }) {
  const [state, setState] = useState(() => (
    initial
      ? { status: 'ready', ...normalize(initial) }
      : { status: 'loading', version: null, catalog: EMPTY_CATALOG }
  ));
  const inFlight = useRef(null);
  const skipMountFetch = useRef(Boolean(initial));

  const refresh = useCallback(() => {
    // Coalesce focus + visibilitychange (both fire on a tab switch) into one request.
    if (inFlight.current) return inFlight.current;
    const request = Promise.resolve()
      .then(() => fetch(`${API_BASE_URL}/api/catalog`, { cache: 'no-store' }))
      .then(async (res) => {
        if (!res?.ok) throw new Error(`Catalog request failed (${res?.status}).`);
        const text = await res.text();
        return normalize(text ? JSON.parse(text) : null);
      })
      .then((next) => {
        setState((prev) => (
          prev.status === 'ready' && prev.version === next.version ? prev : { status: 'ready', ...next }
        ));
        return next;
      })
      .catch(() => {
        // Keep a previously loaded catalog on a transient failure; only an initial failure is an error.
        setState((prev) => (prev.status === 'ready' ? prev : { ...prev, status: 'error' }));
        return null;
      })
      .finally(() => {
        inFlight.current = null;
      });
    inFlight.current = request;
    return request;
  }, []);

  useEffect(() => {
    if (skipMountFetch.current) {
      skipMountFetch.current = false;
    } else {
      refresh();
    }
    const onFocus = () => refresh();
    const onVisibilityChange = () => {
      if (document.visibilityState === 'visible') refresh();
    };
    window.addEventListener('focus', onFocus);
    document.addEventListener('visibilitychange', onVisibilityChange);
    return () => {
      window.removeEventListener('focus', onFocus);
      document.removeEventListener('visibilitychange', onVisibilityChange);
    };
  }, [refresh]);

  const value = useMemo(() => ({ ...state, refresh }), [state, refresh]);
  return <CatalogContext.Provider value={value}>{children}</CatalogContext.Provider>;
}

// eslint-disable-next-line react-refresh/only-export-components
export function useCatalog() {
  const value = useContext(CatalogContext);
  if (!value) throw new Error('useCatalog must be used inside <CatalogProvider>.');
  return value;
}
