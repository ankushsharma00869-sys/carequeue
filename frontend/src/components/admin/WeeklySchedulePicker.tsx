import React from 'react';

export const WEEK_DAYS = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'];

const DAY_LABEL: Record<string, string> = {
  MONDAY: 'Monday',
  TUESDAY: 'Tuesday',
  WEDNESDAY: 'Wednesday',
  THURSDAY: 'Thursday',
  FRIDAY: 'Friday',
  SATURDAY: 'Saturday',
  SUNDAY: 'Sunday',
};

export interface DayRow {
  dayOfWeek: string;
  enabled: boolean;
  start: string;
  end: string;
  slotMinutes: number;
  maxPatients: number;
}

/** Mon-Sat open 09:00-17:00 with 15-minute slots; Sunday closed. */
export function defaultWeek(): DayRow[] {
  return WEEK_DAYS.map((d) => ({
    dayOfWeek: d,
    enabled: d !== 'SUNDAY',
    start: '09:00',
    end: '17:00',
    slotMinutes: 15,
    maxPatients: 40,
  }));
}

/** Rows in the shape the backend expects. Closed days are left out. */
export function toPayload(rows: DayRow[]) {
  return rows
    .filter((r) => r.enabled)
    .map((r) => ({
      dayOfWeek: r.dayOfWeek,
      startTime: `${r.start}:00`,
      endTime: `${r.end}:00`,
      slotDurationMinutes: r.slotMinutes,
      maxPatients: r.maxPatients,
      active: true,
    }));
}

/** Backend rows -> picker rows (a day with no row is shown as closed). */
export function fromPayload(list: any[]): DayRow[] {
  return defaultWeek().map((row) => {
    const found = list.find((x) => x.dayOfWeek === row.dayOfWeek);
    if (!found) return { ...row, enabled: false };
    return {
      dayOfWeek: row.dayOfWeek,
      enabled: true,
      start: String(found.startTime).slice(0, 5),
      end: String(found.endTime).slice(0, 5),
      slotMinutes: found.slotDurationMinutes ?? 15,
      maxPatients: found.maxPatients ?? 40,
    };
  });
}

const inputClass =
  'px-2 py-1.5 bg-surface-elevated border border-border rounded text-xs text-primary-text focus:outline-none focus:border-accent disabled:opacity-40';

export const WeeklySchedulePicker: React.FC<{ value: DayRow[]; onChange: (rows: DayRow[]) => void }> = ({
  value,
  onChange,
}) => {
  const update = (day: string, patch: Partial<DayRow>) =>
    onChange(value.map((r) => (r.dayOfWeek === day ? { ...r, ...patch } : r)));

  return (
    <div className="md:col-span-2">
      <p className="text-xs font-medium text-primary-text mb-2">Weekly schedule</p>
      <div className="grid grid-cols-1 md:grid-cols-12 gap-2 text-[11px] text-secondary-text mb-1">
        <span className="col-span-3">Day</span>
        <span className="col-span-2">From</span>
        <span className="col-span-2">To</span>
        <span className="col-span-2">Slot</span>
        <span className="col-span-3">Max patients / day</span>
      </div>
      <div className="space-y-2">
        {value.map((r) => (
          <div key={r.dayOfWeek} className="grid grid-cols-1 md:grid-cols-12 items-center gap-2">
            <label className="col-span-3 flex items-center gap-2 text-xs text-primary-text">
              <input
                type="checkbox"
                checked={r.enabled}
                onChange={(e) => update(r.dayOfWeek, { enabled: e.target.checked })}
              />
              {DAY_LABEL[r.dayOfWeek]}
            </label>
            <input
              type="time"
              className={`${inputClass} col-span-2`}
              disabled={!r.enabled}
              value={r.start}
              onChange={(e) => update(r.dayOfWeek, { start: e.target.value })}
            />
            <input
              type="time"
              className={`${inputClass} col-span-2`}
              disabled={!r.enabled}
              value={r.end}
              onChange={(e) => update(r.dayOfWeek, { end: e.target.value })}
            />
            <select
              className={`${inputClass} col-span-2`}
              disabled={!r.enabled}
              value={r.slotMinutes}
              onChange={(e) => update(r.dayOfWeek, { slotMinutes: Number(e.target.value) })}
            >
              {[10, 15, 20, 30, 60].map((m) => (
                <option key={m} value={m}>
                  {m} min
                </option>
              ))}
            </select>
            <input
              type="number"
              min={1}
              className={`${inputClass} col-span-3`}
              disabled={!r.enabled}
              value={r.maxPatients}
              onChange={(e) => update(r.dayOfWeek, { maxPatients: Number(e.target.value) })}
            />
          </div>
        ))}
      </div>
      <p className="text-[11px] text-secondary-text mt-2">
        Unchecked days are closed. Slots are created from the start time in steps of the slot length, and the last
        slot must end before the closing time.
      </p>
    </div>
  );
};
