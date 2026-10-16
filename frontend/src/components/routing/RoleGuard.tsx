import React from 'react';
import { Navigate, Outlet } from 'react-router-dom';
import { useAuth } from '../../context/AuthContext';
import { Role } from '../../types';

interface RoleGuardProps {
  allow: Role[];
}

/**
 * Guards a group of nested routes so only the listed roles can render them.
 * This is a UX convenience, not the security boundary — the backend enforces
 * the real authorization on every API call regardless of what the frontend
 * renders. This just stops a logged-in user of the wrong role from landing
 * on a page that isn't theirs (e.g. Patient manually navigating to /admin)
 * and sends them to a clear "not allowed" page instead.
 */
export const RoleGuard: React.FC<RoleGuardProps> = ({ allow }) => {
  const { user } = useAuth();

  if (!user) {
    return <Navigate to="/login" replace />;
  }

  if (!allow.includes(user.role)) {
    return <Navigate to="/unauthorized" replace />;
  }

  return <Outlet />;
};
