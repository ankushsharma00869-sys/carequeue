import React, { createContext, useContext, useState, useEffect } from 'react';
import api from '../api/client';
import { User, AuthResponse, RegisterRequest } from '../types';

interface AuthContextType {
  user: User | null;
  token: string | null;
  loading: boolean;
  login: (email: string, password: string) => Promise<void>;
  register: (request: RegisterRequest) => Promise<void>;
  logout: () => void;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

export const AuthProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [user, setUser] = useState<User | null>(() => {
    const saved = localStorage.getItem('cq_user');
    return saved ? JSON.parse(saved) : null;
  });
  const [token, setToken] = useState<string | null>(() => localStorage.getItem('cq_token'));
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    // Always re-validate against the server when we have a token, even if a user
    // is already cached. The cache is only for an instant first paint — the
    // server's /auth/me response is what actually decides the role from here.
    if (token) {
      api.get('/auth/me')
        .then((res) => {
          setUser(res.data);
          localStorage.setItem('cq_user', JSON.stringify(res.data));
        })
        .catch(() => {
          logout();
        });
    }
  }, [token]);

  const login = async (email: string, password: string) => {
    setLoading(true);
    try {
      const res = await api.post<AuthResponse>('/auth/login', { email, password });
      const { token: jwt, userId, fullName, role, doctorId, patientId } = res.data;
      const userData: User = { id: userId, email, fullName, role, doctorId, patientId };

      localStorage.setItem('cq_token', jwt);
      localStorage.setItem('cq_user', JSON.stringify(userData));
      setToken(jwt);
      setUser(userData);
    } finally {
      setLoading(false);
    }
  };

  const register = async (request: RegisterRequest) => {
    setLoading(true);
    try {
      // Note: request never includes a role. The backend's POST /api/auth/register
      // ignores any client-supplied role for public self-service sign-up and always
      // creates a PATIENT account — this is a defense-in-depth mirror of that rule,
      // not the actual security boundary.
      const res = await api.post<AuthResponse>('/auth/register', request);
      const { token: jwt, userId, fullName, role, doctorId, patientId } = res.data;
      const userData: User = { id: userId, email: request.email, fullName, role, doctorId, patientId };

      localStorage.setItem('cq_token', jwt);
      localStorage.setItem('cq_user', JSON.stringify(userData));
      setToken(jwt);
      setUser(userData);
    } finally {
      setLoading(false);
    }
  };

  const logout = () => {
    localStorage.removeItem('cq_token');
    localStorage.removeItem('cq_user');
    setToken(null);
    setUser(null);
  };

  return (
    <AuthContext.Provider value={{ user, token, loading, login, register, logout }}>
      {children}
    </AuthContext.Provider>
  );
};

export const useAuth = () => {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return context;
};