package io.akka.umami.analytics;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.akka.umami.application.Store;
import io.akka.umami.domain.Traffic;
import io.akka.umami.lib.Constants;
import io.akka.umami.lib.Dates;
import io.akka.umami.lib.Filters;
import io.akka.umami.lib.Json;
import io.akka.umami.lib.Values;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** What is happening now, what a session did, and what the properties on either hold. */
public final class Insight {

  private final Store store;
  private final Loader loader;
  private final Rollup rollup;

  public Insight(Store store) {
    this.store = store;
    this.rollup = new Rollup(store);
    this.loader = rollup.loader();
  }

  // ------------------------------------------------------------------ now

  /** Distinct sessions in the last five minutes, with no event-type restriction. SPEC R77. */
  public long activeVisitors(String websiteId, Instant now) {
    var from = now.minus(Constants.ACTIVE_VISITOR_MINUTES, ChronoUnit.MINUTES);
    var sessions = new HashSet<String>();
    for (var event : store.events(websiteId, from, now)) {
      sessions.add(event.sessionId());
    }
    return sessions.size();
  }

  /** The last hundred events, newest first, excluding the vitals rows. */
  public List<Traffic.Reading> activity(String websiteId, Filters.Query query) {
    var selection = loader.select(websiteId, query);
    var readings = new ArrayList<Traffic.Reading>();
    for (var reading : selection.readings()) {
      if (reading.event().eventType() != Constants.PERFORMANCE_EVENT) {
        readings.add(reading);
      }
    }
    readings.sort(Comparator.comparing((Traffic.Reading r) -> r.event().createdAt()).reversed());
    return readings.size() > 100 ? readings.subList(0, 100) : readings;
  }

  /**
   * The whole live payload.
   *
   * <p>Walked oldest first: a country is counted the first time each session is seen, and the page
   * and the referrer are counted every time. SPEC R79.
   */
  public ObjectNode realtime(String websiteId, Filters.Query query, Instant now) {
    var activity = new ArrayList<>(activity(websiteId, query));
    java.util.Collections.reverse(activity);

    var countries = Json.object();
    var urls = Json.object();
    var referrers = Json.object();
    var events = Json.array();
    var seenSessions = new HashSet<String>();
    long eventCount = 0;
    for (var reading : activity) {
      var event = reading.event();
      if (seenSessions.add(event.sessionId())) {
        var country = reading.session() == null ? null : reading.session().country();
        if (country != null && !country.isEmpty()) {
          countries.put(country, countries.path(country).asLong(0) + 1);
        }
        events.add(activityRow(reading, "session"));
      }
      if (event.urlPath() != null && !event.urlPath().isEmpty()) {
        urls.put(event.urlPath(), urls.path(event.urlPath()).asLong(0) + 1);
      }
      if (event.referrerDomain() != null && !event.referrerDomain().isEmpty()) {
        referrers.put(event.referrerDomain(), referrers.path(event.referrerDomain()).asLong(0) + 1);
      }
      boolean named = event.eventName() != null && !event.eventName().isEmpty();
      if (named) {
        eventCount++;
      }
      events.add(activityRow(reading, named ? "event" : "pageview"));
    }
    var newest = Json.array();
    for (int i = events.size() - 1; i >= 0; i--) {
      newest.add(events.get(i));
    }

    var pageviews = rollup.pageviewSeries(websiteId, query);
    var sessionSeries = rollup.sessionSeries(websiteId, query);
    long views = pageviews.stream().mapToLong(Rollup.Point::y).sum();
    long visitors = sessionSeries.stream().mapToLong(Rollup.Point::y).sum();

    var series = Json.object();
    series.set("views", points(pageviews));
    series.set("visitors", points(sessionSeries));

    var totals = Json.object();
    totals.put("views", views);
    totals.put("visitors", visitors);
    totals.put("events", eventCount);
    totals.put("countries", countries.size());

    var out = Json.object();
    out.set("countries", countries);
    out.set("urls", urls);
    out.set("referrers", referrers);
    out.set("events", newest);
    out.set("series", series);
    out.set("totals", totals);
    out.put("timestamp", now.toEpochMilli());
    return out;
  }

