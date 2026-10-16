import React, { useEffect, useState } from 'react';
import api from '../../api/client';
import { Calendar } from 'lucide-react';
import { DayRow, WeeklySchedulePicker, defaultWeek, fromPayload, toPayload } from './WeeklySchedulePicker';

interface DoctorOption {
  id: number;
  fullName: string;
  departmentName?: string;
}

const selectClass =
  'w-full px-3 py-2 bg-surface-elevated border border-border rounded text-xs text-primary-text focus:outline-none focus:border-accent';

function errorText(err: any, fallback: string): string {
  return err?.response?.data?.message || fallback;
}

/** Admin tool to view and change any doctor's weekly schedule. */
export const DoctorScheduleEditor: React.FC = () => {
  const [doctors, setDoctors] = useState<DoctorOption[]>([]);
  const [doctorId, setDoctorId] = useState<number | ''>('');
  const [rows, setRows] = useState<DayRow[]>(defaultWeek());
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api
      .get<DoctorOption[]>('/doctors')
      .then((res) => setDoctors(res.data))
      .catch(() => setError('Could not load the doctor list.'));
  }, []);

  const load = async (id: number) => {
    setLoading(true);
    setError(null);
    setMessage(null);
    try {
      const res = await api.get(`/admin/doctors/${id}/schedule`);
      setRows(fromPayload(res.data));
    } catch (err: any) {
      setError(errorText(err, 'Could not load this doctor schedule.'));
    } finally {
      setLoading(false);
    }
  };

  const save = async () => {
    if (doctorId === '') return;
    setSaving(true);
    setError(null);
    setMessage(null);
    try {
      await api.put(`/admin/doctors/${doctorId}/schedule`, toPayload(rows));
      setMessage('Schedule saved. Patients will see the new slots right away.');
    } catch (err: any) {
      setError(errorText(err, 'Could not save the schedule. Check the times and try again.'));
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="bg-surface border border-border rounded-lg overflow-hidden">
      <div className="px-6 py-4 border-b border-border flex items-center gap-2">
        <Calendar className="w-4 h-4 text-accent" />
        <h2 className="text-sm font-semibold text-primary-text">Doctor Schedules</h2>
      </div>
      <div className="p-6 space-y-4">
        <div>
          <label className="block text-xs text-secondary-text mb-1">Doctor</label>
          <select
            className={selectClass}
            value={doctorId}
            onChange={(e) => {
              const id = e.target.value === '' ? '' : Number(e.target.value);
              setDoctorId(id);
              if (id !== '') load(id);
            }}
          >
            <option value="">Select a doctor</option>
            {doctors.map((d) => (
              <option key={d.id} value={d.id}>
                {d.fullName}
                {d.departmentName ? ` (${d.departmentName})` : ''}
              </option>
            ))}
          </select>
        </div>

        {doctorId !== '' && (
          <>
            {loading ? (
              <p className="text-xs text-secondary-text">Loading schedule...</p>
            ) : (
              <WeeklySchedulePicker value={rows} onChange={setRows} />
            )}
            <div className="flex items-center gap-4">
              <button
                type="button"
                onClick={save}
                disabled={saving || loading}
                className="px-4 py-2 rounded bg-accent text-white text-xs font-medium disabled:opacity-50"
              >
                {saving ? 'Saving...' : 'Save schedule'}
              </button>
              {message && <span className="text-xs text-green-400">{message}</span>}
              {error && <span className="text-xs text-red-400">{error}</span>}
            </div>
          </>
        )}
        {doctorId === '' && error && <p className="text-xs text-red-400">{error}</p>}
      </div>
    </div>
  );
};
