package io.akka.umami.api;

import akka.http.javadsl.model.HttpEntity;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.model.headers.RawHeader;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.akka.umami.application.Collect;
import io.akka.umami.domain.Recordings;
import io.akka.umami.lib.Constants;
import io.akka.umami.lib.Crypto;
import io.akka.umami.lib.Detect;
import io.akka.umami.lib.Env;
import io.akka.umami.lib.Json;
import io.akka.umami.lib.Jwt;
import io.akka.umami.lib.Responses;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Everything a visitor's browser talks to, plus the two routes that answer before anybody has
 * signed in.
 *
 * <p>None of it is authenticated. What stops a stranger writing into somebody else's site is that
 * the site's identifier has to exist, which is the same protection the original has.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint
public class CollectEndpoint extends Api {

  private static final byte[] TRANSPARENT_GIF =
      Base64.getDecoder().decode("R0lGODlhAQABAIAAAP///wAAACH5BAEAAAAALAAAAAABAAEAAAICRAEAOw==");

  private static final int MAXIMUM_RECORD_BYTES = 1_000_000;

  public CollectEndpoint(ComponentClient client) {
    super(client);
  }

  // ------------------------------------------------------------------ collection

  /** The envelope: which kind of payload this is, and that there is one. */
  private static Schema envelopeSchema() {
    var schema = Schema.object();
    schema.string("type").options(List.of("event", "identify", "performance")).required();
    schema.objectField("payload").required();
    return schema;
  }

  /** The payload itself, including the rule that exactly one source is named. */
  static Schema payloadSchema() {
    var payload =
        Schema.object()
            .refine(
                node -> count(node, "website") + count(node, "link") + count(node, "pixel") == 1,
                "website",
                "Exactly one of website, link, or pixel must be provided");
    payload.uuid("website");
    payload.uuid("link");
    payload.uuid("pixel");
    payload.any("data");
    payload.string("hostname");
    payload.string("language");
    payload.string("referrer");
    payload.string("screen");
    payload.string("title");
    payload.string("url");
    safeString(payload, "name");
    safeString(payload, "tag");
    payload.string("ip");
    payload.string("userAgent");
    payload.integer("timestamp");
    payload.string("id");
    payload.string("browser");
    payload.string("os");
    payload.string("device");
    payload.number("lcp").range(0, 60000);
    payload.number("inp").range(0, 60000);
    payload.number("cls").range(0, 100);
    payload.number("fcp").range(0, 60000);
    payload.number("ttfb").range(0, 60000);
    return payload;
  }

  /**
   * A value beginning with an equals sign, a plus, a minus, an at sign, a tab or a carriage return
   * is refused on the way in, because a spreadsheet opening the export would read it as a formula.
   */
  private static void safeString(Schema schema, String name) {
    schema.string(name).pattern("^(?![=+\\-@\\t\\r]).*$",
        "Value must not start with =, +, -, @, tab, or carriage return");
  }

  private static int count(ObjectNode node, String field) {
    var value = node.get(field);
    return value == null || value.isNull() ? 0 : 1;
  }

  @Post("/api/send")
  public HttpResponse send(HttpEntity.Strict requestBody) {
    return answer(() -> {
      var answer = collectOneBody(body(requestBody));
      return Responses.json(StatusCodes.get(answer.status()), answer.body());
    });
  }

  /** One element of a batch: the status and the body the single route would have answered. */
  private record Answer(int status, ObjectNode body) {}

