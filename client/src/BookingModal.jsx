import { useEffect, useMemo, useState } from 'react';
import './BookingModal.css';
import { getCalendarDays } from './slotBuilderUtils';
import { NAIL_ART, NAIL_SERVICES, REMOVAL } from './services';
import { useFocusTrap } from './useFocusTrap';

// Relative base so requests go through the dev-server proxy (same-origin). See App.jsx.
const API_BASE_URL = process.env.REACT_APP_API_URL || '';

const TERMS = [
  'Bookings are made through DMs unless otherwise stated.',
  'A deposit of S$30 is needed to secure your slot. No deposit within 24 hours = cancelled appointment.',
  'Deposit is non-refundable when: rescheduling less than 72 hours from your slot; reschedule more than 1 time; no show or cancellation; arriving more than 15 minutes late (counts as a no-show).',
  'Please check your manicure before leaving. Touch-ups are only offered for 7 days after your appointment date.',
  'Complimentary nail art top-up is offered on a per-appointment basis only. Please inform before your appointment.',
  'Kindly send inspo pictures once your booking is confirmed — this gives us time to ensure we have the necessary supplies for your set.',
  'A late fee of S$10 applies to clients who are more than 15 minutes late. Arriving 20 minutes late may not leave sufficient time to complete the service.',
  "For 'On My Mind, On Your Nails' sets — your nails will be featured on our Instagram story. By booking this set you consent to your nails being photographed and shared on our social media.",
  'By booking with us you agree to these Terms & Conditions. Thank You! ♥'
];

const STEP_LABELS = ['Service', 'Add-ons', 'Date & Time', 'T&C', 'Personal Details'];

const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];
const WEEKDAYS_SHORT = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];

function formatLongDate(iso) {
  if (!iso) return '';
  const [y, m, d] = iso.split('-').map(Number);
  const dow = new Date(y, m - 1, d).getDay();
  return `${WEEKDAYS_SHORT[dow]}, ${d} ${MONTHS[m - 1]} ${y}`;
}

function CheckIcon({ className }) {
  return (
    <svg className={className} fill="none" height="16" stroke="currentColor" strokeLinecap="round" strokeLinejoin="round" strokeWidth="2.4" viewBox="0 0 24 24" width="16" xmlns="http://www.w3.org/2000/svg">
      <path d="M20 6 9 17l-5-5" />
    </svg>
  );
}

// Sum the chosen service plus optional nail-art and removal add-ons.
// Missing selections contribute 0.
function computeTotal(service, nailArt, removal) {
  const basePrice = service ? service.price : 0;
  const nailArtPrice = NAIL_ART.find((a) => a.id === nailArt)?.price || 0;
  const removalPrice = REMOVAL.find((r) => r.id === removal)?.price || 0;
  return basePrice + nailArtPrice + removalPrice;
}

function computeEstimatedDuration(service, nailArt, removal) {
  const baseDuration = service?.durationMin || 0;
  const nailArtDuration = NAIL_ART.find((a) => a.id === nailArt)?.durationMin || 0;
  const removalDuration = REMOVAL.find((r) => r.id === removal)?.durationMin || 0;
  return baseDuration + nailArtDuration + removalDuration;
}

// Shared service/technician/date/time/total recap, used by the details step and
// the success screen. `className` and `children` let each caller tweak styling
// and append a deposit note without duplicating the rows.
function BookingSummary({ service, date, time, total, className = 'bk-summary', children }) {
  return (
    <div className={className}>
      <div><span>Service</span><span>{service?.name}</span></div>
      <div><span>Date</span><span>{formatLongDate(date)}</span></div>
      <div><span>Time</span><span>{time}</span></div>
      <div><span>Total</span><span>S${total}</span></div>
      {children}
    </div>
  );
}

function Stepper({ step }) {
  return (
    <div className="bk-stepper">
      {STEP_LABELS.map((label, i) => (
        <div className="bk-step" key={label}>
          <span className={`bk-step-dot ${i < step ? 'bk-step-dot--done' : i === step ? 'bk-step-dot--active' : 'bk-step-dot--todo'}`}>
            {i === step && <span className="bk-step-dot-core" />}
          </span>
          {i === step && <span className="bk-step-label">{label}</span>}
        </div>
      ))}
    </div>
  );
}

// Step 0 — Service
function ServiceStep({ services, serviceId, selectService }) {
  return (
    <>
      <div className="bk-service-list">
        {services.map((s) => (
          <button
            className={`bk-service${serviceId === s.id ? ' bk-service--selected' : ''}`}
            key={s.id}
            onClick={() => selectService(s.id)}
            type="button"
          >
            <div className="bk-service-info">
              <div className="bk-service-name-row">
                <span className="bk-service-name">{s.name}</span>
                {s.popular && <span className="bk-popular">Popular</span>}
              </div>
              <p className="bk-service-desc">{s.desc}</p>
              <span className="bk-service-duration">🕐 {s.duration}</span>
            </div>
            <div className="bk-service-price">
              <span className="bk-price">S${s.price}</span>
              {serviceId === s.id && <CheckIcon className="bk-service-check" />}
            </div>
          </button>
        ))}
      </div>
    </>
  );
}

