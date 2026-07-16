import '@testing-library/jest-dom';
import { afterEach, vi } from 'vitest';
import { cleanup } from '@testing-library/react';

// The test suite was written for CRA/Jest and uses `jest.fn()` and
// `jest.restoreAllMocks()`. Vitest's `vi` is API-compatible for those, so alias it
// rather than editing every test file.
globalThis.jest = vi;

// CRA's react-scripts unmounted React trees between tests automatically; Vitest does
// not, so clean up explicitly to keep the DOM isolated per test.
afterEach(() => {
  cleanup();
});
