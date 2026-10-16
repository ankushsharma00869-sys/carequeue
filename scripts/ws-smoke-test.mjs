// WebSocket (STOMP) smoke test against the running Docker stack.
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { Client } from '@stomp/stompjs';
import WebSocket from 'ws';

const args = process.argv.slice(2);
const arg = (name, fallback) => {
  const i = args.indexOf(name);
  return i >= 0 ? args[i + 1] : fallback;
};
const HTTP = arg('--http', 'http://localhost:8081');
const WS = arg('--ws', 'ws://localhost:8081/ws');

const here = path.dirname(fileURLToPath(import.meta.url));
const envText = await readFile(path.join(here, '..', '.env'), 'utf8');
const adminPassword = envText.match(/^BOOTSTRAP_ADMIN_PASSWORD=(.*)$/m)?.[1]?.trim();
if (!adminPassword) throw new Error('BOOTSTRAP_ADMIN_PASSWORD not found in .env');

let failures = 0;
const check = (name, ok) => {
  console.log((ok ? 'PASS  ' : 'FAIL  ') + name);
  if (!ok) failures++;
};

async function api(method, route, token, body) {
  const res = await fetch(HTTP + route, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: 'Bearer ' + token } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  let json = null;
  try { json = text ? JSON.parse(text) : null; } catch { /* non-JSON body */ }
  return { status: res.status, json };
}

async function login(email, password) {
  const r = await api('POST', '/api/auth/login', null, { email, password });
  if (r.status !== 200) throw new Error(`login failed for ${email}: HTTP ${r.status}`);
  return r.json;
}

async function waitFor(condition, ms = 10000) {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    if (condition()) return true;
    await new Promise((r) => setTimeout(r, 100));
  }
  return false;
}

function connect(token) {
  const state = { connected: false, closed: false, errors: [], messages: {} };
  const client = new Client({
    webSocketFactory: () => new WebSocket(WS),
    connectHeaders: token ? { Authorization: 'Bearer ' + token } : {},
    reconnectDelay: 0,
    onConnect: () => { state.connected = true; },
    onStompError: (frame) => state.errors.push(frame.headers?.message || 'STOMP ERROR'),
    onWebSocketError: () => state.errors.push('websocket error'),
    onWebSocketClose: () => { state.closed = true; },
  });
  client.activate();
  return { client, state };
}

function subscribe(conn, destination) {
  conn.state.messages[destination] = [];
  conn.client.subscribe(destination, (m) => {
    try {
      conn.state.messages[destination].push(JSON.parse(m.body));
    } catch { /* ignore non-JSON */ }
  });
}

const pad = (n) => String(n).padStart(2, '0');
const stamp = Date.now();
const digits = String(stamp).slice(-6);

// ---------------------------------------------------------------- set up data over HTTP

const admin = await login('admin@carequeue.com', adminPassword);
check('admin login returns the ADMIN role', admin.role === 'ADMIN');

const hospitalId = (await api('GET', '/api/hospitals', admin.token)).json[0].id;
const departmentId = (await api('GET', `/api/hospitals/${hospitalId}/departments`, admin.token)).json[0].id;

const schedule = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'].map((d) => ({
  dayOfWeek: d, startTime: '00:00:00', endTime: '23:45:00',
  slotDurationMinutes: 15, maxPatients: 40, active: true,
}));

const doctorPassword = `WsDoc#${stamp}`;
const doc = await api('POST', '/api/admin/doctors', admin.token, {
  fullName: `WS Doctor ${stamp}`, email: `ws.doctor.${stamp}@example.com`, password: doctorPassword,
  phoneNumber: `9400${digits}`, hospitalId, departmentId,
  licenseNumber: `LIC-WS-${stamp}`, specialization: 'WebSocket test', initialSchedule: schedule,
});
check('admin creates a doctor with a schedule', doc.status === 200);
const doctorId = doc.json.id;
const doctor = await login(`ws.doctor.${stamp}@example.com`, doctorPassword);

