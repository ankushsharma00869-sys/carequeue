export type Role = 'PATIENT' | 'DOCTOR' | 'RECEPTIONIST' | 'ADMIN';

export type AppointmentStatus =
  | 'SCHEDULED'
  | 'CONFIRMED'
  | 'CHECKED_IN'
  | 'IN_QUEUE'
  | 'IN_CONSULTATION'
  | 'COMPLETED'
  | 'CANCELLED'
  | 'NO_SHOW';

export type QueueStatus =
  | 'BOOKED'
  | 'WAITING'
  | 'CALLED'
  | 'IN_CONSULTATION'
  | 'COMPLETED'
  | 'SKIPPED'
  | 'NO_SHOW';

export type QueuePriority = 'CRITICAL' | 'PRIORITY' | 'NORMAL';

export interface User {
  id: number;
  email: string;
  fullName: string;
  phoneNumber?: string;
  role: Role;
  doctorId?: number;
  patientId?: number;
  departmentName?: string;
  hospitalName?: string;
}

export interface RegisterRequest {
  fullName: string;
  email: string;
  password: string;
  phoneNumber?: string;
  gender?: string;
  bloodGroup?: string;
}

export interface AuthResponse {
  token: string;
  tokenType: string;
  userId: number;
  email: string;
  fullName: string;
  role: Role;
  doctorId?: number;
  patientId?: number;
}

export interface Hospital {
  id: number;
  name: string;
  code: string;
  address: string;
  city: string;
  phone: string;
  emergencyContact: string;
  active: boolean;
}

export interface Department {
  id: number;
  hospitalId: number;
  hospitalName: string;
  name: string;
  code: string;
  description: string;
  floorNumber: string;
  icon: string;
  active: boolean;
}

export interface Doctor {
  id: number;
  userId: number;
  fullName: string;
  email: string;
  phone: string;
  hospitalId: number;
  hospitalName: string;
  departmentId: number;
  departmentName: string;
  licenseNumber: string;
  specialization: string;
  roomNumber: string;
  consultationFee: number;
  bio: string;
  averageConsultationMinutes: number;
  active: boolean;
}

export interface TimeSlot {
  time: string;
  formattedTime: string;
  available: boolean;
  reason: string;
}

export interface DoctorAvailability {
  doctorId: number;
  doctorName: string;
  departmentName: string;
  date: string;
  dayOfWeek: string;
  onDuty: boolean;
  shiftStart: string;
  shiftEnd: string;
  slotDurationMinutes: number;
  availableSlots: TimeSlot[];
  totalSlots: number;
  bookedSlots: number;
}

export interface Appointment {
  id: number;
  appointmentNumber: string;
  patientId: number;
  patientName: string;
  patientPhone: string;
  doctorId: number;
  doctorName: string;
  doctorSpecialization: string;
  doctorRoom: string;
  hospitalId: number;
  hospitalName: string;
  departmentId: number;
  departmentName: string;
  appointmentDate: string;
  appointmentTime: string;
  status: AppointmentStatus;
  tokenFormatted: string;
  estimatedWaitMinutes: number;
  patientNotes?: string;
  cancellationReason?: string;
  canCancel: boolean;
  noShowRiskLevel: 'LOW' | 'MEDIUM' | 'HIGH';
  noShowRiskScore: number;
  createdAt: string;
}

export interface QueueEntry {
  id: number;
  appointmentId?: number;
  appointmentNumber?: string;
  doctorId: number;
  doctorName: string;
  patientId: number;
  patientName: string;
  patientPhone?: string;
  scheduleDate: string;
  tokenNumber: number;
  tokenFormatted: string;
  priority: QueuePriority;
  priorityRank: number;
  status: QueueStatus;
  peopleAhead: number;
  estimatedWaitMinutes: number;
  isCurrent: boolean;
  isNext: boolean;
  isEmergency: boolean;
  calledAt?: string;
  startedAt?: string;
  completedAt?: string;
  roomNumber: string;
}

/**
 * Sanitized "queue board" view — token numbers/status only, no patient names or phone
 * numbers. Backed by GET /api/queue/doctor/{doctorId}/board and the
 * /topic/queue/{doctorId}/board WebSocket topic, both of which any authenticated user
 * (patients included) may read. Use DoctorQueueOverview instead for the doctor/reception
 * full-detail view, which is restricted to that doctor, receptionists and admins.
 */
export interface QueueBoardEntry {
  tokenFormatted: string;
  status: QueueStatus;
  isEmergency: boolean;
}

export interface QueueBoard {
  doctorId: number;
  doctorName: string;
  departmentName: string;
  roomNumber: string;
  date: string;
  currentToken: string | null;
  nextToken: string | null;
  waitingCount: number;
  emergencyCount: number;
  waitingTokens: QueueBoardEntry[];
}

export interface DoctorQueueOverview {
  doctorId: number;
  doctorName: string;
  specialization: string;
  roomNumber: string;
  departmentName: string;
  date: string;
  currentPatient?: QueueEntry;
  nextPatient?: QueueEntry;
  totalPatientsToday: number;
  waitingCount: number;
  inConsultationCount: number;
  completedCount: number;
  skippedCount: number;
  emergencyCount: number;
  noShowCount: number;
  waitingQueue: QueueEntry[];
  completedQueue: QueueEntry[];
}

export interface QueueTimelineStep {
  tokenFormatted: string;
  label: string;
  status: QueueStatus;
  isUserToken: boolean;
  isEmergency: boolean;
}

export interface PatientLiveQueue {
  appointmentId: number;
  appointmentNumber: string;
  yourToken: string;
  currentToken: string;
  nextToken: string;
  peopleAhead: number;
  estimatedWaitMinutes: number;
  status: QueueStatus;
  doctorName: string;
  departmentName: string;
  roomNumber: string;
  appointmentDate: string;
  appointmentTime: string;
  timeline: QueueTimelineStep[];
}

export interface AnalyticsOverview {
  totalAppointmentsToday: number;
  completedToday: number;
  waitingNow: number;
  cancelledToday: number;
  noShowsToday: number;
  emergencyCasesToday: number;
  averageWaitMinutes: number;
  doctorUtilizationPercentage: number | null; // null = not tracked by the system
  departmentDistribution: {
    departmentName: string;
    count: number;
    percentage: number;
  }[];
  hourlyCheckIns: {
    hourLabel: string;
    checkIns: number;
    consultations: number | null; // null = not tracked
  }[];
  doctorUtilization: {
    doctorId: number;
    doctorName: string;
    departmentName: string;
    completedPatients: number;
    totalAssigned: number;
    averageConsultationMinutes: number;
  }[];
}

export interface SymptomScreeningResponse {
  symptomSummary: string;
  suggestedDepartment: string;
  urgencyLevel: string;
  questionsForDoctor: string[];
  clinicalReasoning: string;
  isEmergencyAlert: boolean;
  medicalDisclaimer: string;
}