import {
  computePreviewSlots,
  getCalendarDays,
  getSelectedDates,
  getTimeSlotsForDay,
  minutesToTime,
  timeToMinutes,
} from './slotBuilderUtils';

// ─── timeToMinutes ───────────────────────────────────────────────────────────

describe('timeToMinutes', () => {
  test('converts midnight', () => expect(timeToMinutes('00:00')).toBe(0));
  test('converts 9am', () => expect(timeToMinutes('09:00')).toBe(540));
  test('converts 23:59', () => expect(timeToMinutes('23:59')).toBe(1439));
  test('converts 1:30', () => expect(timeToMinutes('01:30')).toBe(90));
});

// ─── minutesToTime ───────────────────────────────────────────────────────────

describe('minutesToTime', () => {
  test('converts 0 to 00:00', () => expect(minutesToTime(0)).toBe('00:00'));
  test('converts 540 to 09:00', () => expect(minutesToTime(540)).toBe('09:00'));
  test('converts 90 to 01:30', () => expect(minutesToTime(90)).toBe('01:30'));
  test('converts 1439 to 23:59', () => expect(minutesToTime(1439)).toBe('23:59'));
  test('pads single-digit hours and minutes', () => expect(minutesToTime(61)).toBe('01:01'));
  test('wraps past 24h via modulo', () => expect(minutesToTime(24 * 60 + 60)).toBe('01:00'));
});

// ─── getSelectedDates ────────────────────────────────────────────────────────

describe('getSelectedDates – specific mode', () => {
  test('returns sorted copy of specificDates', () => {
    const builder = {
      dateMode: 'specific',
      specificDates: ['2025-06-20', '2025-06-15', '2025-06-18'],
    };
    expect(getSelectedDates(builder)).toEqual(['2025-06-15', '2025-06-18', '2025-06-20']);
  });

  test('returns empty array when no dates selected', () => {
    const builder = { dateMode: 'specific', specificDates: [] };
    expect(getSelectedDates(builder)).toEqual([]);
  });

  test('does not mutate the original array', () => {
    const original = ['2025-06-20', '2025-06-15'];
    getSelectedDates({ dateMode: 'specific', specificDates: original });
    expect(original).toEqual(['2025-06-20', '2025-06-15']);
  });
});

describe('getSelectedDates – recurring mode', () => {
  const base = {
    dateMode: 'recurring',
    recurringFrom: '2025-06-16', // Monday
    recurringTo: '2025-06-22',   // Sunday
    recurringDays: [1, 3],       // Monday and Wednesday
  };

  test('returns Mon and Wed within the range', () => {
    expect(getSelectedDates(base)).toEqual(['2025-06-16', '2025-06-18']);
  });

  test('returns empty when no days selected', () => {
    expect(getSelectedDates({ ...base, recurringDays: [] })).toEqual([]);
  });

  test('returns empty when from > to', () => {
    expect(getSelectedDates({ ...base, recurringFrom: '2025-06-22', recurringTo: '2025-06-16' })).toEqual([]);
  });

  test('returns empty when from is missing', () => {
    expect(getSelectedDates({ ...base, recurringFrom: '' })).toEqual([]);
  });

  test('returns empty when to is missing', () => {
    expect(getSelectedDates({ ...base, recurringTo: '' })).toEqual([]);
  });

  test('returns single day when from equals to and day matches', () => {
    expect(getSelectedDates({ ...base, recurringFrom: '2025-06-16', recurringTo: '2025-06-16', recurringDays: [1] }))
      .toEqual(['2025-06-16']);
  });

  test('returns empty when from equals to and day does not match', () => {
    expect(getSelectedDates({ ...base, recurringFrom: '2025-06-16', recurringTo: '2025-06-16', recurringDays: [0] }))
      .toEqual([]);
  });

  test('spans multiple weeks', () => {
    const result = getSelectedDates({ ...base, recurringTo: '2025-06-30', recurringDays: [1] });
    expect(result).toEqual(['2025-06-16', '2025-06-23', '2025-06-30']);
  });
});

