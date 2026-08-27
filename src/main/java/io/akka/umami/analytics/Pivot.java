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
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The thirteen answers about one property at a time: what it held, when, and how much of it.
 *
 * <p>Two families sit here and they are not symmetrical, which is the whole of the difficulty. An
 * event property belongs to one event and is bounded by the window; a session property belongs to
 * a session and is not bounded by anything, so a session series can put a point outside the range
 * that was asked for. The event side counts rows and the session side counts distinct sessions,
 * except where the session side selects its sessions first — in which case it counts rows too,
 * because there is then nothing to double them.
 *
 * <p>Nothing here fills a gap. A bucket with no rows is absent from the answer rather than present
 * with a zero, and a caller drawing a chart fills it.
 */
public final class Pivot {

  private final Store store;
  private final Loader loader;

  public Pivot(Store store, Loader loader) {
    this.store = store;
    this.loader = loader;
  }

  // ------------------------------------------------------------------ event properties

  /** One row per event, carrying every property that event holds. */
  public record EventRow(
      String eventId,
      String sessionId,
      String eventName,
      String urlPath,
      Instant createdAt,
      List<String> propertyKeys,
      List<String> propertyValues) {}

  /**
   * Every event of the given name that carries at least one property, newest first.
   *
   * <p>The name is the only thing selecting the event: an event of any type carrying that name is
   * included, which is what the relational deployment does. The property name a caller may also
   * have sent narrows nothing here — every property of the event comes back.
   */
  public List<EventRow> eventPivot(
      String websiteId, String eventName, Filters.Query query,
      List<Filters.PropertyFilter> propertyFilters) {
    var selection = loader.select(websiteId, query);
    var rows = new ArrayList<EventRow>();
    for (var reading : selection.readings()) {
      var event = reading.event();
      if (!java.util.Objects.equals(eventName, event.eventName())) {
        continue;
      }
      if (event.properties().isEmpty()) {
        continue;
      }
      if (!matchesAll(propertyFilters, event.properties(), query.timezone())) {
        continue;
      }
      var ordered = new ArrayList<>(event.properties());
      ordered.sort(Comparator.comparing(Values.Property::key));
      var keys = new ArrayList<String>();
      var values = new ArrayList<String>();
      for (var property : ordered) {
        keys.add(property.key());
        values.add(pivotValue(property, query.timezone()));
      }
      rows.add(
          new EventRow(event.id(), event.sessionId(), event.eventName(), event.urlPath(),
              event.createdAt(), keys, values));
    }
    rows.sort(Comparator.comparing(EventRow::createdAt).reversed());
    return rows;
  }

  /** Each element of an array-typed property, per bucket, counted once per occurrence. */
  public ArrayNode eventArraySeries(String websiteId, String eventName, String propertyName,
      Filters.Query query, List<Filters.PropertyFilter> propertyFilters) {
    var counts = new LinkedHashMap<String, Map<String, long[]>>();
    for (var event : namedEvents(websiteId, eventName, query, propertyFilters)) {
      var bucket = Dates.bucket(event.createdAt(), query.unit(), query.timezone());
      for (var property : event.properties()) {
        if (!propertyName.equals(property.key()) || property.dataType() != Constants.DATA_ARRAY) {
          continue;
        }
        var array = Json.readArray(property.stringValue());
        if (array == null) {
          continue;
        }
        for (var element : array) {
          counts.computeIfAbsent(bucket, b -> new LinkedHashMap<>())
              .computeIfAbsent(element.asText(), v -> new long[1])[0]++;
        }
      }
    }
    return series(counts);
  }

  /** A date-typed property, grouped by the second it names rather than by any bucket. */
  public ArrayNode eventDateSeries(String websiteId, String eventName, String propertyName,
      Filters.Query query, List<Filters.PropertyFilter> propertyFilters) {
    var counts = new LinkedHashMap<String, long[]>();
    for (var event : namedEvents(websiteId, eventName, query, propertyFilters)) {
      for (var property : event.properties()) {
        if (!propertyName.equals(property.key()) || property.dataType() != Constants.DATA_DATE) {
          continue;
        }
        var label = Dates.toSecond(property.dateValue(), query.timezone());
        counts.computeIfAbsent(nullKey(label), k -> new long[1])[0]++;
      }
    }
    return dateSeries(counts);
  }

