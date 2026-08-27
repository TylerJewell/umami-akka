package io.akka.umami.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import akka.javasdk.testkit.TestKitSupport;
import io.akka.umami.lib.Json;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * SPEC R1 to R24 and R25 to R33, driven the way a page on somebody's site drives them.
 *
 * <p>Every event here names its own instant, which pins the salt bucket and switches off the
 * visit rollover, so the sequence is the same whatever the clock says while the test runs.
 */
class CollectionIntegrationTest extends TestKitSupport {

  private static final long BASE = 1756000000L;
  private static final String DESKTOP =
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
          + "Chrome/120.0.0.0 Safari/537.36";
  private static final String PHONE =
      "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 "
          + "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1";

  private HttpClientSupport http;

  @BeforeEach
  void signIn() {
    http = new HttpClientSupport("http://localhost:" + testKit.getPort());
    // The first administrator is written at startup and the index a sign-in reads is brought
    // up to date just after, so the first sign-in of a run waits for it.
    var answer =
        Settle.untilStarted(() -> http.signIn("admin", "umami"), a -> a.status() == 200,
            "the first administrator");
    assertEquals(200, answer.status());
  }

  /** Reads a figure until the read side shows the events already written. */
  private HttpClientSupport.Answer settled(String path, String field, long expected) {
    return Settle.until(() -> http.get(path), a -> a.status() == 200 && a.number(field) == expected,
        field + " of " + expected);
  }

  private HttpClientSupport.Answer settledSize(String path, int expected) {
    return Settle.until(() -> http.get(path),
        a -> a.status() == 200 && a.body() != null && a.body().size() == expected,
        expected + " rows");
  }

  private String createWebsite(String name, String domain) {
    var body = Json.object();
    body.put("name", name);
    body.put("domain", domain);
    var answer = http.post("/api/websites", body);
    assertEquals(200, answer.status(), "creating a website");
    return answer.text("id");
  }

  private HttpClientSupport.Answer send(String websiteId, String url, String name, long at,
      String userAgent, String ip, String cache, String referrer) {
    var payload = Json.object();
    payload.put("website", websiteId);
    payload.put("url", url);
    payload.put("hostname", "probe.test");
    payload.put("userAgent", userAgent);
    payload.put("ip", ip);
    payload.put("timestamp", at);
    if (name != null) {
      payload.put("name", name);
    }
    if (referrer != null) {
      payload.put("referrer", referrer);
    }
    var body = Json.object();
    body.put("type", "event");
    body.set("payload", payload);
    return cache == null
        ? http.post("/api/send", body)
        : http.post("/api/send", body, Map.of("x-umami-cache", cache));
  }

  private String range() {
    return "?startAt=" + ((BASE - 3600) * 1000) + "&endAt=" + ((BASE + 3600) * 1000)
        + "&timezone=UTC";
  }

  @Test
  void thePageViewCustomEventAndRollupAgreeOnWhatEachCounts() {
    var websiteId = createWebsite("rollup", "rollup.test");

    var first = send(websiteId, "/", null, BASE, DESKTOP, "203.0.113.9", null, null);
    assertEquals(200, first.status());
    assertNotNull(first.text("cache"), "a page view answers a cache token");
    assertNotNull(first.text("sessionId"));
    assertNotNull(first.text("visitId"));
    var cache = first.text("cache");

    var named = send(websiteId, "/", "signup", BASE + 10, DESKTOP, "203.0.113.9", cache, null);
    assertEquals(200, named.status());
    assertEquals(first.text("visitId"), named.text("visitId"), "the same visit");

    send(websiteId, "/pricing", null, BASE + 20, DESKTOP, "203.0.113.9", cache, null);
    send(websiteId, "/x", null, BASE + 50, DESKTOP, "203.0.113.9", "not-a-token", null);
    send(websiteId, "/", null, BASE + 40, PHONE, "198.51.100.7", null, null);

    var stats = settled("/api/websites/" + websiteId + "/stats" + range(), "pageviews", 4);
    assertEquals(200, stats.status());
    assertEquals(4, stats.number("pageviews"), "the named event is not a page view");
    assertEquals(2, stats.number("visitors"));
    assertEquals(2, stats.number("visits"));
    assertEquals(1, stats.number("bounces"), "one visit had a single view and nothing named");
    assertEquals(50, stats.number("totaltime"),
        "the last view minus the first, per visit, in seconds");
    assertNotNull(stats.body().get("comparison"), "the rollup always carries a comparison");
  }

