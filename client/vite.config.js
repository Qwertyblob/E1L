/// <reference types="vitest/config" />
import { fileURLToPath, URL } from 'node:url';
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Migrated from Create React App. Notes that matter for prod:
// - build.outDir defaults to `dist`; web/Dockerfile copies /app/dist.
// - modulePreload.polyfill is disabled so Vite does NOT inject an inline <script>;
//   the strict nginx CSP is `script-src 'self'` (no 'unsafe-inline'). Modern browsers
//   (our only targets) support <link rel="modulepreload"> natively, so the polyfill
//   is unnecessary. Keep this off or the page breaks under CSP.
export default defineConfig({
  plugins: [react()],
  server: {
    // Dev-only: mirror CRA's `proxy` field so `/api` hits the local Spring Boot app.
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
  build: {
    modulePreload: { polyfill: false },
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: './src/vitest.setup.js',
    // Static image imports resolve to a stub so tests stay hermetic (mirrors the
    // former Jest moduleNameMapper -> src/__mocks__/fileMock.js).
    alias: [
      {
        // Must match the WHOLE specifier: for a RegExp alias Vite replaces only the
        // matched substring, so anchoring with ^…$ swaps the entire import path for
        // the stub (a bare `\.jpg$` would mangle the path).
        find: /^.*\.(jpg|jpeg|png|gif|webp|svg)$/,
        replacement: fileURLToPath(new URL('./src/__mocks__/fileMock.js', import.meta.url)),
      },
    ],
  },
});