  /** A number-typed property summed, averaged or counted per bucket. */
  public ArrayNode eventNumericSeries(String websiteId, String eventName, String propertyName,
      String metric, Filters.Query query, List<Filters.PropertyFilter> propertyFilters) {
    var buckets = new TreeMap<String, List<BigDecimal>>();
    for (var event : namedEvents(websiteId, eventName, query, propertyFilters)) {
      var bucket = Dates.bucket(event.createdAt(), query.unit(), query.timezone());
      for (var property : event.properties()) {
        if (!propertyName.equals(property.key()) || property.dataType() != Constants.DATA_NUMBER) {
          continue;
        }
        buckets.computeIfAbsent(bucket, b -> new ArrayList<>())
            .add(property.numberValue() == null ? BigDecimal.ZERO : property.numberValue());
      }
    }
    return numericSeries(buckets, metric);
  }

  /** The five figures over a number-typed property, all zero when nothing matched. */
  public ObjectNode eventNumericStats(String websiteId, String eventName, String propertyName,
      Filters.Query query, List<Filters.PropertyFilter> propertyFilters) {
    var values = new ArrayList<BigDecimal>();
    for (var event : namedEvents(websiteId, eventName, query, propertyFilters)) {
      for (var property : event.properties()) {
        if (!propertyName.equals(property.key()) || property.dataType() != Constants.DATA_NUMBER) {
          continue;
        }
        values.add(property.numberValue() == null ? BigDecimal.ZERO : property.numberValue());
      }
    }
    return numericStats(values);
  }

  /** A string- or boolean-typed property, per value per bucket, counting rows. */
  public ArrayNode eventPropertySeries(String websiteId, String eventName, String propertyName,
      Filters.Query query, List<Filters.PropertyFilter> propertyFilters) {
    var counts = new LinkedHashMap<String, Map<String, long[]>>();
    for (var event : namedEvents(websiteId, eventName, query, propertyFilters)) {
      var bucket = Dates.bucket(event.createdAt(), query.unit(), query.timezone());
      for (var property : event.properties()) {
        if (!propertyName.equals(property.key())
            || (property.dataType() != Constants.DATA_STRING
                && property.dataType() != Constants.DATA_BOOLEAN)) {
          continue;
        }
        counts.computeIfAbsent(bucket, b -> new LinkedHashMap<>())
            .computeIfAbsent(nullKey(property.stringValue()), v -> new long[1])[0]++;
      }
    }
    return series(counts);
  }

  /**
   * Every named event of the window that carries at least one property filter's answer.
   *
   * <p>Unlike the pivot, these five are restricted to events of type 2 — the original's own
   * queries carry that restriction on the series and not on the pivot, and the difference is
   * observable on an event name shared between a page view and a named event.
   */
  private List<Traffic.Event> namedEvents(String websiteId, String eventName, Filters.Query query,
      List<Filters.PropertyFilter> propertyFilters) {
    var selection = loader.select(websiteId, query.withEventType(Constants.CUSTOM_EVENT));
    var out = new ArrayList<Traffic.Event>();
    for (var reading : selection.readings()) {
      var event = reading.event();
      if (!java.util.Objects.equals(eventName, event.eventName())) {
        continue;
      }
      if (!matchesAll(propertyFilters, event.properties(), query.timezone())) {
        continue;
      }
      out.add(event);
    }
    return out;
  }

  // ------------------------------------------------------------------ session properties

  /** One row per session, carrying the latest value of every property that session holds. */
  public record SessionRow(
      String sessionId,
      String distinctId,
      Instant createdAt,
      List<String> propertyKeys,
      List<String> propertyValues) {}