// Step 1 — Add-ons
function AddOnsStep({ nailArt, setNailArt, removal, setRemoval, total }) {
  return (
    <>
      <p className="bk-prompt">Customise your appointment. All add-ons are optional.</p>
      <p className="bk-section-label">Nail Art Design</p>
      <div className="bk-option-list">
        {NAIL_ART.map((a) => (
          <button className={`bk-option${nailArt === a.id ? ' bk-option--selected' : ''}`} key={a.id} onClick={() => setNailArt(a.id)} type="button">
            <div className="bk-option-info">
              <span className="bk-option-name">{a.name}{a.time && <span className="bk-option-time">{a.time}</span>}</span>
              {a.sub && <span className="bk-option-sub">{a.sub}</span>}
            </div>
            {a.price > 0 && <span className="bk-option-price">from S${a.price}</span>}
            {nailArt === a.id && <CheckIcon className="bk-option-check" />}
          </button>
        ))}
      </div>
      <p className="bk-section-label">Removal <span className="bk-muted">(if applicable)</span></p>
      <div className="bk-option-list">
        {REMOVAL.map((r) => (
          <button className={`bk-option${removal === r.id ? ' bk-option--selected' : ''}`} key={r.id} onClick={() => setRemoval(r.id)} type="button">
            <div className="bk-option-info">
              <span className="bk-option-name">{r.name}{r.time && <span className="bk-option-time">{r.time}</span>}</span>
              {r.sub && <span className="bk-option-sub">{r.sub}</span>}
            </div>
            {r.price > 0 && <span className="bk-option-price">+S${r.price}</span>}
            {removal === r.id && <CheckIcon className="bk-option-check" />}
          </button>
        ))}
      </div>
      <div className="bk-estimate">
        <span>Estimated total</span>
        <span>S${total}</span>
      </div>
    </>
  );
}

// Step 2 — Date & Time
function DateTimeStep({
  calMonth,
  calYear,
  prevMonth,
  nextMonth,
  availabilityByDate,
  date,
  setDate,
  setTime,
  time,
  availableTimes,
  estimatedDuration,
}) {
  return (
    <>
      <div className="bk-cal-header">
        <button className="bk-cal-nav" onClick={prevMonth} type="button" aria-label="Previous month">&#8249;</button>
        <span className="bk-cal-title">{MONTHS[calMonth]} {calYear}</span>
        <button className="bk-cal-nav" onClick={nextMonth} type="button" aria-label="Next month">&#8250;</button>
      </div>
      <div className="bk-cal-grid">
        {['Su', 'Mo', 'Tu', 'We', 'Th', 'Fr', 'Sa'].map((d) => <span className="bk-cal-dow" key={d}>{d}</span>)}
        {getCalendarDays(calYear, calMonth).map((cell, i) => {
          if (!cell.date) return <span className="bk-cal-cell bk-cal-cell--empty" key={i} />;
          const hasSlots = (availabilityByDate[cell.date] || []).length > 0;
          return (
            <button
              className={`bk-cal-cell${date === cell.date ? ' bk-cal-cell--selected' : ''}`}
              disabled={!hasSlots}
              key={i}
              onClick={() => { setDate(cell.date); setTime(null); }}
              type="button"
            >
              {cell.day}
            </button>
          );
        })}
      </div>
      {date && (
        <div className="bk-times">
          <p className="bk-times-head">Available times · <strong>{formatLongDate(date)}</strong></p>
          <p className="bk-times-duration">Estimated duration: <strong>{estimatedDuration} min</strong></p>
          {availableTimes.length > 0 ? (
            <div className="bk-time-grid">
              {availableTimes.map((t) => (
                <button className={`bk-time${time === t ? ' bk-time--selected' : ''}`} key={t} onClick={() => setTime(t)} type="button">{t}</button>
              ))}
            </div>
          ) : (
            <p className="bk-times-empty">No available times for this day.</p>
          )}
        </div>
      )}
    </>
  );
}

