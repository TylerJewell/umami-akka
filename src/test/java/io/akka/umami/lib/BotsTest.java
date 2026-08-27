package io.akka.umami.lib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.junit.jupiter.api.Test;

/**
 * SPEC R5, R5a: which user agents are robots.
 *
 * <p>The interesting cases are not the obvious robots — any list anybody writes catches
 * `Googlebot`. They are the browsers a careless list catches by accident, because the original
 * accepts a robot's request and stores nothing, so calling a browser a robot silently discards
 * that visitor's traffic and no error appears anywhere.
 */
class BotsTest {

  @Test
  void everyPatternCompiles() {
    // Translated from another engine's syntax, so each is compiled on its own: a combined
    // alternation that fails to compile says nothing about which of the 209 was at fault.
    for (var pattern : Bots.PATTERNS) {
      try {
        Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
      } catch (PatternSyntaxException broken) {
        throw new AssertionError("cannot compile " + pattern + ": " + broken.getMessage());
      }
    }
    assertEquals(209, Bots.PATTERNS.size());
  }

  @Test
  void anOrdinaryBrowserIsNotARobot() {
    for (var agent : List.of(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/120.0.0.0 Safari/537.36",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 "
            + "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1",
        "Mozilla/5.0 (X11; Linux x86_64; rv:120.0) Gecko/20100101 Firefox/120.0",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/119.0.0.0 Safari/537.36 Edg/119.0.0.0")) {
      assertFalse(Bots.isBot(agent), agent);
    }
  }

  @Test
  void aBrowserWhoseNameContainsARobotsIsStillNotARobot() {
    // `(?<! cu)bots?(?:\b|_)` and `(?<!cam)scan`. A substring list gets both of these wrong,
    // and this port's first one did.
    assertFalse(Bots.isBot("Mozilla/5.0 (Windows NT 10.0) Cubots/1.0"));
    assertFalse(Bots.isBot("Mozilla/5.0 (Windows NT 10.0) CamScanner/5.0"));
  }

  @Test
  void googlesOwnWebViewIsNotARobotButGooglebotIs() {
    // `(?<! channel/|google/)google(?!(?:wv|app|/google| pixel))`
    assertFalse(Bots.isBot(
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 "
            + "Chrome/119.0.0.0 Mobile Safari/537.36 GoogleWV"));
    assertTrue(Bots.isBot(
        "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)"));
  }

  @Test
  void theOrdinaryRobotsAreRobots() {
    for (var agent : List.of(
        "Mozilla/5.0 (compatible; bingbot/2.0; +http://www.bing.com/bingbot.htm)",
        "curl/8.4.0",
        "Wget/1.21.3",
        "python-requests/2.31.0",
        "PostmanRuntime/7.35.0",
        "facebookexternalhit/1.1",
        "Twitterbot/1.0",
        "Mozilla/5.0 (compatible; YandexBot/3.0; +http://yandex.com/bots)",
        "Mozilla/5.0 (compatible; AhrefsBot/7.0; +http://ahrefs.com/robot/)",
        "Java/17.0.2",
        "Go-http-client/2.0",
        "Pingdom.com_bot_version_1.4_(http://www.pingdom.com/)")) {
      assertTrue(Bots.isBot(agent), agent);
    }
  }

  @Test
  void aHeadlessBrowserIsARobot() {
    assertTrue(Bots.isBot(
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "HeadlessChrome/119.0.0.0 Safari/537.36"));
  }

  @Test
  void nothingAtAllIsARobot() {
    assertTrue(Bots.isBot(null));
    assertTrue(Bots.isBot(""));
    assertTrue(Bots.isBot("   "));
  }

  @Test
  void theCheckIsCaseInsensitive() {
    assertTrue(Bots.isBot("GOOGLEBOT/2.1"));
    assertTrue(Bots.isBot("CURL/8.4.0"));
  }

  @Test
  void detectAsksTheSameList() {
    assertEquals(Bots.isBot("curl/8.4.0"), Detect.isBot("curl/8.4.0"));
    assertEquals(Bots.isBot("Mozilla/5.0 (Windows NT 10.0) Cubots/1.0"),
        Detect.isBot("Mozilla/5.0 (Windows NT 10.0) Cubots/1.0"));
  }
}
