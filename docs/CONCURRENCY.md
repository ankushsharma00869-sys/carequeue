# CareQueue — Concurrency, Safe Token Generation & Priority Mechanics

## 1. The Challenge: Race Conditions in Hospital Booking
In a busy hospital setting, multiple patients frequently attempt to reserve the same appointment slot simultaneously, or multiple check-in terminals attempt to assign the next queue token at the exact same millisecond.

A naive CRUD implementation leads to:
1. **Double Booking**: Two patients receive confirmations for Dr. Smith at 09:30 AM.
2. **Duplicate Token Allocations**: Two patients both receive Token `A-021`.
3. **Queue Inversion**: An emergency cardiac patient is placed behind routine consultations due to naive timestamp sorting.

---

## 2. Multi-Tier Concurrency Architecture

CareQueue solves this using a **3-tier defense-in-depth model**:

```
[ Tier 1: Distributed Slot Locking (Redis SETNX) ]
                    ↓
[ Tier 2: Atomic Striped Token Sequencer (ReentrantLock) ]
                    ↓
[ Tier 3: Database ACID Unique Constraint Safety Net ]
```

### Tier 1: Distributed Slot Lock
When a patient clicks "Confirm Appointment":
1. The backend generates a slot lock key:
   `lock:slot:{doctorId}:{appointmentDate}:{appointmentTime}`
2. It attempts to acquire an exclusive lock in Redis with a 10-second TTL:
   `SET lock:slot:1:2026-09-18:09:30 "LOCKED" NX EX 10`
3. If another request already holds this lock, the second request immediately receives `409 Conflict`:
   *"The selected time slot is currently being locked by another booking request. Please select a different slot."*

### Tier 2: Atomic Token Sequencing
Token generation is executed within a critical synchronized sequence per `(doctorId, scheduleDate)`:
- High-concurrency requests lock the doctor-date monitor.
- Queries the latest sequence:
  `SELECT COALESCE(MAX(tokenNumber), 0) FROM QueueEntry WHERE doctor.id = :docId AND scheduleDate = :date`
- Assigns `nextToken = max + 1`.
- Immediately flushes the persistent entity: `queueEntryRepository.saveAndFlush(queueEntry)`.

### Tier 3: Database ACID Unique Constraint
At the relational schema layer:
```sql
CONSTRAINT uk_doctor_date_token UNIQUE (doctor_id, schedule_date, token_number)
```
If by any anomaly two concurrent transactions bypass memory monitors, the relational database guarantees that only one transaction can commit. The colliding transaction is aborted and rolled back.

---

## 3. Backend Priority Queue Mechanics (Emergency Triage)

Emergency patients must **never** be manually re-ordered on the frontend. Doing so creates client-side race conditions and visual desynchronization.

CareQueue implements **Backend Relational Priority Sorting**:

```sql
SELECT q FROM QueueEntry q
WHERE q.doctor.id = :doctorId AND q.scheduleDate = :scheduleDate AND q.status = 'WAITING'
ORDER BY q.priorityRank ASC, q.tokenNumber ASC
```

### Priority Hierarchy:
1. **`CRITICAL` (Rank 1)**: Emergency trauma, acute cardiac arrest, severe respiratory failure (`E-xxx`).
2. **`PRIORITY` (Rank 2)**: Urgent triage requiring expedited evaluation.
3. **`NORMAL` (Rank 3)**: Standard scheduled consultations (`A-xxx`).

When a doctor clicks **"CALL NEXT PATIENT"**:
The backend executes `findWaitingQueue(...)` which returns Rank 1 entries first. Even if regular tokens `A-021`, `A-022`, and `A-023` have been waiting for an hour, emergency token `E-001` (Rank 1) is admitted immediately to the physician console.