import React, { useState } from 'react';
import { Link } from 'react-router-dom';
import api from '../api/client';

type Step = 'details' | 'code' | 'done';

const inputClass =
  'w-full px-3 py-2 bg-surface-elevated border border-border rounded text-xs text-primary-text focus:outline-none focus:border-accent';
const labelClass = 'block text-[11px] text-secondary-text mb-1';
const buttonClass =
  'w-full py-2.5 rounded bg-accent text-white text-xs font-medium disabled:opacity-50 disabled:cursor-not-allowed';

function errorText(err: any, fallback: string): string {
  return err?.response?.data?.message || fallback;
}

/**
 * Lets a patient who visited reception turn their walk-in record into an online account.
 * Ownership is proven by the email code plus the mobile number and date of birth on file.
 * Every failure shows the same generic message, so nothing reveals which detail was wrong.
 */
export const ActivateWalkInPage: React.FC = () => {
  const [step, setStep] = useState<Step>('details');
  const [email, setEmail] = useState('');
  const [mobile, setMobile] = useState('');
  const [dob, setDob] = useState('');
  const [code, setCode] = useState('');
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);

  // Cooldown is stored in localStorage, so a refresh or tab switch does not reset it.
  const COOLDOWN_MS = 60000;
  const cooldownKey = 'cq_otp_next_claim:' + email.trim().toLowerCase();
  const secondsLeft = () => {
    const next = Number(localStorage.getItem(cooldownKey) || 0);
    return Math.max(0, Math.ceil((next - Date.now()) / 1000));
  };

  const sendCode = async () => {
    const wait = secondsLeft();
    if (wait > 0) {
      setStep('code');
      setInfo('A code was already sent. Please wait ' + wait + ' seconds before requesting another.');
      return;
    }
    setBusy(true);
    setError(null);
    setInfo(null);
    try {
      const res = await api.post('/otp/claim-walkin/request', { email, mobile, dateOfBirth: dob });
      localStorage.setItem(cooldownKey, String(Date.now() + COOLDOWN_MS));
      setInfo(res.data?.message || 'If a matching record exists, a verification code has been sent.');
      setStep('code');
    } catch (err: any) {
      setError(errorText(err, 'We could not send the code right now. Please try again later.'));
    } finally {
      setBusy(false);
    }
  };

  const onSendCode = (e: React.FormEvent) => {
    e.preventDefault();
    sendCode();
  };

  const onActivate = async (e: React.FormEvent) => {
    e.preventDefault();
    if (password.length < 8) {
      setError('Password must be at least 8 characters.');
      return;
    }
    if (password !== confirm) {
      setError('The two passwords do not match.');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await api.post('/otp/claim-walkin/activate', {
        email,
        mobile,
        dateOfBirth: dob,
        code,
        newPassword: password,
      });
      setStep('done');
    } catch (err: any) {
      setError(errorText(err, 'We could not activate this record. Check the details, or contact reception.'));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="min-h-screen flex items-center justify-center p-4">
      <div className="w-full max-w-sm bg-surface border border-border rounded-lg p-6 space-y-4">
        <div>
          <h1 className="text-lg font-bold text-primary-text">Activate your online account</h1>
          <p className="text-[11px] text-secondary-text mt-1">
            Visited our reception before? Activate the account we created for you, so you can log in and see your
            appointments.
          </p>
        </div>

        {step === 'details' && (
          <form onSubmit={onSendCode} className="space-y-3">
            <div>
              <label className={labelClass}>Email you want to use *</label>
              <input className={inputClass} type="email" value={email} onChange={(e) => setEmail(e.target.value)} required maxLength={100} />
            </div>
            <div>
              <label className={labelClass}>Mobile number on file (optional)</label>
              <input className={inputClass} value={mobile} onChange={(e) => setMobile(e.target.value)} maxLength={20} />
            </div>
            <div>
              <label className={labelClass}>Date of birth *</label>
              <input className={inputClass} type="date" value={dob} onChange={(e) => setDob(e.target.value)} required />
            </div>
            <button type="submit" disabled={busy} className={buttonClass}>
              {busy ? 'Sending...' : 'Send verification code'}
            </button>
          </form>
        )}

        {step === 'code' && (
          <form onSubmit={onActivate} className="space-y-3">
            {info && <p className="text-[11px] text-secondary-text">{info}</p>}
            <div>
              <label className={labelClass}>6 digit code (from your email, valid 5 minutes)</label>
              <input
                className={inputClass}
                inputMode="numeric"
                pattern="\d{6}"
                maxLength={6}
                value={code}
                onChange={(e) => setCode(e.target.value)}
                required
              />
            </div>
            <div>
              <label className={labelClass}>New password (at least 8 characters) *</label>
              <input className={inputClass} type="password" value={password} onChange={(e) => setPassword(e.target.value)} required minLength={8} maxLength={100} />
            </div>
            <div>
              <label className={labelClass}>Confirm new password *</label>
              <input className={inputClass} type="password" value={confirm} onChange={(e) => setConfirm(e.target.value)} required minLength={8} maxLength={100} />
            </div>
            <button type="submit" disabled={busy} className={buttonClass}>
              {busy ? 'Activating...' : 'Activate account'}
            </button>
            <div className="flex items-center justify-between text-[11px]">
              <button type="button" onClick={sendCode} disabled={busy} className="text-accent hover:text-accent-hover">
                Resend code
              </button>
              <Link to="/login" className="text-secondary-text hover:text-primary-text">Back to login</Link>
            </div>
          </form>
        )}

        {step === 'done' && (
          <div className="space-y-3">
            <p className="text-xs text-primary-text">Your account is active. You can now log in with your email and new password.</p>
            <Link to="/login" className="block text-center w-full py-2.5 rounded bg-accent text-white text-xs font-medium">
              Go to login
            </Link>
          </div>
        )}

        {step !== 'done' && error && <p className="text-[11px] text-red-400">{error}</p>}
        {step === 'details' && (
          <p className="text-[11px] text-secondary-text text-center">
            <Link to="/login" className="hover:text-primary-text">Back to login</Link>
          </p>
        )}
      </div>
    </div>
  );
};
