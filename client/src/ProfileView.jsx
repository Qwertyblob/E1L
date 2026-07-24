import { useState } from 'react';
import logoBlack from './assets/images/E1LN_Logo/E1LN_Black_Long_Logo.png';
import {
  appointmentEnded,
  computePreviewSlots,
  getCalendarDays,
  getTimeSlotsForDay,
  minutesToTime,
  timeToMinutes,
} from './slotBuilderUtils';

function formatDateShort(dateStr) {
  const [, mm, dd] = dateStr.split('-').map(Number);
  const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  const weekdays = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
  const dow = new Date(dateStr + 'T00:00:00').getDay();
  return `${weekdays[dow]}, ${months[mm - 1]} ${dd}`;
}

function formatLocalSlotTime(isoLocal) {
  const [datePart, timePart] = isoLocal.split('T');
  const [, mm, dd] = datePart.split('-').map(Number);
  const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  return `${months[mm - 1]} ${dd}, ${timePart}`;
}

const CANCELLATION_NOTICE_MS = 72 * 60 * 60 * 1000;

// Mirrors the server's 72-hour cancellation rule (BookingWindow). Slot times are the
// salon's wall-clock stored as UTC, and the salon runs on Singapore time (fixed UTC+8,
// no DST), so "now" in the same convention is Date.now() + 8h.
function isCancellable(slotStartIso) {
  const nowAsStoredUtc = Date.now() + 8 * 60 * 60 * 1000;
  return new Date(slotStartIso).getTime() - nowAsStoredUtc >= CANCELLATION_NOTICE_MS;
}

// ── Tab: My Bookings ──
function BookingsTab({
  isLoadingMyBookings,
  loadMyBookings,
  myBookingsError,
  bookingActionMessage,
  bookingActionMessageType,
  myBookings,
  bookingsFilter,
  setBookingsFilter,
  formatDate,
  formatTimestamp,
  statusClass,
  setConfirmCancelId,
}) {
  return (
    <div className="dashboard-grid">
      <article className="panel panel--wide">
        <div className="panel-heading">
          <h3>My bookings</h3>
          <button className="primary-button compact" disabled={isLoadingMyBookings} onClick={loadMyBookings} type="button">
            {isLoadingMyBookings ? 'Refreshing' : 'Refresh'}
          </button>
        </div>

        {myBookingsError && <p className="form-message error">{myBookingsError}</p>}
        {bookingActionMessage && (
          <p className={`form-message ${bookingActionMessageType}`}>{bookingActionMessage}</p>
        )}

        <div className="profile-tabs" role="tablist">
          {[
            { id: 'upcoming', label: 'Upcoming', status: 'BOOKED' },
            { id: 'completed', label: 'Completed', status: 'COMPLETED' },
            { id: 'cancelled', label: 'Cancelled', status: 'CANCELLED' },
          ].map((f) => {
            const count = myBookings.filter((b) => b.status === f.status).length;
            return (
              <button
                className={`profile-tab${bookingsFilter === f.id ? ' profile-tab--active' : ''}`}
                key={f.id}
                onClick={() => setBookingsFilter(f.id)}
                type="button"
              >
                {f.label} ({count})
              </button>
            );
          })}
        </div>

        {(() => {
          const statusFor = { upcoming: 'BOOKED', completed: 'COMPLETED', cancelled: 'CANCELLED' };
          const filtered = myBookings.filter((b) => b.status === statusFor[bookingsFilter]);
          const emptyText = {
            upcoming: 'No upcoming bookings.',
            completed: 'No completed bookings yet.',
            cancelled: 'No cancelled bookings.',
          };
          return (
            <div className="user-list">
              {filtered.length === 0 && !myBookingsError && <p className="empty-state">{emptyText[bookingsFilter]}</p>}
              {filtered.map((booking) => (
                <div className="user-row" key={booking.id}>
                  <div className="row-info">
                    <span>{booking.slotTitle}</span>
                    <span className="form-hint">{formatDate(booking.slotStartTime)} &middot; Booked on {formatTimestamp(booking.createdAt)}</span>
                  </div>
                  <strong className={statusClass(booking.status)}>
                    {booking.status}
                  </strong>
                  {booking.status === 'BOOKED' && (
                    isCancellable(booking.slotStartTime) ? (
                      <button className="text-button" onClick={() => setConfirmCancelId(booking.id)} type="button">
                        Cancel
                      </button>
                    ) : (
                      <span className="form-hint">Cancellation closes 72h before</span>
                    )
                  )}
                </div>
              ))}
            </div>
          );
        })()}
      </article>
    </div>
  );
}

// ── Tab: Rewards ──
function RewardsTab() {
  return (
    <div className="dashboard-grid">
      <article className="panel panel--wide">
        <div className="panel-heading">
          <h3>Rewards</h3>
        </div>
        <p className="empty-state">Rewards are coming soon. Check back later to track your perks. ✦</p>
      </article>
    </div>
  );
}

