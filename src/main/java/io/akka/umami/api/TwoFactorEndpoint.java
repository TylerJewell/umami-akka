package io.akka.umami.api;

import akka.http.javadsl.model.HttpEntity;
import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Delete;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import io.akka.umami.application.Store;
import io.akka.umami.domain.Accounts;
import io.akka.umami.domain.Security;
import io.akka.umami.lib.Constants;
import io.akka.umami.lib.Env;
import io.akka.umami.lib.Json;
import io.akka.umami.lib.Passwords;
import io.akka.umami.lib.Responses;
import io.akka.umami.lib.TwoFactor;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * The second factor: enrolling, verifying, disabling, and being required to have one.
 *
 * <p>Every route here answers "not found" when the deployment is the hosted one, because the
 * hosted service handles the second factor itself. Without a usable encryption key they all answer
 * "service unavailable" instead, which is what makes an enrolled account unable to sign in at all.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint
public class TwoFactorEndpoint extends Api {

  private static final String SETTING_GLOBAL = "twoFactorRequiredGlobal";

  public TwoFactorEndpoint(ComponentClient client) {
    super(client);
  }

  private static boolean hosted() {
    return Env.isSet("CLOUD_MODE");
  }

  private static HttpResponse unconfigured() {
    return Responses.serviceUnavailable(
        TwoFactor.configurationErrorMessage(), TwoFactor.configurationErrorCode());
  }

  // ------------------------------------------------------------------ what is required

  @Get("/api/2fa/status")
  public HttpResponse status() {
    return answer(() -> {
      var caller = caller();
      var body = Json.object();
      if (hosted()) {
        body.put("isEnabled", false);
        body.put("isRequired", false);
        body.putNull("requiredReason");
        body.put("isConfigured", false);
        body.put("globalRequired", false);
        return Responses.json(body);
      }
      var state = store.twoFactor(caller.userId());
      boolean configured = TwoFactor.isConfigured();
      boolean globalRequired = "true".equals(store.setting(SETTING_GLOBAL));
      String reason = null;
      if (configured) {
        if (globalRequired) {
          reason = "global";
        } else if (caller.user() != null && caller.user().twoFactorRequired()) {
          reason = "user";
        } else {
          for (var membership : store.membershipsOf(caller.userId())) {
            var team = store.team(membership.teamId());
            if (team != null && team.twoFactorRequired()) {
              reason = "team";
              break;
            }
          }
        }
      }
      body.put("isEnabled", state.enabled());
      body.put("isRequired", reason != null);
      body.put("requiredReason", reason);
      body.put("isConfigured", configured);
      body.put("globalRequired", globalRequired);
      return Responses.json(body);
    });
  }

  // ------------------------------------------------------------------ enrolling

  @Post("/api/2fa/setup/initiate")
  public HttpResponse initiate() {
    return answer(() -> {
      if (hosted()) {
        return Responses.notFound();
      }
      var caller = caller();
      if (!TwoFactor.isConfigured()) {
        return unconfigured();
      }
      var user = store.user(caller.userId());
      if (user == null) {
        return Responses.badRequest("User not found");
      }
      var state = store.twoFactor(user.id());
      if (state.enabled()) {
        return Responses.badRequest("2FA is already enabled", "two-factor-error-already-enabled");
      }
      var secret = TwoFactor.generateSecret();
      store.put(Store.TWO_FACTOR, user.id(),
          new Security.TwoFactorState(user.id(), TwoFactor.encryptSecret(secret), false,
              state.backupCodes(), state.usedOtps(), state.attempts(), state.lockedUntil(),
              state.createdAt() == null ? Instant.now() : state.createdAt(), Instant.now()));
      var body = Json.object();
      body.put("qrCodeDataUrl",
          TwoFactor.generateQrCodeDataUrl(TwoFactor.generateUri(secret, user.username())));
      body.put("manualKey", secret);
      return Responses.json(body);
    });
  }

  @Post("/api/2fa/setup/confirm")
  public HttpResponse confirm(HttpEntity.Strict requestBody) {
    return answer(() -> {
      if (hosted()) {
        return Responses.notFound();
      }
      var caller = caller();
      if (!TwoFactor.isConfigured()) {
        return unconfigured();
      }
      var schema = Schema.object();
      schema.string("token").min(6).max(6).required();
      var request = validate(schema, body(requestBody));

      var state = store.twoFactor(caller.userId());
      if (!state.isPending()) {
        return Responses.badRequest("No pending 2FA setup found",
            "two-factor-error-no-pending-setup");
      }
      var lockout = lockedOut(state);
      if (lockout != null) {
        return lockout;
      }
      var token = request.get("token").asText();
      if (isReplayed(state, token)) {
        return Responses.badRequest("Code already used", "two-factor-error-code-used");
      }
      if (!TwoFactor.verify(token, TwoFactor.decryptSecret(state.secret()))) {
        return failed(state);
      }
      var codes = TwoFactor.generateBackupCodes();
      var stored = new ArrayList<Security.BackupCode>();
      codes.hashes().forEach(hash -> stored.add(new Security.BackupCode(hash, false)));
      store.put(Store.TWO_FACTOR, caller.userId(),
          new Security.TwoFactorState(caller.userId(), state.secret(), true, stored,
              remember(state, token), 0, null, state.createdAt(), Instant.now()));
      var body = Json.object();
      var list = Json.array();
      codes.plain().forEach(list::add);
      body.set("backupCodes", list);
      return Responses.json(body);
    });
  }

