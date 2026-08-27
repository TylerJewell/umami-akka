package io.akka.umami.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.akka.umami.domain.Traffic;
import io.akka.umami.lib.Constants;
import io.akka.umami.lib.Crypto;
import io.akka.umami.lib.Detect;
import io.akka.umami.lib.Env;
import io.akka.umami.lib.Json;
import io.akka.umami.lib.Jwt;
import io.akka.umami.lib.Values;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * What a collected payload becomes.
 *
 * <p>Everything a visitor is recognised by is derived here and nothing is stored on their machine:
 * the session identifier is a hash of the site, the address, the user agent and a salt that rotates
 * on a period, and the visit identifier hangs off that and the hour. The cache token handed back is
 * an optimisation, not an identity — a request without one lands in exactly the same session.
 */
public final class Collect {

  private final Store store;

  public Collect(Store store) {
    this.store = store;
  }

  /** Why a payload was refused, or what it produced. */
  public sealed interface Outcome {

    record Recorded(String cacheToken, String sessionId, String visitId) implements Outcome {}

    record Robot() implements Outcome {}

    record Blocked() implements Outcome {}

    record UnknownWebsite() implements Outcome {}
  }

  /** What the collection route knows about the request the payload arrived on. */
  public record Context(Map<String, String> headers, String remoteAddress, Instant now) {}

