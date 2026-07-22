import { useEffect, useMemo, useRef, useState } from 'react';
import './BookingModal.css';
import { getCalendarDays } from './slotBuilderUtils';
import { NAIL_ART, NAIL_SERVICES, REMOVAL } from './services';
import { useFocusTrap } from './useFocusTrap';

// Relative base so requests go through the dev-server proxy (same-origin). See App.jsx.
const API_BASE_URL = import.meta.env.VITE_API_URL || '';

// Inspo-image limits. Kept in step with BookingService.validateAttachments and the nginx
// client_max_body_size so the client rejects oversized sets before the server/edge does.
const MAX_ATTACHMENTS = 5;
const MAX_ATTACHMENT_BYTES = 5 * 1024 * 1024;
const MAX_TOTAL_ATTACHMENT_BYTES = 15 * 1024 * 1024;
// Only formats the server can decode + re-encode (ImageSanitizer uses stock Java ImageIO — no
// WebP/HEIC). Keep this in step with the accept attribute and the backend allow-list.
const ALLOWED_IMAGE_TYPES = ['image/jpeg', 'image/png'];

const TERMS = [
  'All bookings must be made through our website unless otherwise stated.',
  'A S$30 deposit secures your slot. Unpaid deposits after 24 hours will result in automatic cancellation.',
  'Cancellations or reschedules must be made at least 72 hours in advance, or the deposit will be forfeited.',
  'Kindly share your inspo pictures or links when booking (or send them to us via DM) — this gives us time to ensure we have the necessary supplies for your set.',
  'Please check your manicure before leaving. Touch-ups are offered up to 7 days after your appointment date.',
  'Arriving more than 20 minutes late incurs a S$10 late fee and may mean your full set cannot be completed.',
  'We’re not liable for allergic reactions or product sensitivity not disclosed beforehand. We may decline or modify a service if nails show signs of infection/damage.',
  'As our studio space is limited, we kindly ask that clients attend their appointment alone where possible. Please let us know in advance if you’ll be bringing a guest.',
  'Prices are subject to change without prior notice. Any price changes will not affect deposits already paid for confirmed bookings.'
];

const STEP_LABELS = ['Service', 'Add-ons', 'Date & Time', 'Personal Details', 'T&C', 'Deposit'];

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

// Human-readable list of the chosen add-ons for the recap rows. "none" selections (the default
// nail-art/removal options) are omitted; shows "None" when nothing extra is added.
function formatAddOns(nailArt, removal) {
  const parts = [];
  if (nailArt && nailArt !== 'none') {
    parts.push(NAIL_ART.find((a) => a.id === nailArt)?.name || nailArt);
  }
  if (removal && removal !== 'none') {
    parts.push(REMOVAL.find((r) => r.id === removal)?.name || removal);
  }
  return parts.length ? parts.join(', ') : 'None';
}

