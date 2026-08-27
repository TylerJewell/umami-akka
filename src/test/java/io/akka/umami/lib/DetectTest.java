package io.akka.umami.lib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** SPEC R49, R50, R5, R6: what can be told about a visitor from the request alone. */
class DetectTest {

  private static final String CHROME_ON_WINDOWS =
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
          + "Chrome/120.0.0.0 Safari/537.36";
  private static final String SAFARI_ON_IPHONE =
      "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 "
          + "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1";
  private static final String FIREFOX_ON_LINUX =
      "Mozilla/5.0 (X11; Linux x86_64; rv:121.0) Gecko/20100101 Firefox/121.0";

  @AfterEach
  void clear() {
    Env.clearOverrides();
  }

  @Test
  void theBrowserIsWhicheverRuleMatchesFirst() {
    assertEquals("chrome", Detect.browserName(CHROME_ON_WINDOWS));
    assertEquals("ios", Detect.browserName(SAFARI_ON_IPHONE));
    assertEquals("firefox", Detect.browserName(FIREFOX_ON_LINUX));
    assertEquals("edge-chromium", Detect.browserName(CHROME_ON_WINDOWS + " Edg/120.0.0.0"));
  }

  /**
   * SPEC R50a: the two rules at the end of the list, the ones a hand-written table stops before.
   *
   * <p>Both are reachable in a running deployment and neither is reached often. `curl` needs the
   * robot check turned off, because the robot list refuses that agent first; `searchbot` needs an
   * agent the robot list does not refuse, which the last case is -- checked against the running
   * original, question-log row 76.
   */
  @Test
  void theTwoRulesAfterTheBrowsersAreStillBrowserNames() {
    assertEquals("curl", Detect.browserName("curl/8.4.0"));
    assertNull(Detect.browserName("curl/8.4.0 something"), "the rule is anchored at both ends");
    assertEquals("searchbot", Detect.browserName("mozilla/5.0 yahoo!"));
    assertEquals("searchbot", Detect.browserName("something feedburner"));
    assertEquals("chrome", Detect.browserName(CHROME_ON_WINDOWS + " yahoo!"),
        "an earlier rule still wins: the list is ordered and searchbot is last");
  }

  /**
   * SPEC R50a: every rule anchored at the end is anchored at the end of the whole agent.
   *
   * <p>Java's ordinary end anchor also matches immediately before a final line terminator and the
   * original's does not, so an agent with a newline on it resolves to a browser here and to
   * nothing there. Checked against the running original: `Mozilla/5.0 MiuiBrowser/17.0` is stored
   * as `miui` and the same string with a newline is stored with no browser at all. Question-log
   * row 78.
   */
  @Test
  void anAgentWithANewlineOnTheEndMatchesNoRuleAnchoredThere() {
    assertEquals("miui", Detect.browserName("Mozilla/5.0 MiuiBrowser/17.0"));
    assertNull(Detect.browserName("Mozilla/5.0 MiuiBrowser/17.0\n"));
    assertEquals("curl", Detect.browserName("curl/8.4.0"));
    assertNull(Detect.browserName("curl/8.4.0\n"));
    var webview = "Mozilla/5.0 AppleWebKit/537.36 (KHTML, like Gecko)";
    assertEquals("ios-webview", Detect.browserName(webview));
    assertNull(Detect.browserName(webview + "\n"));
    // A rule ending in `\s or end` is not affected, because a newline is whitespace on
    // both sides and the first half of the alternation matches. The original answers
    // `chrome` for both of these, which is what makes the three above the whole of it.
    assertEquals("chrome", Detect.browserName("Mozilla/5.0 Chrome/120.0.0.0"));
    assertEquals("chrome", Detect.browserName("Mozilla/5.0 Chrome/120.0.0.0\n"));
  }

  @Test
  void anUnclassifiableAgentRecordsNoBrowserAtAll() {
    assertNull(Detect.browserName("some-agent/1.0"));
    assertNull(Detect.browserName(null));
  }

  @Test
  void theOperatingSystemIsWhicheverRuleMatchesFirst() {
    assertEquals("Windows 10", Detect.detectOS(CHROME_ON_WINDOWS));
    assertEquals("iOS", Detect.detectOS(SAFARI_ON_IPHONE));
    assertEquals("Linux", Detect.detectOS(FIREFOX_ON_LINUX));
    assertEquals("Mac OS",
        Detect.detectOS("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) Chrome/120"));
    assertNull(Detect.detectOS("some-agent/1.0"));
  }

  @Test
  void aDesktopNoWiderThanNineteenTwentyIsALaptop() {
    assertEquals("laptop", Detect.getDevice(CHROME_ON_WINDOWS, "1512x982"));
    assertEquals("laptop", Detect.getDevice(CHROME_ON_WINDOWS, "1920x1080"));
    assertEquals("desktop", Detect.getDevice(CHROME_ON_WINDOWS, "1921x1080"));
    assertEquals("desktop", Detect.getDevice(CHROME_ON_WINDOWS, null),
        "with no screen there is nothing to compare");
  }

  @Test
  void aPhoneIsAPhoneWhateverItsScreenSays() {
    assertEquals("mobile", Detect.getDevice(SAFARI_ON_IPHONE, "390x844"));
    assertEquals("mobile", Detect.getDevice(SAFARI_ON_IPHONE, "3000x2000"));
  }

