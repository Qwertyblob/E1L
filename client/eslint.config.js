import js from '@eslint/js';
import globals from 'globals';
import reactHooks from 'eslint-plugin-react-hooks';
import reactRefresh from 'eslint-plugin-react-refresh';

// Flat config (ESLint 10). Replaces CRA's `eslintConfig: { extends: ['react-app'] }`.
// `npm run lint` runs `eslint .`; CI runs it as an explicit step.
export default [
  { ignores: ['dist', 'build', 'coverage', 'node_modules'] },
  js.configs.recommended,
  {
    files: ['**/*.{js,jsx}'],
    languageOptions: {
      ecmaVersion: 2022,
      sourceType: 'module',
      globals: { ...globals.browser },
      parserOptions: {
        ecmaVersion: 'latest',
        ecmaFeatures: { jsx: true },
      },
    },
    plugins: {
      'react-hooks': reactHooks,
      'react-refresh': reactRefresh,
    },
    rules: {
      ...reactHooks.configs.recommended.rules,
      'react-refresh/only-export-components': ['warn', { allowConstantExport: true }],
      // Unused vars are a warning, not a hard error, so lint stays advisory over
      // pre-existing code rather than failing CI on stylistic nits.
      'no-unused-vars': ['warn', { argsIgnorePattern: '^_', varsIgnorePattern: '^_' }],
    },
  },
  {
    // Test files + Vitest setup: Node + Vitest globals (test/expect/describe/vi are
    // injected via test.globals; `jest` is aliased in vitest.setup.js).
    files: ['**/*.test.{js,jsx}', 'src/vitest.setup.js'],
    languageOptions: {
      globals: { ...globals.node, ...globals.vitest, jest: 'readonly' },
    },
  },
  {
    // Vite/ESLint config files run in Node (ESM).
    files: ['vite.config.js', 'eslint.config.js'],
    languageOptions: { globals: { ...globals.node } },
  },
];
