package io.akka.umami.analytics;

import io.akka.umami.application.Store;
import io.akka.umami.domain.Traffic;
import io.akka.umami.lib.Channels;
import io.akka.umami.lib.Constants;
import io.akka.umami.lib.Dates;
import io.akka.umami.lib.Filters;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** The five figures, the series behind them, and every dimension they break down by. */
public final class Rollup {

  private final Store store;
  private final Loader loader;

  public Rollup(Store store) {
    this.store = store;
    this.loader = new Loader(store);
  }

  public Loader loader() {
    return loader;
  }

  public record Stats(long pageviews, long visitors, long visits, long bounces, long totaltime) {}

  public record Point(String x, long y) {}

  public record NamedPoint(String x, String t, long y) {}

  public record Metric(String x, long y, String country) {}

  public record Expanded(
      String name, long pageviews, long visitors, long visits, long bounces, long totaltime) {}

  /** A dimension value and, where the dimension is a place, the country disambiguating it. */
  private record ValueCountry(String value, String country) {}

  // ------------------------------------------------------------------ the five figures

  public Stats stats(String websiteId, Filters.Query query) {
    var selection = loader.select(websiteId, query);
    var visits = loader.visits(selection);
    long pageviews = 0;
    long bounces = 0;
    long totaltime = 0;
    var sessions = new HashSet<String>();
    for (var visit : visits.values()) {
      pageviews += visit.views();
      totaltime += visit.seconds();
      if (visit.isBounce()) {
        bounces++;
      }
      sessions.add(visit.key().sessionId());
    }
    // An excluded bounce leaves the figure at zero rather than at the count of what remained.
    return new Stats(
        pageviews, sessions.size(), visits.size(), query.excludeBounce() ? 0 : bounces, totaltime);
  }

  // ------------------------------------------------------------------ series

  public List<Point> pageviewSeries(String websiteId, Filters.Query query) {
    return series(websiteId, query, false);
  }

  public List<Point> sessionSeries(String websiteId, Filters.Query query) {
    return series(websiteId, query, true);
  }

  private List<Point> series(String websiteId, Filters.Query query, boolean distinctSessions) {
    var selection = loader.select(websiteId, query);
    var buckets = new TreeMap<String, Object>();
    var counts = new LinkedHashMap<String, long[]>();
    var seen = new LinkedHashMap<String, Set<String>>();
    for (var reading : selection.readings()) {
      if (!reading.event().isViewLike()) {
        continue;
      }
      var bucket = Dates.bucket(reading.event().createdAt(), query.unit(), query.timezone());
      buckets.put(bucket, null);
      if (distinctSessions) {
        seen.computeIfAbsent(bucket, b -> new HashSet<>()).add(reading.event().sessionId());
      } else {
        counts.computeIfAbsent(bucket, b -> new long[1])[0]++;
      }
    }
    var out = new ArrayList<Point>();
    for (var bucket : buckets.keySet()) {
      out.add(
          new Point(
              bucket,
              distinctSessions ? seen.get(bucket).size() : counts.get(bucket)[0]));
    }
    return out;
  }

  /** The named-event series: one row per name and bucket. */
  public List<NamedPoint> eventSeries(String websiteId, Filters.Query query, Integer limit) {
    var selection = loader.select(websiteId, query.withEventType(Constants.CUSTOM_EVENT));
    var totals = new HashMap<String, Long>();
    for (var reading : selection.readings()) {
      totals.merge(nullToEmpty(reading.event().eventName()), 1L, Long::sum);
    }
    Set<String> kept = null;
    if (limit != null && limit > 0) {
      kept =
          totals.entrySet().stream()
              .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
              .limit(limit)
              .map(Map.Entry::getKey)
              .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }
    var grouped = new LinkedHashMap<String, Map<String, long[]>>();
    for (var reading : selection.readings()) {
      var name = nullToEmpty(reading.event().eventName());
      if (kept != null && !kept.contains(name)) {
        continue;
      }
      var bucket = Dates.bucket(reading.event().createdAt(), query.unit(), query.timezone());
      grouped
          .computeIfAbsent(bucket, b -> new LinkedHashMap<>())
          .computeIfAbsent(name, n -> new long[1])[0]++;
    }
    var out = new ArrayList<NamedPoint>();
    for (var bucket : new TreeMap<>(grouped).entrySet()) {
      for (var name : bucket.getValue().entrySet()) {
        out.add(new NamedPoint(name.getKey(), bucket.getKey(), name.getValue()[0]));
      }
    }
    return out;
  }

