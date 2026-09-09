package io.travelos.common.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyTest {

  @Test
  void rendersInMajorUnits() {
    assertThat(Money.usd(82000)).hasToString("USD 820.00");
    assertThat(Money.usd(5)).hasToString("USD 0.05");
    assertThat(Money.of("JPY", 1200)).hasToString("JPY 1200");
    assertThat(Money.usd(-150)).hasToString("USD -1.50");
  }

  @Test
  void parsesMajorUnitsExactly() {
    assertThat(Money.fromMajor("USD", new BigDecimal("820.00"))).isEqualTo(Money.usd(82000));
    assertThat(Money.fromMajor("USD", new BigDecimal("820"))).isEqualTo(Money.usd(82000));
    assertThat(Money.fromMajor("JPY", new BigDecimal("1200"))).isEqualTo(Money.of("JPY", 1200));
    assertThatThrownBy(() -> Money.fromMajor("USD", new BigDecimal("820.005")))
        .isInstanceOf(ArithmeticException.class);
  }

  @Test
  void arithmeticIsIntegerAndOverflowSafe() {
    assertThat(Money.usd(82000).plus(Money.usd(7300))).isEqualTo(Money.usd(89300));
    assertThat(Money.usd(82000).minus(Money.usd(90000))).isEqualTo(Money.usd(-8000));
    assertThat(Money.usd(82000).minus(Money.usd(90000)).isNegative()).isTrue();
    assertThatThrownBy(() -> Money.usd(Long.MAX_VALUE).plus(Money.usd(1)))
        .isInstanceOf(ArithmeticException.class);
  }

  @Test
  void refusesToMixCurrencies() {
    assertThatThrownBy(() -> Money.usd(1).plus(Money.of("EUR", 1)))
        .isInstanceOf(Money.CurrencyMismatchException.class);
    assertThatThrownBy(() -> Money.usd(1).compareTo(Money.of("EUR", 1)))
        .isInstanceOf(Money.CurrencyMismatchException.class);
  }

  @Test
  void rejectsUnknownCurrency() {
    assertThatThrownBy(() -> Money.of("BOGUS", 1)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void comparesWithinCurrency() {
    assertThat(Money.usd(200).isGreaterThan(Money.usd(100))).isTrue();
    assertThat(Money.usd(100).isGreaterThan(Money.usd(100))).isFalse();
    assertThat(Money.zero("USD").isZero()).isTrue();
  }
}