// Step 3 — T&C
function TermsStep({ agreed, setAgreed }) {
  return (
    <>
      <p className="bk-prompt">Please read and accept our Terms &amp; Conditions before entering your details.</p>
      <div className="bk-terms">
        <p className="bk-terms-title">Terms &amp; Conditions</p>
        <ol className="bk-terms-list">
          {TERMS.map((t, i) => <li key={i}>{t}</li>)}
        </ol>
      </div>
      <label className="bk-agree">
        <input checked={agreed} onChange={(e) => setAgreed(e.target.checked)} type="checkbox" />
        <span>I have read and agree to the Terms &amp; Conditions. I understand a S$30 deposit is required and is non-refundable under the stated conditions.</span>
      </label>
    </>
  );
}

// Step 4 — Details & Confirm
function DetailsStep({ service, date, time, total, deposit, form, updateForm, bookingError }) {
  return (
    <>
      <BookingSummary service={service} date={date} time={time} total={total} />
      <div className="bk-summary-deposit">
        <span>Deposit due</span><span>S${deposit}</span>
      </div>
      <label className="bk-field">
        <span>Full Name *</span>
        <input name="fullName" onChange={updateForm} placeholder="Your name" type="text" value={form.fullName} />
      </label>
      <label className="bk-field">
        <span>Email *</span>
        <input name="email" onChange={updateForm} placeholder="your@email.com" type="email" value={form.email} />
      </label>
      <label className="bk-field">
        <span>Phone</span>
        <input name="phone" onChange={updateForm} placeholder="+65 9123 4567" type="tel" value={form.phone} />
      </label>
      <label className="bk-field">
        <span>Instagram Username</span>
        <input name="instagram" onChange={updateForm} placeholder="@yourhandle" type="text" value={form.instagram} />
      </label>
      <label className="bk-field">
        <span>Notes (optional)</span>
        <textarea name="notes" onChange={updateForm} placeholder="Nail shape, inspo refs, allergies…" rows="3" value={form.notes} />
      </label>
      <p className="bk-deposit-note">A deposit of S${deposit} is required to confirm your slot. Payment details will be shared after booking.</p>
      {bookingError && <p className="bk-booking-error">{bookingError}</p>}
    </>
  );
}

function SuccessView({ formEmail, service, date, time, total, deposit, onClose }) {
  return (
    <div className="bk-success">
      <div className="bk-success-check"><CheckIcon /></div>
      <h2 className="bk-success-title">You're booked!</h2>
      <p className="bk-success-sub">
        Confirmation sent to {formEmail || 'your email'}. Kindly send your inspo pics via DM once your deposit is paid. 💅
      </p>
      <BookingSummary
        className="bk-summary bk-summary--success"
        service={service}
        date={date}
        time={time}
        total={total}
      >
        <p className="bk-summary-note">Deposit of S${deposit} required to confirm slot.</p>
      </BookingSummary>
      <button className="bk-btn bk-btn--primary bk-btn--full" onClick={onClose} type="button">Done</button>
    </div>
  );
}

