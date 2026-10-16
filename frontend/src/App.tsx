import React from 'react';
import { BrowserRouter, Routes, Route, Navigate, Outlet } from 'react-router-dom';
import { AuthProvider, useAuth } from './context/AuthContext';
import { WebSocketProvider } from './context/WebSocketContext';
import { Navbar } from './components/layout/Navbar';
import { Sidebar } from './components/layout/Sidebar';
import { LoginPage } from './pages/LoginPage';
import { ActivateWalkInPage } from './pages/ActivateWalkInPage';
import { SignupPage } from './pages/SignupPage';
import { NotificationsPage } from './pages/NotificationsPage';
import { PatientDashboardPage } from './pages/PatientDashboardPage';
import { BookingWizardPage } from './pages/BookingWizardPage';
import { DoctorDashboardPage } from './pages/DoctorDashboardPage';
import { ReceptionDashboardPage } from './pages/ReceptionDashboardPage';
import { LiveQueuePage } from './pages/LiveQueuePage';
import { AiScreeningPage } from './pages/AiScreeningPage';
import { AnalyticsPage } from './pages/AnalyticsPage';
import { AdminDashboardPage } from './pages/AdminDashboardPage';
import { UnauthorizedPage } from './pages/UnauthorizedPage';
import { RoleGuard } from './components/routing/RoleGuard';

const ProtectedLayout: React.FC = () => {
  const { user } = useAuth();

  if (!user) {
    return <Navigate to="/login" replace />;
  }

  return (
    <div className="flex min-h-screen bg-background text-primary-text">
      <Sidebar />
      <div className="flex-1 flex flex-col min-w-0">
        <Navbar />
        <main className="flex-1 overflow-y-auto">
          <Outlet />
        </main>
      </div>
    </div>
  );
};

const RoleBasedRedirect: React.FC = () => {
  const { user } = useAuth();
  if (!user) return <Navigate to="/login" replace />;

  switch (user.role) {
    case 'DOCTOR':
      return <Navigate to="/doctor" replace />;
    case 'RECEPTIONIST':
      return <Navigate to="/reception" replace />;
    case 'ADMIN':
      return <Navigate to="/admin" replace />;
    case 'PATIENT':
    default:
      return <Navigate to="/patient" replace />;
  }
};

export const App: React.FC = () => {
  return (
    <AuthProvider>
      <WebSocketProvider>
        <BrowserRouter>
          <Routes>
            <Route path="/login" element={<LoginPage />} />
            <Route path="/signup" element={<SignupPage />} />
            <Route path="/unauthorized" element={<UnauthorizedPage />} />
            <Route path="/activate-walkin" element={<ActivateWalkInPage />} />

            <Route element={<ProtectedLayout />}>
              <Route path="/" element={<RoleBasedRedirect />} />

              <Route element={<RoleGuard allow={['PATIENT']} />}>
                <Route path="/patient" element={<PatientDashboardPage />} />
                <Route path="/ai-screening" element={<AiScreeningPage />} />
              </Route>

              <Route element={<RoleGuard allow={['PATIENT', 'RECEPTIONIST']} />}>
                <Route path="/book" element={<BookingWizardPage />} />
              </Route>

              <Route element={<RoleGuard allow={['DOCTOR']} />}>
                <Route path="/doctor" element={<DoctorDashboardPage />} />
              </Route>

              <Route element={<RoleGuard allow={['RECEPTIONIST', 'ADMIN']} />}>
                <Route path="/reception" element={<ReceptionDashboardPage />} />
              </Route>

              <Route element={<RoleGuard allow={['RECEPTIONIST', 'ADMIN']} />}>
                <Route path="/analytics" element={<AnalyticsPage />} />
              </Route>

              <Route element={<RoleGuard allow={['ADMIN']} />}>
                <Route path="/admin" element={<AdminDashboardPage />} />
              </Route>

              {/* Shared live status view — every role can see it */}
              <Route element={<RoleGuard allow={['PATIENT', 'DOCTOR', 'RECEPTIONIST', 'ADMIN']} />}>
                <Route path="/notifications" element={<NotificationsPage />} />
                <Route path="/queue-board" element={<LiveQueuePage />} />
              </Route>
            </Route>

            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </BrowserRouter>
      </WebSocketProvider>
    </AuthProvider>
  );
};