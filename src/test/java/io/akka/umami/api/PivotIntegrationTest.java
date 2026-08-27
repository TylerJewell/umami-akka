package io.akka.umami.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import akka.javasdk.testkit.TestKitSupport;
import com.fasterxml.jackson.databind.JsonNode;
import io.akka.umami.lib.Json;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * SPEC R132 to R144: the thirteen answers about one property at a time.
 *
 * <p>Driven through the HTTP layer rather than through the component client, because most of what
 * these rules are about is read off the query string — which property, which metric, which type of
 * value — and a call that hands a filter object straight to the analytics never reads it.
 */
class PivotIntegrationTest extends TestKitSupport {

  /** An instant with room either side of it, so a window can be asked for that contains it. */
  private static final long BASE = 1756000000000L;
  private static final String START = String.valueOf(BASE - 3_600_000L);
  private static final String END = String.valueOf(BASE + 3_600_000L);
  private static final String DESKTOP =
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
          + "Chrome/120.0.0.0 Safari/537.36";

  private HttpClientSupport http;

  @BeforeEach
  void signIn() {
    http = new HttpClientSupport("http://localhost:" + testKit.getPort());
    Settle.until(() -> http.signIn("admin", "umami"), a -> a.status() == 200,
        "the first administrator");
  }

  private String createWebsite(String name) {
    var body = Json.object();
    body.put("name", name);
    body.put("domain", "pivot.test");
    var answer = http.post("/api/websites", body);
    assertEquals(200, answer.status());
    return answer.text("id");
  }

  /** One named event carrying properties, at a named instant so nothing depends on the clock. */
  private void sendEvent(String websiteId, String name, long at, String ip,
      java.util.function.Consumer<com.fasterxml.jackson.databind.node.ObjectNode> data) {
    var payload = Json.object();
    payload.put("website", websiteId);
    payload.put("url", "/checkout");
    payload.put("hostname", "pivot.test");
    payload.put("userAgent", DESKTOP);
    payload.put("ip", ip);
    payload.put("timestamp", at / 1000);
    payload.put("name", name);
    var properties = Json.object();
    data.accept(properties);
    payload.set("data", properties);
    var body = Json.object();
    body.put("type", "event");
    body.set("payload", payload);
    var answer = http.post("/api/send", body);
    assertEquals(200, answer.status());
  }

  /** A plain view, which is what puts the session into the window a session query looks at. */
  private void sendView(String websiteId, long at, String ip) {
    var payload = Json.object();
    payload.put("website", websiteId);
    payload.put("url", "/");
    payload.put("hostname", "pivot.test");
    payload.put("userAgent", DESKTOP);
    payload.put("ip", ip);
    payload.put("timestamp", at / 1000);
    var body = Json.object();
    body.put("type", "event");
    body.set("payload", payload);
    assertEquals(200, http.post("/api/send", body).status());
  }

  /**
   * A property written against the session rather than against one event.
   *
   * <p>A view goes with it, because a session query selects its sessions out of the window's
   * events: a session that only ever identified itself is in no window at all.
   */
  private void identify(String websiteId, String distinctId, long at, String ip,
      java.util.function.Consumer<com.fasterxml.jackson.databind.node.ObjectNode> data) {
    sendView(websiteId, at, ip);
    var payload = Json.object();
    payload.put("website", websiteId);
    payload.put("hostname", "pivot.test");
    payload.put("userAgent", DESKTOP);
    payload.put("ip", ip);
    payload.put("timestamp", at / 1000);
    payload.put("id", distinctId);
    var properties = Json.object();
    data.accept(properties);
    payload.set("data", properties);
    var body = Json.object();
    body.put("type", "identify");
    body.set("payload", payload);
    assertEquals(200, http.post("/api/send", body).status());
  }

  private HttpClientSupport.Answer ask(String path) {
    return http.get(path);
  }

  private static String window(String websiteId, String suffix) {
    return "/api/websites/" + websiteId + "?startAt=" + START + "&endAt=" + END + suffix;
  }

  private String query(String websiteId, String tail) {
    return "/api/websites/" + websiteId + tail
        + (tail.contains("?") ? "&" : "?") + "startAt=" + START + "&endAt=" + END;
  }

  // ------------------------------------------------------------------ event properties