  private static ArrayNode points(List<Rollup.Point> series) {
    var out = Json.array();
    for (var point : series) {
      var row = Json.object();
      row.put("x", point.x());
      row.put("y", point.y());
      out.add(row);
    }
    return out;
  }

  private static ObjectNode activityRow(Traffic.Reading reading, String kind) {
    var row = Json.object();
    row.put("__type", kind);
    row.put("sessionId", reading.event().sessionId());
    row.put("eventName", reading.event().eventName());
    row.put("createdAt", reading.event().createdAt().toString());
    row.put("urlPath", reading.event().urlPath());
    row.put("referrerDomain", reading.event().referrerDomain());
    row.put("hostname", reading.event().hostname());
    if (reading.session() != null) {
      row.put("browser", reading.session().browser());
      row.put("os", reading.session().os());
      row.put("device", reading.session().device());
      row.put("country", reading.session().country());
    }
    return row;
  }

  // ------------------------------------------------------------------ sessions

  public record SessionRow(
      String id,
      String websiteId,
      String hostname,
      String browser,
      String os,
      String device,
      String screen,
      String language,
      String country,
      String region,
      String city,
      Instant firstAt,
      Instant lastAt,
      long visits,
      long views,
      long events,
      Instant createdAt) {}

  /** One row per session and host name, which is what grouping by the event's host produces. */
  public List<SessionRow> sessions(String websiteId, Filters.Query query) {
    var selection = loader.select(websiteId, query);
    var grouped = new LinkedHashMap<String, List<Traffic.Reading>>();
    for (var reading : selection.readings()) {
      if (reading.event().eventType() == Constants.PERFORMANCE_EVENT) {
        continue;
      }
      grouped
          .computeIfAbsent(
              reading.event().sessionId() + "|" + Rollup.nullToEmpty(reading.event().hostname()),
              k -> new ArrayList<>())
          .add(reading);
    }
    var out = new ArrayList<SessionRow>();
    for (var entry : grouped.entrySet()) {
      var readings = entry.getValue();
      var session = readings.get(0).session();
      Instant first = null;
      Instant last = null;
      long views = 0;
      long events = 0;
      var visits = new HashSet<String>();
      for (var reading : readings) {
        var at = reading.event().createdAt();
        if (first == null || at.isBefore(first)) {
          first = at;
        }
        if (last == null || at.isAfter(last)) {
          last = at;
        }
        visits.add(reading.event().visitId());
        if (reading.event().eventType() == Constants.PAGE_VIEW) {
          views++;
        }
        if (reading.event().isNamed()) {
          events++;
        }
      }
      out.add(
          new SessionRow(
              readings.get(0).event().sessionId(),
              websiteId,
              readings.get(0).event().hostname(),
              session == null ? null : session.browser(),
              session == null ? null : session.os(),
              session == null ? null : session.device(),
              session == null ? null : session.screen(),
              session == null ? null : session.language(),
              session == null ? null : session.country(),
              session == null ? null : session.region(),
              session == null ? null : session.city(),
              first,
              last,
              visits.size(),
              views,
              events,
              last));
    }
    out.sort(Comparator.comparing(SessionRow::createdAt).reversed());
    return out;
  }

