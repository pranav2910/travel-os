package io.travelos.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class FieldCipherTest {

  private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

  @Test
  void roundTripsAndNeverStoresPlaintext() {
    FieldCipher c = FieldCipher.fromBase64Key(KEY);
    String stored = c.encrypt("P12345678");
    assertThat(stored).startsWith("v1:").doesNotContain("P12345678");
    assertThat(c.decrypt(stored)).isEqualTo("P12345678");
    assertThat(c.encrypt("P12345678")).as("a fresh nonce every time").isNotEqualTo(stored);
    assertThat(c.encrypt(null)).isNull();
    assertThat(c.decrypt(null)).isNull();
  }

  @Test
  void aDifferentKeyOrADamagedValueFailsLoudly() {
    String stored = FieldCipher.fromBase64Key(KEY).encrypt("secret");
    byte[] other = new byte[32];
    other[0] = 1;
    FieldCipher wrong = FieldCipher.fromBase64Key(Base64.getEncoder().encodeToString(other));
    assertThatThrownBy(() -> wrong.decrypt(stored)).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> FieldCipher.fromBase64Key("short"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> FieldCipher.fromBase64Key(KEY).decrypt("plain"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void onlyTheLastFourCharactersAreEverShown() {
    assertThat(FieldCipher.last4("P12345678")).isEqualTo("****5678");
    assertThat(FieldCipher.last4("123")).isEqualTo("****");
    assertThat(FieldCipher.last4(null)).isNull();
  }
}
