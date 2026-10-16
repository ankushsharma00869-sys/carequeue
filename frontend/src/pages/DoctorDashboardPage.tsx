import React, { useState, useEffect } from 'react';
import api from '../api/client';
import { useAuth } from '../context/AuthContext';
import { useWebSocket } from '../context/WebSocketContext';
import { DoctorQueueOverview, QueueEntry } from '../types';
import { Badge } from '../components/common/Badge';
import { Modal } from '../components/common/Modal';
import { Stethoscope, UserCheck, AlertTriangle, ArrowRight, Play, CheckCircle, SkipForward, UserX, RefreshCw } from 'lucide-react';

export const DoctorDashboardPage: React.FC = () => {
  const { user } = useAuth();
  const { subscribeToDoctorQueue } = useWebSocket();
  const [overview, setOverview] = useState<DoctorQueueOverview | null>(null);
  const [loading, setLoading] = useState(true);

  const [completeModalEntry, setCompleteModalEntry] = useState<QueueEntry | null>(null);
  const [diagnosis, setDiagnosis] = useState('');
  const [notes, setNotes] = useState('');
  const [prescription, setPrescription] = useState('');
  const [actionLoading, setActionLoading] = useState(false);

  const fetchDoctorQueue = async () => {
    if (!user?.doctorId) return;
    try {
      const res = await api.get<DoctorQueueOverview>(`/queue/doctor/${user.doctorId}`);
      setOverview(res.data);
    } catch (err) {
      console.error('Failed to fetch doctor queue', err);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchDoctorQueue();
  }, [user]);

  useEffect(() => {
    if (user?.doctorId) {
      const unsubscribe = subscribeToDoctorQueue(user.doctorId, (data) => {
        setOverview(data);
      });
      return () => unsubscribe();
    }
  }, [user]);

  const handleCallNext = async () => {
    if (!user?.doctorId) return;
    setActionLoading(true);
    try {
      const res = await api.post(`/queue/doctor/${user.doctorId}/call-next`);
      setOverview(res.data);
    } catch (err: any) {
      alert(err.response?.data?.message || 'Failed to call next patient');
    } finally {
      setActionLoading(false);
    }
  };

  const handleStartConsultation = async (entry: QueueEntry) => {
    if (!user?.doctorId) return;
    setActionLoading(true);
    try {
      const res = await api.post(`/queue/doctor/${user.doctorId}/start/${entry.id}`);
      setOverview(res.data);
    } finally {
      setActionLoading(false);
    }
  };

  const handleCompleteConsultation = async () => {
    if (!user?.doctorId || !completeModalEntry) return;
    setActionLoading(true);
    try {
      const res = await api.post(`/queue/doctor/${user.doctorId}/complete/${completeModalEntry.id}`, {
        primaryDiagnosis: diagnosis,
        clinicalNotes: notes,
        prescriptionSummary: prescription,
      });
      setOverview(res.data);
      setCompleteModalEntry(null);
      setDiagnosis('');
      setNotes('');
      setPrescription('');
    } finally {
      setActionLoading(false);
    }
  };

  const handleSkip = async (entry: QueueEntry) => {
    if (!user?.doctorId) return;
    setActionLoading(true);
    try {
      const res = await api.post(`/queue/doctor/${user.doctorId}/skip/${entry.id}`);
      setOverview(res.data);
    } finally {
      setActionLoading(false);
    }
  };

  const handleMarkNoShow = async (entry: QueueEntry) => {
    if (!user?.doctorId) return;
    if (!window.confirm(`Confirm marking token ${entry.tokenFormatted} as NO-SHOW?`)) return;
    setActionLoading(true);
    try {
      const res = await api.post(`/queue/doctor/${user.doctorId}/no-show/${entry.id}`);
      setOverview(res.data);
    } finally {
      setActionLoading(false);
    }
  };

  if (loading) {
    return (
      <div className="p-8 space-y-4 max-w-6xl mx-auto">
        <div className="h-6 w-48 bg-surface-elevated animate-pulse rounded"></div>
        <div className="h-64 bg-surface border border-border animate-pulse rounded-lg"></div>
      </div>
    );
  }

  const current = overview?.currentPatient;

  return (
    <div className="p-8 max-w-6xl mx-auto space-y-8">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-bold text-primary-text tracking-wide">
            Physician Consultation Console
          </h1>
          <p className="text-xs text-secondary-text mt-1">
            {overview?.doctorName} • {overview?.departmentName} • {overview?.roomNumber}
          </p>
        </div>
        <button
          onClick={fetchDoctorQueue}
          className="flex items-center space-x-1.5 px-3 py-1.5 rounded bg-surface border border-border text-xs text-secondary-text hover:text-primary-text transition-colors"
        >
          <RefreshCw className="w-3.5 h-3.5" />
          <span>Refresh</span>
        </button>
      </div>

      <div className="grid grid-cols-2 md:grid-cols-6 gap-3">
        <div className="p-4 rounded-lg bg-surface border border-border">
          <div className="text-[11px] text-muted-text font-medium">TOTAL TODAY</div>
          <div className="text-2xl font-bold font-mono text-primary-text mt-1">{overview?.totalPatientsToday}</div>
        </div>
        <div className="p-4 rounded-lg bg-surface border border-border">
          <div className="text-[11px] text-muted-text font-medium">WAITING</div>
          <div className="text-2xl font-bold font-mono text-accent mt-1">{overview?.waitingCount}</div>
        </div>
        <div className="p-4 rounded-lg bg-surface border border-border">
          <div className="text-[11px] text-muted-text font-medium">IN CONSULTATION</div>
          <div className="text-2xl font-bold font-mono text-amber-400 mt-1">{overview?.inConsultationCount}</div>
        </div>
        <div className="p-4 rounded-lg bg-surface border border-border">
          <div className="text-[11px] text-muted-text font-medium">COMPLETED</div>
          <div className="text-2xl font-bold font-mono text-emerald-400 mt-1">{overview?.completedCount}</div>
        </div>
        <div className="p-4 rounded-lg bg-surface border border-border">
          <div className="text-[11px] text-muted-text font-medium">EMERGENCIES</div>
          <div className="text-2xl font-bold font-mono text-emergency mt-1">{overview?.emergencyCount}</div>
        </div>
        <div className="p-4 rounded-lg bg-surface border border-border">
          <div className="text-[11px] text-muted-text font-medium">NO-SHOWS</div>
          <div className="text-2xl font-bold font-mono text-muted-text mt-1">{overview?.noShowCount}</div>
        </div>
      </div>

      <div className="bg-surface border border-border rounded-lg p-6 shadow-xl">
        <div className="flex items-center justify-between pb-4 border-b border-border">
          <div className="flex items-center space-x-2">
            <span className="w-2.5 h-2.5 rounded-full bg-accent animate-pulse"></span>
            <span className="text-xs font-semibold uppercase tracking-wider text-secondary-text">
              Active Patient Focus
            </span>
          </div>

          <button
            onClick={handleCallNext}
            disabled={actionLoading || (overview?.waitingCount === 0)}
            className="py-2 px-4 rounded bg-accent hover:bg-accent-hover text-white text-xs font-semibold tracking-wide transition-colors flex items-center space-x-2 disabled:opacity-40"
          >
            <span>CALL NEXT PATIENT</span>
            <ArrowRight className="w-3.5 h-3.5" />
          </button>
        </div>

        {current ? (
          <div className="mt-6 flex flex-col md:flex-row items-start md:items-center justify-between gap-6">
            <div>
              <div className="flex items-center space-x-3">
                <span className="text-4xl font-bold font-mono text-primary-text tracking-tight">
                  {current.tokenFormatted}
                </span>
                <Badge variant={current.isEmergency ? 'emergency' : 'normal'} size="md">
                  {current.isEmergency ? 'CRITICAL EMERGENCY' : current.status}
                </Badge>
              </div>

              <div className="mt-2 text-sm font-semibold text-primary-text">{current.patientName}</div>
              <div className="text-xs text-secondary-text">
                Appt #{current.appointmentNumber} • Phone: {current.patientPhone || 'N/A'}
              </div>
            </div>

            <div className="flex flex-wrap gap-2">
              {current.status === 'CALLED' && (
                <button
                  onClick={() => handleStartConsultation(current)}
                  disabled={actionLoading}
                  className="px-3.5 py-2 rounded bg-surface-elevated border border-accent/40 text-accent hover:bg-accent/15 text-xs font-medium flex items-center space-x-1.5 transition-colors"
                >
                  <Play className="w-3.5 h-3.5" />
                  <span>Start Consultation</span>
                </button>
              )}

              {current.status === 'IN_CONSULTATION' && (
                <button
                  onClick={() => setCompleteModalEntry(current)}
                  disabled={actionLoading}
                  className="px-4 py-2 rounded bg-success hover:bg-emerald-600 text-white text-xs font-semibold flex items-center space-x-1.5 transition-colors"
                >
                  <CheckCircle className="w-3.5 h-3.5" />
                  <span>Complete Consultation</span>
                </button>
              )}

              <button
                onClick={() => handleSkip(current)}
                disabled={actionLoading}
                className="px-3 py-2 rounded bg-surface-elevated border border-border hover:border-border-light text-secondary-text hover:text-primary-text text-xs flex items-center space-x-1.5 transition-colors"
              >
                <SkipForward className="w-3.5 h-3.5" />
                <span>Skip</span>
              </button>

              <button
                onClick={() => handleMarkNoShow(current)}
                disabled={actionLoading}
                className="px-3 py-2 rounded bg-surface-elevated border border-border hover:border-emergency/40 text-secondary-text hover:text-emergency text-xs flex items-center space-x-1.5 transition-colors"
              >
                <UserX className="w-3.5 h-3.5" />
                <span>Mark No-Show</span>
              </button>
            </div>
          </div>
        ) : (
          <div className="mt-6 py-6 text-center text-xs text-secondary-text">
            No patient is currently called or in consultation. Click <strong>"CALL NEXT PATIENT"</strong> to admit the next waiting token.
          </div>
        )}
      </div>

      <div className="bg-surface border border-border rounded-lg overflow-hidden">
        <div className="px-6 py-4 border-b border-border flex items-center justify-between">
          <h2 className="text-sm font-semibold text-primary-text">Pending Waiting Queue</h2>
          <span className="text-xs text-muted-text">{overview?.waitingQueue.length || 0} in line</span>
        </div>

        <div className="overflow-x-auto">
          <table className="w-full text-left text-xs">
            <thead className="bg-surface-elevated/60 text-secondary-text uppercase tracking-wider text-[11px] border-b border-border">
              <tr>
                <th className="px-6 py-3 font-medium">Token</th>
                <th className="px-6 py-3 font-medium">Priority</th>
                <th className="px-6 py-3 font-medium">Patient Name</th>
                <th className="px-6 py-3 font-medium">Appt ID</th>
                <th className="px-6 py-3 font-medium">Est. Wait</th>
                <th className="px-6 py-3 font-medium text-right">Quick Action</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {overview?.waitingQueue.length === 0 ? (
                <tr>
                  <td colSpan={6} className="px-6 py-8 text-center text-secondary-text">
                    All patients have been consulted. Queue is clear.
                  </td>
                </tr>
              ) : (
                overview?.waitingQueue.map((entry) => (
                  <tr
                    key={entry.id}
                    className={`hover:bg-surface-elevated/40 transition-colors ${
                      entry.isEmergency ? 'bg-emergency/5' : ''
                    }`}
                  >
                    <td className="px-6 py-4 font-mono font-bold text-sm">
                      <span className={entry.isEmergency ? 'text-emergency' : 'text-accent'}>
                        {entry.tokenFormatted}
                      </span>
                    </td>
                    <td className="px-6 py-4">
                      <Badge variant={entry.isEmergency ? 'emergency' : 'neutral'}>
                        {entry.isEmergency ? 'CRITICAL #1' : 'Routine'}
                      </Badge>
                    </td>
                    <td className="px-6 py-4 font-medium text-primary-text">{entry.patientName}</td>
                    <td className="px-6 py-4 font-mono text-muted-text">{entry.appointmentNumber}</td>
                    <td className="px-6 py-4 text-secondary-text">{entry.estimatedWaitMinutes} min</td>
                    <td className="px-6 py-4 text-right">
                      <button
                        onClick={() => handleStartConsultation(entry)}
                        className="px-2.5 py-1 rounded bg-surface border border-border text-[11px] text-secondary-text hover:text-accent hover:border-accent transition-colors"
                      >
                        Admit Now
                      </button>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </div>

      <Modal
        isOpen={!!completeModalEntry}
        onClose={() => setCompleteModalEntry(null)}
        title={`Complete Consultation — ${completeModalEntry?.tokenFormatted}`}
      >
        <div className="space-y-4">
          <div>
            <label className="block text-xs font-medium text-secondary-text mb-1">Primary Clinical Diagnosis</label>
            <input
              type="text"
              value={diagnosis}
              onChange={(e) => setDiagnosis(e.target.value)}
              placeholder="e.g. Acute Viral Bronchitis / Stage 1 Hypertension"
              className="w-full p-2 bg-surface-elevated border border-border rounded text-xs text-primary-text placeholder-muted-text focus:outline-none focus:border-accent"
            />
          </div>

          <div>
            <label className="block text-xs font-medium text-secondary-text mb-1">Clinical Observations & Notes</label>
            <textarea
              rows={3}
              value={notes}
              onChange={(e) => setNotes(e.target.value)}
              placeholder="Vital signs, auscultation findings, follow-up recommendations..."
              className="w-full p-2 bg-surface-elevated border border-border rounded text-xs text-primary-text placeholder-muted-text focus:outline-none focus:border-accent"
            />
          </div>

          <div>
            <label className="block text-xs font-medium text-secondary-text mb-1">Prescription / Treatment Plan</label>
            <textarea
              rows={2}
              value={prescription}
              onChange={(e) => setPrescription(e.target.value)}
              placeholder="Rx medications, dosages, dietary or therapeutic directives..."
              className="w-full p-2 bg-surface-elevated border border-border rounded text-xs text-primary-text placeholder-muted-text focus:outline-none focus:border-accent"
            />
          </div>

          <div className="flex justify-end space-x-3 pt-2">
            <button
              onClick={() => setCompleteModalEntry(null)}
              className="px-3 py-1.5 rounded border border-border text-xs text-secondary-text hover:text-primary-text"
            >
              Cancel
            </button>
            <button
              onClick={handleCompleteConsultation}
              disabled={actionLoading}
              className="px-4 py-1.5 rounded bg-success hover:bg-emerald-600 text-white text-xs font-semibold transition-colors disabled:opacity-50"
            >
              Save & Finalize Visit
            </button>
          </div>
        </div>
      </Modal>
    </div>
  );
};