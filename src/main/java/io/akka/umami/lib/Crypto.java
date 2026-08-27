package io.akka.umami.lib;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The hashing, encryption and identifier derivation the whole system is keyed on.
 *
 * <p>Every identifier a visitor is recognised by comes out of {@link #uuid(String...)}, which is a
 * version-5 identifier over the SHA-512 of the arguments concatenated with the deployment secret.
 * Two deployments with different secrets therefore never agree on a session identifier, which is
 * the privacy property the whole design rests on.
 */
public final class Crypto {

  private static final int IV_LENGTH = 16;
  private static final int SALT_LENGTH = 64;
  private static final int TAG_LENGTH = 16;
  private static final int TAG_POSITION = SALT_LENGTH + IV_LENGTH;
  private static final int ENC_POSITION = TAG_POSITION + TAG_LENGTH;
  private static final int PBKDF2_ITERATIONS = 10000;

  /** The namespace version-5 identifiers are drawn from: the DNS namespace. */
  private static final UUID DNS_NAMESPACE = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");

  private static final DateTimeFormatter UTC_STRING =
      DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
          .withZone(ZoneOffset.UTC);

  private static final SecureRandom RANDOM = new SecureRandom();

  private Crypto() {}

  /** SHA-512 of the arguments joined with nothing between them, as lower-case hexadecimal. */
  public static String hash(String... args) {
    return digest("SHA-512", String.join("", args));
  }

  public static String md5(String... args) {
    return digest("MD5", String.join("", args));
  }

  private static String digest(String algorithm, String value) {
    try {
      var md = MessageDigest.getInstance(algorithm);
      return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(algorithm + " is unavailable", e);
    }
  }

  /** The deployment secret: the hash of the configured secret, or of the connection string. */
  public static String secret() {
    var appSecret = Env.get("APP_SECRET");
    var database = Env.get("DATABASE_URL");
    return hash(appSecret != null ? appSecret : (database != null ? database : ""));
  }

  /**
   * A random identifier when called with nothing, and a derived one when called with arguments.
   * The derived form is what makes a returning visitor resolve to the same session without
   * anything being stored on their machine.
   */
  public static UUID uuid(String... args) {
    if (args.length == 0) {
      return Env.isSet("USE_UUIDV7") ? uuidV7() : UUID.randomUUID();
    }
    var joined = new String[args.length + 1];
    System.arraycopy(args, 0, joined, 0, args.length);
    joined[args.length] = secret();
    return uuidV5(DNS_NAMESPACE, hash(joined));
  }

  /**
   * A version-7 identifier: forty-eight bits of milliseconds since the epoch, then the version,
   * then random bits. The leading timestamp is why {@code USE_UUIDV7} exists — identifiers made
   * later sort after ones made earlier, which a version-4 identifier gives no way to do. SPEC
   * R148.
   */
  static UUID uuidV7() {
    var bytes = new byte[10];
    RANDOM.nextBytes(bytes);
    var millis = System.currentTimeMillis();
    var high =
        (millis << 16)
            | (0x7000L)
            | ((bytes[0] & 0x0FL) << 8)
            | (bytes[1] & 0xFFL);
    var low = 0L;
    for (int i = 2; i < 10; i++) {
      low = (low << 8) | (bytes[i] & 0xFFL);
    }
    low = (low & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
    return new UUID(high, low);
  }

  static UUID uuidV5(UUID namespace, String name) {
    try {
      var md = MessageDigest.getInstance("SHA-1");
      var ns = new byte[16];
      writeLong(ns, 0, namespace.getMostSignificantBits());
      writeLong(ns, 8, namespace.getLeastSignificantBits());
      md.update(ns);
      md.update(name.getBytes(StandardCharsets.UTF_8));
      var bytes = Arrays.copyOf(md.digest(), 16);
      bytes[6] = (byte) ((bytes[6] & 0x0f) | 0x50);
      bytes[8] = (byte) ((bytes[8] & 0x3f) | 0x80);
      long msb = 0;
      long lsb = 0;
      for (int i = 0; i < 8; i++) {
        msb = (msb << 8) | (bytes[i] & 0xffL);
      }
      for (int i = 8; i < 16; i++) {
        lsb = (lsb << 8) | (bytes[i] & 0xffL);
      }
      return new UUID(msb, lsb);
    } catch (Exception e) {
      throw new IllegalStateException("SHA-1 is unavailable", e);
    }
  }

  private static void writeLong(byte[] target, int offset, long value) {
    for (int i = 0; i < 8; i++) {
      target[offset + i] = (byte) (value >>> (8 * (7 - i)));
    }
  }

  /** A random key for a stored session, as thirty-two hexadecimal characters. */
  public static String createAuthKey() {
    var bytes = new byte[16];
    RANDOM.nextBytes(bytes);
    return HexFormat.of().formatHex(bytes);
  }

  /**
   * The salt a session identifier is derived through. It is the start of the day, week or month
   * the event itself carries, so an event backdated by its sender lands in that period's bucket
   * rather than in today's.
   */
  public static String getSalt(String rotation, Instant createdAt) {
    var zoned = createdAt.atZone(ZoneOffset.UTC);
    ZonedDateTime start =
        switch (rotation == null ? "month" : rotation) {
          case "day" -> zoned.truncatedTo(ChronoUnit.DAYS);
          case "week" -> zoned.truncatedTo(ChronoUnit.DAYS).with(DayOfWeek.SUNDAY).minusDays(
              zoned.truncatedTo(ChronoUnit.DAYS).with(DayOfWeek.SUNDAY).isAfter(zoned) ? 7 : 0);
          default -> zoned.truncatedTo(ChronoUnit.DAYS).withDayOfMonth(1);
        };
    return hash(UTC_STRING.format(start));
  }

  /** The salt a visit identifier is derived through: the hour the event carries. */
  public static String visitSalt(Instant createdAt) {
    return hash(UTC_STRING.format(createdAt.atZone(ZoneOffset.UTC).truncatedTo(ChronoUnit.HOURS)));
  }

  /**
   * Encrypts to {@code base64(salt ‖ iv ‖ tag ‖ ciphertext)}, the layout the original writes, so a
   * token minted by either side is readable by the other.
   */
  public static String encrypt(String value, String password) {
    try {
      var iv = new byte[IV_LENGTH];
      var salt = new byte[SALT_LENGTH];
      RANDOM.nextBytes(iv);
      RANDOM.nextBytes(salt);
      var cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.ENCRYPT_MODE, deriveKey(password, salt), new GCMParameterSpec(TAG_LENGTH * 8, iv));
      var sealed = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
      // Java appends the tag to the ciphertext; the layout wants it in front.
      var cipherText = Arrays.copyOfRange(sealed, 0, sealed.length - TAG_LENGTH);
      var tag = Arrays.copyOfRange(sealed, sealed.length - TAG_LENGTH, sealed.length);
      var out = new byte[SALT_LENGTH + IV_LENGTH + TAG_LENGTH + cipherText.length];
      System.arraycopy(salt, 0, out, 0, SALT_LENGTH);
      System.arraycopy(iv, 0, out, SALT_LENGTH, IV_LENGTH);
      System.arraycopy(tag, 0, out, TAG_POSITION, TAG_LENGTH);
      System.arraycopy(cipherText, 0, out, ENC_POSITION, cipherText.length);
      return Base64.getEncoder().encodeToString(out);
    } catch (Exception e) {
      throw new IllegalStateException("encryption failed", e);
    }
  }

  public static String decrypt(String value, String password) {
    try {
      var raw = Base64.getDecoder().decode(value);
      var salt = Arrays.copyOfRange(raw, 0, SALT_LENGTH);
      var iv = Arrays.copyOfRange(raw, SALT_LENGTH, TAG_POSITION);
      var tag = Arrays.copyOfRange(raw, TAG_POSITION, ENC_POSITION);
      var cipherText = Arrays.copyOfRange(raw, ENC_POSITION, raw.length);
      var sealed = new byte[cipherText.length + TAG_LENGTH];
      System.arraycopy(cipherText, 0, sealed, 0, cipherText.length);
      System.arraycopy(tag, 0, sealed, cipherText.length, TAG_LENGTH);
      var cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE, deriveKey(password, salt), new GCMParameterSpec(TAG_LENGTH * 8, iv));
      return new String(cipher.doFinal(sealed), StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalArgumentException("decryption failed", e);
    }
  }

  private static SecretKeySpec deriveKey(String password, byte[] salt) throws Exception {
    var factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512");
    var spec = new PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, 256);
    return new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
  }

  private static final String ALPHANUMERIC =
      "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

  /** A random number between the two bounds, both included. */
  public static int random(int min, int max) {
    return min + RANDOM.nextInt(max - min + 1);
  }

  public static String getRandomChars(int length) {
    return getRandomChars(length, ALPHANUMERIC);
  }

  public static String getRandomChars(int length, String alphabet) {
    var out = new StringBuilder(length);
    for (int i = 0; i < length; i++) {
      out.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
    }
    return out.toString();
  }
}