// ── Tab: Account ──
function AccountTab({
  user,
  refreshProfile,
  signOut,
  signOutMessage,
  handleChangePassword,
  updateChangePasswordField,
  changePasswordForm,
  changePasswordMessage,
  changePasswordMessageType,
  isChangingPassword,
  handleDeleteAccount,
  deleteAccountMessage,
  isDeletingAccount,
}) {
  // Two-step delete: the first click reveals the confirmation; only the explicit
  // "Yes, delete my account" actually calls the irreversible endpoint.
  const [confirmingDelete, setConfirmingDelete] = useState(false);
  return (
    <div className="dashboard-grid">
      <article className="panel">
        <div className="panel-heading">
          <h3>Profile</h3>
          <button className="text-button" onClick={refreshProfile} type="button">
            Refresh
          </button>
        </div>
        <dl className="profile-list">
          <div>
            <dt>Name</dt>
            <dd>{user.name}</dd>
          </div>
          <div>
            <dt>Email</dt>
            <dd>{user.email}</dd>
          </div>
          <div>
            <dt>Role</dt>
            <dd>{user.role}</dd>
          </div>
        </dl>
        <div style={{ marginTop: '20px' }}>
          <button className="outline-button outline-button--sm" onClick={signOut} type="button">Sign out</button>
          {signOutMessage && (
            <p className="form-message error" role="alert">{signOutMessage}</p>
          )}
        </div>
      </article>

      <article className="panel panel--wide">
        <div className="panel-heading">
          <h3>Change password</h3>
        </div>
        <form className="change-password-form" onSubmit={handleChangePassword}>
          <label>
            <span>Current password</span>
            <input
              autoComplete="current-password"
              name="currentPassword"
              onChange={updateChangePasswordField}
              placeholder="Your current password"
              required
              type="password"
              value={changePasswordForm.currentPassword}
            />
          </label>
          <label>
            <span>New password</span>
            <input
              autoComplete="new-password"
              minLength="8"
              name="newPassword"
              onChange={updateChangePasswordField}
              placeholder="At least 8 characters with a letter and digit"
              required
              type="password"
              value={changePasswordForm.newPassword}
            />
          </label>
          {changePasswordMessage && (
            <p className={`form-message ${changePasswordMessageType}`}>{changePasswordMessage}</p>
          )}
          <div>
            <button className="primary-button" disabled={isChangingPassword} type="submit">
              {isChangingPassword ? 'Please wait' : 'Change password'}
            </button>
          </div>
        </form>
      </article>

      <article className="panel panel--wide danger-zone">
        <div className="panel-heading">
          <h3>Delete account</h3>
        </div>
        <p className="form-hint">
          Permanently delete your account and all of your bookings. This cannot be undone.
        </p>
        {deleteAccountMessage && <p className="form-message error">{deleteAccountMessage}</p>}
        {confirmingDelete ? (
          <div className="danger-zone-confirm">
            <p className="form-message error">Are you sure? This permanently deletes your account and bookings.</p>
            <div className="danger-zone-actions">
              <button
                className="danger-button"
                disabled={isDeletingAccount}
                onClick={handleDeleteAccount}
                type="button"
              >
                {isDeletingAccount ? 'Deleting…' : 'Yes, delete my account'}
              </button>
              <button
                className="outline-button outline-button--sm"
                disabled={isDeletingAccount}
                onClick={() => setConfirmingDelete(false)}
                type="button"
              >
                Cancel
              </button>
            </div>
          </div>
        ) : (
          <div>
            <button className="danger-button" onClick={() => setConfirmingDelete(true)} type="button">
              Delete account
            </button>
          </div>
        )}
      </article>
    </div>
  );
}

// ── Schedule panel: all bookings (list + calendar) ──
function ScheduleBookingsPanel({
  scheduleView,
  setScheduleView,
  isLoadingAdminBookings,
  loadAdminBookings,
  adminBookingsError,
  scheduleFilter,
  setScheduleFilter,
  adminBookings,
  scheduleCal,
  setScheduleCal,
  bookingsByDate,
  scheduleSelectedBookings,
  setBookingDetail,
  handleAdminCompleteBooking,
  handleAdminCancelBooking,
  handleAdminConfirmBooking,
  handleResendConfirmation,
  formatDate,
  statusClass,
}) {
  return (
    <article className="panel panel--wide">
      <div className="panel-heading">
        <h3>Schedule &middot; All bookings</h3>
        <div className="schedule-controls">
          <div className="schedule-toggle">
            <button className={scheduleView === 'list' ? 'active' : ''} onClick={() => setScheduleView('list')} type="button">List</button>
            <button className={scheduleView === 'calendar' ? 'active' : ''} onClick={() => setScheduleView('calendar')} type="button">Calendar</button>
          </div>
          <button className="primary-button compact" disabled={isLoadingAdminBookings} onClick={loadAdminBookings} type="button">
            {isLoadingAdminBookings ? 'Loading' : 'Refresh'}
          </button>
        </div>
      </div>
      {adminBookingsError && <p className="form-message error">{adminBookingsError}</p>}

      {scheduleView === 'list' && (
        <div className="profile-tabs" role="tablist">
          {[
            { id: 'pending', label: 'Pending', match: (b) => b.status === 'BOOKED' && !b.confirmedAt },
            { id: 'upcoming', label: 'Upcoming', match: (b) => b.status === 'BOOKED' && b.confirmedAt },
            { id: 'completed', label: 'Completed', match: (b) => b.status === 'COMPLETED' },
            { id: 'cancelled', label: 'Cancelled', match: (b) => b.status === 'CANCELLED' },
          ].map((f) => {
            const count = adminBookings.filter(f.match).length;
            return (
              <button
                className={`profile-tab${scheduleFilter === f.id ? ' profile-tab--active' : ''}`}
                key={f.id}
                onClick={() => setScheduleFilter(f.id)}
                type="button"
              >
                {f.label} ({count})
              </button>
            );
          })}
        </div>
      )}

      {scheduleView === 'calendar' && (
        <div className="schedule-calendar">
          <div className="bk-cal-header">
            <button className="bk-cal-nav" onClick={() => setScheduleCal((c) => ({ ...c, month: c.month === 0 ? 11 : c.month - 1, year: c.month === 0 ? c.year - 1 : c.year }))} type="button" aria-label="Previous month">&#8249;</button>
            <span className="bk-cal-title">{['January','February','March','April','May','June','July','August','September','October','November','December'][scheduleCal.month]} {scheduleCal.year}</span>
            <button className="bk-cal-nav" onClick={() => setScheduleCal((c) => ({ ...c, month: c.month === 11 ? 0 : c.month + 1, year: c.month === 11 ? c.year + 1 : c.year }))} type="button" aria-label="Next month">&#8250;</button>
          </div>
          <div className="bk-cal-grid">
            {['Su', 'Mo', 'Tu', 'We', 'Th', 'Fr', 'Sa'].map((d) => <span className="bk-cal-dow" key={d}>{d}</span>)}
            {getCalendarDays(scheduleCal.year, scheduleCal.month).map((cell, i) => {
              if (!cell.date) return <span className="bk-cal-cell bk-cal-cell--empty" key={i} />;
              const count = (bookingsByDate[cell.date] || []).length;
              return (
                <button
                  className={`bk-cal-cell schedule-cal-cell${scheduleCal.selected === cell.date ? ' bk-cal-cell--selected' : ''}${count ? ' schedule-cal-cell--has' : ''}`}
                  disabled={count === 0}
                  key={i}
                  onClick={() => setScheduleCal((c) => ({ ...c, selected: c.selected === cell.date ? null : cell.date }))}
                  type="button"
                >
                  {cell.day}
                  {count > 0 && <span className="schedule-cal-count">{count}</span>}
                </button>
              );
            })}
          </div>
          {scheduleCal.selected && (
            <p className="schedule-cal-label">Showing {scheduleSelectedBookings.length} booking{scheduleSelectedBookings.length !== 1 ? 's' : ''} on {formatDateShort(scheduleCal.selected)}{' '}
              <button className="text-button schedule-clear" onClick={() => setScheduleCal((c) => ({ ...c, selected: null }))} type="button">Show all</button>
            </p>
          )}
        </div>
      )}

      <div className="user-list">
        {scheduleSelectedBookings.length === 0 && !adminBookingsError && (
          <p className="empty-state">
            {scheduleView === 'calendar'
              ? 'No upcoming bookings.'
              : `No ${scheduleFilter} bookings.`}
          </p>
        )}
        {scheduleSelectedBookings.map((booking) => {
          const pending = booking.status === 'BOOKED' && !booking.confirmedAt;
          const displayStatus = pending ? 'PENDING' : booking.status;
          return (
          <div
            className="user-row user-row--clickable"
            key={booking.id}
            onClick={() => setBookingDetail(booking)}
            role="button"
            tabIndex={0}
            onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); setBookingDetail(booking); } }}
          >
            <div className="row-info">
              <span>#{booking.id} &middot; {booking.userName}</span>
              <span className="form-hint">
                {booking.slotTitle} &middot; {formatDate(booking.slotStartTime)}
              </span>
            </div>
            <strong className={statusClass(displayStatus)}>
              {displayStatus}
            </strong>
            {booking.status === 'BOOKED' && (
              <div className="schedule-row-actions">
                {pending ? (
                  <>
                    <button className="text-button" onClick={(e) => { e.stopPropagation(); handleAdminConfirmBooking(booking.id); }} type="button">
                      Confirm
                    </button>
                    <button className="text-button" onClick={(e) => { e.stopPropagation(); handleAdminCancelBooking(booking.id); }} type="button">
                      Cancel
                    </button>
                  </>
                ) : (
                  <>
                    <button
                      className="text-button"
                      onClick={(e) => { e.stopPropagation(); handleAdminCompleteBooking(booking.id); }}
                      type="button"
                      disabled={!appointmentEnded(booking)}
                      title={appointmentEnded(booking) ? undefined : 'Can only complete after the appointment has ended'}
                    >
                      Complete
                    </button>
                    <button className="text-button" onClick={(e) => { e.stopPropagation(); handleResendConfirmation(booking.id); }} type="button">
                      Resend
                    </button>
                    <button className="text-button" onClick={(e) => { e.stopPropagation(); handleAdminCancelBooking(booking.id); }} type="button">
                      Cancel
                    </button>
                  </>
                )}
              </div>
            )}
          </div>
          );
        })}
      </div>
    </article>
  );
}

