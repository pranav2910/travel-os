import { describe, expect, it } from 'vitest';
import { addMoney, formatMoney, isZero, minorUnits, parseMajor } from './money';

describe('money', () => {
  it('formats minor units exactly with an explicit currency', () => {
    expect(formatMoney({ currency: 'USD', amountMinor: 49558 })).toBe('USD 495.58');
    expect(formatMoney({ currency: 'USD', amountMinor: '7300' })).toBe('USD 73.00');
    expect(formatMoney({ currency: 'USD', amountMinor: -4600 })).toBe('USD −46.00');
    expect(formatMoney({ currency: 'JPY', amountMinor: '12345' })).toBe('JPY 12,345');
    expect(formatMoney({ currency: 'USD', amountMinor: 0 })).toBe('USD 0.00');
    expect(formatMoney(null)).toBe('—');
  });
  it('keeps int64 strings beyond the double range exact', () => {
    expect(formatMoney({ currency: 'USD', amountMinor: '9007199254740993' })).toBe(
      'USD 90,071,992,547,409.93',
    );
    expect(minorUnits('9007199254740993')).toBe(9007199254740993n);
  });
  it('refuses non-integers and unsafe numbers', () => {
    expect(() => minorUnits('12.5')).toThrow(RangeError);
    expect(() => minorUnits(2 ** 53)).toThrow(RangeError);
    expect(() => minorUnits(Number.NaN)).toThrow(RangeError);
  });
  it('adds and compares without floats', () => {
    expect(
      addMoney({ currency: 'USD', amountMinor: '1' }, { currency: 'USD', amountMinor: 2 })
        .amountMinor,
    ).toBe(3n);
    expect(() =>
      addMoney({ currency: 'USD', amountMinor: 1 }, { currency: 'EUR', amountMinor: 1 }),
    ).toThrow();
    expect(isZero({ currency: 'USD', amountMinor: '0' })).toBe(true);
    expect(isZero({ currency: 'USD', amountMinor: 1 })).toBe(false);
  });
  it('parses typed major amounts into minor units', () => {
    expect(parseMajor('435', 'USD')).toBe(43500n);
    expect(parseMajor('435.5', 'USD')).toBe(43550n);
    expect(parseMajor('1,234.56', 'USD')).toBe(123456n);
    expect(parseMajor('12', 'JPY')).toBe(12n);
    expect(parseMajor('12.345', 'USD')).toBeNull();
    expect(parseMajor('abc', 'USD')).toBeNull();
  });
});