  /**
   * Every session of the window holding the named property, newest first.
   *
   * <p>A session may have written the same key more than once; the latest write wins, tie-broken
   * by the record's own identifier so two writes at one instant still resolve to one answer. The
   * property named selects which sessions appear and orders them; it does not narrow what comes
   * back, which is every property those sessions hold.
   */
  public List<SessionRow> sessionPivot(String websiteId, String propertyName, Filters.Query query,
      List<Filters.PropertyFilter> propertyFilters) {
    // The sort key is the instant of the named property's own latest write, which is not always
    // the instant the row reports. It is carried beside each row rather than looked up from the
    // comparator: a comparator that reads the store runs a query per comparison.
    var ordered = new ArrayList<Map.Entry<Instant, SessionRow>>();
    for (var sessionId : selectedSessions(websiteId, query, propertyFilters)) {
      var latest = latestProperties(websiteId, sessionId);
      var named = latest.get(propertyName);
      if (named == null) {
        continue;
      }
      var keys = new ArrayList<>(latest.keySet());
      keys.sort(Comparator.naturalOrder());
      var values = new ArrayList<String>();
      Instant newest = null;
      String distinctId = "";
      for (var key : keys) {
        var row = latest.get(key);
        values.add(pivotValue(row.property(), query.timezone()));
        if (newest == null || row.createdAt().isAfter(newest)) {
          newest = row.createdAt();
        }
        if (row.distinctId() != null && row.distinctId().compareTo(distinctId) > 0) {
          distinctId = row.distinctId();
        }
      }
      ordered.add(Map.entry(named.createdAt(),
          new SessionRow(sessionId, distinctId, newest, keys, values)));
    }
    ordered.sort(Map.Entry.<Instant, SessionRow>comparingByKey().reversed());
    return ordered.stream().map(Map.Entry::getValue).toList();
  }

  /** Each element of an array-typed session property, per bucket, counting distinct sessions. */
  public ArrayNode sessionArraySeries(String websiteId, String propertyName, Filters.Query query,
      List<Filters.PropertyFilter> propertyFilters) {
    var seen = new LinkedHashMap<String, Map<String, Set<String>>>();
    for (var row : sessionRows(websiteId, propertyName, query, propertyFilters)) {
      if (row.property().dataType() != Constants.DATA_ARRAY) {
        continue;
      }
      var bucket = Dates.bucket(row.createdAt(), query.unit(), query.timezone());
      var array = Json.readArray(row.property().stringValue());
      if (array == null) {
        continue;
      }
      for (var element : array) {
        seen.computeIfAbsent(bucket, b -> new LinkedHashMap<>())
            .computeIfAbsent(element.asText(), v -> new HashSet<>())
            .add(row.sessionId());
      }
    }
    return distinctSeries(seen);
  }

  /** A date-typed session property, grouped by the second it names, counting distinct sessions. */
  public ArrayNode sessionDateSeries(String websiteId, String propertyName, Filters.Query query,
      List<Filters.PropertyFilter> propertyFilters) {
    var seen = new LinkedHashMap<String, Set<String>>();
    for (var row : sessionRows(websiteId, propertyName, query, propertyFilters)) {
      if (row.property().dataType() != Constants.DATA_DATE) {
        continue;
      }
      var label = Dates.toSecond(row.property().dateValue(), query.timezone());
      seen.computeIfAbsent(nullKey(label), k -> new HashSet<>()).add(row.sessionId());
    }
    var counts = new LinkedHashMap<String, long[]>();
    seen.forEach((label, sessions) -> counts.put(label, new long[] {sessions.size()}));
    return dateSeries(counts);
  }

  /**
   * A number-typed session property summed, averaged or counted per bucket.
   *
   * <p>The sessions are selected first and the property rows read once each, so a sum is a sum of
   * the values rather than of the values repeated per event — which is why this one may sum while
   * the three above it may only count.
   */
  public ArrayNode sessionNumericSeries(String websiteId, String propertyName, String metric,
      Filters.Query query, List<Filters.PropertyFilter> propertyFilters) {
    var buckets = new TreeMap<String, List<BigDecimal>>();
    var sessionsPerBucket = new TreeMap<String, Set<String>>();
    for (var sessionId : selectedSessions(websiteId, query, propertyFilters)) {
      for (var row : store.sessionProperties(websiteId, sessionId)) {
        if (!propertyName.equals(row.property().key())
            || row.property().dataType() != Constants.DATA_NUMBER) {
          continue;
        }
        var bucket = Dates.bucket(row.createdAt(), query.unit(), query.timezone());
        buckets.computeIfAbsent(bucket, b -> new ArrayList<>())
            .add(row.property().numberValue() == null
                ? BigDecimal.ZERO : row.property().numberValue());
        sessionsPerBucket.computeIfAbsent(bucket, b -> new HashSet<>()).add(sessionId);
      }
    }
    if ("count".equals(metric)) {
      var out = Json.array();
      sessionsPerBucket.forEach(
          (bucket, sessions) -> {
            var point = Json.object();
            point.put("t", bucket);
            point.put("y", sessions.size());
            out.add(point);
          });
      return out;
    }
    return numericSeries(buckets, metric);
  }

