package io.akka.umami.lib;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The second factor: the code, the secret at rest, and the backup codes.
 *
 * <p>The code's parameters are not written down anywhere in the original — it takes its library's
 * defaults — so they are pinned here explicitly and checked against a code the running original
 * accepted.
 */
public final class TwoFactor {

  /** The code parameters, unstated in the original and confirmed against it. */
  public static final int DIGITS = 6;
  public static final int PERIOD_SECONDS = 30;
  public static final int WINDOW_STEPS = 1;
  public static final String ALGORITHM = "HmacSHA1";

  private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  private static final int SECRET_BYTES = 20;
  private static final int GCM_IV_LENGTH = 12;
  private static final int GCM_TAG_LENGTH = 16;
  private static final SecureRandom RANDOM = new SecureRandom();

  private TwoFactor() {}

  // --- configuration ---------------------------------------------------------------------

  public static boolean isConfigured() {
    var key = Env.get("TWO_FACTOR_ENCRYPTION_KEY");
    return key != null && key.matches("^[0-9a-fA-F]{64}$");
  }

  public static String configurationErrorCode() {
    return "two-factor-error-not-configured";
  }

  public static String configurationErrorMessage() {
    return "TWO_FACTOR_ENCRYPTION_KEY is missing or invalid";
  }

  private static SecretKeySpec key() {
    var hex = Env.get("TWO_FACTOR_ENCRYPTION_KEY");
    if (hex == null || !hex.matches("^[0-9a-fA-F]{64}$")) {
      throw new IllegalStateException(configurationErrorMessage());
    }
    return new SecretKeySpec(HexFormat.of().parseHex(hex), "AES");
  }

  // --- the secret at rest -----------------------------------------------------------------