  // ------------------------------------------------------------------ dimensions

  /** Which family a dimension belongs to, which decides both the query and what it counts. */
  public static boolean isSessionDimension(String type) {
    return Constants.SESSION_COLUMNS.contains(type);
  }

  public static boolean isEventDimension(String type) {
    return Constants.EVENT_COLUMNS.contains(type);
  }

  public List<Metric> metrics(String websiteId, String type, Filters.Query query, int limit,
      int offset) {
    if ("channel".equals(type)) {
      return channelMetrics(websiteId, query, limit, offset);
    }
    boolean named = "event".equals(type);
    var effective = named ? query.withEventType(Constants.CUSTOM_EVENT) : query;
    var selection = loader.select(websiteId, effective);
    boolean includeCountry = "city".equals(type) || "region".equals(type);

    var counts = new LinkedHashMap<ValueCountry, long[]>();
    var distinct = new LinkedHashMap<ValueCountry, Set<String>>();
    var labels = new LinkedHashMap<ValueCountry, ValueCountry>();

    var entryOrExit = entryExitPaths(selection, type);

    for (var reading : selection.readings()) {
      if (!named && !reading.event().isViewLike()) {
        continue;
      }
      var value = dimensionValue(reading, type, entryOrExit);
      if (value == null || value.isEmpty()) {
        continue;
      }
      if ("referrer".equals(type) || "domain".equals(type)) {
        var host = Filters.stripWww(reading.event().hostname());
        if (value.equals(host)) {
          continue;
        }
      }
      var country = includeCountry
          ? nullToEmpty(reading.session() == null ? null : reading.session().country())
          : null;
      var key = new ValueCountry(value, country);
      labels.put(key, key);
      if (named) {
        counts.computeIfAbsent(key, k -> new long[1])[0]++;
      } else {
        distinct.computeIfAbsent(key, k -> new HashSet<>()).add(reading.event().sessionId());
      }
    }

    var out = new ArrayList<Metric>();
    for (var entry : labels.entrySet()) {
      long value =
          named ? counts.get(entry.getKey())[0] : distinct.get(entry.getKey()).size();
      out.add(new Metric(entry.getValue().value(), value, entry.getValue().country()));
    }
    out.sort(Comparator.comparingLong(Metric::y).reversed());
    return page(out, limit, offset);
  }

  public List<Expanded> expandedMetrics(String websiteId, String type, Filters.Query query,
      int limit, int offset) {
    if ("channel".equals(type)) {
      return channelExpanded(websiteId, query, limit, offset);
    }
    boolean named = "event".equals(type);
    if (named) {
      return eventExpanded(websiteId, query, limit, offset);
    }
    boolean pageDimension = isEventDimension(type);
    var selection = loader.select(websiteId, query);
    var entryOrExit = entryExitPaths(selection, type);

    var perVisit = new LinkedHashMap<String, Map<Loader.VisitKey, int[]>>();
    var bounds = new LinkedHashMap<String, Map<Loader.VisitKey, Instant[]>>();
    for (var reading : selection.readings()) {
      if (!reading.event().isViewLike()) {
        continue;
      }
      var value = expandedDimensionValue(reading, type, entryOrExit);
      if (value == null || value.isEmpty()) {
        continue;
      }
      if ("referrer".equals(type) || "domain".equals(type)) {
        var host = Filters.stripWww(reading.event().hostname());
        if (nullToEmpty(reading.event().referrerDomain()).equals(nullToEmpty(host))) {
          continue;
        }
      }
      var key = new Loader.VisitKey(reading.event().sessionId(), reading.event().visitId());
      perVisit.computeIfAbsent(value, v -> new LinkedHashMap<>())
          .computeIfAbsent(key, k -> new int[1])[0]++;
      var extremes =
          bounds.computeIfAbsent(value, v -> new LinkedHashMap<>())
              .computeIfAbsent(key, k -> new Instant[2]);
      if (extremes[0] == null || reading.event().createdAt().isBefore(extremes[0])) {
        extremes[0] = reading.event().createdAt();
      }
      if (extremes[1] == null || reading.event().createdAt().isAfter(extremes[1])) {
        extremes[1] = reading.event().createdAt();
      }
    }

    var namedVisits = namedEventVisits(websiteId, query);
    var out = new ArrayList<Expanded>();
    for (var entry : perVisit.entrySet()) {
      long pageviews = 0;
      long bounces = 0;
      long totaltime = 0;
      var sessions = new HashSet<String>();
      for (var visit : entry.getValue().entrySet()) {
        int views = visit.getValue()[0];
        pageviews += views;
        sessions.add(visit.getKey().sessionId());
        if (views == 1 && !namedVisits.contains(visit.getKey())) {
          bounces++;
        }
        var extremes = bounds.get(entry.getKey()).get(visit.getKey());
        if (extremes[0] != null && extremes[1] != null) {
          totaltime += Math.max(0, (extremes[1].toEpochMilli() - extremes[0].toEpochMilli()) / 1000);
        }
      }
      // The page form of this question reports no bounce and no time at all, whatever the data.
      out.add(
          new Expanded(
              entry.getKey(),
              pageviews,
              sessions.size(),
              entry.getValue().size(),
              pageDimension || query.excludeBounce() ? 0 : bounces,
              pageDimension ? 0 : totaltime));
    }
    out.sort(
        Comparator.comparingLong(Expanded::visitors)
            .thenComparingLong(Expanded::visits)
            .reversed());
    return page(out, limit, offset);
  }

