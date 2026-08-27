package io.akka.umami.lib;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every setting the service reads, in one place.
 *
 * <p>The overrides map exists because a setting decides observable behaviour — whether a robot is
 * refused, how the session salt rotates, whether a trailing slash survives — and a test that cannot
 * set one can only check the default.
 */
public final class Env {

  private static final Map<String, String> OVERRIDES = new ConcurrentHashMap<>();

  private Env() {}

  public static String get(String name) {
    var override = OVERRIDES.get(name);
    if (override != null) {
      return override.isEmpty() ? null : override;
    }
    var value = System.getenv(name);
    if (value == null) {
      value = System.getProperty(name);
    }
    return value == null || value.isEmpty() ? null : value;
  }

  public static String get(String name, String fallback) {
    var value = get(name);
    return value == null ? fallback : value;
  }

  public static boolean isSet(String name) {
    return get(name) != null;
  }

  /** Sets a value for the duration of a test; an empty string means "unset". */
  public static void override(String name, String value) {
    if (value == null) {
      OVERRIDES.remove(name);
    } else {
      OVERRIDES.put(name, value);
    }
  }

  public static void clearOverrides() {
    OVERRIDES.clear();
  }
}