  private Answer collectOneBody(com.fasterxml.jackson.databind.JsonNode element) {
    var envelope = envelopeSchema().validate(element);
    if (envelope.failed()) {
      return new Answer(400, Responses.errorBody(400, "Bad request", "bad-request",
          envelope.error()));
    }
    var payload = envelopeSchema().validateNested(element, "payload", payloadSchema());
    if (payload.failed()) {
      return new Answer(400, Responses.errorBody(400, "Bad request", "bad-request",
          payload.error()));
    }
    var outcome =
        collect.collect(envelope.value().get("type").asText(), payload.value(),
            new Collect.Context(headers(), remoteAddress(), Instant.now()));
    return switch (outcome) {
      case Collect.Outcome.Robot ignored -> {
        var body = Json.object();
        body.put("beep", "boop");
        yield new Answer(200, body);
      }
      case Collect.Outcome.Blocked ignored ->
          new Answer(403, Responses.errorBody(403, "Forbidden", "forbidden", null));
      case Collect.Outcome.UnknownWebsite ignored -> {
        var extra = Json.object();
        extra.put("message", "Website not found.");
        yield new Answer(400, Responses.errorBody(400, "Bad request", "bad-request", extra));
      }
      case Collect.Outcome.Recorded recorded -> {
        var body = Json.object();
        body.put("cache", recorded.cacheToken());
        body.put("sessionId", recorded.sessionId());
        body.put("visitId", recorded.visitId());
        yield new Answer(200, body);
      }
    };
  }

  /** At most five hundred payloads replayed through the same path, one answer for the lot. */
  @Post("/api/batch")
  public HttpResponse batch(HttpEntity.Strict requestBody) {
    return answer(() -> {
      var node = bodyNode(requestBody);
      if (node == null || !node.isArray()) {
        var error = Json.object();
        var errors = Json.array();
        errors.add("Invalid input: expected array, received object");
        error.set("errors", errors);
        return Responses.badRequest(error);
      }
      if (node.size() > 500) {
        var error = Json.object();
        var errors = Json.array();
        errors.add("Too big: expected array to have <=500 items");
        error.set("errors", errors);
        return Responses.badRequest(error);
      }
      // Only the elements that were refused are described, and the token handed back is the
      // first success's, not the last: a batch of one bad element and one good one still gives
      // the caller a token to carry.
      var details = Json.array();
      int errors = 0;
      String cache = null;
      for (int index = 0; index < node.size(); index++) {
        var answer = collectOneBody(node.get(index));
        if (answer.status() == 200) {
          if (cache == null && answer.body().hasNonNull("cache")) {
            cache = answer.body().get("cache").asText();
          }
        } else {
          var detail = Json.object();
          detail.put("index", index);
          detail.set("response", answer.body());
          details.add(detail);
          errors++;
        }
      }
      var body = Json.object();
      body.put("size", node.size());
      body.put("processed", node.size() - errors);
      body.put("errors", errors);
      body.set("details", details);
      body.put("cache", cache);
      return Responses.json(body);
    });
  }

  // ------------------------------------------------------------------ the recorder

