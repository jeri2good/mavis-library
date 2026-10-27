// Calendar days belong to readyBy; lead time is elapsed time before that instant.
export const DAYS = ['sun', 'mon', 'tue', 'wed', 'thu', 'fri', 'sat'];
const formatters = new Map();
export function localParts(now, timezone = 'UTC') {
  if (!formatters.has(timezone)) formatters.set(timezone, new Intl.DateTimeFormat('en-CA', { timeZone: timezone, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }));
  return Object.fromEntries(formatters.get(timezone).formatToParts(new Date(now)).filter(p => p.type !== 'literal').map(p => [p.type, p.value]));
}
export function localDate(now, timezone = 'UTC') {
  const p = localParts(now, timezone);
  return `${p.year}-${p.month}-${p.day}`;
}
function wallInstant(wall, zone) {
  const offsets = new Set();
  for (const delta of [-36, -12, 0, 12, 36]) {
    const sample = wall + delta * 3600000, p = localParts(sample, zone);
    offsets.add(Date.UTC(+p.year, +p.month - 1, +p.day, +p.hour, +p.minute) - sample);
  }
  const candidates = [...offsets].map(offset => {
    const time = wall - offset, p = localParts(time, zone);
    return { time, local: Date.UTC(+p.year, +p.month - 1, +p.day, +p.hour, +p.minute) };
  });
  // Fold: first occurrence. Gap: shift forward by the DST gap, like Temporal compatible.
  return candidates.filter(c => c.local >= wall).sort((a, b) => a.local - b.local || a.time - b.time)[0].time;
}
export function nextRunAt(schedule, now = Date.now()) {
  if (!schedule.enabled || !schedule.days.length) return null;
  const p = localParts(now, schedule.timezone), [h, m] = schedule.readyBy.split(':').map(Number);
  for (let day = 0; day < 9; day++) {
    const date = new Date(Date.UTC(+p.year, +p.month - 1, +p.day + day));
    if (!schedule.days.includes(DAYS[date.getUTCDay()])) continue;
    const start = wallInstant(date.getTime() + h * 3600000 + m * 60000, schedule.timezone) - 3600000;
    if (start > now) return new Date(start).toISOString();
  }
  throw new Error('No next schedule date');
}
export function nextMidnight(now, zone) {
  const p = localParts(now, zone);
  return new Date(wallInstant(Date.UTC(+p.year, +p.month - 1, +p.day + 1), zone)).toISOString();
}