  /** Stored as {@code ciphertext:iv:tag}, all hexadecimal, with the raw key and no derivation. */
  public static String encryptSecret(String plaintext) {
    try {
      var iv = new byte[GCM_IV_LENGTH];
      RANDOM.nextBytes(iv);
      var cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(GCM_TAG_LENGTH * 8, iv));
      var sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
      var cipherText = java.util.Arrays.copyOfRange(sealed, 0, sealed.length - GCM_TAG_LENGTH);
      var tag = java.util.Arrays.copyOfRange(sealed, sealed.length - GCM_TAG_LENGTH, sealed.length);
      var hex = HexFormat.of();
      return hex.formatHex(cipherText) + ":" + hex.formatHex(iv) + ":" + hex.formatHex(tag);
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("could not encrypt the secret", e);
    }
  }

  public static String decryptSecret(String stored) {
    try {
      var parts = stored.split(":");
      if (parts.length != 3) {
        throw new IllegalArgumentException("malformed stored secret");
      }
      var hex = HexFormat.of();
      var cipherText = hex.parseHex(parts[0]);
      var iv = hex.parseHex(parts[1]);
      var tag = hex.parseHex(parts[2]);
      var sealed = new byte[cipherText.length + tag.length];
      System.arraycopy(cipherText, 0, sealed, 0, cipherText.length);
      System.arraycopy(tag, 0, sealed, cipherText.length, tag.length);
      var cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(GCM_TAG_LENGTH * 8, iv));
      return new String(cipher.doFinal(sealed), StandardCharsets.UTF_8);
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalArgumentException("could not decrypt the secret", e);
    }
  }

  // --- the code ----------------------------------------------------------------------------

  public static String generateSecret() {
    var bytes = new byte[SECRET_BYTES];
    RANDOM.nextBytes(bytes);
    return base32Encode(bytes);
  }

  public static String generateUri(String secret, String label) {
    var issuer = "Umami";
    return "otpauth://totp/"
        + URLEncoder.encode(issuer, StandardCharsets.UTF_8)
        + ":"
        + URLEncoder.encode(label, StandardCharsets.UTF_8)
        + "?secret="
        + secret
        + "&issuer="
        + URLEncoder.encode(issuer, StandardCharsets.UTF_8)
        + "&algorithm=SHA1&digits="
        + DIGITS
        + "&period="
        + PERIOD_SECONDS;
  }

  public static String generateQrCodeDataUrl(String uri) {
    try {
      var writer = new QRCodeWriter();
      var matrix =
          writer.encode(
              uri,
              BarcodeFormat.QR_CODE,
              256,
              256,
              Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H));
      var out = new ByteArrayOutputStream();
      MatrixToImageWriter.writeToStream(matrix, "PNG", out);
      return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
    } catch (Exception e) {
      throw new IllegalStateException("could not draw the provisioning image", e);
    }
  }

  public static boolean verify(String token, String secret) {
    return verify(token, secret, Instant.now());
  }

  /** Accepted within one period either side, which is the library default the original relies on. */
  public static boolean verify(String token, String secret, Instant at) {
    if (token == null || secret == null || token.length() != DIGITS) {
      return false;
    }
    long counter = at.getEpochSecond() / PERIOD_SECONDS;
    for (int step = -WINDOW_STEPS; step <= WINDOW_STEPS; step++) {
      if (token.equals(generate(secret, counter + step))) {
        return true;
      }
    }
    return false;
  }

  public static String generate(String secret, long counter) {
    try {
      var key = base32Decode(secret);
      var mac = Mac.getInstance(ALGORITHM);
      mac.init(new SecretKeySpec(key, ALGORITHM));
      var message = new byte[8];
      for (int i = 7; i >= 0; i--) {
        message[i] = (byte) (counter & 0xff);
        counter >>>= 8;
      }
      var digest = mac.doFinal(message);
      int offset = digest[digest.length - 1] & 0x0f;
      int binary =
          ((digest[offset] & 0x7f) << 24)
              | ((digest[offset + 1] & 0xff) << 16)
              | ((digest[offset + 2] & 0xff) << 8)
              | (digest[offset + 3] & 0xff);
      int modulus = (int) Math.pow(10, DIGITS);
      return String.format("%0" + DIGITS + "d", binary % modulus);
    } catch (Exception e) {
      throw new IllegalStateException("could not generate a code", e);
    }
  }

  // --- backup codes ---------------------------------------------------------------------------

  public record BackupCodes(List<String> plain, List<String> hashes) {}

  /** Ten codes, each sixteen upper-case hexadecimal characters, a hyphen, sixteen more. */
  public static BackupCodes generateBackupCodes() {
    var plain = new ArrayList<String>();
    var hashes = new ArrayList<String>();
    for (int i = 0; i < Constants.BACKUP_CODE_COUNT; i++) {
      var code = randomHalf() + "-" + randomHalf();
      plain.add(code);
      hashes.add(Passwords.hashPassword(code));
    }
    return new BackupCodes(List.copyOf(plain), List.copyOf(hashes));
  }

  private static String randomHalf() {
    var bytes = new byte[8];
    RANDOM.nextBytes(bytes);
    return HexFormat.of().formatHex(bytes).toUpperCase(Locale.ROOT);
  }

  /** The index of the code that matched, or -1. Comparison is case-insensitive by upper-casing. */
  public static int verifyBackupCode(String input, List<String> hashes) {
    if (input == null) {
      return -1;
    }
    var candidate = input.toUpperCase(Locale.ROOT);
    for (int i = 0; i < hashes.size(); i++) {
      if (Passwords.checkPassword(candidate, hashes.get(i))) {
        return i;
      }
    }
    return -1;
  }

  // --- base32, which the provisioning format is written in --------------------------------------

  static String base32Encode(byte[] data) {
    var out = new StringBuilder();
    int buffer = 0;
    int bits = 0;
    for (var b : data) {
      buffer = (buffer << 8) | (b & 0xff);
      bits += 8;
      while (bits >= 5) {
        out.append(BASE32.charAt((buffer >> (bits - 5)) & 0x1f));
        bits -= 5;
      }
    }
    if (bits > 0) {
      out.append(BASE32.charAt((buffer << (5 - bits)) & 0x1f));
    }
    return out.toString();
  }

  static byte[] base32Decode(String value) {
    var clean = value.replace("=", "").toUpperCase(Locale.ROOT);
    var out = new ByteArrayOutputStream();
    int buffer = 0;
    int bits = 0;
    for (var c : clean.toCharArray()) {
      int index = BASE32.indexOf(c);
      if (index < 0) {
        continue;
      }
      buffer = (buffer << 5) | index;
      bits += 5;
      if (bits >= 8) {
        out.write((buffer >> (bits - 8)) & 0xff);
        bits -= 8;
      }
    }
    return out.toByteArray();
  }
}