  public Outcome collect(String type, ObjectNode payload, Context context) {
    var websiteId = text(payload, "website");
    var linkId = text(payload, "link");
    var pixelId = text(payload, "pixel");
    var sourceId = websiteId != null ? websiteId : (pixelId != null ? pixelId : linkId);

    ObjectNode cache = null;
    if (websiteId != null) {
      var header = Detect.header(context.headers(), Constants.CACHE_HEADER);
      var parsed = Jwt.parseToken(header, Crypto.secret());
      if (parsed != null
          && Constants.CACHE_TOKEN_TYPE.equals(Json.text(parsed, "type"))) {
        cache = parsed;
      }
      if (cache == null || Json.text(cache, "websiteId") == null) {
        if (store.website(websiteId) == null) {
          return new Outcome.UnknownWebsite();
        }
      }
    }
    var sessionLinkId = cache == null ? null : Json.text(cache, "sessionLinkId");

    var userAgent = text(payload, "userAgent");
    if (userAgent == null) {
      userAgent = Detect.header(context.headers(), "user-agent");
    }
    var suppliedIp = text(payload, "ip");
    var ip = suppliedIp != null ? suppliedIp : Detect.getIpAddress(context.headers());
    if (ip == null) {
      ip = context.remoteAddress();
    }

    if (!Env.isSet("DISABLE_BOT_CHECK") && Detect.isBot(userAgent)) {
      return new Outcome.Robot();
    }
    if (Detect.hasBlockedIp(ip)) {
      return new Outcome.Blocked();
    }

    var timestamp = number(payload, "timestamp");
    var createdAt = timestamp != null ? Instant.ofEpochSecond(timestamp) : context.now();
    long now = context.now().getEpochSecond();

    var sessionSalt = Crypto.getSalt(Env.get("SALT_ROTATION", "month"), createdAt);
    var visitSalt = Crypto.visitSalt(createdAt);
    var sessionId =
        Crypto.uuid(nullToEmpty(sourceId), nullToEmpty(ip), nullToEmpty(userAgent), sessionSalt)
            .toString();

    var cachedSession = cache == null ? null : Json.text(cache, "sessionId");
    boolean drifted = websiteId != null && cachedSession != null && !cachedSession.equals(sessionId);

    var location = Detect.getLocation(ip, context.headers(), suppliedIp != null);
    var screen = Values.truncate(text(payload, "screen"), "screen");
    var session =
        new Traffic.Session(
            sessionId,
            sourceId,
            Values.truncate(orDetected(text(payload, "browser"), Detect.browserName(userAgent)),
                "browser"),
            Values.truncate(orDetected(text(payload, "os"), Detect.detectOS(userAgent)), "os"),
            Values.truncate(
                orDetected(text(payload, "device"), Detect.getDevice(userAgent, screen)), "device"),
            screen,
            Values.truncate(text(payload, "language"), "language"),
            Values.truncate(location == null ? null : location.country(), "country"),
            Values.truncate(location == null ? null : location.region(), "region"),
            Values.truncate(location == null ? null : location.city(), "city"),
            Values.truncate(text(payload, "id"), "distinctId"),
            createdAt);

    if (cachedSession == null || drifted) {
      var existing = store.session(sourceId, sessionId);
      if (existing == null) {
        store.putFact(Store.SESSION, sourceId + ":" + sessionId, session);
      } else {
        session = existing;
      }
    } else {
      var existing = store.session(sourceId, sessionId);
      if (existing != null) {
        session = existing;
      }
    }

    var visitId = cache == null ? null : Json.text(cache, "visitId");
    var iat = cache == null ? null : Json.number(cache, "iat");
    if (visitId == null) {
      visitId = Crypto.uuid(sessionId, visitSalt).toString();
      iat = now;
    }
    if (drifted) {
      visitId = Crypto.uuid(sessionId, visitSalt).toString();
      iat = now;
    }
    // A caller that named its own instant is backfilling, and a backfill must not be cut into
    // visits by how long the run itself takes. SPEC R11.
    if (timestamp == null && iat != null && now - iat > Constants.VISIT_TIMEOUT_SECONDS) {
      visitId = Crypto.uuid(sessionId, visitSalt).toString();
      iat = now;
    }

    switch (type) {
      case "identify" -> {
        var distinctId = text(payload, "id");
        if (websiteId != null && distinctId != null) {
          var linkKey = Crypto.hash(sessionId, distinctId);
          if (!linkKey.equals(sessionLinkId)) {
            try {
              store.putFact(
                  Store.SESSION_LINK,
                  websiteId + ":" + distinctId + ":" + sessionId,
                  new Traffic.SessionLink(websiteId, distinctId, sessionId, createdAt));
              store.putFact(
                  Store.SESSION,
                  websiteId + ":" + sessionId,
                  withDistinctId(session, distinctId));
            } catch (RuntimeException e) {
              // Identity stitching is best effort: a payload that carried properties still
              // records them, and the caller still gets a token. SPEC R19.
            }
            sessionLinkId = linkKey;
          }
        }
        writeSessionProperties(sourceId, sessionId, text(payload, "id"), payload.get("data"),
            createdAt);
      }
      case "performance" -> {
        var event =
            baseEvent(payload, sourceId, sessionId, visitId, createdAt, websiteId, linkId, pixelId,
                Constants.PERFORMANCE_EVENT, List.of());
        store.putFact(Store.EVENT, event.id(), event);
      }
      default -> {
        int eventType =
            linkId != null
                ? Constants.LINK_EVENT
                : pixelId != null
                    ? Constants.PIXEL_EVENT
                    : (text(payload, "name") != null && !text(payload, "name").isEmpty()
                        ? Constants.CUSTOM_EVENT
                        : Constants.PAGE_VIEW);
        var properties =
            payload.get("data") == null ? List.<Values.Property>of() : Values.flatten(
                payload.get("data"));
        var event =
            baseEvent(payload, sourceId, sessionId, visitId, createdAt, websiteId, linkId, pixelId,
                eventType, properties);
        store.putFact(Store.EVENT, event.id(), event);
        writeRevenue(sourceId, sessionId, event, payload.get("data"), createdAt);
      }
    }

    var claims = Json.object();
    claims.put("websiteId", websiteId);
    claims.put("sessionId", sessionId);
    claims.put("visitId", visitId);
    claims.put("iat", iat);
    if (sessionLinkId != null) {
      claims.put("sessionLinkId", sessionLinkId);
    }
    claims.put("type", Constants.CACHE_TOKEN_TYPE);
    return new Outcome.Recorded(
        Jwt.createToken(claims, Crypto.secret()), sessionId, visitId);
  }

  private static Traffic.Session withDistinctId(Traffic.Session session, String distinctId) {
    return new Traffic.Session(session.id(), session.websiteId(), session.browser(), session.os(),
        session.device(), session.screen(), session.language(), session.country(), session.region(),
        session.city(), Values.truncate(distinctId, "distinctId"), session.createdAt());
  }

  private void writeSessionProperties(String websiteId, String sessionId, String distinctId,
      JsonNode data, Instant createdAt) {
    if (data == null || data.isNull()) {
      return;
    }
    for (var property : Values.flatten(data)) {
      store.putFact(
          Store.SESSION_DATA,
          sessionId + ":" + property.key(),
          new Traffic.SessionProperty(
              Crypto.uuid().toString(), websiteId, sessionId, distinctId, property, createdAt));
    }
  }

