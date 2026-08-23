package io.akka.umami.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** SPEC-001 R3, question-log row 6 — run against the real source. */
class BotDetectorTest {

  @Test
  void aBareMozillaTokenWithNoPlatformBlockIsABot() {
    assertTrue(BotDetector.isBot("Mozilla/5.0"));
  }

  @Test
  void aFullBrowserUserAgentIsNotABot() {
    assertFalse(BotDetector.isBot(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"));
  }

  @Test
  void blankOrMissingIsABot() {
    assertTrue(BotDetector.isBot(null));
    assertTrue(BotDetector.isBot(""));
  }

  @Test
  void anObviousCrawlerNameIsABotEvenWithAPlatformBlock() {
    assertTrue(BotDetector.isBot("Googlebot/2.1 (+http://www.google.com/bot.html)"));
  }
}