// ── Schedule panel: users ──
function UsersPanel({ loadAdminUsers, isLoadingAdmin, adminError, adminUsers, adminUsersMeta = { number: 0, totalPages: 0, totalElements: 0 } }) {
  return (
    <article className="panel">
      <div className="panel-heading">
        <h3>Users</h3>
        <button className="primary-button compact" onClick={() => loadAdminUsers(0)} type="button">
          {isLoadingAdmin ? 'Loading' : 'Load users'}
        </button>
      </div>

      {adminError && <p className="form-message error">{adminError}</p>}

      <div className="user-list">
        {adminUsers.length === 0 && !adminError && (
          <p className="empty-state">No users loaded.</p>
        )}
        {adminUsers.map((adminUser) => (
          <div className="user-row" key={adminUser.id}>
            <span>{adminUser.name}</span>
            <strong>{adminUser.role}</strong>
          </div>
        ))}
      </div>

      {adminUsersMeta.totalPages > 1 && (
        <div className="pager">
          <button
            className="text-button"
            type="button"
            disabled={isLoadingAdmin || adminUsersMeta.number <= 0}
            onClick={() => loadAdminUsers(adminUsersMeta.number - 1)}
          >
            Prev
          </button>
          <span className="form-hint">
            Page {adminUsersMeta.number + 1} of {adminUsersMeta.totalPages} &middot; {adminUsersMeta.totalElements} total
          </span>
          <button
            className="text-button"
            type="button"
            disabled={isLoadingAdmin || adminUsersMeta.number >= adminUsersMeta.totalPages - 1}
            onClick={() => loadAdminUsers(adminUsersMeta.number + 1)}
          >
            Next
          </button>
        </div>
      )}
    </article>
  );
}

