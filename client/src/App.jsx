import { useCallback, useEffect, useRef, useState } from 'react';
import './App.css';
import BookingModal from './BookingModal';
import LandingView from './LandingView';
import ProfileView from './ProfileView';
import AuthModal from './AuthModal';
import { useSlotBuilder } from './useSlotBuilder';
import { BookingDetailModal, CancelBookingDialog } from './BookingDialogs';
import imgExpressManicure from './assets/images/Express_Manicure.jpg';
import imgClassicManicure from './assets/images/Classic_Manicure.jpg';
import imgStructuredClassic from './assets/images/Structured_Classic_Manicure.jpg';
import imgApresGelX from './assets/images/Apres_Extension.jpg';
import img1 from './assets/images/img1.jpg';
import img2 from './assets/images/img2.jpg';
import img3 from './assets/images/img3.jpg';
import img4 from './assets/images/img4.jpg';
import { useFocusTrap } from './useFocusTrap';

// Default to a relative base so requests are same-origin and get forwarded by the
// dev server's "proxy" (package.json) to the backend. Same-origin is required for the
// auth/CSRF cookies to be treated as first-party (a cross-origin localhost:8080 base
// leaves them unstored, so the JS can't read XSRF-TOKEN and writes 403). In prod, set
// VITE_API_URL only if the API is served from a different origin.
const API_BASE_URL = import.meta.env.VITE_API_URL || '';

const galleryImages = [
  imgExpressManicure,
  imgClassicManicure,
  imgStructuredClassic,
  imgApresGelX,
  img1,
  img2,
  img3,
  img4
];

const initialForm = {
  name: '',
  email: '',
  password: '',
  otp: '',
};

function readStoredUser() {
  try {
    const storedUser = localStorage.getItem('authUser');
    return storedUser ? JSON.parse(storedUser) : null;
  } catch {
    return null;
  }
}

// Spring's CookieCsrfTokenRepository writes a (non-httpOnly) XSRF-TOKEN cookie we
// must echo back in the X-XSRF-TOKEN header on every state-changing request.
function readXsrfToken() {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
  return match ? decodeURIComponent(match[1]) : '';
}

function buildRequestHeaders(options) {
  const headers = {
    'Content-Type': 'application/json',
    ...(options.headers || {}),
  };

  // Attach the CSRF token on state-changing requests so Spring's cookie-based
  // CSRF check passes. Safe (GET/HEAD/OPTIONS) requests don't need it.
  const method = (options.method || 'GET').toUpperCase();
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
    const xsrf = readXsrfToken();
    if (xsrf) {
      headers['X-XSRF-TOKEN'] = xsrf;
    }
  }

  return headers;
}

// Error thrown by parseApiResponse for any non-2xx response. Carries the HTTP status
// so callers can tell an auth rejection (401/403 — cookie gone/invalid) apart from a
// transient failure (5xx, or a fetch that never yields a response). refreshProfile
// relies on this to avoid signing a user out over a network blip.
class ApiError extends Error {
  constructor(message, status) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
  }
}

async function parseApiResponse(response) {
  const responseText = await response.text();
  let data;
  try {
    data = responseText ? JSON.parse(responseText) : null;
  } catch {
    data = { message: responseText };
  }

  if (!response.ok) {
    throw new ApiError(
      data?.message || data?.detail || `Request failed with status ${response.status}.`,
      response.status,
    );
  }

  return data;
}

// Empty strings/undefined become null so the server stores absent fields as
// null rather than "". Centralizes the `value || null` rule for every field.
function blankToNull(value) {
  return value || null;
}

function buildBookingPayload({ slotId, form, serviceId, nailArtId, removalId, attachments }) {
  const f = form || {};
  return {
    slotId,
    customerName: blankToNull(f.fullName),
    customerEmail: blankToNull(f.email),
    phone: blankToNull(f.phone),
    instagram: blankToNull(f.instagram),
    notes: blankToNull(f.notes),
    // Send selection IDs only; the server recomputes the service name,
    // add-on names and total price from its catalog (client values are not trusted).
    serviceId: blankToNull(serviceId),
    nailArtId: blankToNull(nailArtId),
    removalId: blankToNull(removalId),
    // Optional inspo images ({ filename, contentType, data }). The server forwards these to the
    // salon inbox as email attachments and never stores them. Omit the key when there are none.
    attachments: attachments && attachments.length ? attachments : null,
  };
}

