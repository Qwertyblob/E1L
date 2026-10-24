import { useEffect, useEffectEvent, useMemo, useRef, useState } from 'react';
import './BookingModal.css';
import { useCatalog } from './CatalogContext';
import { getCalendarDays } from './slotBuilderUtils';
import { formatAddOnTime, formatServiceDuration } from './services';
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

// Sum the chosen service plus optional nail-art and removal add-ons, priced from `catalog` (the
// live catalog from CatalogContext). Missing selections contribute 0. Repairs are deliberately
// absent: they are quoted in person, so they carry no price here (and none in the server catalog).
function computeTotal(catalog, service, nailArt, removal) {
  const basePrice = service ? service.price : 0;
  const nailArtPrice = catalog.NAIL_ART.find((a) => a.id === nailArt)?.price || 0;
  const removalPrice = catalog.REMOVAL.find((r) => r.id === removal)?.price || 0;
  return basePrice + nailArtPrice + removalPrice;
}

// Repairs add no time here for the same reason they add no cost — the salon fits them in
// and quotes them on the day.
function computeEstimatedDuration(catalog, service, nailArt, removal) {
  const baseDuration = service?.durationMin || 0;
  const nailArtDuration = catalog.NAIL_ART.find((a) => a.id === nailArt)?.durationMin || 0;
  const removalDuration = catalog.REMOVAL.find((r) => r.id === removal)?.durationMin || 0;
  return baseDuration + nailArtDuration + removalDuration;
}

// Human-readable list of the chosen add-ons for the recap rows. "none" selections (the default
// nail-art/removal options) are omitted; shows "None" when nothing extra is added. Repairs are
// listed too — they cost/take nothing in the estimate but the salon still needs to see them.
function formatAddOns(catalog, nailArt, removal, repairs = []) {
  const parts = [];
  if (nailArt && nailArt !== 'none') {
    parts.push(catalog.NAIL_ART.find((a) => a.id === nailArt)?.name || nailArt);
  }
  if (removal && removal !== 'none') {
    parts.push(catalog.REMOVAL.find((r) => r.id === removal)?.name || removal);
  }
  for (const id of repairs) {
    parts.push(catalog.REPAIRS.find((r) => r.id === id)?.name || id);
  }
  return parts.length ? parts.join(', ') : 'None';
}

// Shared service/add-ons/date/time/total recap, used by the details, T&C and deposit steps and the
// success screen. `className` and `children` let each caller tweak styling and append a deposit note
// without duplicating the rows. The total is an estimate, so it is labelled "Total estimate".
function BookingSummary({ service, addOns, date, time, total, duration, className = 'bk-summary', children }) {
  return (
    <div className={className}>
      <div><span>Services</span><span>{service?.name}</span></div>
      <div><span>Add-ons</span><span>{addOns}</span></div>
      <div><span>Date</span><span>{formatLongDate(date)}</span></div>
      <div><span>Time</span><span>{time}</span></div>
      {duration != null && <div><span>Estimated duration</span><span>{duration} min</span></div>}
      <div><span>Total estimate</span><span>S${total}</span></div>
      {children}
    </div>
  );
}