  /** One session in full. The date range is not consulted at all. SPEC R80. */
  public ObjectNode session(String websiteId, String sessionId) {
    var session = store.session(websiteId, sessionId);
    if (session == null) {
      return null;
    }
    var events = store.factsBySession(Store.EVENT, websiteId, sessionId, Traffic.Event.class);
    Instant first = null;
    Instant last = null;
    long views = 0;
    long named = 0;
    long totaltime = 0;
    var visits = new LinkedHashMap<String, Instant[]>();
    for (var event : events) {
      if (event.eventType() == Constants.PERFORMANCE_EVENT) {
        continue;
      }
      var bounds = visits.computeIfAbsent(event.visitId(), v -> new Instant[2]);
      if (bounds[0] == null || event.createdAt().isBefore(bounds[0])) {
        bounds[0] = event.createdAt();
      }
      if (bounds[1] == null || event.createdAt().isAfter(bounds[1])) {
        bounds[1] = event.createdAt();
      }
      if (first == null || event.createdAt().isBefore(first)) {
        first = event.createdAt();
      }
      if (last == null || event.createdAt().isAfter(last)) {
        last = event.createdAt();
      }
      if (event.eventType() == Constants.PAGE_VIEW) {
        views++;
      }
      if (event.isNamed()) {
        named++;
      }
    }
    for (var bounds : visits.values()) {
      if (bounds[0] != null && bounds[1] != null) {
        totaltime += Math.max(0, (bounds[1].toEpochMilli() - bounds[0].toEpochMilli()) / 1000);
      }
    }
    var out = Json.object();
    out.put("id", session.id());
    out.put("websiteId", websiteId);
    out.put("distinctId", session.distinctId());
    out.put("browser", session.browser());
    out.put("os", session.os());
    out.put("device", session.device());
    out.put("screen", session.screen());
    out.put("language", session.language());
    out.put("country", session.country());
    out.put("region", session.region());
    out.put("city", session.city());
    out.put("firstAt", first == null ? null : first.toString());
    out.put("lastAt", last == null ? null : last.toString());
    out.put("visits", visits.size());
    out.put("views", views);
    out.put("events", named);
    out.put("totaltime", totaltime);
    return out;
  }

  /** The last five hundred events of the named sessions, newest first. */
  public ArrayNode sessionActivity(String websiteId, List<String> sessionIds, Instant from,
      Instant to) {
    var readings = new ArrayList<Traffic.Event>();
    for (var sessionId : sessionIds) {
      for (var event : store.factsBySession(Store.EVENT, websiteId, sessionId,
          Traffic.Event.class)) {
        if (event.eventType() == Constants.PERFORMANCE_EVENT) {
          continue;
        }
        if (event.createdAt().isBefore(from) || event.createdAt().isAfter(to)) {
          continue;
        }
        readings.add(event);
      }
    }
    readings.sort(Comparator.comparing(Traffic.Event::createdAt).reversed());
    var out = Json.array();
    for (var event : readings.subList(0, Math.min(500, readings.size()))) {
      var row = Json.object();
      row.put("createdAt", event.createdAt().toString());
      row.put("urlPath", event.urlPath());
      row.put("urlQuery", event.urlQuery());
      row.put("referrerDomain", event.referrerDomain());
      row.put("eventId", event.id());
      row.put("eventType", event.eventType());
      row.put("eventName", event.eventName());
      row.put("visitId", event.visitId());
      row.put("hostname", event.hostname());
      row.put("hasData", !event.properties().isEmpty());
      out.add(row);
    }
    return out;
  }

  // ------------------------------------------------------------------ the event list

  /** The events of a window, newest first, with whether each carries properties. */
  public List<Traffic.Reading> eventList(String websiteId, Filters.Query query, String search) {
    var selection = loader.select(websiteId, query);
    var out = new ArrayList<Traffic.Reading>();
    for (var reading : selection.readings()) {
      if (reading.event().eventType() == Constants.PERFORMANCE_EVENT) {
        continue;
      }
      if (search != null && !search.isBlank() && !matchesEventSearch(reading, search)) {
        continue;
      }
      out.add(reading);
    }
    out.sort(Comparator.comparing((Traffic.Reading r) -> r.event().createdAt()).reversed());
    return out;
  }

