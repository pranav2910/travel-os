package io.travelos.common.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TenantIdTest {

  @ParameterizedTest
  @ValueSource(strings = {"acme", "acme-corp", "a", "tenant-23", "0x1"})
  void acceptsSlugs(String value) {
    assertThat(TenantId.of(value).value()).isEqualTo(value);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "Acme", "acme corp", "-acme", "acme_corp", "acme/other"})
  void rejectsNonSlugs(String value) {
    assertThatThrownBy(() -> TenantId.of(value)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsOverlongSlug() {
    assertThatThrownBy(() -> TenantId.of("a".repeat(64)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(TenantId.of("a".repeat(63)).value()).hasSize(63);
  }

  @Test
  void cacheKeysAreTenantScoped() {
    assertThat(TenantId.of("acme").cacheKey("trip", "trip_01ARZ3NDEKTSV4RRFFQ69G5FAV"))
        .isEqualTo("tenant:acme:trip:trip_01ARZ3NDEKTSV4RRFFQ69G5FAV");
  }
}