  /** The five figures over a number-typed session property. */
  public ObjectNode sessionNumericStats(String websiteId, String propertyName,
      Filters.Query query, List<Filters.PropertyFilter> propertyFilters) {
    var values = new ArrayList<BigDecimal>();
    for (var sessionId : selectedSessions(websiteId, query, propertyFilters)) {
      for (var row : store.sessionProperties(websiteId, sessionId)) {
        if (!propertyName.equals(row.property().key())
            || row.property().dataType() != Constants.DATA_NUMBER) {
          continue;
        }
        values.add(row.property().numberValue() == null
            ? BigDecimal.ZERO : row.property().numberValue());
      }
    }
    return numericStats(values);
  }

  /** A string- or boolean-typed session property, per value per bucket, counting sessions. */
  public ArrayNode sessionPropertySeries(String websiteId, String propertyName,
      Filters.Query query, List<Filters.PropertyFilter> propertyFilters) {
    var seen = new LinkedHashMap<String, Map<String, Set<String>>>();
    for (var row : sessionRows(websiteId, propertyName, query, propertyFilters)) {
      int type = row.property().dataType();
      if (type != Constants.DATA_STRING && type != Constants.DATA_BOOLEAN) {
        continue;
      }
      var bucket = Dates.bucket(row.createdAt(), query.unit(), query.timezone());
      seen.computeIfAbsent(bucket, b -> new LinkedHashMap<>())
          .computeIfAbsent(nullKey(row.property().stringValue()), v -> new HashSet<>())
          .add(row.sessionId());
    }
    return distinctSeries(seen);
  }

  /** What each value of a string session property is worth: its sessions, visits, views, events. */
  public ArrayNode sessionActivityStats(String websiteId, String propertyName,
      Filters.Query query, List<Filters.PropertyFilter> propertyFilters) {
    var sessions = selectedSessions(websiteId, query, propertyFilters);
    var rollup = sessionRollup(websiteId, query, sessions);

    // A label is a string value that is neither absent nor empty; nothing else is counted.
    var byLabel = new LinkedHashMap<String, long[]>();
    var labelSessions = new LinkedHashMap<String, Set<String>>();
    for (var sessionId : sessions) {
      for (var row : store.sessionProperties(websiteId, sessionId)) {
        if (!propertyName.equals(row.property().key())
            || row.property().dataType() != Constants.DATA_STRING) {
          continue;
        }
        var label = row.property().stringValue();
        if (label == null || label.isEmpty()) {
          continue;
        }
        var stats = rollup.getOrDefault(sessionId, new long[4]);
        var totals = byLabel.computeIfAbsent(label, l -> new long[4]);
        for (int i = 0; i < 4; i++) {
          totals[i] += stats[i];
        }
        labelSessions.computeIfAbsent(label, l -> new HashSet<>()).add(sessionId);
      }
    }

    var ordered = new ArrayList<>(byLabel.entrySet());
    ordered.sort(
        Comparator.<Map.Entry<String, long[]>>comparingLong(e -> e.getValue()[0]).reversed()
            .thenComparing(Map.Entry::getKey));
    var out = Json.array();
    for (var entry : ordered.subList(0, Math.min(100, ordered.size()))) {
      var node = Json.object();
      node.put("label", entry.getKey());
      node.put("activity", entry.getValue()[0]);
      node.put("sessions", labelSessions.get(entry.getKey()).size());
      node.put("visits", entry.getValue()[1]);
      node.put("views", entry.getValue()[2]);
      node.put("events", entry.getValue()[3]);
      out.add(node);
    }
    return out;
  }

