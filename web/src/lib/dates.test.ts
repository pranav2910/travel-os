import { describe, expect, it } from 'vitest';
import { crossesMidnight, formatInstant, formatLocalDate, formatTime, utcInstant } from './dates';

describe('dates', () => {
  it('shows an instant on the UTC clock by default and labels the zone', () => {
    expect(formatInstant('2026-10-06T10:00:00Z')).toMatch(/Oct 06, 2026, 10:00 UTC$/);
  });
  it('writes the same instant in a property zone without moving it', () => {
    // 10:00Z is 03:00 in Los Angeles in October (PDT)
    expect(formatTime('2026-10-06T10:00:00Z', 'America/Los_Angeles')).toBe('03:00');
    expect(formatInstant('2026-10-06T10:00:00Z', 'America/Los_Angeles')).toMatch(/03:00 PDT$/);
  });
  it('flags an overnight flight on the UTC calendar', () => {
    expect(crossesMidnight('2026-10-07T23:30:00Z', '2026-10-08T06:00:00Z')).toBe(true);
    expect(crossesMidnight('2026-10-07T10:00:00Z', '2026-10-07T16:20:00Z')).toBe(false);
  });
  it('shows a contract local date as written', () => {
    expect(formatLocalDate('2026-10-06')).toBe('Tue, Oct 06, 2026');
  });
  it('builds a UTC instant from a date and a wall time', () => {
    expect(utcInstant('2026-10-06', '05:00')).toBe('2026-10-06T05:00:00.000Z');
  });
});