  @Test
  void eventPivotReturnsEveryPropertyOfTheNamedEventNewestFirst() {
    var websiteId = createWebsite("event pivot");
    sendEvent(websiteId, "purchase", BASE, "203.0.113.1", data -> {
      data.put("plan", "pro");
      data.put("amount", 12);
    });
    sendEvent(websiteId, "purchase", BASE + 60_000L, "203.0.113.2", data -> {
      data.put("plan", "free");
      data.put("amount", 3);
    });
    sendEvent(websiteId, "signup", BASE + 120_000L, "203.0.113.3", data -> data.put("plan", "pro"));

    var answer = Settle.until(
        () -> ask(query(websiteId, "/event-data-pivot?eventName=purchase")),
        a -> a.status() == 200 && a.body().get("data").size() == 2,
        "both purchases in the pivot");
    var rows = answer.body().get("data");
    assertEquals(2, rows.size());
    assertEquals(2, answer.body().get("count").asInt());
    // Newest first.
    assertEquals("free", valueOf(rows.get(0), "plan"));
    assertEquals("pro", valueOf(rows.get(1), "plan"));
    // The keys come back sorted, and every property of the event is present whatever was asked.
    assertEquals(List.of("amount", "plan"), keysOf(rows.get(0)));
    assertTrue(rows.get(0).has("eventId"));
    assertTrue(rows.get(0).has("sessionId"));
    assertEquals("/checkout", rows.get(0).get("urlPath").asText());
  }

  @Test
  void eventPivotRefusesWithoutAnEventName() {
    var websiteId = createWebsite("event pivot refusal");
    var answer = ask(query(websiteId, "/event-data-pivot"));
    assertEquals(400, answer.status());
  }

  @Test
  void eventNumericStatsSummarisesANumberProperty() {
    var websiteId = createWebsite("event numeric stats");
    sendEvent(websiteId, "purchase", BASE, "203.0.113.11", data -> data.put("amount", 10));
    sendEvent(websiteId, "purchase", BASE + 1000L, "203.0.113.12", data -> data.put("amount", 20));
    sendEvent(websiteId, "purchase", BASE + 2000L, "203.0.113.13", data -> data.put("amount", 30));

    var answer = Settle.until(
        () -> ask(query(websiteId,
            "/event-data-pivot/numeric-stats?eventName=purchase&propertyName=amount")),
        a -> a.status() == 200 && !"0".equals(a.body().get("total").asText()),
        "three amounts summarised");
    var body = answer.body();
    assertEquals("60", body.get("total").asText());
    assertEquals("20", body.get("average").asText());
    assertEquals("20", body.get("median").asText());
    assertEquals("30", body.get("max").asText());
    assertEquals("10", body.get("min").asText());
  }

  @Test
  void eventNumericStatsAnswersZeroWhenNothingMatched() {
    var websiteId = createWebsite("event numeric stats empty");
    var answer = ask(query(websiteId,
        "/event-data-pivot/numeric-stats?eventName=nothing&propertyName=amount"));
    assertEquals(200, answer.status());
    assertEquals("0", answer.body().get("total").asText());
    assertEquals("0", answer.body().get("median").asText());
  }

  @Test
  void eventNumericMedianSitsBetweenTheTwoMiddleValues() {
    var websiteId = createWebsite("event median");
    sendEvent(websiteId, "purchase", BASE, "203.0.113.21", data -> data.put("amount", 10));
    sendEvent(websiteId, "purchase", BASE + 1000L, "203.0.113.22", data -> data.put("amount", 30));

    var answer = Settle.until(
        () -> ask(query(websiteId,
            "/event-data-pivot/numeric-stats?eventName=purchase&propertyName=amount")),
        a -> a.status() == 200 && !"0".equals(a.body().get("total").asText()),
        "two amounts summarised");
    assertEquals("20", answer.body().get("median").asText());
  }

