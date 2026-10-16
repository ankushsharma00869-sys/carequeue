import React, { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import api from '../api/client';
import { useAuth } from '../context/AuthContext';
import { WalkInPatientForm } from '../components/common/WalkInPatientForm';
import { Hospital, Department, Doctor, DoctorAvailability, TimeSlot } from '../types';
import { Check, ChevronRight, Clock, Building, Stethoscope, Calendar, AlertCircle } from 'lucide-react';

export const BookingWizardPage: React.FC = () => {
  const [step, setStep] = useState(1);
  const { user } = useAuth();
  const isStaffBooking = user?.role === 'RECEPTIONIST' || user?.role === 'ADMIN';
  const [patientQuery, setPatientQuery] = useState('');
  const [patientResults, setPatientResults] = useState<Array<{ id: number; fullName: string; phone: string; email: string }>>([]);
  const [selectedPatient, setSelectedPatient] = useState<{ id: number; fullName: string; phone: string; email: string } | null>(null);
  const [showWalkInForm, setShowWalkInForm] = useState(false);

  // Staff search for the patient while typing (walk-in booking).
  useEffect(() => {
    if (!isStaffBooking || patientQuery.trim().length < 2) {
      setPatientResults([]);
      return;
    }
    const timer = setTimeout(() => {
      api.get('/staff/patients', { params: { q: patientQuery.trim() } })
        .then((res) => setPatientResults(res.data))
        .catch(() => setPatientResults([]));
    }, 300);
    return () => clearTimeout(timer);
  }, [patientQuery, isStaffBooking]);
  const [hospitals, setHospitals] = useState<Hospital[]>([]);
  const [departments, setDepartments] = useState<Department[]>([]);
  const [doctors, setDoctors] = useState<Doctor[]>([]);
  const [availability, setAvailability] = useState<DoctorAvailability | null>(null);
  const [slotError, setSlotError] = useState<string | null>(null);

  const [selectedHospital, setSelectedHospital] = useState<Hospital | null>(null);
  const [selectedDept, setSelectedDept] = useState<Department | null>(null);
  const [selectedDoctor, setSelectedDoctor] = useState<Doctor | null>(null);
  const [selectedDate, setSelectedDate] = useState<string>(new Date().toISOString().split('T')[0]);
  const [selectedSlot, setSelectedSlot] = useState<TimeSlot | null>(null);
  const [patientNotes, setPatientNotes] = useState('');

  const [submitting, setSubmitting] = useState(false);
  const [bookingSuccess, setBookingSuccess] = useState<any | null>(null);
  const [bookingError, setBookingError] = useState<string | null>(null);

  const navigate = useNavigate();

  useEffect(() => {
    api.get<Hospital[]>('/hospitals').then((res) => setHospitals(res.data));
  }, []);

  const handleSelectHospital = async (h: Hospital) => {
    setSelectedHospital(h);
    const res = await api.get<Department[]>(`/hospitals/${h.id}/departments`);
    setDepartments(res.data);
    setStep(2);
  };

  const handleSelectDept = async (d: Department) => {
    setSelectedDept(d);
    const res = await api.get<Doctor[]>(`/departments/${d.id}/doctors`);
    setDoctors(res.data);
    setStep(3);
  };

  const handleSelectDoctor = async (doc: Doctor) => {
    setSelectedDoctor(doc);
    fetchAvailability(doc.id, selectedDate);
    setStep(4);
  };

  const handleDateChange = async (date: string) => {
    setSelectedDate(date);
    if (selectedDoctor) {
      fetchAvailability(selectedDoctor.id, date);
    }
  };

  const fetchAvailability = async (docId: number, date: string) => {
    setSlotError(null);
    try {
      const res = await api.get<DoctorAvailability>(`/doctors/${docId}/availability?date=${date}`);
      setAvailability(res.data);
    } catch (err) {
      console.error(err);
      setAvailability(null);
      setSlotError('Could not load time slots for this date. Please try another date or reload the page.');
    }
  };

  const handleSelectSlot = (slot: TimeSlot) => {
    if (!slot.available) return;
    setSelectedSlot(slot);
    setStep(5);
  };

  const handleConfirmBooking = async () => {
    if (!selectedHospital || !selectedDept || !selectedDoctor || !selectedSlot) return;
    if (isStaffBooking && !selectedPatient) return;

    setSubmitting(true);
    setBookingError(null);

    try {
      const payload = {
        hospitalId: selectedHospital.id,
        departmentId: selectedDept.id,
        doctorId: selectedDoctor.id,
        appointmentDate: selectedDate,
        appointmentTime: selectedSlot.time,
        patientNotes,
        patientId: isStaffBooking ? selectedPatient?.id : undefined,
      };

      const res = await api.post('/appointments', payload);
      setBookingSuccess(res.data);
      setStep(6);
    } catch (err: any) {
      setBookingError(err.response?.data?.message || 'Booking conflict or system error.');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="p-8 max-w-4xl mx-auto space-y-8">
      <div>
        <h1 className="text-xl font-bold text-primary-text tracking-wide">Hospital Consultation Booking</h1>
        <p className="text-xs text-secondary-text mt-1">Multi-step appointment scheduling and transactional queue token allocation</p>
      </div>

      <div className="flex items-center justify-between border-y border-border py-3 px-2 bg-surface/50 text-xs">
        {['Hospital', 'Department', 'Physician', 'Date & Slot', 'Review', 'Token Issued'].map((label, i) => (
          <div key={label} className="flex items-center space-x-2">
            <span
              className={`w-5 h-5 rounded-full flex items-center justify-center text-[10px] font-bold ${
                step > i + 1
                  ? 'bg-success text-white'
                  : step === i + 1
                  ? 'bg-accent text-white'
                  : 'bg-surface-elevated text-muted-text'
              }`}
            >
              {step > i + 1 ? <Check className="w-3 h-3" /> : i + 1}
            </span>
            <span className={step === i + 1 ? 'text-primary-text font-semibold' : 'text-muted-text'}>
              {label}
            </span>
            {i < 5 && <ChevronRight className="w-3.5 h-3.5 text-border-light mx-1" />}
          </div>
        ))}
      </div>

      {step === 1 && (
        <div className="space-y-4">
          <h2 className="text-sm font-semibold text-primary-text">Select Medical Facility</h2>
          <div className="grid md:grid-cols-2 gap-4">
            {hospitals.map((h) => (
              <div
                key={h.id}
                onClick={() => handleSelectHospital(h)}
                className="p-4 bg-surface border border-border hover:border-accent/50 rounded-lg cursor-pointer transition-colors space-y-2"
              >
                <div className="flex items-center justify-between">
                  <div className="font-semibold text-primary-text text-sm">{h.name}</div>
                  <span className="text-[10px] font-mono px-2 py-0.5 rounded bg-surface-elevated border border-border text-muted-text">
                    {h.code}
                  </span>
                </div>
                <p className="text-xs text-secondary-text">{h.address}, {h.city}</p>
                <div className="text-[11px] text-muted-text">Emergency Contact: {h.emergencyContact}</div>
              </div>
            ))}
          </div>
        </div>
      )}

      {step === 2 && (
        <div className="space-y-4">
          <div className="flex items-center justify-between">
            <h2 className="text-sm font-semibold text-primary-text">Select Clinical Department</h2>
            <button onClick={() => setStep(1)} className="text-xs text-accent hover:underline">Change Facility</button>
          </div>
          <div className="grid md:grid-cols-3 gap-3">
            {departments.map((d) => (
              <div
                key={d.id}
                onClick={() => handleSelectDept(d)}
                className="p-4 bg-surface border border-border hover:border-accent/50 rounded-lg cursor-pointer transition-colors"
              >
                <div className="font-semibold text-primary-text text-sm">{d.name}</div>
                <div className="text-[11px] text-secondary-text mt-1">{d.description}</div>
                <div className="text-[10px] text-muted-text mt-3 font-mono">{d.floorNumber}</div>
              </div>
            ))}
          </div>
        </div>
      )}

      {step === 3 && (
        <div className="space-y-4">
          <div className="flex items-center justify-between">
            <h2 className="text-sm font-semibold text-primary-text">Select Attending Physician</h2>
            <button onClick={() => setStep(2)} className="text-xs text-accent hover:underline">Change Department</button>
          </div>
          <div className="grid md:grid-cols-2 gap-4">
            {doctors.map((doc) => (
              <div
                key={doc.id}
                onClick={() => handleSelectDoctor(doc)}
                className="p-4 bg-surface border border-border hover:border-accent/50 rounded-lg cursor-pointer transition-colors space-y-2"
              >
                <div className="flex items-center justify-between">
                  <div className="font-semibold text-primary-text text-sm">{doc.fullName}</div>
                  <span className="text-xs text-accent font-semibold">${doc.consultationFee}</span>
                </div>
                <div className="text-xs text-teal-400">{doc.specialization}</div>
                <div className="text-xs text-secondary-text line-clamp-2">{doc.bio}</div>
                <div className="flex items-center justify-between pt-2 text-[11px] text-muted-text border-t border-border">
                  <span>{doc.roomNumber}</span>
                  <span>Avg Consult: {doc.averageConsultationMinutes}m</span>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {step === 4 && selectedDoctor && (
        <div className="space-y-6">
          <div className="flex items-center justify-between">
            <div>
              <h2 className="text-sm font-semibold text-primary-text">Select Consultation Slot</h2>
              <p className="text-xs text-secondary-text">{selectedDoctor.fullName} • {selectedDoctor.specialization}</p>
            </div>
            <button onClick={() => setStep(3)} className="text-xs text-accent hover:underline">Change Doctor</button>
          </div>

          <div className="flex items-center space-x-4">
            <label className="text-xs text-secondary-text font-medium">Appointment Date:</label>
            <input
              type="date"
              value={selectedDate}
              onChange={(e) => handleDateChange(e.target.value)}
              className="px-3 py-1.5 bg-surface border border-border rounded text-xs text-primary-text focus:outline-none focus:border-accent"
            />
          </div>

          {slotError && <p className="text-xs text-red-400">{slotError}</p>}
          {availability && !availability.onDuty && (
            <p className="text-xs text-secondary-text">This doctor is not on duty on this day. Please choose another date.</p>
          )}
          {availability && (
            <div className="space-y-3">
              <div className="flex items-center justify-between text-xs text-secondary-text">
                <span>Shift: {availability.shiftStart} - {availability.shiftEnd}</span>
                <span>{availability.bookedSlots} / {availability.totalSlots} Slots Reserved</span>
              </div>

              <div className="grid grid-cols-4 sm:grid-cols-6 gap-2">
                {availability.availableSlots.map((slot, idx) => (
                  <button
                    key={idx}
                    type="button"
                    disabled={!slot.available}
                    onClick={() => handleSelectSlot(slot)}
                    className={`py-2 px-1 text-center rounded border text-xs font-mono transition-colors ${
                      slot.available
                        ? 'bg-surface border-border hover:border-accent hover:bg-accent/10 text-primary-text cursor-pointer'
                        : 'bg-surface-elevated/40 border-transparent text-muted-text cursor-not-allowed line-through'
                    }`}
                  >
                    {slot.formattedTime}
                  </button>
                ))}
              </div>
            </div>
          )}
        </div>
      )}

      {step === 5 && selectedDoctor && selectedSlot && (
        <div className="space-y-6 max-w-lg mx-auto bg-surface border border-border rounded-lg p-6">
          <h2 className="text-base font-semibold text-primary-text">Confirm Appointment Details</h2>
          {isStaffBooking && (
            <div className="space-y-2 p-3 rounded border border-border bg-surface-elevated/40">
              <p className="text-xs font-medium text-primary-text">Patient</p>
              {selectedPatient ? (
                <div className="flex items-center justify-between text-xs text-primary-text">
                  <span>{selectedPatient.fullName} • {selectedPatient.phone || selectedPatient.email}</span>
                  <button type="button" className="text-accent" onClick={() => { setSelectedPatient(null); setPatientQuery(''); }}>Change</button>
                </div>
              ) : (
                <>
                  <input
                    className="w-full px-3 py-2 bg-surface-elevated border border-border rounded text-xs text-primary-text"
                    placeholder="Search patient by name, phone or email"
                    value={patientQuery}
                    onChange={(e) => setPatientQuery(e.target.value)}
                  />
                  {patientResults.map((p) => (
                    <button key={p.id} type="button" className="block w-full text-left text-xs text-primary-text hover:bg-surface-elevated px-2 py-1 rounded" onClick={() => setSelectedPatient(p)}>
                      {p.fullName} • {p.phone || p.email}
                    </button>
                  ))}
                  {patientQuery.trim().length >= 2 && patientResults.length === 0 && !showWalkInForm && (
                    <button type="button" className="text-xs text-accent" onClick={() => setShowWalkInForm(true)}>
                      No match: register a new walk-in patient
                    </button>
                  )}
                  {showWalkInForm && (
                    <WalkInPatientForm
                      onCreated={(p) => { setSelectedPatient(p); setShowWalkInForm(false); }}
                      onCancel={() => setShowWalkInForm(false)}
                    />
                  )}
                </>
              )}
            </div>
          )}

          <div className="space-y-2 text-xs divide-y divide-border">
            <div className="flex justify-between py-2">
              <span className="text-secondary-text">Medical Facility:</span>
              <strong className="text-primary-text">{selectedHospital?.name}</strong>
            </div>
            <div className="flex justify-between py-2">
              <span className="text-secondary-text">Department:</span>
              <strong className="text-primary-text">{selectedDept?.name}</strong>
            </div>
            <div className="flex justify-between py-2">
              <span className="text-secondary-text">Physician:</span>
              <strong className="text-primary-text">{selectedDoctor.fullName} ({selectedDoctor.roomNumber})</strong>
            </div>
            <div className="flex justify-between py-2">
              <span className="text-secondary-text">Consultation Slot:</span>
              <strong className="text-accent font-mono">{selectedDate} at {selectedSlot.formattedTime}</strong>
            </div>
            <div className="flex justify-between py-2">
              <span className="text-secondary-text">Consultation Fee:</span>
              <strong className="text-primary-text">${selectedDoctor.consultationFee}</strong>
            </div>
          </div>

          <div>
            <label className="block text-xs font-medium text-secondary-text mb-1">Chief Complaint / Visit Notes (Optional)</label>
            <textarea
              rows={2}
              value={patientNotes}
              onChange={(e) => setPatientNotes(e.target.value)}
              placeholder="e.g., Follow up on lab tests, ongoing symptoms..."
              className="w-full p-2 bg-surface-elevated border border-border rounded text-xs text-primary-text placeholder-muted-text focus:outline-none focus:border-accent"
            />
          </div>

          {bookingError && (
            <div className="p-3 rounded bg-emergency/15 border border-emergency/30 text-xs text-emergency">
              {bookingError}
            </div>
          )}

          <div className="flex items-center justify-between pt-2">
            <button
              onClick={() => setStep(4)}
              className="px-3 py-1.5 rounded border border-border text-xs text-secondary-text hover:text-primary-text"
            >
              Back to Slots
            </button>
            <button
              onClick={handleConfirmBooking}
              disabled={submitting}
              className="px-5 py-2 rounded bg-accent hover:bg-accent-hover text-white text-xs font-semibold tracking-wide transition-colors disabled:opacity-50"
            >
              {submitting ? 'Acquiring Slot Lock...' : 'Confirm Appointment'}
            </button>
          </div>
        </div>
      )}

      {step === 6 && bookingSuccess && (
        <div className="max-w-md mx-auto bg-surface border border-accent/40 rounded-lg p-6 text-center space-y-4 shadow-2xl">
          <div className="w-12 h-12 rounded-full bg-success/20 text-success flex items-center justify-center mx-auto">
            <Check className="w-6 h-6" />
          </div>

          <div>
            <h2 className="text-base font-bold text-primary-text tracking-wide">Appointment Confirmed</h2>
            <p className="text-xs text-secondary-text mt-0.5">Your consultation token has been generated on the hospital queue.</p>
          </div>

          <div className="p-4 rounded-lg bg-surface-elevated border border-border space-y-3 text-left text-xs">
            <div className="flex justify-between border-b border-border pb-2">
              <span className="text-secondary-text">Appointment ID:</span>
              <strong className="font-mono text-primary-text">{bookingSuccess.appointmentNumber}</strong>
            </div>

            <div className="flex justify-between items-center py-1">
              <span className="text-secondary-text">Allocated Token:</span>
              <span className="text-2xl font-bold font-mono text-accent">{bookingSuccess.tokenFormatted}</span>
            </div>

            <div className="flex justify-between border-t border-border pt-2">
              <span className="text-secondary-text">Attending Doctor:</span>
              <strong className="text-primary-text">{bookingSuccess.doctorName}</strong>
            </div>

            <div className="flex justify-between">
              <span className="text-secondary-text">Clinical Room:</span>
              <strong className="text-primary-text">{bookingSuccess.doctorRoom}</strong>
            </div>

            <div className="flex justify-between">
              <span className="text-secondary-text">Scheduled Time:</span>
              <strong className="text-primary-text">{bookingSuccess.appointmentDate} at {bookingSuccess.appointmentTime}</strong>
            </div>

            <div className="flex justify-between border-t border-border pt-2">
              <span className="text-secondary-text">Est. Waiting Time:</span>
              <strong className="text-emerald-400 font-mono">{bookingSuccess.estimatedWaitMinutes} min</strong>
            </div>
          </div>

          <div className="flex items-center space-x-3 pt-2">
            <button
              onClick={() => navigate('/patient')}
              className="flex-1 py-2 px-4 rounded bg-accent hover:bg-accent-hover text-white text-xs font-semibold transition-colors"
            >
              View Live Queue Position
            </button>
          </div>
        </div>
      )}
    </div>
  );
};