package io.akka.umami.lib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** SPEC R8, R9, R10: what a visitor is recognised by, and what it is derived from. */
class CryptoTest {

  @BeforeEach
  void useAFixedSecret() {
    Env.override("APP_SECRET", "probe-secret");
  }

  @AfterEach
  void clear() {
    Env.clearOverrides();
  }

  @Test
  void theHashIsSha512AsHexadecimalOverTheArgumentsJoinedWithNothing() {
    var hash = Crypto.hash("a", "b");
    assertEquals(128, hash.length());
    assertTrue(hash.matches("^[0-9a-f]{128}$"));
    assertEquals(Crypto.hash("ab"), hash, "the arguments are joined with no separator");
    assertNotEquals(Crypto.hash("a", "c"), hash);
  }

  @Test
  void anIdentifierWithNoArgumentsIsRandomAndOneWithArgumentsIsStable() {
    assertNotEquals(Crypto.uuid().toString(), Crypto.uuid().toString());
    assertEquals(Crypto.uuid("a", "b").toString(), Crypto.uuid("a", "b").toString());
    assertNotEquals(Crypto.uuid("a", "b").toString(), Crypto.uuid("a", "c").toString());
  }

  @Test
  void aDerivedIdentifierIsVersionFive() {
    var uuid = Crypto.uuid("site", "1.2.3.4", "agent", "salt");
    assertEquals(5, uuid.version(), "the derived form is a version-five identifier");
  }

  @Test
  void theSecretChangesEveryDerivedIdentifier() {
    var first = Crypto.uuid("site", "1.2.3.4", "agent", "salt").toString();
    Env.override("APP_SECRET", "a-different-secret");
    assertNotEquals(first, Crypto.uuid("site", "1.2.3.4", "agent", "salt").toString(),
        "two deployments never agree on a session identifier");
  }

  @Test
  void encryptionRoundTripsAndIsDifferentEveryTime() {
    var first = Crypto.encrypt("value", "password");
    var second = Crypto.encrypt("value", "password");
    assertNotEquals(first, second, "a fresh salt and vector each time");
    assertEquals("value", Crypto.decrypt(first, "password"));
    assertThrows(IllegalArgumentException.class, () -> Crypto.decrypt(first, "wrong"));
  }

  @Test
  void aTamperedValueIsRefusedRatherThanDecoded() {
    var sealed = Crypto.encrypt("value", "password");
    var tampered = sealed.substring(0, sealed.length() - 4) + "AAAA";
    assertThrows(IllegalArgumentException.class, () -> Crypto.decrypt(tampered, "password"));
  }

  @Test
  void theSaltIsTakenFromTheEventsOwnInstantAndRotatesOnThePeriod() {
    var january = Instant.parse("2025-01-15T10:00:00Z");
    var laterInJanuary = Instant.parse("2025-01-28T23:00:00Z");
    var february = Instant.parse("2025-02-01T00:00:00Z");

    assertEquals(Crypto.getSalt("month", january), Crypto.getSalt("month", laterInJanuary));
    assertNotEquals(Crypto.getSalt("month", january), Crypto.getSalt("month", february));

    assertNotEquals(Crypto.getSalt("day", january), Crypto.getSalt("day", laterInJanuary));
    assertEquals(Crypto.getSalt("day", january),
        Crypto.getSalt("day", Instant.parse("2025-01-15T23:59:59Z")));
  }

  @Test
  void theVisitSaltRotatesOnTheHour() {
    assertEquals(
        Crypto.visitSalt(Instant.parse("2025-01-15T10:00:00Z")),
        Crypto.visitSalt(Instant.parse("2025-01-15T10:59:59Z")));
    assertNotEquals(
        Crypto.visitSalt(Instant.parse("2025-01-15T10:00:00Z")),
        Crypto.visitSalt(Instant.parse("2025-01-15T11:00:00Z")));
  }

  @Test
  void randomCharactersAreOfTheLengthAndAlphabetAskedFor() {
    assertEquals(16, Crypto.getRandomChars(16).length());
    assertEquals("", Crypto.getRandomChars(0));
    assertTrue(Crypto.getRandomChars(32).matches("^[0-9a-zA-Z]+$"));
    assertTrue(Crypto.getRandomChars(20, "ab").matches("^[ab]+$"));
    var seen = new java.util.HashSet<String>();
    for (int i = 0; i < 100; i++) {
      seen.add(Crypto.getRandomChars(16));
    }
    assertEquals(100, seen.size(), "a hundred generated values are all different");
  }

  @Test
  void anAuthenticationKeyIsThirtyTwoHexadecimalCharacters() {
    assertTrue(Crypto.createAuthKey().matches("^[0-9a-f]{32}$"));
  }

  @Test
  void theSecretFallsBackToTheConnectionStringWhenNoneIsSet() {
    Env.override("APP_SECRET", "");
    Env.override("DATABASE_URL", "postgresql://x");
    assertEquals(Crypto.hash("postgresql://x"), Crypto.secret());
  }

  @Test
  void theShorthandHashOfAMissingSecretIsNotTheEmptyString() {
    Env.override("APP_SECRET", "");
    Env.override("DATABASE_URL", "");
    assertFalse(Crypto.secret().isEmpty());
  }
}
