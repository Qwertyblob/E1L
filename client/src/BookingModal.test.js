import '@testing-library/jest-dom';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import BookingModal from './BookingModal';

// ── fixtures ──────────────────────────────────────────────────────────────────
// Slots are placed in the currently-displayed calendar month so the day cell is
// reachable without month navigation. The component reads the ISO substrings
// directly (date = chars 0-10, time = chars 11-16).
const NOW = new Date();
const Y = NOW.getFullYear();
const M = String(NOW.getMonth() + 1).padStart(2, '0');
const DATE_STR = `${Y}-${M}-15`;

const SLOTS = [
  { id: 101, startTime: `${DATE_STR}T14:00:00Z`, available: true },
  { id: 102, startTime: `${DATE_STR}T10:00:00Z`, available: true },
  { id: 103, startTime: `${DATE_STR}T09:00:00Z`, available: false }, // filtered out
];

function mockSlots(slots = SLOTS) {
  global.fetch = jest.fn(() =>
    Promise.resolve({ ok: true, json: () => Promise.resolve(slots) }),
  );
}

function renderModal(props = {}) {
  const onClose = jest.fn();
  const onConfirm = jest.fn().mockResolvedValue(undefined);
  render(<BookingModal onClose={onClose} onConfirm={onConfirm} {...props} />);
  return { onClose, onConfirm };
}

const clickContinue = () => userEvent.click(screen.getByRole('button', { name: 'Continue' }));

// Advance: service (step0) → add-ons (step1).
// The service name is anchored to avoid matching "Structured Classic Manicure".
async function toAddonsStep(serviceName = /^Classic Manicure/) {
  await userEvent.click(screen.getByRole('button', { name: serviceName }));
  await clickContinue();
}

beforeEach(() => {
  mockSlots();
});

afterEach(() => {
  jest.restoreAllMocks();
});

describe('BookingModal — pricing logic', () => {
  test('total = service base + nail art + removal', async () => {
    renderModal();
    await toAddonsStep(); // Classic Manicure = 58

    const estimate = () => screen.getByText('Estimated total').closest('.bk-estimate');
    expect(within(estimate()).getByText('S$58')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: /Tier 2 — Layered/ })); // +25
    expect(within(estimate()).getByText('S$83')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: /Extensions.*Done by every1luvs/ })); // +10
    expect(within(estimate()).getByText('S$93')).toBeInTheDocument();
  });

  test('estimated appointment duration includes selected add-on durations', async () => {
    renderModal();
    await toAddonsStep(); // Classic Manicure = 45 min

    await userEvent.click(screen.getByRole('button', { name: /Tier 2 — Layered/ })); // +45 min
    await userEvent.click(screen.getByRole('button', { name: /Extensions.*Done by every1luvs/ })); // +30 min
    await clickContinue(); // date & time

    await userEvent.click(await screen.findByRole('button', { name: '15' }));

    expect(screen.getByText('Estimated duration:')).toBeInTheDocument();
    expect(screen.getByText('120 min')).toBeInTheDocument();
  });
});

describe('BookingModal — step gating (canContinue)', () => {
  test('cannot continue from step 0 until a service is selected', async () => {
    renderModal();
    expect(screen.getByRole('button', { name: 'Continue' })).toBeDisabled();

    await userEvent.click(screen.getByRole('button', { name: /^Classic Manicure/ }));
    expect(screen.getByRole('button', { name: 'Continue' })).toBeEnabled();
  });

  test('T&C step blocks continue until the box is checked', async () => {
    renderModal();
    await toAddonsStep();
    await clickContinue(); // → date & time (step 2)

    await userEvent.click(await screen.findByRole('button', { name: '15' }));
    await userEvent.click(screen.getByRole('button', { name: '10:00' }));
    await clickContinue(); // → T&C (step 3)

    expect(screen.getByRole('button', { name: 'Continue' })).toBeDisabled();
    await userEvent.click(screen.getByRole('checkbox'));
    expect(screen.getByRole('button', { name: 'Continue' })).toBeEnabled();
  });
});

