package io.akka.umami.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import akka.javasdk.testkit.TestKitSupport;
import io.akka.umami.lib.Json;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * SPEC R125 to R127: the two live screens are fed by a stream, and reached the way a page reaches
 * one.
 *
 * <p>A browser's event source sends no request headers, so the assertion that an ordinary call puts
 * in `Authorization` has nowhere to go but the query string. Nothing else in this rebuild checks
 * that: every other test calls with a header, and a stream route that only read headers answered
 * every real page 401 while every test passed.
 */
class StreamIntegrationTest extends TestKitSupport {

  private HttpClientSupport http;
  private HttpClient raw;

  @BeforeEach
  void signIn() {
    http = new HttpClientSupport("http://localhost:" + testKit.getPort());
    raw = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    Settle.until(() -> http.signIn("admin", "umami"), a -> a.status() == 200,
        "the first administrator");
  }

  private String createWebsite() {
    var body = Json.object();
    body.put("name", "stream");
    body.put("domain", "stream.test");
    var answer = http.post("/api/websites", body);
    assertEquals(200, answer.status());
    return answer.text("id");
  }

  /** Opens a stream and returns its status with the first payload it carried, if any. */
  private record First(int status, String payload) {}

  /** A token carries `+` and `/`, which a query string does not pass through unencoded. */
  private String encodedToken() {
    return URLEncoder.encode(http.token(), StandardCharsets.UTF_8);
  }

  private First firstElement(String path) {
    try {
      var request =
          HttpRequest.newBuilder(URI.create("http://localhost:" + testKit.getPort() + path))
              .header("Accept", "text/event-stream")
              .timeout(Duration.ofSeconds(20))
              .GET()
              .build();
      var answer = raw.send(request, HttpResponse.BodyHandlers.ofInputStream());
      if (answer.statusCode() != 200) {
        answer.body().close();
        return new First(answer.statusCode(), null);
      }
      try (var reader =
          new BufferedReader(new InputStreamReader(answer.body(), StandardCharsets.UTF_8))) {
        var deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        String line;
        while (System.nanoTime() < deadline && (line = reader.readLine()) != null) {
          if (line.startsWith("data:") && !line.substring(5).isBlank()) {
            return new First(200, line.substring(5).trim());
          }
        }
      }
      return new First(200, null);
    } catch (Exception failure) {
      throw new RuntimeException(failure);
    }
  }

  @Test
  void theActiveStreamAnswersATokenOnTheQueryString() {
    var websiteId = createWebsite();
    var first = firstElement(
        "/api/websites/" + websiteId + "/active/stream?token=" + encodedToken());
    assertEquals(200, first.status());
    assertNotNull(first.payload(), "the stream sent no payload");
    var payload = Json.readObject(first.payload());
    // The element is the answer itself. A wrapper around it is invisible to every test that
    // asks the entity, and fatal to the interface, which reads `data.visitors`.
    assertTrue(payload.has("visitors"), "first element was " + first.payload());
  }

  @Test
  void theRealtimeStreamAnswersATokenOnTheQueryString() {
    var websiteId = createWebsite();
    var first = firstElement("/api/realtime/" + websiteId + "/stream?token=" + encodedToken());
    assertEquals(200, first.status());
    assertNotNull(first.payload(), "the stream sent no payload");
    var payload = Json.readObject(first.payload());
    for (var field : new String[] {"countries", "urls", "referrers", "events", "series",
        "totals", "timestamp"}) {
      assertTrue(payload.has(field), field + " missing from " + first.payload());
    }
  }

  @Test
  void aStreamWithNoAssertionAtAllIsRefused() {
    var websiteId = createWebsite();
    assertEquals(401, firstElement("/api/websites/" + websiteId + "/active/stream").status());
    assertEquals(401, firstElement("/api/realtime/" + websiteId + "/stream").status());
  }

  @Test
  void aStreamWithAnUnusableTokenIsRefused() {
    var websiteId = createWebsite();
    assertEquals(401,
        firstElement("/api/websites/" + websiteId + "/active/stream?token=not-a-token").status());
  }

  @Test
  void anOrdinaryRouteDoesNotAcceptATokenOnTheQueryString() {
    var websiteId = createWebsite();
    var anonymous = new HttpClientSupport("http://localhost:" + testKit.getPort());
    var answer = anonymous.get("/api/websites/" + websiteId + "?token=" + encodedToken());
    assertEquals(401, answer.status(),
        "only a stream reads the query string; an ordinary route reading it would put the "
            + "token in every log the original keeps it out of");
  }
}
