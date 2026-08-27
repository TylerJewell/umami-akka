package io.akka.umami.api;

import akka.http.javadsl.model.HttpEntity;
import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Delete;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.akka.umami.application.Claims;
import io.akka.umami.application.Store;
import io.akka.umami.domain.Content;
import io.akka.umami.lib.Constants;
import io.akka.umami.lib.Crypto;
import io.akka.umami.lib.Env;
import io.akka.umami.lib.Filters;
import io.akka.umami.lib.Json;
import io.akka.umami.lib.Responses;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Websites: creating them, changing them, sharing them, resetting them, exporting them. */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint
public class WebsiteEndpoint extends Api {

  public WebsiteEndpoint(ComponentClient client, akka.stream.Materializer materializer) {
    super(client, materializer);
  }

  // ------------------------------------------------------------------ the list

  @Get("/api/websites")
  public HttpResponse list() {
    return answer(() -> {
      var caller = caller();
      boolean includeTeams = queryParam("includeTeams") != null;
      return Responses.json(websitePage(this, store, caller.userId(), includeTeams,
          filters(null)));
    });
  }

  /** Every website an account can reach, with or without the ones its teams own. */
  static ObjectNode websitePage(Api api, Store store, String userId, boolean includeTeams,
      Filters.Query query) {
    var websites = new ArrayList<Content.Website>(store.byOwner(Store.WEBSITE, userId,
        Content.Website.class));
    if (includeTeams) {
      var seen = new LinkedHashSet<String>();
      websites.forEach(website -> seen.add(website.id()));
      for (var membership : store.membershipsOf(userId)) {
        for (var website : store.byTeam(Store.WEBSITE, membership.teamId(),
            Content.Website.class)) {
          if (seen.add(website.id())) {
            websites.add(website);
          }
        }
      }
    }
    websites.removeIf(Content.Website::isDeleted);
    var search = query.search();
    if (search != null && !search.isBlank()) {
      websites.removeIf(
          website ->
              !AccountEndpoint.containsIgnoringCase(website.name(), search)
                  && !AccountEndpoint.containsIgnoringCase(website.domain(), search));
    }
    var orderBy = effectiveOrderBy(query.orderBy(), List.of("name", "domain", "createdAt"), "name");
    var comparator = websiteComparator(orderBy);
    websites.sort(Boolean.TRUE.equals(query.sortDescending()) ? comparator.reversed() : comparator);
    return page(
        websites,
        query.page() == null ? 1 : query.page(),
        query.pageSize() == null ? Constants.DEFAULT_PAGE_SIZE : query.pageSize(),
        orderBy,
        query.search(),
        website -> {
          var row = Writers.website(website, shareIdOf(store, website.id()));
          // Listing an account's own websites fetches the owner alongside each row; listing
          // everything the account can reach, teams included, does not.
          return includeTeams ? row
              : Writers.withAccount(row, "user", store.user(website.userId()));
        });
  }

  private static Comparator<Content.Website> websiteComparator(String orderBy) {
    return switch (orderBy) {
      case "domain" -> Comparator.comparing(website ->
          AccountEndpoint.nullToEmpty(website.domain()));
      case "createdAt" -> Comparator.comparing(website ->
          website.createdAt() == null ? Instant.EPOCH : website.createdAt());
      default -> Comparator.comparing(website -> AccountEndpoint.nullToEmpty(website.name()));
    };
  }

  /** The newest share of an entity decides the identifier a website reports. */
  static String shareIdOf(Store store, String entityId) {
    var shares = new ArrayList<>(store.sharesOf(entityId));
    if (shares.isEmpty()) {
      return null;
    }
    shares.sort(
        Comparator.comparing((Content.Share share) ->
            share.createdAt() == null ? Instant.EPOCH : share.createdAt()).reversed());
    return shares.get(0).slug();
  }

