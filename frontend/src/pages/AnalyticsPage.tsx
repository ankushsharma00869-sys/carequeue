import React, { useState, useEffect } from 'react';
import api from '../api/client';
import { AnalyticsOverview } from '../types';
import { BarChart3, TrendingUp, Clock, AlertTriangle, Users, CheckCircle2, XCircle, RefreshCw } from 'lucide-react';
import { BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer, CartesianGrid, LineChart, Line } from 'recharts';

export const AnalyticsPage: React.FC = () => {
  const [data, setData] = useState<AnalyticsOverview | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const fetchAnalytics = async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await api.get<AnalyticsOverview>('/analytics/overview');
      setData(res.data);
    } catch (err: any) {
      // Keep any previous data out of the way: a failed refresh must not look like fresh numbers.
      setData(null);
      setError(err.response?.data?.message || 'Could not load analytics. Check your connection and try again.');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchAnalytics();
  }, []);

  if (error) {
    return (
      <div className="p-8 max-w-6xl mx-auto">
        <div className="p-4 rounded bg-emergency/15 border border-emergency/30 text-xs text-emergency">{error}</div>
        <button onClick={fetchAnalytics} className="mt-4 text-xs text-accent hover:text-accent-hover">Try again</button>
      </div>
    );
  }

  if (loading || !data) {
    return (
      <div className="p-8 space-y-4 max-w-6xl mx-auto">
        <div className="h-6 w-48 bg-surface-elevated animate-pulse rounded"></div>
        <div className="grid grid-cols-4 gap-4">
          {[1, 2, 3, 4].map((i) => (
            <div key={i} className="h-24 bg-surface border border-border animate-pulse rounded-lg"></div>
          ))}
        </div>
      </div>
    );
  }

  return (
    <div className="p-8 max-w-6xl mx-auto space-y-8">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-bold text-primary-text tracking-wide">
            Hospital Operations Analytics
          </h1>
          <p className="text-xs text-secondary-text mt-1">
            Real-time clinical throughput, patient wait duration, and department workload distributions
          </p>
        </div>

        <button
          onClick={fetchAnalytics}
          className="flex items-center space-x-1.5 px-3 py-1.5 rounded bg-surface border border-border text-xs text-secondary-text hover:text-primary-text transition-colors"
        >
          <RefreshCw className="w-3.5 h-3.5" />
          <span>Refresh Metrics</span>
        </button>
      </div>

      {/* KPI METRIC CARDS (Direct from Section 30) */}
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-4">
        <div className="p-4 rounded-lg bg-surface border border-border shadow-md">
          <div className="flex items-center justify-between text-muted-text text-xs">
            <span>TOTAL SCHEDULED</span>
            <Users className="w-4 h-4 text-accent" />
          </div>
          <div className="text-2xl font-bold font-mono text-primary-text mt-2">
            {data.totalAppointmentsToday}
          </div>
          <div className="text-[11px] text-secondary-text mt-1">Today's patient volume</div>
        </div>

        <div className="p-4 rounded-lg bg-surface border border-border shadow-md">
          <div className="flex items-center justify-between text-muted-text text-xs">
            <span>COMPLETED VISITS</span>
            <CheckCircle2 className="w-4 h-4 text-emerald-400" />
          </div>
          <div className="text-2xl font-bold font-mono text-emerald-400 mt-2">
            {data.completedToday}
          </div>
          <div className="text-[11px] text-secondary-text mt-1">Discharged / Consulted</div>
        </div>

        <div className="p-4 rounded-lg bg-surface border border-border shadow-md">
          <div className="flex items-center justify-between text-muted-text text-xs">
            <span>AVERAGE WAIT TIME</span>
            <Clock className="w-4 h-4 text-amber-400" />
          </div>
          <div className="text-2xl font-bold font-mono text-amber-400 mt-2">
            {data.averageWaitMinutes} <span className="text-sm font-normal text-secondary-text">min</span>
          </div>
          <div className="text-[11px] text-secondary-text mt-1">Check-in to consult</div>
        </div>

        <div className="p-4 rounded-lg bg-surface border border-border shadow-md">
          <div className="flex items-center justify-between text-muted-text text-xs">
            <span>DOCTOR UTILIZATION</span>
            <TrendingUp className="w-4 h-4 text-purple-400" />
          </div>
          <div className="text-2xl font-bold font-mono text-purple-400 mt-2">
            {data.doctorUtilizationPercentage == null ? 'Not tracked' : `${data.doctorUtilizationPercentage}%`}
          </div>
          <div className="text-[11px] text-secondary-text mt-1">Clinic shift efficiency</div>
        </div>
      </div>

      {/* SECONDARY ROW */}
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-4">
        <div className="p-3 rounded bg-surface-elevated/40 border border-border flex items-center justify-between text-xs">
          <span className="text-secondary-text">Active in Queue:</span>
          <strong className="font-mono text-accent">{data.waitingNow}</strong>
        </div>
        <div className="p-3 rounded bg-surface-elevated/40 border border-border flex items-center justify-between text-xs">
          <span className="text-secondary-text">Cancelled Appointments:</span>
          <strong className="font-mono text-muted-text">{data.cancelledToday}</strong>
        </div>
        <div className="p-3 rounded bg-surface-elevated/40 border border-border flex items-center justify-between text-xs">
          <span className="text-secondary-text">Patient No-Shows:</span>
          <strong className="font-mono text-amber-400">{data.noShowsToday}</strong>
        </div>
        <div className="p-3 rounded bg-surface-elevated/40 border border-border flex items-center justify-between text-xs">
          <span className="text-secondary-text">Emergency Cases Triaged:</span>
          <strong className="font-mono text-emergency">{data.emergencyCasesToday}</strong>
        </div>
      </div>

      {/* RECHARTS: DEPARTMENT VOLUME & PEAK HOURS (Section 30 requirement) */}
      <div className="grid lg:grid-cols-2 gap-6">
        {/* Department Distribution */}
        <div className="bg-surface border border-border rounded-lg p-6 shadow-xl space-y-4">
          <h2 className="text-sm font-semibold text-primary-text">Department Patient Volume</h2>
          <div className="h-64">
            <ResponsiveContainer width="100%" height="100%">
              <BarChart data={data.departmentDistribution}>
                <CartesianGrid strokeDasharray="3 3" stroke="#242E42" />
                <XAxis dataKey="departmentName" stroke="#94A3B8" fontSize={10} tickLine={false} />
                <YAxis stroke="#94A3B8" fontSize={11} tickLine={false} />
                <Tooltip
                  contentStyle={{ backgroundColor: '#141A24', borderColor: '#242E42', fontSize: '11px' }}
                  itemStyle={{ color: '#0EA5E9' }}
                />
                <Bar dataKey="count" fill="#0EA5E9" radius={[4, 4, 0, 0]} />
              </BarChart>
            </ResponsiveContainer>
          </div>
        </div>

        {/* Hourly Check-In Load */}
        <div className="bg-surface border border-border rounded-lg p-6 shadow-xl space-y-4">
          <h2 className="text-sm font-semibold text-primary-text">Peak Consultation & Check-In Hours</h2>
          <div className="h-64">
            <ResponsiveContainer width="100%" height="100%">
              <LineChart data={data.hourlyCheckIns}>
                <CartesianGrid strokeDasharray="3 3" stroke="#242E42" />
                <XAxis dataKey="hourLabel" stroke="#94A3B8" fontSize={10} tickLine={false} />
                <YAxis stroke="#94A3B8" fontSize={11} tickLine={false} />
                <Tooltip
                  contentStyle={{ backgroundColor: '#141A24', borderColor: '#242E42', fontSize: '11px' }}
                />
                <Line type="monotone" dataKey="checkIns" stroke="#10B981" strokeWidth={2} dot={{ r: 3 }} name="Check-Ins" />
              </LineChart>
            </ResponsiveContainer>
          </div>
        </div>
      </div>

      {/* DOCTOR UTILIZATION TABLE */}
      <div className="bg-surface border border-border rounded-lg overflow-hidden">
        <div className="px-6 py-4 border-b border-border flex items-center justify-between">
          <h2 className="text-sm font-semibold text-primary-text">Physician Workload Breakdown</h2>
          <span className="text-xs text-muted-text">{data.doctorUtilization.length} Active Physicians</span>
        </div>

        <div className="overflow-x-auto">
          <table className="w-full text-left text-xs">
            <thead className="bg-surface-elevated/60 text-secondary-text uppercase tracking-wider text-[11px] border-b border-border">
              <tr>
                <th className="px-6 py-3 font-medium">Physician</th>
                <th className="px-6 py-3 font-medium">Department</th>
                <th className="px-6 py-3 font-medium">Assigned Visits</th>
                <th className="px-6 py-3 font-medium">Completed</th>
                <th className="px-6 py-3 font-medium">Avg Slot Duration</th>
                <th className="px-6 py-3 font-medium text-right">Completion Rate</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {data.doctorUtilization.map((doc) => {
                const rate = doc.totalAssigned > 0 ? Math.round((doc.completedPatients * 100) / doc.totalAssigned) : 0;
                return (
                  <tr key={doc.doctorId} className="hover:bg-surface-elevated/40 transition-colors">
                    <td className="px-6 py-4 font-semibold text-primary-text">{doc.doctorName}</td>
                    <td className="px-6 py-4 text-secondary-text">{doc.departmentName}</td>
                    <td className="px-6 py-4 font-mono">{doc.totalAssigned}</td>
                    <td className="px-6 py-4 font-mono text-emerald-400">{doc.completedPatients}</td>
                    <td className="px-6 py-4 text-secondary-text font-mono">{doc.averageConsultationMinutes} min</td>
                    <td className="px-6 py-4 text-right font-mono font-bold">
                      <span className={rate >= 75 ? 'text-emerald-400' : 'text-amber-400'}>{rate}%</span>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
};