describe('BookingModal — availability mapping', () => {
  test('only available slots are offered, sorted, and unavailable days are disabled', async () => {
    renderModal();
    await toAddonsStep();
    await clickContinue(); // step 2 — date & time

    // Day 15 has slots → enabled; an empty day (e.g. 20) → disabled.
    expect(await screen.findByRole('button', { name: '15' })).toBeEnabled();
    expect(screen.getByRole('button', { name: '20' })).toBeDisabled();

    await userEvent.click(screen.getByRole('button', { name: '15' }));

    // 09:00 slot was available:false → excluded; 10:00 & 14:00 shown sorted.
    expect(screen.getByRole('button', { name: '10:00' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '14:00' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '09:00' })).not.toBeInTheDocument();

    // continue still gated until a time is picked
    expect(screen.getByRole('button', { name: 'Continue' })).toBeDisabled();
    await userEvent.click(screen.getByRole('button', { name: '10:00' }));
    expect(screen.getByRole('button', { name: 'Continue' })).toBeEnabled();
  });
});

describe('BookingModal — confirm flow', () => {
  async function driveToConfirm() {
    await toAddonsStep(); // Classic Manicure
    await clickContinue(); // step 2 — date & time
    await userEvent.click(await screen.findByRole('button', { name: '15' }));
    await userEvent.click(screen.getByRole('button', { name: '10:00' }));
    await clickContinue(); // step 3 — T&C
    await userEvent.click(screen.getByRole('checkbox'));
    await clickContinue(); // step 4 — details
  }

  test('confirm resolves the matching slot id and submits the expected payload', async () => {
    const { onConfirm } = renderModal();
    await driveToConfirm();

    await userEvent.type(screen.getByLabelText(/Full Name/), 'Alice');
    await userEvent.type(screen.getByLabelText(/Email/), 'alice@example.com');
    await userEvent.click(screen.getByRole('button', { name: 'Confirm Booking' }));

    await waitFor(() => expect(onConfirm).toHaveBeenCalledTimes(1));
    expect(onConfirm).toHaveBeenCalledWith(
      expect.objectContaining({
        slotId: 102, // the 10:00 slot
        total: 58,
        deposit: 30,
        serviceId: 'classic',
        nailArtId: 'none',
        removalId: 'none',
        time: '10:00',
        date: DATE_STR,
      }),
    );
    expect(await screen.findByText("You're booked!")).toBeInTheDocument();
  });

  test('confirm is gated until name and email are filled', async () => {
    renderModal();
    await driveToConfirm();

    expect(screen.getByRole('button', { name: 'Confirm Booking' })).toBeDisabled();
    await userEvent.type(screen.getByLabelText(/Full Name/), 'Alice');
    expect(screen.getByRole('button', { name: 'Confirm Booking' })).toBeDisabled();
    await userEvent.type(screen.getByLabelText(/Email/), 'alice@example.com');
    expect(screen.getByRole('button', { name: 'Confirm Booking' })).toBeEnabled();
  });

  test('surfaces the error message when onConfirm rejects', async () => {
    const onConfirm = jest.fn().mockRejectedValue(new Error('Slot already taken.'));
    render(<BookingModal onClose={jest.fn()} onConfirm={onConfirm} />);
    await driveToConfirm();

    await userEvent.type(screen.getByLabelText(/Full Name/), 'Alice');
    await userEvent.type(screen.getByLabelText(/Email/), 'alice@example.com');
    await userEvent.click(screen.getByRole('button', { name: 'Confirm Booking' }));

    expect(await screen.findByText('Slot already taken.')).toBeInTheDocument();
    expect(screen.queryByText("You're booked!")).not.toBeInTheDocument();
  });

  test('prefills name and email from currentUser', async () => {
    renderModal({ currentUser: { name: 'Bob', email: 'bob@example.com' } });
    await driveToConfirm();

    expect(screen.getByLabelText(/Full Name/)).toHaveValue('Bob');
    expect(screen.getByLabelText(/Email/)).toHaveValue('bob@example.com');
  });
});

describe('BookingModal — slots fetch failure is non-fatal', () => {
  test('renders with no availability when the fetch fails', async () => {
    global.fetch = jest.fn(() => Promise.reject(new Error('network')));
    renderModal();
    await toAddonsStep();
    await clickContinue(); // step 2 — date & time

    // every day cell is disabled because no slots loaded
    const day15 = await screen.findByRole('button', { name: '15' });
    expect(day15).toBeDisabled();
  });
});
