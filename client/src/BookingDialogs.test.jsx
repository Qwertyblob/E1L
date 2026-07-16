import '@testing-library/jest-dom';
import { render, screen } from '@testing-library/react';
import { BookingDetailModal } from './BookingDialogs';

// Minimal formatter/prop stubs — the modal only needs them to render text.
const baseProps = {
  bookingDetailRef: { current: null },
  statusClass: () => '',
  formatDate: (s) => s || '—',
  formatTimestamp: (s) => s || '—',
  onClose: jest.fn(),
  onComplete: jest.fn(),
  onCancel: jest.fn(),
};

function makeBooking(slotEndTime) {
  return { id: 1, status: 'BOOKED', slotEndTime, slotStartTime: slotEndTime };
}

describe('BookingDetailModal — completion gate (B4)', () => {
  test('Mark completed is disabled while the appointment is still in the future', () => {
    render(<BookingDetailModal {...baseProps} bookingDetail={makeBooking('2999-01-01T10:00:00Z')} />);
    expect(screen.getByRole('button', { name: 'Mark completed' })).toBeDisabled();
  });

  test('Mark completed is enabled once the appointment has ended', () => {
    render(<BookingDetailModal {...baseProps} bookingDetail={makeBooking('2020-01-01T10:00:00Z')} />);
    expect(screen.getByRole('button', { name: 'Mark completed' })).toBeEnabled();
  });
});