// ── Slot builder: date selection (specific calendar / weekly recurring) ──
function SlotDateSection({
  slotBuilder,
  setSlotBuilder,
  updateSlotBuilderField,
  prevMonth,
  nextMonth,
  toggleSpecificDate,
  removeSpecificDate,
  toggleRecurringDay,
}) {
  return (
    <section className="slot-builder-section">
      <p className="slot-section-label">Dates</p>
      <div className="segment-control">
        <button
          className={slotBuilder.dateMode === 'specific' ? 'active' : ''}
          onClick={() => setSlotBuilder((b) => ({ ...b, dateMode: 'specific' }))}
          type="button"
        >
          Specific dates
        </button>
        <button
          className={slotBuilder.dateMode === 'recurring' ? 'active' : ''}
          onClick={() => setSlotBuilder((b) => ({ ...b, dateMode: 'recurring' }))}
          type="button"
        >
          Weekly recurring
        </button>
      </div>

      {slotBuilder.dateMode === 'specific' && (() => {
        const calDays = getCalendarDays(slotBuilder.calendarYear, slotBuilder.calendarMonth);
        const monthNames = ['January', 'February', 'March', 'April', 'May', 'June',
          'July', 'August', 'September', 'October', 'November', 'December'];
        const now = new Date();
        const today = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
        return (
          <div className="specific-dates">
            <div className="calendar-picker">
              <div className="calendar-header">
                <button className="calendar-nav" onClick={prevMonth} type="button">&#8249;</button>
                <span className="calendar-title">
                  {monthNames[slotBuilder.calendarMonth]} {slotBuilder.calendarYear}
                </span>
                <button className="calendar-nav" onClick={nextMonth} type="button">&#8250;</button>
              </div>
              <div className="calendar-grid">
                {['Su', 'Mo', 'Tu', 'We', 'Th', 'Fr', 'Sa'].map((d) => (
                  <span className="calendar-dow" key={d}>{d}</span>
                ))}
                {calDays.map((cell, i) => (
                  <button
                    className={[
                      'calendar-day',
                      !cell.date ? 'calendar-day--adjacent' : '',
                      cell.date === today ? 'calendar-day--today' : '',
                      cell.date && slotBuilder.specificDates.includes(cell.date) ? 'calendar-day--selected' : '',
                    ].join(' ').trim()}
                    disabled={!cell.date}
                    key={i}
                    onClick={() => cell.date && toggleSpecificDate(cell.date)}
                    type="button"
                  >
                    {cell.day}
                  </button>
                ))}
              </div>
            </div>
            {slotBuilder.specificDates.length > 0 ? (
              <div className="date-chips">
                {slotBuilder.specificDates.map((d) => (
                  <span className="date-chip" key={d}>
                    {formatDateShort(d)}
                    <button onClick={() => removeSpecificDate(d)} type="button">&times;</button>
                  </span>
                ))}
              </div>
            ) : (
              <p className="form-hint">Click days on the calendar to select dates.</p>
            )}
          </div>
        );
      })()}

      {slotBuilder.dateMode === 'recurring' && (
        <div className="recurring-dates">
          <div className="day-picker">
            {['Su', 'Mo', 'Tu', 'We', 'Th', 'Fr', 'Sa'].map((label, i) => (
              <button
                className={slotBuilder.recurringDays.includes(i) ? 'active' : ''}
                key={i}
                onClick={() => toggleRecurringDay(i)}
                type="button"
              >
                {label}
              </button>
            ))}
          </div>
          <div className="date-range-row">
            <label>
              <span>From</span>
              <input
                name="recurringFrom"
                onChange={updateSlotBuilderField}
                type="date"
                value={slotBuilder.recurringFrom}
              />
            </label>
            <label>
              <span>To</span>
              <input
                name="recurringTo"
                onChange={updateSlotBuilderField}
                type="date"
                value={slotBuilder.recurringTo}
              />
            </label>
          </div>
          {slotBuilder.recurringDays.length === 0 && (
            <p className="form-hint">Select at least one day of the week.</p>
          )}
        </div>
      )}
    </section>
  );
}

// ── Slot builder: time-per-day configuration (single / multiple) ──
function SlotTimeSection({ slotBuilder, setSlotBuilder, updateSlotBuilderField }) {
  return (
    <section className="slot-builder-section">
      <p className="slot-section-label">Time per day</p>
      <div className="segment-control">
        <button
          className={slotBuilder.slotMode === 'single' ? 'active' : ''}
          onClick={() => setSlotBuilder((b) => ({ ...b, slotMode: 'single' }))}
          type="button"
        >
          Single slot
        </button>
        <button
          className={slotBuilder.slotMode === 'multiple' ? 'active' : ''}
          onClick={() => setSlotBuilder((b) => ({ ...b, slotMode: 'multiple' }))}
          type="button"
        >
          Multiple slots
        </button>
      </div>

      {slotBuilder.slotMode === 'single' && (
        <div className="time-row">
          <label>
            <span>Start time</span>
            <input
              name="startTime"
              onChange={updateSlotBuilderField}
              type="time"
              value={slotBuilder.startTime}
            />
          </label>
          <label>
            <span>Duration (minutes)</span>
            <input
              min="1"
              name="duration"
              onChange={updateSlotBuilderField}
              type="number"
              value={slotBuilder.duration}
            />
          </label>
          {slotBuilder.startTime && (() => {
            const dur = parseInt(slotBuilder.duration, 10);
            if (!dur || dur < 1) return null;
            const endMins = timeToMinutes(slotBuilder.startTime) + dur;
            return endMins >= 24 * 60
              ? <p className="time-calc-hint time-calc-warn">Extends past midnight</p>
              : <p className="time-calc-hint">Ends at {minutesToTime(endMins)}</p>;
          })()}
        </div>
      )}

      {slotBuilder.slotMode === 'multiple' && (
        <div className="time-row">
          <label>
            <span>First slot starts</span>
            <input
              name="firstSlotStart"
              onChange={updateSlotBuilderField}
              type="time"
              value={slotBuilder.firstSlotStart}
            />
          </label>
          <label>
            <span>Slot duration (min)</span>
            <input
              min="1"
              name="slotDuration"
              onChange={updateSlotBuilderField}
              type="number"
              value={slotBuilder.slotDuration}
            />
          </label>
          <label>
            <span>Gap between slots (min)</span>
            <input
              min="0"
              name="slotGap"
              onChange={updateSlotBuilderField}
              type="number"
              value={slotBuilder.slotGap}
            />
          </label>
          <label>
            <span>Slots per day</span>
            <input
              max="48"
              min="1"
              name="slotsPerDay"
              onChange={updateSlotBuilderField}
              type="number"
              value={slotBuilder.slotsPerDay}
            />
          </label>
          {(() => {
            const daySlots = getTimeSlotsForDay(slotBuilder);
            return daySlots.length > 0 ? (
              <p className="time-calc-hint">
                {daySlots.length} slot{daySlots.length !== 1 ? 's' : ''} &middot; Last ends at {daySlots[daySlots.length - 1].end}
              </p>
            ) : null;
          })()}
        </div>
      )}
    </section>
  );
}

// ── Slot builder: preview + create action ──
function SlotPreviewSection({ slotBuilder, slotFormMessage, slotFormMessageType, isCreatingSlot, handleCreateSlots }) {
  const previewSlots = computePreviewSlots(slotBuilder);
  return (
    <section className="slot-builder-section">
      <div className="preview-heading">
        <p className="slot-section-label">Preview</p>
        {previewSlots.length > 0 && (
          <span className="preview-count">{previewSlots.length} slot{previewSlots.length !== 1 ? 's' : ''}</span>
        )}
      </div>
      {previewSlots.length > 0 ? (
        <div className="preview-list">
          {previewSlots.map((slot) => (
            <div className="preview-row" key={`${slot.startTime}-${slot.endTime}`}>
              <span>{slot.title}</span>
              <span className="form-hint">
                {formatLocalSlotTime(slot.startTime)} &rarr; {formatLocalSlotTime(slot.endTime)}
              </span>
            </div>
          ))}
        </div>
      ) : (
        <p className="empty-state">
          {!slotBuilder.title.trim()
            ? 'Enter a title to start.'
            : 'Configure dates and times above to preview slots.'}
        </p>
      )}
      {slotFormMessage && (
        <p className={`form-message ${slotFormMessageType}`}>{slotFormMessage}</p>
      )}
      <div>
        <button
          className="primary-button"
          disabled={previewSlots.length === 0 || isCreatingSlot}
          onClick={handleCreateSlots}
          type="button"
        >
          {isCreatingSlot
            ? 'Creating…'
            : previewSlots.length > 0
              ? `Create ${previewSlots.length} slot${previewSlots.length !== 1 ? 's' : ''}`
              : 'Create slots'}
        </button>
      </div>
    </section>
  );
}