  private static boolean matchesEventSearch(Traffic.Reading reading, String search) {
    var lower = search.toLowerCase(Locale.ROOT);
    var event = reading.event();
    if (event.eventType() == Constants.CUSTOM_EVENT) {
      return event.eventName() != null
          && event.eventName().toLowerCase(Locale.ROOT).contains(lower);
    }
    if (event.eventType() == Constants.PAGE_VIEW) {
      return event.urlPath() != null && event.urlPath().toLowerCase(Locale.ROOT).contains(lower);
    }
    return false;
  }

  /** The four figures the event screen's header shows. */
  public ObjectNode eventStats(String websiteId, Filters.Query query) {
    var selection = loader.select(websiteId, query.withEventType(Constants.CUSTOM_EVENT));
    var visitors = new HashSet<String>();
    var visits = new HashSet<String>();
    var names = new HashSet<String>();
    long events = 0;
    for (var reading : selection.readings()) {
      events++;
      visitors.add(reading.event().sessionId());
      visits.add(reading.event().visitId());
      if (reading.event().eventName() != null) {
        names.add(reading.event().eventName());
      }
    }
    var out = Json.object();
    out.put("events", events);
    out.put("visitors", visitors.size());
    out.put("visits", visits.size());
    out.put("uniqueEvents", names.size());
    return out;
  }

  /** The five figures the session screen's header shows. */
  public ObjectNode sessionStats(String websiteId, Filters.Query query) {
    var selection = loader.select(websiteId, query);
    long pageviews = 0;
    long events = 0;
    var visitors = new HashSet<String>();
    var visits = new HashSet<String>();
    var countries = new HashSet<String>();
    for (var reading : selection.readings()) {
      if (reading.event().eventType() == Constants.PERFORMANCE_EVENT) {
        continue;
      }
      if (reading.event().eventType() == Constants.PAGE_VIEW) {
        pageviews++;
      }
      if (reading.event().isNamed()) {
        events++;
      }
      visitors.add(reading.event().sessionId());
      visits.add(reading.event().visitId());
      if (reading.session() != null && reading.session().country() != null
          && !reading.session().country().isEmpty()) {
        countries.add(reading.session().country());
      }
    }
    var out = Json.object();
    out.set("pageviews", value(pageviews));
    out.set("visitors", value(visitors.size()));
    out.set("visits", value(visits.size()));
    out.set("countries", value(countries.size()));
    out.set("events", value(events));
    return out;
  }

  private static ObjectNode value(long number) {
    var out = Json.object();
    out.put("value", number);
    return out;
  }

  /** Sessions by day of the week and hour, as a dense seven by twenty-four grid. */
  public ArrayNode weeklyTraffic(String websiteId, Filters.Query query, String timezone) {
    var selection = loader.select(websiteId, query);
    var grid = new LinkedHashMap<String, Set<String>>();
    for (var reading : selection.readings()) {
      if (!reading.event().isViewLike()) {
        continue;
      }
      grid.computeIfAbsent(Dates.weeklyKey(reading.event().createdAt(), timezone),
          k -> new HashSet<>()).add(reading.event().sessionId());
    }
    var out = Json.array();
    for (int day = 0; day < 7; day++) {
      var row = Json.array();
      for (int hour = 0; hour < 24; hour++) {
        var key = day + ":" + String.format("%02d", hour);
        row.add(grid.containsKey(key) ? grid.get(key).size() : 0);
      }
      out.add(row);
    }
    return out;
  }

  /** The earliest and latest instants a website ever recorded. */
  public ObjectNode dateRange(String websiteId) {
    Instant first = null;
    Instant last = null;
    for (var event : store.events(websiteId, Instant.parse(Constants.DEFAULT_RESET_DATE),
        Instant.now().plus(365, ChronoUnit.DAYS))) {
      if (first == null || event.createdAt().isBefore(first)) {
        first = event.createdAt();
      }
      if (last == null || event.createdAt().isAfter(last)) {
        last = event.createdAt();
      }
    }
    var out = Json.object();
    out.put("startDate", first == null ? null : first.toString());
    out.put("endDate", last == null ? null : last.toString());
    return out;
  }