// ─── getTimeSlotsForDay ──────────────────────────────────────────────────────

describe('getTimeSlotsForDay – single mode', () => {
  const base = { slotMode: 'single', startTime: '09:00', duration: 60 };

  test('returns one slot with correct end time', () => {
    expect(getTimeSlotsForDay(base)).toEqual([{ start: '09:00', end: '10:00' }]);
  });

  test('handles 30-minute duration', () => {
    expect(getTimeSlotsForDay({ ...base, duration: 30 })).toEqual([{ start: '09:00', end: '09:30' }]);
  });

  test('returns empty when end exceeds midnight', () => {
    expect(getTimeSlotsForDay({ ...base, startTime: '23:30', duration: 60 })).toEqual([]);
  });

  test('returns empty when slot ends exactly at midnight', () => {
    // 23:00 + 60 = 24:00, which would wrap to a same-date "00:00" end (before the
    // start), so it is rejected. Slots must end by 23:59.
    expect(getTimeSlotsForDay({ ...base, startTime: '23:00', duration: 60 })).toEqual([]);
  });

  test('returns empty when startTime is empty', () => {
    expect(getTimeSlotsForDay({ ...base, startTime: '' })).toEqual([]);
  });

  test('returns empty when duration is 0', () => {
    expect(getTimeSlotsForDay({ ...base, duration: 0 })).toEqual([]);
  });

  test('returns empty when duration is negative', () => {
    expect(getTimeSlotsForDay({ ...base, duration: -10 })).toEqual([]);
  });
});

describe('getTimeSlotsForDay – multiple mode', () => {
  const base = {
    slotMode: 'multiple',
    firstSlotStart: '09:00',
    slotDuration: 30,
    slotGap: 10,
    slotsPerDay: 3,
  };

  test('returns correct number of slots with gaps', () => {
    expect(getTimeSlotsForDay(base)).toEqual([
      { start: '09:00', end: '09:30' },
      { start: '09:40', end: '10:10' },
      { start: '10:20', end: '10:50' },
    ]);
  });

  test('supports zero gap (back to back)', () => {
    expect(getTimeSlotsForDay({ ...base, slotGap: 0 })).toEqual([
      { start: '09:00', end: '09:30' },
      { start: '09:30', end: '10:00' },
      { start: '10:00', end: '10:30' },
    ]);
  });

  test('stops before midnight overflow', () => {
    // The first slot would end at 24:00 (wrapping to a same-date "00:00"), so the
    // loop stops before emitting it.
    const result = getTimeSlotsForDay({
      ...base,
      firstSlotStart: '23:00',
      slotDuration: 60,
      slotGap: 0,
      slotsPerDay: 5,
    });
    expect(result).toEqual([]);
  });

  test('returns empty when firstSlotStart is missing', () => {
    expect(getTimeSlotsForDay({ ...base, firstSlotStart: '' })).toEqual([]);
  });

  test('returns empty when slotDuration is 0', () => {
    expect(getTimeSlotsForDay({ ...base, slotDuration: 0 })).toEqual([]);
  });

  test('returns empty when slotsPerDay is 0', () => {
    expect(getTimeSlotsForDay({ ...base, slotsPerDay: 0 })).toEqual([]);
  });

  test('returns empty when slotGap is negative', () => {
    expect(getTimeSlotsForDay({ ...base, slotGap: -1 })).toEqual([]);
  });
});

// ─── computePreviewSlots ─────────────────────────────────────────────────────

