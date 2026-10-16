import React, { createContext, useContext, useEffect, useRef } from 'react';
import { Client, StompSubscription } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { useAuth } from './AuthContext';

interface WebSocketContextType {
  // Full-detail queue (patient names + phone numbers): staff-only. The backend rejects SUBSCRIBE to
  // this topic for anyone who isn't the assigned doctor, a receptionist, or an admin.
  // Do NOT use this from patient-facing pages.
  subscribeToDoctorQueue: (doctorId: number, callback: (data: any) => void) => () => void;
  // Sanitized queue board (token numbers/status only): safe for any authenticated role,
  // including patients.
  subscribeToQueueBoard: (doctorId: number, callback: (data: any) => void) => () => void;
  subscribeToPatientUpdates: (patientId: number, callback: (data: any) => void) => () => void;
  connected: boolean;
}

const WebSocketContext = createContext<WebSocketContextType | undefined>(undefined);

// Same origin by default: nginx proxies /ws/ to the backend, so the page at 127.0.0.1:3001 connects to
// 127.0.0.1:3001/ws. VITE_WS_URL overrides this only for a split deployment (frontend and backend on
// different hosts). The previous hard-coded http://localhost:8080 pointed at a different stack entirely.
function resolveWsBase(): string {
  const configured = import.meta.env.VITE_WS_URL;
  return configured && configured.length > 0 ? configured : window.location.origin;
}

export const WebSocketProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const clientRef = useRef<Client | null>(null);
  const [connected, setConnected] = React.useState(false);
  const { token } = useAuth();

  // Callbacks per destination. They live in refs, so they survive reconnects, and a page can subscribe
  // before the socket is connected: the subscription is attached as soon as the connection is up.
  const handlersRef = useRef(new Map<string, Set<(data: any) => void>>());
  const subsRef = useRef(new Map<string, StompSubscription>());

  const attach = (destination: string) => {
    const client = clientRef.current;
    if (!client || !client.connected || subsRef.current.has(destination)) return;
    const sub = client.subscribe(destination, (message) => {
      let data: any;
      try {
        data = JSON.parse(message.body);
      } catch (err) {
        console.error('WS parse error', destination, err);
        return;
      }
      handlersRef.current.get(destination)?.forEach((cb) => cb(data));
    });
    subsRef.current.set(destination, sub);
  };

  useEffect(() => {
    // No one is logged in yet (or they just logged out): don't attempt to connect.
    if (!token) {
      clientRef.current = null;
      subsRef.current.clear();
      setConnected(false);
      return;
    }

    const socket = new SockJS(`${resolveWsBase()}/ws`);
    const stompClient = new Client({
      webSocketFactory: () => socket as any,
      connectHeaders: {
        Authorization: `Bearer ${token}`,
      },
      debug: () => {},
      reconnectDelay: 5000,
      heartbeatIncoming: 4000,
      heartbeatOutgoing: 4000,
      onConnect: () => {
        setConnected(true);
        // Subscriptions do not survive a reconnect: re-attach everything anyone is waiting for.
        subsRef.current.clear();
        handlersRef.current.forEach((_, destination) => attach(destination));
      },
      onDisconnect: () => {
        subsRef.current.clear();
        setConnected(false);
      },
      onStompError: () => {
        subsRef.current.clear();
        setConnected(false);
      },
      onWebSocketClose: () => {
        subsRef.current.clear();
        setConnected(false);
      },
    });

    clientRef.current = stompClient;
    stompClient.activate();

    return () => {
      stompClient.deactivate();
      clientRef.current = null;
      subsRef.current.clear();
    };
  }, [token]);

  const subscribe = (destination: string, callback: (data: any) => void) => {
    let set = handlersRef.current.get(destination);
    if (!set) {
      set = new Set();
      handlersRef.current.set(destination, set);
    }
    set.add(callback);
    attach(destination);

    return () => {
      const current = handlersRef.current.get(destination);
      if (!current) return;
      current.delete(callback);
      if (current.size === 0) {
        handlersRef.current.delete(destination);
        const sub = subsRef.current.get(destination);
        subsRef.current.delete(destination);
        try {
          sub?.unsubscribe();
        } catch {
          // The connection is already gone; nothing to unsubscribe from.
        }
      }
    };
  };

  const subscribeToDoctorQueue = (doctorId: number, callback: (data: any) => void) =>
    subscribe(`/topic/queue/${doctorId}`, callback);

  const subscribeToQueueBoard = (doctorId: number, callback: (data: any) => void) =>
    subscribe(`/topic/queue/${doctorId}/board`, callback);

  const subscribeToPatientUpdates = (patientId: number, callback: (data: any) => void) =>
    subscribe(`/topic/patient/${patientId}`, callback);

  return (
    <WebSocketContext.Provider
      value={{ subscribeToDoctorQueue, subscribeToQueueBoard, subscribeToPatientUpdates, connected }}
    >
      {children}
    </WebSocketContext.Provider>
  );
};

export const useWebSocket = () => {
  const context = useContext(WebSocketContext);
  if (!context) {
    throw new Error('useWebSocket must be used within a WebSocketProvider');
  }
  return context;
};