  /** The sparkline behind each row of the website list, in twelve-hour buckets. */
  public ObjectNode listCharts(List<String> websiteIds, Instant from, Instant to, String timezone,
      Integer eventType) {
    var out = Json.object();
    var labels = bucketLabels(from, to, timezone);
    for (var websiteId : websiteIds) {
      var counts = new LinkedHashMap<String, Set<String>>();
      var total = new HashSet<String>();
      for (var event : store.events(websiteId, from, to)) {
        if (eventType != null ? event.eventType() != eventType : !event.isViewLike()) {
          continue;
        }
        counts.computeIfAbsent(bucketLabel(event.createdAt(), timezone), b -> new HashSet<>())
            .add(event.sessionId());
        total.add(event.sessionId());
      }
      var values = Json.array();
      for (var label : labels) {
        values.add(counts.containsKey(label) ? counts.get(label).size() : 0);
      }
      var row = Json.object();
      row.set("values", values);
      row.put("total", total.size());
      out.set(websiteId, row);
    }
    var wrapper = Json.object();
    wrapper.set("data", out);
    return wrapper;
  }

  /**
   * One slot every twelve hours from the start of the window, each labelled with its own hour.
   *
   * <p>The label is not rounded down to a twelve-hour boundary the way an event's is, so a
   * window that does not begin on such a boundary produces slots no event can land in. The
   * list a website's chart is drawn from is asked for over whole days, where the two agree.
   */
  private static List<String> bucketLabels(Instant from, Instant to, String timezone) {
    var out = new ArrayList<String>();
    var zone = Dates.zone(timezone);
    for (var cursor = from; !cursor.isAfter(to);
        cursor = cursor.plus(Constants.CHART_BUCKET_HOURS, ChronoUnit.HOURS)) {
      var when = cursor.atZone(zone);
      out.add(String.format("%04d-%02d-%02d %02d:00:00", when.getYear(), when.getMonthValue(),
          when.getDayOfMonth(), when.getHour()));
    }
    return out;
  }

  private static String bucketLabel(Instant instant, String timezone) {
    var when = instant.atZone(Dates.zone(timezone));
    int half = (when.getHour() / Constants.CHART_BUCKET_HOURS) * Constants.CHART_BUCKET_HOURS;
    return String.format("%04d-%02d-%02d %02d:00:00", when.getYear(), when.getMonthValue(),
        when.getDayOfMonth(), half);
  }

  // ------------------------------------------------------------------ properties

  public record PropertyRow(String eventName, String propertyName, int dataType, long total) {}

  public List<PropertyRow> eventProperties(String websiteId, Filters.Query query,
      String propertyName) {
    var selection = loader.select(websiteId, query.withEventType(Constants.CUSTOM_EVENT));
    var counts = new LinkedHashMap<String, long[]>();
    var labels = new LinkedHashMap<String, PropertyRow>();
    for (var reading : selection.readings()) {
      for (var property : reading.event().properties()) {
        if (propertyName != null && !propertyName.equals(property.key())) {
          continue;
        }
        var name = Rollup.nullToEmpty(reading.event().eventName());
        var key = name + "|" + property.key() + "|" + property.dataType();
        counts.computeIfAbsent(key, k -> new long[1])[0]++;
        labels.put(key, new PropertyRow(name, property.key(), property.dataType(), 0));
      }
    }
    var out = new ArrayList<PropertyRow>();
    for (var entry : labels.entrySet()) {
      var row = entry.getValue();
      out.add(new PropertyRow(row.eventName(), row.propertyName(), row.dataType(),
          counts.get(entry.getKey())[0]));
    }
    out.sort(Comparator.comparingLong(PropertyRow::total).reversed());
    return out.size() > Constants.METRIC_LIMIT ? out.subList(0, Constants.METRIC_LIMIT) : out;
  }