  @Post("/api/2fa/setup/cancel")
  public HttpResponse cancel() {
    return answer(() -> {
      if (hosted()) {
        return Responses.notFound();
      }
      var caller = caller();
      var state = store.twoFactor(caller.userId());
      if (state.isPending()) {
        store.remove(Store.TWO_FACTOR, caller.userId());
      }
      var body = Json.object();
      body.put("ok", true);
      return Responses.json(body);
    });
  }

  // ------------------------------------------------------------------ verifying

  /**
   * The second half of a sign-in.
   *
   * <p>The token minted here carries no password fingerprint, so a session completed with a second
   * factor survives a later password change, where one completed with a password alone does not.
   * SPEC R114.
   */
  @Post("/api/2fa/verify")
  public HttpResponse verifyCode(HttpEntity.Strict requestBody) {
    return answer(() -> {
      if (hosted()) {
        return Responses.notFound();
      }
      var partial = auth.readPartialToken(headers());
      if (Auth2.bearerMissing(headers())) {
        return Responses.unauthorized(null, "two-factor-error-missing-token");
      }
      if (partial == null) {
        return Responses.unauthorized(null, "two-factor-error-invalid-partial-token");
      }
      if (!TwoFactor.isConfigured()) {
        return unconfigured();
      }
      var raw = body(requestBody);
      boolean hasToken = raw.has("token") && !raw.get("token").isNull();
      boolean hasBackup = raw.has("backupCode") && !raw.get("backupCode").isNull();
      if (hasToken && hasBackup) {
        // The body is a strict union of two shapes, so each branch reports the other's key
        // as one it does not recognise. Two messages, not one. SPEC R112.
        var error = Json.object();
        var errors = Json.array();
        errors.add("Unrecognized key: \"backupCode\"");
        errors.add("Unrecognized key: \"token\"");
        error.set("errors", errors);
        return Responses.badRequest(error);
      }
      if (!hasToken && !hasBackup) {
        return Responses.badRequest();
      }
      var schema = Schema.object().strict();
      schema.string("token").min(6).max(6);
      schema.string("backupCode").min(1);
      var request = validate(schema, raw);

      var user = store.user(partial);
      if (user == null) {
        return Responses.unauthorized();
      }
      var state = store.twoFactor(user.id());
      if (!state.enabled()) {
        return Responses.badRequest("2FA is not enabled", "two-factor-error-not-enabled");
      }
      var lockout = lockedOut(state);
      if (lockout != null) {
        return lockout;
      }

      if (hasBackup) {
        var codes = new ArrayList<>(state.backupCodes());
        var unused = new ArrayList<String>();
        for (var code : codes) {
          unused.add(code.used() ? null : code.codeHash());
        }
        int index = -1;
        for (int i = 0; i < codes.size(); i++) {
          if (!codes.get(i).used()
              && Passwords.checkPassword(
                  request.get("backupCode").asText().toUpperCase(java.util.Locale.ROOT),
                  codes.get(i).codeHash())) {
            index = i;
            break;
          }
        }
        if (index < 0) {
          return failedWith(state, "Invalid backup code", "two-factor-error-invalid-backup-code");
        }
        codes.set(index, new Security.BackupCode(codes.get(index).codeHash(), true));
        store.put(Store.TWO_FACTOR, user.id(),
            new Security.TwoFactorState(user.id(), state.secret(), true, codes, state.usedOtps(),
                0, null, state.createdAt(), Instant.now()));
        return Responses.json(sessionBodyFor(user));
      }

      var token = request.get("token").asText();
      if (isReplayed(state, token)) {
        return Responses.badRequest("Code already used", "two-factor-error-code-used");
      }
      if (!TwoFactor.verify(token, TwoFactor.decryptSecret(state.secret()))) {
        return failed(state);
      }
      store.put(Store.TWO_FACTOR, user.id(),
          new Security.TwoFactorState(user.id(), state.secret(), true, state.backupCodes(),
              remember(state, token), 0, null, state.createdAt(), Instant.now()));
      return Responses.json(sessionBodyFor(user));
    });
  }

