import axios from 'axios';

// In production (Vercel), VITE_API_URL must be set to the Render backend URL
// e.g. https://carequeue-backend.onrender.com
// In local dev, Vite proxy handles /api → http://localhost:8081 (this stack's backend port)
const baseURL = import.meta.env.VITE_API_URL
  ? `${import.meta.env.VITE_API_URL}/api`
  : '/api';

const api = axios.create({
  baseURL,
  headers: {
    'Content-Type': 'application/json',
  },
});

api.interceptors.request.use((config) => {
  const token = localStorage.getItem('cq_token');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

api.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401) {
      // Avoid redirecting if already on login
      if (!window.location.pathname.includes('/login')) {
        localStorage.removeItem('cq_token');
        localStorage.removeItem('cq_user');
        window.location.href = '/login';
      }
    }
    return Promise.reject(error);
  }
);

export default api;