package io.travelos.common.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * An amount in a currency's minor unit (cents for USD). Integer arithmetic only: no floating-point
 * drift, and overflow throws instead of wrapping. Mixing currencies throws — there is no implicit
 * FX.
 */
public record Money(String currency, long amountMinor) implements Comparable<Money> {

  public Money {
    Objects.requireNonNull(currency, "currency");
    // Validates the ISO 4217 code; throws IllegalArgumentException for garbage.
    Currency.getInstance(currency);
  }

  public static Money of(String currency, long amountMinor) {
    return new Money(currency, amountMinor);
  }

  public static Money usd(long cents) {
    return new Money("USD", cents);
  }

  public static Money zero(String currency) {
    return new Money(currency, 0);
  }

  /**
   * Parses a major-unit amount ({@code 820.00}) exactly; rejects more precision than the currency
   * has.
   */
  public static Money fromMajor(String currency, BigDecimal major) {
    int digits = Currency.getInstance(currency).getDefaultFractionDigits();
    BigDecimal scaled = major.setScale(digits, RoundingMode.UNNECESSARY);
    return new Money(currency, scaled.movePointRight(digits).longValueExact());
  }

  public BigDecimal toMajor() {
    int digits = Currency.getInstance(currency).getDefaultFractionDigits();
    return BigDecimal.valueOf(amountMinor, digits);
  }

  public Money plus(Money other) {
    requireSameCurrency(other);
    return new Money(currency, Math.addExact(amountMinor, other.amountMinor));
  }

  public Money minus(Money other) {
    requireSameCurrency(other);
    return new Money(currency, Math.subtractExact(amountMinor, other.amountMinor));
  }

  public boolean isNegative() {
    return amountMinor < 0;
  }

  public boolean isZero() {
    return amountMinor == 0;
  }

  public boolean isGreaterThan(Money other) {
    return compareTo(other) > 0;
  }

  @Override
  public int compareTo(Money other) {
    requireSameCurrency(other);
    return Long.compare(amountMinor, other.amountMinor);
  }

  @Override
  public String toString() {
    return currency + " " + toMajor().toPlainString();
  }

  private void requireSameCurrency(Money other) {
    if (!currency.equals(other.currency)) {
      throw new CurrencyMismatchException(currency, other.currency);
    }
  }

  public static final class CurrencyMismatchException extends IllegalArgumentException {
    public CurrencyMismatchException(String left, String right) {
      super("cannot combine " + left + " with " + right + " without an explicit FX conversion");
    }
  }
}
