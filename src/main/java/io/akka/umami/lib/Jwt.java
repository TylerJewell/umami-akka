package io.akka.umami.lib;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signed assertions, in the two shapes the system uses.
 *
 * <p>A plain one is signed and readable by anybody who has it: that is what a collection cache
 * token and a share token are. A secure one is the same assertion encrypted afterwards, which is
 * what a sign-in token is. The two are distinguished at rest only by the {@code type} claim, and
 * that claim is the whole of what stops a collection token being replayed as a share token.
 */
public final class Jwt {

  private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();
  private static final String HEADER = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";

  private Jwt() {}

  /** Signs the payload. An {@code iat} already present is kept rather than replaced. */
  public static String createToken(ObjectNode payload, String secret) {
    return createToken(payload, secret, null);
  }

  public static String createToken(ObjectNode payload, String secret, Long expiresInSeconds) {
    var claims = payload.deepCopy();
    var now = Instant.now().getEpochSecond();
    if (!claims.has("iat")) {
      claims.put("iat", now);
    }
    if (expiresInSeconds != null) {
      claims.put("exp", now + expiresInSeconds);
    }
    var head = URL.encodeToString(HEADER.getBytes(StandardCharsets.UTF_8));
    var body = URL.encodeToString(Json.write(claims).getBytes(StandardCharsets.UTF_8));
    var signed = head + "." + body;
    return signed + "." + URL.encodeToString(sign(signed, secret));
  }

  /** Verifies and parses, answering null on anything that does not hold up. */
  public static ObjectNode parseToken(String token, String secret) {
    if (token == null) {
      return null;
    }
    var parts = token.split("\\.");
    if (parts.length != 3) {
      return null;
    }
    try {
      var expected = URL.encodeToString(sign(parts[0] + "." + parts[1], secret));
      if (!constantTimeEquals(expected, parts[2])) {
        return null;
      }
      var claims = Json.readObject(new String(URL_DECODER.decode(parts[1]), StandardCharsets.UTF_8));
      if (claims == null) {
        return null;
      }
      JsonNode exp = claims.get("exp");
      if (exp != null && exp.isNumber() && exp.asLong() < Instant.now().getEpochSecond()) {
        return null;
      }
      return claims;
    } catch (Exception e) {
      return null;
    }
  }

  public static String createSecureToken(ObjectNode payload, String secret) {
    return Crypto.encrypt(createToken(payload, secret), secret);
  }

  public static String createSecureToken(ObjectNode payload, String secret, long expiresInSeconds) {
    return Crypto.encrypt(createToken(payload, secret, expiresInSeconds), secret);
  }

  public static ObjectNode parseSecureToken(String token, String secret) {
    if (token == null) {
      return null;
    }
    try {
      return parseToken(Crypto.decrypt(token, secret), secret);
    } catch (Exception e) {
      return null;
    }
  }

  private static byte[] sign(String value, String secret) {
    try {
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new IllegalStateException("HmacSHA256 is unavailable", e);
    }
  }

  private static boolean constantTimeEquals(String a, String b) {
    if (a.length() != b.length()) {
      return false;
    }
    int diff = 0;
    for (int i = 0; i < a.length(); i++) {
      diff |= a.charAt(i) ^ b.charAt(i);
    }
    return diff == 0;
  }
}