  @Test
  void eventNumericSeriesSumsAveragesAndCountsPerBucket() {
    var websiteId = createWebsite("event numeric series");
    sendEvent(websiteId, "purchase", BASE, "203.0.113.31", data -> data.put("amount", 4));
    sendEvent(websiteId, "purchase", BASE + 1000L, "203.0.113.32", data -> data.put("amount", 6));

    var sum = Settle.until(
        () -> ask(query(websiteId,
            "/event-data-pivot/numeric-series?eventName=purchase&propertyName=amount"
                + "&unit=hour&timezone=UTC")),
        a -> a.status() == 200 && a.body().size() == 1,
        "one bucket");
    assertEquals("10", sum.body().get(0).get("y").asText());
    assertTrue(sum.body().get(0).get("t").asText().endsWith("Z"));

    var average = ask(query(websiteId,
        "/event-data-pivot/numeric-series?eventName=purchase&propertyName=amount"
            + "&metric=avg&unit=hour&timezone=UTC"));
    assertEquals("5", average.body().get(0).get("y").asText());

    var count = ask(query(websiteId,
        "/event-data-pivot/numeric-series?eventName=purchase&propertyName=amount"
            + "&metric=count&unit=hour&timezone=UTC"));
    // A count is a number where a sum is a decimal string, on the original as well.
    assertEquals(2, count.body().get(0).get("y").asInt());
    assertTrue(count.body().get(0).get("y").isNumber());
  }

  @Test
  void eventNumericSeriesRefusesAnUnknownMetric() {
    var websiteId = createWebsite("event metric refusal");
    var answer = ask(query(websiteId,
        "/event-data-pivot/numeric-series?eventName=purchase&propertyName=amount&metric=mode"));
    assertEquals(400, answer.status());
  }

  @Test
  void eventPropertySeriesCountsRowsPerValuePerBucket() {
    var websiteId = createWebsite("event property series");
    sendEvent(websiteId, "purchase", BASE, "203.0.113.41", data -> data.put("plan", "pro"));
    sendEvent(websiteId, "purchase", BASE + 1000L, "203.0.113.42", data -> data.put("plan", "pro"));
    sendEvent(websiteId, "purchase", BASE + 2000L, "203.0.113.43", data -> data.put("plan", "free"));

    var answer = Settle.until(
        () -> ask(query(websiteId,
            "/event-data-pivot/property-series?eventName=purchase&propertyName=plan"
                + "&unit=hour&timezone=UTC")),
        a -> a.status() == 200 && a.body().size() == 2,
        "two values in one bucket");
    var counts = new java.util.HashMap<String, Integer>();
    answer.body().forEach(point -> counts.put(point.get("x").asText(), point.get("y").asInt()));
    assertEquals(2, counts.get("pro"));
    assertEquals(1, counts.get("free"));
  }

  @Test
  void eventArraySeriesCountsEveryElementOfEveryArray() {
    var websiteId = createWebsite("event array series");
    sendEvent(websiteId, "purchase", BASE, "203.0.113.51",
        data -> data.set("tags", Json.array().add("a").add("b")));
    sendEvent(websiteId, "purchase", BASE + 1000L, "203.0.113.52",
        data -> data.set("tags", Json.array().add("b")));

    var answer = Settle.until(
        () -> ask(query(websiteId,
            "/event-data-pivot/array-series?eventName=purchase&propertyName=tags"
                + "&unit=hour&timezone=UTC")),
        a -> a.status() == 200 && a.body().size() == 2,
        "both elements");
    var counts = new java.util.HashMap<String, Integer>();
    answer.body().forEach(point -> counts.put(point.get("x").asText(), point.get("y").asInt()));
    assertEquals(1, counts.get("a"));
    assertEquals(2, counts.get("b"));
  }

  @Test
  void eventDateSeriesGroupsByTheSecondTheValueNames() {
    var websiteId = createWebsite("event date series");
    sendEvent(websiteId, "purchase", BASE, "203.0.113.61",
        data -> data.put("renews", "2026-01-01T00:00:00Z"));
    sendEvent(websiteId, "purchase", BASE + 1000L, "203.0.113.62",
        data -> data.put("renews", "2026-01-01T00:00:00Z"));

    var answer = Settle.until(
        () -> ask(query(websiteId,
            "/event-data-pivot/date-series?eventName=purchase&propertyName=renews"
                + "&timezone=UTC")),
        a -> a.status() == 200 && a.body().size() == 1,
        "one second");
    assertEquals(2, answer.body().get(0).get("y").asInt());
    assertEquals("2026-01-01T00:00:00Z", answer.body().get(0).get("t").asText());
  }

