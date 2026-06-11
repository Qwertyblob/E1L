import { NAIL_SERVICES, COOL_SERVICES, NAIL_ART, REMOVAL } from './services';

describe('services catalog data integrity', () => {
  test('every nail service has a unique id', () => {
    const ids = NAIL_SERVICES.map((s) => s.id);
    expect(new Set(ids).size).toBe(ids.length);
  });

  test('every nail service has required pricing/duration fields', () => {
    for (const service of NAIL_SERVICES) {
      expect(service.id).toBeTruthy();
      expect(service.name).toBeTruthy();
      expect(typeof service.durationMin).toBe('number');
      expect(service.durationMin).toBeGreaterThan(0);
      expect(typeof service.junior).toBe('number');
      expect(typeof service.senior).toBe('number');
    }
  });

  test('senior price is never below junior price', () => {
    for (const service of [...NAIL_SERVICES, ...COOL_SERVICES]) {
      expect(service.senior).toBeGreaterThanOrEqual(service.junior);
    }
  });

  test('nail art tiers are non-negative and start with a free "none" option', () => {
    expect(NAIL_ART[0]).toMatchObject({ id: 'none', price: 0 });
    for (const art of NAIL_ART) {
      expect(art.price).toBeGreaterThanOrEqual(0);
    }
  });

  test('removal options are non-negative and start with a free "none" option', () => {
    expect(REMOVAL[0]).toMatchObject({ id: 'none', price: 0 });
    for (const removal of REMOVAL) {
      expect(removal.price).toBeGreaterThanOrEqual(0);
    }
  });

  test('nail art and removal ids are unique', () => {
    const artIds = NAIL_ART.map((a) => a.id);
    const removalIds = REMOVAL.map((r) => r.id);
    expect(new Set(artIds).size).toBe(artIds.length);
    expect(new Set(removalIds).size).toBe(removalIds.length);
  });
});