  @Test
  void aRobotIsAcceptedAndRecordsNothing() {
    var websiteId = createWebsite("robot", "robot.test");
    var answer =
        send(websiteId, "/", null, BASE, "Mozilla/5.0 (compatible; Googlebot/2.1)",
            "203.0.113.9", null, null);
    assertEquals(200, answer.status(), "a robot is not refused");
    assertEquals("boop", answer.text("beep"));

    // Nothing was recorded, so there is nothing to wait for; a moment's grace is enough to
    // show that nothing arrives later either.
    var stats =
        Settle.until(() -> http.get("/api/websites/" + websiteId + "/stats" + range()),
            a -> a.status() == 200, "an answer");
    assertEquals(0, stats.number("pageviews"));
  }

  @Test
  void aPayloadNamingNoSourceOrTwoIsRefusedWithTheSameSentence() {
    var payload = Json.object();
    payload.put("url", "/");
    var body = Json.object();
    body.put("type", "event");
    body.set("payload", payload);
    var answer = http.post("/api/send", body);
    assertEquals(400, answer.status());
    // The payload is a field of the request, so its failures are reported under its own key.
    assertEquals(
        "Exactly one of website, link, or pixel must be provided",
        answer.body().get("error").get("properties").get("payload").get("properties")
            .get("website").get("errors").get(0).asText());
  }

  @Test
  void aNameBeginningWithAFormulaCharacterIsRefused() {
    var websiteId = createWebsite("formula", "formula.test");
    var answer = send(websiteId, "/", "=cmd", BASE, DESKTOP, "203.0.113.9", null, null);
    assertEquals(400, answer.status());
    assertEquals(
        "Value must not start with =, +, -, @, tab, or carriage return",
        answer.body().get("error").get("properties").get("payload").get("properties")
            .get("name").get("errors").get(0).asText());
  }

  @Test
  void anUnknownWebsiteIsRefusedByName() {
    var payload = Json.object();
    payload.put("website", "00000000-0000-4000-8000-000000000000");
    payload.put("url", "/");
    payload.put("userAgent", DESKTOP);
    var body = Json.object();
    body.put("type", "event");
    body.set("payload", payload);
    var answer = http.post("/api/send", body);
    assertEquals(400, answer.status());
    assertEquals("Website not found.", answer.errorMessage());
  }

  @Test
  void anUnusableCacheTokenIsIgnoredAndAFreshOneIsIssued() {
    var websiteId = createWebsite("cache", "cache.test");
    var answer = send(websiteId, "/", null, BASE, DESKTOP, "203.0.113.9", "not-a-token", null);
    assertEquals(200, answer.status());
    assertNotNull(answer.text("cache"));
  }

  @Test
  void twoVisitorsAreTwoSessionsAndOneVisitorIsOne() {
    var websiteId = createWebsite("sessions", "sessions.test");
    var one = send(websiteId, "/", null, BASE, DESKTOP, "203.0.113.9", null, null);
    var again = send(websiteId, "/again", null, BASE + 5, DESKTOP, "203.0.113.9", null, null);
    var other = send(websiteId, "/", null, BASE, PHONE, "198.51.100.7", null, null);

    assertEquals(one.text("sessionId"), again.text("sessionId"),
        "the same address and agent resolve to the same session with no token at all");
    assertNotEquals(one.text("sessionId"), other.text("sessionId"));
  }

