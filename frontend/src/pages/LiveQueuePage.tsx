import React, { useState, useEffect } from 'react';
import api from '../api/client';
import { useWebSocket } from '../context/WebSocketContext';
import { Doctor, QueueBoard } from '../types';
import { Badge } from '../components/common/Badge';

/**
 * Shared waiting-room "queue board" — every role (Patient, Doctor, Receptionist, Admin)
 * can view this page (see the /queue-board route in App.tsx), so it deliberately only
 * ever shows token numbers and status, never another patient's name or phone number.
 * That full-detail view exists separately on the Doctor and Reception dashboards, which
 * are restricted to that doctor / reception / admin.
 */
export const LiveQueuePage: React.FC = () => {
  const [doctors, setDoctors] = useState<Doctor[]>([]);
  const [selectedDoctorId, setSelectedDoctorId] = useState<number | null>(null);
  const [board, setBoard] = useState<QueueBoard | null>(null);
  const [loading, setLoading] = useState(true);

  const { subscribeToQueueBoard } = useWebSocket();

  useEffect(() => {
    api.get<Doctor[]>('/doctors').then((res) => {
      setDoctors(res.data);
      if (res.data.length > 0) {
        setSelectedDoctorId(res.data[0].id);
        fetchBoard(res.data[0].id);
      }
    });
  }, []);

  const fetchBoard = async (docId: number) => {
    setLoading(true);
    try {
      const res = await api.get<QueueBoard>(`/queue/doctor/${docId}/board`);
      setBoard(res.data);
    } catch (err) {
      console.error(err);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (selectedDoctorId) {
      const unsubscribe = subscribeToQueueBoard(selectedDoctorId, (data) => {
        setBoard(data);
      });
      return () => unsubscribe();
    }
  }, [selectedDoctorId]);

  const handleDoctorChange = (id: number) => {
    setSelectedDoctorId(id);
    fetchBoard(id);
  };

  return (
    <div className="p-4 md:p-8 max-w-6xl mx-auto space-y-8">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div>
          <h1 className="text-xl font-bold text-primary-text tracking-wide">
            Hospital Operations Live Queue Board
          </h1>
          <p className="text-xs text-secondary-text mt-1">
            Real-time token status synchronized across clinic terminals via WebSockets
          </p>
        </div>

        {/* Doctor Selector */}
        <div className="flex items-center space-x-3">
          <label className="text-xs text-secondary-text font-medium">Physician:</label>
          <select
            value={selectedDoctorId || ''}
            onChange={(e) => handleDoctorChange(Number(e.target.value))}
            className="px-3 py-1.5 bg-surface border border-border rounded text-xs text-primary-text focus:outline-none focus:border-accent"
          >
            {doctors.map((doc) => (
              <option key={doc.id} value={doc.id}>
                {doc.fullName} ({doc.departmentName} • {doc.roomNumber})
              </option>
            ))}
          </select>
        </div>
      </div>

      {loading && !board ? (
        <div className="grid md:grid-cols-3 gap-4">
          {[0, 1, 2].map((i) => (
            <div key={i} className="h-32 bg-surface border border-border animate-pulse rounded-lg" />
          ))}
        </div>
      ) : (
        <>
          {/* BIG BOARD HERO DISPLAY */}
          <div className="grid md:grid-cols-3 gap-4">
            <div className="p-6 rounded-lg bg-surface border border-accent/40 shadow-xl text-center">
              <div className="text-[11px] font-semibold uppercase tracking-widest text-accent">NOW CONSULTING</div>
              <div className="text-5xl font-bold font-mono text-primary-text mt-3 tracking-tight">
                {board?.currentToken || '--'}
              </div>
              <div className="text-xs text-secondary-text mt-2">
                Room {board?.roomNumber || '—'}
              </div>
            </div>

            <div className="p-6 rounded-lg bg-surface border border-border text-center">
              <div className="text-[11px] font-semibold uppercase tracking-widest text-secondary-text">NEXT IN LINE</div>
              <div className="text-5xl font-bold font-mono text-primary-text mt-3 tracking-tight">
                {board?.nextToken || '--'}
              </div>
              <div className="text-xs text-secondary-text mt-2">
                {board?.nextToken ? 'Please be ready' : 'Queue Empty'}
              </div>
            </div>

            <div className="p-6 rounded-lg bg-surface border border-border text-center">
              <div className="text-[11px] font-semibold uppercase tracking-widest text-secondary-text">WAITING ROOM</div>
              <div className="text-5xl font-bold font-mono text-emerald-400 mt-3 tracking-tight">
                {board?.waitingCount || 0}
              </div>
              <div className="text-xs text-secondary-text mt-2">
                {board?.emergencyCount ? `${board.emergencyCount} emergency case(s)` : `${board?.departmentName || ''}`}
              </div>
            </div>
          </div>

          {/* TOKEN-ONLY LIVE QUEUE LIST */}
          <div className="bg-surface border border-border rounded-lg overflow-hidden">
            <div className="px-6 py-4 border-b border-border flex items-center justify-between">
              <h2 className="text-sm font-semibold text-primary-text">Live Queue Order</h2>
              <span className="text-xs text-muted-text">Sorted by: Priority Rank & Token Number</span>
            </div>

            <div className="overflow-x-auto">
              <table className="w-full text-left text-xs">
                <thead className="bg-surface-elevated/60 text-secondary-text uppercase tracking-wider text-[11px] border-b border-border">
                  <tr>
                    <th className="px-6 py-3 font-medium">Position</th>
                    <th className="px-6 py-3 font-medium">Token</th>
                    <th className="px-6 py-3 font-medium">Priority Level</th>
                    <th className="px-6 py-3 font-medium">Status</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-border">
                  {(!board || board.waitingTokens.length === 0) && !board?.currentToken ? (
                    <tr>
                      <td colSpan={4} className="px-6 py-8 text-center text-secondary-text">
                        No waiting tokens in queue.
                      </td>
                    </tr>
                  ) : (
                    <>
                      {board?.currentToken && (
                        <tr className="bg-accent/10 font-medium">
                          <td className="px-6 py-4 font-bold text-accent">● CURRENT</td>
                          <td className="px-6 py-4 font-mono font-bold text-accent text-sm">
                            {board.currentToken}
                          </td>
                          <td className="px-6 py-4">
                            <Badge variant="normal">Routine</Badge>
                          </td>
                          <td className="px-6 py-4 text-accent">IN_CONSULTATION</td>
                        </tr>
                      )}

                      {board?.waitingTokens.map((entry, idx) => (
                        <tr
                          key={entry.tokenFormatted}
                          className={`hover:bg-surface-elevated/40 transition-colors ${
                            entry.isEmergency ? 'bg-emergency/5' : ''
                          }`}
                        >
                          <td className="px-6 py-4 font-mono text-secondary-text">#{idx + 1}</td>
                          <td className="px-6 py-4 font-mono font-bold text-sm">
                            <span className={entry.isEmergency ? 'text-emergency' : 'text-primary-text'}>
                              {entry.tokenFormatted}
                            </span>
                          </td>
                          <td className="px-6 py-4">
                            <Badge variant={entry.isEmergency ? 'emergency' : 'neutral'}>
                              {entry.isEmergency ? 'CRITICAL #1' : 'Routine'}
                            </Badge>
                          </td>
                          <td className="px-6 py-4 text-secondary-text">{entry.status}</td>
                        </tr>
                      ))}
                    </>
                  )}
                </tbody>
              </table>
            </div>
          </div>
        </>
      )}
    </div>
  );
};