  @Post("/api/record")
  public HttpResponse record(HttpEntity.Strict requestBody) {
    return withCors(answer(() -> {
      var raw = requestBody.getData();
      if (raw.size() > MAXIMUM_RECORD_BYTES) {
        var extra = Json.object();
        extra.put("reason", "payload_too_large");
        extra.put("maxBytes", MAXIMUM_RECORD_BYTES);
        extra.put("size", raw.size());
        return Responses.payloadTooLarge(extra);
      }
      var request = body(requestBody);
      var type = Json.text(request, "type");
      var payload = request.get("payload");
      if (payload == null || !payload.isObject()) {
        return Responses.badRequest();
      }
      var websiteId = Json.text(payload, "website");
      var events = payload.get("events");
      if (websiteId == null || events == null || !events.isArray()) {
        return Responses.badRequest();
      }
      if (events.size() > 200) {
        var error = Json.object();
        var errors = Json.array();
        errors.add("Too big: expected array to have <=200 items");
        error.set("errors", errors);
        return Responses.badRequest(error);
      }

      var cacheHeader = header(Constants.CACHE_HEADER);
      if (cacheHeader == null) {
        return Responses.badRequest("Missing session token.");
      }
      var cache = Jwt.parseToken(cacheHeader, Crypto.secret());
      if (cache == null || !Constants.CACHE_TOKEN_TYPE.equals(Json.text(cache, "type"))) {
        return Responses.badRequest("Invalid session token.");
      }
      var website = store.website(websiteId);
      if (website == null) {
        return Responses.badRequest("Website not found.");
      }
      var userAgent = header("user-agent");
      if (!Env.isSet("DISABLE_BOT_CHECK") && Detect.isBot(userAgent)) {
        var body = Json.object();
        body.put("beep", "boop");
        return Responses.json(body);
      }
      var ip = Detect.getIpAddress(headers());
      if (Detect.hasBlockedIp(ip == null ? remoteAddress() : ip)) {
        return Responses.forbidden();
      }

      var configuration =
          website.replayConfig() == null
              ? io.akka.umami.domain.Content.ReplayConfig.EMPTY.withDefaults()
              : website.replayConfig().withDefaults();
      if (!website.recorderEnabled()) {
        return refused("recorder_disabled");
      }
      var sessionId = Json.text(cache, "sessionId");
      var visitId = Json.text(cache, "visitId");

      if ("heatmap".equals(type)) {
        if (!Boolean.TRUE.equals(configuration.heatmapEnabled())) {
          return refused("heatmap_disabled");
        }
        for (var event : events) {
          var id = Crypto.uuid().toString();
          var kind = "scroll".equals(Json.text(event, "type"))
              ? Constants.HEATMAP_SCROLL
              : Constants.HEATMAP_CLICK;
          store.putFact(
              io.akka.umami.application.Store.HEATMAP,
              id,
              new Recordings.HeatmapEvent(
                  id,
                  websiteId,
                  sessionId,
                  visitId,
                  Json.text(event, "url"),
                  kind,
                  rounded(event, "x"),
                  rounded(event, "y"),
                  rounded(event, "pageX"),
                  rounded(event, "pageY"),
                  rounded(event, "pageW"),
                  rounded(event, "pageH"),
                  rounded(event, "viewportW"),
                  rounded(event, "viewportH"),
                  clamped(rounded(event, "scrollPct")),
                  stamp(event)));
        }
        return Responses.ok();
      }

      if (!Boolean.TRUE.equals(configuration.replayEnabled())) {
        return refused("replay_disabled");
      }
      var existing = store.replayChunks(websiteId, visitId);
      var id = Crypto.uuid().toString();
      var now = Instant.now();
      store.putFact(
          io.akka.umami.application.Store.REPLAY,
          id,
          new Recordings.ReplayChunk(
              id,
              websiteId,
              sessionId,
              visitId,
              existing.size(),
              Json.write(events),
              events.size(),
              now,
              now,
              now));
      return Responses.ok();
    }));
  }

  private static HttpResponse refused(String reason) {
    var body = Json.object();
    body.put("ok", false);
    body.put("reason", reason);
    return Responses.json(body);
  }

  private static Integer rounded(com.fasterxml.jackson.databind.JsonNode node, String field) {
    var value = node.get(field);
    if (value == null || !value.isNumber() || !Double.isFinite(value.asDouble())) {
      return null;
    }
    return (int) Math.round(value.asDouble());
  }

  private static Integer clamped(Integer value) {
    if (value == null) {
      return null;
    }
    return Math.max(0, Math.min(100, value));
  }

  private static Instant stamp(com.fasterxml.jackson.databind.JsonNode node) {
    var value = node.get("timestamp");
    if (value == null || !value.isNumber()) {
      return Instant.now();
    }
    return Instant.ofEpochMilli(value.asLong());
  }

  static HttpResponse withCors(HttpResponse response) {
    return response
        .addHeader(RawHeader.create("Access-Control-Allow-Origin", "*"))
        .addHeader(RawHeader.create("Access-Control-Allow-Headers", "Content-Type, x-umami-cache"))
        .addHeader(RawHeader.create("Access-Control-Allow-Methods", "GET, POST, OPTIONS"))
        .addHeader(RawHeader.create("Access-Control-Max-Age", Env.get("CORS_MAX_AGE", "86400")));
  }