// ── Schedule panel: create slots ──
function CreateSlotsPanel({
  slotBuilder,
  setSlotBuilder,
  updateSlotBuilderField,
  prevMonth,
  nextMonth,
  toggleSpecificDate,
  removeSpecificDate,
  toggleRecurringDay,
  handleCreateSlots,
  slotFormMessage,
  slotFormMessageType,
  isCreatingSlot,
}) {
  return (
    <article className="panel panel--wide">
      <div className="panel-heading">
        <h3>Create slots</h3>
      </div>

      <div className="slot-builder">
        <section className="slot-builder-section">
          <p className="slot-section-label">Slot info</p>
          <div className="slot-info-fields">
            <label>
              <span>Title</span>
              <input
                name="title"
                onChange={updateSlotBuilderField}
                placeholder="e.g. Morning session"
                type="text"
                value={slotBuilder.title}
              />
            </label>
            <label>
              <span>Description (optional)</span>
              <input
                name="description"
                onChange={updateSlotBuilderField}
                type="text"
                value={slotBuilder.description}
              />
            </label>
            <label>
              <span>Capacity</span>
              <input
                min="1"
                name="capacity"
                onChange={updateSlotBuilderField}
                type="number"
                value={slotBuilder.capacity}
              />
            </label>
          </div>
        </section>

        <SlotDateSection
          slotBuilder={slotBuilder}
          setSlotBuilder={setSlotBuilder}
          updateSlotBuilderField={updateSlotBuilderField}
          prevMonth={prevMonth}
          nextMonth={nextMonth}
          toggleSpecificDate={toggleSpecificDate}
          removeSpecificDate={removeSpecificDate}
          toggleRecurringDay={toggleRecurringDay}
        />

        <SlotTimeSection
          slotBuilder={slotBuilder}
          setSlotBuilder={setSlotBuilder}
          updateSlotBuilderField={updateSlotBuilderField}
        />

        <SlotPreviewSection
          slotBuilder={slotBuilder}
          slotFormMessage={slotFormMessage}
          slotFormMessageType={slotFormMessageType}
          isCreatingSlot={isCreatingSlot}
          handleCreateSlots={handleCreateSlots}
        />
      </div>
    </article>
  );
}

// ── Schedule panel: manage existing slots ──
const SLOT_MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];

const SLOT_FILTERS = [
  { id: 'upcoming', label: 'Upcoming' },
  { id: 'past', label: 'Past' },
  { id: 'all', label: 'All' },
  { id: 'archived', label: 'Archived' },
];

const slotDay = (slot) => (slot.startTime ? String(slot.startTime).slice(0, 10) : '');

// Group every slot by its start date so the calendar can show a per-day count.
function groupSlotsByDate(slots) {
  return slots.reduce((acc, slot) => {
    const day = slotDay(slot);
    if (day) (acc[day] = acc[day] || []).push(slot);
    return acc;
  }, {});
}

// Count shown on each list-view filter tab. Archived stays null until its data has
// actually been fetched; once fetched the count is the server-side total, not just
// the loaded pages.
function slotFilterCount(filterId, adminSlots, isUpcoming, archivedSlotsLoaded, archivedSlotsTotal) {
  if (filterId === 'archived') return archivedSlotsLoaded ? archivedSlotsTotal : null;
  if (filterId === 'all') return adminSlots.length;
  if (filterId === 'upcoming') return adminSlots.filter(isUpcoming).length;
  return adminSlots.filter((s) => !isUpcoming(s)).length;
}

// Which slots the body renders: the calendar view shows the selected day (or every
// slot when no day is picked); the list view shows the active filter's slice, with
// the Archived tab drawing from its own on-demand dataset.
function selectVisibleSlots({ slotView, slotFilter, slotCal, adminSlots, archivedSlots, slotsByDate, isUpcoming }) {
  if (slotView === 'calendar') {
    return slotCal.selected ? (slotsByDate[slotCal.selected] || []) : adminSlots;
  }
  if (slotFilter === 'archived') return archivedSlots;
  if (slotFilter === 'upcoming') return adminSlots.filter(isUpcoming);
  if (slotFilter === 'past') return adminSlots.filter((slot) => !isUpcoming(slot));
  return adminSlots;
}

function SlotFilterTabs({
  slotFilter, setSlotFilter, adminSlots, isUpcoming, archivedSlotsLoaded, archivedSlotsTotal, loadArchivedSlots,
}) {
  return (
    <div className="profile-tabs" role="tablist">
      {SLOT_FILTERS.map((f) => {
        const count = slotFilterCount(f.id, adminSlots, isUpcoming, archivedSlotsLoaded, archivedSlotsTotal);
        return (
          <button
            className={`profile-tab${slotFilter === f.id ? ' profile-tab--active' : ''}`}
            key={f.id}
            onClick={() => {
              setSlotFilter(f.id);
              // The archive can be large, so it's only fetched when this tab is opened.
              if (f.id === 'archived' && !archivedSlotsLoaded) loadArchivedSlots();
            }}
            type="button"
          >
            {f.label}{count != null ? ` (${count})` : ''}
          </button>
        );
      })}
    </div>
  );
}

