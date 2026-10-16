import React, { useState } from 'react';
import api from '../../api/client';
import { Hospital, Department } from '../../types';
import { UserPlus, Stethoscope, Headset, CheckCircle2 } from 'lucide-react';
import { WeeklySchedulePicker, defaultWeek, toPayload } from './WeeklySchedulePicker';
import type { DayRow } from './WeeklySchedulePicker';

interface Props {
  hospitals: Hospital[];
  departments: Department[];
  onDoctorCreated: () => void;
}

/**
 * Implements the "complete doctor provisioning flow" (audit brief section 27/56) and the
 * matching receptionist flow (section 28/57) — previously the Admin dashboard only ever
 * *displayed* hospitals/departments/doctors read-only; there was no UI anywhere to create
 * a Doctor or Receptionist account, even though POST /api/admin/staff already existed on
 * the backend and could create a bare login for one.
 */
export const StaffManagementPanel: React.FC<Props> = ({ hospitals, departments, onDoctorCreated }) => {
  const [tab, setTab] = useState<'doctor' | 'receptionist'>('doctor');

  return (
    <div className="bg-surface border border-border rounded-lg overflow-hidden">
      <div className="px-6 py-4 border-b border-border flex items-center gap-2">
        <UserPlus className="w-4 h-4 text-accent" />
        <h2 className="text-sm font-semibold text-primary-text">Provision Staff Account</h2>
      </div>

      <div className="flex border-b border-border">
        <button
          onClick={() => setTab('doctor')}
          className={`flex-1 py-2.5 text-xs font-medium flex items-center justify-center gap-1.5 transition-colors ${
            tab === 'doctor' ? 'text-accent border-b-2 border-accent bg-accent/5' : 'text-secondary-text hover:text-primary-text'
          }`}
        >
          <Stethoscope className="w-3.5 h-3.5" />
          Add Doctor
        </button>
        <button
          onClick={() => setTab('receptionist')}
          className={`flex-1 py-2.5 text-xs font-medium flex items-center justify-center gap-1.5 transition-colors ${
            tab === 'receptionist' ? 'text-accent border-b-2 border-accent bg-accent/5' : 'text-secondary-text hover:text-primary-text'
          }`}
        >
          <Headset className="w-3.5 h-3.5" />
          Add Receptionist
        </button>
      </div>

      <div className="p-6">
        {tab === 'doctor' ? (
          <AddDoctorForm hospitals={hospitals} departments={departments} onCreated={onDoctorCreated} />
        ) : (
          <AddReceptionistForm hospitals={hospitals} />
        )}
      </div>
    </div>
  );
};

const inputClass =
  'w-full px-3 py-2 bg-surface-elevated border border-border rounded text-xs text-primary-text placeholder-muted-text focus:outline-none focus:border-accent';
const labelClass = 'block text-xs font-medium text-secondary-text mb-1';

