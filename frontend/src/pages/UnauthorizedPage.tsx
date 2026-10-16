import React from 'react';
import { Link } from 'react-router-dom';
import { ShieldAlert } from 'lucide-react';
import { useAuth } from '../context/AuthContext';

const HOME_BY_ROLE: Record<string, string> = {
  DOCTOR: '/doctor',
  RECEPTIONIST: '/reception',
  ADMIN: '/admin',
  PATIENT: '/patient',
};

export const UnauthorizedPage: React.FC = () => {
  const { user } = useAuth();
  const homePath = (user && HOME_BY_ROLE[user.role]) || '/login';

  return (
    <div className="min-h-screen flex items-center justify-center bg-background text-primary-text px-4">
      <div className="text-center max-w-sm">
        <div className="w-14 h-14 mx-auto rounded-full bg-emergency/15 border border-emergency/30 flex items-center justify-center text-emergency mb-4">
          <ShieldAlert className="w-7 h-7" />
        </div>
        <h1 className="text-lg font-bold mb-1">Access denied</h1>
        <p className="text-sm text-secondary-text mb-6 leading-relaxed">
          {user
            ? `Your ${user.role.toLowerCase()} account doesn't have permission to view this page.`
            : "You don't have permission to view this page."}
        </p>
        <Link
          to={homePath}
          className="inline-block px-4 py-2 rounded bg-accent text-primary-text text-sm font-semibold hover:opacity-90 transition-opacity"
        >
          Back to my dashboard
        </Link>
      </div>
    </div>
  );
};