  @Test
  void theDimensionsCountWhatEachFamilyCounts() {
    var websiteId = createWebsite("dimensions", "dimensions.test");
    var cache = send(websiteId, "/", null, BASE, DESKTOP, "203.0.113.9", null,
        "https://www.google.com/search?q=x").text("cache");
    send(websiteId, "/", "signup", BASE + 10, DESKTOP, "203.0.113.9", cache, null);
    send(websiteId, "/", "signup", BASE + 20, DESKTOP, "203.0.113.9", cache, null);
    send(websiteId, "/", null, BASE + 30, PHONE, "198.51.100.7", null,
        "https://news.ycombinator.com/");

    var byPath =
        Settle.until(
            () -> http.get("/api/websites/" + websiteId + "/metrics" + range() + "&type=path"),
            a -> a.status() == 200 && a.body().size() == 1
                && a.body().get(0).get("y").asLong() == 2,
            "one path seen by two sessions");
    assertEquals("/", byPath.body().get(0).get("x").asText());
    assertEquals(2, byPath.body().get(0).get("y").asLong(), "a path counts distinct sessions");

    var byEvent =
        Settle.until(
            () -> http.get("/api/websites/" + websiteId + "/metrics" + range() + "&type=event"),
            a -> a.status() == 200 && a.body().size() == 1
                && a.body().get(0).get("y").asLong() == 2,
            "one event name counted twice");
    assertEquals("signup", byEvent.body().get(0).get("x").asText());
    assertEquals(2, byEvent.body().get(0).get("y").asLong(), "an event counts rows");

    settledSize("/api/websites/" + websiteId + "/metrics" + range() + "&type=device", 2);

    var byChannel =
        settledSize("/api/websites/" + websiteId + "/metrics" + range() + "&type=channel", 2);
    var channels = new java.util.HashSet<String>();
    byChannel.body().forEach(row -> channels.add(row.get("x").asText()));
    assertTrue(channels.contains("organicSearch"), "a search referrer");
    assertTrue(channels.contains("organicSocial"), "a social referrer");
  }

  @Test
  void aDimensionNameThatIsNotOneIsRefused() {
    var websiteId = createWebsite("baddimension", "baddimension.test");
    assertEquals(400,
        http.get("/api/websites/" + websiteId + "/metrics" + range() + "&type=url").status(),
        "the names are the filter names, not the column names");
    assertEquals(400,
        http.get("/api/websites/" + websiteId + "/metrics" + range() + "&type=nonsense").status());
  }

  @Test
  void anOperatorWithNoMeaningOnADimensionLeavesTheQueryMalformed() {
    var websiteId = createWebsite("badoperator", "badoperator.test");
    var answer =
        http.get("/api/websites/" + websiteId + "/stats" + range() + "&path=gt./pricing");
    assertEquals(500, answer.status(),
        "the original emits an empty condition and its store refuses the query");
  }

  @Test
  void aFilterChangesWhatBouncesWithoutNarrowingWhatDecidesIt() {
    var websiteId = createWebsite("bounce", "bounce.test");
    var cache = send(websiteId, "/", null, BASE, DESKTOP, "203.0.113.9", null, null).text("cache");
    send(websiteId, "/", "signup", BASE + 10, DESKTOP, "203.0.113.9", cache, null);
    send(websiteId, "/pricing", null, BASE + 20, DESKTOP, "203.0.113.9", cache, null);

    var filtered =
        settled("/api/websites/" + websiteId + "/stats" + range() + "&path=eq./pricing",
            "pageviews", 1);
    assertEquals(0, filtered.number("bounces"),
        "the named event that stops it bouncing is picked up by a lookup the filter does not reach");
  }

  @Test
  void excludingBouncesRemovesTheWholeVisit() {
    var websiteId = createWebsite("exclude", "exclude.test");
    var cache = send(websiteId, "/", null, BASE, DESKTOP, "203.0.113.9", null, null).text("cache");
    send(websiteId, "/pricing", null, BASE + 50, DESKTOP, "203.0.113.9", cache, null);
    send(websiteId, "/", null, BASE + 40, PHONE, "198.51.100.7", null, null);

    var all = settled("/api/websites/" + websiteId + "/stats" + range(), "pageviews", 3);
    assertEquals(1, all.number("bounces"));

    var kept = http.get("/api/websites/" + websiteId + "/stats" + range() + "&excludeBounce=true");
    assertEquals(2, kept.number("pageviews"));
    assertEquals(1, kept.number("visitors"));
    assertEquals(0, kept.number("bounces"), "the figure is zero rather than what remained");
  }

  @Test
  void theSeriesCarriesInstantsWithAZoneMarkerWhenNoZoneIsNamed() {
    var websiteId = createWebsite("series", "series.test");
    send(websiteId, "/", null, BASE, DESKTOP, "203.0.113.9", null, null);
    var answer =
        Settle.until(
            () -> http.get("/api/websites/" + websiteId + "/pageviews" + range() + "&unit=hour"),
            a -> a.status() == 200 && a.body().get("pageviews").size() == 1, "one bucket");
    var bucket = answer.body().get("pageviews").get(0).get("x").asText();
    assertTrue(bucket.endsWith("Z"), bucket);
    assertTrue(bucket.contains("T"));
  }

