package io.akka.umami.api;

import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.client.ComponentClient;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.akka.umami.analytics.Rollup;
import io.akka.umami.lib.Constants;
import io.akka.umami.lib.Dates;
import io.akka.umami.lib.Filters;
import io.akka.umami.lib.Json;
import io.akka.umami.lib.Responses;
import java.util.ArrayList;
import java.util.List;

/** Every question the dashboard asks about one website's traffic. */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint
public class AnalyticsEndpoint extends Api {

  public AnalyticsEndpoint(ComponentClient client, akka.stream.Materializer materializer) {
    super(client, materializer);
  }

  private static final List<String> OVERVIEW = List.of("overview", "compare");
  private static final List<String> BREADTH =
      List.of("overview", "events", "sessions", "compare", "breakdown", "utm", "attribution");

  // ------------------------------------------------------------------ the five figures

  @Get("/api/websites/{websiteId}/stats")
  public HttpResponse stats(String websiteId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canViewWebsiteSection(caller, websiteId, OVERVIEW));
      var query = filters(websiteId);
      var body = Writers.stats(rollup.stats(websiteId, query));
      var comparison =
          Dates.compareRange(
              query.compare() == null ? "prev" : query.compare(), query.startDate(),
              query.endDate());
      var previous =
          rollup.stats(websiteId, query.withRange(comparison.startDate(), comparison.endDate()));
      body.set("comparison", Writers.stats(previous));
      return Responses.json(body);
    });
  }

  @Get("/api/websites/{websiteId}/pageviews")
  public HttpResponse pageviews(String websiteId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canViewWebsiteSection(caller, websiteId, OVERVIEW));
      var query = filters(websiteId);
      var body = Json.object();
      body.set("pageviews", points(rollup.pageviewSeries(websiteId, query)));
      body.set("sessions", points(rollup.sessionSeries(websiteId, query)));
      if (query.compare() != null) {
        var comparison =
            Dates.compareRange(query.compare(), query.startDate(), query.endDate());
        var shifted = query.withRange(comparison.startDate(), comparison.endDate());
        body.put("startDate", query.startDate().toString());
        body.put("endDate", query.endDate().toString());
        var previous = Json.object();
        previous.set("pageviews", points(rollup.pageviewSeries(websiteId, shifted)));
        previous.set("sessions", points(rollup.sessionSeries(websiteId, shifted)));
        previous.put("startDate", comparison.startDate().toString());
        previous.put("endDate", comparison.endDate().toString());
        body.set("compare", previous);
      }
      return Responses.json(body);
    });
  }

  private static com.fasterxml.jackson.databind.node.ArrayNode points(List<Rollup.Point> series) {
    var out = Json.array();
    series.forEach(point -> out.add(Writers.point(point)));
    return out;
  }

  // ------------------------------------------------------------------ dimensions

  @Get("/api/websites/{websiteId}/metrics")
  public HttpResponse metrics(String websiteId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canViewWebsiteSection(caller, websiteId, BREADTH));
      var type = queryParam("type");
      if (!isKnownDimension(type)) {
        return Responses.badRequest();
      }
      var query = withSearchAsFilter(filters(websiteId), type);
      int limit = orDefault(intParam(queryParam("limit")), Constants.METRIC_LIMIT);
      int offset = orDefault(intParam(queryParam("offset")), 0);
      var out = Json.array();
      rollup.metrics(websiteId, type, query, limit, offset)
          .forEach(metric -> out.add(Writers.metric(metric)));
      return Responses.json(out);
    });
  }

  @Get("/api/websites/{websiteId}/metrics/expanded")
  public HttpResponse expandedMetrics(String websiteId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canViewWebsiteSection(caller, websiteId, OVERVIEW));
      var type = queryParam("type");
      if (!isKnownDimension(type)) {
        return Responses.badRequest();
      }
      var query = withSearchAsFilter(filters(websiteId), type);
      int limit = orDefault(intParam(queryParam("limit")), Constants.METRIC_LIMIT);
      int offset = orDefault(intParam(queryParam("offset")), 0);
      var out = Json.array();
      rollup.expandedMetrics(websiteId, type, query, limit, offset)
          .forEach(row -> out.add(Writers.expanded(row)));
      return Responses.json(out);
    });
  }

  private static boolean isKnownDimension(String type) {
    return type != null
        && (Constants.SESSION_COLUMNS.contains(type)
            || Constants.EVENT_COLUMNS.contains(type)
            || "channel".equals(type));
  }

  /** A search term on a dimension becomes a contains filter on that same dimension. */
  private Filters.Query withSearchAsFilter(Filters.Query query, String type) {
    var search = queryParam("search");
    if (search == null || search.isBlank() || !Constants.FILTER_COLUMNS.containsKey(type)) {
      return query;
    }
    var clause = Filters.parseClause(type, "c." + search);
    return clause == null ? query : query.withClause(clause);
  }

  @Get("/api/websites/{websiteId}/values")
  public HttpResponse values(String websiteId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canViewWebsiteSection(caller, websiteId, BREADTH));
      // This route restricts the dimension to a fixed set, unlike the metrics routes, which
      // take any string and check it against the columns.
      var type = queryParam("type");
      if (!Constants.FIELD_TYPES.contains(type)) {
        return Responses.badRequest(
            Schema.problem("type", Schema.notAnOption(Constants.FIELD_TYPES)));
      }
      var query = filters(websiteId);
      var out = Json.array();
      for (var entry : rollup.values(websiteId, type, query, queryParam("search"))) {
        var row = Json.object();
        row.put("value", entry.getKey());
        row.put("count", entry.getValue());
        out.add(row);
      }
      return Responses.json(out);
    });
  }

  // ------------------------------------------------------------------ events

  @Get("/api/websites/{websiteId}/events")
  public HttpResponse events(String websiteId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canViewWebsiteSection(caller, websiteId, List.of("events")));
      var query = filters(websiteId);
      var rows = insight.eventList(websiteId, query, queryParam("search"));
      return Responses.json(analyticsPage(rows, query, Writers::eventRow));
    });
  }

  @Get("/api/websites/{websiteId}/events/series")
  public HttpResponse eventSeries(String websiteId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canViewWebsiteSection(caller, websiteId, List.of("events")));
      var query = filters(websiteId);
      var out = Json.array();
      rollup.eventSeries(websiteId, query, intParam(queryParam("limit")))
          .forEach(point -> out.add(Writers.namedPoint(point)));
      return Responses.json(out);
    });
  }

  @Get("/api/websites/{websiteId}/events/stats")
  public HttpResponse eventStats(String websiteId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canViewWebsiteSection(caller, websiteId, List.of("events")));
      var query = filters(websiteId);
      var stats = insight.eventStats(websiteId, query);
      var comparison =
          Dates.compareRange(query.compare() == null ? "prev" : query.compare(), query.startDate(),
              query.endDate());
      stats.set("comparison",
          insight.eventStats(websiteId,
              query.withRange(comparison.startDate(), comparison.endDate())));
      var body = Json.object();
      body.set("data", stats);
      return Responses.json(body);
    });
  }

  // ------------------------------------------------------------------ event properties

  @Get("/api/websites/{websiteId}/event-data")
  public HttpResponse eventData(String websiteId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canViewWebsiteSection(caller, websiteId, List.of("events")));
      var query = filters(websiteId);
      var selection =
          rollup.loader().select(websiteId, query.withEventType(Constants.CUSTOM_EVENT));
      var rows = new ArrayList<ObjectNode>();
      for (var reading : selection.readings()) {
        if (reading.event().properties().isEmpty()) {
          continue;
        }
        var row = Json.object();
        row.put("websiteId", websiteId);
        row.put("eventId", reading.event().id());
        row.put("eventName", reading.event().eventName());
        var properties = Json.array();
        for (var property : reading.event().properties()) {
          var entry = Json.object();
          entry.put("dataKey", property.key());
          entry.put("stringValue", property.stringValue());
          entry.put("numberValue", property.numberValue());
          entry.put("dateValue",
              property.dateValue() == null ? null : property.dateValue().toString());
          entry.put("dataType", property.dataType());
          entry.put("createdAt", reading.event().createdAt().toString());
          properties.add(entry);
        }
        row.set("eventProperties", properties);
        rows.add(row);
      }
      // This route rebuilds its own answer out of the paged one, and keeps only the four keys
      // it names.
      var body = page(rows, query, row -> row);
      body.remove("orderBy");
      body.remove("search");
      return Responses.json(body);
    });
  }

  @Get("/api/websites/{websiteId}/event-data/events")
  public HttpResponse eventDataEvents(String websiteId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canViewWebsiteSection(caller, websiteId, List.of("events")));
      var query = filters(websiteId);
      var out = Json.array();
      for (var row : insight.eventProperties(websiteId, query, null)) {
        var node = Json.object();
        node.put("eventName", row.eventName());
        node.put("propertyName", row.propertyName());
        node.put("dataType", row.dataType());
        node.put("total", row.total());
        out.add(node);
      }
      return Responses.json(out);
    });
  }

  @Get("/api/websites/{websiteId}/event-data/fields")
  public HttpResponse eventDataFields(String websiteId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canViewWebsiteSection(caller, websiteId, List.of("events")));
      var query = filters(websiteId);
      var out = Json.array();
      for (var row : insight.eventFields(websiteId, queryParam("eventName"), query)) {
        var node = Json.object();
        node.put("propertyName", row.propertyName());
        node.put("dataType", row.dataType());
        node.put("total", row.total());
        out.add(node);
      }
      return Responses.json(out);
    });
  }

  @Get("/api/websites/{websiteId}/event-data/properties")
  public HttpResponse eventDataProperties(String websiteId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canViewWebsiteSection(caller, websiteId, List.of("events")));
      var query = filters(websiteId);
      var out = Json.array();
      for (var row : insight.eventProperties(websiteId, query, queryParam("propertyName"))) {
        var node = Json.object();
        node.put("eventName", row.eventName());
        node.put("propertyName", row.propertyName());
        node.put("dataType", row.dataType());
        node.put("total", row.total());
        out.add(node);
      }
      return Responses.json(out);
    });
  }

  @Get("/api/websites/{websiteId}/event-data/stats")
  public HttpResponse eventDataStats(String websiteId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canViewWebsiteSection(caller, websiteId, List.of("events")));
      return Responses.json(insight.eventDataStats(websiteId, filters(websiteId)));
    });
  }

  @Get("/api/websites/{websiteId}/event-data/values")
  public HttpResponse eventDataValues(String websiteId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canViewWebsiteSection(caller, websiteId, List.of("events")));
      var propertyName = queryParam("propertyName");
      if (propertyName == null) {
        var error = Json.object();
        var properties = Json.object();
        var field = Json.object();
        var errors = Json.array();
        errors.add("Invalid input: expected string, received undefined");
        field.set("errors", errors);
        properties.set("propertyName", field);
        error.set("errors", Json.array());
        error.set("properties", properties);
        return Responses.badRequest(error);
      }
      return Responses.json(
          insight.eventDataValues(websiteId, queryParam("eventName"), filters(websiteId),
              propertyName, intParam(queryParam("dataType"))));
    });
  }

  @Get("/api/websites/{websiteId}/event-data/{eventId}")
  public HttpResponse eventDataById(String websiteId, String eventId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canViewWebsiteSection(caller, websiteId, List.of("events")));
      var event = store.findFact(io.akka.umami.application.Store.EVENT, eventId,
          io.akka.umami.domain.Traffic.Event.class);
      var out = Json.array();
      if (event != null && websiteId.equals(event.websiteId())) {
        for (var property : event.properties()) {
          var row = Json.object();
          row.put("websiteId", websiteId);
          row.put("eventId", event.id());
          row.put("eventName", event.eventName());
          row.put("dataKey", property.key());
          row.put("stringValue", property.stringValue());
          row.put("numberValue", property.numberValue());
          row.put("dateValue",
              property.dateValue() == null ? null : property.dateValue().toString());
          row.put("dataType", property.dataType());
          row.put("createdAt", event.createdAt().toString());
          out.add(row);
        }
      }
      return Responses.json(out);
    });
  }

  // ------------------------------------------------------------------ the pivot family

  private static final List<String> EVENTS = List.of("events");
  private static final List<String> SESSIONS = List.of("sessions");
  private static final List<String> METRICS = List.of("sum", "avg", "count");

  /** A query parameter a schema declares as required, refused the way a schema refuses it. */
  private String required(String name) {
    var value = queryParam(name);
    if (value == null) {
      throw new Refusal(Responses.badRequest(
          Schema.problem(name, "Invalid input: expected string, received undefined")));
    }
    return value;
  }

  /** The {@code pf_} filters, a different mechanism from the {@code epf}/{@code spf} ones. */
  private List<io.akka.umami.lib.Filters.PropertyFilter> scoped() {
    return io.akka.umami.lib.Filters.parseScopedPropertyFilters(query(), "pf");
  }

  private String metric() {
    var metric = queryParam("metric");
    if (metric == null) {
      return "sum";
    }
    if (!METRICS.contains(metric)) {
      throw new Refusal(
          Responses.badRequest(Schema.problem("metric", Schema.notAnOption(METRICS))));
    }
    return metric;
  }

  @Get("/api/websites/{websiteId}/event-data-pivot")
  public HttpResponse eventDataPivot(String websiteId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canViewWebsiteSection(caller, websiteId, EVENTS));
      var eventName = required("eventName");
      var query = filters(websiteId);
      var rows = pivot.eventPivot(websiteId, eventName, query, scoped());
      return Responses.json(analyticsPage(rows, query, Writers::eventPivotRow));
    });
  }

  @Get("/api/websites/{websiteId}/event-data-pivot/array-series")
  public HttpResponse eventDataArraySeries(String websiteId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canViewWebsiteSection(caller, websiteId, EVENTS));
      var eventName = required("eventName");
      var propertyName = required("propertyName");
      return Responses.json(
          pivot.eventArraySeries(websiteId, eventName, propertyName, filters(websiteId), scoped()));
    });
  }

  @Get("/api/websites/{websiteId}/event-data-pivot/date-series")
  public HttpResponse eventDataDateSeries(String websiteId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canViewWebsiteSection(caller, websiteId, EVENTS));
      var eventName = required("eventName");
      var propertyName = required("propertyName");
      return Responses.json(
          pivot.eventDateSeries(websiteId, eventName, propertyName, filters(websiteId), scoped()));
    });
  }

  @Get("/api/websites/{websiteId}/event-data-pivot/numeric-series")
  public HttpResponse eventDataNumericSeries(String websiteId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canViewWebsiteSection(caller, websiteId, EVENTS));
      var eventName = required("eventName");
      var propertyName = required("propertyName");
      var metric = metric();
      return Responses.json(
          pivot.eventNumericSeries(websiteId, eventName, propertyName, metric, filters(websiteId),
              scoped()));
    });
  }

  @Get("/api/websites/{websiteId}/event-data-pivot/numeric-stats")
  public HttpResponse eventDataNumericStats(String websiteId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canViewWebsiteSection(caller, websiteId, EVENTS));
      var eventName = required("eventName");
      var propertyName = required("propertyName");
      return Responses.json(
          pivot.eventNumericStats(websiteId, eventName, propertyName, filters(websiteId),
              scoped()));
    });
  }

  @Get("/api/websites/{websiteId}/event-data-pivot/property-series")
  public HttpResponse eventDataPropertySeries(String websiteId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canViewWebsiteSection(caller, websiteId, EVENTS));
      var eventName = required("eventName");
      var propertyName = required("propertyName");
      return Responses.json(
          pivot.eventPropertySeries(websiteId, eventName, propertyName, filters(websiteId),
              scoped()));
    });
  }

  @Get("/api/websites/{websiteId}/session-data-pivot")
  public HttpResponse sessionDataPivot(String websiteId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canViewWebsiteSection(caller, websiteId, SESSIONS));
      var propertyName = required("propertyName");
      var query = filters(websiteId);
      var rows = pivot.sessionPivot(websiteId, propertyName, query, scoped());
      return Responses.json(analyticsPage(rows, query, Writers::sessionPivotRow));
    });
  }

  @Get("/api/websites/{websiteId}/session-data/array-series")
  public HttpResponse sessionDataArraySeries(String websiteId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canViewWebsiteSection(caller, websiteId, SESSIONS));
      var propertyName = required("propertyName");
      return Responses.json(
          pivot.sessionArraySeries(websiteId, propertyName, filters(websiteId), scoped()));
    });
  }

  @Get("/api/websites/{websiteId}/session-data/date-series")
  public HttpResponse sessionDataDateSeries(String websiteId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canViewWebsiteSection(caller, websiteId, SESSIONS));
      var propertyName = required("propertyName");
      return Responses.json(
          pivot.sessionDateSeries(websiteId, propertyName, filters(websiteId), scoped()));
    });
  }

  @Get("/api/websites/{websiteId}/session-data/numeric-series")
  public HttpResponse sessionDataNumericSeries(String websiteId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canViewWebsiteSection(caller, websiteId, SESSIONS));
      var propertyName = required("propertyName");
      var metric = metric();
      return Responses.json(
          pivot.sessionNumericSeries(websiteId, propertyName, metric, filters(websiteId),
              scoped()));
    });
  }

  @Get("/api/websites/{websiteId}/session-data/numeric-stats")
  public HttpResponse sessionDataNumericStats(String websiteId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canViewWebsiteSection(caller, websiteId, SESSIONS));
      var propertyName = required("propertyName");
      return Responses.json(
          pivot.sessionNumericStats(websiteId, propertyName, filters(websiteId), scoped()));
    });
  }

  @Get("/api/websites/{websiteId}/session-data/property-series")
  public HttpResponse sessionDataPropertySeries(String websiteId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canViewWebsiteSection(caller, websiteId, SESSIONS));
      var propertyName = required("propertyName");
      return Responses.json(
          pivot.sessionPropertySeries(websiteId, propertyName, filters(websiteId), scoped()));
    });
  }

  @Get("/api/websites/{websiteId}/session-data/stats")
  public HttpResponse sessionDataStats(String websiteId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canViewWebsiteSection(caller, websiteId, SESSIONS));
      var propertyName = required("propertyName");
      return Responses.json(
          pivot.sessionActivityStats(websiteId, propertyName, filters(websiteId), scoped()));
    });
  }

  // ------------------------------------------------------------------ session properties

  @Get("/api/websites/{websiteId}/session-data/properties")
  public HttpResponse sessionDataProperties(String websiteId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canViewWebsiteSection(caller, websiteId, List.of("sessions")));
      return Responses.json(
          insight.sessionPropertyNames(websiteId, filters(websiteId),
              queryParam("propertyName")));
    });
  }

  @Get("/api/websites/{websiteId}/session-data/values")
  public HttpResponse sessionDataValues(String websiteId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canViewWebsiteSection(caller, websiteId, List.of("sessions")));
      var propertyName = queryParam("propertyName");
      var dataType = intParam(queryParam("dataType"));
      var counts = new java.util.LinkedHashMap<String, long[]>();
      for (var entry : insight.sessionPropertyValues(websiteId, filters(websiteId)).entrySet()) {
        var events = entry.getValue().events();
        for (var property : entry.getValue().properties()) {
          if (propertyName != null && !propertyName.equals(property.key())) {
            continue;
          }
          if (dataType != null && property.dataType() != dataType) {
            continue;
          }
          if (property.dataType() == Constants.DATA_ARRAY) {
            var array = Json.readArray(property.stringValue());
            if (array != null) {
              array.forEach(element ->
                  counts.computeIfAbsent(element.asText(), v -> new long[1])[0] += events);
            }
            continue;
          }
          var value = io.akka.umami.lib.Values.displayValue(property);
          if (value != null) {
            counts.computeIfAbsent(value, v -> new long[1])[0] += events;
          }
        }
      }
      var ordered = new ArrayList<>(counts.entrySet());
      ordered.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
      var out = Json.array();
      for (var entry : ordered.subList(0, Math.min(Constants.METRIC_LIMIT, ordered.size()))) {
        var row = Json.object();
        row.put("value", entry.getKey());
        row.put("total", entry.getValue()[0]);
        out.add(row);
      }
      return Responses.json(out);
    });
  }

  // ------------------------------------------------------------------ sessions

  @Get("/api/websites/{websiteId}/sessions")
  public HttpResponse sessions(String websiteId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canViewWebsiteSection(caller, websiteId, List.of("sessions")));
      var query = filters(websiteId);
      var rows = new ArrayList<>(insight.sessions(websiteId, query));
      var search = query.search();
      if (search != null && !search.isBlank()) {
        rows.removeIf(row ->
            !AccountEndpoint.containsIgnoringCase(row.browser(), search)
                && !AccountEndpoint.containsIgnoringCase(row.os(), search)
                && !AccountEndpoint.containsIgnoringCase(row.device(), search)
                && !AccountEndpoint.containsIgnoringCase(row.city(), search));
      }
      return Responses.json(analyticsPage(rows, query, Writers::sessionRow));
    });
  }

  @Get("/api/websites/{websiteId}/sessions/stats")
  public HttpResponse sessionStats(String websiteId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canViewWebsiteSection(caller, websiteId, List.of("sessions")));
      return Responses.json(insight.sessionStats(websiteId, filters(websiteId)));
    });
  }

  @Get("/api/websites/{websiteId}/sessions/weekly")
  public HttpResponse weekly(String websiteId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canViewWebsiteSection(caller, websiteId,
          List.of("overview", "sessions")));
      var query = filters(websiteId);
      return Responses.json(insight.weeklyTraffic(websiteId, query, queryParam("timezone")));
    });
  }

  private static final List<String> SESSION_SECTIONS =
      List.of("sessions", "events", "realtime", "revenue");

  @Get("/api/websites/{websiteId}/sessions/{sessionId}")
  public HttpResponse session(String websiteId, String sessionId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canViewWebsiteSection(caller, websiteId, SESSION_SECTIONS));
      var body = insight.session(websiteId, sessionId);
      if (body == null) {
        return Responses.notFound();
      }
      body.put("canDelete", permissions.canDeleteWebsite(caller, websiteId));
      body.put("stitchedSessionCount", stitched(websiteId, sessionId).size());
      return Responses.json(body);
    });
  }

  /** Every session that shares this one's distinct identifier, including this one. */
  private List<String> stitched(String websiteId, String sessionId) {
    var out = new java.util.LinkedHashSet<String>();
    out.add(sessionId);
    for (var link : store.identityLinksOfSession(websiteId, sessionId)) {
      for (var sibling : store.identityLinks(websiteId, link.distinctId())) {
        out.add(sibling.sessionId());
      }
    }
    return new ArrayList<>(out);
  }

  @akka.javasdk.annotations.http.Delete("/api/websites/{websiteId}/sessions/{sessionId}")
  public HttpResponse deleteSession(String websiteId, String sessionId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canDeleteWebsite(caller, websiteId));
      if (!collect.purgeSession(websiteId, sessionId)) {
        return Responses.notFound();
      }
      return Responses.ok();
    });
  }

  @Get("/api/websites/{websiteId}/sessions/{sessionId}/activity")
  public HttpResponse sessionActivity(String websiteId, String sessionId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canViewWebsiteSection(caller, websiteId, SESSION_SECTIONS));
      var sessions = stitched(websiteId, sessionId);
      var start = parseInstant(queryParam("startAt")) == null
          ? java.time.Instant.EPOCH
          : java.time.Instant.ofEpochMilli(longParam(queryParam("startAt")));
      var end = queryParam("endAt") == null
          ? java.time.Instant.now()
          : java.time.Instant.ofEpochMilli(longParam(queryParam("endAt")));
      // Where a session was stitched to others, the window widens to hold all of them.
      if (sessions.size() > 1) {
        java.time.Instant earliest = null;
        java.time.Instant latest = null;
        for (var id : sessions) {
          var session = store.session(websiteId, id);
          if (session == null || session.createdAt() == null) {
            continue;
          }
          if (earliest == null || session.createdAt().isBefore(earliest)) {
            earliest = session.createdAt();
          }
          if (latest == null || session.createdAt().isAfter(latest)) {
            latest = session.createdAt();
          }
        }
        if (earliest != null) {
          start = earliest.atZone(java.time.ZoneOffset.UTC).withDayOfMonth(1)
              .truncatedTo(java.time.temporal.ChronoUnit.DAYS).toInstant();
        }
        if (latest != null) {
          end = latest.atZone(java.time.ZoneOffset.UTC).withDayOfMonth(1).plusMonths(1)
              .truncatedTo(java.time.temporal.ChronoUnit.DAYS).toInstant();
        }
      }
      return Responses.json(insight.sessionActivity(websiteId, sessions, start, end));
    });
  }

  @Get("/api/websites/{websiteId}/sessions/{sessionId}/properties")
  public HttpResponse sessionPropertiesOf(String websiteId, String sessionId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canViewWebsiteSection(caller, websiteId, SESSION_SECTIONS));
      return Responses.json(insight.sessionProperties(websiteId, sessionId));
    });
  }

  // ------------------------------------------------------------------ the rest

  @Get("/api/websites/{websiteId}/daterange")
  public HttpResponse dateRange(String websiteId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canViewSharedWebsite(caller, websiteId));
      return Responses.json(insight.dateRange(websiteId));
    });
  }

  @Get("/api/websites/{websiteId}/active")
  public HttpResponse active(String websiteId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canViewWebsiteSection(caller, websiteId,
          List.of("overview", "realtime")));
      var body = Json.object();
      body.put("visitors", insight.activeVisitors(websiteId, java.time.Instant.now()));
      return Responses.json(body);
    });
  }

  private static int orDefault(Integer value, int fallback) {
    return value == null ? fallback : value;
  }
}