// Shown when the published catalog changed mid-checkout (or the picked time stopped fitting): what
// changed, and — if the customer already paid — that they must not pay again and how to recover.
function CheckoutNotice({ notice, depositPaid, deposit }) {
  if (!notice) return null;
  return (
    <div className="bk-notice" role="status">
      <p className="bk-notice-title">{notice.title}</p>
      {notice.lines.map((line) => <p className="bk-notice-line" key={line}>{line}</p>)}
      {depositPaid && (
        <p className="bk-notice-paid">
          You've already paid your S${deposit} deposit — please do <strong>not</strong> pay again. If
          nothing suitable is left, contact us with your payment receipt and we'll reschedule your
          appointment or refund your deposit.
        </p>
      )}
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
function ServiceStep({ services, serviceId, selectService, catalogStatus }) {
  return (
    <>
      {catalogStatus === 'loading' && <p className="bk-prompt">Loading services…</p>}
      {catalogStatus === 'fallback' && (
        // Showing the bundled backup catalog: the live prices (and the booking system) are
        // unreachable right now, so don't let anyone book against possibly-outdated prices.
        <p className="bk-booking-error" role="alert">
          Online booking is temporarily unavailable. Please try again in a little while, or message us
          on Instagram to book.
        </p>
      )}
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
              <span className="bk-service-duration">🕐 {formatServiceDuration(s.durationMin)}</span>
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
function AddOnsStep({ catalog, nailArt, setNailArt, removal, setRemoval, repairs, toggleRepair, total }) {
  const { NAIL_ART, REMOVAL, REPAIRS } = catalog;
  return (
    <>
      <p className="bk-prompt">Customise your appointment. All add-ons are optional.</p>
      <p className="bk-section-label">Nail Art Design</p>
      <div className="bk-option-list">
        {NAIL_ART.map((a) => (
          <button className={`bk-option${nailArt === a.id ? ' bk-option--selected' : ''}`} key={a.id} onClick={() => setNailArt(a.id)} type="button">
            <div className="bk-option-info">
              <span className="bk-option-name">{a.name}{formatAddOnTime(a.durationMin) && <span className="bk-option-time">{formatAddOnTime(a.durationMin)}</span>}</span>
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
              <span className="bk-option-name">{r.name}{formatAddOnTime(r.durationMin) && <span className="bk-option-time">{formatAddOnTime(r.durationMin)}</span>}</span>
              {r.sub && <span className="bk-option-sub">{r.sub}</span>}
            </div>
            {r.price > 0 && <span className="bk-option-price">+S${r.price}</span>}
            {removal === r.id && <CheckIcon className="bk-option-check" />}
          </button>
        ))}
      </div>
      {/* Repairs are multi-select (unlike the single-choice sections above) and carry no price or
          duration, so the estimate below is unaffected — hence the note. */}
      <p className="bk-section-label">Repairs <span className="bk-muted">(select any that apply)</span></p>
      <div className="bk-option-list">
        {REPAIRS.map((r) => {
          const selected = repairs.includes(r.id);
          return (
            <button
              aria-pressed={selected}
              className={`bk-option${selected ? ' bk-option--selected' : ''}`}
              key={r.id}
              onClick={() => toggleRepair(r.id)}
              type="button"
            >
              <div className="bk-option-info">
                <span className="bk-option-name">{r.name}</span>
                {r.sub && <span className="bk-option-sub">{r.sub}</span>}
              </div>
              {selected && <CheckIcon className="bk-option-check" />}
            </button>
          );
        })}
      </div>
      <p className="bk-section-note">
        Repairs are quoted on the day. Their time and cost are <strong>not</strong> included in the
        estimated duration or estimated total below.
      </p>
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

// Step 4 — T&C. Accepting here approves BOTH the terms and the quote recapped above it: the
// catalog version that recap was priced from is what the booking is submitted against.
function TermsStep({ agreed, setAgreed, bookingError, service, addOns, date, time, total, duration, deposit }) {
  return (
    <>
      <BookingSummary service={service} addOns={addOns} date={date} time={time} total={total} duration={duration} />
      <div className="bk-summary-deposit">
        <span>Deposit due</span><span>S${deposit}</span>
      </div>
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
  service, addOns, date, time, total, deposit, depositPaid, setDepositPaid,
  qrError, onQrLoad, onQrError, bookingError, paidBeforeRecovery,
}) {
  return (
    <>
      <BookingSummary service={service} addOns={addOns} date={date} time={time} total={total} />
      <div className="bk-summary-deposit">
        <span>Deposit due</span><span>S${deposit}</span>
      </div>
      {paidBeforeRecovery && depositPaid && (
        // Back here after re-reviewing an updated quote: the deposit is a flat S$30 regardless of
        // prices, so the payment they already made still stands.
        <p className="bk-recovery-note" role="note">
          You've already paid your S${deposit} deposit — please do <strong>not</strong> pay again.
        </p>
      )}
      <div className="bk-paynow">
        {qrError ? (
          // The QR is the only payment method shown; if it fails to load, surface an error and
          // block confirmation (see canContinue) rather than let the customer "confirm" with no
          // way to pay.
          <p className="bk-booking-error" role="alert">
            We couldn't load the payment QR. Please refresh the page and try again, or contact us to
            arrange your S${deposit} deposit before your appointment.
          </p>
        ) : (
          <>
            <img
              alt={`PayNow S$${deposit} deposit QR code`}
              className="bk-paynow-qr"
              // Cover the cached case too: if the image is already complete on mount, its load
              // event may never fire, so confirm here as well.
              ref={(el) => { if (el && el.complete && el.naturalWidth > 0) onQrLoad(); }}
              onLoad={onQrLoad}
              onError={onQrError}
              src="/paynow-qr.png"
            />
            <p className="bk-paynow-note">
              Scan with your banking app and pay <strong>S${deposit}</strong>, using <strong>your name</strong> as
              the payment reference. Your slot is confirmed once we receive it. Unpaid deposits are
              released after 24 hours.
            </p>
          </>
        )}
      </div>
      <label className="bk-agree">
        <input checked={depositPaid} onChange={(e) => setDepositPaid(e.target.checked)} type="checkbox" />
        <span>I have paid the S${deposit} deposit.</span>
      </label>
      <p className="bk-summary-note">
        Deposit payable will be offset against your final bill upon completion of service, subject to
        compliance with our terms and conditions.
      </p>
      {bookingError && (
        <div className="bk-recovery">
          <p className="bk-booking-error">{bookingError}</p>
          {depositPaid && (
            // The customer marked the deposit paid, but the booking didn't go through. Give a
            // distinct recovery message so they don't pay again and know how to get a refund /
            // reschedule (manual, since there's no automated payment record).
            <p className="bk-recovery-note" role="alert">
              If you have already paid the S${deposit} deposit, please do <strong>not</strong> pay
              again. Contact us with your payment receipt and we'll reschedule your appointment or
              refund your deposit.
            </p>
          )}
        </div>
      )}
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
      <h2 className="bk-success-title">Booking request received 🎉</h2>
      <p className="bk-success-sub">
        Time to give your hands the love they deserve.
        <br /><br />
        We've received your request. Once your deposit is verified we'll confirm your appointment and
        email your confirmation to {maskEmail(formEmail)}. Our studio address follows in a separate
        email 2 days before your appointment.
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
  const { catalog, version, status: catalogStatus, refresh: refreshCatalog } = useCatalog();
  const now = new Date();
  const [step, setStep] = useState(0);
  const [done, setDone] = useState(false);
  const [confirmed, setConfirmed] = useState(null);

  const [serviceId, setServiceId] = useState(null);
  const [nailArt, setNailArt] = useState('none');
  const [removal, setRemoval] = useState('none');
  // Repairs are multi-select, so this is a list of ids (possibly empty) rather than a single id.
  const [repairs, setRepairs] = useState([]);
  const [calYear, setCalYear] = useState(now.getFullYear());
  const [calMonth, setCalMonth] = useState(now.getMonth());
  const [date, setDate] = useState(null);
  const [time, setTime] = useState(null);
  const [agreed, setAgreed] = useState(false);
  const [depositPaid, setDepositPaid] = useState(false);
  // The deposit QR is the only payment method on the final step; confirmation is gated on it
  // having loaded (qrLoaded && !qrError) so a missing/broken asset can't silently permit a booking.
  const [qrLoaded, setQrLoaded] = useState(false);
  const [qrError, setQrError] = useState(false);
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
  // The catalog version of the quote the customer explicitly accepted on the T&C step (with the
  // recap in front of them). The booking is submitted against THIS version — never whatever the
  // context holds at submit time — so a background refresh can't silently re-price an approved
  // quote. null = no current approval (Confirm is blocked).
  const [reviewedCatalogVersion, setReviewedCatalogVersion] = useState(null);
  const [accepting, setAccepting] = useState(false);
  // { title, lines[] } shown above the current step after a mid-checkout catalog/slot change.
  const [notice, setNotice] = useState(null);
  // True once a recovery happened after the customer ticked "I have paid", so the deposit step
  // reminds them not to pay twice.
  const [paidBeforeRecovery, setPaidBeforeRecovery] = useState(false);
  // Bumped to force an availability re-fetch for an unchanged quote (e.g. after SLOT_UNAVAILABLE).
  const [availabilityNonce, setAvailabilityNonce] = useState(0);
  // The catalog version/content the customer's current quote was built against, and the version a
  // deposit claim was last reported for (one report per catalog change, however it is detected).
  const quoteVersionRef = useRef(null);
  const quoteCatalogRef = useRef(null);
  const reportedRecoveryVersionRef = useRef(null);

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
  // fits each slot under the scheduling invariant. Re-fetch whenever the quote changes — including
  // a new catalog version, since a republish can change durations for the same selection. An
  // AbortController cancels the in-flight request and a monotonic sequence guards against a slow
  // earlier-quote response landing after a newer one, so stale data can never overwrite current.
  const availabilitySeq = useRef(0);
  // Fresh availability for the current quote. If the customer is already past the time step and
  // their picked time is no longer offered (e.g. a republish lengthened the appointment, or the slot
  // filled), send them back to choose another rather than letting them confirm a time that can't fit.
  const onAvailabilityLoaded = useEffectEvent((slots) => {
    if (done || step <= 2 || !date || !time) return;
    const stillOffered = slots.some((slot) => slot?.available !== false
      && String(slot.startTime).slice(0, 10) === date
      && String(slot.startTime).slice(11, 16) === time);
    if (!stillOffered) {
      routeToTimeStep('Your chosen time no longer fits your updated appointment. Please pick another time.');
    }
  });
  useEffect(() => {
    // No service chosen yet — nothing to check; availableSlots stays at its initial []. (serviceId
    // only ever goes null -> a real id, so there's no stale list to clear.)
    if (done || !serviceId) return undefined;
    const seq = (availabilitySeq.current += 1);
    const quoteKey = `${version}|${serviceId}|${nailArt}|${removal}`;
    const controller = new AbortController();
    const params = new URLSearchParams({ serviceId });
    if (nailArt) params.set('nailArtId', nailArt);
    if (removal) params.set('removalId', removal);
    fetch(`${API_BASE_URL}/api/slots/available?${params.toString()}`, { signal: controller.signal })
      .then((res) => (res.ok ? res.json() : []))
      .then((data) => {
        if (controller.signal.aborted || seq !== availabilitySeq.current) return;
        const slots = Array.isArray(data) ? data : [];
        setAvailableSlots(slots);
        setLoadedQuoteKey(quoteKey);
        onAvailabilityLoaded(slots);
      })
      .catch((err) => {
        if (controller.signal.aborted || err.name === 'AbortError' || seq !== availabilitySeq.current) return;
        setAvailableSlots([]);
        setLoadedQuoteKey(quoteKey);
      });
    return () => controller.abort();
  }, [done, serviceId, nailArt, removal, version, availabilityNonce]);

  const service = useMemo(
    () => catalog.NAIL_SERVICES.find((s) => s.id === serviceId) || null,
    [catalog, serviceId],
  );
  // Total time the chosen service + add-ons need; shown to the customer on the times step.
  const estimatedDuration = computeEstimatedDuration(catalog, service, nailArt, removal);

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

  const total = computeTotal(catalog, service, nailArt, removal);
  const addOnsLabel = formatAddOns(catalog, nailArt, removal, repairs);
  const deposit = 30;

  const currentQuoteKey = serviceId ? `${version}|${serviceId}|${nailArt}|${removal}` : null;
  const canContinue = (() => {
    if (step === 0) return !!service && catalogStatus === 'ready';
    // Gate on availability having loaded for the CURRENT quote, and on the picked time still being
    // offered for it — so a stale selection from a previous service/add-on can't be carried forward.
    if (step === 2) {
      return !!date && !!time
        && loadedQuoteKey === currentQuoteKey
        && availableTimes.includes(time);
    }
    if (step === 3) return form.fullName.trim() && form.email.trim();
    if (step === 4) return agreed && !accepting;
    // Confirm also needs a current approval: any catalog change clears it until re-accepted.
    if (step === 5) return depositPaid && qrLoaded && !qrError && reviewedCatalogVersion != null;
    return true;
  })();

  function routeToTimeStep(message) {
    setStep(2);
    setTime(null);
    setAgreed(false);
    setReviewedCatalogVersion(null);
    setNotice({ title: 'Please pick another time.', lines: [message] });
  }

  /**
   * The single recovery path for a catalog change after the customer has a quote — whether noticed
   * by a background refresh, by the acceptance-time refresh, or by the server rejecting the booking
   * (CATALOG_VERSION_MISMATCH). Invalidates any approval, drops selections that are no longer
   * offered, routes to the earliest affected step and explains what changed. The deposit is a flat
   * S$30 and stays marked paid; if it was, the salon is alerted once per catalog version so a
   * customer who can't (or doesn't) finish is never left paid with no booking and nobody aware.
   */
  function recoverFromCatalogChange(freshCatalog, freshVersion, { force = false } = {}) {
    const alreadyAtVersion = freshVersion === quoteVersionRef.current;
    if (alreadyAtVersion && !force) return;
    const oldCatalog = quoteCatalogRef.current || freshCatalog;
    const nameIn = (list, id) => list.find((x) => x.id === id)?.name || id;
    const oldService = oldCatalog.NAIL_SERVICES.find((s) => s.id === serviceId) || null;
    const oldTotal = computeTotal(oldCatalog, oldService, nailArt, removal);

    const removed = [];
    const nextService = serviceId ? freshCatalog.NAIL_SERVICES.find((s) => s.id === serviceId) || null : null;
    const serviceGone = Boolean(serviceId) && !nextService;
    if (serviceGone) removed.push(nameIn(oldCatalog.NAIL_SERVICES, serviceId));
    let nextNailArt = nailArt;
    if (nailArt !== 'none' && !freshCatalog.NAIL_ART.some((a) => a.id === nailArt)) {
      removed.push(nameIn(oldCatalog.NAIL_ART, nailArt));
      nextNailArt = 'none';
    }
    let nextRemoval = removal;
    if (removal !== 'none' && !freshCatalog.REMOVAL.some((r) => r.id === removal)) {
      removed.push(nameIn(oldCatalog.REMOVAL, removal));
      nextRemoval = 'none';
    }
    const keptRepairs = repairs.filter((id) => freshCatalog.REPAIRS.some((r) => r.id === id));
    repairs.filter((id) => !keptRepairs.includes(id))
      .forEach((id) => removed.push(nameIn(oldCatalog.REPAIRS, id)));

    if (serviceGone) setServiceId(null);
    if (nextNailArt !== nailArt) setNailArt(nextNailArt);
    if (nextRemoval !== removal) setRemoval(nextRemoval);
    if (keptRepairs.length !== repairs.length) setRepairs(keptRepairs);
    setAgreed(false);
    setReviewedCatalogVersion(null);

    // Earliest affected step: service → add-ons → (the T&C recap, for an already-reviewed quote).
    // The time step is re-checked separately once availability reloads for the new version.
    let target = step;
    if (serviceGone) target = 0;
    else if (removed.length) target = Math.min(step, 1);
    else if (step > 4) target = 4;
    if (target !== step) setStep(target);

    const newTotal = serviceGone ? null : computeTotal(freshCatalog, nextService, nextNailArt, nextRemoval);
    const lines = [];
    if (removed.length) lines.push(`No longer offered, so removed from your booking: ${removed.join(', ')}.`);
    if (serviceGone) lines.push('Please choose another service.');
    if (newTotal != null && serviceId && newTotal !== oldTotal) {
      lines.push(`Your estimated total changed from S$${oldTotal} to S$${newTotal}.`);
    }
    if (step >= 4 && !serviceGone) lines.push('Please review your booking and accept again.');
    if (lines.length) {
      const next = { title: 'Our prices were just updated.', lines };
      // A forced re-run for a version already handled keeps the more specific earlier notice.
      setNotice((prev) => (alreadyAtVersion && prev ? prev : next));
    }

    if (depositPaid) {
      setPaidBeforeRecovery(true);
      if (reportedRecoveryVersionRef.current !== freshVersion) {
        reportedRecoveryVersionRef.current = freshVersion;
        reportDepositClaim('Prices updated during checkout — customer re-reviewing.'
          + (removed.length ? ` No longer offered: ${removed.join(', ')}.` : ''));
      }
    }
    quoteVersionRef.current = freshVersion;
    quoteCatalogRef.current = freshCatalog;
  }

  // Track the catalog the current quote is built on; a new version (focus/visibility refresh,
  // acceptance refresh) runs the recovery above. The first ready catalog just becomes the baseline.
  const onCatalogVersion = useEffectEvent((nextCatalog, nextVersion) => {
    if (done || nextVersion == null) return;
    if (quoteVersionRef.current == null) {
      quoteVersionRef.current = nextVersion;
      quoteCatalogRef.current = nextCatalog;
      return;
    }
    recoverFromCatalogChange(nextCatalog, nextVersion);
  });
  useEffect(() => {
    onCatalogVersion(catalog, version);
  }, [catalog, version]);

  // Continue on the T&C step = explicit approval of the recapped quote. Re-check the live catalog
  // first: if it moved since the recap was rendered, this click is NOT an approval of the new
  // prices — show the updated quote and require another explicit acceptance. If the check itself
  // fails, accept what was shown; the server still rejects a stale version on submit.
  async function acceptTerms() {
    if (!canContinue) return;
    const shownVersion = version;
    setAccepting(true);
    const fresh = await refreshCatalog();
    setAccepting(false);
    if (fresh && fresh.version !== shownVersion) {
      recoverFromCatalogChange(fresh.catalog, fresh.version);
      return;
    }
    setReviewedCatalogVersion(shownVersion);
    setNotice(null);
    next();
  }

  function next() { setStep((s) => Math.min(s + 1, STEP_LABELS.length - 1)); }
  function back() { setStep((s) => Math.max(s - 1, 0)); }

  function selectService(id) {
    setServiceId(id);
  }

  // Repairs don't change the quote (no price, no duration), so toggling one never invalidates
  // the loaded availability — unlike nail art / removal, they're absent from the quote key.
  function toggleRepair(id) {
    setRepairs((prev) => (prev.includes(id) ? prev.filter((r) => r !== id) : [...prev, id]));
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

  // Best-effort, fire-and-forget report to the salon that a deposit may have been paid for a
  // booking that failed. CSRF-exempt endpoint; a failure here must never affect the recovery UX.
  function reportDepositClaim(reason) {
    fetch(`${API_BASE_URL}/api/deposit-claims`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        customerName: form.fullName,
        customerEmail: form.email,
        phone: form.phone,
        serviceName: service?.name || null,
        appointmentDate: date,
        appointmentTime: time,
        reason,
      }),
    }).catch(() => {});
  }

  async function confirmBooking() {
    if (!canContinue || submitting) return;
    setBookingError('');

    const slotId = resolveSlotId();
    if (!slotId) {
      const message = 'That time is no longer available. Please pick another slot.';
      setBookingError(message);
      // The deposit may already be paid (Confirm requires it), so alert the salon here too — this
      // early-return skips the onConfirm() call whose catch normally reports the claim.
      if (depositPaid) reportDepositClaim(message);
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
        repairIds: repairs,
        // The version the customer accepted; the server 409s (CATALOG_VERSION_MISMATCH) if the
        // live catalog has moved on, instead of booking a price they never saw.
        catalogVersion: reviewedCatalogVersion,
        date,
        time,
        // Strip the preview data-URL + size; the API only needs filename/contentType/base64 data.
        attachments: attachments.map((a) => ({ filename: a.filename, contentType: a.contentType, data: a.data })),
      });
      // Keep the accepted booking details even if the live catalog changes after confirmation.
      setConfirmed({ service, addOns: addOnsLabel, date, time, total });
      setDone(true);
    } catch (err) {
      if (err?.code === 'CATALOG_VERSION_MISMATCH') {
        // Prices changed after the customer accepted. Re-show the updated quote for re-acceptance
        // via the shared recovery (which also alerts the salon once if the deposit was paid).
        const fresh = await refreshCatalog();
        recoverFromCatalogChange(fresh?.catalog ?? catalog, fresh?.version ?? version, { force: true });
        return;
      }
      const message = err?.message || 'Could not complete your booking. Please try again.';
      // The customer marked the deposit paid but the booking didn't go through. Alert the salon
      // (best-effort) so they can reconcile the PayNow receipt and reschedule/refund.
      if (depositPaid) reportDepositClaim(message);
      if (err?.code === 'SLOT_UNAVAILABLE') {
        // The time filled up meanwhile: back to time selection with fresh availability.
        if (depositPaid) setPaidBeforeRecovery(true);
        setAvailabilityNonce((n) => n + 1);
        routeToTimeStep(message);
        return;
      }
      setBookingError(message);
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
            service={confirmed.service}
            addOns={confirmed.addOns}
            date={confirmed.date}
            time={confirmed.time}
            total={confirmed.total}
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
              <CheckoutNotice notice={notice} depositPaid={depositPaid} deposit={deposit} />
              {[
                <ServiceStep
                  services={catalog.NAIL_SERVICES}
                  serviceId={serviceId}
                  selectService={selectService}
                  catalogStatus={catalogStatus}
                />,
                <AddOnsStep
                  catalog={catalog}
                  nailArt={nailArt}
                  setNailArt={setNailArt}
                  removal={removal}
                  setRemoval={setRemoval}
                  repairs={repairs}
                  toggleRepair={toggleRepair}
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
                <TermsStep
                  agreed={agreed}
                  setAgreed={setAgreed}
                  bookingError={bookingError}
                  service={service}
                  addOns={addOnsLabel}
                  date={date}
                  time={time}
                  total={total}
                  duration={estimatedDuration}
                  deposit={deposit}
                />,
                <DepositStep
                  service={service}
                  addOns={addOnsLabel}
                  date={date}
                  time={time}
                  total={total}
                  deposit={deposit}
                  depositPaid={depositPaid}
                  setDepositPaid={setDepositPaid}
                  qrError={qrError}
                  onQrLoad={() => setQrLoaded(true)}
                  onQrError={() => setQrError(true)}
                  bookingError={bookingError}
                  paidBeforeRecovery={paidBeforeRecovery}
                />,
              ][step]}
            </div>

            <footer className="bk-footer">
              {step > 0 && <button className="bk-btn bk-btn--ghost" onClick={back} type="button" disabled={submitting}>Back</button>}
              {step < 5 ? (
                <button className={`bk-btn bk-btn--primary${step === 0 ? ' bk-btn--full' : ''}`} disabled={!canContinue} onClick={step === 4 ? acceptTerms : next} type="button">
                  {step === 4 && accepting ? 'Checking prices…' : 'Continue'}
                </button>
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
