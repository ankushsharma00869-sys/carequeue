import React, { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import api from '../api/client';
import { Activity, ArrowRight, ArrowLeft } from 'lucide-react';

/**
 * Public self-service PATIENT sign-up.
 *
 * Deliberately offers no role selector anywhere on this page: the backend's
 * POST /api/auth/register endpoint hardcodes newly self-registered accounts
 * to Role.PATIENT regardless of what the client sends, and this form never
 * even constructs a role field. Doctor / Receptionist / Admin accounts can
 * only be created by an authenticated Admin via POST /api/admin/staff.
 */
export const SignupPage: React.FC = () => {
  const [fullName, setFullName] = useState('');
  const [email, setEmail] = useState('');
  const [phoneNumber, setPhoneNumber] = useState('');
  const [gender, setGender] = useState('');
  const [bloodGroup, setBloodGroup] = useState('');
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [codeSent, setCodeSent] = useState(false);
  const [code, setCode] = useState('');
  const [verified, setVerified] = useState(false);
  const [otpBusy, setOtpBusy] = useState(false);
  const [otpMessage, setOtpMessage] = useState<string | null>(null);

  const { register, loading } = useAuth();
  const navigate = useNavigate();

  // Cooldown is stored in localStorage, so a refresh or tab switch does not reset it.
  const COOLDOWN_MS = 60000;
  const cooldownKey = (addr: string) => 'cq_otp_next_signup:' + addr.trim().toLowerCase();
  const secondsLeft = (addr: string) => {
    const next = Number(localStorage.getItem(cooldownKey(addr)) || 0);
    return Math.max(0, Math.ceil((next - Date.now()) / 1000));
  };

  const sendOtp = async () => {
    const wait = secondsLeft(email);
    if (wait > 0) {
      setCodeSent(true);
      setOtpMessage('A code was already sent. Please wait ' + wait + ' seconds before requesting another.');
      return;
    }
    setOtpBusy(true);
    setOtpMessage(null);
    try {
      const res = await api.post('/otp/email/request', { email });
      localStorage.setItem(cooldownKey(email), String(Date.now() + COOLDOWN_MS));
      setCodeSent(true);
      setOtpMessage(res.data?.message || 'If this address can receive email, a verification code has been sent.');
    } catch (err: any) {
      setOtpMessage(err.response?.data?.message || 'Could not send the code. Please try again later.');
    } finally {
      setOtpBusy(false);
    }
  };

  const verifyOtp = async () => {
    setOtpBusy(true);
    setOtpMessage(null);
    try {
      await api.post('/otp/email/verify', { email, code });
      setVerified(true);
      setOtpMessage('Email verified.');
    } catch (err: any) {
      setVerified(false);
      setOtpMessage(err.response?.data?.message || 'The code is invalid or has expired.');
    } finally {
      setOtpBusy(false);
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);

    if (!verified) {
      setError('Please verify your email address first.');
      return;
    }

    if (password !== confirmPassword) {
      setError('Passwords do not match.');
      return;
    }
    if (password.length < 6) {
      setError('Password must be at least 6 characters.');
      return;
    }

    setSubmitting(true);
    try {
      await register({
        fullName,
        email,
        phoneNumber,
        gender: gender || undefined,
        bloodGroup: bloodGroup || undefined,
        password,
      });
      navigate('/', { replace: true });
    } catch (err: any) {
      setError(err.response?.data?.message || 'Could not create your account. Please try again.');
    } finally {
      setSubmitting(false);
    }
  };

  const busy = submitting || loading;

  return (
    <div className="min-h-screen bg-background flex flex-col justify-center py-12 sm:px-6 lg:px-8">
      <div className="sm:mx-auto sm:w-full sm:max-w-md text-center">
        <div className="inline-flex items-center justify-center w-12 h-12 rounded bg-accent/15 border border-accent/30 text-accent mb-3">
          <Activity className="w-6 h-6" />
        </div>
        <h1 className="text-xl font-bold text-primary-text tracking-wide">CareQueue</h1>
        <p className="mt-1 text-xs text-secondary-text">Create your patient account</p>
      </div>

      <div className="mt-8 sm:mx-auto sm:w-full sm:max-w-md px-4">
        <div className="bg-surface border border-border rounded-lg p-6 shadow-xl">
          {error && (
            <div className="mb-4 p-3 rounded bg-emergency/15 border border-emergency/30 text-xs text-emergency">
              {error}
            </div>
          )}

          <form onSubmit={handleSubmit} className="space-y-4">
            <div>
              <label className="block text-xs font-medium text-secondary-text mb-1">Full Name</label>
              <input
                type="text"
                required
                value={fullName}
                onChange={(e) => setFullName(e.target.value)}
                placeholder="Rahul Kumar"
                className="w-full px-3 py-2 bg-surface-elevated border border-border rounded text-xs text-primary-text placeholder-muted-text focus:outline-none focus:border-accent"
              />
            </div>

            <div>
              <label className="block text-xs font-medium text-secondary-text mb-1">Email</label>
              <input
                type="email"
                required
                value={email}
                onChange={(e) => {
                  setEmail(e.target.value);
                  setVerified(false);
                  setCodeSent(false);
                  setCode('');
                  setOtpMessage(null);
                }}
                placeholder="name@example.com"
                className="w-full px-3 py-2 bg-surface-elevated border border-border rounded text-xs text-primary-text placeholder-muted-text focus:outline-none focus:border-accent"
              />
            </div>

            <div className="space-y-2">
              {!verified && !codeSent && (
                <button
                  type="button"
                  onClick={sendOtp}
                  disabled={otpBusy || !email}
                  className="w-full py-2 px-4 rounded border border-accent text-accent text-xs font-semibold disabled:opacity-50"
                >
                  {otpBusy ? 'Sending...' : 'Send verification code to this email'}
                </button>
              )}
              {!verified && codeSent && (
                <div className="flex items-center gap-2">
                  <input
                    inputMode="numeric"
                    maxLength={6}
                    value={code}
                    onChange={(e) => setCode(e.target.value)}
                    placeholder="6 digit code"
                    className="flex-1 px-3 py-2 bg-surface-elevated border border-border rounded text-xs text-primary-text focus:outline-none focus:border-accent"
                  />
                  <button
                    type="button"
                    onClick={verifyOtp}
                    disabled={otpBusy || code.length !== 6}
                    className="px-3 py-2 rounded bg-accent text-white text-xs font-semibold disabled:opacity-50"
                  >
                    Verify
                  </button>
                </div>
              )}
              {!verified && codeSent && (
                <button type="button" onClick={sendOtp} disabled={otpBusy} className="text-[11px] text-accent">
                  Resend code
                </button>
              )}
              {otpMessage && <p className="text-[11px] text-secondary-text">{otpMessage}</p>}
            </div>

            <div>
              <label className="block text-xs font-medium text-secondary-text mb-1">Phone Number</label>
              <input
                type="tel"
                required
                value={phoneNumber}
                onChange={(e) => setPhoneNumber(e.target.value)}
                placeholder="9876543210"
                className="w-full px-3 py-2 bg-surface-elevated border border-border rounded text-xs text-primary-text placeholder-muted-text focus:outline-none focus:border-accent"
              />
            </div>

            <div className="grid grid-cols-2 gap-3">
              <div>
                <label className="block text-xs font-medium text-secondary-text mb-1">Gender</label>
                <select
                  value={gender}
                  onChange={(e) => setGender(e.target.value)}
                  className="w-full px-3 py-2 bg-surface-elevated border border-border rounded text-xs text-primary-text focus:outline-none focus:border-accent"
                >
                  <option value="">Select</option>
                  <option value="MALE">Male</option>
                  <option value="FEMALE">Female</option>
                  <option value="OTHER">Other</option>
                </select>
              </div>
              <div>
                <label className="block text-xs font-medium text-secondary-text mb-1">Blood Group (optional)</label>
                <select
                  value={bloodGroup}
                  onChange={(e) => setBloodGroup(e.target.value)}
                  className="w-full px-3 py-2 bg-surface-elevated border border-border rounded text-xs text-primary-text focus:outline-none focus:border-accent"
                >
                  <option value="">Unknown</option>
                  {['A+', 'A-', 'B+', 'B-', 'AB+', 'AB-', 'O+', 'O-'].map((bg) => (
                    <option key={bg} value={bg}>{bg}</option>
                  ))}
                </select>
              </div>
            </div>

            <div>
              <label className="block text-xs font-medium text-secondary-text mb-1">Password</label>
              <input
                type="password"
                required
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder="At least 6 characters"
                className="w-full px-3 py-2 bg-surface-elevated border border-border rounded text-xs text-primary-text placeholder-muted-text focus:outline-none focus:border-accent"
              />
            </div>

            <div>
              <label className="block text-xs font-medium text-secondary-text mb-1">Confirm Password</label>
              <input
                type="password"
                required
                value={confirmPassword}
                onChange={(e) => setConfirmPassword(e.target.value)}
                placeholder="••••••••"
                className="w-full px-3 py-2 bg-surface-elevated border border-border rounded text-xs text-primary-text placeholder-muted-text focus:outline-none focus:border-accent"
              />
            </div>

            <button
              type="submit"
              disabled={busy || !verified}
              className="w-full py-2 px-4 rounded bg-accent hover:bg-accent-hover text-white text-xs font-semibold tracking-wide transition-colors flex items-center justify-center space-x-2 disabled:opacity-50"
            >
              <span>{busy ? 'Creating account...' : 'Create Account'}</span>
              <ArrowRight className="w-3.5 h-3.5" />
            </button>
          </form>

          <div className="mt-6 pt-6 border-t border-border text-center">
            <Link
              to="/login"
              className="inline-flex items-center space-x-1 text-[11px] text-accent hover:text-accent-hover"
            >
              <ArrowLeft className="w-3 h-3" />
              <span>Already have an account? Sign In</span>
            </Link>
            <p className="mt-3 text-[11px] text-muted-text leading-relaxed">
              This creates a Patient account only. Doctor, Receptionist and Admin
              accounts are provisioned by your hospital administrator.
            </p>
          </div>
        </div>
      </div>
    </div>
  );
};