function SlotCalendarView({ slotCal, setSlotCal, slotsByDate, visibleCount }) {
  return (
    <div className="schedule-calendar">
      <div className="bk-cal-header">
        <button className="bk-cal-nav" onClick={() => setSlotCal((c) => ({ ...c, month: c.month === 0 ? 11 : c.month - 1, year: c.month === 0 ? c.year - 1 : c.year }))} type="button" aria-label="Previous month">&#8249;</button>
        <span className="bk-cal-title">{SLOT_MONTHS[slotCal.month]} {slotCal.year}</span>
        <button className="bk-cal-nav" onClick={() => setSlotCal((c) => ({ ...c, month: c.month === 11 ? 0 : c.month + 1, year: c.month === 11 ? c.year + 1 : c.year }))} type="button" aria-label="Next month">&#8250;</button>
      </div>
      <div className="bk-cal-grid">
        {['Su', 'Mo', 'Tu', 'We', 'Th', 'Fr', 'Sa'].map((d) => <span className="bk-cal-dow" key={d}>{d}</span>)}
        {getCalendarDays(slotCal.year, slotCal.month).map((cell, i) => {
          if (!cell.date) return <span className="bk-cal-cell bk-cal-cell--empty" key={i} />;
          const count = (slotsByDate[cell.date] || []).length;
          return (
            <button
              className={`bk-cal-cell schedule-cal-cell${slotCal.selected === cell.date ? ' bk-cal-cell--selected' : ''}${count ? ' schedule-cal-cell--has' : ''}`}
              disabled={count === 0}
              key={i}
              onClick={() => setSlotCal((c) => ({ ...c, selected: c.selected === cell.date ? null : cell.date }))}
              type="button"
            >
              {cell.day}
              {count > 0 && <span className="schedule-cal-count">{count}</span>}
            </button>
          );
        })}
      </div>
      {slotCal.selected && (
        <p className="schedule-cal-label">Showing {visibleCount} slot{visibleCount !== 1 ? 's' : ''} on {formatDateShort(slotCal.selected)}{' '}
          <button className="text-button schedule-clear" onClick={() => setSlotCal((c) => ({ ...c, selected: null }))} type="button">Show all</button>
        </p>
      )}
    </div>
  );
}

function SlotRow({ slot, formatDate, formatTimestamp, handleDeleteSlot }) {
  return (
    <div className={`user-row${slot.archived ? ' user-row--archived' : ''}`}>
      <div className="row-info">
        <span>
          {slot.title}
          {slot.archived && <span className="archived-pill">Archived</span>}
        </span>
        <span className="form-hint">
          {formatDate(slot.startTime)} &rarr; {formatDate(slot.endTime)} &middot; {slot.bookedCount}/{slot.capacity} booked &middot; Created {formatTimestamp(slot.createdAt)}
        </span>
      </div>
      <button className="text-button" onClick={() => handleDeleteSlot(slot.id)} type="button">
        Delete
      </button>
    </div>
  );
}

function SlotList({
  slotView, slotFilter, visibleSlots, adminSlotsError, archivedSlotsError,
  isLoadingArchivedSlots, archivedSlots, archivedSlotsLoaded, archivedSlotsPage, archivedSlotsTotal,
  loadArchivedSlots, formatDate, formatTimestamp, handleDeleteSlot,
}) {
  const showArchivedLoading = slotView === 'list' && slotFilter === 'archived' && isLoadingArchivedSlots;
  const showEmptyState = visibleSlots.length === 0 && !adminSlotsError
    && !(slotFilter === 'archived' && (isLoadingArchivedSlots || archivedSlotsError));
  const showLoadMore = slotView === 'list' && slotFilter === 'archived' && archivedSlotsLoaded
    && archivedSlots.length < archivedSlotsTotal;

  return (
    <div className="user-list">
      {showArchivedLoading && <p className="empty-state">Loading archived slots&hellip;</p>}
      {showEmptyState && (
        <p className="empty-state">{slotView === 'calendar' ? 'No slots.' : `No ${slotFilter} slots.`}</p>
      )}
      {visibleSlots.map((slot) => (
        <SlotRow
          key={slot.id}
          slot={slot}
          formatDate={formatDate}
          formatTimestamp={formatTimestamp}
          handleDeleteSlot={handleDeleteSlot}
        />
      ))}
      {showLoadMore && (
        <button
          className="primary-button compact archived-load-more"
          disabled={isLoadingArchivedSlots}
          onClick={() => loadArchivedSlots(archivedSlotsPage + 1)}
          type="button"
        >
          {isLoadingArchivedSlots
            ? 'Loading'
            : `Load more (${archivedSlots.length} of ${archivedSlotsTotal})`}
        </button>
      )}
    </div>
  );
}