  /** The property names one event carries, ordered by how often, then by name. */
  public List<PropertyRow> eventFields(String websiteId, String eventName, Filters.Query query) {
    var rows = eventProperties(websiteId, query, null);
    var counts = new LinkedHashMap<String, long[]>();
    var types = new LinkedHashMap<String, Integer>();
    for (var row : rows) {
      if (eventName != null && !eventName.equals(row.eventName())) {
        continue;
      }
      counts.computeIfAbsent(row.propertyName(), k -> new long[1])[0] += row.total();
      types.put(row.propertyName(), row.dataType());
    }
    var out = new ArrayList<PropertyRow>();
    counts.forEach((name, total) ->
        out.add(new PropertyRow(null, name, types.get(name), total[0])));
    out.sort(
        Comparator.comparingLong(PropertyRow::total).reversed()
            .thenComparing(PropertyRow::propertyName));
    return out;
  }

  public ObjectNode eventDataStats(String websiteId, Filters.Query query) {
    var selection = loader.select(websiteId, query.withEventType(Constants.CUSTOM_EVENT));
    var events = new HashSet<String>();
    var keys = new HashSet<String>();
    long records = 0;
    for (var reading : selection.readings()) {
      for (var property : reading.event().properties()) {
        events.add(reading.event().id());
        keys.add(property.key());
        records++;
      }
    }
    var out = Json.object();
    out.put("events", events.size());
    out.put("properties", keys.size());
    out.put("records", records);
    return out;
  }

  /** The values one property took, and how often. An array property is expanded element by element. */
  public ArrayNode eventDataValues(String websiteId, String eventName, Filters.Query query,
      String propertyName, Integer dataType) {
    var selection = loader.select(websiteId, query.withEventType(Constants.CUSTOM_EVENT));
    var counts = new LinkedHashMap<String, long[]>();
    for (var reading : selection.readings()) {
      if (eventName != null && !eventName.equals(reading.event().eventName())) {
        continue;
      }
      for (var property : reading.event().properties()) {
        if (!property.key().equals(propertyName)) {
          continue;
        }
        if (dataType != null && property.dataType() != dataType) {
          continue;
        }
        if (property.dataType() == Constants.DATA_ARRAY) {
          var array = Json.readArray(property.stringValue());
          if (array != null) {
            array.forEach(element ->
                counts.computeIfAbsent(element.asText(), v -> new long[1])[0]++);
          }
          continue;
        }
        var value = displayed(property, query.timezone());
        if (value != null) {
          counts.computeIfAbsent(value, v -> new long[1])[0]++;
        }
      }
    }
    var ordered = new ArrayList<>(counts.entrySet());
    ordered.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
    var out = Json.array();
    for (var entry : ordered.subList(0, Math.min(100, ordered.size()))) {
      var row = Json.object();
      row.put("value", entry.getKey());
      row.put("total", entry.getValue()[0]);
      out.add(row);
    }
    return out;
  }

  /** A date-typed property reads back as its hour, not as the instant it was written. */
  static String displayed(Values.Property property, String timezone) {
    if (property.dataType() == Constants.DATA_DATE && property.dateValue() != null) {
      return Dates.bucket(property.dateValue(), "hour", timezone);
    }
    return Values.displayValue(property);
  }

  public ObjectNode numericStats(List<BigDecimal> values) {
    var out = Json.object();
    if (values.isEmpty()) {
      out.put("total", BigDecimal.ZERO);
      out.put("average", BigDecimal.ZERO);
      out.put("median", BigDecimal.ZERO);
      out.put("max", BigDecimal.ZERO);
      out.put("min", BigDecimal.ZERO);
      return out;
    }
    var sorted = new ArrayList<>(values);
    sorted.sort(BigDecimal::compareTo);
    var total = BigDecimal.ZERO;
    for (var value : sorted) {
      total = total.add(value);
    }
    out.put("total", total);
    out.put("average", total.divide(BigDecimal.valueOf(sorted.size()), 6,
        java.math.RoundingMode.HALF_UP));
    out.put("median", BigDecimal.valueOf(
        Reports.percentile(sorted.stream().map(BigDecimal::doubleValue).toList(), 0.5)));
    out.put("max", sorted.get(sorted.size() - 1));
    out.put("min", sorted.get(0));
    return out;
  }

