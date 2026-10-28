import React from 'react';
import { useAuth } from '../../context/AuthContext';
import { useWebSocket } from '../../context/WebSocketContext';
import { Building2, LogOut, Menu, Radio } from 'lucide-react';
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

  // The drawer lives in Sidebar and listens for this event.
  const openMenu = () => window.dispatchEvent(new CustomEvent('cq:open-menu'));

  return (
    <header className="h-14 border-b border-border bg-surface px-3 sm:px-6 flex items-center justify-between gap-2 sticky top-0 z-30 select-none">
      <div className="flex items-center gap-1 sm:gap-6 min-w-0 flex-1">
        {/* Menu button: phones and tablets only (desktop keeps the sidebar visible) */}
        <button
          type="button"
          onClick={openMenu}
          aria-label="Open menu"
          className="md:hidden -ml-1 w-11 h-11 rounded flex items-center justify-center text-primary-text hover:bg-surface-elevated flex-shrink-0"
        >
          <Menu className="w-5 h-5" />
        </button>

        <div className="flex items-center gap-2 text-xs font-medium text-secondary-text min-w-0">
          <Building2 className="hidden sm:block w-4 h-4 text-accent flex-shrink-0" />
          <span className="text-primary-text font-semibold tracking-wide truncate">Lucknow Hospital</span>
          <span className="hidden md:inline text-muted-text truncate">• Bakshi Ka Talab, Lucknow</span>
        </div>

        <div className="flex items-center gap-1.5 px-2 sm:px-2.5 py-0.5 rounded-full bg-surface-elevated border border-border text-xs flex-shrink-0">
          <Radio className={`w-3 h-3 ${connected ? 'text-success animate-pulse' : 'text-muted-text'}`} />
          <span className={`${connected ? 'text-success font-medium' : 'text-muted-text'} sr-only sm:not-sr-only`}>
            {connected ? 'Live Sync Active' : 'Connecting WS...'}
          </span>
        </div>
      </div>

      <div className="flex items-center flex-shrink-0">
        {user && (
          <div className="flex items-center gap-2 sm:gap-3 pl-2 sm:pl-3 border-l border-border">
            <div className="text-right min-w-0">
              <div className="text-xs font-medium text-primary-text max-w-[84px] sm:max-w-none truncate">{user.fullName}</div>
              <div className="flex items-center justify-end mt-0.5">
                <span className={`text-[10px] uppercase tracking-wider px-1.5 py-0.5 rounded border whitespace-nowrap ${roleBadges[user.role]?.bg}`}>
                  {roleBadges[user.role]?.label}
                </span>
              </div>
            </div>

            <button
              type="button"
              onClick={logout}
              title="Sign Out"
              aria-label="Sign out"
              className="w-11 h-11 sm:w-auto sm:h-auto sm:p-1.5 flex items-center justify-center text-secondary-text hover:text-emergency hover:bg-surface-elevated rounded border border-transparent hover:border-border transition-colors"
            >
              <LogOut className="w-4 h-4" />
            </button>
          </div>
        )}
      </div>
    </header>
  );
};
