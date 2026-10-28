import React, { useEffect, useState } from 'react';
import { NavLink, useLocation } from 'react-router-dom';
import { useAuth } from '../../context/AuthContext';
import {
  Activity,
  CalendarCheck,
  Clock,
  Stethoscope,
  Users,
  AlertTriangle,
  BarChart3,
  FileSearch,
  Layers,
  ShieldCheck,
  Bell,
  X,
} from 'lucide-react';

export const Sidebar: React.FC = () => {
  const { user } = useAuth();
  // Mobile drawer state. Hooks must come before the early return below.
  const [open, setOpen] = useState(false);
  const location = useLocation();
  useEffect(() => setOpen(false), [location.pathname]);
  // The header's menu button asks for the drawer to open.
  useEffect(() => {
    const openDrawer = () => setOpen(true);
    window.addEventListener('cq:open-menu', openDrawer);
    return () => window.removeEventListener('cq:open-menu', openDrawer);
  }, []);
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false);
    };
    const previousOverflow = document.body.style.overflow;
    document.addEventListener('keydown', onKey);
    document.body.style.overflow = 'hidden';
    return () => {
      document.removeEventListener('keydown', onKey);
      document.body.style.overflow = previousOverflow;
    };
  }, [open]);
  if (!user) return null;

  const linkClass = ({ isActive }: { isActive: boolean }) =>
    `flex items-center space-x-3 px-3.5 py-2.5 rounded text-xs font-medium transition-colors ${
      isActive
        ? 'bg-accent/15 text-accent border border-accent/30 font-semibold'
        : 'text-secondary-text hover:text-primary-text hover:bg-surface-elevated border border-transparent'
    }`;

  return (
    <>
      {open && <div className="md:hidden fixed inset-0 z-40 bg-black/60" onClick={() => setOpen(false)} />}
      <aside
        className={`fixed inset-y-0 left-0 z-50 w-60 bg-surface border-r border-border flex flex-col select-none transform transition-transform duration-200 ${
          open ? 'translate-x-0' : '-translate-x-full'
        } md:static md:translate-x-0 md:flex md:shrink-0 md:min-h-screen`}
      >
        <button
          type="button"
          onClick={() => setOpen(false)}
          className="md:hidden absolute top-3 right-3 z-10 w-11 h-11 rounded flex items-center justify-center text-secondary-text hover:text-primary-text"
          aria-label="Close menu"
        >
          <X className="w-5 h-5" />
        </button>
      {/* Brand Header */}
      <div className="h-14 flex items-center px-4 border-b border-border space-x-2.5">
        <div className="w-8 h-8 rounded bg-accent/15 border border-accent/30 flex items-center justify-center text-accent">
          <Activity className="w-4 h-4" />
        </div>
        <div>
          <span className="font-bold text-sm tracking-wide text-primary-text">CareQueue</span>
          <span className="text-[10px] block text-secondary-text font-mono uppercase tracking-widest">Ops System</span>
        </div>
      </div>

      {/* Role-Specific Navigation */}
      <div className="flex-1 py-4 px-3 space-y-6 overflow-y-auto">
        {user.role === 'PATIENT' && (
          <div className="space-y-1">
            <div className="text-[10px] font-semibold tracking-wider uppercase text-muted-text px-3 mb-2">
              Patient Portal
            </div>
            <NavLink to="/patient" className={linkClass}>
              <Clock className="w-4 h-4" />
              <span>Today's Token</span>
            </NavLink>
            <NavLink to="/book" className={linkClass}>
              <CalendarCheck className="w-4 h-4" />
              <span>Book Appointment</span>
            </NavLink>
            <NavLink to="/queue-board" className={linkClass}>
              <Layers className="w-4 h-4" />
              <span>Live Queue Board</span>
            </NavLink>
            <NavLink to="/notifications" className={linkClass}>
              <Bell className="w-4 h-4" />
              <span>Notifications</span>
            </NavLink>
            <NavLink to="/ai-screening" className={linkClass}>
              <FileSearch className="w-4 h-4" />
              <span>Pre-Screening AI</span>
            </NavLink>
          </div>
        )}

        {user.role === 'DOCTOR' && (
          <div className="space-y-1">
            <div className="text-[10px] font-semibold tracking-wider uppercase text-muted-text px-3 mb-2">
              Physician Station
            </div>
            <NavLink to="/doctor" className={linkClass}>
              <Stethoscope className="w-4 h-4" />
              <span>Consultation Queue</span>
            </NavLink>
            <NavLink to="/queue-board" className={linkClass}>
              <Layers className="w-4 h-4" />
              <span>Live Queue Board</span>
            </NavLink>
          </div>
        )}

        {user.role === 'RECEPTIONIST' && (
          <div className="space-y-1">
            <div className="text-[10px] font-semibold tracking-wider uppercase text-muted-text px-3 mb-2">
              Reception Operations
            </div>
            <NavLink to="/reception" className={linkClass}>
              <Users className="w-4 h-4" />
              <span>Arrivals & Check-In</span>
            </NavLink>
            <NavLink to="/book" className={linkClass}>
              <CalendarCheck className="w-4 h-4" />
              <span>Walk-In Booking</span>
            </NavLink>
            <NavLink to="/queue-board" className={linkClass}>
              <Layers className="w-4 h-4" />
              <span>Live Queue Board</span>
            </NavLink>
            <NavLink to="/analytics" className={linkClass}>
              <BarChart3 className="w-4 h-4" />
              <span>Operations Analytics</span>
            </NavLink>
          </div>
        )}

        {user.role === 'ADMIN' && (
          <div className="space-y-1">
            <div className="text-[10px] font-semibold tracking-wider uppercase text-muted-text px-3 mb-2">
              Hospital Governance
            </div>
            <NavLink to="/admin" className={linkClass}>
              <ShieldCheck className="w-4 h-4" />
              <span>Operations Overview</span>
            </NavLink>
            <NavLink to="/reception" className={linkClass}>
              <Users className="w-4 h-4" />
              <span>Reception Desk</span>
            </NavLink>
            <NavLink to="/queue-board" className={linkClass}>
              <Layers className="w-4 h-4" />
              <span>Live Queue Board</span>
            </NavLink>
            <NavLink to="/analytics" className={linkClass}>
              <BarChart3 className="w-4 h-4" />
              <span>Clinical Analytics</span>
            </NavLink>
          </div>
        )}
      </div>

      {/* Emergency Quick Action Bar in Sidebar */}
      {(user.role === 'RECEPTIONIST' || user.role === 'ADMIN') && (
        <div className="p-3 border-t border-border">
          <NavLink
            to="/reception?action=emergency"
            className="flex items-center justify-center space-x-2 w-full py-2 px-3 rounded bg-emergency/15 hover:bg-emergency/25 text-emergency border border-emergency/30 text-xs font-semibold tracking-wide transition-colors"
          >
            <AlertTriangle className="w-3.5 h-3.5" />
            <span>CODE RED TRIAGE</span>
          </NavLink>
        </div>
      )}

      {/* Bottom Status Info */}
      <div className="p-3 border-t border-border text-[11px] text-muted-text flex items-center justify-between">
        <span>CareQueue v1.0</span>
        <span className="font-mono text-[10px]">BUILD 2026</span>
      </div>
    </aside>
    </>
  );
};