const recPassword = `WsRec#${stamp}`;
const rec = await api('POST', '/api/admin/receptionists', admin.token, {
  fullName: `WS Reception ${stamp}`, email: `ws.reception.${stamp}@example.com`, password: recPassword,
  phoneNumber: `9500${digits}`, hospitalId,
});
check('admin creates a receptionist', rec.status === 200);
const reception = await login(`ws.reception.${stamp}@example.com`, recPassword);

async function newPatient(label) {
  const password = `WsPat#${stamp}${label}`;
  const email = `ws.patient${label}.${stamp}@example.com`;
  const signup = await api('POST', '/api/auth/register', null, {
    fullName: `WS Patient ${label} ${stamp}`, email, password, phoneNumber: `9600${digits}`,
  });
  if (signup.status !== 200) throw new Error(`signup failed for ${label}: HTTP ${signup.status}`);
  return login(email, password);
}
const patient = await newPatient('a');
const other = await newPatient('b');
check('two patients signed up and logged in', !!patient.token && !!other.token);

// Next quarter-hour at least 10 minutes from now, on this machine's clock (hospital zone).
const base = new Date(Date.now() + 10 * 60 * 1000);
const slot = new Date(base);
slot.setMinutes(Math.ceil(base.getMinutes() / 15) * 15, 0, 0);
const date = `${slot.getFullYear()}-${pad(slot.getMonth() + 1)}-${pad(slot.getDate())}`;
const time = `${pad(slot.getHours())}:${pad(slot.getMinutes())}:00`;

const booking = await api('POST', '/api/appointments', patient.token, {
  hospitalId, departmentId, doctorId, appointmentDate: date, appointmentTime: time,
});
check('patient books an appointment (CONFIRMED)', booking.status === 200 && booking.json?.status === 'CONFIRMED');
const apptId = booking.json.id;
const ticket = booking.json.tokenFormatted;

// ---------------------------------------------------------------- STOMP checks

const patientWs = connect(patient.token);
const doctorWs = connect(doctor.token);
const otherWs = connect(other.token);
const badWs = connect('not.a.jwt');

check('patient connects over STOMP with a valid JWT', await waitFor(() => patientWs.state.connected));
check('doctor connects over STOMP with a valid JWT', await waitFor(() => doctorWs.state.connected));
check('invalid JWT is refused at STOMP CONNECT',
  await waitFor(() => badWs.state.errors.length > 0 || badWs.state.closed) && !badWs.state.connected);

const patientTopic = `/topic/patient/${patient.patientId}`;
const doctorTopic = `/topic/queue/${doctorId}`;
subscribe(patientWs, patientTopic);
subscribe(doctorWs, doctorTopic);
subscribe(otherWs, doctorTopic); // another patient must not be allowed on the doctor's queue
await new Promise((r) => setTimeout(r, 800)); // let the server register the subscriptions

check('a second patient is refused the doctor queue topic',
  await waitFor(() => otherWs.state.errors.length > 0 || otherWs.state.closed, 5000) &&
  (otherWs.state.messages[doctorTopic] || []).length === 0);

// ---------------------------------------------------------------- drive the queue over HTTP

const checkIn = await api('POST', `/api/queue/reception/check-in/${apptId}`, reception.token);
check('receptionist checks the patient in', checkIn.status === 200);

const called = await api('POST', `/api/queue/doctor/${doctorId}/call-next`, doctor.token);
check('doctor calls the next patient', called.status === 200 && called.json?.currentPatient?.tokenFormatted === ticket);

check('patient receives the call over STOMP on their own channel',
  await waitFor(() => (patientWs.state.messages[patientTopic] || []).some((m) => m.queueEntry?.tokenFormatted === ticket)));

check('doctor receives the queue update over STOMP on the queue topic',
  await waitFor(() => (doctorWs.state.messages[doctorTopic] || []).some((m) => m.currentPatient?.tokenFormatted === ticket)));

for (const conn of [patientWs, doctorWs, otherWs, badWs]) {
  try { await conn.client.deactivate(); } catch { /* already closed */ }
}

console.log('');
if (failures > 0) {
  console.log(`${failures} check(s) failed.`);
  process.exit(1);
}
console.log('All WebSocket checks passed.');
