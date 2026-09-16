package io.travelos.learning.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FailureCodesTest {
  @Test
  void transportAndPaymentFailuresAreNotSupplierQuality() {
    assertThat(FailureCodes.isPlatform("SUPPLIER_UNAVAILABLE")).isTrue();
    assertThat(FailureCodes.isPlatform("SUPPLIER_DEADLINE_EXCEEDED")).isTrue();
    assertThat(FailureCodes.isPlatform("TIMEOUT")).isTrue();
    assertThat(FailureCodes.isPlatform("PAYMENT_DECLINED")).isTrue();
    assertThat(FailureCodes.isPlatform("")).isTrue();
    assertThat(FailureCodes.isPlatform(null)).isTrue();
  }

  @Test
  void supplierRefusalsAre() {
    assertThat(FailureCodes.isPlatform("SEAT_NO_LONGER_AVAILABLE")).isFalse();
    assertThat(FailureCodes.isPlatform("ROOM_NO_LONGER_AVAILABLE")).isFalse();
    assertThat(FailureCodes.isPlatform("OFFER_EXPIRED")).isFalse();
    assertThat(FailureCodes.isPlatform("CANCELLATION_REFUSED")).isFalse();
  }
}
