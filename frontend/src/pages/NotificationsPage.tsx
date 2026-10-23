import React, { useCallback, useEffect, useState } from 'react';
import api from '../api/client';
import { Bell, CheckCheck } from 'lucide-react';

interface AppNotification {
  id: number;
  type: string;
  title: string;
  message: string;
  read: boolean;
  createdAt: string;
}

/**
 * The signed-in user's own notifications. The backend scopes GET /api/notifications to the
 * JWT principal, so no user id is ever sent from here.
 */
export const NotificationsPage: React.FC = () => {
  const [items, setItems] = useState<AppNotification[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const res = await api.get<{ content: AppNotification[] }>('/notifications', { params: { size: 50 } });
      setItems(res.data.content);
      setError(null);
    } catch (err: any) {
      setError(err.response?.data?.message || 'Could not load notifications. Check your connection and try again.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const markRead = async (id: number) => {
    await api.patch(`/notifications/${id}/read`);
    setItems((prev) => prev.map((n) => (n.id === id ? { ...n, read: true } : n)));
  };

  const markAllRead = async () => {
    await api.patch('/notifications/read-all');
    setItems((prev) => prev.map((n) => ({ ...n, read: true })));
  };

  const unread = items.filter((n) => !n.read).length;

  return (
    <div className="p-4 sm:p-8 max-w-3xl mx-auto space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-bold text-primary-text tracking-wide">Notifications</h1>
          <p className="text-xs text-secondary-text mt-1">{unread} unread</p>
        </div>
        {unread > 0 && (
          <button onClick={markAllRead} className="flex items-center gap-1.5 text-xs text-accent hover:text-accent-hover">
            <CheckCheck className="w-3.5 h-3.5" /> Mark all as read
          </button>
        )}
      </div>

      {loading && <div className="h-24 bg-surface border border-border rounded-lg animate-pulse" />}
      {error && <div className="p-3 rounded bg-emergency/15 border border-emergency/30 text-xs text-emergency">{error}</div>}

      {!loading && !error && items.length === 0 && (
        <div className="p-4 md:p-8 text-center text-xs text-secondary-text bg-surface border border-border rounded-lg">
          <Bell className="w-5 h-5 mx-auto mb-2 text-muted-text" />
          No notifications yet. Booking confirmations and "your token was called" alerts will appear here.
        </div>
      )}

      <ul className="space-y-2">
        {items.map((n) => (
          <li
            key={n.id}
            className={`p-4 rounded-lg border text-xs ${n.read ? 'bg-surface border-border' : 'bg-accent/5 border-accent/30'}`}
          >
            <div className="flex items-start justify-between gap-3">
              <div>
                <div className="font-semibold text-primary-text">{n.title}</div>
                <div className="text-secondary-text mt-1">{n.message}</div>
                <div className="text-[11px] text-muted-text mt-2">{new Date(n.createdAt).toLocaleString()}</div>
              </div>
              {!n.read && (
                <button onClick={() => markRead(n.id)} className="text-[11px] text-accent hover:text-accent-hover whitespace-nowrap">
                  Mark read
                </button>
              )}
            </div>
          </li>
        ))}
      </ul>
    </div>
  );
};