  /** A property set naming a positive amount and a currency records revenue beside the event. */
  private void writeRevenue(String websiteId, String sessionId, Traffic.Event event, JsonNode data,
      Instant createdAt) {
    if (data == null || data.isNull()) {
      return;
    }
    var amount = data.get("revenue");
    var currency = data.get("currency");
    if (amount == null || !amount.isNumber() || currency == null || currency.asText().isEmpty()) {
      return;
    }
    if (amount.decimalValue().compareTo(BigDecimal.ZERO) <= 0) {
      return;
    }
    var id = Crypto.uuid().toString();
    store.putFact(
        Store.REVENUE,
        id,
        new Traffic.Revenue(
            id,
            websiteId,
            sessionId,
            event.id(),
            Values.truncate(event.eventName(), "eventName"),
            Values.truncate(currency.asText(), "currency"),
            amount.decimalValue(),
            createdAt));
  }

  private Traffic.Event baseEvent(ObjectNode payload, String websiteId, String sessionId,
      String visitId, Instant createdAt, String websiteIdOrNull, String linkId, String pixelId,
      int eventType, List<Values.Property> properties) {
    var hostname = text(payload, "hostname");
    var url = text(payload, "url");
    var referrer = text(payload, "referrer");

    var base = "https://" + (hostname == null || hostname.isEmpty() ? "localhost" : hostname);
    var resolved = resolve(base, url);
    var path = resolved == null ? "" : resolved.path();
    var query = resolved == null || resolved.query() == null ? "" : resolved.query();
    var fragment = resolved == null ? "" : resolved.fragment();
    var urlPath = "/undefined".equals(path) ? "" : path + (fragment == null ? "" : fragment);
    if (Env.isSet("REMOVE_TRAILING_SLASH")) {
      urlPath = removeTrailingSlash(urlPath);
    }

    // The referrer is resolved against the event's own domain rather than the fallback, so a
    // path-only referrer lands on the site it came from.
    var eventDomain = ownHost(hostname == null ? null : hostname);
    var referrerBase = eventDomain == null ? base : "https://" + eventDomain;
    var referrerResolved = resolve(referrerBase, referrer);
    String referrerDomain = null;
    String referrerPath = null;
    String referrerQuery = null;
    if (referrerResolved != null) {
      referrerPath = referrerResolved.path();
      referrerQuery = referrerResolved.query() == null ? "" : referrerResolved.query();
      var host = ownHost(referrerResolved.host());
      if (host != null && !host.equalsIgnoreCase(eventDomain)) {
        referrerDomain = host;
      }
    }

    var parameters = queryParameters(query);
    return new Traffic.Event(
        Crypto.uuid().toString(),
        websiteId,
        sessionId,
        visitId,
        createdAt,
        Values.truncate(urlPath, "urlPath"),
        Values.truncate(query, "urlQuery"),
        Values.truncate(referrerPath, "referrerPath"),
        Values.truncate(referrerQuery, "referrerQuery"),
        Values.truncate(referrerDomain, "referrerDomain"),
        Values.truncate(text(payload, "title"), "pageTitle"),
        Values.truncate(parameters.get("utm_source"), "utm"),
        Values.truncate(parameters.get("utm_medium"), "utm"),
        Values.truncate(parameters.get("utm_campaign"), "utm"),
        Values.truncate(parameters.get("utm_content"), "utm"),
        Values.truncate(parameters.get("utm_term"), "utm"),
        Values.truncate(parameters.get("gclid"), "clickId"),
        Values.truncate(parameters.get("fbclid"), "clickId"),
        Values.truncate(parameters.get("msclkid"), "clickId"),
        Values.truncate(parameters.get("ttclid"), "clickId"),
        Values.truncate(parameters.get("twclid"), "clickId"),
        Values.truncate(parameters.get("li_fat_id"), "clickId"),
        eventType,
        emptyToNull(Values.truncate(text(payload, "name"), "eventName")),
        Values.truncate(text(payload, "tag"), "tag"),
        Values.truncate(hostname, "hostname"),
        decimal(payload, "lcp"),
        decimal(payload, "inp"),
        decimal(payload, "cls"),
        decimal(payload, "fcp"),
        decimal(payload, "ttfb"),
        properties);
  }

  /** The event's own host with a leading {@code www.} removed, which is what a self-referral is. */
  private static String ownHost(String host) {
    if (host == null) {
      return null;
    }
    var stripped = host.startsWith("www.") ? host.substring(4) : host;
    int colon = stripped.indexOf(':');
    return colon < 0 ? stripped : stripped.substring(0, colon);
  }

  static String removeTrailingSlash(String path) {
    if (path == null || path.isEmpty()) {
      return path;
    }
    int hash = path.indexOf('#');
    var before = hash < 0 ? path : path.substring(0, hash);
    var after = hash < 0 ? "" : path.substring(hash);
    if (before.length() > 1 && before.endsWith("/")) {
      before = before.substring(0, before.length() - 1);
    }
    return before + after;
  }

