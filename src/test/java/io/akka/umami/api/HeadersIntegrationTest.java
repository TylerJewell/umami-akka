package io.akka.umami.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import akka.javasdk.annotations.http.Delete;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.Patch;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.annotations.http.Put;
import akka.javasdk.testkit.TestKitSupport;
import io.akka.umami.lib.Env;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * SPEC R147: the seven headers every answer from a route under {@code /api} carries.
 *
 * <p>The rule is about every route, so the check enumerates them from the annotations rather than
 * sampling: a route added later without the headers fails here without anybody remembering to add
 * a case. Nothing is signed in, so every request is refused before it can reach any state — the
 * rule holds whatever the status, which is what makes an unauthenticated sweep a fair reading of
 * it.
 */
class HeadersIntegrationTest extends TestKitSupport {

  private static final List<Class<?>> ENDPOINTS =
      List.of(AccountEndpoint.class, AnalyticsEndpoint.class, BoardEndpoint.class,
          CollectEndpoint.class, LinkEndpoint.class, RealtimeEndpoint.class, ReportEndpoint.class,
          SegmentEndpoint.class, ShareEndpoint.class, TeamEndpoint.class, TwoFactorEndpoint.class,
          WebsiteEndpoint.class);

  private static final String POLICY =
      "default-src 'self'; img-src 'self' https: data: blob:; "
          + "script-src 'self' 'unsafe-eval' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; "
          + "connect-src 'self' https:; frame-src 'self' http: https:; frame-ancestors 'self';";

  private HttpClientSupport http;

  @BeforeEach
  void caller() {
    http = new HttpClientSupport("http://localhost:" + testKit.getPort());
  }

  @AfterEach
  void clearSettings() {
    Env.clearOverrides();
  }

  /** Every route the service declares, as a method and a path with its parameters filled in. */
  private static Map<String, String> declaredRoutes() {
    var routes = new LinkedHashMap<String, String>();
    for (var type : ENDPOINTS) {
      for (var method : type.getDeclaredMethods()) {
        for (Annotation annotation : method.getAnnotations()) {
          String verb = null;
          String path = null;
          if (annotation instanceof Get get) {
            verb = "GET";
            path = get.value();
          } else if (annotation instanceof Post post) {
            verb = "POST";
            path = post.value();
          } else if (annotation instanceof Put put) {
            verb = "PUT";
            path = put.value();
          } else if (annotation instanceof Delete delete) {
            verb = "DELETE";
            path = delete.value();
          } else if (annotation instanceof Patch patch) {
            verb = "PATCH";
            path = patch.value();
          }
          if (verb != null) {
            routes.put(verb + " " + path, verb + " " + fill(path));
          }
        }
      }
    }
    return routes;
  }

  /** A path parameter is given an identifier that resolves to nothing, so nothing is changed. */
  private static String fill(String path) {
    return path.replaceAll("\\{[^}]+}", "00000000-0000-4000-8000-000000000000");
  }

  private void assertTheSevenHeaders(String where, Map<String, String> headers) {
    assertEquals("*", headers.get("access-control-allow-origin"), where);
    assertEquals("*", headers.get("access-control-allow-headers"), where);
    assertEquals("GET, DELETE, POST, PUT", headers.get("access-control-allow-methods"), where);
    assertEquals("86400", headers.get("access-control-max-age"), where);
    assertEquals("no-cache", headers.get("cache-control"), where);
    assertEquals("on", headers.get("x-dns-prefetch-control"), where);
    assertEquals(POLICY, headers.get("content-security-policy"), where);
  }

  @Test
  void everyDeclaredApiRouteCarriesTheSevenHeaders() {
    var routes = declaredRoutes();
    assertTrue(routes.size() >= 129, "expected the whole route surface, saw " + routes.size());
    var missing = new ArrayList<String>();
    var underApi = 0;
    for (var route : routes.entrySet()) {
      if (!route.getKey().contains(" /api/")) {
        continue;
      }
      underApi++;
      var request = route.getValue();
      var space = request.indexOf(' ');
      var answer =
          http.send(request.substring(0, space), request.substring(space + 1), null, Map.of());
      try {
        assertTheSevenHeaders(route.getKey(), answer.headers());
      } catch (AssertionError failure) {
        missing.add(route.getKey() + ": " + failure.getMessage());
      }
    }
    assertEquals(List.of(), missing, missing.size() + " route(s) answered without them");
    assertTrue(underApi >= 127, "expected the whole API surface, saw " + underApi);
  }

