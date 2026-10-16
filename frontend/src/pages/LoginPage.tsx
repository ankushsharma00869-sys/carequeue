import React, { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { Activity, ArrowRight } from 'lucide-react';

export const LoginPage: React.FC = () => {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const { login, loading } = useAuth();
  const navigate = useNavigate();

  const handleLogin = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    try {
      await login(email, password);
      navigate('/');
    } catch (err: any) {
      setError(err.response?.data?.message || 'Invalid email or password credentials');
    }
  };

  return (
    <div className="min-h-screen bg-background flex flex-col justify-center py-12 sm:px-6 lg:px-8">
      <div className="sm:mx-auto sm:w-full sm:max-w-md text-center">
        <div className="inline-flex items-center justify-center w-12 h-12 rounded bg-accent/15 border border-accent/30 text-accent mb-3">
          <Activity className="w-6 h-6" />
        </div>
        <h1 className="text-xl font-bold text-primary-text tracking-wide">CareQueue</h1>
        <p className="mt-1 text-xs text-secondary-text">Smart Hospital Operations & Queue Management System</p>
      </div>

      <div className="mt-8 sm:mx-auto sm:w-full sm:max-w-md px-4">
        <div className="bg-surface border border-border rounded-lg p-6 shadow-xl">
          {error && (
            <div className="mb-4 p-3 rounded bg-emergency/15 border border-emergency/30 text-xs text-emergency">
              {error}
            </div>
          )}

          <form onSubmit={handleLogin} className="space-y-4">
            <div>
              <label className="block text-xs font-medium text-secondary-text mb-1">Hospital Email</label>
              <input
                type="email"
                required
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                placeholder="name@carequeue.com"
                className="w-full px-3 py-2 bg-surface-elevated border border-border rounded text-xs text-primary-text placeholder-muted-text focus:outline-none focus:border-accent"
              />
            </div>

            <div>
              <label className="block text-xs font-medium text-secondary-text mb-1">Password</label>
              <input
                type="password"
                required
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder="••••••••"
                className="w-full px-3 py-2 bg-surface-elevated border border-border rounded text-xs text-primary-text placeholder-muted-text focus:outline-none focus:border-accent"
              />
            </div>

            <button
              type="submit"
              disabled={loading}
              className="w-full py-2 px-4 rounded bg-accent hover:bg-accent-hover text-white text-xs font-semibold tracking-wide transition-colors flex items-center justify-center space-x-2 disabled:opacity-50"
            >
              <span>{loading ? 'Authenticating...' : 'Sign In'}</span>
              <ArrowRight className="w-3.5 h-3.5" />
            </button>
          </form>

          {/*
            Patients can self-register below (PATIENT-only, no role selector — see
            SignupPage). Staff (Doctor / Receptionist / Admin) accounts are never
            self-service and must be provisioned by an administrator.
          */}
          <div className="mt-6 pt-6 border-t border-border text-center space-y-1.5">
            <p className="text-[11px] text-secondary-text">
              Don't have an account?{' '}
              <Link to="/signup" className="text-accent hover:text-accent-hover font-medium">
                Create Patient Account
              </Link>
            </p>
            <p className="text-[11px] text-secondary-text">
              Visited the hospital reception before?{' '}
              <Link to="/activate-walkin" className="text-accent hover:text-accent-hover font-medium">
                Activate your online account
              </Link>
            </p>
            <details className="mt-4 rounded border border-border bg-surface-elevated/40 px-3 py-2 text-left">
              <summary className="cursor-pointer text-xs font-semibold text-primary-text">How it works</summary>
              <div className="mt-2 space-y-3 text-[11px] text-secondary-text">
                <div>
                  <p className="font-semibold text-primary-text">1. New patient (first visit)</p>
                  <p>Click "Create Patient Account", fill your details, then verify your email with the 6 digit code. Then log in.</p>
                </div>
                <div>
                  <p className="font-semibold text-primary-text">2. Reception registered you</p>
                  <p>Click "Activate your online account" above. Enter your email, mobile number (if reception gave one) and date of birth, then set a new password. After that, log in with your email and new password.</p>
                </div>
                <div>
                  <p className="font-semibold text-primary-text">3. Book an appointment</p>
                  <p>Go to Book Appointment, choose hospital, department, doctor, date and time slot, then confirm. You will get a token number.</p>
                </div>
                <div>
                  <p className="font-semibold text-primary-text">4. Track your turn</p>
                  <p>Today's Token shows your position and estimated wait. Live Queue Board shows the queue live.</p>
                </div>
                <p>Code not received? Check the Spam folder, or wait 1 minute and click Resend code.</p>
              </div>
            </details>
            <p className="text-[11px] text-muted-text leading-relaxed">
              Doctor, Receptionist and Admin accounts are provisioned by your hospital administrator.
            </p>
          </div>
        </div>
      </div>
    </div>
  );
};