  @Post("/api/websites")
  public HttpResponse create(HttpEntity.Strict requestBody) {
    return answer(() -> {
      var caller = caller();
      var schema = Schema.object();
      schema.string("name").trim().min(1).max(100).required();
      schema.string("domain").trim().max(500).pattern(Constants.DOMAIN_PATTERN).required();
      schema.string("shareId").max(50).nullable();
      schema.uuid("teamId").nullable();
      schema.uuid("id").nullable();
      var request = validate(schema, body(requestBody));

      var teamId = textOrNull(request, "teamId");
      require((teamId == null || permissions.canCreateTeamWebsite(caller, teamId))
          && permissions.canCreateWebsite(caller));

      var id = textOrNull(request, "id") != null ? request.get("id").asText()
          : Crypto.uuid().toString();
      var now = Instant.now();
      var website =
          new Content.Website(id, request.get("name").asText(), request.get("domain").asText(),
              null, teamId == null ? caller.userId() : null, teamId, caller.userId(), now, now,
              null, false, null);
      store.put(Store.WEBSITE, id, website);

      String shareId = null;
      var requestedShare = textOrNull(request, "shareId");
      if (requestedShare != null) {
        // Creating a website has no check of its own on the share identifier; the store's
        // uniqueness is what refuses it, and nothing catches that.
        if (!claims.take(Claims.SHARE_SLUG, requestedShare, id)) {
          return Responses.uncaught();
        }
        var parameters = Json.object();
        parameters.put("overview", true);
        parameters.put("events", true);
        var share =
            new Content.Share(Crypto.uuid().toString(), id, request.get("name").asText(),
                Constants.ENTITY_WEBSITE, requestedShare, parameters, now, now);
        store.put(Store.SHARE, share.id(), share);
        shareId = requestedShare;
      }
      return Responses.json(Writers.website(website, shareId));
    });
  }

