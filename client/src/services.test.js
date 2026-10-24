import { formatAddOnTime, formatServiceDuration } from './services';

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