// Shared service/add-ons/date/time/total recap, used by the details step and the success screen.
// `className` and `children` let each caller tweak styling and append a deposit note without
// duplicating the rows. The total is an estimate, so it is labelled "Total estimate".
function BookingSummary({ service, addOns, date, time, total, className = 'bk-summary', children }) {
  return (
    <div className={className}>
      <div><span>Services</span><span>{service?.name}</span></div>
      <div><span>Add-ons</span><span>{addOns}</span></div>
      <div><span>Date</span><span>{formatLongDate(date)}</span></div>
      <div><span>Time</span><span>{time}</span></div>
      <div><span>Total estimate</span><span>S${total}</span></div>
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

// Step 4 — T&C (final step; confirms the booking)
function TermsStep({ agreed, setAgreed, bookingError }) {
  return (
    <>
      <p className="bk-prompt">Please read and accept our Terms &amp; Conditions to complete your booking.</p>
      <div className="bk-terms">
        <p className="bk-terms-title">Terms &amp; Conditions</p>
        <ol className="bk-terms-list">
          {TERMS.map((t, i) => <li key={i}>{t}</li>)}
        </ol>
      </div>
      <label className="bk-agree">
        <input checked={agreed} onChange={(e) => setAgreed(e.target.checked)} type="checkbox" />
        <span>I have read and agree to the Terms &amp; Conditions.</span>
      </label>
      {bookingError && <p className="bk-booking-error">{bookingError}</p>}
    </>
  );
}

// Step 5 — Deposit (final step; recap + fixed-S$30 PayNow QR, gated on a "paid" confirmation)
function DepositStep({
  service, addOns, date, time, total, deposit, depositPaid, setDepositPaid, bookingError,
}) {
  return (
    <>
      <BookingSummary service={service} addOns={addOns} date={date} time={time} total={total} />
      <div className="bk-summary-deposit">
        <span>Deposit due</span><span>S${deposit}</span>
      </div>
      <div className="bk-paynow">
        <img
          alt={`PayNow S$${deposit} deposit QR code`}
          className="bk-paynow-qr"
          onError={(e) => { e.currentTarget.style.display = 'none'; }}
          src="/paynow-qr.png"
        />
        <p className="bk-paynow-note">
          Scan with your banking app and pay <strong>S${deposit}</strong>, using <strong>your name</strong> as
          the payment reference. Your slot is confirmed once we receive it — unpaid deposits are
          released after 24 hours.
        </p>
      </div>
      <label className="bk-agree">
        <input checked={depositPaid} onChange={(e) => setDepositPaid(e.target.checked)} type="checkbox" />
        <span>I have paid the S${deposit} deposit.</span>
      </label>
      {bookingError && <p className="bk-booking-error">{bookingError}</p>}
    </>
  );
}

// Read a File into a transient attachment: base64 `data` for the request plus a `previewUrl`
// data-URL for the thumbnail. Resolves null if the reader fails so one bad file is skipped.
function readImageFile(file) {
  return new Promise((resolve) => {
    const reader = new FileReader();
    reader.onload = () => {
      const dataUrl = String(reader.result || '');
      const comma = dataUrl.indexOf(',');
      const base64 = comma >= 0 ? dataUrl.slice(comma + 1) : dataUrl;
      resolve({
        id: `${file.name}-${file.size}-${file.lastModified}-${Math.random().toString(36).slice(2)}`,
        filename: file.name || 'inspo.jpg',
        contentType: file.type || 'image/jpeg',
        data: base64,
        previewUrl: dataUrl,
        size: file.size,
      });
    };
    reader.onerror = () => resolve(null);
    reader.readAsDataURL(file);
  });
}

// Step 3 — Personal Details
function DetailsStep({
  service, addOns, date, time, total, deposit, form, updateForm,
  attachments, addFiles, removeAttachment, attachmentError,
}) {
  function onFileInput(e) {
    addFiles(e.target.files);
    e.target.value = ''; // allow re-selecting the same file after a remove
  }

  return (
    <>
      <BookingSummary service={service} addOns={addOns} date={date} time={time} total={total} />
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
        <input name="phone" onChange={updateForm} placeholder="9123 4567" type="tel" value={form.phone} />
      </label>
      <label className="bk-field">
        <span>Instagram Username</span>
        <input name="instagram" onChange={updateForm} placeholder="@yourhandle" type="text" value={form.instagram} />
      </label>
      <label className="bk-field">
        <span>Notes (optional)</span>
        <textarea name="notes" onChange={updateForm} placeholder="Nail shape, inspo refs, allergies…" rows="3" value={form.notes} />
      </label>
      <div className="bk-field">
        <span>Inspo photos (optional)</span>
        <p className="bk-attach-hint">JPEG or PNG. Up to {MAX_ATTACHMENTS} images, 5&nbsp;MB each.</p>
        <label className="bk-attach-add">
          <input accept="image/jpeg,image/png" multiple onChange={onFileInput} type="file" />
          <span>+ Add photos</span>
        </label>
        {attachments.length > 0 && (
          <ul className="bk-attach-grid">
            {attachments.map((a) => (
              <li className="bk-attach-item" key={a.id}>
                <img alt={a.filename} className="bk-attach-thumb" src={a.previewUrl} />
                <button
                  aria-label={`Remove ${a.filename}`}
                  className="bk-attach-remove"
                  onClick={() => removeAttachment(a.id)}
                  type="button"
                >
                  &times;
                </button>
              </li>
            ))}
          </ul>
        )}
        {attachmentError && <p className="bk-attach-error">{attachmentError}</p>}
      </div>
      <p className="bk-deposit-note">A deposit of S${deposit} is required to confirm your slot. Payment details will be shared after booking.</p>
    </>
  );
}

// Partially mask an email for display: keep the first character of the local
// part and the full domain, star out the rest (e.g. "jane@gmail.com" -> "j***@gmail.com").
function maskEmail(email) {
  if (!email) return 'your email';
  const at = email.indexOf('@');
  if (at < 1) return email;
  const local = email.slice(0, at);
  const domain = email.slice(at + 1);
  const visible = local.slice(0, 1);
  const stars = '*'.repeat(Math.max(local.length - 1, 3));
  return `${visible}${stars}@${domain}`;
}

function SuccessView({ formEmail, service, addOns, date, time, total, deposit, onClose }) {
  return (
    <div className="bk-success">
      <div className="bk-success-check"><CheckIcon /></div>
      <h2 className="bk-success-title">You're all set! 🎉</h2>
      <p className="bk-success-sub">
        Time to give your hands the love they deserve.
        <br /><br />
        A confirmation email has been sent to {maskEmail(formEmail)}. Our studio address will follow
        in a separate email 2 days before your appointment.
        <br /><br />
        Thank you for booking with us, we'll have you leaving with fresh nails and good vibes.
        <br /><br />
        See you soon!
      </p>
      <BookingSummary
        className="bk-summary bk-summary--success"
        service={service}
        addOns={addOns}
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
  const [depositPaid, setDepositPaid] = useState(false);
  const [form, setForm] = useState({
    fullName: currentUser?.name || '',
    email: currentUser?.email || '',
    phone: '',
    instagram: '',
    notes: '',
  });
  const [availableSlots, setAvailableSlots] = useState([]);
  // The quote (service + add-ons) that `availableSlots` was actually loaded for. Continuation past
  // the date/time step is gated on this matching the current quote, so a customer can't proceed on
  // availability computed for a previous service/add-on selection while a newer fetch is in flight.
  const [loadedQuoteKey, setLoadedQuoteKey] = useState(null);
  const [submitting, setSubmitting] = useState(false);
  const [bookingError, setBookingError] = useState('');
  const [attachments, setAttachments] = useState([]);
  const [attachmentError, setAttachmentError] = useState('');

  // Accept image files from the picker or a paste, validating count/size against the same caps
  // the server enforces. Non-images and oversized files are skipped with an inline message.
  async function addFiles(fileList) {
    const files = Array.from(fileList || []);
    if (files.length === 0) return;
    setAttachmentError('');

    let error = '';
    const images = files.filter((f) => ALLOWED_IMAGE_TYPES.includes(f.type));
    if (images.length !== files.length) error = 'Only JPEG or PNG images can be attached.';

    let count = attachments.length;
    let totalBytes = attachments.reduce((sum, a) => sum + (a.size || 0), 0);
    const additions = [];
    for (const file of images) {
      if (count >= MAX_ATTACHMENTS) { error = `You can attach up to ${MAX_ATTACHMENTS} images.`; break; }
      if (file.size > MAX_ATTACHMENT_BYTES) { error = 'Each image must be 5 MB or smaller.'; continue; }
      if (totalBytes + file.size > MAX_TOTAL_ATTACHMENT_BYTES) { error = 'Those images are too large in total.'; break; }
      const attachment = await readImageFile(file);
      if (!attachment) { error = 'One image could not be read. Please try another.'; continue; }
      additions.push(attachment);
      count += 1;
      totalBytes += file.size;
    }

    if (additions.length) setAttachments((prev) => [...prev, ...additions]);
    if (error) setAttachmentError(error);
  }

  function removeAttachment(id) {
    setAttachments((prev) => prev.filter((a) => a.id !== id));
    setAttachmentError('');
  }

  const dialogRef = useFocusTrap(true, onClose);

  useEffect(() => {
    const prevOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.body.style.overflow = prevOverflow;
    };
  }, []);

  // Availability is quote-aware: the server checks whether the chosen service's duration still
  // fits each slot under the scheduling invariant. Re-fetch whenever the quote changes. An
  // AbortController cancels the in-flight request and a monotonic sequence guards against a slow
  // earlier-quote response landing after a newer one, so stale data can never overwrite current.
  const availabilitySeq = useRef(0);
  useEffect(() => {
    // No service chosen yet — nothing to check; availableSlots stays at its initial []. (serviceId
    // only ever goes null -> a real id, so there's no stale list to clear.)
    if (!serviceId) return undefined;
    const seq = (availabilitySeq.current += 1);
    const quoteKey = `${serviceId}|${nailArt}|${removal}`;
    const controller = new AbortController();
    const params = new URLSearchParams({ serviceId });
    if (nailArt) params.set('nailArtId', nailArt);
    if (removal) params.set('removalId', removal);
    fetch(`${API_BASE_URL}/api/slots/available?${params.toString()}`, { signal: controller.signal })
      .then((res) => (res.ok ? res.json() : []))
      .then((data) => {
        if (seq !== availabilitySeq.current) return;
        setAvailableSlots(Array.isArray(data) ? data : []);
        setLoadedQuoteKey(quoteKey);
      })
      .catch((err) => {
        if (err.name === 'AbortError' || seq !== availabilitySeq.current) return;
        setAvailableSlots([]);
        setLoadedQuoteKey(quoteKey);
      });
    return () => controller.abort();
  }, [serviceId, nailArt, removal]);

  const service = useMemo(
    () => NAIL_SERVICES.find((s) => s.id === serviceId) || null,
    [serviceId],
  );
  // Total time the chosen service + add-ons need; shown to the customer on the times step.
  const estimatedDuration = computeEstimatedDuration(service, nailArt, removal);

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

  const total = computeTotal(service, nailArt, removal);
  const addOnsLabel = formatAddOns(nailArt, removal);
  const deposit = 30;

  const currentQuoteKey = serviceId ? `${serviceId}|${nailArt}|${removal}` : null;
  const canContinue = (() => {
    if (step === 0) return !!service;
    // Gate on availability having loaded for the CURRENT quote, and on the picked time still being
    // offered for it — so a stale selection from a previous service/add-on can't be carried forward.
    if (step === 2) {
      return !!date && !!time
        && loadedQuoteKey === currentQuoteKey
        && availableTimes.includes(time);
    }
    if (step === 3) return form.fullName.trim() && form.email.trim();
    if (step === 4) return agreed;
    if (step === 5) return depositPaid;
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
        // Strip the preview data-URL + size; the API only needs filename/contentType/base64 data.
        attachments: attachments.map((a) => ({ filename: a.filename, contentType: a.contentType, data: a.data })),
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
            addOns={addOnsLabel}
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
                <DetailsStep
                  service={service}
                  addOns={addOnsLabel}
                  date={date}
                  time={time}
                  total={total}
                  deposit={deposit}
                  form={form}
                  updateForm={updateForm}
                  attachments={attachments}
                  addFiles={addFiles}
                  removeAttachment={removeAttachment}
                  attachmentError={attachmentError}
                />,
                <TermsStep agreed={agreed} setAgreed={setAgreed} bookingError={bookingError} />,
                <DepositStep
                  service={service}
                  addOns={addOnsLabel}
                  date={date}
                  time={time}
                  total={total}
                  deposit={deposit}
                  depositPaid={depositPaid}
                  setDepositPaid={setDepositPaid}
                  bookingError={bookingError}
                />,
              ][step]}
            </div>

            <footer className="bk-footer">
              {step > 0 && <button className="bk-btn bk-btn--ghost" onClick={back} type="button" disabled={submitting}>Back</button>}
              {step < 5 ? (
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