  @Test
  void aTabletAndATelevisionAreTheirOwnFamilies() {
    assertTrue(Detect.getDevice("Mozilla/5.0 (iPad; CPU OS 17_0) Safari", "1024x768")
        .equals("tablet"));
    assertEquals("console",
        Detect.getDevice("Mozilla/5.0 (PlayStation 5/2.26) AppleWebKit", "1920x1080"));
  }

  @Test
  void aScreenThatIsNotAPairOfNumbersLeavesTheFamilyAlone() {
    assertEquals("desktop", Detect.getDevice(CHROME_ON_WINDOWS, "not-a-screen"));
  }

  @Test
  void aRobotIsRecognisedAndARealBrowserIsNot() {
    assertTrue(Detect.isBot(
        "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)"));
    assertTrue(Detect.isBot("curl/8.0.1"));
    assertTrue(Detect.isBot("Python-urllib/3.14"));
    assertTrue(Detect.isBot(null), "no agent at all is not a person");
    assertFalse(Detect.isBot(CHROME_ON_WINDOWS));
    assertFalse(Detect.isBot(SAFARI_ON_IPHONE));
  }

  @Test
  void theAddressComesFromTheFirstHeaderThatCarriesOne() {
    assertEquals("1.2.3.4", Detect.getIpAddress(Map.of("x-forwarded-for", "1.2.3.4")));
    assertEquals("1.2.3.4",
        Detect.getIpAddress(Map.of("x-forwarded-for", "1.2.3.4, 10.0.0.1, 10.0.0.2")),
        "a forwarding chain names its origin first");
    assertEquals("1.2.3.4", Detect.getIpAddress(Map.of("cf-connecting-ip", "1.2.3.4")));
    assertEquals("1.2.3.4",
        Detect.getIpAddress(Map.of("cf-connecting-ip", "1.2.3.4", "x-forwarded-for", "9.9.9.9")),
        "a provider header outranks the forwarding chain");
    assertEquals("192.0.2.60",
        Detect.getIpAddress(Map.of("forwarded", "for=192.0.2.60;proto=http;by=203.0.113.43")));
    assertNull(Detect.getIpAddress(Map.of()));
  }

  @Test
  void aConfiguredHeaderOutranksTheWholeList() {
    Env.override("CLIENT_IP_HEADER", "x-custom-ip");
    assertEquals("10.0.0.1",
        Detect.getIpAddress(Map.of("x-custom-ip", "10.0.0.1", "cf-connecting-ip", "9.9.9.9")));
    assertEquals("9.9.9.9", Detect.getIpAddress(Map.of("cf-connecting-ip", "9.9.9.9")),
        "with the configured header absent the list is tried as usual");
  }

  @Test
  void anAddressMappedIntoTheNewerFamilyIsReportedInTheOlderForm() {
    assertEquals("1.2.3.4", Detect.normalizeIp("::ffff:1.2.3.4"));
    assertEquals("1.2.3.4", Detect.normalizeIp("1.2.3.4:8080"));
    assertEquals("[::1]", Detect.normalizeIp("[::1]:8080"));
    assertEquals("not-an-ip", Detect.normalizeIp("not-an-ip"));
  }

  @Test
  void theRefusalListTakesAddressesAndRanges() {
    Env.override("IGNORE_IP", "1.2.3.4, 10.0.0.0/8");
    assertTrue(Detect.hasBlockedIp("1.2.3.4"));
    assertTrue(Detect.hasBlockedIp("10.9.9.9"));
    assertFalse(Detect.hasBlockedIp("11.0.0.1"));
    assertFalse(Detect.hasBlockedIp("not-an-ip"), "an entry that cannot be read is ignored");
  }

  @Test
  void aLocalOrUnreadableAddressHasNoLocationAtAll() {
    assertNull(Detect.getLocation("127.0.0.1", Map.of("cf-ipcountry", "US"), false));
    assertNull(Detect.getLocation("not-an-ip", Map.of("cf-ipcountry", "US"), false));
    assertNull(Detect.getLocation("10.0.0.1", Map.of("cf-ipcountry", "US"), false));
  }

  @Test
  void theFirstProviderHeaderPresentDecidesTheLocation() {
    var location =
        Detect.getLocation("8.8.8.8",
            Map.of("cf-ipcountry", "US", "cf-region-code", "CA", "cf-ipcity", "Los Angeles"),
            false);
    assertEquals("US", location.country());
    assertEquals("US-CA", location.region());
    assertEquals("Los Angeles", location.city());
  }

  @Test
  void aRegionThatAlreadyNamesItsCountryIsLeftAlone() {
    assertEquals("US-CA", Detect.regionCode("US", "CA"));
    assertEquals("US-CA", Detect.regionCode("US", "US-CA"));
    assertNull(Detect.regionCode("US", null));
    assertNull(Detect.regionCode(null, "CA"));
  }

  @Test
  void anAddressSuppliedByTheCallerSkipsTheProviderHeaders() {
    assertNull(Detect.getLocation("8.8.8.8", Map.of("cf-ipcountry", "US"), true),
        "with no geography database configured, nothing is left to answer from");
  }
}