  /** Per session: how much it did, over how many visits, of which views and named events. */
  private Map<String, long[]> sessionRollup(String websiteId, Filters.Query query,
      List<String> sessions) {
    var wanted = new HashSet<>(sessions);
    var visits = new HashMap<String, Set<String>>();
    var out = new HashMap<String, long[]>();
    for (var reading : loader.unfiltered(websiteId, query.startDate(), query.endDate())) {
      var event = reading.event();
      if (!wanted.contains(event.sessionId()) || event.eventType() == Constants.PERFORMANCE_EVENT) {
        continue;
      }
      var stats = out.computeIfAbsent(event.sessionId(), id -> new long[4]);
      stats[0]++;
      if (event.eventType() == Constants.PAGE_VIEW) {
        stats[2]++;
      }
      if (event.eventType() == Constants.CUSTOM_EVENT) {
        stats[3]++;
      }
      visits.computeIfAbsent(event.sessionId(), id -> new HashSet<>()).add(event.visitId());
    }
    visits.forEach((sessionId, ids) -> out.get(sessionId)[1] = ids.size());
    return out;
  }

  /** The sessions of the window that survive every filter the request carries. */
  private List<String> selectedSessions(String websiteId, Filters.Query query,
      List<Filters.PropertyFilter> propertyFilters) {
    var selection = loader.select(websiteId, query);
    var out = new ArrayList<String>();
    var seen = new HashSet<String>();
    for (var reading : selection.readings()) {
      if (reading.event().eventType() == Constants.PERFORMANCE_EVENT) {
        continue;
      }
      var sessionId = reading.event().sessionId();
      if (!seen.add(sessionId)) {
        continue;
      }
      if (!propertyFilters.isEmpty()
          && !matchesAll(propertyFilters, propertiesOf(websiteId, sessionId), query.timezone())) {
        continue;
      }
      out.add(sessionId);
    }
    return out;
  }

  /**
   * The property rows the three fan-out series read: one per selected session per write.
   *
   * <p>These are not narrowed by the window — a session property carries the instant it was
   * written, and the original bounds only the events that selected the session. So a bucket
   * outside the range asked for is a correct answer rather than a leak.
   */
  private List<Traffic.SessionProperty> sessionRows(String websiteId, String propertyName,
      Filters.Query query, List<Filters.PropertyFilter> propertyFilters) {
    var out = new ArrayList<Traffic.SessionProperty>();
    for (var sessionId : selectedSessions(websiteId, query, propertyFilters)) {
      for (var row : store.sessionProperties(websiteId, sessionId)) {
        if (propertyName.equals(row.property().key())) {
          out.add(row);
        }
      }
    }
    return out;
  }

  private List<Values.Property> propertiesOf(String websiteId, String sessionId) {
    return store.sessionProperties(websiteId, sessionId).stream()
        .map(Traffic.SessionProperty::property)
        .toList();
  }

  /** The latest write of each key a session holds, tie-broken by the record's own identifier. */
  private Map<String, Traffic.SessionProperty> latestProperties(String websiteId,
      String sessionId) {
    var out = new LinkedHashMap<String, Traffic.SessionProperty>();
    for (var row : store.sessionProperties(websiteId, sessionId)) {
      var held = out.get(row.property().key());
      if (held == null
          || row.createdAt().isAfter(held.createdAt())
          || (row.createdAt().equals(held.createdAt())
              && row.id().compareTo(held.id()) > 0)) {
        out.put(row.property().key(), row);
      }
    }
    return out;
  }

  // ------------------------------------------------------------------ shared shapes

  /**
   * The string a pivot cell holds.
   *
   * <p>A number keeps the four decimal places it was stored with; a date is written to the second;
   * anything absent is the empty string rather than nothing, because the two arrays are read by
   * position and a hole in one would shift the other.
   */
  static String pivotValue(Values.Property property, String timezone) {
    var value =
        switch (property.dataType()) {
          case Constants.DATA_NUMBER -> property.stringValue();
          case Constants.DATA_DATE -> Dates.toSecond(property.dateValue(), timezone);
          default -> property.stringValue();
        };
    return value == null ? "" : value;
  }

