import React, { useState, useEffect } from 'react';
import { useSearchParams } from 'react-router-dom';
import api from '../api/client';
import { Appointment, Hospital, Department, Doctor } from '../types';
import { Badge } from '../components/common/Badge';
import { Modal } from '../components/common/Modal';
import { Users, AlertTriangle, CheckCircle, RefreshCw, Plus, Clock, Search } from 'lucide-react';

export const ReceptionDashboardPage: React.FC = () => {
  const [appointments, setAppointments] = useState<Appointment[]>([]);
  const [loading, setLoading] = useState(true);
  const [searchTerm, setSearchTerm] = useState('');
  const [searchParams] = useSearchParams();

  // Emergency Modal State
  const [isEmergencyModalOpen, setIsEmergencyModalOpen] = useState(false);
  const [hospitals, setHospitals] = useState<Hospital[]>([]);
  const [departments, setDepartments] = useState<Department[]>([]);
  const [doctors, setDoctors] = useState<Doctor[]>([]);

  const [emPatientName, setEmPatientName] = useState('');
  const [emPhone, setEmPhone] = useState('');
  const [emCondition, setEmCondition] = useState('');
  const [emSeverity, setEmSeverity] = useState('CRITICAL');
  const [selectedHospitalId, setSelectedHospitalId] = useState<number | ''>('');
  const [selectedDeptId, setSelectedDeptId] = useState<number | ''>('');
  const [selectedDocId, setSelectedDocId] = useState<number | ''>('');
  const [emergencySubmitting, setEmergencySubmitting] = useState(false);

  // Departments and doctors shown in the emergency form belong to the chosen hospital only.
  const deptsForHospital = departments.filter((d: any) => d.hospitalId === selectedHospitalId);
  const docsForSelection = doctors.filter(
    (doc: any) =>
      doc.hospitalId === selectedHospitalId && (selectedDeptId === '' || doc.departmentId === selectedDeptId),
  );

  // If the selected department is not in this hospital (e.g. after the hospital changed), pick the first one.
  useEffect(() => {
    if (deptsForHospital.length > 0 && !deptsForHospital.some((d) => d.id === selectedDeptId)) {
      setSelectedDeptId(deptsForHospital[0].id);
    }
  }, [selectedHospitalId, departments]);
  const [emergencyAlertSuccess, setEmergencyAlertSuccess] = useState<string | null>(null);

  const fetchTodayAppointments = async () => {
    try {
      const res = await api.get<Appointment[]>('/appointments/today');
      setAppointments(res.data);
    } catch (err) {
      console.error(err);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchTodayAppointments();
    api.get<Hospital[]>('/hospitals').then((res) => {
      setHospitals(res.data);
      if (res.data.length > 0) setSelectedHospitalId(res.data[0].id);
    });
    api.get<Department[]>('/departments').then((res) => {
      setDepartments(res.data);
      if (res.data.length > 0) setSelectedDeptId(res.data[0].id);
    });
    api.get<Doctor[]>('/doctors').then((res) => setDoctors(res.data));

    if (searchParams.get('action') === 'emergency') {
      setIsEmergencyModalOpen(true);
    }
  }, [searchParams]);

  const handleCheckIn = async (appointmentId: number) => {
    try {
      await api.post(`/queue/reception/check-in/${appointmentId}`);
      await fetchTodayAppointments();
    } catch (err: any) {
      alert(err.response?.data?.message || 'Check-in failed');
    }
  };

  const handleSubmitEmergency = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!selectedHospitalId || !selectedDeptId) return;

    setEmergencySubmitting(true);
    setEmergencyAlertSuccess(null);

    try {
      const res = await api.post('/emergency', {
        hospitalId: selectedHospitalId,
        departmentId: selectedDeptId,
        doctorId: selectedDocId ? selectedDocId : undefined,
        patientName: emPatientName,
        patientPhone: emPhone,
        triageCondition: emCondition,
        severity: emSeverity,
      });

      setEmergencyAlertSuccess(
        `CODE RED ISSUED: Emergency Token ${res.data.tokenFormatted} allocated with Priority #1.`
      );
      setEmPatientName('');
      setEmPhone('');
      setEmCondition('');
      await fetchTodayAppointments();
    } catch (err: any) {
      alert(err.response?.data?.message || 'Failed to submit emergency admission');
    } finally {
      setEmergencySubmitting(false);
    }
  };

  const filteredAppointments = appointments.filter(
    (a) =>
      a.patientName.toLowerCase().includes(searchTerm.toLowerCase()) ||
      a.appointmentNumber.toLowerCase().includes(searchTerm.toLowerCase()) ||
      (a.tokenFormatted && a.tokenFormatted.toLowerCase().includes(searchTerm.toLowerCase()))
  );

  return (
    <div className="p-4 md:p-8 max-w-6xl mx-auto space-y-8">
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div>
          <h1 className="text-xl font-bold text-primary-text tracking-wide">
            Reception Desk & Arrivals
          </h1>
          <p className="text-xs text-secondary-text mt-1">
            Physical patient check-in, walk-in triage, and emergency priority admission
          </p>
        </div>

        <div className="flex items-center space-x-3">
          <button
            onClick={() => {
              setIsEmergencyModalOpen(true);
              setEmergencyAlertSuccess(null);
            }}
            className="flex items-center space-x-2 px-4 py-2 rounded bg-emergency hover:bg-red-600 text-white text-xs font-semibold tracking-wide transition-colors shadow-lg"
          >
            <AlertTriangle className="w-4 h-4" />
            <span>CODE RED EMERGENCY INTAKE</span>
          </button>

          <button
            onClick={fetchTodayAppointments}
            className="flex items-center space-x-1.5 px-3 py-2 rounded bg-surface border border-border text-xs text-secondary-text hover:text-primary-text transition-colors"
          >
            <RefreshCw className="w-3.5 h-3.5" />
            <span>Refresh</span>
          </button>
        </div>
      </div>

      {/* TODAY'S RECEPTION APPOINTMENT TABLE */}
      <div className="bg-surface border border-border rounded-lg overflow-hidden shadow-xl">
        <div className="px-6 py-4 border-b border-border flex flex-col sm:flex-row sm:items-center justify-between gap-3">
          <div className="flex items-center space-x-2">
            <Users className="w-4 h-4 text-accent" />
            <h2 className="text-sm font-semibold text-primary-text">Today's Appointment Ledger</h2>
            <span className="text-xs text-muted-text">({filteredAppointments.length} matching)</span>
          </div>

          <div className="relative w-full sm:w-64">
            <Search className="w-3.5 h-3.5 text-muted-text absolute left-3 top-2.5" />
            <input
              type="text"
              value={searchTerm}
              onChange={(e) => setSearchTerm(e.target.value)}
              placeholder="Search patient, token, ID..."
              className="w-full pl-8 pr-3 py-1.5 bg-surface-elevated border border-border rounded text-xs text-primary-text placeholder-muted-text focus:outline-none focus:border-accent"
            />
          </div>
        </div>

        <div className="overflow-x-auto">
          <table className="w-full text-left text-xs">
            <thead className="bg-surface-elevated/60 text-secondary-text uppercase tracking-wider text-[11px] border-b border-border">
              <tr>
                <th className="px-6 py-3 font-medium">Token</th>
                <th className="px-6 py-3 font-medium">Patient Details</th>
                <th className="px-6 py-3 font-medium">Physician & Dept</th>
                <th className="px-6 py-3 font-medium">Scheduled Time</th>
                <th className="px-6 py-3 font-medium">No-Show Risk</th>
                <th className="px-6 py-3 font-medium">Status</th>
                <th className="px-6 py-3 font-medium text-right">Desk Action</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {filteredAppointments.length === 0 ? (
                <tr>
                  <td colSpan={7} className="px-6 py-8 text-center text-secondary-text">
                    No scheduled visits found matching criteria.
                  </td>
                </tr>
              ) : (
                filteredAppointments.map((a) => (
                  <tr key={a.id} className="hover:bg-surface-elevated/40 transition-colors">
                    <td className="px-6 py-4 font-mono font-bold text-sm text-accent">
                      {a.tokenFormatted || '--'}
                    </td>
                    <td className="px-6 py-4">
                      <div className="font-semibold text-primary-text">{a.patientName}</div>
                      <div className="text-[11px] text-muted-text font-mono">
                        {a.appointmentNumber} • {a.patientPhone || 'No Phone'}
                      </div>
                    </td>
                    <td className="px-6 py-4">
                      <div className="font-medium text-primary-text">{a.doctorName}</div>
                      <div className="text-[11px] text-secondary-text">{a.departmentName}</div>
                    </td>
                    <td className="px-6 py-4 font-mono text-secondary-text">
                      {a.appointmentTime}
                    </td>
                    <td className="px-6 py-4">
                      <span
                        className={`inline-block px-2 py-0.5 rounded text-[10px] font-bold uppercase tracking-wider ${
                          a.noShowRiskLevel === 'HIGH'
                            ? 'bg-emergency/20 text-emergency'
                            : a.noShowRiskLevel === 'MEDIUM'
                            ? 'bg-amber-500/20 text-amber-400'
                            : 'bg-emerald-500/20 text-emerald-400'
                        }`}
                      >
                        {a.noShowRiskLevel} ({a.noShowRiskScore})
                      </span>
                    </td>
                    <td className="px-6 py-4">
                      <Badge
                        variant={
                          a.status === 'COMPLETED'
                            ? 'success'
                            : a.status === 'IN_QUEUE'
                            ? 'normal'
                            : a.status === 'CHECKED_IN'
                            ? 'priority'
                            : 'neutral'
                        }
                      >
                        {a.status}
                      </Badge>
                    </td>
                    <td className="px-6 py-4 text-right">
                      {a.status === 'CONFIRMED' && (
                        <button
                          onClick={() => handleCheckIn(a.id)}
                          className="px-3 py-1.5 rounded bg-accent hover:bg-accent-hover text-white text-[11px] font-semibold transition-colors flex items-center space-x-1 ml-auto"
                        >
                          <CheckCircle className="w-3.5 h-3.5" />
                          <span>Check In Arrival</span>
                        </button>
                      )}
                      {a.status !== 'CONFIRMED' && (
                        <span className="text-[11px] text-muted-text font-mono">Checked In</span>
                      )}
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </div>

      {/* CODE RED EMERGENCY ADMISSION MODAL (Section 16) */}
      <Modal
        isOpen={isEmergencyModalOpen}
        onClose={() => setIsEmergencyModalOpen(false)}
        title="Code Red Emergency Triage & Intake"
        maxWidth="max-w-xl"
      >
        <form onSubmit={handleSubmitEmergency} className="space-y-4">
          <div className="p-3 rounded bg-emergency/15 border border-emergency/30 text-xs text-emergency">
            <strong>Emergency Priority Mechanism:</strong> Submitting this form allocates an immediate <strong>E-xxx</strong> token with Priority Rank #1. The patient will immediately jump to the head of the pending queue ahead of all regular appointments.
          </div>

          {emergencyAlertSuccess && (
            <div className="p-3 rounded bg-success/15 border border-success/30 text-xs text-success font-semibold">
              {emergencyAlertSuccess}
            </div>
          )}

          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
            <div>
              <label className="block text-xs font-medium text-secondary-text mb-1">Emergency Patient Name</label>
              <input
                type="text"
                required
                value={emPatientName}
                onChange={(e) => setEmPatientName(e.target.value)}
                placeholder="Full Name / John Doe (Unidentified)"
                className="w-full p-2 bg-surface-elevated border border-border rounded text-xs text-primary-text placeholder-muted-text focus:outline-none focus:border-emergency"
              />
            </div>

            <div>
              <label className="block text-xs font-medium text-secondary-text mb-1">Emergency Contact Phone</label>
              <input
                type="tel"
                value={emPhone}
                onChange={(e) => setEmPhone(e.target.value)}
                placeholder="+1 (555) 000-0000"
                className="w-full p-2 bg-surface-elevated border border-border rounded text-xs text-primary-text placeholder-muted-text focus:outline-none focus:border-emergency"
              />
            </div>
          </div>

          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
            <div className="col-span-2">
              <label className="block text-xs font-medium text-secondary-text mb-1">Hospital</label>
              <select
                value={selectedHospitalId}
                onChange={(e) => {
                  setSelectedHospitalId(Number(e.target.value));
                  setSelectedDeptId('');
                  setSelectedDocId('');
                }}
                className="w-full p-2 bg-surface-elevated border border-border rounded text-xs text-primary-text focus:outline-none focus:border-emergency"
              >
                {hospitals.map((h) => (
                  <option key={h.id} value={h.id}>{h.name}</option>
                ))}
              </select>
            </div>

            <div>
              <label className="block text-xs font-medium text-secondary-text mb-1">Triage Department</label>
              <select
                value={selectedDeptId}
                onChange={(e) => {
                  setSelectedDeptId(Number(e.target.value));
                  setSelectedDocId('');
                }}
                className="w-full p-2 bg-surface-elevated border border-border rounded text-xs text-primary-text focus:outline-none focus:border-emergency"
              >
                {deptsForHospital.map((d) => (
                  <option key={d.id} value={d.id}>{d.name}</option>
                ))}
              </select>
            </div>

            <div>
              <label className="block text-xs font-medium text-secondary-text mb-1">Triage Severity Level</label>
              <select
                value={emSeverity}
                onChange={(e) => setEmSeverity(e.target.value)}
                className="w-full p-2 bg-surface-elevated border border-border rounded text-xs text-primary-text focus:outline-none focus:border-emergency font-bold text-emergency"
              >
                <option value="CRITICAL">CRITICAL (Immediate Arrest / Trauma)</option>
                <option value="HIGH">HIGH (Acute Severe Distress)</option>
                <option value="MODERATE">MODERATE (Urgent Stabilization)</option>
              </select>
            </div>
          </div>

          <div>
            <label className="block text-xs font-medium text-secondary-text mb-1">Assigned Physician (Optional)</label>
            <select
              value={selectedDocId}
              onChange={(e) => setSelectedDocId(e.target.value ? Number(e.target.value) : '')}
              className="w-full p-2 bg-surface-elevated border border-border rounded text-xs text-primary-text focus:outline-none focus:border-emergency"
            >
              <option value="">-- Auto-Assign Next Available Physician --</option>
              {docsForSelection.map((doc) => (
                <option key={doc.id} value={doc.id}>
                  {doc.fullName} ({doc.departmentName} • {doc.roomNumber})
                </option>
              ))}
            </select>
          </div>

          <div>
            <label className="block text-xs font-medium text-secondary-text mb-1">Triage Presentation & Vital Signs</label>
            <textarea
              rows={3}
              required
              value={emCondition}
              onChange={(e) => setEmCondition(e.target.value)}
              placeholder="e.g. Severe chest pain, diaphoresis, BP 185/110, pulse 115, oxygen sat 89%..."
              className="w-full p-2 bg-surface-elevated border border-border rounded text-xs text-primary-text placeholder-muted-text focus:outline-none focus:border-emergency"
            />
          </div>

          <div className="flex justify-end space-x-3 pt-2">
            <button
              type="button"
              onClick={() => setIsEmergencyModalOpen(false)}
              className="px-3 py-1.5 rounded border border-border text-xs text-secondary-text hover:text-primary-text"
            >
              Close
            </button>
            <button
              type="submit"
              disabled={emergencySubmitting}
              className="px-5 py-2 rounded bg-emergency hover:bg-red-600 text-white text-xs font-semibold tracking-wide transition-colors disabled:opacity-50"
            >
              {emergencySubmitting ? 'Allocating Priority #1...' : 'Dispatch Priority Queue Entry'}
            </button>
          </div>
        </form>
      </Modal>
    </div>
  );
};