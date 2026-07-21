import '@testing-library/jest-dom';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { BookingDetailModal, ConfirmDialog } from './BookingDialogs';

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

describe('ConfirmDialog', () => {
  const request = {
    title: 'Delete this slot?',
    message: 'This permanently deletes the slot.',
    confirmLabel: 'Delete slot',
    busyLabel: 'Deleting…',
  };

  test('renders nothing when there is no pending request', () => {
    const { container } = render(<ConfirmDialog request={null} onCancel={jest.fn()} onConfirm={jest.fn()} />);
    expect(container).toBeEmptyDOMElement();
  });

  test('shows the request and wires Keep / confirm', async () => {
    const onConfirm = jest.fn();
    const onCancel = jest.fn();
    render(<ConfirmDialog request={request} isBusy={false} onConfirm={onConfirm} onCancel={onCancel} />);

    expect(screen.getByText('Delete this slot?')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Keep' }));
    expect(onCancel).toHaveBeenCalledTimes(1);
    await userEvent.click(screen.getByRole('button', { name: 'Delete slot' }));
    expect(onConfirm).toHaveBeenCalledTimes(1);
  });

  test('shows the busy label and disables both buttons while running', () => {
    render(<ConfirmDialog request={request} isBusy onConfirm={jest.fn()} onCancel={jest.fn()} />);
    expect(screen.getByRole('button', { name: 'Deleting…' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Keep' })).toBeDisabled();
  });
});