  // ------------------------------------------------------------------ the two redirectors

  /** Collects a payload this service built itself, where there is nothing to answer with. */
  private void record(ObjectNode payload) {
    collect.collect("event", payload,
        new Collect.Context(headers(), remoteAddress(), Instant.now()));
  }

  /** A tracking image: it records a pixel event and answers one transparent dot. */
  @Get("/p/{slug}")
  public HttpResponse pixel(String slug) {
    return answer(() -> {
      var pixel = store.pixelBySlug(slug);
      if (pixel == null) {
        return Responses.notFound();
      }
      var payload = Json.object();
      payload.put("pixel", pixel.id());
      payload.put("url", requestUrl());
      payload.put("referrer", header("referer"));
      record(payload);
      return Responses.text(
          "image/gif",
          TRANSPARENT_GIF,
          Map.of(
              "Cache-Control", "no-cache, no-store, must-revalidate",
              "Pragma", "no-cache",
              "Expires", "0"));
    });
  }

  /** A short link: it records a link event and sends the caller on. */
  @Get("/q/{slug}")
  public HttpResponse link(String slug) {
    return answer(() -> {
      var link = store.linkBySlug(slug);
      if (link == null) {
        return Responses.notFound();
      }
      var payload = Json.object();
      payload.put("link", link.id());
      payload.put("url", requestUrl());
      payload.put("referrer", header("referer"));
      record(payload);
      return HttpResponse.create()
          .withStatus(StatusCodes.TEMPORARY_REDIRECT)
          .addHeader(akka.http.javadsl.model.headers.Location.create(link.url()));
    });
  }

  private String requestUrl() {
    var host = header("host");
    return "https://" + (host == null ? "localhost" : host) + "/";
  }

  // ------------------------------------------------------------------ health and settings

  @Get("/api/heartbeat")
  public HttpResponse heartbeat() {
    var body = Json.object();
    body.put("ok", true);
    return Responses.json(body);
  }

  @Get("/api/config")
  public HttpResponse config() {
    var body = Json.object();
    body.put("cloudMode", Env.isSet("CLOUD_MODE"));
    // A setting with no value is absent from the answer rather than present and null, which
    // is what the original's own writer produces and what its client reads. SPEC R120.
    present(body, "faviconUrl", Env.get("FAVICON_URL"));
    present(body, "linksUrl", Env.get("LINKS_URL"));
    present(body, "pixelsUrl", Env.get("PIXELS_URL"));
    body.put("privateMode", Env.isSet("PRIVATE_MODE"));
    body.put("sessionDeletionEnabled", true);
    body.put("telemetryDisabled", Env.isSet("DISABLE_TELEMETRY"));
    present(body, "trackerScriptName", Env.get("TRACKER_SCRIPT_NAME"));
    body.put("updatesDisabled", Env.isSet("DISABLE_UPDATES"));
    return Responses.json(body);
  }

  private static void present(com.fasterxml.jackson.databind.node.ObjectNode body,
      String name, String value) {
    if (value != null) {
      body.put(name, value);
    }
  }

  /** The counter script, or a comment where it is switched off. */
  @Get("/api/scripts/telemetry")
  public HttpResponse telemetry() {
    if (Env.isSet("DISABLE_TELEMETRY") || Env.isSet("PRIVATE_MODE")
        || !"production".equals(Env.get("NODE_ENV"))) {
      return Responses.text("text/javascript", utf8("/* telemetry disabled */"), Map.of());
    }
    var script =
        "(()=>{const i=document.createElement('img');i.setAttribute('src',"
            + "'https://i.umami.is/a.png?v=" + Constants.CURRENT_VERSION + "');"
            + "i.setAttribute('style','position:absolute;left:-9999px;');"
            + "document.body.appendChild(i);})();";
    return Responses.text("text/javascript", utf8(script), Map.of());
  }
}