  private com.fasterxml.jackson.databind.node.ObjectNode sessionBodyFor(Accounts.User user) {
    var body = Json.object();
    body.put("token", auth.createSessionToken(user, false));
    var account = Json.object();
    account.put("id", user.id());
    account.put("username", user.username());
    account.put("role", user.role());
    account.put("createdAt", Writers.stamp(user.createdAt()));
    account.put("isAdmin", user.isAdmin());
    var teams = Json.array();
    for (var membership : store.membershipsOf(user.id())) {
      var team = store.team(membership.teamId());
      if (team != null && !team.isDeleted()) {
        var row = Json.object();
        row.put("id", team.id());
        row.put("name", team.name());
        row.put("logoUrl", team.logoUrl());
        teams.add(row);
      }
    }
    account.set("teams", teams);
    body.set("user", account);
    return body;
  }

  // ------------------------------------------------------------------ disabling

  @Post("/api/2fa/disable")
  public HttpResponse disable(HttpEntity.Strict requestBody) {
    return answer(() -> {
      if (hosted()) {
        return Responses.notFound();
      }
      var caller = caller();
      if (!TwoFactor.isConfigured()) {
        return unconfigured();
      }
      var schema = Schema.object();
      schema.string("password").required();
      schema.string("token").min(6).max(6).required();
      var request = validate(schema, body(requestBody));

      if (isRequired(caller)) {
        return Responses.forbidden("2FA is required and cannot be disabled",
            "two-factor-error-disable-not-allowed");
      }
      var user = store.user(caller.userId());
      if (user == null
          || !Passwords.checkPassword(request.get("password").asText(), user.password())) {
        return Responses.badRequest("Incorrect password",
            "two-factor-error-incorrect-password");
      }
      var state = store.twoFactor(user.id());
      if (!state.enabled()) {
        return Responses.badRequest("2FA is not enabled", "two-factor-error-not-enabled");
      }
      var lockout = lockedOut(state);
      if (lockout != null) {
        return lockout;
      }
      var token = request.get("token").asText();
      if (isReplayed(state, token)) {
        return Responses.badRequest("Code already used", "two-factor-error-code-used");
      }
      if (!TwoFactor.verify(token, TwoFactor.decryptSecret(state.secret()))) {
        return failed(state);
      }
      store.remove(Store.TWO_FACTOR, user.id());
      var body = Json.object();
      body.put("ok", true);
      return Responses.json(body);
    });
  }

  private boolean isRequired(Accounts.Auth caller) {
    if ("true".equals(store.setting(SETTING_GLOBAL))) {
      return true;
    }
    if (caller.user() != null && caller.user().twoFactorRequired()) {
      return true;
    }
    for (var membership : store.membershipsOf(caller.userId())) {
      var team = store.team(membership.teamId());
      if (team != null && team.twoFactorRequired()) {
        return true;
      }
    }
    return false;
  }

  // ------------------------------------------------------------------ administration

  @Post("/api/admin/2fa/global")
  public HttpResponse setGlobal(HttpEntity.Strict requestBody) {
    return answer(() -> {
      if (hosted()) {
        return Responses.notFound();
      }
      var caller = caller();
      require(permissions.isAdmin(caller));
      var schema = Schema.object();
      schema.bool("required").required();
      var request = validate(schema, body(requestBody));
      boolean required = request.get("required").asBoolean();
      if (required && !TwoFactor.isConfigured()) {
        return unconfigured();
      }
      store.setSetting(SETTING_GLOBAL, String.valueOf(required));
      var body = Json.object();
      body.put("ok", true);
      body.put("required", required);
      return Responses.json(body);
    });
  }

  @Post("/api/admin/teams/{teamId}/2fa")
  public HttpResponse setTeam(String teamId, HttpEntity.Strict requestBody) {
    return answer(() -> {
      if (hosted()) {
        return Responses.notFound();
      }
      var caller = caller();
      require(permissions.isAdmin(caller));
      var schema = Schema.object();
      schema.bool("required").required();
      var request = validate(schema, body(requestBody));
      boolean required = request.get("required").asBoolean();
      if (required && !TwoFactor.isConfigured()) {
        return unconfigured();
      }
      var team = store.team(teamId);
      if (team == null) {
        return Responses.notFound();
      }
      store.put(Store.TEAM, teamId,
          new Accounts.Team(team.id(), team.name(), team.accessCode(), team.logoUrl(), required,
              team.createdAt(), Instant.now(), team.deletedAt()));
      var body = Json.object();
      body.put("ok", true);
      body.put("teamId", teamId);
      body.put("twoFactorRequired", required);
      return Responses.json(body);
    });
  }

