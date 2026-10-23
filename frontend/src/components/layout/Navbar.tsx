import React from 'react';
import { useAuth } from '../../context/AuthContext';
import { useWebSocket } from '../../context/WebSocketContext';
import { Building2, LogOut, Radio } from 'lucide-react';
import { Role } from '../../types';

export const Navbar: React.FC = () => {
  const { user, logout } = useAuth();
  const { connected } = useWebSocket();

  const roleBadges: Record<Role, { label: string; bg: string }> = {
    PATIENT: { label: 'PATIENT', bg: 'bg-accent/20 text-accent border-accent/40' },
    DOCTOR: { label: 'PHYSICIAN', bg: 'bg-teal-500/20 text-teal-400 border-teal-500/40' },
    RECEPTIONIST: { label: 'RECEPTION DESK', bg: 'bg-amber-500/20 text-amber-400 border-amber-500/40' },
    ADMIN: { label: 'ADMINISTRATION', bg: 'bg-purple-500/20 text-purple-400 border-purple-500/40' },
  };

  return (
    <header className="h-14 border-b border-border bg-surface px-6 flex items-center justify-between sticky top-0 z-30 select-none">
      <div className="flex items-center space-x-6">
        <div className="flex items-center space-x-2 text-xs font-medium text-secondary-text">
          <Building2 className="w-4 h-4 text-accent" />
          <span className="text-primary-text font-semibold tracking-wide">Lucknow Hospital</span>
          <span className="text-muted-text">• Bakshi Ka Talab, Lucknow</span>
        </div>

        <div className="flex items-center space-x-1.5 px-2.5 py-0.5 rounded-full bg-surface-elevated border border-border text-xs">
          <Radio className={`w-3 h-3 ${connected ? 'text-success animate-pulse' : 'text-muted-text'}`} />
          <span className={connected ? 'text-success font-medium' : 'text-muted-text'}>
            {connected ? 'Live Sync Active' : 'Connecting WS...'}
          </span>
        </div>
      </div>

      <div className="flex items-center space-x-5">
        {/* User Identity & Logout */}
        {user && (
          <div className="flex items-center space-x-3 pl-3 border-l border-border">
            <div className="text-right">
              <div className="text-xs font-medium text-primary-text">{user.fullName}</div>
              <div className="flex items-center justify-end mt-0.5">
                <span className={`text-[10px] uppercase tracking-wider px-1.5 py-0.2 rounded border ${roleBadges[user.role]?.bg}`}>
                  {roleBadges[user.role]?.label}
                </span>
              </div>
            </div>

            <button
              onClick={logout}
              title="Sign Out"
              className="p-1.5 text-secondary-text hover:text-emergency hover:bg-surface-elevated rounded border border-transparent hover:border-border transition-colors"
            >
              <LogOut className="w-4 h-4" />
            </button>
          </div>
        )}
      </div>
    </header>
  );
};