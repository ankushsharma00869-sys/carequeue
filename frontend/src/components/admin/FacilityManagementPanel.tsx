import React, { useState } from 'react';
import api from '../../api/client';
import { Hospital } from '../../types';
import { Building2, Layers, CheckCircle2 } from 'lucide-react';

interface Props {
  hospitals: Hospital[];
  onChanged: () => void;
}

const inputClass =
  'w-full px-3 py-2 bg-surface-elevated border border-border rounded text-xs text-primary-text focus:outline-none focus:border-accent';
const labelClass = 'block text-xs text-secondary-text mb-1';
const buttonClass =
  'px-4 py-2 rounded bg-accent text-white text-xs font-medium disabled:opacity-50 disabled:cursor-not-allowed';

function errorText(err: any, fallback: string): string {
  return err?.response?.data?.message || fallback;
}

/** Admin tool to create hospitals and departments, so a new facility can be booked like any other. */
export const FacilityManagementPanel: React.FC<Props> = ({ hospitals, onChanged }) => {
  const [tab, setTab] = useState<'hospital' | 'department'>('hospital');

  return (
    <div className="bg-surface border border-border rounded-lg overflow-hidden">
      <div className="px-6 py-4 border-b border-border flex items-center gap-2">
        <Building2 className="w-4 h-4 text-accent" />
        <h2 className="text-sm font-semibold text-primary-text">Facilities</h2>
      </div>

      <div className="flex border-b border-border">
        <button
          onClick={() => setTab('hospital')}
          className={`flex-1 py-2.5 text-xs font-medium flex items-center justify-center gap-1.5 transition-colors ${
            tab === 'hospital' ? 'text-accent border-b-2 border-accent bg-accent/5' : 'text-secondary-text hover:text-primary-text'
          }`}
        >
          <Building2 className="w-3.5 h-3.5" />
          Add Hospital
        </button>
        <button
          onClick={() => setTab('department')}
          className={`flex-1 py-2.5 text-xs font-medium flex items-center justify-center gap-1.5 transition-colors ${
            tab === 'department' ? 'text-accent border-b-2 border-accent bg-accent/5' : 'text-secondary-text hover:text-primary-text'
          }`}
        >
          <Layers className="w-3.5 h-3.5" />
          Add Department
        </button>
      </div>

      <div className="p-6">
        {tab === 'hospital' ? (
          <AddHospitalForm onCreated={onChanged} />
        ) : (
          <AddDepartmentForm hospitals={hospitals} onCreated={onChanged} />
        )}
      </div>
    </div>
  );
};

const AddHospitalForm: React.FC<{ onCreated: () => void }> = ({ onCreated }) => {
  const [name, setName] = useState('');
  const [code, setCode] = useState('');
  const [address, setAddress] = useState('');
  const [city, setCity] = useState('');
  const [phone, setPhone] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setSuccess(null);
    setSubmitting(true);
    try {
      const res = await api.post('/admin/hospitals', { name, code, address, city, phone });
      setSuccess(`Hospital "${res.data.name}" created (code ${res.data.code}).`);
      setName('');
      setCode('');
      setAddress('');
      setCity('');
      setPhone('');
      onCreated();
    } catch (err: any) {
      setError(errorText(err, 'Could not create the hospital. Check the fields and try again.'));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <form onSubmit={submit} className="grid md:grid-cols-2 gap-4">
      <div>
        <label className={labelClass}>Hospital name *</label>
        <input className={inputClass} value={name} onChange={(e) => setName(e.target.value)} required maxLength={150} />
      </div>
      <div>
        <label className={labelClass}>Code * (unique, e.g. CITY-02)</label>
        <input className={inputClass} value={code} onChange={(e) => setCode(e.target.value)} required maxLength={20} />
      </div>
      <div>
        <label className={labelClass}>City</label>
        <input className={inputClass} value={city} onChange={(e) => setCity(e.target.value)} maxLength={80} />
      </div>
      <div>
        <label className={labelClass}>Phone</label>
        <input className={inputClass} value={phone} onChange={(e) => setPhone(e.target.value)} maxLength={20} />
      </div>
      <div className="md:col-span-2">
        <label className={labelClass}>Address</label>
        <input className={inputClass} value={address} onChange={(e) => setAddress(e.target.value)} maxLength={255} />
      </div>
      <div className="md:col-span-2 flex items-center gap-4">
        <button type="submit" disabled={submitting} className={buttonClass}>
          {submitting ? 'Creating...' : 'Create hospital'}
        </button>
        {success && (
          <span className="text-xs text-green-400 flex items-center gap-1">
            <CheckCircle2 className="w-3.5 h-3.5" /> {success}
          </span>
        )}
        {error && <span className="text-xs text-red-400">{error}</span>}
      </div>
    </form>
  );
};

const AddDepartmentForm: React.FC<{ hospitals: Hospital[]; onCreated: () => void }> = ({ hospitals, onCreated }) => {
  const [hospitalId, setHospitalId] = useState<number | ''>('');
  const [name, setName] = useState('');
  const [code, setCode] = useState('');
  const [floorNumber, setFloorNumber] = useState('');
  const [description, setDescription] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (hospitalId === '') {
      setError('Choose a hospital first.');
      return;
    }
    setError(null);
    setSuccess(null);
    setSubmitting(true);
    try {
      const res = await api.post('/admin/departments', {
        hospitalId,
        name,
        code,
        floorNumber,
        description,
      });
      setSuccess(`Department "${res.data.name}" created.`);
      setName('');
      setCode('');
      setFloorNumber('');
      setDescription('');
      onCreated();
    } catch (err: any) {
      setError(errorText(err, 'Could not create the department. Check the fields and try again.'));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <form onSubmit={submit} className="grid md:grid-cols-2 gap-4">
      <div className="md:col-span-2">
        <label className={labelClass}>Hospital *</label>
        <select
          className={inputClass}
          value={hospitalId}
          onChange={(e) => setHospitalId(e.target.value === '' ? '' : Number(e.target.value))}
          required
        >
          <option value="">Select a hospital</option>
          {hospitals.map((h) => (
            <option key={h.id} value={h.id}>
              {h.name} ({h.code})
            </option>
          ))}
        </select>
      </div>
      <div>
        <label className={labelClass}>Department name *</label>
        <input className={inputClass} value={name} onChange={(e) => setName(e.target.value)} required maxLength={100} />
      </div>
      <div>
        <label className={labelClass}>Code * (unique within the hospital)</label>
        <input className={inputClass} value={code} onChange={(e) => setCode(e.target.value)} required maxLength={20} />
      </div>
      <div>
        <label className={labelClass}>Floor</label>
        <input className={inputClass} value={floorNumber} onChange={(e) => setFloorNumber(e.target.value)} maxLength={20} />
      </div>
      <div>
        <label className={labelClass}>Description</label>
        <input className={inputClass} value={description} onChange={(e) => setDescription(e.target.value)} maxLength={300} />
      </div>
      <div className="md:col-span-2 flex items-center gap-4">
        <button type="submit" disabled={submitting} className={buttonClass}>
          {submitting ? 'Creating...' : 'Create department'}
        </button>
        {success && (
          <span className="text-xs text-green-400 flex items-center gap-1">
            <CheckCircle2 className="w-3.5 h-3.5" /> {success}
          </span>
        )}
        {error && <span className="text-xs text-red-400">{error}</span>}
      </div>
    </form>
  );
};
