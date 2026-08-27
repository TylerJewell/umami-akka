package io.akka.umami.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import akka.javasdk.testkit.TestKitSupport;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.akka.umami.lib.Env;
import io.akka.umami.lib.Json;
import io.akka.umami.lib.TwoFactor;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** SPEC R62 to R76 and R105 to R114: the ten reports, and the second factor end to end. */
class ReportIntegrationTest extends TestKitSupport {

  private static final long BASE = 1756000000L;
  private static final String START = "2025-08-24T00:00:00Z";
  private static final String END = "2025-08-24T03:00:00Z";
  private static final String DESKTOP =
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
          + "Chrome/120.0.0.0 Safari/537.36";
  private static final String PHONE =
      "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 "
          + "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1";

  private HttpClientSupport http;

  @BeforeEach
  void signIn() {
    Env.override("TWO_FACTOR_ENCRYPTION_KEY",
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
    http = new HttpClientSupport("http://localhost:" + testKit.getPort());
    Settle.untilStarted(() -> http.signIn("admin", "umami"), a -> a.status() == 200,
        "the first administrator");
  }

  @AfterEach
  void clear() {
    Env.clearOverrides();
  }

  private String createWebsite(String name, String domain) {
    var body = Json.object();
    body.put("name", name);
    body.put("domain", domain);
    var answer = http.post("/api/websites", body);
    assertEquals(200, answer.status(), answer.body() == null ? "no body" : answer.body().toString());
    return answer.text("id");
  }

  private String send(String websiteId, String url, String name, long at, String userAgent,
      String ip, String cache) {
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
    var body = Json.object();
    body.put("type", "event");
    body.set("payload", payload);
    var answer =
        cache == null ? http.post("/api/send", body)
            : http.post("/api/send", body, Map.of("x-umami-cache", cache));
    assertEquals(200, answer.status(), answer.body() == null ? "no body" : answer.body().toString());
    return answer.text("cache");
  }

  private ObjectNode execution(String websiteId, String type, ObjectNode parameters) {
    parameters.put("startDate", START);
    parameters.put("endDate", END);
    var body = Json.object();
    body.put("websiteId", websiteId);
    body.set("filters", Json.object());
    body.put("type", type);
    body.set("parameters", parameters);
    return body;
  }

  private String seeded(String name, String domain) {
    var websiteId = createWebsite(name, domain);
    var cache = send(websiteId, "/", null, BASE, DESKTOP, "203.0.113.9", null);
    send(websiteId, "/pricing", null, BASE + 60, DESKTOP, "203.0.113.9", cache);
    send(websiteId, "/checkout", null, BASE + 120, DESKTOP, "203.0.113.9", cache);
    send(websiteId, "/", "signup", BASE + 180, DESKTOP, "203.0.113.9", cache);
    send(websiteId, "/", null, BASE + 30, PHONE, "198.51.100.7", null);
    var window = "?startAt=" + ((BASE - 3600) * 1000) + "&endAt=" + ((BASE + 3600) * 1000)
        + "&timezone=UTC";
    Settle.until(() -> http.get("/api/websites/" + websiteId + "/stats" + window),
        a -> a.status() == 200 && a.number("pageviews") == 4, "the seeded traffic");
    return websiteId;
  }

  // ------------------------------------------------------------------ reports

  @Test
  void aFunnelNeedsAtLeastTwoStepsAndReportsItsDropOff() {
    var websiteId = seeded("probe funnel", "probefunnel.test");

    var one = Json.object();
    one.put("window", 60);
    var steps = Json.array();
    var step = Json.object();
    step.put("type", "path");
    step.put("value", "/");
    steps.add(step);
    one.set("steps", steps);
    var refused = http.post("/api/reports/funnel", execution(websiteId, "funnel", one));
    assertEquals(400, refused.status());
    assertEquals("Too small: expected array to have >=2 items",
        refused.body().get("error").get("properties").get("parameters").get("properties")
            .get("steps").get("errors").get(0).asText());

    var two = Json.object();
    two.put("window", 60);
    var pair = Json.array();
    var first = Json.object();
    first.put("type", "path");
    first.put("value", "/");
    var second = Json.object();
    second.put("type", "path");
    second.put("value", "/checkout");
    pair.add(first);
    pair.add(second);
    two.set("steps", pair);
    var answer = http.post("/api/reports/funnel", execution(websiteId, "funnel", two));
    assertEquals(200, answer.status());
    assertEquals(2, answer.body().size());
    assertEquals(2, answer.body().get(0).get("visitors").asLong(), "both visitors saw the root");
    assertEquals(1, answer.body().get(1).get("visitors").asLong(), "one reached the checkout");
    assertEquals(0, answer.body().get(0).get("previous").asLong());
    // The arithmetic gives negative infinity and the answer format cannot carry one, so the
    // first step's drop-off is written as nothing at all.
    assertTrue(answer.body().get(0).get("dropoff").isNull(),
        "the first step has nothing before it");
    assertEquals(0.5, answer.body().get(1).get("dropoff").asDouble(), 0.0001);
    // The first step was reached, so the proportion remaining has a value on both steps.
    assertEquals(1.0, answer.body().get(0).get("remaining").asDouble(), 0.0001);
    assertEquals(0.5, answer.body().get(1).get("remaining").asDouble(), 0.0001);
  }

  /** SPEC R64a: a funnel nobody entered divides nothing by nothing on both proportions. */
  @Test
  void anEmptyFunnelHasNoProportions() {
    var websiteId = seeded("probe empty funnel", "probeemptyfunnel.test");

    var parameters = Json.object();
    parameters.put("window", 60);
    var pair = Json.array();
    for (var path : List.of("/nobody-was-here", "/nor-here")) {
      var step = Json.object();
      step.put("type", "path");
      step.put("value", path);
      pair.add(step);
    }
    parameters.set("steps", pair);

    var answer = http.post("/api/reports/funnel", execution(websiteId, "funnel", parameters));
    assertEquals(200, answer.status());
    assertEquals(2, answer.body().size());
    for (var step : answer.body()) {
      assertEquals(0, step.get("visitors").asLong());
      assertTrue(step.get("dropoff").isNull(), "dropoff was " + step.get("dropoff"));
      // Zero would say the step lost everyone, which is not the same claim as having had
      // nobody to lose.
      assertTrue(step.get("remaining").isNull(), "remaining was " + step.get("remaining"));
    }
  }

  @Test
  void aWildcardStepMatchesByPattern() {
    var websiteId = seeded("probe wildcard", "probewildcard.test");
    var parameters = Json.object();
    parameters.put("window", 60);
    var steps = Json.array();
    var first = Json.object();
    first.put("type", "path");
    first.put("value", "/");
    var second = Json.object();
    second.put("type", "path");
    second.put("value", "/check*");
    steps.add(first);
    steps.add(second);
    parameters.set("steps", steps);
    var answer = http.post("/api/reports/funnel", execution(websiteId, "funnel", parameters));
    assertEquals(200, answer.status());
    assertEquals(1, answer.body().get(1).get("visitors").asLong());
  }

  @Test
  void aGoalCountsWhatMatchedAgainstWhatDidNot() {
    var websiteId = seeded("probe goal", "probegoal.test");
    var parameters = Json.object();
    parameters.put("type", "path");
    parameters.put("value", "/checkout");
    var answer = http.post("/api/reports/goal", execution(websiteId, "goal", parameters));
    assertEquals(200, answer.status());
    assertEquals(1, answer.number("num"));
    assertEquals(2, answer.number("total"));
  }

  @Test
  void aBreakdownGroupsTheDimensionsItIsGiven() {
    var websiteId = seeded("probe breakdown", "probebreakdown.test");
    var parameters = Json.object();
    var fields = Json.array();
    fields.add("path");
    fields.add("device");
    parameters.set("fields", fields);
    var answer = http.post("/api/reports/breakdown", execution(websiteId, "breakdown",
        parameters));
    assertEquals(200, answer.status());
    assertTrue(answer.body().size() >= 3);
    answer.body().forEach(row -> {
      assertTrue(row.has("views"));
      assertTrue(row.has("visitors"));
      assertTrue(row.has("visits"));
      assertTrue(row.has("bounces"));
      assertTrue(row.has("totaltime"));
      assertTrue(row.has("path"));
      assertTrue(row.has("device"));
    });
  }

  @Test
  void aFieldThatIsNotADimensionIsRefused() {
    var websiteId = seeded("probe field", "probefield.test");
    var parameters = Json.object();
    var fields = Json.array();
    fields.add("nonsense");
    parameters.set("fields", fields);
    assertEquals(400,
        http.post("/api/reports/breakdown", execution(websiteId, "breakdown", parameters))
            .status());
  }

  @Test
  void aCampaignReportAnswersTheFiveColumns() {
    var websiteId = seeded("probe utm", "probeutm.test");
    var answer = http.post("/api/reports/utm", execution(websiteId, "utm", Json.object()));
    assertEquals(200, answer.status());
    for (var key : List.of("utm_source", "utm_medium", "utm_campaign", "utm_content",
        "utm_term")) {
      assertTrue(answer.body().has(key), key);
    }
  }

  @Test
  void aJourneyCarriesSevenSlotsWhateverWasAskedFor() {
    var websiteId = seeded("probe journey", "probejourney.test");
    var parameters = Json.object();
    parameters.put("steps", 3);
    var answer = http.post("/api/reports/journey", execution(websiteId, "journey", parameters));
    assertEquals(200, answer.status());
    assertTrue(answer.body().size() >= 1);
    // Seven slots with consecutive equal ones collapsed: a visit of three steps out of three
    // requested leaves three named slots and one null for the four never selected.
    var items = answer.body().get(0).get("items");
    assertTrue(items.size() >= 2 && items.size() <= 7, "collapsed to " + items.size());
    assertTrue(items.get(items.size() - 1).isNull(), "the slots never selected end in nothing");
    assertTrue(answer.body().get(0).get("count").asLong() >= 1);
  }

  @Test
  void aRetentionReportCountsAgainstItsOwnCohort() {
    var websiteId = seeded("probe retention", "proberetention.test");
    var parameters = Json.object();
    parameters.put("timezone", "UTC");
    var answer = http.post("/api/reports/retention", execution(websiteId, "retention",
        parameters));
    assertEquals(200, answer.status());
    assertTrue(answer.body().size() >= 1);
    var row = answer.body().get(0);
    assertEquals(0, row.get("day").asLong());
    assertEquals(2, row.get("visitors").asLong());
    assertEquals(2, row.get("returnVisitors").asLong());
    assertEquals(100.0, row.get("percentage").asDouble(), 0.0001);
  }

  @Test
  void anAttributionReportAnswersItsEightParts() {
    var websiteId = seeded("probe attribution", "probeattribution.test");
    var parameters = Json.object();
    parameters.put("model", "first-click");
    parameters.put("type", "path");
    parameters.put("step", "/checkout");
    var answer = http.post("/api/reports/attribution", execution(websiteId, "attribution",
        parameters));
    assertEquals(200, answer.status());
    for (var key : List.of("referrer", "paidAds", "utm_source", "utm_medium", "utm_campaign",
        "utm_content", "utm_term", "total")) {
      assertTrue(answer.body().has(key), key);
    }
    assertEquals(1, answer.body().get("total").get("visitors").asLong());
  }

  @Test
  void anUnknownAttributionModelIsRefused() {
    var websiteId = seeded("probe model", "probemodel.test");
    var parameters = Json.object();
    parameters.put("model", "middle-click");
    parameters.put("type", "path");
    parameters.put("step", "/");
    assertEquals(400,
        http.post("/api/reports/attribution", execution(websiteId, "attribution", parameters))
            .status());
  }

  @Test
  void aPerformanceReportAnswersItsSixParts() {
    var websiteId = createWebsite("probe performance", "probeperformance.test");
    var payload = Json.object();
    payload.put("website", websiteId);
    payload.put("url", "/");
    payload.put("hostname", "probe.test");
    payload.put("userAgent", DESKTOP);
    payload.put("ip", "203.0.113.9");
    payload.put("timestamp", BASE);
    payload.put("lcp", 1234.5);
    payload.put("inp", 90.2);
    payload.put("cls", 0.0123);
    payload.put("fcp", 800.1);
    payload.put("ttfb", 210.4);
    var body = Json.object();
    body.put("type", "performance");
    body.set("payload", payload);
    assertEquals(200, http.post("/api/send", body).status());

    var parameters = Json.object();
    parameters.put("metric", "lcp");
    parameters.put("timezone", "UTC");
    parameters.put("unit", "hour");
    var answer =
        Settle.until(
            () -> http.post("/api/reports/performance",
                execution(websiteId, "performance", parameters)),
            a -> a.status() == 200 && a.body().get("summary").get("count").asInt() == 1,
            "the vitals");
    for (var key : List.of("chart", "summary", "pages", "pageTitles", "devices", "browsers")) {
      assertTrue(answer.body().has(key), key);
    }
    assertEquals(1234.5, answer.body().get("summary").get("lcp").get("p75").asDouble(), 0.001);
    assertEquals(0.0123, answer.body().get("summary").get("cls").get("p50").asDouble(), 0.0001);
  }

  @Test
  void aRevenueReportAnswersItsSixParts() {
    var websiteId = createWebsite("probe revenue", "proberevenue.test");
    var payload = Json.object();
    payload.put("website", websiteId);
    payload.put("url", "/thanks");
    payload.put("name", "purchase");
    payload.put("hostname", "probe.test");
    payload.put("userAgent", DESKTOP);
    payload.put("ip", "203.0.113.9");
    payload.put("timestamp", BASE);
    var data = Json.object();
    data.put("revenue", 42.5);
    data.put("currency", "USD");
    payload.set("data", data);
    var body = Json.object();
    body.put("type", "event");
    body.set("payload", payload);
    assertEquals(200, http.post("/api/send", body).status());

    var parameters = Json.object();
    parameters.put("currency", "USD");
    parameters.put("timezone", "UTC");
    parameters.put("unit", "hour");
    var answer =
        Settle.until(
            () -> http.post("/api/reports/revenue", execution(websiteId, "revenue", parameters)),
            a -> a.status() == 200 && a.body().get("total").get("count").asInt() == 1,
            "the revenue");
    for (var key : List.of("chart", "total", "country", "region", "referrer", "channel")) {
      assertTrue(answer.body().has(key), key);
    }
    assertEquals(42.5, answer.body().get("total").get("sum").asDouble(), 0.001);
    assertEquals(1, answer.body().get("total").get("unique_count").asInt());
    assertEquals(42.5, answer.body().get("total").get("average").asDouble(), 0.001);
  }

  @Test
  void aHeatmapReportIsClosedToAShareAndAnswersItsFiveParts() {
    var websiteId = seeded("probe heatmap", "probeheatmap.test");
    var parameters = Json.object();
    parameters.put("mode", "click");
    parameters.put("urlPath", "/");
    var answer = http.post("/api/reports/heatmap", execution(websiteId, "heatmap", parameters));
    assertEquals(200, answer.status());
    for (var key : List.of("mode", "pages", "points", "scroll", "snapshot")) {
      assertTrue(answer.body().has(key), key);
    }
    assertEquals("click", answer.body().get("mode").asText());
  }

  @Test
  void aSavedReportIsCreatedReadAndRemoved() {
    var websiteId = seeded("probe saved", "probesaved.test");
    var body = Json.object();
    body.put("websiteId", websiteId);
    body.put("type", "funnel");
    body.put("name", "probe saved funnel");
    var parameters = Json.object();
    parameters.put("window", 60);
    body.set("parameters", parameters);
    var created = http.post("/api/reports", body);
    assertEquals(200, created.status());
    assertEquals("", created.text("description"), "a description defaults to the empty string");

    var read = Settle.until(() -> http.get("/api/reports/" + created.text("id")),
        a -> a.status() == 200, "the saved report");
    assertEquals("funnel", read.text("type"));

    assertEquals(404, http.get("/api/reports/" + java.util.UUID.randomUUID()).status());
    assertEquals(200, http.delete("/api/reports/" + created.text("id")).status());
  }

  // ------------------------------------------------------------------ segments

  @Test
  void aSegmentIsSavedAndReadBackByItsType() {
    var websiteId = seeded("probe segment", "probesegment.test");
    var body = Json.object();
    body.put("type", "segment");
    body.put("name", "probe segment");
    var parameters = Json.object();
    var filters = Json.array();
    var filter = Json.object();
    filter.put("name", "path");
    filter.put("operator", "eq");
    filter.put("value", "/");
    filters.add(filter);
    parameters.set("filters", filters);
    parameters.put("match", "all");
    body.set("parameters", parameters);
    var created = http.post("/api/websites/" + websiteId + "/segments", body);
    assertEquals(200, created.status());

    var listed =
        Settle.until(() -> http.get("/api/websites/" + websiteId + "/segments?type=segment"),
            a -> a.status() == 200 && a.body().get("count").asInt() == 1, "the segment");
    assertEquals(1, listed.body().get("data").size());

    var applied =
        http.get("/api/websites/" + websiteId + "/stats?startAt=" + ((BASE - 3600) * 1000)
            + "&endAt=" + ((BASE + 3600) * 1000) + "&timezone=UTC&segment=" + created.text("id"));
    assertEquals(200, applied.status());
    assertEquals(2, applied.number("pageviews"), "the saved filter narrows to the root");

    assertEquals(400, http.get("/api/websites/" + websiteId + "/segments?type=nonsense").status());
  }

  // ------------------------------------------------------------------ the second factor

  @Test
  void theWholeSecondFactorCeremonyRunsAndCanBeUndone() {
    // On an account of its own: a run that stops before it disables the factor would otherwise
    // leave the administrator unable to sign in for every check after it.
    var account = Json.object();
    account.put("username", "probeceremony");
    account.put("password", "password1");
    account.put("role", "user");
    assertEquals(200, http.post("/api/users", account).status());
    var them = new HttpClientSupport("http://localhost:" + testKit.getPort());
    Settle.until(() -> them.signIn("probeceremony", "password1"), a -> a.status() == 200,
        "the ceremony account");
    http = them;

    var status =
        Settle.until(() -> http.get("/api/2fa/status"), a -> a.status() == 200, "the status");
    assertFalse(status.body().get("isEnabled").asBoolean());
    assertTrue(status.body().get("isConfigured").asBoolean());

    var initiate = http.post("/api/2fa/setup/initiate", Json.object());
    assertEquals(200, initiate.status());
    var secret = initiate.text("manualKey");
    assertNotNull(secret);
    assertTrue(initiate.text("qrCodeDataUrl").startsWith("data:image/png;base64,"));

    var wrong = Json.object();
    wrong.put("token", "000000");
    var refused = http.post("/api/2fa/setup/confirm", wrong);
    assertEquals(400, refused.status());
    assertEquals("two-factor-error-invalid-code", refused.errorCode());
    assertEquals("Invalid verification code", refused.errorMessage());

    var confirm = Json.object();
    confirm.put("token", TwoFactor.generate(secret, Instant.now().getEpochSecond() / 30));
    var confirmed = http.post("/api/2fa/setup/confirm", confirm);
    assertEquals(200, confirmed.status());
    assertEquals(10, confirmed.body().get("backupCodes").size());
    var codes = confirmed.body().get("backupCodes");
    for (var code : codes) {
      assertTrue(code.asText().matches("^[0-9A-F]{16}-[0-9A-F]{16}$"), code.asText());
    }

    var replayed = http.post("/api/2fa/setup/confirm", confirm);
    assertEquals(400, replayed.status());
    assertEquals("two-factor-error-no-pending-setup", replayed.errorCode());

    var enabled = http.get("/api/2fa/status");
    assertTrue(enabled.body().get("isEnabled").asBoolean());

    var anonymous = new HttpClientSupport("http://localhost:" + testKit.getPort());
    var login = anonymous.signIn("probeceremony", "password1");
    assertEquals(200, login.status());
    assertTrue(login.body().get("requiresTwoFactor").asBoolean());
    var partial = login.text("partialToken");
    assertNotNull(partial);
    assertFalse(login.body().has("token"), "no session token is issued yet");

    var both = Json.object();
    both.put("token", "000000");
    both.put("backupCode", codes.get(0).asText());
    var refusedBoth =
        anonymous.post("/api/2fa/verify", both, Map.of("Authorization", "Bearer " + partial));
    assertEquals(400, refusedBoth.status());
    var messages = refusedBoth.body().get("error").get("errors");
    assertEquals(2, messages.size(), "the union is strict on both sides");

    var backup = Json.object();
    backup.put("backupCode", codes.get(0).asText());
    var used =
        anonymous.post("/api/2fa/verify", backup, Map.of("Authorization", "Bearer " + partial));
    assertEquals(200, used.status());
    assertNotNull(used.text("token"));

    var reused =
        anonymous.post("/api/2fa/verify", backup, Map.of("Authorization", "Bearer " + partial));
    assertEquals(400, reused.status());
    assertEquals("two-factor-error-invalid-backup-code", reused.errorCode());

    var disable = Json.object();
    disable.put("password", "password1");
    disable.put("token", TwoFactor.generate(secret,
        (Instant.now().getEpochSecond() / 30) + 1));
    var off = http.post("/api/2fa/disable", disable);
    assertEquals(200, off.status(), off.body() == null ? "" : off.body().toString());
    assertFalse(http.get("/api/2fa/status").body().get("isEnabled").asBoolean());
  }

  @Test
  void aVerificationWithNoAssertionAtAllIsRefusedByName() {
    var anonymous = new HttpClientSupport("http://localhost:" + testKit.getPort());
    var body = Json.object();
    body.put("token", "000000");
    var answer = anonymous.post("/api/2fa/verify", body);
    assertEquals(401, answer.status());
    assertEquals("two-factor-error-missing-token", answer.errorCode());
  }

  @Test
  void requiringTheSecondFactorEverywhereShowsInTheStatus() {
    var on = Json.object();
    on.put("required", true);
    assertEquals(200, http.post("/api/admin/2fa/global", on).status());
    var status =
        Settle.until(() -> http.get("/api/2fa/status"),
            a -> a.status() == 200 && a.body().get("isRequired").asBoolean(),
            "the requirement");
    assertEquals("global", status.body().get("requiredReason").asText());
    assertTrue(status.body().get("globalRequired").asBoolean());

    var off = Json.object();
    off.put("required", false);
    assertEquals(200, http.post("/api/admin/2fa/global", off).status());
  }

  @Test
  void withoutAUsableKeyEveryTwoFactorRouteIsUnavailable() {
    Env.override("TWO_FACTOR_ENCRYPTION_KEY", "");
    var initiate = http.post("/api/2fa/setup/initiate", Json.object());
    assertEquals(503, initiate.status());
    assertEquals("two-factor-error-not-configured", initiate.errorCode());
    assertEquals("TWO_FACTOR_ENCRYPTION_KEY is missing or invalid", initiate.errorMessage());

    var status = http.get("/api/2fa/status");
    assertEquals(200, status.status());
    assertFalse(status.body().get("isConfigured").asBoolean());
    assertFalse(status.body().get("isRequired").asBoolean());
  }
}
