import '@testing-library/jest-dom';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
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

// A spread of slots; id 103 is unavailable and must never appear in the picker.
const SLOTS = [
  { id: 101, startTime: `${DATE_STR}T14:00:00Z`, endTime: `${DATE_STR}T18:00:00Z`, available: true },
  { id: 102, startTime: `${DATE_STR}T10:00:00Z`, endTime: `${DATE_STR}T14:00:00Z`, available: true },
  { id: 103, startTime: `${DATE_STR}T09:00:00Z`, endTime: `${DATE_STR}T13:00:00Z`, available: false }, // filtered out
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

// Advance: add-ons (step1) → date & time (step2) → personal details (step3).
async function toDetailsStep() {
  await toAddonsStep(); // Classic Manicure
  await clickContinue(); // step 2 — date & time
  await userEvent.click(await screen.findByRole('button', { name: '15' }));
  await userEvent.click(screen.getByRole('button', { name: '10:00' }));
  await clickContinue(); // step 3 — personal details
}

// Advance: details (step3) → fill name/email → T&C (step4).
async function toTermsStep(name = 'Alice', email = 'alice@example.com') {
  await toDetailsStep();
  await userEvent.type(screen.getByLabelText(/Full Name/), name);
  await userEvent.type(screen.getByLabelText(/Email/), email);
  await clickContinue(); // step 4 — T&C
}

// Advance: T&C (step4) → accept terms → Deposit (step5, the final confirm step with the QR).
async function toDepositStep(name = 'Alice', email = 'alice@example.com') {
  await toTermsStep(name, email);
  await userEvent.click(screen.getByRole('checkbox'));
  await clickContinue(); // step 5 — Deposit
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

  test('details step blocks continue until name and email are filled', async () => {
    renderModal();
    await toDetailsStep(); // → personal details (step 3)

    expect(screen.getByRole('button', { name: 'Continue' })).toBeDisabled();
    await userEvent.type(screen.getByLabelText(/Full Name/), 'Alice');
    expect(screen.getByRole('button', { name: 'Continue' })).toBeDisabled();
    await userEvent.type(screen.getByLabelText(/Email/), 'alice@example.com');
    expect(screen.getByRole('button', { name: 'Continue' })).toBeEnabled();
  });

  test('T&C step blocks continue until the box is checked', async () => {
    renderModal();
    await toTermsStep(); // → T&C (step 4)

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

  test('availability request carries the chosen service quote', async () => {
    renderModal();
    await toAddonsStep(); // selects Classic Manicure → serviceId=classic

    // The quote-aware availability call must include the chosen service (add-ons default to none).
    await waitFor(() => {
      const urls = global.fetch.mock.calls.map((c) => String(c[0]));
      expect(urls.some((u) => u.includes('/api/slots/available?') && u.includes('serviceId=classic')))
        .toBe(true);
    });
  });

  test('no availability request is made before a service is chosen', () => {
    renderModal(); // still on the service step
    expect(global.fetch).not.toHaveBeenCalled();
  });

  test('a picked time is re-gated when the quote changes to one that no longer offers it', async () => {
    // Availability is quote-dependent: adding Tier 2 art removes the 10:00 slot.
    global.fetch = jest.fn((url) => {
      const excludesTen = String(url).includes('nailArtId=tier2');
      const slots = excludesTen
        ? SLOTS.filter((s) => !String(s.startTime).startsWith(`${DATE_STR}T10:00`))
        : SLOTS;
      return Promise.resolve({ ok: true, json: () => Promise.resolve(slots) });
    });

    renderModal();
    await toAddonsStep();     // Classic Manicure (quote classic|none|none)
    await clickContinue();    // step 2 — date & time
    await userEvent.click(await screen.findByRole('button', { name: '15' }));
    await userEvent.click(screen.getByRole('button', { name: '10:00' }));
    expect(screen.getByRole('button', { name: 'Continue' })).toBeEnabled();

    // Change the quote: add Tier 2 art. Availability refetches without 10:00; date/time persist.
    await userEvent.click(screen.getByRole('button', { name: 'Back' }));
    await userEvent.click(screen.getByRole('button', { name: /Tier 2 — Layered/ }));
    await clickContinue();    // back to step 2 (do NOT re-click the day, so the stale time remains)

    // The previously-picked 10:00 is no longer offered for the new quote → cannot continue.
    await waitFor(() => expect(screen.getByRole('button', { name: 'Continue' })).toBeDisabled());
    expect(screen.queryByRole('button', { name: '10:00' })).not.toBeInTheDocument();
  });
});

describe('BookingModal — confirm flow', () => {
  test('confirm resolves the matching slot id and submits the expected payload', async () => {
    const { onConfirm } = renderModal();
    await toDepositStep(); // details filled with Alice / alice@example.com, terms accepted
    fireEvent.load(screen.getByAltText('PayNow S$30 deposit QR code')); // QR loaded → confirm ungated
    await userEvent.click(screen.getByRole('checkbox')); // confirm the deposit was paid
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
    expect(await screen.findByText("You're all set! 🎉")).toBeInTheDocument();
  });

  test('deposit step shows the recap + QR and gates confirm on QR load + the "paid" checkbox', async () => {
    renderModal();
    await toDepositStep();

    // Booking recap (with the deposit-due row) + the fixed-S$30 PayNow QR.
    expect(screen.getByText('Deposit due')).toBeInTheDocument();
    const qr = screen.getByAltText('PayNow S$30 deposit QR code');
    expect(qr).toHaveAttribute('src', '/paynow-qr.png');

    const confirm = screen.getByRole('button', { name: 'Confirm Booking' });
    // Ticking "paid" is not enough while the QR hasn't loaded — no payment method is shown yet.
    await userEvent.click(screen.getByRole('checkbox'));
    expect(confirm).toBeDisabled();
    // Once the QR loads, confirm enables.
    fireEvent.load(qr);
    expect(confirm).toBeEnabled();
  });

  test('deposit step blocks confirmation and shows an error when the QR fails to load', async () => {
    renderModal();
    await toDepositStep();

    fireEvent.error(screen.getByAltText('PayNow S$30 deposit QR code')); // missing/broken /paynow-qr.png

    // The QR is replaced by an error, and ticking "paid" still cannot enable confirm.
    expect(screen.getByRole('alert')).toBeInTheDocument();
    expect(screen.queryByAltText('PayNow S$30 deposit QR code')).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('checkbox'));
    expect(screen.getByRole('button', { name: 'Confirm Booking' })).toBeDisabled();
  });

  test('attaches an inspo image on the details step and includes it in the payload', async () => {
    const { onConfirm } = renderModal();
    await toDetailsStep(); // personal details (step 3), where the uploader lives
    await userEvent.type(screen.getByLabelText(/Full Name/), 'Alice');
    await userEvent.type(screen.getByLabelText(/Email/), 'alice@example.com');

    const file = new File(['hello'], 'inspo.png', { type: 'image/png' });
    await userEvent.upload(document.querySelector('input[type="file"]'), file);

    // Thumbnail (alt = filename) appears once the file has been read.
    expect(await screen.findByAltText('inspo.png')).toBeInTheDocument();

    await clickContinue(); // → T&C (step 4)
    await userEvent.click(screen.getByRole('checkbox'));
    await clickContinue(); // → Deposit (step 5)
    fireEvent.load(screen.getByAltText('PayNow S$30 deposit QR code')); // QR loaded → confirm ungated
    await userEvent.click(screen.getByRole('checkbox')); // confirm the deposit was paid
    await userEvent.click(screen.getByRole('button', { name: 'Confirm Booking' }));

    await waitFor(() => expect(onConfirm).toHaveBeenCalledTimes(1));
    const payload = onConfirm.mock.calls[0][0];
    expect(payload.attachments).toHaveLength(1);
    expect(payload.attachments[0]).toMatchObject({ filename: 'inspo.png', contentType: 'image/png' });
    expect(payload.attachments[0].data).toBe('aGVsbG8='); // base64 of "hello", data-URL prefix stripped
  });

  test('surfaces the error message when onConfirm rejects', async () => {
    const onConfirm = jest.fn().mockRejectedValue(new Error('Slot already taken.'));
    render(<BookingModal onClose={jest.fn()} onConfirm={onConfirm} />);
    await toDepositStep();
    fireEvent.load(screen.getByAltText('PayNow S$30 deposit QR code')); // QR loaded → confirm ungated
    await userEvent.click(screen.getByRole('checkbox')); // confirm the deposit was paid
    await userEvent.click(screen.getByRole('button', { name: 'Confirm Booking' }));

    expect(await screen.findByText('Slot already taken.')).toBeInTheDocument();
    // Deposit was marked paid, so a distinct do-not-pay-again recovery message is shown too.
    expect(screen.getByText(/contact us with your payment receipt/i)).toBeInTheDocument();
    expect(screen.queryByText("You're all set! 🎉")).not.toBeInTheDocument();
  });

  test('prefills name and email from currentUser', async () => {
    renderModal({ currentUser: { name: 'Bob', email: 'bob@example.com' } });
    await toDetailsStep();

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