describe('computePreviewSlots', () => {
  const base = {
    title: 'Morning session',
    description: 'Yoga class',
    capacity: 10,
    dateMode: 'specific',
    specificDates: ['2025-06-15', '2025-06-16'],
    slotMode: 'single',
    startTime: '09:00',
    duration: 60,
  };

  test('returns cross-product of dates and time slots', () => {
    const result = computePreviewSlots(base);
    expect(result).toHaveLength(2);
    expect(result[0]).toMatchObject({ startTime: '2025-06-15T09:00', endTime: '2025-06-15T10:00' });
    expect(result[1]).toMatchObject({ startTime: '2025-06-16T09:00', endTime: '2025-06-16T10:00' });
  });

  test('trims title', () => {
    const result = computePreviewSlots({ ...base, title: '  Morning session  ' });
    expect(result[0].title).toBe('Morning session');
  });

  test('returns empty array when title is blank', () => {
    expect(computePreviewSlots({ ...base, title: '   ' })).toEqual([]);
  });

  test('returns empty array when no dates are selected', () => {
    expect(computePreviewSlots({ ...base, specificDates: [] })).toEqual([]);
  });

  test('returns empty array when time config is invalid', () => {
    expect(computePreviewSlots({ ...base, startTime: '', duration: 60 })).toEqual([]);
  });

  test('sets description to null when blank', () => {
    const result = computePreviewSlots({ ...base, description: '   ' });
    expect(result[0].description).toBeNull();
  });

  test('capacity defaults to 1 when unparseable', () => {
    const result = computePreviewSlots({ ...base, capacity: 'abc' });
    expect(result[0].capacity).toBe(1);
  });

  test('multiple slots per day × multiple dates', () => {
    const result = computePreviewSlots({
      ...base,
      slotMode: 'multiple',
      firstSlotStart: '09:00',
      slotDuration: 60,
      slotGap: 0,
      slotsPerDay: 2,
    });
    expect(result).toHaveLength(4);
    expect(result.map(s => s.startTime)).toEqual([
      '2025-06-15T09:00',
      '2025-06-15T10:00',
      '2025-06-16T09:00',
      '2025-06-16T10:00',
    ]);
  });
});

// ─── getCalendarDays ─────────────────────────────────────────────────────────

describe('getCalendarDays', () => {
  test('grid length is always a multiple of 7', () => {
    for (let month = 0; month < 12; month++) {
      const days = getCalendarDays(2025, month);
      expect(days.length % 7).toBe(0);
    }
  });

  test('number of non-null cells equals days in the month', () => {
    // June 2025 has 30 days
    const days = getCalendarDays(2025, 5);
    const real = days.filter(d => d.date !== null);
    expect(real).toHaveLength(30);
  });

  test('first real cell has date string matching the 1st of the month', () => {
    const days = getCalendarDays(2025, 5); // June 2025
    const first = days.find(d => d.date !== null);
    expect(first.date).toBe('2025-06-01');
  });

  test('last real cell has date string matching the last day of the month', () => {
    const days = getCalendarDays(2025, 1); // February 2025 (28 days)
    const last = [...days].reverse().find(d => d.date !== null);
    expect(last.date).toBe('2025-02-28');
  });

  test('adjacent padding cells have null date', () => {
    const days = getCalendarDays(2025, 5);
    days.slice(0, days.findIndex(d => d.date !== null)).forEach(cell => {
      expect(cell.date).toBeNull();
    });
  });

  test('June 2025 starts on Sunday so no leading padding', () => {
    // June 1 2025 is a Sunday (getDay() = 0)
    const days = getCalendarDays(2025, 5);
    expect(days[0].date).toBe('2025-06-01');
  });

  test('February 2025 first cell is correct padding day', () => {
    // Feb 1 2025 is a Saturday (getDay() = 6) so 6 leading cells
    const days = getCalendarDays(2025, 1);
    const leadingNulls = days.slice(0, days.findIndex(d => d.date !== null));
    expect(leadingNulls).toHaveLength(6);
  });

  test('generates correct date strings for all real cells in a month', () => {
    const days = getCalendarDays(2025, 0); // January 2025
    const real = days.filter(d => d.date !== null);
    expect(real[0].date).toBe('2025-01-01');
    expect(real[30].date).toBe('2025-01-31');
    expect(real).toHaveLength(31);
  });

  test('handles leap year February', () => {
    const days = getCalendarDays(2024, 1); // February 2024 (leap year)
    const real = days.filter(d => d.date !== null);
    expect(real).toHaveLength(29);
    expect(real[28].date).toBe('2024-02-29');
  });
});
