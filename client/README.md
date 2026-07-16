# every1luvs client (React 19 + Vite)

The React SPA for every1luvs. Built with [Vite](https://vite.dev/); tests run on
[Vitest](https://vitest.dev/) + React Testing Library. Served in production as a static
bundle by nginx (see `web/Dockerfile` and `nginx/nginx.conf`).

## Scripts

Run from `client/`:

- `npm run dev` (alias `npm start`) — dev server on http://localhost:5173. `/api` is
  proxied to the local Spring Boot app on `localhost:8080` (see `server.proxy` in
  `vite.config.js`), keeping requests same-origin so the auth/CSRF cookies work.
- `npm test` — run the suite once (`vitest run`). `npm run test:watch` for watch mode.
- `npm run lint` — ESLint (flat config in `eslint.config.js`).
- `npm run build` — production bundle to `dist/` (what the Docker `web` image serves).
- `npm run preview` — serve the built `dist/` locally to sanity-check the prod bundle.

## Notes that matter

- **API base URL:** `import.meta.env.VITE_API_URL` (unset → `''` → relative same-origin
  `/api`). Only set it if the API is served from a different origin.
- **Fonts are self-hosted** via `@fontsource/playfair-display` (imported in
  `src/index.jsx`), so the page makes no external Google Fonts requests and the nginx CSP
  needs no `fonts.googleapis.com` / `fonts.gstatic.com` exceptions.
- **Strict CSP (`script-src 'self'`):** `vite.config.js` sets
  `build.modulePreload.polyfill = false` so the build emits no inline `<script>`. Don't
  re-enable it without loosening the CSP in `nginx/nginx.conf`.
- **Shared service catalog:** `src/services.json` is the single source of truth for
  services/prices; the Maven build copies it onto the backend classpath. Edit prices there.
