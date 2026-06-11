export function timeToMinutes(time) {
  const [h, m] = time.split(':').map(Number);
  return h * 60 + m;
}

export function minutesToTime(minutes) {
  const h = Math.floor(minutes / 60) % 24;
  const m = minutes % 60;
  return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`;
}

export function getSelectedDates(builder) {
  if (builder.dateMode === 'specific') {
    return [...builder.specificDates].sort();
  }
  if (!builder.recurringFrom || !builder.recurringTo || builder.recurringDays.length === 0) return [];
  const dates = [];
  const cur = new Date(builder.recurringFrom + 'T00:00:00');
  const to = new Date(builder.recurringTo + 'T00:00:00');
  if (cur > to) return [];
  while (cur <= to) {
    if (builder.recurringDays.includes(cur.getDay())) {
      const y = cur.getFullYear();
      const mo = String(cur.getMonth() + 1).padStart(2, '0');
      const d = String(cur.getDate()).padStart(2, '0');
      dates.push(`${y}-${mo}-${d}`);
    }
    cur.setDate(cur.getDate() + 1);
  }
  return dates;
}

function singleTimeSlot(builder) {
  if (!builder.startTime) return [];
  const duration = parseInt(builder.duration, 10);
  if (isNaN(duration) || duration < 1) return [];
  const startMins = timeToMinutes(builder.startTime);
  const endMins = startMins + duration;
  // Reject end >= midnight: an end of 1440 wraps to "00:00", which would land on
  // the same date and read as before the start. Slots must end by 23:59.
  if (endMins >= 24 * 60) return [];
  return [{ start: builder.startTime, end: minutesToTime(endMins) }];
}

function repeatingTimeSlots(builder) {
  if (!builder.firstSlotStart) return [];
  const dur = parseInt(builder.slotDuration, 10);
  const gap = parseInt(builder.slotGap, 10);
  const count = parseInt(builder.slotsPerDay, 10);
  if (isNaN(dur) || dur < 1 || isNaN(gap) || gap < 0 || isNaN(count) || count < 1) return [];
  const slots = [];
  let cur = timeToMinutes(builder.firstSlotStart);
  for (let i = 0; i < count; i++) {
    const end = cur + dur;
    if (end >= 24 * 60) break;
    slots.push({ start: minutesToTime(cur), end: minutesToTime(end) });
    cur = end + gap;
  }
  return slots;
}

export function getTimeSlotsForDay(builder) {
  return builder.slotMode === 'single'
    ? singleTimeSlot(builder)
    : repeatingTimeSlots(builder);
}

export function computePreviewSlots(builder) {
  if (!builder.title.trim()) return [];
  const dates = getSelectedDates(builder);
  if (dates.length === 0) return [];
  const timeSlots = getTimeSlotsForDay(builder);
  if (timeSlots.length === 0) return [];
  const result = [];
  for (const date of dates) {
    for (const { start, end } of timeSlots) {
      result.push({
        title: builder.title.trim(),
        description: builder.description.trim() || null,
        startTime: `${date}T${start}`,
        endTime: `${date}T${end}`,
        capacity: parseInt(builder.capacity, 10) || 1,
      });
    }
  }
  return result;
}

export function getCalendarDays(year, month) {
  const firstDayOfWeek = new Date(year, month, 1).getDay(); // 0=Sun
  const daysInMonth = new Date(year, month + 1, 0).getDate();
  const daysInPrevMonth = new Date(year, month, 0).getDate();
  const days = [];

  for (let i = firstDayOfWeek - 1; i >= 0; i--) {
    days.push({ date: null, day: daysInPrevMonth - i });
  }
  for (let d = 1; d <= daysInMonth; d++) {
    const mo = String(month + 1).padStart(2, '0');
    const dd = String(d).padStart(2, '0');
    days.push({ date: `${year}-${mo}-${dd}`, day: d });
  }
  const trailing = days.length % 7 === 0 ? 0 : 7 - (days.length % 7);
  for (let d = 1; d <= trailing; d++) {
    days.push({ date: null, day: d });
  }
  return days;
}