  private static boolean matchesAll(List<Filters.PropertyFilter> filters,
      List<Values.Property> properties, String timezone) {
    for (var filter : filters) {
      boolean satisfied = false;
      for (var property : properties) {
        if (Filters.matchesProperty(filter, property, timezone)) {
          satisfied = true;
          break;
        }
      }
      if (!satisfied) {
        return false;
      }
    }
    return true;
  }

  /** A key that is absent stays absent: a null value is its own group, not the empty string. */
  private static String nullKey(String value) {
    return value;
  }

  private static ArrayNode series(Map<String, Map<String, long[]>> counts) {
    var out = Json.array();
    var buckets = new ArrayList<>(counts.keySet());
    buckets.sort(Comparator.nullsLast(Comparator.naturalOrder()));
    for (var bucket : buckets) {
      counts.get(bucket).forEach(
          (value, total) -> {
            var point = Json.object();
            point.put("x", value);
            point.put("t", bucket);
            point.put("y", total[0]);
            out.add(point);
          });
    }
    return out;
  }

  private static ArrayNode distinctSeries(Map<String, Map<String, Set<String>>> seen) {
    var counts = new LinkedHashMap<String, Map<String, long[]>>();
    seen.forEach(
        (bucket, values) -> {
          var inner = new LinkedHashMap<String, long[]>();
          values.forEach((value, sessions) -> inner.put(value, new long[] {sessions.size()}));
          counts.put(bucket, inner);
        });
    return series(counts);
  }

  private static ArrayNode dateSeries(Map<String, long[]> counts) {
    var labels = new ArrayList<>(counts.keySet());
    labels.sort(Comparator.nullsLast(Comparator.naturalOrder()));
    var out = Json.array();
    for (var label : labels) {
      var point = Json.object();
      point.put("t", label);
      point.put("y", counts.get(label)[0]);
      out.add(point);
    }
    return out;
  }

  /**
   * A sum or an average per bucket, written as a decimal string; a count, written as a number.
   *
   * <p>The two are different JSON types on the original as well, because one arrives from a
   * decimal column and the other from a row count, and a caller reading the field has to cope
   * with both.
   */
  private static ArrayNode numericSeries(Map<String, List<BigDecimal>> buckets, String metric) {
    var out = Json.array();
    buckets.forEach(
        (bucket, values) -> {
          var point = Json.object();
          point.put("t", bucket);
          if ("count".equals(metric)) {
            point.put("y", values.size());
          } else if ("avg".equals(metric)) {
            point.put("y", average(values).toPlainString());
          } else {
            point.put("y", total(values).toPlainString());
          }
          out.add(point);
        });
    return out;
  }

  static ObjectNode numericStats(List<BigDecimal> values) {
    var out = Json.object();
    if (values.isEmpty()) {
      out.put("total", "0");
      out.put("average", "0");
      out.put("median", "0");
      out.put("max", "0");
      out.put("min", "0");
      return out;
    }
    var sorted = new ArrayList<>(values);
    sorted.sort(BigDecimal::compareTo);
    out.put("total", total(sorted).toPlainString());
    out.put("average", average(sorted).toPlainString());
    out.put("median", median(sorted).toPlainString());
    out.put("max", sorted.get(sorted.size() - 1).toPlainString());
    out.put("min", sorted.get(0).toPlainString());
    return out;
  }

  private static BigDecimal total(List<BigDecimal> values) {
    var sum = BigDecimal.ZERO;
    for (var value : values) {
      sum = sum.add(value);
    }
    return sum;
  }

  private static BigDecimal average(List<BigDecimal> values) {
    if (values.isEmpty()) {
      return BigDecimal.ZERO;
    }
    return total(values).divide(BigDecimal.valueOf(values.size()), 16, RoundingMode.HALF_UP)
        .stripTrailingZeros();
  }

  /** The median between the two middle values rather than one of them, which is interpolation. */
  private static BigDecimal median(List<BigDecimal> sorted) {
    int size = sorted.size();
    if (size % 2 == 1) {
      return sorted.get(size / 2);
    }
    return sorted.get(size / 2 - 1).add(sorted.get(size / 2))
        .divide(BigDecimal.valueOf(2), 16, RoundingMode.HALF_UP)
        .stripTrailingZeros();
  }
}
