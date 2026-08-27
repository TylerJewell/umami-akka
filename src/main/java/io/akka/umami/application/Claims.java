package io.akka.umami.application;

import io.akka.umami.lib.Crypto;
import java.time.Instant;

/**
 * A name that only one thing may hold.
 *
 * <p>An account name, a link's slug, a pixel's slug and a share's slug are each claimed by one
 * record, and the original enforces that by asking its index before it writes. An index here is
 * brought up to date just after the write rather than inside it, so the same question asked twice
 * in quick succession can answer "nobody" twice and let two records take one name.
 *
 * <p>So a claim is a record of its own, addressed by the name being claimed. Reading a record by
 * its own address is settled rather than eventual, which is what makes the check hold under a
 * second request that has not finished yet.
 */
public final class Claims {

  public static final String USERNAME = "username";
  public static final String LINK_SLUG = "linkSlug";
  public static final String PIXEL_SLUG = "pixelSlug";
  public static final String SHARE_SLUG = "shareSlug";
  public static final String ACCESS_CODE = "accessCode";

  private static final String KIND = "claim";

  private final Store store;

  public Claims(Store store) {
    this.store = store;
  }

  public record Claim(String scope, String value, String ownerId, Instant createdAt) {}

  /** Whoever holds this name, or null. */
  public String holder(String scope, String value) {
    if (value == null) {
      return null;
    }
    var claim = store.find(KIND, key(scope, value), Claim.class);
    return claim == null ? null : claim.ownerId();
  }

  /**
   * Takes the name for this owner, answering false when somebody else already has it. Taking a
   * name the same owner already holds succeeds, so a repeated write is not a refusal.
   */
  public boolean take(String scope, String value, String ownerId) {
    if (value == null) {
      return true;
    }
    var existing = holder(scope, value);
    if (existing != null && !existing.equals(ownerId)) {
      return false;
    }
    store.put(KIND, key(scope, value), new Claim(scope, value, ownerId, Instant.now()));
    return true;
  }

  public void release(String scope, String value) {
    if (value != null) {
      store.remove(KIND, key(scope, value));
    }
  }

  private static String key(String scope, String value) {
    // Hashed rather than joined: a name may hold any character at all, and an address has to be
    // one a record can be filed under. Hashing the scope with it also keeps two scopes apart.
    return scope + "-" + Crypto.hash(scope, value);
  }
}