  /** The event form calls a visit a bounce when it produced exactly one matching named event. */
  private List<Expanded> eventExpanded(String websiteId, Filters.Query query, int limit,
      int offset) {
    var selection = loader.select(websiteId, query.withEventType(Constants.CUSTOM_EVENT));
    var perVisit = new LinkedHashMap<String, Map<Loader.VisitKey, int[]>>();
    var bounds = new LinkedHashMap<String, Map<Loader.VisitKey, Instant[]>>();
    for (var reading : selection.readings()) {
      var value = nullToEmpty(reading.event().eventName());
      if (value.isEmpty()) {
        continue;
      }
      var key = new Loader.VisitKey(reading.event().sessionId(), reading.event().visitId());
      perVisit.computeIfAbsent(value, v -> new LinkedHashMap<>())
          .computeIfAbsent(key, k -> new int[1])[0]++;
      var extremes =
          bounds.computeIfAbsent(value, v -> new LinkedHashMap<>())
              .computeIfAbsent(key, k -> new Instant[2]);
      if (extremes[0] == null || reading.event().createdAt().isBefore(extremes[0])) {
        extremes[0] = reading.event().createdAt();
      }
      if (extremes[1] == null || reading.event().createdAt().isAfter(extremes[1])) {
        extremes[1] = reading.event().createdAt();
      }
    }
    var out = new ArrayList<Expanded>();
    for (var entry : perVisit.entrySet()) {
      long pageviews = 0;
      long bounces = 0;
      long totaltime = 0;
      var sessions = new HashSet<String>();
      for (var visit : entry.getValue().entrySet()) {
        int count = visit.getValue()[0];
        pageviews += count;
        sessions.add(visit.getKey().sessionId());
        if (count == 1) {
          bounces++;
        }
        var extremes = bounds.get(entry.getKey()).get(visit.getKey());
        if (extremes[0] != null && extremes[1] != null) {
          totaltime += Math.max(0, (extremes[1].toEpochMilli() - extremes[0].toEpochMilli()) / 1000);
        }
      }
      out.add(new Expanded(entry.getKey(), pageviews, sessions.size(), entry.getValue().size(),
          bounces, totaltime));
    }
    out.sort(
        Comparator.comparingLong(Expanded::visitors).thenComparingLong(Expanded::visits).reversed());
    return page(out, limit, offset);
  }

  // ------------------------------------------------------------------ channels

  /** One channel per visit: the first event that classified, by instant then identifier. */
  public Map<Loader.VisitKey, String> visitChannels(String websiteId, Filters.Query query) {
    var selection = loader.select(websiteId, query);
    var readings = new ArrayList<>(selection.readings());
    readings.sort(
        Comparator.comparing((Traffic.Reading r) -> r.event().createdAt())
            .thenComparing(r -> r.event().id()));
    var chosen = new LinkedHashMap<Loader.VisitKey, String>();
    var seen = new LinkedHashSet<Loader.VisitKey>();
    for (var reading : readings) {
      if (!reading.event().isViewLike()) {
        continue;
      }
      var key = new Loader.VisitKey(reading.event().sessionId(), reading.event().visitId());
      seen.add(key);
      if (chosen.containsKey(key)) {
        continue;
      }
      var channel =
          Channels.classify(
              reading.event().referrerDomain(),
              reading.event().urlQuery(),
              reading.event().utmMedium(),
              reading.event().utmSource(),
              reading.event().hostname());
      if (!channel.isEmpty()) {
        chosen.put(key, channel);
      }
    }
    var out = new LinkedHashMap<Loader.VisitKey, String>();
    for (var key : seen) {
      out.put(key, chosen.getOrDefault(key, Channels.DIRECT));
    }
    return out;
  }