  @Test
  void aPropertyFilterNarrowsThePivot() {
    var websiteId = createWebsite("event pivot filter");
    sendEvent(websiteId, "purchase", BASE, "203.0.113.71", data -> data.put("plan", "pro"));
    sendEvent(websiteId, "purchase", BASE + 1000L, "203.0.113.72", data -> data.put("plan", "free"));

    Settle.until(
        () -> ask(query(websiteId, "/event-data-pivot?eventName=purchase")),
        a -> a.status() == 200 && a.body().get("data").size() == 2,
        "both purchases");
    var answer = ask(query(websiteId, "/event-data-pivot?eventName=purchase&pf_plan=eq.pro"));
    assertEquals(200, answer.status());
    assertEquals(1, answer.body().get("data").size());
    assertEquals("pro", valueOf(answer.body().get("data").get(0), "plan"));
  }

  // ------------------------------------------------------------------ session properties

  @Test
  void sessionPivotReturnsTheLatestValueOfEveryPropertyTheSessionHolds() {
    var websiteId = createWebsite("session pivot");
    identify(websiteId, "person-1", BASE, "203.0.113.81", data -> {
      data.put("plan", "free");
      data.put("seats", 2);
    });
    identify(websiteId, "person-1", BASE + 60_000L, "203.0.113.81", data -> data.put("plan", "pro"));

    var answer = Settle.until(
        () -> ask(query(websiteId, "/session-data-pivot?propertyName=plan")),
        a -> a.status() == 200 && a.body().get("data").size() == 1
            && "pro".equals(valueOf(a.body().get("data").get(0), "plan")),
        "the later write winning");
    var row = answer.body().get("data").get(0);
    assertEquals(List.of("plan", "seats"), keysOf(row));
    assertEquals("pro", valueOf(row, "plan"));
    assertEquals("person-1", row.get("distinctId").asText());
  }

  @Test
  void sessionPivotShowsOnlySessionsHoldingTheNamedProperty() {
    var websiteId = createWebsite("session pivot narrowing");
    identify(websiteId, "person-a", BASE, "203.0.113.91", data -> data.put("plan", "pro"));
    identify(websiteId, "person-b", BASE, "203.0.113.92", data -> data.put("team", "blue"));

    var answer = Settle.until(
        () -> ask(query(websiteId, "/session-data-pivot?propertyName=plan")),
        a -> a.status() == 200 && a.body().get("data").size() == 1,
        "only the session holding the property");
    assertEquals(1, answer.body().get("data").size());
  }

  @Test
  void sessionNumericStatsSummarisesOneValuePerSession() {
    var websiteId = createWebsite("session numeric stats");
    identify(websiteId, "person-c", BASE, "203.0.113.101", data -> data.put("seats", 2));
    identify(websiteId, "person-d", BASE, "203.0.113.102", data -> data.put("seats", 4));

    // Waiting on the pivot rather than on the figure: a wait whose condition is the answer
    // being asserted cannot fail, it can only time out.
    Settle.until(
        () -> ask(query(websiteId, "/session-data-pivot?propertyName=seats")),
        a -> a.status() == 200 && a.body().get("data").size() == 2,
        "both sessions");
    var answer = ask(query(websiteId, "/session-data/numeric-stats?propertyName=seats"));
    assertEquals(200, answer.status());
    assertEquals("6", answer.body().get("total").asText());
    assertEquals("3", answer.body().get("average").asText());
    assertEquals("4", answer.body().get("max").asText());
    assertEquals("2", answer.body().get("min").asText());
  }

  @Test
  void sessionNumericSeriesCountsDistinctSessionsRatherThanRows() {
    var websiteId = createWebsite("session numeric series");
    identify(websiteId, "person-e", BASE, "203.0.113.111", data -> data.put("seats", 5));
    identify(websiteId, "person-f", BASE, "203.0.113.112", data -> data.put("seats", 7));

    var count = Settle.until(
        () -> ask(query(websiteId,
            "/session-data/numeric-series?propertyName=seats&metric=count&unit=hour&timezone=UTC")),
        a -> a.status() == 200 && a.body().size() == 1,
        "one bucket");
    assertEquals(2, count.body().get(0).get("y").asInt());

    var sum = ask(query(websiteId,
        "/session-data/numeric-series?propertyName=seats&unit=hour&timezone=UTC"));
    assertEquals("12", sum.body().get(0).get("y").asText());
  }