  @Test
  void aBatchIsReplayedThroughTheSamePathAndCappedAtFiveHundred() {
    var websiteId = createWebsite("batch", "batch.test");
    var batch = Json.array();
    for (int i = 0; i < 3; i++) {
      var payload = Json.object();
      payload.put("website", websiteId);
      payload.put("url", "/b" + i);
      payload.put("userAgent", DESKTOP);
      payload.put("ip", "203.0.113.4");
      payload.put("timestamp", BASE + i);
      var element = Json.object();
      element.put("type", "event");
      element.set("payload", payload);
      batch.add(element);
    }
    var answer = http.post("/api/batch", batch);
    assertEquals(200, answer.status());
    assertEquals(3, answer.number("size"));
    assertEquals(3, answer.number("processed"));
    assertEquals(0, answer.number("errors"));

    var big = Json.array();
    for (int i = 0; i < 501; i++) {
      big.add(Json.object());
    }
    var refused = http.post("/api/batch", big);
    assertEquals(400, refused.status());
    assertEquals("Too big: expected array to have <=500 items",
        refused.body().get("error").get("errors").get(0).asText());
  }

  @Test
  void aPropertySetIsFlattenedAndReadableBack() {
    var websiteId = createWebsite("properties", "properties.test");
    var payload = Json.object();
    payload.put("website", websiteId);
    payload.put("url", "/");
    payload.put("name", "signup");
    payload.put("userAgent", DESKTOP);
    payload.put("ip", "203.0.113.9");
    payload.put("timestamp", BASE);
    var data = Json.object();
    data.put("plan", "pro");
    data.put("seats", 3);
    payload.set("data", data);
    var body = Json.object();
    body.put("type", "event");
    body.set("payload", payload);
    assertEquals(200, http.post("/api/send", body).status());

    var fields =
        settledSize("/api/websites/" + websiteId + "/event-data/fields" + range()
            + "&eventName=signup", 2);
    assertEquals(200, fields.status());

    var values =
        http.get("/api/websites/" + websiteId + "/event-data/values" + range()
            + "&eventName=signup&propertyName=plan");
    assertEquals(200, values.status());
    assertEquals("pro", values.body().get(0).get("value").asText());

    var stats =
        settled("/api/websites/" + websiteId + "/event-data/stats" + range(), "events", 1);
    assertEquals(2, stats.number("properties"));
    assertEquals(2, stats.number("records"));
  }

  @Test
  void theActiveCountAndTheDateRangeAnswerWithoutAWindow() {
    var websiteId = createWebsite("now", "now.test");
    assertEquals(200, http.get("/api/websites/" + websiteId + "/active").status());
    var range = http.get("/api/websites/" + websiteId + "/daterange");
    assertEquals(200, range.status());
    assertTrue(range.body().has("startDate"));
  }

  @Test
  void theShortLinkAndThePixelCollectAndAnswerTheirOwnShapes() {
    var link = Json.object();
    link.put("name", "probe link");
    link.put("url", "https://example.test/x");
    link.put("slug", "probelinkone");
    assertEquals(200, http.post("/api/links", link).status());

    var redirect =
        Settle.until(() -> http.get("/q/probelinkone"), a -> a.status() == 307,
            "the short link");
    assertEquals(307, redirect.status());
    assertEquals("https://example.test/x", redirect.headers().get("location"));
    assertEquals(404, http.get("/q/nothing-here").status());

    var pixel = Json.object();
    pixel.put("name", "probe pixel");
    pixel.put("slug", "probepixelone");
    assertEquals(200, http.post("/api/pixels", pixel).status());
    var image =
        Settle.until(() -> http.get("/p/probepixelone"), a -> a.status() == 200, "the pixel");
    assertTrue(image.headers().get("content-type").startsWith("image/gif"));
    assertEquals("no-cache, no-store, must-revalidate", image.headers().get("cache-control"));
  }

  @Test
  void theHealthAndConfigurationRoutesAnswerWithoutASignIn() {
    var anonymous = new HttpClientSupport("http://localhost:" + testKit.getPort());
    var heartbeat = anonymous.get("/api/heartbeat");
    assertEquals(200, heartbeat.status());
    assertTrue(heartbeat.body().get("ok").asBoolean());

    var config = anonymous.get("/api/config");
    assertEquals(200, config.status());
    assertFalse(config.body().get("cloudMode").asBoolean());
    assertTrue(config.body().get("sessionDeletionEnabled").asBoolean());
    assertFalse(config.body().has("faviconUrl"),
        "a setting with no value is absent rather than null");
  }
}
