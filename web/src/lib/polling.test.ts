import { describe, expect, it } from 'vitest';
import {
  ARRIVAL_WINDOW_MS,
  WAKE_WINDOW_MS,
  arrivalInterval,
  pollingInterval,
  wake,
  wokenAt,
} from './polling';

describe('polling', () => {
  it('stops at a terminal state and backs off over time', () => {
    expect(pollingInterval(true, Date.now())).toBe(false);
    expect(pollingInterval(false, Date.now())).toBe(1500);
    expect(pollingInterval(false, Date.now() - 65_000)).toBe(6000);
    expect(pollingInterval(false, Date.now() - 10 * 60_000)).toBe(15000);
    expect(pollingInterval(false, Date.now() - 31 * 60_000)).toBe(false);
  });

  it('a wake is remembered for a bounded while', () => {
    expect(wokenAt('trip_x')).toBeNull();
    wake('trip_x');
    expect(wokenAt('trip_x')).toBeCloseTo(Date.now(), -2);
    const realNow = Date.now;
    Date.now = () => realNow() + WAKE_WINDOW_MS + 1;
    try {
      expect(wokenAt('trip_x')).toBeNull();
    } finally {
      Date.now = realNow;
    }
  });

  it('a deep link asks again for a record that is not here yet, for a bounded while', () => {
    expect(arrivalInterval(Date.now(), true)).toBe(2000);
    expect(arrivalInterval(Date.now(), false)).toBe(false);
    expect(arrivalInterval(Date.now() - ARRIVAL_WINDOW_MS - 1, true)).toBe(false);
  });
});