  record Resolved(String host, String path, String query, String fragment) {}

  static Resolved resolve(String base, String value) {
    if (value == null || value.isEmpty()) {
      return null;
    }
    try {
      var uri = URI.create(base).resolve(value);
      var host = uri.getHost();
      if (host != null && uri.getPort() > 0) {
        host = host + ":" + uri.getPort();
      }
      return new Resolved(
          host,
          uri.getRawPath() == null ? "" : uri.getRawPath(),
          uri.getRawQuery(),
          uri.getRawFragment() == null ? "" : "#" + uri.getRawFragment());
    } catch (RuntimeException e) {
      return null;
    }
  }

  static Map<String, String> queryParameters(String query) {
    var out = new java.util.LinkedHashMap<String, String>();
    if (query == null || query.isEmpty()) {
      return out;
    }
    for (var pair : query.split("&")) {
      int equals = pair.indexOf('=');
      if (equals <= 0) {
        continue;
      }
      var name = java.net.URLDecoder.decode(pair.substring(0, equals),
          java.nio.charset.StandardCharsets.UTF_8);
      var value = java.net.URLDecoder.decode(pair.substring(equals + 1),
          java.nio.charset.StandardCharsets.UTF_8);
      out.putIfAbsent(name.toLowerCase(Locale.ROOT), value);
    }
    return out;
  }

  // ------------------------------------------------------------------ removal

  /** Removes every fact of a website, which is what a reset and a removal both begin with. */
  public void purge(String websiteId) {
    for (var kind : List.of(Store.EVENT, Store.SESSION, Store.SESSION_DATA, Store.REVENUE,
        Store.HEATMAP, Store.REPLAY, Store.SESSION_LINK)) {
      for (var id : store.factKeys(kind, websiteId)) {
        store.removeFact(kind, id);
      }
    }
    for (var saved : store.savedReplays(websiteId)) {
      store.remove(Store.SAVED_REPLAY, saved.id());
    }
  }

  /** Removes one session and everything hanging off it. */
  public boolean purgeSession(String websiteId, String sessionId) {
    var session = store.session(websiteId, sessionId);
    if (session == null) {
      return false;
    }
    var visits = new ArrayList<String>();
    for (var event : store.factsBySession(Store.EVENT, websiteId, sessionId,
        Traffic.Event.class)) {
      if (!visits.contains(event.visitId())) {
        visits.add(event.visitId());
      }
      store.removeFact(Store.EVENT, event.id());
    }
    for (var property : store.sessionProperties(websiteId, sessionId)) {
      store.removeFact(Store.SESSION_DATA, sessionId + ":" + property.property().key());
    }
    for (var record : store.factsBySession(Store.REVENUE, websiteId, sessionId,
        Traffic.Revenue.class)) {
      store.removeFact(Store.REVENUE, record.id());
    }
    for (var link : store.identityLinksOfSession(websiteId, sessionId)) {
      store.removeFact(Store.SESSION_LINK,
          websiteId + ":" + link.distinctId() + ":" + sessionId);
    }
    for (var visitId : visits) {
      for (var chunk : store.replayChunks(websiteId, visitId)) {
        store.removeFact(Store.REPLAY, chunk.id());
      }
      var saved = store.savedReplay(websiteId, visitId);
      if (saved != null) {
        store.remove(Store.SAVED_REPLAY, saved.id());
      }
    }
    for (var row : store.heatmap(websiteId, Instant.EPOCH,
        Instant.now().plusSeconds(365L * 24 * 3600))) {
      if (sessionId.equals(row.sessionId())) {
        store.removeFact(Store.HEATMAP, row.id());
      }
    }
    store.removeFact(Store.SESSION, websiteId + ":" + sessionId);
    return true;
  }

  // ------------------------------------------------------------------ small readers

  static String text(JsonNode node, String field) {
    if (node == null) {
      return null;
    }
    var value = node.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }

  private static Long number(JsonNode node, String field) {
    var value = node.get(field);
    return value == null || !value.isNumber() ? null : value.asLong();
  }

  private static BigDecimal decimal(JsonNode node, String field) {
    var value = node.get(field);
    return value == null || !value.isNumber() ? null : value.decimalValue();
  }

  private static String orDetected(String supplied, String detected) {
    return supplied != null ? supplied : detected;
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  private static String emptyToNull(String value) {
    return value == null || value.isEmpty() ? null : value;
  }

  public static UUID randomId() {
    return Crypto.uuid();
  }
}
