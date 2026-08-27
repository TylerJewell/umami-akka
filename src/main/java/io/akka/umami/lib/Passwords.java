package io.akka.umami.lib;

import at.favre.lib.crypto.bcrypt.BCrypt;

/**
 * Account passwords and two-factor backup codes, both bcrypt at cost 10 — the cost the original
 * uses, so a store either side wrote is readable by the other.
 */
public final class Passwords {

  private static final int COST = 10;

  private Passwords() {}

  public static String hashPassword(String password) {
    return BCrypt.withDefaults().hashToString(COST, password.toCharArray());
  }

  public static boolean checkPassword(String password, String hash) {
    if (password == null || hash == null) {
      return false;
    }
    try {
      return BCrypt.verifyer().verify(password.toCharArray(), hash).verified;
    } catch (Exception e) {
      return false;
    }
  }
}
