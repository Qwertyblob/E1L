import { useCallback, useState } from 'react';
import { computePreviewSlots } from './slotBuilderUtils';

function freshSlotBuilder() {
  const now = new Date();
  return {
    title: '',
    description: '',
    capacity: 1,
    dateMode: 'specific',
    specificDates: [],
    recurringDays: [],
    recurringFrom: '',
    recurringTo: '',
    slotMode: 'single',
    startTime: '09:00',
    duration: 60,
    firstSlotStart: '09:00',
    slotDuration: 60,
    slotGap: 10,
    slotsPerDay: 4,
    calendarYear: now.getFullYear(),
    calendarMonth: now.getMonth(),
  };
}

// Owns the admin "create slots" form: the builder draft, its field/calendar
// mutations, and the batch-create request. `apiRequest` is the shared fetch
// wrapper; `onSlotsCreated` refreshes the slot list after a successful create.
export function useSlotBuilder({ apiRequest, onSlotsCreated }) {
  const [slotBuilder, setSlotBuilder] = useState(freshSlotBuilder);
  const [slotFormMessage, setSlotFormMessage] = useState('');
  const [slotFormMessageType, setSlotFormMessageType] = useState('error');
  const [isCreatingSlot, setIsCreatingSlot] = useState(false);

  function updateSlotBuilderField(event) {
    const { name, value } = event.target;
    setSlotBuilder((b) => ({ ...b, [name]: value }));
  }

  function toggleSpecificDate(date) {
    setSlotBuilder((b) => ({
      ...b,
      specificDates: b.specificDates.includes(date)
        ? b.specificDates.filter((d) => d !== date)
        : [...b.specificDates, date].sort(),
    }));
  }

  function removeSpecificDate(date) {
    setSlotBuilder((b) => ({ ...b, specificDates: b.specificDates.filter((d) => d !== date) }));
  }

  function prevMonth() {
    setSlotBuilder((b) => {
      const m = b.calendarMonth === 0 ? 11 : b.calendarMonth - 1;
      const y = b.calendarMonth === 0 ? b.calendarYear - 1 : b.calendarYear;
      return { ...b, calendarMonth: m, calendarYear: y };
    });
  }

  function nextMonth() {
    setSlotBuilder((b) => {
      const m = b.calendarMonth === 11 ? 0 : b.calendarMonth + 1;
      const y = b.calendarMonth === 11 ? b.calendarYear + 1 : b.calendarYear;
      return { ...b, calendarMonth: m, calendarYear: y };
    });
  }

  function toggleRecurringDay(day) {
    setSlotBuilder((b) => ({
      ...b,
      recurringDays: b.recurringDays.includes(day)
        ? b.recurringDays.filter((d) => d !== day)
        : [...b.recurringDays, day],
    }));
  }

  async function handleCreateSlots() {
    const slots = computePreviewSlots(slotBuilder);
    if (slots.length === 0) return;
    setSlotFormMessage('');
    setIsCreatingSlot(true);
    try {
      await apiRequest('/api/admin/slots/batch', {
        method: 'POST',
        body: JSON.stringify({ slots }),
      });
      const count = slots.length;
      setSlotFormMessage(`${count} slot${count !== 1 ? 's' : ''} created.`);
      setSlotFormMessageType('success');
      setSlotBuilder(freshSlotBuilder());
      if (onSlotsCreated) onSlotsCreated();
    } catch (error) {
      setSlotFormMessage(error.message);
      setSlotFormMessageType('error');
    } finally {
      setIsCreatingSlot(false);
    }
  }

  const resetSlotBuilder = useCallback(() => {
    setSlotBuilder(freshSlotBuilder());
    setSlotFormMessage('');
  }, []);

  return {
    slotBuilder,
    setSlotBuilder,
    slotFormMessage,
    slotFormMessageType,
    isCreatingSlot,
    updateSlotBuilderField,
    toggleSpecificDate,
    removeSpecificDate,
    prevMonth,
    nextMonth,
    toggleRecurringDay,
    handleCreateSlots,
    resetSlotBuilder,
  };
}
