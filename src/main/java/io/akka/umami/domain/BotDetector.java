package io.akka.umami.domain;

import java.util.regex.Pattern;

/**
 * Whether a request is a bot, well enough to reproduce the boundary the original draws with
 * the {@code isbot} library — SPEC-001 R3, question-log row 6, open decision "bot detection".
 * The original's pattern database is out of scope (docs/scope.md); a real browser's
 * {@code User-Agent} always carries a parenthesised platform block ({@code Mozilla/5.0
 * (Windows NT 10.0; ...)}), and a bare {@code Mozilla/5.0} with none was the one case run
 * against the original, so that is the line drawn here.
 */
public final class BotDetector {

  private static final Pattern PLATFORM_BLOCK = Pattern.compile("\\([^()]+\\)");
  private static final Pattern OBVIOUS_BOT = Pattern.compile(
      "bot|spider|crawl|curl|wget|python-requests|headlesschrome", Pattern.CASE_INSENSITIVE);

  private BotDetector() {}

  public static boolean isBot(String userAgent) {
    if (userAgent == null || userAgent.isBlank()) {
      return true;
    }
    if (OBVIOUS_BOT.matcher(userAgent).find()) {
      return true;
    }
    return !PLATFORM_BLOCK.matcher(userAgent).find();
  }
}
