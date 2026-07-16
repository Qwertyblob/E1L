// Stub for static image imports so tests don't resolve real asset files
// (and aren't affected by asset path mismatches). Wired in via the `test.alias`
// regex in vite.config.js (the Vitest equivalent of Jest's moduleNameMapper).
export default 'test-file-stub';
