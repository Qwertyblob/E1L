import { appointmentEnded } from './slotBuilderUtils';

export function CancelBookingDialog({ confirmCancelId, cancelDialogRef, isCancelling, onKeep, onConfirm }) {
  if (confirmCancelId == null) return null;

  return (
    <div className="confirm-backdrop">
      <div className="confirm-card" ref={cancelDialogRef} tabIndex={-1} role="dialog" aria-modal="true" aria-label="Cancel booking">
        <h3 className="confirm-title">Cancel this booking?</h3>
        <p className="confirm-text">Are you sure you want to cancel this booking? This can't be undone.</p>
        <div className="confirm-actions">
          <button className="bk-btn bk-btn--ghost" disabled={isCancelling} onClick={onKeep} type="button">
            Keep booking
          </button>
          <button className="bk-btn bk-btn--primary" disabled={isCancelling} onClick={onConfirm} type="button">
            {isCancelling ? 'Cancelling…' : 'Cancel booking'}
          </button>
        </div>
      </div>
    </div>
  );
}

export function BookingDetailModal({ bookingDetail, bookingDetailRef, statusClass, formatDate, formatTimestamp, onClose, onComplete, onCancel }) {
  if (!bookingDetail) return null;

  return (
    <div className="confirm-backdrop" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div className="detail-card" ref={bookingDetailRef} tabIndex={-1} role="dialog" aria-modal="true" aria-label="Booking details">
        <div className="detail-head">
          <h3 className="confirm-title">Booking #{bookingDetail.id}</h3>
          <button className="auth-modal-close" onClick={onClose} type="button" aria-label="Close">&times;</button>
        </div>
        <strong className={`detail-status ${statusClass(bookingDetail.status)}`}>{bookingDetail.status}</strong>
        <dl className="detail-list">
          <div><dt>Name</dt><dd>{bookingDetail.userName || '—'}</dd></div>
          <div><dt>Email</dt><dd>{bookingDetail.customerEmail || '—'}</dd></div>
          <div><dt>Phone</dt><dd>{bookingDetail.phone || '—'}</dd></div>
          <div><dt>Instagram</dt><dd>{bookingDetail.instagram || '—'}</dd></div>
          <div><dt>Service</dt><dd>{bookingDetail.slotTitle}</dd></div>
          <div><dt>When</dt><dd>{formatDate(bookingDetail.slotStartTime)} &rarr; {formatDate(bookingDetail.slotEndTime)}</dd></div>
          <div><dt>Booked on</dt><dd>{formatTimestamp(bookingDetail.createdAt)}</dd></div>
          {bookingDetail.serviceName && <div><dt>Service booked</dt><dd>{bookingDetail.serviceName}</dd></div>}
          {bookingDetail.technician && <div><dt>Technician</dt><dd>{bookingDetail.technician}</dd></div>}
          {bookingDetail.nailArt && <div><dt>Nail art</dt><dd>{bookingDetail.nailArt}</dd></div>}
          {bookingDetail.removal && <div><dt>Removal</dt><dd>{bookingDetail.removal}</dd></div>}
          {bookingDetail.totalPrice != null && <div><dt>Total</dt><dd>S${bookingDetail.totalPrice}</dd></div>}
          <div><dt>Notes</dt><dd>{bookingDetail.notes || '—'}</dd></div>
        </dl>
        {bookingDetail.status === 'BOOKED' && (
          <div className="detail-actions">
            <button
              className="bk-btn bk-btn--ghost"
              onClick={() => onComplete(bookingDetail.id)}
              type="button"
              disabled={!appointmentEnded(bookingDetail)}
              title={appointmentEnded(bookingDetail) ? undefined : 'Can only complete after the appointment has ended'}
            >
              Mark completed
            </button>
            <button className="bk-btn bk-btn--primary" onClick={() => onCancel(bookingDetail.id)} type="button">
              Cancel booking
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
