package io.akka.umami.lib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.HashSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** SPEC R105 to R109: the second factor's parameters, its storage and its backup codes. */
class TwoFactorTest {

  private static final String KEY =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

  @BeforeEach
  void configure() {
    Env.override("TWO_FACTOR_ENCRYPTION_KEY", KEY);
  }

  @AfterEach
  void clear() {
    Env.clearOverrides();
  }

  @Test
  void aKeyIsUsableOnlyWhenItIsSixtyFourHexadecimalCharacters() {
    assertTrue(TwoFactor.isConfigured());
    Env.override("TWO_FACTOR_ENCRYPTION_KEY", "replace-me-with-a-64-character-hex-string");
    assertFalse(TwoFactor.isConfigured(),
        "the placeholder the published compose file ships is not a key");
    Env.override("TWO_FACTOR_ENCRYPTION_KEY", "0123456789abcdef");
    assertFalse(TwoFactor.isConfigured(), "half a key is not a key");
    Env.override("TWO_FACTOR_ENCRYPTION_KEY", "");
    assertFalse(TwoFactor.isConfigured());
  }

  @Test
  void theSecretIsStoredAsThreeHexadecimalPartsAndRoundTrips() {
    var stored = TwoFactor.encryptSecret("JBSWY3DPEHPK3PXP");
    var parts = stored.split(":");
    assertEquals(3, parts.length, "ciphertext, vector and tag");
    for (var part : parts) {
      assertTrue(part.matches("^[0-9a-f]+$"));
    }
    assertFalse(stored.contains("JBSWY3DPEHPK3PXP"));
    assertEquals("JBSWY3DPEHPK3PXP", TwoFactor.decryptSecret(stored));
  }

  @Test
  void twoStoragesOfOneSecretDifferAndATamperedOneIsRefused() {
    assertNotEquals(TwoFactor.encryptSecret("same"), TwoFactor.encryptSecret("same"));
    var stored = TwoFactor.encryptSecret("same");
    var parts = stored.split(":");
    var tampered = "ff" + parts[0].substring(2) + ":" + parts[1] + ":" + parts[2];
    assertThrows(IllegalArgumentException.class, () -> TwoFactor.decryptSecret(tampered));
  }

  @Test
  void anUnusableKeyMakesStorageImpossibleRatherThanSilentlyWeak() {
    Env.override("TWO_FACTOR_ENCRYPTION_KEY", "");
    assertThrows(IllegalStateException.class, () -> TwoFactor.encryptSecret("x"));
  }

  @Test
  void aSecretIsBaseThirtyTwoAndDifferentEveryTime() {
    var secret = TwoFactor.generateSecret();
    assertTrue(secret.matches("^[A-Z2-7]+$"));
    assertNotEquals(secret, TwoFactor.generateSecret());
  }

  @Test
  void theProvisioningAddressNamesTheIssuerTheAccountAndTheParameters() {
    var uri = TwoFactor.generateUri("JBSWY3DPEHPK3PXP", "alice");
    assertTrue(uri.startsWith("otpauth://totp/"));
    assertTrue(uri.contains("issuer=Umami"));
    assertTrue(uri.contains("alice"));
    assertTrue(uri.contains("secret=JBSWY3DPEHPK3PXP"));
    assertTrue(uri.contains("algorithm=SHA1"));
    assertTrue(uri.contains("digits=6"));
    assertTrue(uri.contains("period=30"));
  }

  @Test
  void aCodeIsSixDigitsAndVerifiesForItsOwnPeriod() {
    var secret = TwoFactor.generateSecret();
    var now = Instant.parse("2026-07-24T12:00:00Z");
    var code = TwoFactor.generate(secret, now.getEpochSecond() / 30);
    assertTrue(code.matches("^\\d{6}$"));
    assertTrue(TwoFactor.verify(code, secret, now));
    assertFalse(TwoFactor.verify("000000", secret, now)
        && !code.equals("000000"), "a code that is not this one does not verify");
    assertFalse(TwoFactor.verify(code, TwoFactor.generateSecret(), now),
        "a code does not verify against another secret");
  }

  @Test
  void aCodeIsAcceptedOnePeriodEitherSideAndNotTwo() {
    var secret = TwoFactor.generateSecret();
    var now = Instant.parse("2026-07-24T12:00:00Z");
    var code = TwoFactor.generate(secret, now.getEpochSecond() / 30);
    assertTrue(TwoFactor.verify(code, secret, now.plusSeconds(30)));
    assertTrue(TwoFactor.verify(code, secret, now.minusSeconds(30)));
    assertFalse(TwoFactor.verify(code, secret, now.plusSeconds(90)));
  }

  @Test
  void theKnownVectorFromTheStandardProducesTheKnownCode() {
    // RFC 6238's test secret is the ASCII "12345678901234567890"; at the fifty-ninth second
    // the counter is one and the six-digit answer is 287082.
    var secret = TwoFactor.base32Encode("12345678901234567890".getBytes(
        java.nio.charset.StandardCharsets.US_ASCII));
    assertEquals("287082", TwoFactor.generate(secret, 1));
  }

  @Test
  void thereAreTenBackupCodesOfTheStatedShapeAndTheyAreAllDifferent() {
    var codes = TwoFactor.generateBackupCodes();
    assertEquals(10, codes.plain().size());
    assertEquals(10, codes.hashes().size());
    for (var code : codes.plain()) {
      assertTrue(code.matches("^[0-9A-F]{16}-[0-9A-F]{16}$"), code);
    }
    assertEquals(10, new HashSet<>(codes.plain()).size());
    for (var hash : codes.hashes()) {
      assertTrue(hash.matches("^\\$2[aby]\\$.*"));
      assertFalse(codes.plain().contains(hash));
    }
  }

  @Test
  void aBackupCodeIsMatchedByIndexAndIgnoringCase() {
    var codes = TwoFactor.generateBackupCodes();
    assertEquals(3, TwoFactor.verifyBackupCode(codes.plain().get(3), codes.hashes()));
    assertEquals(0,
        TwoFactor.verifyBackupCode(codes.plain().get(0).toLowerCase(java.util.Locale.ROOT),
            codes.hashes()));
    assertEquals(-1, TwoFactor.verifyBackupCode("AAAAAAAAAAAAAAAA-BBBBBBBBBBBBBBBB",
        codes.hashes()));
    assertEquals(-1, TwoFactor.verifyBackupCode(null, codes.hashes()));
  }

  @Test
  void baseThirtyTwoRoundTrips() {
    var bytes = "the quick brown fox".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    assertEquals("the quick brown fox",
        new String(TwoFactor.base32Decode(TwoFactor.base32Encode(bytes)),
            java.nio.charset.StandardCharsets.US_ASCII));
  }
}