  /**
   * The two collection redirectors are not under {@code /api}, so umami gives them the two
   * headers every address gets and none of the access-control set. A rule that named "every route"
   * would have been satisfied by giving them all seven, which is a different answer from the
   * original's.
   */
  @Test
  void theTwoRedirectorsCarryOnlyWhatEveryAddressCarries() {
    for (var path : List.of("/p/no-such-pixel", "/q/no-such-link")) {
      var headers = http.get(path).headers();
      assertEquals("on", headers.get("x-dns-prefetch-control"), path);
      assertEquals(POLICY, headers.get("content-security-policy"), path);
      assertEquals(null, headers.get("access-control-allow-headers"), path);
      assertEquals(null, headers.get("access-control-max-age"), path);
    }
  }

  /**
   * The one address under {@code /api} where the rule does not hold, pinned so that it stays a
   * declared difference rather than becoming an undetected one. In the original the header rules
   * are matched on the address, so a path no route claims is answered 404 with six of the seven;
   * here nothing this service wrote is in that path at all, and the runtime answers first.
   */
  @Test
  void anAddressNoRouteDeclaresIsAnsweredBeforeThisServiceSeesIt() {
    var answer = http.get("/api/there-is-no-such-route");
    assertEquals(404, answer.status());
    assertEquals(null, answer.headers().get("access-control-max-age"));
    assertEquals(null, answer.headers().get("content-security-policy"));
  }

  @Test
  void theStreamRoutesCarryThemToo() {
    var id = "00000000-0000-4000-8000-000000000000";
    for (var path : List.of("/api/realtime/" + id + "/stream", "/api/websites/" + id
        + "/active/stream")) {
      var answer = http.get(path);
      assertEquals(401, answer.status(), path);
      assertTheSevenHeaders("GET " + path, answer.headers());
    }
  }

  @Test
  void corsMaxAgeReplacesTheDefaultLifetimeOfAPreflight() {
    Env.override("CORS_MAX_AGE", "3600");
    assertEquals("3600", http.get("/api/heartbeat").headers().get("access-control-max-age"));
  }

  @Test
  void forceSslAddsStrictTransportSecurityAndNothingElseDoes() {
    assertEquals(null, http.get("/api/heartbeat").headers().get("strict-transport-security"));
    Env.override("FORCE_SSL", "1");
    assertEquals("max-age=63072000; includeSubDomains; preload",
        http.get("/api/heartbeat").headers().get("strict-transport-security"));
  }

  @Test
  void apiUrlAddsItsOriginToConnectSourceAndDropsItsPath() {
    Env.override("API_URL", "https://api.example:8443/v1");
    assertTrue(http.get("/api/heartbeat").headers().get("content-security-policy")
        .contains("connect-src 'self' https: https://api.example:8443;"));
  }

  @Test
  void anApiUrlThatIsNotAnAddressAddsNothing() {
    Env.override("API_URL", "/relative/path");
    assertTrue(http.get("/api/heartbeat").headers().get("content-security-policy")
        .contains("connect-src 'self' https:;"));
  }

  @Test
  void allowedFrameUrlsAreAppendedToFrameAncestors() {
    Env.override("ALLOWED_FRAME_URLS", "https://frames.example https://other.example");
    assertTrue(http.get("/api/heartbeat").headers().get("content-security-policy")
        .contains("frame-ancestors 'self' https://frames.example https://other.example;"));
  }

  /**
   * Exactly one, not two. The runtime writes this header itself while the service is running in
   * development, and a browser reads two copies of it as the single value {@code *, *}, which
   * matches no origin and refuses the call. The check reads every value rather than the first
   * because a map of name to first value — which is what {@link HttpClientSupport} exposes, and
   * what every other check here uses — cannot see a duplicate at all.
   */
  @Test
  void theOriginIsAllowedOnceRatherThanTwice() throws Exception {
    var answer =
        java.net.http.HttpClient.newHttpClient()
            .send(
                java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create(
                            "http://localhost:" + testKit.getPort() + "/api/heartbeat"))
                    .build(),
                java.net.http.HttpResponse.BodyHandlers.discarding());
    assertEquals(List.of("*"), answer.headers().allValues("access-control-allow-origin"));
  }
}