const AddDoctorForm: React.FC<{ hospitals: Hospital[]; departments: Department[]; onCreated: () => void }> = ({
  hospitals,
  departments,
  onCreated,
}) => {
  const [form, setForm] = useState({
    fullName: '', email: '', password: '', phoneNumber: '',
    hospitalId: '', departmentId: '', licenseNumber: '', specialization: '',
    roomNumber: '', consultationFee: '', averageConsultationMinutes: '15',
  });
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [schedule, setSchedule] = useState<DayRow[]>(defaultWeek());

  const filteredDepartments = form.hospitalId
    ? departments.filter((d) => d.hospitalId === Number(form.hospitalId))
    : departments;

  const set = (field: string) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
    setForm((f) => ({ ...f, [field]: e.target.value }));

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setSuccess(null);

    if (!form.hospitalId || !form.departmentId) {
      setError('Hospital and Department are required.');
      return;
    }

    setSubmitting(true);
    try {
      const res = await api.post('/admin/doctors', {
        fullName: form.fullName,
        email: form.email,
        password: form.password,
        phoneNumber: form.phoneNumber,
        hospitalId: Number(form.hospitalId),
        departmentId: Number(form.departmentId),
        licenseNumber: form.licenseNumber,
        specialization: form.specialization,
        roomNumber: form.roomNumber || undefined,
        consultationFee: form.consultationFee ? Number(form.consultationFee) : undefined,
        averageConsultationMinutes: Number(form.averageConsultationMinutes) || 15,
        initialSchedule: toPayload(schedule),
      });
      setSuccess(`Dr. ${res.data.fullName} created and assigned to ${res.data.departmentName}. They can log in immediately.`);
      setForm({
        fullName: '', email: '', password: '', phoneNumber: '',
        hospitalId: '', departmentId: '', licenseNumber: '', specialization: '',
        roomNumber: '', consultationFee: '', averageConsultationMinutes: '15',
      });
      onCreated();
    } catch (err: any) {
      setError(err.response?.data?.message || 'Could not create doctor account.');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <form onSubmit={handleSubmit} className="space-y-4">
      {error && <div className="p-3 rounded bg-emergency/15 border border-emergency/30 text-xs text-emergency">{error}</div>}
      {success && (
        <div className="p-3 rounded bg-success/15 border border-success/30 text-xs text-success flex items-start gap-2">
          <CheckCircle2 className="w-3.5 h-3.5 mt-0.5 flex-shrink-0" />
          <span>{success}</span>
        </div>
      )}

      <div className="grid sm:grid-cols-2 gap-3">
        <div>
          <label className={labelClass}>Full Name</label>
          <input required value={form.fullName} onChange={set('fullName')} placeholder="Dr. Ankit Verma" className={inputClass} />
        </div>
        <div>
          <label className={labelClass}>Email</label>
          <input required type="email" value={form.email} onChange={set('email')} placeholder="doctor@carequeue.com" className={inputClass} />
        </div>
        <div>
          <label className={labelClass}>Password</label>
          <input required type="password" value={form.password} onChange={set('password')} placeholder="At least 6 characters" className={inputClass} />
        </div>
        <div>
          <label className={labelClass}>Phone Number</label>
          <input value={form.phoneNumber} onChange={set('phoneNumber')} placeholder="9876543210" className={inputClass} />
        </div>

        <div>
          <label className={labelClass}>Hospital</label>
          <select required value={form.hospitalId} onChange={set('hospitalId')} className={inputClass}>
            <option value="">Select hospital</option>
            {hospitals.map((h) => <option key={h.id} value={h.id}>{h.name}</option>)}
          </select>
        </div>
        <div>
          <label className={labelClass}>Department</label>
          <select required value={form.departmentId} onChange={set('departmentId')} className={inputClass}>
            <option value="">Select department</option>
            {filteredDepartments.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
          </select>
        </div>

        <div>
          <label className={labelClass}>License Number</label>
          <input required value={form.licenseNumber} onChange={set('licenseNumber')} placeholder="MCI-123456" className={inputClass} />
        </div>
        <div>
          <label className={labelClass}>Specialization</label>
          <input required value={form.specialization} onChange={set('specialization')} placeholder="General Medicine" className={inputClass} />
        </div>

        <div>
          <label className={labelClass}>Room Number</label>
          <input value={form.roomNumber} onChange={set('roomNumber')} placeholder="104" className={inputClass} />
        </div>
        <div>
          <label className={labelClass}>Consultation Fee (₹)</label>
          <input type="number" min="0" value={form.consultationFee} onChange={set('consultationFee')} placeholder="500" className={inputClass} />
        </div>
        <div>
          <label className={labelClass}>Avg. Consultation Duration (min)</label>
          <input type="number" min="5" value={form.averageConsultationMinutes} onChange={set('averageConsultationMinutes')} className={inputClass} />
        </div>
      </div>

      <WeeklySchedulePicker value={schedule} onChange={setSchedule} />

      <button
        type="submit"
        disabled={submitting}
        className="px-4 py-2 rounded bg-accent hover:bg-accent-hover text-white text-xs font-semibold tracking-wide transition-colors disabled:opacity-50"
      >
        {submitting ? 'Creating...' : 'Create Doctor Account'}
      </button>
    </form>
  );
};

const AddReceptionistForm: React.FC<{ hospitals: Hospital[] }> = ({ hospitals }) => {
  const [form, setForm] = useState({ fullName: '', email: '', password: '', phoneNumber: '', hospitalId: '' });
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const set = (field: string) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
    setForm((f) => ({ ...f, [field]: e.target.value }));

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setSuccess(null);

    if (!form.hospitalId) {
      setError('Hospital is required.');
      return;
    }

    setSubmitting(true);
    try {
      const res = await api.post('/admin/receptionists', {
        fullName: form.fullName,
        email: form.email,
        password: form.password,
        phoneNumber: form.phoneNumber,
        hospitalId: Number(form.hospitalId),
      });
      setSuccess(`${res.data.fullName} created and assigned to ${res.data.hospitalName}. They can log in immediately.`);
      setForm({ fullName: '', email: '', password: '', phoneNumber: '', hospitalId: '' });
    } catch (err: any) {
      setError(err.response?.data?.message || 'Could not create receptionist account.');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <form onSubmit={handleSubmit} className="space-y-4 max-w-md">
      {error && <div className="p-3 rounded bg-emergency/15 border border-emergency/30 text-xs text-emergency">{error}</div>}
      {success && (
        <div className="p-3 rounded bg-success/15 border border-success/30 text-xs text-success flex items-start gap-2">
          <CheckCircle2 className="w-3.5 h-3.5 mt-0.5 flex-shrink-0" />
          <span>{success}</span>
        </div>
      )}

      <div>
        <label className={labelClass}>Full Name</label>
        <input required value={form.fullName} onChange={set('fullName')} placeholder="Priya Sharma" className={inputClass} />
      </div>
      <div>
        <label className={labelClass}>Email</label>
        <input required type="email" value={form.email} onChange={set('email')} placeholder="reception@carequeue.com" className={inputClass} />
      </div>
      <div>
        <label className={labelClass}>Password</label>
        <input required type="password" value={form.password} onChange={set('password')} placeholder="At least 6 characters" className={inputClass} />
      </div>
      <div>
        <label className={labelClass}>Phone Number</label>
        <input value={form.phoneNumber} onChange={set('phoneNumber')} placeholder="9876543210" className={inputClass} />
      </div>
      <div>
        <label className={labelClass}>Hospital</label>
        <select required value={form.hospitalId} onChange={set('hospitalId')} className={inputClass}>
          <option value="">Select hospital</option>
          {hospitals.map((h) => <option key={h.id} value={h.id}>{h.name}</option>)}
        </select>
      </div>

      <button
        type="submit"
        disabled={submitting}
        className="px-4 py-2 rounded bg-accent hover:bg-accent-hover text-white text-xs font-semibold tracking-wide transition-colors disabled:opacity-50"
      >
        {submitting ? 'Creating...' : 'Create Receptionist Account'}
      </button>
    </form>
  );
};