  private List<Metric> channelMetrics(String websiteId, Filters.Query query, int limit, int offset) {
    var channels = visitChannels(websiteId, query);
    var distinct = new LinkedHashMap<String, Set<String>>();
    for (var entry : channels.entrySet()) {
      distinct.computeIfAbsent(entry.getValue(), c -> new HashSet<>())
          .add(entry.getKey().sessionId());
    }
    var out = new ArrayList<Metric>();
    distinct.forEach((channel, sessions) -> out.add(new Metric(channel, sessions.size(), null)));
    out.sort(Comparator.comparingLong(Metric::y).reversed());
    return page(out, limit, offset);
  }

  private List<Expanded> channelExpanded(String websiteId, Filters.Query query, int limit,
      int offset) {
    var channels = visitChannels(websiteId, query);
    var selection = loader.select(websiteId, query);
    var visits = loader.visits(selection);
    var grouped = new LinkedHashMap<String, List<Loader.Visit>>();
    for (var entry : channels.entrySet()) {
      var visit = visits.get(entry.getKey());
      if (visit != null) {
        grouped.computeIfAbsent(entry.getValue(), c -> new ArrayList<>()).add(visit);
      }
    }
    var out = new ArrayList<Expanded>();
    for (var entry : grouped.entrySet()) {
      long pageviews = 0;
      long bounces = 0;
      long totaltime = 0;
      var sessions = new HashSet<String>();
      for (var visit : entry.getValue()) {
        pageviews += visit.views();
        totaltime += visit.seconds();
        sessions.add(visit.key().sessionId());
        if (visit.isBounce()) {
          bounces++;
        }
      }
      out.add(
          new Expanded(
              entry.getKey(),
              pageviews,
              sessions.size(),
              entry.getValue().size(),
              query.excludeBounce() ? 0 : bounces,
              totaltime));
    }
    out.sort(
        Comparator.comparingLong(Expanded::visitors).thenComparingLong(Expanded::visits).reversed());
    return page(out, limit, offset);
  }

  // ------------------------------------------------------------------ shared

  /**
   * The value a dimension reads off one row in its expanded form, where a referrer is folded
   * into a canonical site. The plain form answers the host as it was stored.
   */
  public String expandedDimensionValue(Traffic.Reading reading, String type,
      Map<Loader.VisitKey, String> entryOrExit) {
    if ("domain".equals(type)) {
      var referrer = reading.event().referrerDomain();
      if (referrer == null || referrer.isEmpty()) {
        return null;
      }
      return Channels.groupedDomain(referrer);
    }
    return dimensionValue(reading, type, entryOrExit);
  }

  /** The value a dimension reads off one row, including the two that are not columns. */
  public String dimensionValue(Traffic.Reading reading, String type,
      Map<Loader.VisitKey, String> entryOrExit) {
    if (("entry".equals(type) || "exit".equals(type)) && entryOrExit != null) {
      return entryOrExit.get(
          new Loader.VisitKey(reading.event().sessionId(), reading.event().visitId()));
    }
    if ("language".equals(type)) {
      var language = reading.session() == null ? null : reading.session().language();
      if (language == null || language.isEmpty()) {
        return null;
      }
      return language.substring(0, Math.min(2, language.length())).toLowerCase(Locale.ROOT);
    }
    return reading.column(type);
  }

  /** The first or last view of each visit, which is what the two path dimensions resolve to. */
  public Map<Loader.VisitKey, String> entryExitPaths(Loader.Selection selection, String type) {
    if (!"entry".equals(type) && !"exit".equals(type)) {
      return null;
    }
    boolean first = "entry".equals(type);
    var chosen = new LinkedHashMap<Loader.VisitKey, Traffic.Event>();
    for (var reading : selection.readings()) {
      var event = reading.event();
      if (!event.isViewLike()) {
        continue;
      }
      var key = new Loader.VisitKey(event.sessionId(), event.visitId());
      var current = chosen.get(key);
      if (current == null
          || (first
              ? event.createdAt().isBefore(current.createdAt())
              : event.createdAt().isAfter(current.createdAt()))) {
        chosen.put(key, event);
      }
    }
    var out = new LinkedHashMap<Loader.VisitKey, String>();
    chosen.forEach((key, event) -> out.put(key, event.urlPath()));
    return out;
  }

