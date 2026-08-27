package io.akka.umami.application;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.akka.umami.domain.Accounts;
import io.akka.umami.domain.Security;
import io.akka.umami.lib.Constants;
import io.akka.umami.lib.Crypto;
import io.akka.umami.lib.Detect;
import io.akka.umami.lib.Json;
import io.akka.umami.lib.Jwt;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What a request was able to prove about its caller.
 *
 * <p>Two shapes reach here. A sign-in token is an encrypted assertion carrying the account and,
 * usually, a fingerprint of its password, so changing a password invalidates every token issued
 * before it. A share token is a plain assertion carrying entity identifiers and no account at all,
 * and it only counts when the request also declares it is being used inside a share.
 */
public final class Auth {

  private final Store store;

  public Auth(Store store) {
    this.store = store;
  }

  public Accounts.Auth check(Map<String, String> headers) {
    var bearer = bearerToken(headers);
    var shareToken = parseShareToken(headers);

    Accounts.User user = null;
    String authKey = null;
    if (bearer != null) {
      var payload = Jwt.parseSecureToken(bearer, Crypto.secret());
      if (payload != null) {
        var userId = Json.text(payload, "userId");
        if (userId != null) {
          user = store.user(userId);
          var fingerprint = Json.text(payload, "pwd");
          if (user != null && fingerprint != null
              && !Crypto.hash(user.password()).equals(fingerprint)) {
            user = null;
          }
        } else {
          authKey = Json.text(payload, "authKey");
          if (authKey != null) {
            var session = store.authSession(authKey);
            if (session != null
                && (session.expiresAt() == null || session.expiresAt().isAfter(Instant.now()))) {
              user = store.user(session.userId());
              if (user != null && session.passwordHash() != null
                  && !Crypto.hash(user.password()).equals(session.passwordHash())) {
                user = null;
              }
            }
          }
        }
      }
    }
    if (user != null && user.isDeleted()) {
      user = null;
    }
    if (user == null && shareToken == null) {
      return null;
    }
    // A share token outside a share context is not honoured, so an assertion lifted from one
    // page cannot be replayed against the ordinary interface.
    if (user == null && Detect.header(headers, Constants.SHARE_CONTEXT_HEADER) == null) {
      return null;
    }
    return new Accounts.Auth(bearer, authKey, redact(user), shareToken);
  }

  /** The account as it is handed to a caller: without its password. */
  public static Accounts.User redact(Accounts.User user) {
    if (user == null) {
      return null;
    }
    return new Accounts.User(user.id(), user.username(), null, user.role(), user.logoUrl(),
        user.displayName(), user.twoFactorRequired(), user.createdAt(), user.updatedAt(),
        user.deletedAt());
  }

  public static String bearerToken(Map<String, String> headers) {
    var header = Detect.header(headers, Constants.AUTH_HEADER);
    if (header == null) {
      return null;
    }
    var parts = header.split(" ");
    return parts.length < 2 ? null : parts[1];
  }

  public Accounts.ShareToken parseShareToken(Map<String, String> headers) {
    var header = Detect.header(headers, Constants.SHARE_TOKEN_HEADER);
    var payload = Jwt.parseToken(header, Crypto.secret());
    if (payload == null || !Constants.SHARE_TOKEN_TYPE.equals(Json.text(payload, "type"))) {
      return null;
    }
    return new Accounts.ShareToken(
        Json.text(payload, "shareId"),
        payload.has("shareType") ? payload.get("shareType").asInt() : null,
        Json.text(payload, "websiteId"),
        list(payload, "websiteIds"),
        Json.text(payload, "boardId"),
        Json.text(payload, "pixelId"),
        list(payload, "pixelIds"),
        Json.text(payload, "linkId"),
        list(payload, "linkIds"),
        payload.get("parameters") instanceof ObjectNode parameters ? parameters : null);
  }

  private static List<String> list(ObjectNode node, String field) {
    var value = node.get(field);
    if (value == null || !value.isArray()) {
      return null;
    }
    var out = new ArrayList<String>();
    value.forEach(element -> out.add(element.asText()));
    return out;
  }

  // ------------------------------------------------------------------ minting

  /** The token a sign-in produces, carrying the password fingerprint that invalidates it later. */
  public String createSessionToken(Accounts.User user, boolean withFingerprint) {
    var claims = Json.object();
    claims.put("userId", user.id());
    claims.put("role", user.role());
    if (withFingerprint && user.password() != null) {
      claims.put("pwd", Crypto.hash(user.password()));
    }
    return Jwt.createSecureToken(claims, Crypto.secret());
  }

  /** A sign-in held on the server, which is what the single-sign-on exchange hands out. */
  public String createStoredSession(Accounts.User user, Long expiresInSeconds) {
    var authKey = "auth:" + Crypto.createAuthKey();
    store.put(
        Store.AUTH_SESSION,
        authKey,
        new Security.AuthSession(
            authKey,
            user.id(),
            user.role(),
            user.password() == null ? null : Crypto.hash(user.password()),
            expiresInSeconds == null ? null : Instant.now().plusSeconds(expiresInSeconds)));
    var claims = Json.object();
    claims.put("authKey", authKey);
    return Jwt.createSecureToken(claims, Crypto.secret());
  }

  public void removeStoredSession(String authKey) {
    if (authKey != null) {
      store.remove(Store.AUTH_SESSION, authKey);
    }
  }

  /** The five-minute assertion handed out between a password and a second factor. */
  public String createPartialToken(String userId) {
    var claims = Json.object();
    claims.put("userId", userId);
    claims.put("type", Constants.PARTIAL_AUTH_TOKEN_TYPE);
    return Jwt.createSecureToken(claims, Crypto.secret(), 300);
  }

  public String readPartialToken(Map<String, String> headers) {
    var bearer = bearerToken(headers);
    if (bearer == null) {
      return null;
    }
    var payload = Jwt.parseSecureToken(bearer, Crypto.secret());
    if (payload == null
        || !Constants.PARTIAL_AUTH_TOKEN_TYPE.equals(Json.text(payload, "type"))) {
      return null;
    }
    return Json.text(payload, "userId");
  }
}