  @Get("/api/admin/users/{userId}/2fa")
  public HttpResponse userStatus(String userId) {
    return answer(() -> {
      if (hosted()) {
        return Responses.notFound();
      }
      var caller = caller();
      require(permissions.isAdmin(caller));
      var body = Json.object();
      body.put("isEnabled", store.twoFactor(userId).enabled());
      return Responses.json(body);
    });
  }

  @Post("/api/admin/users/{userId}/2fa")
  public HttpResponse setUser(String userId, HttpEntity.Strict requestBody) {
    return answer(() -> {
      if (hosted()) {
        return Responses.notFound();
      }
      var caller = caller();
      require(permissions.isAdmin(caller));
      var schema = Schema.object();
      schema.bool("required").required();
      var request = validate(schema, body(requestBody));
      boolean required = request.get("required").asBoolean();
      if (required && !TwoFactor.isConfigured()) {
        return unconfigured();
      }
      var user = store.user(userId);
      if (user == null) {
        return Responses.notFound();
      }
      store.put(Store.USER, userId,
          new Accounts.User(user.id(), user.username(), user.password(), user.role(),
              user.logoUrl(), user.displayName(), required, user.createdAt(), Instant.now(),
              user.deletedAt()));
      var body = Json.object();
      body.put("ok", true);
      body.put("userId", userId);
      body.put("twoFactorRequired", required);
      return Responses.json(body);
    });
  }

  /** Clears every second-factor record for an account, which is how a locked-out person recovers. */
  @Delete("/api/admin/users/{userId}/2fa")
  public HttpResponse resetUser(String userId) {
    return answer(() -> {
      if (hosted()) {
        return Responses.notFound();
      }
      var caller = caller();
      require(permissions.isAdmin(caller));
      var state = store.twoFactor(userId);
      store.remove(Store.TWO_FACTOR, userId);
      var body = Json.object();
      body.put("ok", true);
      body.put("userId", userId);
      var reset = Json.object();
      reset.put("twoFactorAuth", state.secret() == null ? 0 : 1);
      reset.put("backupCodes", state.backupCodes().size());
      reset.put("otpUsed", state.usedOtps().size());
      reset.put("rateLimit", state.attempts() > 0 || state.lockedUntil() != null ? 1 : 0);
      body.set("reset", reset);
      return Responses.json(body);
    });
  }

  // ------------------------------------------------------------------ attempts

  private HttpResponse lockedOut(Security.TwoFactorState state) {
    if (state.lockedUntil() != null && state.lockedUntil().isAfter(Instant.now())) {
      return Responses.tooManyAttempts("Too many failed attempts. Please try again later.",
          state.lockedUntil().toEpochMilli());
    }
    return null;
  }

  private HttpResponse failed(Security.TwoFactorState state) {
    return failedWith(state, "Invalid verification code", "two-factor-error-invalid-code");
  }

  private HttpResponse failedWith(Security.TwoFactorState state, String message, String code) {
    int attempts = state.attempts() + 1;
    Instant lockedUntil =
        attempts >= Constants.TWO_FACTOR_MAX_ATTEMPTS
            ? Instant.now().plus(Constants.TWO_FACTOR_LOCKOUT_MINUTES, ChronoUnit.MINUTES)
            : state.lockedUntil();
    store.put(Store.TWO_FACTOR, state.userId(),
        new Security.TwoFactorState(state.userId(), state.secret(), state.enabled(),
            state.backupCodes(), state.usedOtps(), attempts, lockedUntil, state.createdAt(),
            Instant.now()));
    return Responses.badRequest(message, code);
  }

  /** A code is remembered for ninety seconds, which is three of its own periods. */
  private static boolean isReplayed(Security.TwoFactorState state, String otp) {
    var now = Instant.now();
    for (var used : state.usedOtps()) {
      if (used.otp().equals(otp) && used.expiresAt().isAfter(now)) {
        return true;
      }
    }
    return false;
  }

  private static List<Security.UsedOtp> remember(Security.TwoFactorState state, String otp) {
    var now = Instant.now();
    var out = new ArrayList<Security.UsedOtp>();
    for (var used : state.usedOtps()) {
      if (used.expiresAt().isAfter(now) && !used.otp().equals(otp)) {
        out.add(used);
      }
    }
    out.add(new Security.UsedOtp(otp, now.plusMillis(Constants.OTP_REMEMBERED_MILLIS)));
    return out;
  }

  /** Whether the request carried a bearer token at all, which is a different refusal. */
  static final class Auth2 {
    private Auth2() {}

    static boolean bearerMissing(java.util.Map<String, String> headers) {
      return io.akka.umami.application.Auth.bearerToken(headers) == null;
    }
  }
}