  /** Which visits of the window held a named event, read unfiltered. */
  public Set<Loader.VisitKey> namedEventVisits(String websiteId, Filters.Query query) {
    var out = new HashSet<Loader.VisitKey>();
    for (var reading : loader.unfiltered(websiteId, query.startDate(), query.endDate())) {
      if (reading.event().isNamed()) {
        out.add(new Loader.VisitKey(reading.event().sessionId(), reading.event().visitId()));
      }
    }
    return out;
  }

  /** The values a dimension actually took, for the filter picker. */
  public List<Map.Entry<String, Long>> values(String websiteId, String type, Filters.Query query,
      String search) {
    if (isSessionDimension(type)) {
      return sessionValues(websiteId, type, query, search);
    }
    var selection = loader.select(websiteId, query);
    var counts = new LinkedHashMap<String, long[]>();
    var entryOrExit = entryExitPaths(selection, type);
    for (var reading : selection.readings()) {
      var value = dimensionValue(reading, type, entryOrExit);
      if (value == null || value.isEmpty()) {
        continue;
      }
      if ("referrer".equals(type) || "domain".equals(type)) {
        var host = Filters.stripWww(reading.event().hostname());
        if (value.equals(host)) {
          continue;
        }
      }
      if (search != null && !search.isBlank() && !matchesSearch(value, search)) {
        continue;
      }
      counts.computeIfAbsent(value, v -> new long[1])[0]++;
    }
    var out = new ArrayList<Map.Entry<String, Long>>();
    counts.forEach((value, count) -> out.add(Map.entry(value, count[0])));
    out.sort(Map.Entry.<String, Long>comparingByValue().reversed());
    return out.size() > 10 ? out.subList(0, 10) : out;
  }

  /** A session dimension counts sessions, not the events they hold. */
  private List<Map.Entry<String, Long>> sessionValues(String websiteId, String type,
      Filters.Query query, String search) {
    var counts = new LinkedHashMap<String, long[]>();
    for (var session : store.sessions(websiteId)) {
      if (session.createdAt() == null
          || session.createdAt().isBefore(query.startDate())
          || session.createdAt().isAfter(query.endDate())) {
        continue;
      }
      var value = sessionColumn(session, type);
      if (value == null || value.isEmpty()) {
        continue;
      }
      if (search != null && !search.isBlank() && !matchesSearch(value, search)) {
        continue;
      }
      counts.computeIfAbsent(value, v -> new long[1])[0]++;
    }
    var out = new ArrayList<Map.Entry<String, Long>>();
    counts.forEach((value, count) -> out.add(Map.entry(value, count[0])));
    out.sort(Map.Entry.<String, Long>comparingByValue().reversed());
    return out.size() > 10 ? out.subList(0, 10) : out;
  }

  private static String sessionColumn(Traffic.Session session, String type) {
    return switch (type) {
      case "browser" -> session.browser();
      case "os" -> session.os();
      case "device" -> session.device();
      case "screen" -> session.screen();
      case "language" -> session.language();
      case "country" -> session.country();
      case "region" -> session.region();
      case "city" -> session.city();
      case "distinctId" -> session.distinctId();
      default -> null;
    };
  }

  /**
   * A comma in the search term makes each term an exact match rather than a partial one — the
   * multi-term form binds its values without wildcards. SPEC's list of irregularities.
   */
  private static boolean matchesSearch(String value, String search) {
    if (search.contains(",")) {
      var terms = search.split(",");
      int limit = Math.min(5, terms.length);
      for (int i = 0; i < limit; i++) {
        if (value.equalsIgnoreCase(terms[i].trim())) {
          return true;
        }
      }
      return false;
    }
    return value.toLowerCase(Locale.ROOT).contains(search.toLowerCase(Locale.ROOT));
  }

  static <T> List<T> page(List<T> rows, int limit, int offset) {
    if (offset >= rows.size()) {
      return List.of();
    }
    int end = limit <= 0 ? rows.size() : Math.min(rows.size(), offset + limit);
    return new ArrayList<>(rows.subList(offset, end));
  }

  static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  public Store store() {
    return store;
  }
}
