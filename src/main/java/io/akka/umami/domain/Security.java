package io.akka.umami.domain;

import java.time.Instant;
import java.util.List;

/** The second factor's records, a stored sign-in, and the settings table. */
public final class Security {

  private Security() {}

  public record BackupCode(String codeHash, boolean used) {}

  public record UsedOtp(String otp, Instant expiresAt) {}

  /**
   * Everything one account's second factor is: the secret, whether it is on, the backup codes,
   * the codes already spent, and the lockout counter. The original spreads these across four
   * tables; here they belong to one account and change together.
   */
  public record TwoFactorState(
      String userId,
      String secret,
      boolean enabled,
      List<BackupCode> backupCodes,
      List<UsedOtp> usedOtps,
      int attempts,
      Instant lockedUntil,
      Instant createdAt,
      Instant updatedAt) {

    public static TwoFactorState empty(String userId) {
      return new TwoFactorState(userId, null, false, List.of(), List.of(), 0, null, null, null);
    }

    public boolean isPending() {
      return secret != null && !enabled;
    }
  }

  /** A sign-in held on the server rather than only in the token. */
  public record AuthSession(String authKey, String userId, String role, String passwordHash,
      Instant expiresAt) {}

  public record AppSetting(String key, String value) {}
}
