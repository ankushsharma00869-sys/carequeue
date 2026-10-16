import React, { useState, useEffect, useCallback } from 'react';
import api from '../api/client';
import { Hospital, Department, Doctor, AnalyticsOverview } from '../types';
import { ShieldCheck, Building2, Stethoscope, Users, Layers, Activity } from 'lucide-react';
import { AnalyticsPage } from './AnalyticsPage';
import { StaffManagementPanel } from '../components/admin/StaffManagementPanel';
import { FacilityManagementPanel } from '../components/admin/FacilityManagementPanel';
import { DoctorScheduleEditor } from '../components/admin/DoctorScheduleEditor';

export const AdminDashboardPage: React.FC = () => {
  const [hospitals, setHospitals] = useState<Hospital[]>([]);
  const [departments, setDepartments] = useState<Department[]>([]);
  const [doctors, setDoctors] = useState<Doctor[]>([]);

  const refreshDoctors = useCallback(() => {
    api.get<Doctor[]>('/doctors').then((res) => setDoctors(res.data));
  }, []);

  const refreshFacilities = useCallback(() => {
    api.get<Hospital[]>('/hospitals').then((res) => setHospitals(res.data));
    api.get<Department[]>('/departments').then((res) => setDepartments(res.data));
  }, []);

  useEffect(() => {
    refreshFacilities();
    refreshDoctors();
  }, [refreshFacilities, refreshDoctors]);

  return (
    <div className="space-y-8">
      {/* Top Embedded Operations Metrics */}
      <AnalyticsPage />

      {/* Facilities & Staff Infrastructure Ledger */}
      <div className="px-8 pb-12 max-w-6xl mx-auto space-y-6">
        <h2 className="text-base font-bold text-primary-text tracking-wide flex items-center gap-2">
          <Building2 className="w-4 h-4 text-accent" />
          <span>Active Clinical Facilities & Staff Directory</span>
        </h2>

        <div className="grid md:grid-cols-3 gap-4">
          <div className="p-5 rounded-lg bg-surface border border-border space-y-3">
            <div className="flex items-center justify-between">
              <span className="text-xs font-semibold text-secondary-text uppercase tracking-wider">Hospitals</span>
              <Building2 className="w-4 h-4 text-accent" />
            </div>
            <div className="text-2xl font-bold font-mono text-primary-text">{hospitals.length}</div>
            <ul className="text-xs text-secondary-text space-y-1">
              {hospitals.map((h) => (
                <li key={h.id} className="truncate">• {h.name} ({h.code})</li>
              ))}
            </ul>
          </div>

          <div className="p-5 rounded-lg bg-surface border border-border space-y-3">
            <div className="flex items-center justify-between">
              <span className="text-xs font-semibold text-secondary-text uppercase tracking-wider">Departments</span>
              <Layers className="w-4 h-4 text-teal-400" />
            </div>
            <div className="text-2xl font-bold font-mono text-primary-text">{departments.length}</div>
            <ul className="text-xs text-secondary-text space-y-1">
              {departments.slice(0, 4).map((d) => (
                <li key={d.id} className="truncate">• {d.name} ({d.floorNumber})</li>
              ))}
            </ul>
          </div>

          <div className="p-5 rounded-lg bg-surface border border-border space-y-3">
            <div className="flex items-center justify-between">
              <span className="text-xs font-semibold text-secondary-text uppercase tracking-wider">Credentialed Physicians</span>
              <Stethoscope className="w-4 h-4 text-purple-400" />
            </div>
            <div className="text-2xl font-bold font-mono text-primary-text">{doctors.length}</div>
            <ul className="text-xs text-secondary-text space-y-1">
              {doctors.slice(0, 4).map((doc) => (
                <li key={doc.id} className="truncate">• {doc.fullName} ({doc.roomNumber})</li>
              ))}
            </ul>
          </div>
        </div>

        <FacilityManagementPanel hospitals={hospitals} onChanged={refreshFacilities} />
        <DoctorScheduleEditor />
        <StaffManagementPanel hospitals={hospitals} departments={departments} onDoctorCreated={refreshDoctors} />
      </div>
    </div>
  );
};