package io.akka.umami.domain;

import java.time.Instant;
import java.util.List;

/** The records a person, a team and their membership are held as. */
public final class Accounts {

  private Accounts() {}

  public record User(
      String id,
      String username,
      String password,
      String role,
      String logoUrl,
      String displayName,
      boolean twoFactorRequired,
      Instant createdAt,
      Instant updatedAt,
      Instant deletedAt) {

    public boolean isAdmin() {
      return io.akka.umami.lib.Constants.ROLE_ADMIN.equals(role);
    }

    public boolean isDeleted() {
      return deletedAt != null;
    }
  }

  public record Team(
      String id,
      String name,
      String accessCode,
      String logoUrl,
      boolean twoFactorRequired,
      Instant createdAt,
      Instant updatedAt,
      Instant deletedAt) {

    public boolean isDeleted() {
      return deletedAt != null;
    }
  }

  public record TeamUser(
      String id, String teamId, String userId, String role, Instant createdAt, Instant updatedAt) {}

  /** What a request was able to prove about its caller. */
  public record Auth(String token, String authKey, User user, ShareToken shareToken) {

    public boolean isAdmin() {
      return user != null && user.isAdmin();
    }

    public String userId() {
      return user == null ? null : user.id();
    }
  }

  /** An anonymous assertion about which entities a link may read, and which sections of them. */
  public record ShareToken(
      String shareId,
      Integer shareType,
      String websiteId,
      List<String> websiteIds,
      String boardId,
      String pixelId,
      List<String> pixelIds,
      String linkId,
      List<String> linkIds,
      com.fasterxml.jackson.databind.node.ObjectNode parameters) {

    public boolean names(String entityId) {
      if (entityId == null) {
        return false;
      }
      return entityId.equals(websiteId)
          || entityId.equals(pixelId)
          || entityId.equals(linkId)
          || (websiteIds != null && websiteIds.contains(entityId))
          || (pixelIds != null && pixelIds.contains(entityId))
          || (linkIds != null && linkIds.contains(entityId));
    }
  }
}
