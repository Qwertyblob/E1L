import { bundledPhotoKey, formatAddOnTime, formatServiceDuration } from './services';

describe('catalog duration labels', () => {
  test('a service duration reads as an approximate length', () => {
    expect(formatServiceDuration(45)).toBe('~45 min');
  });

  test('an add-on shows the extra time it adds', () => {
    expect(formatAddOnTime(35)).toBe('+35 mins');
  });

  test('a no-time add-on (e.g. "none") has no time label', () => {
    expect(formatAddOnTime(0)).toBeNull();
  });
});

describe('bundled service photo lookup', () => {
  const photos = { 'seed-classic': 'classic.jpg', 'seed-express': 'express.jpg' };

  test('uses the service\'s own seed photo id', () => {
    expect(bundledPhotoKey({ id: 'classic', imageId: 'seed-classic' }, photos)).toBe('seed-classic');
  });

  test('an original service without an imageId still finds its photo by service id', () => {
    expect(bundledPhotoKey({ id: 'express' }, photos)).toBe('seed-express');
    expect(bundledPhotoKey({ id: 'express', imageId: '0f3a9c' }, photos)).toBe('seed-express');
  });

  test('a service with no bundled photo gets none (the page uses the generic background)', () => {
    expect(bundledPhotoKey({ id: 'gel-pedicure', imageId: '0f3a9c' }, photos)).toBeNull();
  });
});