function ManageSlotsPanel({
  isLoadingAdminSlots,
  loadAdminSlots,
  adminSlotsError,
  adminSlots,
  isLoadingArchivedSlots,
  loadArchivedSlots,
  archivedSlotsError,
  archivedSlots,
  archivedSlotsLoaded,
  archivedSlotsPage,
  archivedSlotsTotal,
  formatDate,
  formatTimestamp,
  handleDeleteSlot,
}) {
  const today = new Date();
  const [slotView, setSlotView] = useState('list');
  const [slotFilter, setSlotFilter] = useState('all');
  const [slotCal, setSlotCal] = useState({ month: today.getMonth(), year: today.getFullYear(), selected: null });

  const todayKey = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(today.getDate()).padStart(2, '0')}`;
  const isUpcoming = (slot) => slotDay(slot) >= todayKey;

  const slotsByDate = groupSlotsByDate(adminSlots);
  const visibleSlots = selectVisibleSlots({
    slotView, slotFilter, slotCal, adminSlots, archivedSlots, slotsByDate, isUpcoming,
  });

  return (
    <article className="panel panel--wide">
      <div className="panel-heading">
        <h3>Manage slots</h3>
        <div className="schedule-controls">
          <div className="schedule-toggle">
            <button className={slotView === 'list' ? 'active' : ''} onClick={() => setSlotView('list')} type="button">List</button>
            <button className={slotView === 'calendar' ? 'active' : ''} onClick={() => setSlotView('calendar')} type="button">Calendar</button>
          </div>
          <button
            className="primary-button compact"
            disabled={isLoadingAdminSlots || isLoadingArchivedSlots}
            onClick={() => (slotView === 'list' && slotFilter === 'archived' ? loadArchivedSlots() : loadAdminSlots(0))}
            type="button"
          >
            {isLoadingAdminSlots || isLoadingArchivedSlots ? 'Loading' : 'Refresh'}
          </button>
        </div>
      </div>
      {adminSlotsError && <p className="form-message error">{adminSlotsError}</p>}
      {slotFilter === 'archived' && archivedSlotsError && <p className="form-message error">{archivedSlotsError}</p>}

      {slotView === 'list' && (
        <SlotFilterTabs
          slotFilter={slotFilter}
          setSlotFilter={setSlotFilter}
          adminSlots={adminSlots}
          isUpcoming={isUpcoming}
          archivedSlotsLoaded={archivedSlotsLoaded}
          archivedSlotsTotal={archivedSlotsTotal}
          loadArchivedSlots={loadArchivedSlots}
        />
      )}

      {slotView === 'calendar' && (
        <SlotCalendarView
          slotCal={slotCal}
          setSlotCal={setSlotCal}
          slotsByDate={slotsByDate}
          visibleCount={visibleSlots.length}
        />
      )}

      <SlotList
        slotView={slotView}
        slotFilter={slotFilter}
        visibleSlots={visibleSlots}
        adminSlotsError={adminSlotsError}
        archivedSlotsError={archivedSlotsError}
        isLoadingArchivedSlots={isLoadingArchivedSlots}
        archivedSlots={archivedSlots}
        archivedSlotsLoaded={archivedSlotsLoaded}
        archivedSlotsPage={archivedSlotsPage}
        archivedSlotsTotal={archivedSlotsTotal}
        loadArchivedSlots={loadArchivedSlots}
        formatDate={formatDate}
        formatTimestamp={formatTimestamp}
        handleDeleteSlot={handleDeleteSlot}
      />
    </article>
  );
}

// ── Tab: Schedule (admin) ──
function ScheduleTab(props) {
  return (
    <div className="dashboard-grid">
      <ScheduleBookingsPanel
        scheduleView={props.scheduleView}
        setScheduleView={props.setScheduleView}
        isLoadingAdminBookings={props.isLoadingAdminBookings}
        loadAdminBookings={props.loadAdminBookings}
        adminBookingsError={props.adminBookingsError}
        scheduleFilter={props.scheduleFilter}
        setScheduleFilter={props.setScheduleFilter}
        adminBookings={props.adminBookings}
        scheduleCal={props.scheduleCal}
        setScheduleCal={props.setScheduleCal}
        bookingsByDate={props.bookingsByDate}
        scheduleSelectedBookings={props.scheduleSelectedBookings}
        setBookingDetail={props.setBookingDetail}
        handleAdminCompleteBooking={props.handleAdminCompleteBooking}
        handleAdminCancelBooking={props.handleAdminCancelBooking}
        handleAdminConfirmBooking={props.handleAdminConfirmBooking}
        handleResendConfirmation={props.handleResendConfirmation}
        formatDate={props.formatDate}
        statusClass={props.statusClass}
      />

      <UsersPanel
        loadAdminUsers={props.loadAdminUsers}
        isLoadingAdmin={props.isLoadingAdmin}
        adminError={props.adminError}
        adminUsers={props.adminUsers}
        adminUsersMeta={props.adminUsersMeta}
      />

      <CreateSlotsPanel
        slotBuilder={props.slotBuilder}
        setSlotBuilder={props.setSlotBuilder}
        updateSlotBuilderField={props.updateSlotBuilderField}
        prevMonth={props.prevMonth}
        nextMonth={props.nextMonth}
        toggleSpecificDate={props.toggleSpecificDate}
        removeSpecificDate={props.removeSpecificDate}
        toggleRecurringDay={props.toggleRecurringDay}
        handleCreateSlots={props.handleCreateSlots}
        slotFormMessage={props.slotFormMessage}
        slotFormMessageType={props.slotFormMessageType}
        isCreatingSlot={props.isCreatingSlot}
      />

      <ManageSlotsPanel
        isLoadingAdminSlots={props.isLoadingAdminSlots}
        loadAdminSlots={props.loadAdminSlots}
        adminSlotsError={props.adminSlotsError}
        adminSlots={props.adminSlots}
        isLoadingArchivedSlots={props.isLoadingArchivedSlots}
        loadArchivedSlots={props.loadArchivedSlots}
        archivedSlotsError={props.archivedSlotsError}
        archivedSlots={props.archivedSlots}
        archivedSlotsLoaded={props.archivedSlotsLoaded}
        archivedSlotsPage={props.archivedSlotsPage}
        archivedSlotsTotal={props.archivedSlotsTotal}
        formatDate={props.formatDate}
        formatTimestamp={props.formatTimestamp}
        handleDeleteSlot={props.handleDeleteSlot}
      />
    </div>
  );
}

function ProfileView({
  user,
  isAdmin,
  profileTab,
  setActiveView,
  scrollToSection,
  openBookingModal,
  bookingModalEl,
  cancelConfirmEl,
  bookingDetailEl,
  selectProfileTab,
  isLoadingMyBookings,
  loadMyBookings,
  myBookingsError,
  bookingActionMessage,
  bookingActionMessageType,
  myBookings,
  bookingsFilter,
  setBookingsFilter,
  formatDate,
  formatTimestamp,
  statusClass,
  setConfirmCancelId,
  refreshProfile,
  signOut,
  signOutMessage,
  handleChangePassword,
  updateChangePasswordField,
  changePasswordForm,
  changePasswordMessage,
  changePasswordMessageType,
  isChangingPassword,
  handleDeleteAccount,
  deleteAccountMessage,
  isDeletingAccount,
  scheduleView,
  setScheduleView,
  isLoadingAdminBookings,
  loadAdminBookings,
  adminBookingsError,
  scheduleFilter,
  setScheduleFilter,
  adminBookings,
  scheduleCal,
  setScheduleCal,
  bookingsByDate,
  scheduleSelectedBookings,
  setBookingDetail,
  handleAdminCompleteBooking,
  handleAdminCancelBooking,
  handleAdminConfirmBooking,
  handleResendConfirmation,
  loadAdminUsers,
  isLoadingAdmin,
  adminError,
  adminUsers,
  adminUsersMeta,
  slotBuilder,
  setSlotBuilder,
  updateSlotBuilderField,
  prevMonth,
  nextMonth,
  toggleSpecificDate,
  removeSpecificDate,
  toggleRecurringDay,
  handleCreateSlots,
  slotFormMessage,
  slotFormMessageType,
  isCreatingSlot,
  isLoadingAdminSlots,
  loadAdminSlots,
  adminSlotsError,
  adminSlots,
  isLoadingArchivedSlots,
  loadArchivedSlots,
  archivedSlotsError,
  archivedSlots,
  archivedSlotsLoaded,
  archivedSlotsPage,
  archivedSlotsTotal,
  handleDeleteSlot,
}) {
  return (
    <div className="landing">
      <nav className="landing-nav">
        <button
          className="landing-logo"
          onClick={() => { setActiveView('landing'); window.scrollTo(0, 0); }}
          type="button"
        >
          <img alt="Every1 Luvs Nails" className="logo-nav" src={logoBlack} />
        </button>
        <div className="landing-nav-links">
          <button className="nav-text-link" onClick={() => scrollToSection('services')} type="button">Services</button>
          <button className="nav-text-link" onClick={() => scrollToSection('about')} type="button">About</button>
          <button className="nav-text-link" onClick={() => scrollToSection('contact')} type="button">Contact</button>
          <button
            className="nav-tab-link nav-tab-link--active"
            onClick={() => { setActiveView('profile'); window.scrollTo(0, 0); }}
            type="button"
          >
            My Profile
          </button>
        </div>
        <button className="pill-button" onClick={openBookingModal} type="button">
          Book Now
        </button>
      </nav>

      {bookingModalEl}
      {cancelConfirmEl}
      {bookingDetailEl}

      <div className="profile-view">
        <div className="profile-view-header">
          <div>
            <p className="section-eyebrow">Welcome back</p>
            <h2 className="profile-view-name">{user.name}</h2>
          </div>
          <div className="profile-view-meta">
            <span className="role-pill">{user.role}</span>
          </div>
        </div>

        <div className="profile-tabs" role="tablist">
          <button className={`profile-tab${profileTab === 'bookings' ? ' profile-tab--active' : ''}`} onClick={() => selectProfileTab('bookings')} type="button">My Bookings</button>
          <button className={`profile-tab${profileTab === 'rewards' ? ' profile-tab--active' : ''}`} onClick={() => selectProfileTab('rewards')} type="button">Rewards</button>
          <button className={`profile-tab${profileTab === 'account' ? ' profile-tab--active' : ''}`} onClick={() => selectProfileTab('account')} type="button">Account</button>
          {isAdmin && (
            <button className={`profile-tab${profileTab === 'schedule' ? ' profile-tab--active' : ''}`} onClick={() => selectProfileTab('schedule')} type="button">Schedule</button>
          )}
        </div>

        {/* ── My Bookings ── */}
        {profileTab === 'bookings' && (
          <BookingsTab
            isLoadingMyBookings={isLoadingMyBookings}
            loadMyBookings={loadMyBookings}
            myBookingsError={myBookingsError}
            bookingActionMessage={bookingActionMessage}
            bookingActionMessageType={bookingActionMessageType}
            myBookings={myBookings}
            bookingsFilter={bookingsFilter}
            setBookingsFilter={setBookingsFilter}
            formatDate={formatDate}
            formatTimestamp={formatTimestamp}
            statusClass={statusClass}
            setConfirmCancelId={setConfirmCancelId}
          />
        )}

        {/* ── Rewards ── */}
        {profileTab === 'rewards' && <RewardsTab />}

        {/* ── Account ── */}
        {profileTab === 'account' && (
          <AccountTab
            user={user}
            refreshProfile={refreshProfile}
            signOut={signOut}
            signOutMessage={signOutMessage}
            handleChangePassword={handleChangePassword}
            updateChangePasswordField={updateChangePasswordField}
            changePasswordForm={changePasswordForm}
            changePasswordMessage={changePasswordMessage}
            changePasswordMessageType={changePasswordMessageType}
            isChangingPassword={isChangingPassword}
            handleDeleteAccount={handleDeleteAccount}
            deleteAccountMessage={deleteAccountMessage}
            isDeletingAccount={isDeletingAccount}
          />
        )}

        {/* ── Schedule (admin) ── */}
        {profileTab === 'schedule' && isAdmin && (
          <ScheduleTab
            formatDate={formatDate}
            formatTimestamp={formatTimestamp}
            statusClass={statusClass}
            scheduleView={scheduleView}
            setScheduleView={setScheduleView}
            isLoadingAdminBookings={isLoadingAdminBookings}
            loadAdminBookings={loadAdminBookings}
            adminBookingsError={adminBookingsError}
            scheduleFilter={scheduleFilter}
            setScheduleFilter={setScheduleFilter}
            adminBookings={adminBookings}
            scheduleCal={scheduleCal}
            setScheduleCal={setScheduleCal}
            bookingsByDate={bookingsByDate}
            scheduleSelectedBookings={scheduleSelectedBookings}
            setBookingDetail={setBookingDetail}
            handleAdminCompleteBooking={handleAdminCompleteBooking}
            handleAdminCancelBooking={handleAdminCancelBooking}
            handleAdminConfirmBooking={handleAdminConfirmBooking}
            handleResendConfirmation={handleResendConfirmation}
            loadAdminUsers={loadAdminUsers}
            isLoadingAdmin={isLoadingAdmin}
            adminError={adminError}
            adminUsers={adminUsers}
            adminUsersMeta={adminUsersMeta}
            slotBuilder={slotBuilder}
            setSlotBuilder={setSlotBuilder}
            updateSlotBuilderField={updateSlotBuilderField}
            prevMonth={prevMonth}
            nextMonth={nextMonth}
            toggleSpecificDate={toggleSpecificDate}
            removeSpecificDate={removeSpecificDate}
            toggleRecurringDay={toggleRecurringDay}
            handleCreateSlots={handleCreateSlots}
            slotFormMessage={slotFormMessage}
            slotFormMessageType={slotFormMessageType}
            isCreatingSlot={isCreatingSlot}
            isLoadingAdminSlots={isLoadingAdminSlots}
            loadAdminSlots={loadAdminSlots}
            adminSlotsError={adminSlotsError}
            adminSlots={adminSlots}
            isLoadingArchivedSlots={isLoadingArchivedSlots}
            loadArchivedSlots={loadArchivedSlots}
            archivedSlotsError={archivedSlotsError}
            archivedSlots={archivedSlots}
            archivedSlotsLoaded={archivedSlotsLoaded}
            archivedSlotsPage={archivedSlotsPage}
            archivedSlotsTotal={archivedSlotsTotal}
            handleDeleteSlot={handleDeleteSlot}
          />
        )}
      </div>
    </div>
  );
}

export default ProfileView;
