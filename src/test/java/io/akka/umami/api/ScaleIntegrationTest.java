package io.akka.umami.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import akka.javasdk.testkit.TestKitSupport;
import io.akka.umami.lib.Json;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * SPEC R146: a window holding more than a thousand facts still answers.
 *
 * <p>A view query that projects its rows into one response is refused past a thousand of them,
 * and every analytical answer this service gives is a walk over a window of one website's facts.
 * So a website with a thousand events in the window did not answer slowly — every question about
 * it failed outright with a 500, and every test passed, because no test had ever put more than a
 * few dozen events into one window.
 *
 * <p>This is deliberately the slowest test in the project. Its subject is a limit, and a limit
 * cannot be checked below it.
 */
class ScaleIntegrationTest extends TestKitSupport {

  private static final long BASE = 1756000000L;
  private static final int PAGE_VIEWS = 1200;
  private static final int PER_BATCH = 400;
  private static final String DESKTOP =
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
          + "Chrome/120.0.0.0 Safari/537.36";

  private HttpClientSupport http;

  @BeforeEach
  void signIn() {
    http = new HttpClientSupport("http://localhost:" + testKit.getPort());
    Settle.untilStarted(() -> http.signIn("admin", "umami"), a -> a.status() == 200,
        "the first administrator");
  }

  private String window() {
    return "startAt=" + (BASE - 7200) * 1000 + "&endAt=" + (BASE + 7200) * 1000 + "&timezone=UTC";
  }

  @Test
  void aWindowOfMoreThanAThousandFactsAnswersRatherThanFailing() {
    var body = Json.object();
    body.put("name", "scale");
    body.put("domain", "scale.test");
    var created = http.post("/api/websites", body);
    assertEquals(200, created.status());
    var websiteId = created.text("id");

    // Every event gets its own address, so each is its own session too — which is what puts
    // the row count well past the ceiling rather than only at it.
    int sent = 0;
    while (sent < PAGE_VIEWS) {
      var batch = Json.array();
      for (int i = 0; i < PER_BATCH && sent < PAGE_VIEWS; i++, sent++) {
        var payload = Json.object();
        payload.put("website", websiteId);
        payload.put("url", "/page-" + (sent % 40));
        payload.put("hostname", "scale.test");
        payload.put("userAgent", DESKTOP);
        payload.put("ip", "203.0." + (sent / 250) + "." + (sent % 250 + 1));
        payload.put("timestamp", BASE + sent);
        var element = Json.object();
        element.put("type", "event");
        element.set("payload", payload);
        batch.add(element);
      }
      var answer = http.post("/api/batch", batch);
      assertEquals(200, answer.status(), answer.body() == null ? "" : answer.body().toString());
    }

    var stats = Settle.until(
        () -> http.get("/api/websites/" + websiteId + "/stats?" + window()),
        a -> a.status() == 200 && a.body() != null
            && a.body().get("pageviews").asLong() == PAGE_VIEWS,
        PAGE_VIEWS + " page views");
    assertEquals(PAGE_VIEWS, stats.body().get("pageviews").asLong());
    assertEquals(PAGE_VIEWS, stats.body().get("visitors").asLong());

    // Every other shape of read over the same window, because they go through different
    // queries and one of them failing is the whole fault this is about.
    for (var path : List.of(
        "/pageviews?" + window() + "&unit=hour",
        "/metrics?" + window() + "&type=path",
        "/metrics/expanded?" + window() + "&type=path",
        "/sessions?" + window(),
        "/sessions/stats?" + window(),
        "/events?" + window(),
        "/events/stats?" + window(),
        "/daterange")) {
      var answer = http.get("/api/websites/" + websiteId + path);
      assertEquals(200, answer.status(), path + " answered " + answer.status());
    }

    var paths = http.get("/api/websites/" + websiteId + "/metrics?" + window() + "&type=path");
    assertEquals(200, paths.status());
    assertEquals(40, paths.body().size(), "every distinct page came back");
    long counted = 0;
    for (var row : paths.body()) {
      counted += row.get("y").asLong();
    }
    assertEquals(PAGE_VIEWS, counted, "and every view was counted once");
  }

  @Test
  void aListOfMoreThanAThousandRecordsAnswersRatherThanFailing() {
    // The same ceiling applies to the administrative side, where a thousand websites is an
    // ordinary deployment rather than a large one.
    for (int i = 0; i < 1100; i++) {
      var body = Json.object();
      body.put("name", "bulk " + i);
      body.put("domain", "bulk" + i + ".test");
      assertEquals(200, http.post("/api/websites", body).status());
    }
    var listed = Settle.until(
        () -> http.get("/api/websites?pageSize=1&page=1"),
        a -> a.status() == 200 && a.body().get("count").asLong() >= 1100,
        "all 1,100 websites");
    assertTrue(listed.body().get("count").asLong() >= 1100,
        "counted " + listed.body().get("count"));
  }
}
