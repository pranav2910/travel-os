/**
 * Timestamps are ISO-8601 instants (UTC) unless a field is documented as a local date. The sandbox
 * airline schedules flights on the UTC clock, so flight times are shown in UTC and labelled so; a
 * hotel stay or a transfer carries its property's / vendor's IANA zone and is shown in that zone.
 * Nothing here reinterprets an instant: the zone only changes how the same moment is written.
 */
export function formatInstant(
  iso: string | null | undefined,
  zone = 'UTC',
  locale = 'en-US',
): string {
  if (!iso) return '—';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  const text = new Intl.DateTimeFormat(locale, {
    timeZone: zone,
    year: 'numeric',
    month: 'short',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(d);
  return `${text} ${zoneLabel(zone, d)}`;
}

export function formatTime(iso: string | null | undefined, zone = 'UTC', locale = 'en-US'): string {
  if (!iso) return '—';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return new Intl.DateTimeFormat(locale, {
    timeZone: zone,
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(d);
}

export function formatDateInZone(iso: string, zone = 'UTC', locale = 'en-US'): string {
  const d = new Date(iso);
  return new Intl.DateTimeFormat(locale, {
    timeZone: zone,
    year: 'numeric',
    month: 'short',
    day: '2-digit',
  }).format(d);
}

/** A contract local date ("2026-10-06"): shown as written, never shifted through a Date. */
export function formatLocalDate(date: string | null | undefined, locale = 'en-US'): string {
  if (!date) return '—';
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(date);
  if (!m) return date;
  const [, y, mo, d] = m;
  const dt = new Date(Date.UTC(Number(y), Number(mo) - 1, Number(d)));
  return new Intl.DateTimeFormat(locale, {
    timeZone: 'UTC',
    year: 'numeric',
    month: 'short',
    day: '2-digit',
    weekday: 'short',
  }).format(dt);
}

export function zoneLabel(zone: string, at: Date = new Date()): string {
  if (zone === 'UTC') return 'UTC';
  try {
    const parts = new Intl.DateTimeFormat('en-US', {
      timeZone: zone,
      timeZoneName: 'short',
    }).formatToParts(at);
    return parts.find((p) => p.type === 'timeZoneName')?.value ?? zone;
  } catch {
    return zone;
  }
}

/** True when the arrival falls on a later UTC date than the departure (an overnight flight). */
export function crossesMidnight(departure: string, arrival: string, zone = 'UTC'): boolean {
  return formatDateInZone(departure, zone) !== formatDateInZone(arrival, zone);
}

export function isoNowMinutesAhead(minutes: number): string {
  return new Date(Date.now() + minutes * 60_000).toISOString();
}

/** "2026-10-06" + "10:00" (a UTC wall time) -> the instant. */
export function utcInstant(date: string, time: string): string {
  return new Date(`${date}T${time}:00Z`).toISOString();
}

export function relative(iso: string | null | undefined): string {
  if (!iso) return '';
  const ms = Date.now() - new Date(iso).getTime();
  const s = Math.round(ms / 1000);
  if (s < 60) return 'just now';
  const m = Math.round(s / 60);
  if (m < 60) return `${m} min ago`;
  const h = Math.round(m / 60);
  if (h < 48) return `${h} h ago`;
  return `${Math.round(h / 24)} d ago`;
}
