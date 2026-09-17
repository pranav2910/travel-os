/**
 * Money as the contracts define it: an ISO currency and an integer amount in minor units. REST
 * views serialize the amount as a JSON number (a Java long) and proto-JSON documents (recovery
 * decisions) as a string; both are read into a BigInt so nothing is ever rounded through a double.
 */
export interface Money {
  currency: string;
  amountMinor: string | number | bigint;
}

const EXPONENTS: Record<string, number> = {
  JPY: 0,
  KRW: 0,
  KWD: 3,
  BHD: 3,
  JOD: 3,
  OMR: 3,
  TND: 3,
};

export function exponentOf(currency: string): number {
  return EXPONENTS[currency.toUpperCase()] ?? 2;
}

/** Exact minor units. Throws on anything that is not an integer (a fraction or a NaN would be a bug). */
export function minorUnits(value: string | number | bigint): bigint {
  if (typeof value === 'bigint') return value;
  if (typeof value === 'number') {
    if (!Number.isSafeInteger(value))
      throw new RangeError(`amount is not a safe integer: ${value}`);
    return BigInt(value);
  }
  const trimmed = value.trim();
  if (!/^-?\d+$/.test(trimmed)) throw new RangeError(`amount is not an integer string: ${value}`);
  return BigInt(trimmed);
}

/** "USD 495.00" / "USD −46.00": explicit currency, exact digits, never a float. */
export function formatMoney(money: Money | null | undefined, locale = 'en-US'): string {
  if (!money) return '—';
  const amount = minorUnits(money.amountMinor);
  const exp = exponentOf(money.currency);
  const negative = amount < 0n;
  const abs = negative ? -amount : amount;
  const scale = 10n ** BigInt(exp);
  const major = abs / scale;
  const minor = abs % scale;
  const grouped = new Intl.NumberFormat(locale, { useGrouping: true }).format(major);
  const fraction = exp === 0 ? '' : `.${minor.toString().padStart(exp, '0')}`;
  return `${money.currency.toUpperCase()} ${negative ? '−' : ''}${grouped}${fraction}`;
}

export function addMoney(a: Money, b: Money): Money {
  if (a.currency !== b.currency)
    throw new RangeError(`currency mismatch: ${a.currency} vs ${b.currency}`);
  return {
    currency: a.currency,
    amountMinor: minorUnits(a.amountMinor) + minorUnits(b.amountMinor),
  };
}

export function isZero(money: Money): boolean {
  return minorUnits(money.amountMinor) === 0n;
}

/** Major units typed by a person ("1,234.50") into minor units, or null when not a valid amount. */
export function parseMajor(input: string, currency: string): bigint | null {
  const exp = exponentOf(currency);
  const cleaned = input.replace(/[,\s]/g, '');
  const m = /^(-?)(\d+)(?:\.(\d{0,}))?$/.exec(cleaned);
  if (!m) return null;
  const [, sign, whole, frac = ''] = m;
  if (frac.length > exp) return null;
  const minor =
    BigInt(whole ?? '0') * 10n ** BigInt(exp) +
    BigInt((frac + '0'.repeat(exp)).slice(0, exp) || '0');
  return sign === '-' ? -minor : minor;
}
