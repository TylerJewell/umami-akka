package io.akka.umami.api;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.TestKitSupport;
import java.time.Duration;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

/**
 * SPEC-001 R1, R3 through the HTTP layer, not only the entity — a query-param or header
 * mistake at the endpoint would pass every {@code ComponentClient}-based test and fail only
 * here (PIPELINE.md step d, the endpoint-level-integration-test rule).
 */
public class SendEventEndpointIntegrationTest extends TestKitSupport {

  private static final String FULL_BROWSER_UA =
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

  @Test
  public void aPageviewFromARealBrowserUaIsAccepted() {
    String websiteId = UUID.randomUUID().toString();
    var response = httpClient.POST("/api/send")
        .addHeader("User-Agent", FULL_BROWSER_UA)
        .withRequestBody(new EventEndpoint.SendRequest(
            "event", new EventEndpoint.Payload(websiteId, "/home", "example.com", null)))
        .responseBodyAs(EventEndpoint.SendResult.class)
        .invoke();

    assertThat(response.status().isSuccess()).isTrue();
    assertThat(response.body().sessionId()).isNotBlank();
    assertThat(response.body().visitId()).isNotBlank();

    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          var stats = httpClient.GET("/api/websites/" + websiteId + "/stats")
              .addQueryParameter("startAt", "0")
              .addQueryParameter("endAt", String.valueOf(Long.MAX_VALUE))
              .responseBodyAs(WebsiteStatsEndpoint.WebsiteStats.class)
              .invoke()
              .body();
          assertThat(stats.pageviews()).isEqualTo(1);
        });
  }

  @Test
  public void botUserAgentIsAcceptedButNotRecorded() {
    String websiteId = UUID.randomUUID().toString();
    var response = httpClient.POST("/api/send")
        .addHeader("User-Agent", "Mozilla/5.0")
        .withRequestBody(new EventEndpoint.SendRequest(
            "event", new EventEndpoint.Payload(websiteId, "/home", "example.com", null)))
        .responseBodyAs(EventEndpoint.SendResult.class)
        .invoke();

    assertThat(response.status().isSuccess()).isTrue();
    assertThat(response.body().sessionId()).isNull();

    var stats = httpClient.GET("/api/websites/" + websiteId + "/stats")
        .addQueryParameter("startAt", "0")
        .addQueryParameter("endAt", String.valueOf(Long.MAX_VALUE))
        .responseBodyAs(WebsiteStatsEndpoint.WebsiteStats.class)
        .invoke()
        .body();
    assertThat(stats.pageviews()).isEqualTo(0);
  }
}
