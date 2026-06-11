function getAuthHeading(mode) {
  if (mode === 'register') {
    return 'Register';
  }
  if (mode === 'verify') {
    return 'Verify account';
  }
  if (mode === 'forgotPassword') {
    return 'Reset password';
  }
  if (mode === 'resetPassword') {
    return 'Set a new password';
  }
  return 'Sign in';
}

function getAuthEyebrow(mode) {
  if (mode === 'register') {
    return 'Create account';
  }
  if (mode === 'verify') {
    return 'Verification';
  }
  if (mode === 'forgotPassword' || mode === 'resetPassword') {
    return 'Account recovery';
  }
  return 'Welcome back';
}

function getSubmitText(mode, isSubmitting) {
  if (isSubmitting) {
    return 'Please wait';
  }
  if (mode === 'register') {
    return 'Create account';
  }
  if (mode === 'verify') {
    return 'Verify account';
  }
  if (mode === 'forgotPassword') {
    return 'Send reset code';
  }
  if (mode === 'resetPassword') {
    return 'Reset password';
  }
  return 'Sign in';
}

// Each recovery mode submits to its own handler; everything else uses the
// shared login/register submit.
function resolveSubmitHandler(mode, handleForgotPassword, handleResetPassword, handleSubmit) {
  if (mode === 'forgotPassword') {
    return handleForgotPassword;
  }
  if (mode === 'resetPassword') {
    return handleResetPassword;
  }
  return handleSubmit;
}

function ModeSwitch({ mode, switchMode }) {
  return (
    <div className="mode-switch" role="tablist" aria-label="Authentication mode">
      <button
        className={mode === 'login' || mode === 'forgotPassword' || mode === 'resetPassword' ? 'active' : ''}
        onClick={() => switchMode('login')}
        type="button"
      >
        Sign in
      </button>
      <button
        className={mode === 'register' || mode === 'verify' ? 'active' : ''}
        onClick={() => switchMode('register')}
        type="button"
      >
        Register
      </button>
    </div>
  );
}

// Mode-dependent form fields (name, email, OTP, password, hints). Each block is
// gated on the active mode so only the relevant inputs render.
function AuthFields({ mode, form, updateField, pendingVerificationEmail }) {
  return (
    <>
      {mode === 'register' && (
        <label>
          <span>Name</span>
          <input
            autoComplete="name"
            name="name"
            onChange={updateField}
            placeholder="Jane Lee"
            required
            type="text"
            value={form.name}
          />
        </label>
      )}

      {mode !== 'verify' && (
        <label>
          <span>Email</span>
          <input
            autoComplete="email"
            name="email"
            onChange={updateField}
            placeholder="jane@example.com"
            required
            type="email"
            value={form.email}
          />
        </label>
      )}

      {mode === 'forgotPassword' && (
        <p className="form-hint">
          We'll email a password reset code to this address.
        </p>
      )}

      {mode === 'resetPassword' && (
        <>
          <p className="form-hint">
            Enter the reset code we emailed you and choose a new password.
          </p>
          <label>
            <span>Reset code</span>
            <input
              autoComplete="one-time-code"
              inputMode="numeric"
              maxLength="6"
              name="otp"
              onChange={updateField}
              pattern="[0-9]{6}"
              placeholder="000000"
              required
              type="text"
              value={form.otp}
            />
          </label>
        </>
      )}

      {mode === 'verify' && (
        <div className="verification-target">
          <span>Code sent to</span>
          <strong>{pendingVerificationEmail || form.email}</strong>
        </div>
      )}

      {mode !== 'verify' && mode !== 'forgotPassword' && (
        <label>
          <span>{mode === 'resetPassword' ? 'New password' : 'Password'}</span>
          <input
            autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
            minLength="8"
            name="password"
            onChange={updateField}
            placeholder="At least 8 characters"
            required
            type="password"
            value={form.password}
          />
        </label>
      )}

      {mode === 'verify' && (
        <label>
          <span>Verification code</span>
          <input
            autoComplete="one-time-code"
            inputMode="numeric"
            maxLength="6"
            name="otp"
            onChange={updateField}
            pattern="[0-9]{6}"
            placeholder="000000"
            required
            type="text"
            value={form.otp}
          />
        </label>
      )}
    </>
  );
}

// Primary submit plus mode-specific secondary actions (resend code, forgot
// password, back to sign in).
function AuthActions({ mode, isSubmitting, pendingVerificationEmail, form, switchMode, resendVerificationOtp, initialForm }) {
  return (
    <div className="auth-actions">
      <button className="primary-button" disabled={isSubmitting} type="submit">
        {getSubmitText(mode, isSubmitting)}
      </button>
      {mode === 'verify' && (
        <button
          className="text-button"
          disabled={isSubmitting || !(pendingVerificationEmail || form.email)}
          onClick={resendVerificationOtp}
          type="button"
        >
          Resend code
        </button>
      )}
      {mode === 'login' && (
        <button
          className="text-button"
          onClick={() => switchMode('forgotPassword', { ...initialForm, email: form.email })}
          type="button"
        >
          Forgot password?
        </button>
      )}
      {(mode === 'forgotPassword' || mode === 'resetPassword') && (
        <button
          className="text-button"
          onClick={() => switchMode('login')}
          type="button"
        >
          Back to sign in
        </button>
      )}
    </div>
  );
}

function AuthModal({
  showAuth,
  authModalRef,
  mode,
  switchMode,
  setShowAuth,
  setMode,
  setMessage,
  message,
  messageType,
  form,
  updateField,
  pendingVerificationEmail,
  isSubmitting,
  handleSubmit,
  handleForgotPassword,
  handleResetPassword,
  resendVerificationOtp,
  initialForm,
}) {
  if (!showAuth) return null;

  return (
    <div className="auth-modal-backdrop">
      <div className="auth-modal" ref={authModalRef} tabIndex={-1} role="dialog" aria-modal="true" aria-label="Member access">
        <div className="auth-modal-bar">
          <button
            className="auth-modal-close"
            onClick={() => { setShowAuth(false); setMode('login'); setMessage(''); }}
            type="button"
            aria-label="Close"
          >
            &times;
          </button>
        </div>
        <ModeSwitch mode={mode} switchMode={switchMode} />

        <form
          className="auth-form"
          onSubmit={resolveSubmitHandler(mode, handleForgotPassword, handleResetPassword, handleSubmit)}
        >
          <div>
            <p className="eyebrow">{getAuthEyebrow(mode)}</p>
            <h2>{getAuthHeading(mode)}</h2>
          </div>

          <AuthFields
            mode={mode}
            form={form}
            updateField={updateField}
            pendingVerificationEmail={pendingVerificationEmail}
          />

          {message && <p className={`form-message ${messageType}`}>{message}</p>}

          <AuthActions
            mode={mode}
            isSubmitting={isSubmitting}
            pendingVerificationEmail={pendingVerificationEmail}
            form={form}
            switchMode={switchMode}
            resendVerificationOtp={resendVerificationOtp}
            initialForm={initialForm}
          />
        </form>
      </div>
    </div>
  );
}

export default AuthModal;
