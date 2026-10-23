import React, { useState } from 'react';
import api from '../../api/client';

export interface WalkInPatient {
  id: number;
  fullName: string;
  phone: string;
  email: string;
}

interface Props {
  onCreated: (patient: WalkInPatient) => void;
  onCancel: () => void;
}

const inputClass =
  'w-full px-3 py-2 bg-surface-elevated border border-border rounded text-xs text-primary-text focus:outline-none focus:border-accent';

/** Reception registers a walk-in patient. Email is recommended, mobile is optional. */
export const WalkInPatientForm: React.FC<Props> = ({ onCreated, onCancel }) => {
  const [fullName, setFullName] = useState('');
  const [email, setEmail] = useState('');
  const [phone, setPhone] = useState('');
  const [dob, setDob] = useState('');
  const [gender, setGender] = useState('');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setSaving(true);
    setError(null);
    try {
      const res = await api.post('/staff/patients', {
        fullName,
        email: email || undefined,
        phoneNumber: phone || undefined,
        dateOfBirth: dob || undefined,
        gender: gender || undefined,
      });
      onCreated({
        id: res.data.id,
        fullName: res.data.fullName,
        phone: res.data.phone || '',
        email: res.data.email || '',
      });
    } catch (err: any) {
      setError(err.response?.data?.message || 'Could not register this walk-in patient.');
    } finally {
      setSaving(false);
    }
  };

  return (
    <form onSubmit={submit} className="space-y-2 p-3 rounded border border-border">
      <p className="text-xs font-medium text-primary-text">New walk-in patient</p>
      <input className={inputClass} placeholder="Full name *" value={fullName} onChange={(e) => setFullName(e.target.value)} required maxLength={120} />
      <input className={inputClass} type="email" placeholder="Email (recommended)" value={email} onChange={(e) => setEmail(e.target.value)} maxLength={100} />
      <input className={inputClass} placeholder="Mobile (optional)" value={phone} onChange={(e) => setPhone(e.target.value)} maxLength={20} />
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
        <input className={inputClass} type="date" value={dob} onChange={(e) => setDob(e.target.value)} />
        <select className={inputClass} value={gender} onChange={(e) => setGender(e.target.value)}>
          <option value="">Gender</option>
          <option value="Male">Male</option>
          <option value="Female">Female</option>
          <option value="Other">Other</option>
        </select>
      </div>
      <div className="flex items-center gap-3">
        <button type="submit" disabled={saving} className="px-3 py-1.5 rounded bg-accent text-white text-xs disabled:opacity-50">
          {saving ? 'Saving...' : 'Register and select'}
        </button>
        <button type="button" onClick={onCancel} className="text-xs text-secondary-text">Cancel</button>
        {error && <span className="text-xs text-red-400">{error}</span>}
      </div>
    </form>
  );
};
