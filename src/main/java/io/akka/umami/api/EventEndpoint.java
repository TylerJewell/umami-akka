package io.akka.umami.api;

import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.HttpResponses;
import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.http.AbstractHttpEndpoint;
import io.akka.umami.application.VisitEntity;
import io.akka.umami.domain.BotDetector;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * The write side — SPEC-001 R1, R2, R3. Mirrors {@code POST /api/send} for
 * {@code type: "event"} only (docs/scope.md): a pageview when {@code name} is absent, a
 * custom event when it is present.
 */
@HttpEndpoint
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
public class EventEndpoint extends AbstractHttpEndpoint {

  private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM").withZone(ZoneOffset.UTC);
  private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH").withZone(ZoneOffset.UTC);

  private final ComponentClient componentClient;

  public EventEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  public record Payload(String website, String url, String hostname, String name) {}

  public record SendRequest(String type, Payload payload) {}

  public record SendResult(String sessionId, String visitId) {}

  @Post("/api/send")
  public HttpResponse send(SendRequest body) {
    if (body.payload() == null || body.payload().website() == null || body.payload().website().isBlank()) {
      return HttpResponses.badRequest("payload.website must be provided");
    }
    if (!"event".equals(body.type())) {
      return HttpResponses.badRequest("only type=event is supported by this port (docs/scope.md)");
    }

    String userAgent = requestContext().requestHeader("User-Agent").map(h -> h.value()).orElse("");
    if (BotDetector.isBot(userAgent)) {
      // R3: accepted, nothing recorded.
      return HttpResponses.ok(new SendResult(null, null));
    }

    String ip = requestContext().requestHeader("X-Forwarded-For").map(h -> h.value()).orElse("unknown");
    String websiteId = body.payload().website();
    Instant now = Instant.now();

    String sessionId = deterministicId(websiteId + "|" + ip + "|" + userAgent + "|" + MONTH.format(now));
    String visitId = deterministicId(sessionId + "|" + HOUR.format(now));
    long createdAtMillis = now.toEpochMilli();

    String eventName = body.payload().name();
    if (eventName != null && !eventName.isBlank()) {
      componentClient
          .forEventSourcedEntity(visitId)
          .method(VisitEntity::recordCustomEvent)
          .invoke(new VisitEntity.RecordCustomEvent(websiteId, sessionId, eventName, createdAtMillis));
    } else {
      String urlPath = body.payload().url() == null ? "" : body.payload().url();
      componentClient
          .forEventSourcedEntity(visitId)
          .method(VisitEntity::recordPageView)
          .invoke(new VisitEntity.RecordPageView(websiteId, sessionId, urlPath, createdAtMillis));
    }

    return HttpResponses.ok(new SendResult(sessionId, visitId));
  }

  private static String deterministicId(String key) {
    return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
  }
}