  @Test
  void sessionPropertySeriesCountsDistinctSessionsPerValue() {
    var websiteId = createWebsite("session property series");
    identify(websiteId, "person-g", BASE, "203.0.113.121", data -> data.put("plan", "pro"));
    identify(websiteId, "person-h", BASE, "203.0.113.122", data -> data.put("plan", "pro"));
    identify(websiteId, "person-i", BASE, "203.0.113.123", data -> data.put("plan", "free"));

    var answer = Settle.until(
        () -> ask(query(websiteId,
            "/session-data/property-series?propertyName=plan&unit=hour&timezone=UTC")),
        a -> a.status() == 200 && a.body().size() == 2,
        "two values");
    var counts = new java.util.HashMap<String, Integer>();
    answer.body().forEach(point -> counts.put(point.get("x").asText(), point.get("y").asInt()));
    assertEquals(2, counts.get("pro"));
    assertEquals(1, counts.get("free"));
  }

  @Test
  void sessionActivityStatsRanksValuesByHowMuchTheirSessionsDid() {
    var websiteId = createWebsite("session activity");
    identify(websiteId, "person-j", BASE, "203.0.113.131", data -> data.put("plan", "pro"));
    identify(websiteId, "person-k", BASE, "203.0.113.132", data -> data.put("plan", "free"));
    // The pro session does more, so it ranks first however the two are written.
    sendEvent(websiteId, "click", BASE + 1000L, "203.0.113.131", data -> data.put("where", "top"));
    sendEvent(websiteId, "click", BASE + 2000L, "203.0.113.131", data -> data.put("where", "top"));

    var answer = Settle.until(
        () -> ask(query(websiteId, "/session-data/stats?propertyName=plan")),
        a -> a.status() == 200 && a.body().size() == 2,
        "both plans");
    assertEquals("pro", answer.body().get(0).get("label").asText());
    assertTrue(answer.body().get(0).get("activity").asInt()
        > answer.body().get(1).get("activity").asInt());
    assertTrue(answer.body().get(0).get("sessions").asInt() >= 1);
  }

  @Test
  void sessionActivityStatsLeavesOutAnEmptyLabel() {
    var websiteId = createWebsite("session activity empty label");
    identify(websiteId, "person-l", BASE, "203.0.113.141", data -> data.put("plan", ""));

    var answer = ask(query(websiteId, "/session-data/stats?propertyName=plan"));
    assertEquals(200, answer.status());
    answer.body().forEach(row -> assertTrue(!row.get("label").asText().isEmpty()));
  }

  @Test
  void sessionRoutesRefuseWithoutAPropertyName() {
    var websiteId = createWebsite("session refusal");
    for (var path : List.of("/session-data-pivot", "/session-data/array-series",
        "/session-data/date-series", "/session-data/numeric-series",
        "/session-data/numeric-stats", "/session-data/property-series",
        "/session-data/stats")) {
      assertEquals(400, ask(query(websiteId, path)).status(), path);
    }
  }

  @Test
  void everyPivotRouteRefusesAnUnauthenticatedCaller() {
    var websiteId = createWebsite("pivot access");
    var anonymous = new HttpClientSupport("http://localhost:" + testKit.getPort());
    for (var path : List.of(
        "/event-data-pivot?eventName=purchase",
        "/event-data-pivot/array-series?eventName=p&propertyName=t",
        "/event-data-pivot/date-series?eventName=p&propertyName=t",
        "/event-data-pivot/numeric-series?eventName=p&propertyName=t",
        "/event-data-pivot/numeric-stats?eventName=p&propertyName=t",
        "/event-data-pivot/property-series?eventName=p&propertyName=t",
        "/session-data-pivot?propertyName=t",
        "/session-data/array-series?propertyName=t",
        "/session-data/date-series?propertyName=t",
        "/session-data/numeric-series?propertyName=t",
        "/session-data/numeric-stats?propertyName=t",
        "/session-data/property-series?propertyName=t",
        "/session-data/stats?propertyName=t")) {
      var answer = anonymous.get(query(websiteId, path));
      assertEquals(401, answer.status(), path);
    }
  }

  // ------------------------------------------------------------------ reading a pivot row

  private static List<String> keysOf(JsonNode row) {
    var out = new ArrayList<String>();
    row.get("propertyKeys").forEach(key -> out.add(key.asText()));
    return out;
  }

  private static String valueOf(JsonNode row, String key) {
    var keys = row.get("propertyKeys");
    for (int i = 0; i < keys.size(); i++) {
      if (keys.get(i).asText().equals(key)) {
        return row.get("propertyValues").get(i).asText();
      }
    }
    return null;
  }
}