export default function BookingModal({ onClose, onConfirm, currentUser }) {
  const now = new Date();
  const [step, setStep] = useState(0);
  const [done, setDone] = useState(false);

  const [serviceId, setServiceId] = useState(null);
  const [nailArt, setNailArt] = useState('none');
  const [removal, setRemoval] = useState('none');
  const [calYear, setCalYear] = useState(now.getFullYear());
  const [calMonth, setCalMonth] = useState(now.getMonth());
  const [date, setDate] = useState(null);
  const [time, setTime] = useState(null);
  const [agreed, setAgreed] = useState(false);
  const [form, setForm] = useState({
    fullName: currentUser?.name || '',
    email: currentUser?.email || '',
    phone: '',
    instagram: '',
    notes: '',
  });
  const [availableSlots, setAvailableSlots] = useState([]);
  const [submitting, setSubmitting] = useState(false);
  const [bookingError, setBookingError] = useState('');

  const dialogRef = useFocusTrap(true, onClose);

  useEffect(() => {
    const prevOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.body.style.overflow = prevOverflow;
    };
  }, []);

  useEffect(() => {
    let cancelled = false;
    fetch(`${API_BASE_URL}/api/slots/available`)
      .then((res) => (res.ok ? res.json() : []))
      .then((data) => { if (!cancelled) setAvailableSlots(Array.isArray(data) ? data : []); })
      .catch(() => { if (!cancelled) setAvailableSlots([]); });
    return () => { cancelled = true; };
  }, []);

  // Map of available dates -> sorted unique start times (read from the stored
  // wall-clock; slots are persisted as UTC so the ISO date/time substrings are
  // the intended local values).
  const availabilityByDate = useMemo(() => {
    const map = new Map();
    for (const slot of availableSlots) {
      if (!slot?.startTime || slot.available === false) continue;
      const iso = String(slot.startTime);
      const dateKey = iso.slice(0, 10);
      const timeVal = iso.slice(11, 16);
      if (!dateKey || !timeVal) continue;
      if (!map.has(dateKey)) map.set(dateKey, new Set());
      map.get(dateKey).add(timeVal);
    }
    const result = {};
    for (const [d, times] of map.entries()) {
      result[d] = [...times].sort();
    }
    return result;
  }, [availableSlots]);

  const availableTimes = date ? (availabilityByDate[date] || []) : [];

  const services = NAIL_SERVICES;
  const service = useMemo(
    () => NAIL_SERVICES.find((s) => s.id === serviceId) || null,
    [serviceId],
  );

  const total = computeTotal(service, nailArt, removal);
  const estimatedDuration = computeEstimatedDuration(service, nailArt, removal);
  const deposit = 30;

  const canContinue = (() => {
    if (step === 0) return !!service;
    if (step === 2) return !!date && !!time;
    if (step === 3) return agreed;
    if (step === 4) return form.fullName.trim() && form.email.trim();
    return true;
  })();

  function next() { setStep((s) => Math.min(s + 1, STEP_LABELS.length - 1)); }
  function back() { setStep((s) => Math.max(s - 1, 0)); }

  function selectService(id) {
    setServiceId(id);
  }

  function prevMonth() {
    setCalYear((y) => (calMonth === 0 ? y - 1 : y));
    setCalMonth((m) => (m === 0 ? 11 : m - 1));
  }
  function nextMonth() {
    setCalYear((y) => (calMonth === 11 ? y + 1 : y));
    setCalMonth((m) => (m === 11 ? 0 : m + 1));
  }

  function updateForm(e) {
    setForm((f) => ({ ...f, [e.target.name]: e.target.value }));
  }

  function resolveSlotId() {
    const match = availableSlots.find(
      (s) => s?.available !== false
        && String(s.startTime).slice(0, 10) === date
        && String(s.startTime).slice(11, 16) === time,
    );
    return match?.id ?? null;
  }

  async function confirmBooking() {
    if (!canContinue || submitting) return;
    setBookingError('');

    const slotId = resolveSlotId();
    if (!slotId) {
      setBookingError('That time is no longer available. Please pick another slot.');
      return;
    }

    setSubmitting(true);
    try {
      await onConfirm({
        slotId,
        form,
        total,
        deposit,
        // Send selection IDs; the server recomputes the canonical names + price from these.
        serviceId: service?.id || null,
        nailArtId: nailArt,
        removalId: removal,
        date,
        time,
      });
      setDone(true);
    } catch (err) {
      setBookingError(err?.message || 'Could not complete your booking. Please try again.');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="bk-backdrop">
      <div className="bk-modal" ref={dialogRef} tabIndex={-1} role="dialog" aria-modal="true" aria-label="Book Appointment">
        {done ? (
          <SuccessView
            formEmail={form.email}
            service={service}
            date={date}
            time={time}
            total={total}
            deposit={deposit}
            onClose={onClose}
          />
        ) : (
          <>
            <header className="bk-header">
              <h2 className="bk-title">Book Appointment</h2>
              <button className="bk-close" onClick={onClose} type="button" aria-label="Close">&times;</button>
            </header>

            <Stepper step={step} />

            <div className="bk-body">
              {[
                <ServiceStep
                  services={services}
                  serviceId={serviceId}
                  selectService={selectService}
                />,
                <AddOnsStep
                  nailArt={nailArt}
                  setNailArt={setNailArt}
                  removal={removal}
                  setRemoval={setRemoval}
                  total={total}
                />,
                <DateTimeStep
                  calMonth={calMonth}
                  calYear={calYear}
                  prevMonth={prevMonth}
                  nextMonth={nextMonth}
                  availabilityByDate={availabilityByDate}
                  date={date}
                  setDate={setDate}
                  setTime={setTime}
                  time={time}
                  availableTimes={availableTimes}
                  estimatedDuration={estimatedDuration}
                />,
                <TermsStep agreed={agreed} setAgreed={setAgreed} />,
                <DetailsStep
                  service={service}
                  date={date}
                  time={time}
                  total={total}
                  deposit={deposit}
                  form={form}
                  updateForm={updateForm}
                  bookingError={bookingError}
                />,
              ][step]}
            </div>

            <footer className="bk-footer">
              {step > 0 && <button className="bk-btn bk-btn--ghost" onClick={back} type="button" disabled={submitting}>Back</button>}
              {step < 4 ? (
                <button className={`bk-btn bk-btn--primary${step === 0 ? ' bk-btn--full' : ''}`} disabled={!canContinue} onClick={next} type="button">Continue</button>
              ) : (
                <button className="bk-btn bk-btn--primary" disabled={!canContinue || submitting} onClick={confirmBooking} type="button">
                  {submitting ? 'Booking…' : 'Confirm Booking'}
                </button>
              )}
            </footer>
          </>
        )}
      </div>
    </div>
  );
}