function App() {
  const [mode, setMode] = useState('login');
  const [showAuth, setShowAuth] = useState(false);
  const [activeView, setActiveView] = useState('landing');
  const [profileTab, setProfileTab] = useState('bookings');
  const [scheduleView, setScheduleView] = useState('list');
  const [scheduleFilter, setScheduleFilter] = useState('upcoming');
  const [scheduleCal, setScheduleCal] = useState(() => {
    const now = new Date();
    return { year: now.getFullYear(), month: now.getMonth(), selected: null };
  });
  const [bookingDetail, setBookingDetail] = useState(null);
  const [pendingScroll, setPendingScroll] = useState(null);
  const [galleryIndex, setGalleryIndex] = useState(0);
  const [bookingOpen, setBookingOpen] = useState(false);
  const [form, setForm] = useState(initialForm);
  // Auth is now carried by an httpOnly cookie the JS can't read; `user` (confirmed via
  // GET /api/me) is the single source of truth for "is someone signed in".
  const [user, setUser] = useState(readStoredUser);
  const [message, setMessage] = useState('');
  const [messageType, setMessageType] = useState('error');
  const [pendingVerificationEmail, setPendingVerificationEmail] = useState('');
  const [adminUsers, setAdminUsers] = useState([]);
  const [adminUsersMeta, setAdminUsersMeta] = useState({ number: 0, totalPages: 0, totalElements: 0 });
  const [adminError, setAdminError] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [isLoadingAdmin, setIsLoadingAdmin] = useState(false);
  const [changePasswordForm, setChangePasswordForm] = useState({ currentPassword: '', newPassword: '' });
  const [changePasswordMessage, setChangePasswordMessage] = useState('');
  const [changePasswordMessageType, setChangePasswordMessageType] = useState('error');
  const [isChangingPassword, setIsChangingPassword] = useState(false);
  const [deleteAccountMessage, setDeleteAccountMessage] = useState('');
  const [isDeletingAccount, setIsDeletingAccount] = useState(false);
  const [signOutMessage, setSignOutMessage] = useState('');

  const [bookingActionMessage, setBookingActionMessage] = useState('');
  const [bookingActionMessageType, setBookingActionMessageType] = useState('error');
  const [confirmCancelId, setConfirmCancelId] = useState(null);
  const [isCancelling, setIsCancelling] = useState(false);
  const [bookingsFilter, setBookingsFilter] = useState('upcoming');

  const [myBookings, setMyBookings] = useState([]);
  const [myBookingsError, setMyBookingsError] = useState('');
  const [isLoadingMyBookings, setIsLoadingMyBookings] = useState(false);

  const [adminSlots, setAdminSlots] = useState([]);
  const [adminSlotsError, setAdminSlotsError] = useState('');
  // Archived slots are fetched on demand (when the admin opens the Archived tab),
  // never alongside the regular slot list — the archive only grows over time.
  const [archivedSlots, setArchivedSlots] = useState([]);
  const [archivedSlotsError, setArchivedSlotsError] = useState('');
  const [isLoadingArchivedSlots, setIsLoadingArchivedSlots] = useState(false);
  const [archivedSlotsLoaded, setArchivedSlotsLoaded] = useState(false);
  // The archive is paged (server caps page size at 100): track the last loaded page
  // and the server-side total so the UI can offer "load more" and show a real count.
  const [archivedSlotsPage, setArchivedSlotsPage] = useState(0);
  const [archivedSlotsTotal, setArchivedSlotsTotal] = useState(0);
  const [isLoadingAdminSlots, setIsLoadingAdminSlots] = useState(false);

  const [adminBookings, setAdminBookings] = useState([]);
  const [adminBookingsError, setAdminBookingsError] = useState('');
  const [isLoadingAdminBookings, setIsLoadingAdminBookings] = useState(false);

  const authModalRef = useFocusTrap(showAuth, () => { setShowAuth(false); setMode('login'); setMessage(''); });
  const cancelDialogRef = useFocusTrap(confirmCancelId != null, () => setConfirmCancelId(null));
  const bookingDetailRef = useFocusTrap(bookingDetail != null, () => setBookingDetail(null));

  const apiRequest = useCallback(
    async (path, options = {}) => {
      const response = await fetch(`${API_BASE_URL}${path}`, {
        ...options,
        // Send/receive the httpOnly auth cookie and the XSRF-TOKEN cookie cross-origin.
        credentials: 'include',
        headers: buildRequestHeaders(options),
      });

      return parseApiResponse(response);
    },
    [],
  );

  const {
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
  } = useSlotBuilder({ apiRequest, onSlotsCreated: loadAdminSlots });

  // Monotonic counter bumped on every auth-state transition (saveSession / clearSession).
  // refreshProfile snapshots it before its request and discards any result whose generation
  // is no longer current, so a slow or reordered GET /api/me can't clobber a newer auth state
  // (a stale 401 erasing a fresh login, or a stale 200 resurrecting a session after logout).
  const authGenerationRef = useRef(0);

  // Local-only teardown of client auth state. Used both by an explicit sign-out and
  // when the server says we're no longer authenticated (GET /api/me 401). It does NOT
  // call the logout endpoint — see signOut for that.
  const clearSession = useCallback(() => {
    authGenerationRef.current += 1;
    localStorage.removeItem('authUser');
    setUser(null);
    setAdminUsers([]);
    setAdminError('');
    setMessage('');
    setMessageType('error');
    setPendingVerificationEmail('');
    setChangePasswordForm({ currentPassword: '', newPassword: '' });
    setChangePasswordMessage('');
    setDeleteAccountMessage('');
    setIsDeletingAccount(false);
    setSignOutMessage('');
    setBookingActionMessage('');
    setMyBookings([]);
    setMyBookingsError('');
    resetSlotBuilder();
    setAdminSlots([]);
    setAdminSlotsError('');
    setArchivedSlots([]);
    setArchivedSlotsError('');
    setArchivedSlotsLoaded(false);
    setArchivedSlotsPage(0);
    setArchivedSlotsTotal(0);
    setAdminBookings([]);
    setAdminBookingsError('');
    setActiveView('landing');
    setProfileTab('bookings');
    setShowAuth(false);
    setMode('login');
  }, [resetSlotBuilder]);

  // Explicit sign-out: the httpOnly auth cookie can only be cleared by the server, so we
  // wait for the logout call to succeed before tearing down local state. If it fails
  // (network/server error), keep the session — showing a signed-out UI while the cookie
  // is still live would be a lie — and surface the error so the user can retry.
  const signOut = useCallback(async () => {
    setSignOutMessage('');
    try {
      await apiRequest('/api/auth/logout', { method: 'POST' });
      clearSession();
    } catch (error) {
      setSignOutMessage(error.message || 'Could not sign out. Please try again.');
    }
  }, [apiRequest, clearSession]);

  // Login/verify now return only the user object (the JWT is set as an httpOnly cookie
  // by the server and never reaches JS).
  const saveSession = useCallback((authUser) => {
    authGenerationRef.current += 1;
    localStorage.setItem('authUser', JSON.stringify(authUser));
    setUser(authUser);
    setPendingVerificationEmail('');
    setShowAuth(false);
  }, []);

  const refreshProfile = useCallback(async () => {
    // Snapshot the auth generation before the request. If a login (saveSession) or a teardown
    // (clearSession) bumps it while GET /api/me is in flight, this result is stale and must be
    // dropped for BOTH outcomes — otherwise a late 401 could erase a fresh login, or a late 200
    // could resurrect a session after logout.
    const generation = authGenerationRef.current;
    try {
      const profile = await apiRequest('/api/me');
      if (generation !== authGenerationRef.current) return;
      localStorage.setItem('authUser', JSON.stringify(profile));
      setUser(profile);
    } catch (error) {
      if (generation !== authGenerationRef.current) return;
      // Only an explicit auth rejection (401 anonymous/expired cookie, 403 forbidden) means
      // the session is truly gone — tear it down so no stale user lingers. A transient
      // failure (5xx, or a fetch that never reached the server, which has no .status) is
      // NOT a sign-out signal: keep whatever session we have so a blip doesn't log the user
      // out mid-visit; the next refresh recovers.
      if (error instanceof ApiError && (error.status === 401 || error.status === 403)) {
        clearSession();
      }
    }
  }, [apiRequest, clearSession]);

  // Runs once on mount: confirms auth from the cookie via GET /api/me, and seeds the
  // XSRF-TOKEN cookie (the server sets it on this response) before any write request.
  useEffect(() => {
    // Intentional: seed auth/CSRF state from the cookie on mount (async, sets state
    // after the fetch). react-hooks/set-state-in-effect can't see past the callback.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    refreshProfile();
  }, [refreshProfile]);

  const loadMyBookings = useCallback(async () => {
    if (!user) return;
    setMyBookingsError('');
    setIsLoadingMyBookings(true);
    try {
      const data = await apiRequest('/api/bookings/my');
      setMyBookings(data);
    } catch (error) {
      setMyBookingsError(error.message);
      setMyBookings([]);
    } finally {
      setIsLoadingMyBookings(false);
    }
  }, [apiRequest, user]);

  useEffect(() => {
    // Intentional data-loading effect: loadMyBookings sets loading/error state as it
    // fetches the signed-in user's bookings whenever the user changes.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    loadMyBookings();
  }, [loadMyBookings]);

  function updateField(event) {
    setForm((currentForm) => ({
      ...currentForm,
      [event.target.name]: event.target.value,
    }));
  }

  function updateChangePasswordField(event) {
    setChangePasswordForm((current) => ({
      ...current,
      [event.target.name]: event.target.value,
    }));
  }

  useEffect(() => {
    if (pendingScroll && activeView === 'landing') {
      const el = document.getElementById(pendingScroll);
      if (el) el.scrollIntoView({ behavior: 'smooth' });
      // Intentional: clear the one-shot scroll request after performing the scroll.
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setPendingScroll(null);
    }
  }, [pendingScroll, activeView]);

  useEffect(() => {
    const timer = setTimeout(() => {
      setGalleryIndex((current) => (current + 1) % galleryImages.length);
    }, 6000);
    return () => clearTimeout(timer);
  }, [galleryIndex]);

  useEffect(() => {
    if (!showAuth) return undefined;
    const prevOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => { document.body.style.overflow = prevOverflow; };
  }, [showAuth]);

  function prevSlide() {
    setGalleryIndex((current) => (current - 1 + galleryImages.length) % galleryImages.length);
  }

  function nextSlide() {
    setGalleryIndex((current) => (current + 1) % galleryImages.length);
  }

  function scrollToSection(id) {
    if (activeView === 'profile') {
      setActiveView('landing');
      setPendingScroll(id);
    } else {
      document.getElementById(id)?.scrollIntoView({ behavior: 'smooth' });
    }
  }

  function openBooking() {
    if (user) {
      setActiveView('profile');
      window.scrollTo(0, 0);
    } else {
      setShowAuth(true);
      setMode('login');
      setMessage('');
      window.scrollTo(0, 0);
    }
  }

  function openBookingModal() {
    setBookingOpen(true);
  }

  function selectProfileTab(tab) {
    setProfileTab(tab);
    if (tab === 'schedule') {
      loadAdminBookings();
      loadAdminSlots();
    }
  }

  function switchMode(nextMode, nextForm = initialForm) {
    setMode(nextMode);
    setMessage('');
    setMessageType('error');
    setPendingVerificationEmail('');
    setForm(nextForm);
  }

  async function handleForgotPassword(event) {
    event.preventDefault();
    setMessage('');
    setIsSubmitting(true);

    try {
      const result = await apiRequest('/api/auth/forgot-password', {
        method: 'POST',
        body: JSON.stringify({ email: form.email }),
      });
      // Move to the reset-code step; the server emails a code without changing the password.
      setMode('resetPassword');
      setForm({ ...initialForm, email: form.email });
      setMessage(result.message);
      setMessageType('success');
    } catch (error) {
      setMessage(error.message);
      setMessageType('error');
    } finally {
      setIsSubmitting(false);
    }
  }

  async function handleResetPassword(event) {
    event.preventDefault();
    setMessage('');
    setIsSubmitting(true);

    try {
      const result = await apiRequest('/api/auth/reset-password', {
        method: 'POST',
        body: JSON.stringify({ email: form.email, otp: form.otp, newPassword: form.password }),
      });
      switchMode('login', { ...initialForm, email: form.email });
      setMessage(result.message);
      setMessageType('success');
    } catch (error) {
      setMessage(error.message);
      setMessageType('error');
    } finally {
      setIsSubmitting(false);
    }
  }

  async function handleSubmit(event) {
    event.preventDefault();
    setMessage('');
    setIsSubmitting(true);

    try {
      if (mode === 'register') {
        const result = await apiRequest('/api/auth/register', {
          method: 'POST',
          body: JSON.stringify({
            name: form.name,
            email: form.email,
            password: form.password,
          }),
        });

        const verificationEmail = result.email || form.email;
        setMode('verify');
        setMessage(result.message);
        setMessageType('success');
        setPendingVerificationEmail(verificationEmail);
        setForm({ ...initialForm, email: verificationEmail });
        return;
      }

      const endpoint = mode === 'verify' ? '/api/auth/verify-account' : '/api/auth/login';
      const verificationEmail = pendingVerificationEmail || form.email;
      const body = mode === 'verify'
        ? { email: verificationEmail, otp: form.otp }
        : { email: form.email, password: form.password };
      const signedInUser = await apiRequest(endpoint, {
        method: 'POST',
        body: JSON.stringify(body),
      });

      saveSession(signedInUser);
      setForm(initialForm);
    } catch (error) {
      setMessage(error.message);
      setMessageType('error');
      if (error.message === 'Account is not verified.') {
        setPendingVerificationEmail(form.email);
        setMode('verify');
        setForm({ ...initialForm, email: form.email });
      } else if (mode === 'verify') {
        setForm({ ...form, otp: '' });
      }
    } finally {
      setIsSubmitting(false);
    }
  }

  async function resendVerificationOtp() {
    setMessage('');
    setIsSubmitting(true);

    try {
      const result = await apiRequest('/api/auth/resend-verification-otp', {
        method: 'POST',
        body: JSON.stringify({ email: pendingVerificationEmail || form.email }),
      });
      setMessage(result.message);
      setMessageType('success');
    } catch (error) {
      setMessage(error.message);
      setMessageType('error');
    } finally {
      setIsSubmitting(false);
    }
  }

  async function handleChangePassword(event) {
    event.preventDefault();
    setChangePasswordMessage('');
    setIsChangingPassword(true);

    try {
      await apiRequest('/api/me/change-password', {
        method: 'POST',
        body: JSON.stringify({
          currentPassword: changePasswordForm.currentPassword,
          newPassword: changePasswordForm.newPassword,
        }),
      });
      // The server invalidates this session on a successful change (it clears the auth cookie),
      // so tear down local state instead of leaving a stale "signed in" UI, and prompt the user
      // to sign in again with the new password.
      clearSession();
      setShowAuth(true);
      setMessage('Your password was changed. Please sign in again.');
      setMessageType('success');
    } catch (error) {
      setChangePasswordMessage(error.message);
      setChangePasswordMessageType('error');
    } finally {
      setIsChangingPassword(false);
    }
  }

  async function handleDeleteAccount() {
    setDeleteAccountMessage('');
    setIsDeletingAccount(true);

    try {
      await apiRequest('/api/me', { method: 'DELETE' });
      // The account is gone and the server has cleared the auth cookie, so tear down local
      // state and return the now-anonymous visitor to the landing page.
      clearSession();
      setMessage('Your account has been deleted.');
      setMessageType('success');
    } catch (error) {
      setDeleteAccountMessage(error.message);
      setIsDeletingAccount(false);
    }
  }

  function formatDate(iso) {
    // Slot/booking times are stored as the admin's entered wall-clock (persisted as
    // UTC), so render them in UTC to show exactly what was set, regardless of the
    // viewer's local timezone.
    return new Date(iso).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short', timeZone: 'UTC' });
  }

  function formatTimestamp(iso) {
    // Real machine instants (e.g. when a booking/slot was created) — show in the
    // viewer's local timezone so it reflects the actual moment it happened.
    return new Date(iso).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' });
  }

  function statusClass(status) {
    if (status === 'BOOKED') return 'status-booked';
    if (status === 'COMPLETED') return 'status-completed';
    return 'status-cancelled';
  }

  async function confirmCancelBooking() {
    if (confirmCancelId == null) return;
    setBookingActionMessage('');
    setIsCancelling(true);
    try {
      await apiRequest(`/api/bookings/${confirmCancelId}/cancel`, { method: 'POST' });
      setBookingActionMessage('Booking cancelled.');
      setBookingActionMessageType('success');
      loadMyBookings();
      setConfirmCancelId(null);
    } catch (error) {
      setBookingActionMessage(error.message);
      setBookingActionMessageType('error');
      setConfirmCancelId(null);
    } finally {
      setIsCancelling(false);
    }
  }

  async function loadAdminSlots() {
    setAdminSlotsError('');
    setIsLoadingAdminSlots(true);
    try {
      // The manage-slots calendar groups/filters the whole set client-side, so pull a large page.
      const data = await apiRequest('/api/slots?page=0&size=1000');
      setAdminSlots(data.content);
    } catch (error) {
      setAdminSlotsError(error.message);
      setAdminSlots([]);
    } finally {
      setIsLoadingAdminSlots(false);
    }
  }

  // page 0 (re)loads the archive from the top; higher pages append, so "load more"
  // extends the list instead of replacing it. The server caps the page size at 100.
  async function loadArchivedSlots(page = 0) {
    setArchivedSlotsError('');
    setIsLoadingArchivedSlots(true);
    try {
      const data = await apiRequest(`/api/admin/slots/archived?page=${page}&size=100`);
      setArchivedSlots((prev) => (page === 0 ? data.content : [...prev, ...data.content]));
      setArchivedSlotsPage(data.number);
      setArchivedSlotsTotal(data.totalElements);
      setArchivedSlotsLoaded(true);
    } catch (error) {
      setArchivedSlotsError(error.message);
      if (page === 0) setArchivedSlots([]);
    } finally {
      setIsLoadingArchivedSlots(false);
    }
  }

  async function handleDeleteSlot(slotId) {
    try {
      await apiRequest(`/api/admin/slots/${slotId}`, { method: 'DELETE' });
      loadAdminSlots();
      // Keep the archived tab in sync too, but only if it has ever been fetched.
      if (archivedSlotsLoaded) loadArchivedSlots();
    } catch (error) {
      setAdminSlotsError(error.message);
    }
  }

  async function loadAdminBookings() {
    setAdminBookingsError('');
    setIsLoadingAdminBookings(true);
    try {
      // The schedule calendar groups/filters the whole set client-side, so pull a large page.
      const data = await apiRequest('/api/admin/bookings?page=0&size=1000');
      setAdminBookings(data.content);
    } catch (error) {
      setAdminBookingsError(error.message);
      setAdminBookings([]);
    } finally {
      setIsLoadingAdminBookings(false);
    }
  }

  async function handleAdminCancelBooking(bookingId) {
    try {
      await apiRequest(`/api/admin/bookings/${bookingId}/cancel`, { method: 'POST' });
      loadAdminBookings();
    } catch (error) {
      setAdminBookingsError(error.message);
    }
  }

  async function handleAdminCompleteBooking(bookingId) {
    try {
      await apiRequest(`/api/admin/bookings/${bookingId}/complete`, { method: 'POST' });
      loadAdminBookings();
    } catch (error) {
      setAdminBookingsError(error.message);
    }
  }

  async function loadAdminUsers(page = 0) {
    setAdminError('');
    setIsLoadingAdmin(true);

    try {
      const data = await apiRequest(`/api/admin/users?page=${page}&size=50`);
      setAdminUsers(data.content);
      setAdminUsersMeta({ number: data.number, totalPages: data.totalPages, totalElements: data.totalElements });
    } catch (error) {
      setAdminUsers([]);
      setAdminError(error.message);
    } finally {
      setIsLoadingAdmin(false);
    }
  }

  const isAdmin = user?.role === 'ADMIN';
  const SCHEDULE_STATUS = { upcoming: 'BOOKED', completed: 'COMPLETED', cancelled: 'CANCELLED' };
  // Calendar only surfaces upcoming (still-active) bookings.
  const upcomingBookings = adminBookings.filter((b) => b.status === 'BOOKED');
  const bookingsByDate = upcomingBookings.reduce((acc, b) => {
    const day = b.slotStartTime ? String(b.slotStartTime).slice(0, 10) : '';
    if (!day) return acc;
    (acc[day] = acc[day] || []).push(b);
    return acc;
  }, {});
  const scheduleSelectedBookings = scheduleView === 'calendar'
    ? (scheduleCal.selected ? (bookingsByDate[scheduleCal.selected] || []) : upcomingBookings)
    : adminBookings.filter((b) => b.status === SCHEDULE_STATUS[scheduleFilter]);

  async function handleConfirmBooking(selection) {
    // Logged-in bookings use the CSRF-protected endpoint; guests use the public one.
    const endpoint = user ? '/api/bookings/me' : '/api/bookings';
    await apiRequest(endpoint, {
      method: 'POST',
      body: JSON.stringify(buildBookingPayload(selection)),
    });
    loadMyBookings();
  }

  const bookingModalEl = bookingOpen && (
    <BookingModal
      currentUser={user}
      onClose={() => setBookingOpen(false)}
      onConfirm={handleConfirmBooking}
    />
  );

  const cancelConfirmEl = (
    <CancelBookingDialog
      confirmCancelId={confirmCancelId}
      cancelDialogRef={cancelDialogRef}
      isCancelling={isCancelling}
      onKeep={() => setConfirmCancelId(null)}
      onConfirm={confirmCancelBooking}
    />
  );

  const bookingDetailEl = (
    <BookingDetailModal
      bookingDetail={bookingDetail}
      bookingDetailRef={bookingDetailRef}
      statusClass={statusClass}
      formatDate={formatDate}
      formatTimestamp={formatTimestamp}
      onClose={() => setBookingDetail(null)}
      onComplete={(id) => { handleAdminCompleteBooking(id); setBookingDetail(null); }}
      onCancel={(id) => { handleAdminCancelBooking(id); setBookingDetail(null); }}
    />
  );

  if (user && activeView === 'profile') {
    return (
      <ProfileView
        user={user}
        isAdmin={isAdmin}
        profileTab={profileTab}
        setActiveView={setActiveView}
        scrollToSection={scrollToSection}
        openBookingModal={openBookingModal}
        bookingModalEl={bookingModalEl}
        cancelConfirmEl={cancelConfirmEl}
        bookingDetailEl={bookingDetailEl}
        selectProfileTab={selectProfileTab}
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
    );
  }

  const authModal = (
    <AuthModal
      showAuth={showAuth}
      authModalRef={authModalRef}
      mode={mode}
      switchMode={switchMode}
      setShowAuth={setShowAuth}
      setMode={setMode}
      setMessage={setMessage}
      message={message}
      messageType={messageType}
      form={form}
      updateField={updateField}
      pendingVerificationEmail={pendingVerificationEmail}
      isSubmitting={isSubmitting}
      handleSubmit={handleSubmit}
      handleForgotPassword={handleForgotPassword}
      handleResetPassword={handleResetPassword}
      resendVerificationOtp={resendVerificationOtp}
      initialForm={initialForm}
    />
  );

  return (
    <LandingView
      user={user}
      authModal={authModal}
      bookingModalEl={bookingModalEl}
      scrollToSection={scrollToSection}
      onProfileClick={() => { setActiveView('profile'); window.scrollTo(0, 0); }}
      onSignIn={openBooking}
      onBook={openBookingModal}
      galleryImages={galleryImages}
      galleryIndex={galleryIndex}
      onPrevSlide={prevSlide}
      onNextSlide={nextSlide}
      onSelectSlide={setGalleryIndex}
    />
  );
}

export default App;
