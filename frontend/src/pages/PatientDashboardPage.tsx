import React, { useState, useEffect } from 'react';
import api from '../api/client';
import { useWebSocket } from '../context/WebSocketContext';
import { Appointment, PatientLiveQueue } from '../types';
import { Badge } from '../components/common/Badge';
import { Modal } from '../components/common/Modal';
import { Clock, UserCheck, Calendar, AlertCircle, Ban, RefreshCw, CheckCircle2, ChevronRight } from 'lucide-react';

export const PatientDashboardPage: React.FC = () => {
  const [appointments, setAppointments] = useState<Appointment[]>([]);
  const [activeQueue, setActiveQueue] = useState<PatientLiveQueue | null>(null);
  const [loading, setLoading] = useState(true);
  const [cancelModalAppt, setCancelModalAppt] = useState<Appointment | null>(null);
  const [cancelReason, setCancelReason] = useState('');
  const [cancelling, setCancelling] = useState(false);
  const [cancelError, setCancelError] = useState<string | null>(null);

  const { subscribeToQueueBoard } = useWebSocket();

  const fetchPatientData = async () => {
    try {
      const res = await api.get<Appointment[]>('/appointments/my');
      setAppointments(res.data);

      const active = res.data.find(
        (a) => a.status === 'IN_QUEUE' || a.status === 'CONFIRMED' || a.status === 'IN_CONSULTATION'
      );

      if (active) {
        const queueRes = await api.get<PatientLiveQueue>(`/queue/patient/appointment/${active.id}`);
        setActiveQueue(queueRes.data);
      } else {
        setActiveQueue(null);
      }
    } catch (err) {
      console.error('Failed to fetch patient data', err);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchPatientData();
  }, []);

  // Real-time WebSocket live queue listener. Uses the sanitized queue "board" channel
  // (token numbers/status only) rather than the full per-doctor queue channel: the
  // backend now restricts the full channel to that doctor/reception/admin, since it
  // carries every waiting patient's name and phone number. This board channel only
  // tells us "something changed for this doctor's queue today" — we then re-fetch our
  // own appointment/queue status over the authenticated REST endpoint, which already
  // enforces that a patient can only ever read their own data.
  useEffect(() => {
    const active = appointments.find((a) => a.status === 'IN_QUEUE' || a.status === 'IN_CONSULTATION');
    if (active) {
      const unsubscribe = subscribeToQueueBoard(active.doctorId, () => {
        fetchPatientData();
      });
      return () => unsubscribe();
    }
  }, [appointments]);

  const handleCancelAppointment = async () => {
    if (!cancelModalAppt) return;
    setCancelling(true);
    setCancelError(null);
    try {
      await api.patch(`/appointments/${cancelModalAppt.id}/cancel`, {
        reason: cancelReason || 'Cancelled by patient',
      });
      setCancelModalAppt(null);
      setCancelReason('');
      await fetchPatientData();
    } catch (err: any) {
      setCancelError(err.response?.data?.message || 'Failed to cancel appointment');
    } finally {
      setCancelling(false);
    }
  };

  if (loading) {
    return (
      <div className="p-8 space-y-4 max-w-5xl mx-auto">
        <div className="h-6 w-48 bg-surface-elevated animate-pulse rounded"></div>
        <div className="h-44 bg-surface border border-border animate-pulse rounded-lg"></div>
      </div>
    );
  }

  return (
    <div className="p-8 max-w-5xl mx-auto space-y-8">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-bold text-primary-text tracking-wide">Patient Consultation Queue</h1>
          <p className="text-xs text-secondary-text mt-1">Real-time token status, queue position, and scheduled appointments</p>
        </div>
        <button
          onClick={fetchPatientData}
          className="flex items-center space-x-1.5 px-3 py-1.5 rounded bg-surface border border-border text-xs text-secondary-text hover:text-primary-text transition-colors"
        >
          <RefreshCw className="w-3.5 h-3.5" />
          <span>Refresh</span>
        </button>
      </div>

      {/* TODAY'S LIVE TOKEN HERO METRICS (Direct from Section 10 of requirements) */}
      {activeQueue ? (
        <div className="bg-surface border border-border rounded-lg overflow-hidden shadow-xl">
          <div className="px-6 py-4 border-b border-border bg-surface-elevated/40 flex items-center justify-between">
            <div className="flex items-center space-x-3">
              <span className="text-xs font-mono text-muted-text">APPT #{activeQueue.appointmentNumber}</span>
              <span className="text-muted-text">•</span>
              <span className="text-xs font-medium text-primary-text">
                {activeQueue.doctorName} ({activeQueue.departmentName})
              </span>
              <span className="text-xs px-2 py-0.5 rounded bg-surface border border-border text-secondary-text">
                {activeQueue.roomNumber}
              </span>
            </div>
            <Badge
              variant={
                activeQueue.status === 'CALLED'
                  ? 'emergency'
                  : activeQueue.status === 'IN_CONSULTATION'
                  ? 'normal'
                  : 'neutral'
              }
              size="md"
            >
              {activeQueue.status}
            </Badge>
          </div>

          <div className="grid grid-cols-2 md:grid-cols-4 divide-y md:divide-y-0 md:divide-x divide-border">
            <div className="p-6 text-center">
              <div className="text-[11px] uppercase tracking-wider text-muted-text font-semibold">YOUR TOKEN</div>
              <div className="text-3xl font-bold font-mono text-accent mt-2 tracking-tight">
                {activeQueue.yourToken}
              </div>
              <div className="text-[11px] text-secondary-text mt-1">Scheduled: {activeQueue.appointmentTime}</div>
            </div>

            <div className="p-6 text-center">
              <div className="text-[11px] uppercase tracking-wider text-muted-text font-semibold">CURRENT TOKEN</div>
              <div className="text-3xl font-bold font-mono text-primary-text mt-2 tracking-tight">
                {activeQueue.currentToken}
              </div>
              <div className="text-[11px] text-secondary-text mt-1">In consultation</div>
            </div>

            <div className="p-6 text-center">
              <div className="text-[11px] uppercase tracking-wider text-muted-text font-semibold">PEOPLE AHEAD</div>
              <div className="text-3xl font-bold font-mono text-primary-text mt-2 tracking-tight">
                {activeQueue.peopleAhead}
              </div>
              <div className="text-[11px] text-secondary-text mt-1">Patients waiting</div>
            </div>

            <div className="p-6 text-center">
              <div className="text-[11px] uppercase tracking-wider text-muted-text font-semibold">ESTIMATED WAIT</div>
              <div className="text-3xl font-bold font-mono text-emerald-400 mt-2 tracking-tight">
                {activeQueue.estimatedWaitMinutes} <span className="text-base font-normal text-secondary-text">min</span>
              </div>
              <div className="text-[11px] text-secondary-text mt-1">Approximate transit</div>
            </div>
          </div>

          {/* QUEUE VISUALIZATION TIMELINE (Section 13) */}
          <div className="p-6 border-t border-border bg-surface-elevated/20">
            <div className="text-xs font-semibold uppercase tracking-wider text-secondary-text mb-4">
              Queue Position Timeline
            </div>
            <div className="flex flex-wrap items-center gap-2">
              {activeQueue.timeline.map((step, idx) => (
                <div key={idx} className="flex items-center">
                  <div
                    className={`px-3 py-1.5 rounded border text-xs font-mono font-medium flex items-center space-x-1.5 ${
                      step.isUserToken
                        ? 'bg-accent/20 border-accent text-accent font-bold ring-1 ring-accent'
                        : step.status === 'COMPLETED'
                        ? 'bg-surface border-border text-muted-text line-through'
                        : step.status === 'IN_CONSULTATION'
                        ? 'bg-primary-text/10 border-primary-text/30 text-primary-text'
                        : step.isEmergency
                        ? 'bg-emergency/20 border-emergency/50 text-emergency'
                        : 'bg-surface border-border text-secondary-text'
                    }`}
                  >
                    <span>{step.tokenFormatted}</span>
                    <span className="text-[10px] font-sans font-normal opacity-80">
                      {step.isUserToken ? '← You' : step.label === 'Completed' ? '✓' : step.label === 'Current' ? '● Current' : ''}
                    </span>
                  </div>
                  {idx < activeQueue.timeline.length - 1 && (
                    <ChevronRight className="w-3 h-3 text-muted-text mx-1 shrink-0" />
                  )}
                </div>
              ))}
            </div>
          </div>
        </div>
      ) : (
        <div className="p-8 rounded-lg bg-surface border border-border text-center">
          <Calendar className="w-8 h-8 text-muted-text mx-auto mb-2" />
          <h3 className="text-sm font-semibold text-primary-text">No Active Consultation Today</h3>
          <p className="text-xs text-secondary-text mt-1 max-w-sm mx-auto">
            You do not currently have a waiting token in the live hospital queue. Book a new appointment to receive a token.
          </p>
          <a
            href="/book"
            className="inline-block mt-4 px-4 py-2 rounded bg-accent hover:bg-accent-hover text-white text-xs font-semibold transition-colors"
          >
            Book Appointment
          </a>
        </div>
      )}

      {/* APPOINTMENT HISTORY TABLE */}
      <div className="bg-surface border border-border rounded-lg overflow-hidden">
        <div className="px-6 py-4 border-b border-border flex items-center justify-between">
          <h2 className="text-sm font-semibold text-primary-text">All Scheduled Consultations</h2>
          <span className="text-xs text-muted-text">{appointments.length} Total</span>
        </div>

        <div className="overflow-x-auto">
          <table className="w-full text-left text-xs">
            <thead className="bg-surface-elevated/60 text-secondary-text uppercase tracking-wider text-[11px] border-b border-border">
              <tr>
                <th className="px-6 py-3 font-medium">Appt ID</th>
                <th className="px-6 py-3 font-medium">Doctor & Dept</th>
                <th className="px-6 py-3 font-medium">Date & Time</th>
                <th className="px-6 py-3 font-medium">Token</th>
                <th className="px-6 py-3 font-medium">Status</th>
                <th className="px-6 py-3 font-medium text-right">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {appointments.length === 0 ? (
                <tr>
                  <td colSpan={6} className="px-6 py-8 text-center text-secondary-text">
                    No consultations recorded yet.
                  </td>
                </tr>
              ) : (
                appointments.map((a) => (
                  <tr key={a.id} className="hover:bg-surface-elevated/40 transition-colors">
                    <td className="px-6 py-4 font-mono font-medium text-primary-text">
                      {a.appointmentNumber}
                    </td>
                    <td className="px-6 py-4">
                      <div className="font-medium text-primary-text">{a.doctorName}</div>
                      <div className="text-[11px] text-muted-text">{a.departmentName} • {a.doctorRoom}</div>
                    </td>
                    <td className="px-6 py-4">
                      <div className="text-primary-text">{a.appointmentDate}</div>
                      <div className="text-[11px] text-muted-text">{a.appointmentTime}</div>
                    </td>
                    <td className="px-6 py-4 font-mono font-bold text-accent">
                      {a.tokenFormatted || '--'}
                    </td>
                    <td className="px-6 py-4">
                      <Badge
                        variant={
                          a.status === 'COMPLETED'
                            ? 'success'
                            : a.status === 'CANCELLED'
                            ? 'neutral'
                            : a.status === 'IN_QUEUE'
                            ? 'normal'
                            : 'neutral'
                        }
                      >
                        {a.status}
                      </Badge>
                    </td>
                    <td className="px-6 py-4 text-right">
                      {a.canCancel && (
                        <button
                          onClick={() => {
                            setCancelModalAppt(a);
                            setCancelError(null);
                          }}
                          className="px-2.5 py-1 rounded border border-border text-secondary-text hover:text-emergency hover:border-emergency/40 text-[11px] transition-colors"
                        >
                          Cancel
                        </button>
                      )}
                      {!a.canCancel && a.status !== 'CANCELLED' && a.status !== 'COMPLETED' && (
                        <span className="text-[10px] text-muted-text" title="Cancellation locked within 30 min of visit">
                          Lock Window
                        </span>
                      )}
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </div>

      {/* CANCELLATION MODAL */}
      <Modal
        isOpen={!!cancelModalAppt}
        onClose={() => setCancelModalAppt(null)}
        title="Cancel Appointment"
      >
        <div className="space-y-4">
          <div className="p-3 rounded bg-surface-elevated border border-border text-xs space-y-1">
            <div className="text-secondary-text">
              Appointment: <strong className="text-primary-text">{cancelModalAppt?.appointmentNumber}</strong>
            </div>
            <div className="text-secondary-text">
              Physician: <strong className="text-primary-text">{cancelModalAppt?.doctorName}</strong>
            </div>
            <div className="text-secondary-text">
              Scheduled: <strong className="text-primary-text">{cancelModalAppt?.appointmentDate} at {cancelModalAppt?.appointmentTime}</strong>
            </div>
          </div>

          <p className="text-xs text-secondary-text">
            Appointments can be cancelled up to 30 minutes prior to scheduled consultation.
            Once confirmed, your token will be released to the hospital queue.
          </p>

          <div>
            <label className="block text-xs font-medium text-secondary-text mb-1">Cancellation Reason</label>
            <textarea
              rows={2}
              value={cancelReason}
              onChange={(e) => setCancelReason(e.target.value)}
              placeholder="e.g., Personal conflict, symptoms resolved"
              className="w-full p-2 bg-surface-elevated border border-border rounded text-xs text-primary-text placeholder-muted-text focus:outline-none focus:border-accent"
            />
          </div>

          {cancelError && (
            <div className="p-2.5 rounded bg-emergency/15 border border-emergency/30 text-xs text-emergency">
              {cancelError}
            </div>
          )}

          <div className="flex justify-end space-x-3 pt-2">
            <button
              onClick={() => setCancelModalAppt(null)}
              className="px-3 py-1.5 rounded border border-border text-xs text-secondary-text hover:text-primary-text"
            >
              Keep Appointment
            </button>
            <button
              onClick={handleCancelAppointment}
              disabled={cancelling}
              className="px-3 py-1.5 rounded bg-emergency hover:bg-red-600 text-white text-xs font-semibold transition-colors disabled:opacity-50"
            >
              {cancelling ? 'Releasing Slot...' : 'Confirm Cancellation'}
            </button>
          </div>
        </div>
      </Modal>
    </div>
  );
};