  @Get("/api/websites/{websiteId}")
  public HttpResponse read(String websiteId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canViewSharedWebsite(caller, websiteId));
      var website = store.website(websiteId);
      if (website == null) {
        return Responses.json(Json.object().nullNode());
      }
      return Responses.json(Writers.website(website, shareIdOf(store, websiteId)));
    });
  }

  @Post("/api/websites/{websiteId}")
  public HttpResponse update(String websiteId, HttpEntity.Strict requestBody) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canUpdateWebsite(caller, websiteId));
      var schema = Schema.object();
      schema.string("name").trim().min(1).max(100);
      schema.string("domain").trim().max(500).pattern(Constants.DOMAIN_PATTERN);
      schema.string("shareId").max(50).nullable();
      schema.objectField("replayConfig").nullable();
      var request = validate(schema, body(requestBody));

      var website = store.website(websiteId);
      if (website == null) {
        return Responses.badRequest("Website not found.");
      }

      var configuration =
          website.replayConfig() == null ? Content.ReplayConfig.EMPTY : website.replayConfig();
      if (request.has("replayConfig")) {
        var node = request.get("replayConfig");
        configuration =
            node.isNull() ? Content.ReplayConfig.EMPTY : configuration.merge(readConfig(node));
      }

      var updated =
          new Content.Website(
              website.id(),
              request.has("name") ? request.get("name").asText() : website.name(),
              request.has("domain") ? request.get("domain").asText() : website.domain(),
              website.resetAt(),
              website.userId(),
              website.teamId(),
              website.createdBy(),
              website.createdAt(),
              Instant.now(),
              website.deletedAt(),
              configuration.recorderEnabled(),
              configuration);
      store.put(Store.WEBSITE, websiteId, updated);

      String shareId = shareIdOf(store, websiteId);
      if (request.has("shareId")) {
        var node = request.get("shareId");
        if (node.isNull()) {
          store.sharesOf(websiteId).forEach(share -> {
            claims.release(Claims.SHARE_SLUG, share.slug());
            store.remove(Store.SHARE, share.id());
          });
          shareId = null;
        } else {
          var wanted = node.asText();
          if (!claims.take(Claims.SHARE_SLUG, wanted, websiteId)) {
            return Responses.badRequest("That share ID is already taken.");
          }
          var existing = store.sharesOf(websiteId);
          var now = Instant.now();
          if (existing.isEmpty()) {
            var parameters = Json.object();
            parameters.put("overview", true);
            parameters.put("events", true);
            var shareRecordId = Crypto.uuid().toString();
            store.put(Store.SHARE, shareRecordId,
                new Content.Share(shareRecordId, websiteId, updated.name(),
                    Constants.ENTITY_WEBSITE, wanted, parameters, now, now));
          } else {
            var share = existing.get(0);
            store.put(Store.SHARE, share.id(),
                new Content.Share(share.id(), share.entityId(), share.name(), share.shareType(),
                    wanted, share.parameters(), share.createdAt(), now));
          }
          shareId = wanted;
        }
      }
      return Responses.json(Writers.website(updated, shareId));
    });
  }

  private static Content.ReplayConfig readConfig(com.fasterxml.jackson.databind.JsonNode node) {
    return new Content.ReplayConfig(
        strictBoolean(node, "replayEnabled"),
        strictBoolean(node, "heatmapEnabled"),
        strictNumber(node, "sampleRate"),
        strictNumber(node, "heatmapSampleRate"),
        maskLevel(node),
        roundedDuration(node),
        node.get("blockSelector") != null && node.get("blockSelector").isTextual()
            ? node.get("blockSelector").asText()
            : null);
  }

  /** Only a real boolean counts; the string "true" is dropped, as the original's reader does. */
  private static Boolean strictBoolean(com.fasterxml.jackson.databind.JsonNode node, String field) {
    var value = node.get(field);
    return value != null && value.isBoolean() && value.asBoolean() ? Boolean.TRUE : null;
  }

  private static Double strictNumber(com.fasterxml.jackson.databind.JsonNode node, String field) {
    var value = node.get(field);
    return value != null && value.isNumber() ? value.asDouble() : null;
  }

  private static String maskLevel(com.fasterxml.jackson.databind.JsonNode node) {
    var value = node.get("maskLevel");
    if (value == null || !value.isTextual()) {
      return null;
    }
    return List.of("strict", "moderate").contains(value.asText()) ? value.asText() : null;
  }

  private static Integer roundedDuration(com.fasterxml.jackson.databind.JsonNode node) {
    var value = node.get("maxDuration");
    if (value == null || !value.isNumber() || !Double.isFinite(value.asDouble())) {
      return null;
    }
    return (int) Math.round(value.asDouble());
  }

  @Delete("/api/websites/{websiteId}")
  public HttpResponse delete(String websiteId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canDeleteWebsite(caller, websiteId));
      collect.purge(websiteId);
      store.byParent(Store.REPORT, websiteId, Content.Report.class)
          .forEach(report -> store.remove(Store.REPORT, report.id()));
      store.byParent(Store.SEGMENT, websiteId, Content.Segment.class)
          .forEach(segment -> store.remove(Store.SEGMENT, segment.id()));
      store.sharesOf(websiteId).forEach(share -> store.remove(Store.SHARE, share.id()));
      store.remove(Store.WEBSITE, websiteId);
      return Responses.ok();
    });
  }

  // ------------------------------------------------------------------ management

  @Post("/api/websites/{websiteId}/reset")
  public HttpResponse reset(String websiteId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canUpdateWebsite(caller, websiteId));
      var website = store.website(websiteId);
      if (website == null) {
        return Responses.badRequest("Website not found.");
      }
      collect.purge(websiteId);
      store.put(Store.WEBSITE, websiteId,
          new Content.Website(website.id(), website.name(), website.domain(), Instant.now(),
              website.userId(), website.teamId(), website.createdBy(), website.createdAt(),
              Instant.now(), website.deletedAt(), website.recorderEnabled(),
              website.replayConfig()));
      return Responses.ok();
    });
  }

  @Post("/api/websites/{websiteId}/transfer")
  public HttpResponse transfer(String websiteId, HttpEntity.Strict requestBody) {
    return answer(() -> {
      var caller = caller();
      var schema = Schema.object();
      schema.uuid("userId");
      schema.uuid("teamId");
      var request = validate(schema, body(requestBody));
      var userId = textOrNull(request, "userId");
      var teamId = textOrNull(request, "teamId");
      if (userId == null && teamId == null) {
        return Responses.badRequest();
      }
      if (userId != null) {
        require(permissions.canTransferWebsiteToUser(caller, websiteId, userId));
      } else {
        require(permissions.canTransferWebsiteToTeam(caller, websiteId, teamId));
      }
      var website = store.website(websiteId);
      if (website == null) {
        return Responses.badRequest("Website not found.");
      }
      var updated =
          new Content.Website(website.id(), website.name(), website.domain(), website.resetAt(),
              userId != null ? userId : null, userId != null ? null : teamId, website.createdBy(),
              website.createdAt(), Instant.now(), website.deletedAt(), website.recorderEnabled(),
              website.replayConfig());
      store.put(Store.WEBSITE, websiteId, updated);
      return Responses.json(Writers.website(updated, shareIdOf(store, websiteId)));
    });
  }

  /**
   * What the recorder script is told, without any sign-in at all.
   *
   * <p>umami's own handler asks for a sixty-second cache and its own access-control set, and a
   * caller of umami receives neither: the address-pattern rules are applied after the route and
   * replace both. So this route sets no headers of its own either. SPEC R147.
   */
  @Get("/api/websites/{websiteId}/recorder")
  public HttpResponse recorder(String websiteId) {
    return answer(() -> {
      var website = store.website(websiteId);
      if (website == null || !website.recorderEnabled()) {
        var body = Json.object();
        body.put("enabled", false);
        return Responses.json(body);
      }
      var configuration =
          (website.replayConfig() == null ? Content.ReplayConfig.EMPTY : website.replayConfig())
              .withDefaults();
      var body = Json.object();
      body.put("enabled", true);
      body.put("replayEnabled", configuration.replayEnabled());
      body.put("heatmapEnabled", configuration.heatmapEnabled());
      body.put("sampleRate", configuration.sampleRate());
      body.put("heatmapSampleRate", configuration.heatmapSampleRate());
      body.put("maskLevel", configuration.maskLevel());
      body.put("maxDuration", configuration.maxDuration());
      body.put("blockSelector", configuration.blockSelector());
      return Responses.json(body);
    });
  }

  // ------------------------------------------------------------------ sharing

  @Get("/api/websites/{websiteId}/shares")
  public HttpResponse shares(String websiteId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canViewAuthenticatedWebsite(caller, websiteId));
      var query = filters(websiteId);
      var shares = new ArrayList<>(store.sharesOf(websiteId));
      shares.sort(
          Comparator.comparing((Content.Share share) ->
              share.createdAt() == null ? Instant.EPOCH : share.createdAt()).reversed());
      return Responses.json(page(shares, query, Writers::share));
    });
  }

  @Post("/api/websites/{websiteId}/shares")
  public HttpResponse createShare(String websiteId, HttpEntity.Strict requestBody) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canUpdateWebsite(caller, websiteId));
      return Responses.json(
          ShareEndpoint.createShareFor(store, websiteId, Constants.ENTITY_WEBSITE,
              body(requestBody)));
    });
  }

  // ------------------------------------------------------------------ export

  /**
   * Seven comma-separated files in one archive, each a dimension's counts rather than a dump
   * of the events themselves.
   *
   * <p>A cell beginning with an equals sign, a plus, a minus, an at sign, a tab or a carriage
   * return is prefixed with an apostrophe, so a spreadsheet opening it reads text rather than a
   * formula. That is the same guard the collection route applies on the way in. SPEC R122.
   */
  @Get("/api/websites/{websiteId}/export")
  public HttpResponse export(String websiteId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canViewAuthenticatedWebsite(caller, websiteId));
      var query = filters(websiteId);

      var archive = new ByteArrayOutputStream();
      try (var zip = new ZipOutputStream(archive)) {
        write(zip, "events.csv", dimensionCsv(websiteId, "event", query));
        write(zip, "pages.csv", dimensionCsv(websiteId, "path", query));
        write(zip, "referrers.csv", dimensionCsv(websiteId, "referrer", query));
        write(zip, "browsers.csv", dimensionCsv(websiteId, "browser", query));
        write(zip, "os.csv", dimensionCsv(websiteId, "os", query));
        write(zip, "devices.csv", dimensionCsv(websiteId, "device", query));
        write(zip, "countries.csv", dimensionCsv(websiteId, "country", query));
      } catch (java.io.IOException e) {
        return Responses.uncaught();
      }
      var body = Json.object();
      body.put("zip", Base64.getEncoder().encodeToString(archive.toByteArray()));
      return Responses.json(body);
    });
  }

  private String dimensionCsv(String websiteId, String type, Filters.Query query) {
    var rows =
        rollup.metrics(websiteId, type, query, Constants.METRIC_LIMIT, 0).stream()
            .map(metric -> List.of(text(metric.x()), String.valueOf(metric.y())))
            .toList();
    return csv(List.of("x", "y"), rows);
  }

  private static void write(ZipOutputStream zip, String name, String content)
      throws java.io.IOException {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    zip.closeEntry();
  }

  /** What separates one exported row from the next. */
  private static final String SEPARATOR = "\r\n";

  /**
   * Rows separated by a carriage return and a line feed, with none after the last, and nothing
   * at all — not even a heading — when there are no rows.
   */
  static String csv(List<String> headings, List<List<String>> rows) {
    if (rows.isEmpty()) {
      return "";
    }
    var lines = new ArrayList<String>();
    lines.add(String.join(",", headings.stream().map(WebsiteEndpoint::cell).toList()));
    for (var row : rows) {
      lines.add(String.join(",", row.stream().map(WebsiteEndpoint::cell).toList()));
    }
    return String.join(SEPARATOR, lines);
  }

  static String cell(String value) {
    var text = value == null ? "" : value;
    if (!text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0) {
      text = "'" + text;
    }
    if (text.contains(",") || text.contains("\"") || text.contains("\n")) {
      text = "\"" + text.replace("\"", "\"\"") + "\"";
    }
    return text;
  }

  private static String text(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  // ------------------------------------------------------------------ the sparkline

  @Get("/api/websites/charts")
  public HttpResponse charts() {
    return answer(() -> {
      var caller = caller();
      var ids = queryParam("ids");
      if (ids == null || ids.isBlank()) {
        return Responses.badRequest();
      }
      var wanted = new ArrayList<>(List.of(ids.split(",")));
      if (wanted.size() > 20) {
        var error = Json.object();
        var errors = Json.array();
        errors.add("Too big: expected array to have <=20 items");
        error.set("errors", errors);
        return Responses.badRequest(error);
      }
      // Identifiers the caller cannot see are dropped rather than refused. SPEC R124.
      var allowed = permissions.canViewBatchWebsites(caller, wanted);
      var timezone = queryParam("timezone");
      var startAt = longParam(queryParam("startAt"));
      var endAt = longParam(queryParam("endAt"));
      Instant start;
      Instant end;
      if (startAt != null && endAt != null) {
        start = Instant.ofEpochMilli(startAt);
        end = Instant.ofEpochMilli(endAt);
      } else {
        var range = io.akka.umami.lib.Dates.parseDateRange("7day", null, timezone, Instant.now());
        start = range.startDate();
        end = range.endDate();
      }
      return Responses.json(insight.listCharts(allowed, start, end, timezone, null));
    });
  }
}