  /** The properties one session carries, ordered by key. */
  public ArrayNode sessionProperties(String websiteId, String sessionId) {
    var rows = store.sessionProperties(websiteId, sessionId);
    var ordered = new ArrayList<>(rows);
    ordered.sort(Comparator.comparing(row -> row.property().key()));
    var out = Json.array();
    for (var row : ordered) {
      var node = Json.object();
      node.put("websiteId", row.websiteId());
      node.put("sessionId", row.sessionId());
      node.put("dataKey", row.property().key());
      node.put("dataType", row.property().dataType());
      node.put("stringValue", Values.displayValue(row.property()));
      node.put("numberValue", row.property().numberValue());
      node.put("dateValue",
          row.property().dateValue() == null ? null : row.property().dateValue().toString());
      node.put("createdAt", row.createdAt() == null ? null : row.createdAt().toString());
      out.add(node);
    }
    return out;
  }

  /** Which session properties the sessions in this window carry, and how many sessions each. */
  public ArrayNode sessionPropertyNames(String websiteId, Filters.Query query,
      String propertyName) {
    var selection = loader.select(websiteId, query);
    var sessions = new HashSet<String>();
    selection.readings().forEach(reading -> sessions.add(reading.event().sessionId()));
    var counts = new LinkedHashMap<String, Set<String>>();
    var types = new HashMap<String, Integer>();
    for (var sessionId : sessions) {
      for (var row : store.sessionProperties(websiteId, sessionId)) {
        if (propertyName != null && !propertyName.equals(row.property().key())) {
          continue;
        }
        counts.computeIfAbsent(row.property().key(), k -> new HashSet<>()).add(sessionId);
        types.put(row.property().key(), row.property().dataType());
      }
    }
    var ordered = new ArrayList<>(counts.entrySet());
    ordered.sort(
        Comparator.<Map.Entry<String, Set<String>>>comparingInt(e -> e.getValue().size())
            .reversed()
            .thenComparing(Map.Entry::getKey));
    var out = Json.array();
    for (var entry : ordered.subList(0, Math.min(Constants.METRIC_LIMIT, ordered.size()))) {
      var row = Json.object();
      row.put("propertyName", entry.getKey());
      row.put("dataType", types.get(entry.getKey()));
      row.put("total", entry.getValue().size());
      out.add(row);
    }
    return out;
  }

  /** Every session property of this window, gathered by session. */
  /**
   * A session's properties together with how many of its events the window holds.
   *
   * <p>The count matters: the values a property takes are counted per event, not per session,
   * because the query joins the property table onto the event table and counts the rows.
   */
  public record SessionProperties(long events, List<Values.Property> properties) {}

  public Map<String, SessionProperties> sessionPropertyValues(String websiteId,
      Filters.Query query) {
    var selection = loader.select(websiteId, query);
    var counts = new LinkedHashMap<String, long[]>();
    selection.readings().forEach(reading ->
        counts.computeIfAbsent(reading.event().sessionId(), s -> new long[1])[0]++);
    var out = new LinkedHashMap<String, SessionProperties>();
    for (var entry : counts.entrySet()) {
      out.put(
          entry.getKey(),
          new SessionProperties(
              entry.getValue()[0],
              store.sessionProperties(websiteId, entry.getKey()).stream()
                  .map(Traffic.SessionProperty::property)
                  .toList()));
    }
    return out;
  }

  public Rollup rollup() {
    return rollup;
  }

  public Loader loader() {
    return loader;
  }

  public Store store() {
    return store;
  }
}
