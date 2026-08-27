package io.akka.umami.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.akka.umami.application.Store;
import io.akka.umami.domain.Traffic;
import io.akka.umami.lib.Channels;
import io.akka.umami.lib.Constants;
import io.akka.umami.lib.Dates;
import io.akka.umami.lib.Filters;
import io.akka.umami.lib.Json;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
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

/** The ten reports. */
public final class Reports {

  private final Store store;
  private final Loader loader;
  private final Rollup rollup;

  public Reports(Store store) {
    this.store = store;
    this.rollup = new Rollup(store);
    this.loader = rollup.loader();
  }

  // ------------------------------------------------------------------ funnel

  /**
   * Steps in order, each reached from the previous one within the window.
   *
   * <p>Only the first level carries the request's filters, and matching is per session rather than
   * per visit — both of which are what the original does and neither of which is obvious. SPEC R63.
   */
  public ArrayNode funnel(String websiteId, Filters.Query query, JsonNode parameters) {
    var steps = parameters.get("steps");
    long window = parameters.get("window").asLong();
    var all = loader.unfiltered(websiteId, query.startDate(), query.endDate());
    var filtered = loader.select(websiteId, query).readings();

    var counts = new ArrayList<Long>();
    Map<String, Instant> reached = new HashMap<>();

    for (int level = 0; level < steps.size(); level++) {
      var step = steps.get(level);
      var column = "path".equals(step.get("type").asText()) ? "path" : "event";
      var value = step.get("value").asText();
      var stepFilters = step.get("filters");
      var next = new HashMap<String, Instant>();
      var pool = level == 0 ? filtered : all;
      for (var reading : pool) {
        if (!matchesStep(reading, column, value, stepFilters)) {
          continue;
        }
        var sessionId = reading.event().sessionId();
        var at = reading.event().createdAt();
        if (level == 0) {
          var current = next.get(sessionId);
          if (current == null || at.isBefore(current)) {
            next.put(sessionId, at);
          }
          continue;
        }
        var previous = reached.get(sessionId);
        if (previous == null || at.isBefore(previous)) {
          continue;
        }
        if (at.isAfter(previous.plus(window, ChronoUnit.MINUTES))) {
          continue;
        }
        if (at.isAfter(query.endDate())) {
          continue;
        }
        var current = next.get(sessionId);
        if (current == null || at.isBefore(current)) {
          next.put(sessionId, at);
        }
      }
      reached = next;
      counts.add((long) reached.size());
    }

    var out = Json.array();
    long first = counts.isEmpty() ? 0 : counts.get(0);
    for (int i = 0; i < counts.size(); i++) {
      long visitors = counts.get(i);
      long previous = i == 0 ? 0 : counts.get(i - 1);
      var row = Json.object();
      row.put("type", steps.get(i).get("type").asText());
      row.put("value", steps.get(i).get("value").asText());
      row.put("visitors", visitors);
      row.put("previous", previous);
      row.put("dropped", previous > 0 ? previous - visitors : 0);
      // The first step has nothing before it, so its drop-off is negative infinity — and the
      // answer format cannot carry one, so it is written as nothing at all. SPEC R64.
      if (previous == 0) {
        row.putNull("dropoff");
      } else {
        row.put("dropoff", 1 - ((double) visitors / previous));
      }
      // A funnel nobody entered divides nothing by nothing, and the answer format carries
      // that as nothing rather than as zero. Zero would say the step lost everyone, which is
      // a different claim from having nobody to lose. SPEC R64a.
      if (first == 0) {
        row.putNull("remaining");
      } else {
        row.put("remaining", (double) visitors / first);
      }
      out.add(row);
    }
    return out;
  }

  private boolean matchesStep(Traffic.Reading reading, String column, String value,
      JsonNode stepFilters) {
    var actual = reading.column(column);
    if (actual == null) {
      return false;
    }
    // A value starting or ending with an asterisk matches by pattern, the asterisk standing for
    // any run of characters; anything else has to match outright. SPEC R62.
    boolean matched =
        value.startsWith("*") || value.endsWith("*")
            ? wildcardMatch(actual, value)
            : actual.equals(value);
    if (!matched) {
      return false;
    }
    if (stepFilters == null || stepFilters.isNull() || !stepFilters.isArray()) {
      return true;
    }
    for (var filter : stepFilters) {
      var property = filter.get("property").asText();
      var operator = filter.get("operator").asText();
      var wanted = filter.get("value").asText();
      boolean satisfied = false;
      for (var stored : reading.event().properties()) {
        if (!stored.key().equals(property)) {
          continue;
        }
        var actualValue = io.akka.umami.lib.Values.displayValue(stored);
        satisfied = switch (operator) {
          case Constants.OP_NOT_EQUALS -> !wanted.equals(actualValue);
          case Constants.OP_CONTAINS -> actualValue != null
              && actualValue.toLowerCase(Locale.ROOT).contains(wanted.toLowerCase(Locale.ROOT));
          case Constants.OP_DOES_NOT_CONTAIN -> actualValue == null
              || !actualValue.toLowerCase(Locale.ROOT).contains(wanted.toLowerCase(Locale.ROOT));
          default -> wanted.equals(actualValue);
        };
        if (satisfied) {
          break;
        }
      }
      if (!satisfied) {
        return false;
      }
    }
    return true;
  }

  static boolean wildcardMatch(String actual, String pattern) {
    var parts = pattern.split("\\*", -1);
    var regex = new StringBuilder("^");
    for (int i = 0; i < parts.length; i++) {
      if (i > 0) {
        regex.append(".*");
      }
      regex.append(java.util.regex.Pattern.quote(parts[i]));
    }
    regex.append("$");
    return actual.matches(regex.toString());
  }

  // ------------------------------------------------------------------ retention

  /**
   * How many of each day's new sessions came back.
   *
   * <p>The cohort day is the earliest day of a session's <em>filtered</em> events; what is counted
   * afterwards is <em>unfiltered</em>. SPEC R65.
   */
  public ArrayNode retention(String websiteId, Filters.Query query, String timezone) {
    var selection = loader.select(websiteId, query);
    var cohortDay = new LinkedHashMap<String, String>();
    for (var reading : selection.readings()) {
      var day = Dates.bucket(reading.event().createdAt(), "day", timezone);
      cohortDay.merge(reading.event().sessionId(), day,
          (existing, candidate) -> candidate.compareTo(existing) < 0 ? candidate : existing);
    }
    var cohortSize = new TreeMap<String, Long>();
    cohortDay.values().forEach(day -> cohortSize.merge(day, 1L, Long::sum));

    var returning = new TreeMap<String, Map<Long, Set<String>>>();
    for (var reading : loader.unfiltered(websiteId, query.startDate(), query.endDate())) {
      var day = cohortDay.get(reading.event().sessionId());
      if (day == null) {
        continue;
      }
      var start = LocalDate.parse(day.substring(0, 10));
      var actual = Dates.localDate(reading.event().createdAt(), timezone);
      long number = ChronoUnit.DAYS.between(start, actual);
      if (number < 0 || number > 31) {
        continue;
      }
      returning
          .computeIfAbsent(day, d -> new TreeMap<>())
          .computeIfAbsent(number, n -> new HashSet<>())
          .add(reading.event().sessionId());
    }

    var out = Json.array();
    for (var cohort : returning.entrySet()) {
      long size = cohortSize.getOrDefault(cohort.getKey(), 0L);
      for (var day : cohort.getValue().entrySet()) {
        var row = Json.object();
        row.put("date", cohort.getKey());
        row.put("day", day.getKey());
        row.put("visitors", size);
        row.put("returnVisitors", day.getValue().size());
        row.put("percentage", size == 0 ? 0 : (day.getValue().size() * 100.0) / size);
        out.add(row);
      }
    }
    return out;
  }

  // ------------------------------------------------------------------ journey

  /** The commonest paths through a visit, each step being the event's name or its page. */
  public ArrayNode journey(String websiteId, Filters.Query query, JsonNode parameters) {
    int steps = parameters.get("steps").asInt();
    var startStep = optionalText(parameters, "startStep");
    var endStep = optionalText(parameters, "endStep");
    var selection = loader.select(websiteId, query);

    var byVisit = new LinkedHashMap<String, List<Traffic.Reading>>();
    for (var reading : selection.readings()) {
      byVisit.computeIfAbsent(reading.event().visitId(), v -> new ArrayList<>()).add(reading);
    }
    var sequences = new LinkedHashMap<List<String>, long[]>();
    for (var visit : byVisit.values()) {
      visit.sort(Comparator.comparing(r -> r.event().createdAt()));
      var path = new ArrayList<String>();
      var distinct = new java.util.LinkedHashSet<String>();
      for (var reading : visit) {
        var name = reading.event().eventName();
        var step = name == null || name.isEmpty() ? reading.event().urlPath() : name;
        if (distinct.add(step + "@" + reading.event().id())) {
          path.add(step);
        }
        if (path.size() >= steps) {
          break;
        }
      }
      if (path.isEmpty()) {
        continue;
      }
      if (startStep != null && !startStep.equals(path.get(0))) {
        continue;
      }
      if (endStep != null && !endsAt(path, endStep)) {
        continue;
      }
      sequences.computeIfAbsent(List.copyOf(path), k -> new long[1])[0]++;
    }
    var ordered = new ArrayList<>(sequences.entrySet());
    ordered.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));

    var out = Json.array();
    for (var entry : ordered.subList(0, Math.min(100, ordered.size()))) {
      var row = Json.object();
      var items = Json.array();
      // Seven slots, of which the ones past the steps asked for were never selected at all.
      // Consecutive equal slots collapse to one, and a slot the visit did not reach is a
      // different absence from a slot the query never selected — so a two-step path out of
      // three requested leaves two nulls, one of each kind.
      var path = entry.getKey();
      var slots = new java.util.ArrayList<String>();
      for (int i = 0; i < 7; i++) {
        if (i >= steps) {
          slots.add(NOT_SELECTED);
        } else if (i < path.size()) {
          slots.add(path.get(i));
        } else {
          slots.add(null);
        }
      }
      for (var slot : collapse(slots)) {
        if (slot == null || NOT_SELECTED.equals(slot)) {
          items.addNull();
        } else {
          items.add(slot);
        }
      }
      row.set("items", items);
      row.put("count", entry.getValue()[0]);
      out.add(row);
    }
    return out;
  }

  /** Stands for a dimension value the record never carried, answered back as nothing. */
  private static final String ABSENT = "absent-marker";

  /** A slot the query never selected, which is not the same absence as a step not reached. */
  private static final String NOT_SELECTED = "not-selected-marker";

  private static boolean endsAt(List<String> path, String endStep) {
    for (int i = 0; i < path.size(); i++) {
      if (path.get(i).equals(endStep) && (i == path.size() - 1)) {
        return true;
      }
    }
    return false;
  }

  private static List<String> collapse(List<String> path) {
    var out = new ArrayList<String>();
    for (var step : path) {
      if (out.isEmpty() || !java.util.Objects.equals(out.get(out.size() - 1), step)) {
        out.add(step);
      }
    }
    return out;
  }

  // ------------------------------------------------------------------ goal

  /**
   * How many sessions did one thing, against how many did anything.
   *
   * <p>The denominator drops every event-type condition from the caller's filters, which is why a
   * goal on a named event still counts every session in the window below it. SPEC R67.
   */
  public ObjectNode goal(String websiteId, Filters.Query query, JsonNode parameters) {
    var type = parameters.get("type").asText();
    var value = parameters.get("value").asText();
    int eventType = "path".equals(type) ? Constants.PAGE_VIEW : Constants.CUSTOM_EVENT;
    var column = "path".equals(type) ? "path" : "event";

    var matching = loader.select(websiteId, query.withEventType(eventType));
    var sessions = new HashSet<String>();
    for (var reading : matching.readings()) {
      var actual = reading.column(column);
      if (actual == null) {
        continue;
      }
      boolean matched = value.startsWith("*") || value.endsWith("*")
          ? wildcardMatch(actual, value)
          : actual.equals(value);
      if (matched) {
        sessions.add(reading.event().sessionId());
      }
    }
    var denominatorQuery = query.withEventType(null);
    var all = loader.select(websiteId, denominatorQuery);
    var total = new HashSet<String>();
    all.readings().forEach(reading -> total.add(reading.event().sessionId()));

    var out = Json.object();
    out.put("num", sessions.size());
    out.put("total", total.size());
    return out;
  }

  // ------------------------------------------------------------------ attribution

  /** What a converting session touched: the earliest event, or the latest one before it. */
  public ObjectNode attribution(String websiteId, Filters.Query query, JsonNode parameters) {
    var model = parameters.get("model").asText();
    var type = parameters.get("type").asText();
    var step = parameters.get("step").asText();
    int eventType = "path".equals(type) ? Constants.PAGE_VIEW : Constants.CUSTOM_EVENT;
    var column = "path".equals(type) ? "path" : "event";

    var converting = loader.select(websiteId, query.withEventType(eventType));
    var conversion = new HashMap<String, Instant>();
    long pageviews = 0;
    var visitors = new HashSet<String>();
    var visits = new HashSet<String>();
    for (var reading : converting.readings()) {
      if (!step.equals(reading.column(column))) {
        continue;
      }
      pageviews++;
      visitors.add(reading.event().sessionId());
      visits.add(reading.event().visitId());
      conversion.merge(reading.event().sessionId(), reading.event().createdAt(),
          (existing, candidate) -> candidate.isAfter(existing) ? candidate : existing);
    }

    var all = loader.unfiltered(websiteId, query.startDate(), query.endDate());
    var attributed = new HashMap<String, Traffic.Reading>();
    for (var reading : all) {
      var sessionId = reading.event().sessionId();
      var conversionAt = conversion.get(sessionId);
      if (conversionAt == null) {
        continue;
      }
      var at = reading.event().createdAt();
      var current = attributed.get(sessionId);
      if ("first-click".equals(model)) {
        if (current == null || at.isBefore(current.event().createdAt())) {
          attributed.put(sessionId, reading);
        }
      } else {
        if (!at.isBefore(conversionAt)) {
          continue;
        }
        if (current == null || at.isAfter(current.event().createdAt())) {
          attributed.put(sessionId, reading);
        }
      }
    }

    var out = Json.object();
    out.set("referrer", topBy(attributed.values(), r -> {
      var referrer = r.event().referrerDomain();
      var host = Filters.stripWww(r.event().hostname());
      if (referrer == null || referrer.isEmpty() || referrer.equals(host)) {
        return null;
      }
      return referrer;
    }));
    out.set("paidAds", topBy(attributed.values(), Reports::paidNetwork));
    out.set("utm_source", topBy(attributed.values(), r -> blankToNull(r.event().utmSource())));
    out.set("utm_medium", topBy(attributed.values(), r -> blankToNull(r.event().utmMedium())));
    out.set("utm_campaign", topBy(attributed.values(), r -> blankToNull(r.event().utmCampaign())));
    out.set("utm_content", topBy(attributed.values(), r -> blankToNull(r.event().utmContent())));
    out.set("utm_term", topBy(attributed.values(), r -> blankToNull(r.event().utmTerm())));
    var total = Json.object();
    total.put("pageviews", pageviews);
    total.put("visitors", visitors.size());
    total.put("visits", visits.size());
    out.set("total", total);
    return out;
  }

  /** Which advertising network a click identifier names. */
  static String paidNetwork(Traffic.Reading reading) {
    var event = reading.event();
    if (notBlank(event.gclid())) {
      return "Google Ads";
    }
    if (notBlank(event.fbclid())) {
      return "Facebook / Meta";
    }
    if (notBlank(event.msclkid())) {
      return "Microsoft Ads";
    }
    if (notBlank(event.ttclid())) {
      return "TikTok Ads";
    }
    if (notBlank(event.lifatid())) {
      return "LinkedIn Ads";
    }
    if (notBlank(event.twclid())) {
      return "Twitter Ads (X)";
    }
    return null;
  }

  private static ArrayNode topBy(java.util.Collection<Traffic.Reading> readings,
      java.util.function.Function<Traffic.Reading, String> naming) {
    var counts = new LinkedHashMap<String, Set<String>>();
    for (var reading : readings) {
      var name = naming.apply(reading);
      if (name == null || name.isEmpty()) {
        continue;
      }
      counts.computeIfAbsent(name, n -> new HashSet<>()).add(reading.event().sessionId());
    }
    var ordered = new ArrayList<>(counts.entrySet());
    ordered.sort((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()));
    var out = Json.array();
    for (var entry : ordered.subList(0, Math.min(20, ordered.size()))) {
      var row = Json.object();
      row.put("name", entry.getKey());
      row.put("value", entry.getValue().size());
      out.add(row);
    }
    return out;
  }

  // ------------------------------------------------------------------ breakdown

  /** Any set of dimensions grouped together, over page views alone. */
  public ArrayNode breakdown(String websiteId, Filters.Query query, JsonNode parameters) {
    var fields = new ArrayList<String>();
    parameters.get("fields").forEach(field -> fields.add(field.asText()));
    var effective = query.withEventType(Constants.PAGE_VIEW);
    var selection = loader.select(websiteId, effective);
    var namedVisits = rollup.namedEventVisits(websiteId, query);

    var grouped = new LinkedHashMap<List<String>, Map<Loader.VisitKey, int[]>>();
    var bounds = new LinkedHashMap<List<String>, Map<Loader.VisitKey, Instant[]>>();
    for (var reading : selection.readings()) {
      var values = new ArrayList<String>();
      for (var field : fields) {
        values.add(Rollup.nullToEmpty(rollup.dimensionValue(reading, field, null)));
      }
      var key = List.copyOf(values);
      var visitKey = new Loader.VisitKey(reading.event().sessionId(), reading.event().visitId());
      grouped.computeIfAbsent(key, k -> new LinkedHashMap<>())
          .computeIfAbsent(visitKey, v -> new int[1])[0]++;
      var extremes =
          bounds.computeIfAbsent(key, k -> new LinkedHashMap<>())
              .computeIfAbsent(visitKey, v -> new Instant[2]);
      if (extremes[0] == null || reading.event().createdAt().isBefore(extremes[0])) {
        extremes[0] = reading.event().createdAt();
      }
      if (extremes[1] == null || reading.event().createdAt().isAfter(extremes[1])) {
        extremes[1] = reading.event().createdAt();
      }
    }

    var rows = new ArrayList<ObjectNode>();
    for (var entry : grouped.entrySet()) {
      long views = 0;
      long bounces = 0;
      long totaltime = 0;
      var sessions = new HashSet<String>();
      for (var visit : entry.getValue().entrySet()) {
        int count = visit.getValue()[0];
        views += count;
        sessions.add(visit.getKey().sessionId());
        if (count == 1 && !namedVisits.contains(visit.getKey())) {
          bounces++;
        }
        var extremes = bounds.get(entry.getKey()).get(visit.getKey());
        if (extremes[0] != null && extremes[1] != null) {
          totaltime += Math.max(0, (extremes[1].toEpochMilli() - extremes[0].toEpochMilli()) / 1000);
        }
      }
      var row = Json.object();
      row.put("views", views);
      row.put("visitors", sessions.size());
      row.put("visits", entry.getValue().size());
      row.put("bounces", query.excludeBounce() ? 0 : bounces);
      row.put("totaltime", totaltime);
      for (int i = 0; i < fields.size(); i++) {
        row.put(fields.get(i), entry.getKey().get(i));
      }
      rows.add(row);
    }
    rows.sort(
        Comparator.comparingLong((ObjectNode row) -> row.get("visitors").asLong())
            .thenComparingLong(row -> row.get("views").asLong())
            .reversed());
    var out = Json.array();
    rows.subList(0, Math.min(Constants.METRIC_LIMIT, rows.size())).forEach(out::add);
    return out;
  }

  // ------------------------------------------------------------------ campaigns

  public ObjectNode utm(String websiteId, Filters.Query query) {
    var effective = query.withEventType(Constants.PAGE_VIEW);
    var selection = loader.select(websiteId, effective);
    var out = Json.object();
    out.set("utm_source", campaignColumn(selection, Traffic.Event::utmSource));
    out.set("utm_medium", campaignColumn(selection, Traffic.Event::utmMedium));
    out.set("utm_campaign", campaignColumn(selection, Traffic.Event::utmCampaign));
    out.set("utm_term", campaignColumn(selection, Traffic.Event::utmTerm));
    out.set("utm_content", campaignColumn(selection, Traffic.Event::utmContent));
    return out;
  }

  private static ArrayNode campaignColumn(Loader.Selection selection,
      java.util.function.Function<Traffic.Event, String> reading) {
    var counts = new LinkedHashMap<String, long[]>();
    for (var row : selection.readings()) {
      var value = reading.apply(row.event());
      if (value == null || value.isEmpty()) {
        continue;
      }
      counts.computeIfAbsent(value, v -> new long[1])[0]++;
    }
    var ordered = new ArrayList<>(counts.entrySet());
    ordered.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
    var out = Json.array();
    for (var entry : ordered.subList(0, Math.min(50, ordered.size()))) {
      var row = Json.object();
      row.put("utm", entry.getKey());
      row.put("views", entry.getValue()[0]);
      out.add(row);
    }
    return out;
  }

  // ------------------------------------------------------------------ revenue

  /** The revenue records of the sessions the filters selected, in one currency. */
  private List<Traffic.Revenue> revenueOf(String websiteId, Filters.Query query, String currency) {
    var selection = loader.select(websiteId, query.withEventType(null));
    var sessions = new HashSet<String>();
    for (var reading : selection.readings()) {
      if (reading.event().eventType() != Constants.PERFORMANCE_EVENT) {
        sessions.add(reading.event().sessionId());
      }
    }
    var out = new ArrayList<Traffic.Revenue>();
    for (var record : store.revenue(websiteId, query.startDate(), query.endDate())) {
      if (!sessions.contains(record.sessionId())) {
        continue;
      }
      if (currency != null
          && !currency.toUpperCase(Locale.ROOT)
              .equals(Rollup.nullToEmpty(record.currency()).toUpperCase(Locale.ROOT))) {
        continue;
      }
      out.add(record);
    }
    return out;
  }

  public ObjectNode revenueStats(String websiteId, Filters.Query query, String currency) {
    var records = revenueOf(websiteId, query, currency);
    var selection = loader.select(websiteId, query.withEventType(null));
    var allSessions = new HashSet<String>();
    selection.readings().forEach(reading -> {
      if (reading.event().eventType() != Constants.PERFORMANCE_EVENT) {
        allSessions.add(reading.event().sessionId());
      }
    });
    var sum = BigDecimal.ZERO;
    var events = new HashSet<String>();
    var sessions = new HashSet<String>();
    for (var record : records) {
      if (record.revenue() != null) {
        sum = sum.add(record.revenue());
      }
      events.add(record.eventId());
      sessions.add(record.sessionId());
    }
    var out = Json.object();
    // A decimal column is answered as text and a figure worked out afterwards as a number.
    // That is not tidy and it is what a client of this interface is written against.
    if (records.isEmpty()) {
      out.putNull("sum");
    } else {
      out.put("sum", sum.toPlainString());
    }
    out.put("count", events.size());
    out.put("unique_count", sessions.size());
    out.put("total_sessions", allSessions.size());
    out.put("average", events.isEmpty() ? 0 : sum.doubleValue() / events.size());
    out.put("arpu", allSessions.isEmpty() ? 0 : sum.doubleValue() / allSessions.size());
    return out;
  }

  public ArrayNode revenueChart(String websiteId, Filters.Query query, String currency,
      String unit, String timezone) {
    var records = revenueOf(websiteId, query, currency);
    var grouped = new LinkedHashMap<String, Map<String, BigDecimal[]>>();
    for (var record : records) {
      var bucket = Dates.bucket(record.createdAt(), unit, timezone);
      var name = Rollup.nullToEmpty(record.eventName());
      var totals =
          grouped
              .computeIfAbsent(bucket, b -> new LinkedHashMap<>())
              .computeIfAbsent(name, n -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
      totals[0] = totals[0].add(record.revenue() == null ? BigDecimal.ZERO : record.revenue());
      totals[1] = totals[1].add(BigDecimal.ONE);
    }
    var out = Json.array();
    for (var bucket : new TreeMap<>(grouped).entrySet()) {
      for (var name : bucket.getValue().entrySet()) {
        var row = Json.object();
        row.put("x", name.getKey());
        row.put("t", bucket.getKey());
        row.put("y", name.getValue()[0].toPlainString());
        row.put("count", name.getValue()[1].longValue());
        out.add(row);
      }
    }
    return out;
  }

  /**
   * Revenue by one dimension. Broken down by referrer or by channel, a session's whole revenue is
   * attributed to the <em>first</em> event of that session. SPEC R73.
   */
  public ArrayNode revenueMetrics(String websiteId, Filters.Query query, String currency,
      String type) {
    var records = revenueOf(websiteId, query, currency);
    var perSession = new LinkedHashMap<String, BigDecimal>();
    for (var record : records) {
      perSession.merge(record.sessionId(),
          record.revenue() == null ? BigDecimal.ZERO : record.revenue(), BigDecimal::add);
    }
    var sessions = loader.sessionsOf(websiteId);
    var firstEvent = new HashMap<String, Traffic.Reading>();
    for (var reading : loader.unfiltered(websiteId, query.startDate(), query.endDate())) {
      var current = firstEvent.get(reading.event().sessionId());
      if (current == null || reading.event().createdAt().isBefore(current.event().createdAt())) {
        firstEvent.put(reading.event().sessionId(), reading);
      }
    }

    // A dimension value the record never carried is nothing at all rather than an empty
    // string, so the map holds a null key where that happens.
    var totals = new LinkedHashMap<String, BigDecimal>();
    var countries = new LinkedHashMap<String, String>();
    var hasCountry = new java.util.HashSet<String>();
    for (var entry : perSession.entrySet()) {
      String name;
      String country = null;
      switch (type) {
        case "country" -> {
          var session = sessions.get(entry.getKey());
          name = session == null ? null : session.country();
        }
        case "region" -> {
          var session = sessions.get(entry.getKey());
          name = session == null ? null : session.region();
          country = session == null ? null : session.country();
        }
        case "referrer" -> {
          var reading = firstEvent.get(entry.getKey());
          name = reading == null ? null : reading.event().referrerDomain();
        }
        default -> {
          var reading = firstEvent.get(entry.getKey());
          if (reading == null) {
            name = "Unknown";
          } else {
            var channel =
                Channels.classify(
                    reading.event().referrerDomain(),
                    reading.event().urlQuery(),
                    reading.event().utmMedium(),
                    reading.event().utmSource(),
                    reading.event().hostname());
            name = channel.isEmpty() ? "Unknown" : channel;
          }
        }
      }
      var key = name == null ? ABSENT : name;
      totals.merge(key, entry.getValue(), BigDecimal::add);
      if ("region".equals(type)) {
        hasCountry.add(key);
        if (country != null) {
          countries.put(key, country);
        }
      }
    }
    var ordered = new ArrayList<>(totals.entrySet());
    ordered.sort((a, b) -> b.getValue().compareTo(a.getValue()));
    var out = Json.array();
    for (var entry : ordered) {
      var row = Json.object();
      if (hasCountry.contains(entry.getKey())) {
        row.put("country", countries.get(entry.getKey()));
      }
      if (ABSENT.equals(entry.getKey())) {
        row.putNull("name");
      } else {
        row.put("name", entry.getKey());
      }
      row.put("value", entry.getValue().toPlainString());
      out.add(row);
    }
    return out;
  }

  // ------------------------------------------------------------------ performance

  /** The web vitals: three percentiles of each, over the events that carry them. */
  public ObjectNode performance(String websiteId, Filters.Query query, String metric, String unit,
      String timezone) {
    var selection = loader.select(websiteId, query.withEventType(Constants.PERFORMANCE_EVENT));
    var out = Json.object();

    var buckets = new TreeMap<String, List<Double>>();
    for (var reading : selection.readings()) {
      var value = vital(reading.event(), metric);
      if (value == null) {
        continue;
      }
      buckets
          .computeIfAbsent(Dates.bucket(reading.event().createdAt(), unit, timezone),
              b -> new ArrayList<>())
          .add(value);
    }
    var chart = Json.array();
    for (var entry : buckets.entrySet()) {
      var row = Json.object();
      row.put("t", entry.getKey());
      row.put("p50", percentile(entry.getValue(), 0.5));
      row.put("p75", percentile(entry.getValue(), 0.75));
      row.put("p95", percentile(entry.getValue(), 0.95));
      chart.add(row);
    }
    out.set("chart", chart);
    out.set("summary", performanceSummary(selection.readings()));
    return out;
  }

  public ObjectNode performanceSummary(List<Traffic.Reading> readings) {
    var summary = Json.object();
    for (var name : List.of("lcp", "inp", "cls", "fcp", "ttfb")) {
      var values = new ArrayList<Double>();
      for (var reading : readings) {
        var value = vital(reading.event(), name);
        if (value != null) {
          values.add(value);
        }
      }
      var row = Json.object();
      row.put("p50", percentile(values, 0.5));
      row.put("p75", percentile(values, 0.75));
      row.put("p95", percentile(values, 0.95));
      summary.set(name, row);
    }
    summary.put("count", readings.size());
    return summary;
  }

  /** The same three percentiles per dimension value, ordered by the middle one descending. */
  public ArrayNode performanceMetrics(String websiteId, Filters.Query query, String dimension,
      String metric, Integer limit) {
    var selection = loader.select(websiteId, query.withEventType(Constants.PERFORMANCE_EVENT));
    // Grouped on the column as it stands, so a dimension nothing was recorded for is one row
    // named null rather than one named the empty string.
    var grouped = new LinkedHashMap<java.util.Optional<String>, List<Double>>();
    var counts = new LinkedHashMap<java.util.Optional<String>, long[]>();
    for (var reading : selection.readings()) {
      var name = java.util.Optional.ofNullable(reading.column(dimension));
      var value = vital(reading.event(), metric);
      grouped.computeIfAbsent(name, n -> new ArrayList<>());
      if (value != null) {
        grouped.get(name).add(value);
      }
      counts.computeIfAbsent(name, n -> new long[1])[0]++;
    }
    var rows = new ArrayList<ObjectNode>();
    for (var entry : grouped.entrySet()) {
      var row = Json.object();
      row.put("name", entry.getKey().orElse(null));
      row.put("p50", percentile(entry.getValue(), 0.5));
      row.put("p75", percentile(entry.getValue(), 0.75));
      row.put("p95", percentile(entry.getValue(), 0.95));
      row.put("count", counts.get(entry.getKey())[0]);
      rows.add(row);
    }
    rows.sort(Comparator.comparingDouble((ObjectNode row) -> row.get("p75").asDouble()).reversed());
    var out = Json.array();
    int end = limit == null ? rows.size() : Math.min(limit, rows.size());
    rows.subList(0, end).forEach(out::add);
    return out;
  }

  static Double vital(Traffic.Event event, String name) {
    var value = switch (name) {
      case "inp" -> event.inp();
      case "cls" -> event.cls();
      case "fcp" -> event.fcp();
      case "ttfb" -> event.ttfb();
      default -> event.lcp();
    };
    return value == null ? null : value.doubleValue();
  }

  /** The continuous percentile, which is what the store's own ordered-set aggregate computes. */
  static double percentile(List<Double> values, double fraction) {
    if (values == null || values.isEmpty()) {
      return 0;
    }
    var sorted = new ArrayList<>(values);
    sorted.sort(Double::compareTo);
    if (sorted.size() == 1) {
      return sorted.get(0);
    }
    double position = fraction * (sorted.size() - 1);
    int lower = (int) Math.floor(position);
    int upper = (int) Math.ceil(position);
    if (lower == upper) {
      return sorted.get(lower);
    }
    return sorted.get(lower) + (position - lower) * (sorted.get(upper) - sorted.get(lower));
  }

  // ------------------------------------------------------------------ heatmap

  /**
   * Where a page was clicked, or how far down it was read.
   *
   * <p>A filter on the path applies to the recorded rows themselves; every other filter selects the
   * visits those rows have to belong to. SPEC R75.
   */
  public ObjectNode heatmap(String websiteId, Filters.Query query, String urlPath, String mode) {
    int eventType = "scroll".equals(mode) ? Constants.HEATMAP_SCROLL : Constants.HEATMAP_CLICK;
    var rows = store.heatmap(websiteId, query.startDate(), query.endDate());

    var pathClauses = new ArrayList<Filters.Clause>();
    var otherClauses = new ArrayList<Filters.Clause>();
    for (var clause : query.clauses()) {
      if (clause.baseName().equals("path")) {
        pathClauses.add(clause);
      } else {
        otherClauses.add(clause);
      }
    }
    Set<String> allowedVisits = null;
    if (!otherClauses.isEmpty() || !query.eventPropertyFilters().isEmpty()
        || !query.sessionPropertyFilters().isEmpty() || query.cohort() != null) {
      allowedVisits = new HashSet<>();
      for (var reading : loader.select(websiteId, query).readings()) {
        allowedVisits.add(reading.event().visitId());
      }
    }

    var kept = new ArrayList<io.akka.umami.domain.Recordings.HeatmapEvent>();
    for (var row : rows) {
      if (row.eventType() != eventType) {
        continue;
      }
      if (allowedVisits != null && !allowedVisits.contains(row.visitId())) {
        continue;
      }
      if (!matchesPathClauses(row.urlPath(), pathClauses, query.isAnyMatch())) {
        continue;
      }
      kept.add(row);
    }

    var out = Json.object();
    out.put("mode", "scroll".equals(mode) ? "scroll" : "click");

    var pageCounts = new LinkedHashMap<String, long[]>();
    var pageSessions = new LinkedHashMap<String, Set<String>>();
    for (var row : kept) {
      pageCounts.computeIfAbsent(row.urlPath(), p -> new long[1])[0]++;
      pageSessions.computeIfAbsent(row.urlPath(), p -> new HashSet<>()).add(row.visitId());
    }
    var pages = new ArrayList<ObjectNode>();
    for (var entry : pageCounts.entrySet()) {
      var row = Json.object();
      row.put("urlPath", entry.getKey());
      row.put("count", entry.getValue()[0]);
      row.put("sessions", pageSessions.get(entry.getKey()).size());
      pages.add(row);
    }
    pages.sort(
        Comparator.comparingLong((ObjectNode row) -> row.get("sessions").asLong())
            .thenComparingLong(row -> row.get("count").asLong())
            .reversed());
    var pageArray = Json.array();
    pages.subList(0, Math.min(Constants.HEATMAP_PAGE_LIMIT, pages.size())).forEach(pageArray::add);
    out.set("pages", pageArray);

    if (urlPath == null || urlPath.isBlank()) {
      out.set("points", Json.array());
      out.set("scroll", emptyScroll());
      out.putNull("snapshot");
      return out;
    }

    var forPath = kept.stream().filter(row -> urlPath.equals(row.urlPath())).toList();
    if ("scroll".equals(mode)) {
      out.set("points", Json.array());
      out.set("scroll", scrollBuckets(forPath));
    } else {
      out.set("points", clickPoints(forPath));
      out.set("scroll", emptyScroll());
    }
    out.set("snapshot", snapshot(websiteId, urlPath, forPath));
    return out;
  }

  private static boolean matchesPathClauses(String path, List<Filters.Clause> clauses,
      boolean any) {
    if (clauses.isEmpty()) {
      return true;
    }
    boolean seenTrue = false;
    for (var clause : clauses) {
      boolean matched = Filters.matchesClause(clause, name -> "path".equals(name) ? path : null);
      if (any && matched) {
        seenTrue = true;
      }
      if (!any && !matched) {
        return false;
      }
    }
    return !any || seenTrue;
  }

  private static ArrayNode clickPoints(List<io.akka.umami.domain.Recordings.HeatmapEvent> rows) {
    var counts = new LinkedHashMap<String, long[]>();
    var samples = new LinkedHashMap<String, io.akka.umami.domain.Recordings.HeatmapEvent>();
    for (var row : rows) {
      var key = row.x() + "," + row.y() + "," + row.pageX() + "," + row.pageY() + "," + row.pageW()
          + "," + row.pageH() + "," + row.viewportW() + "," + row.viewportH();
      counts.computeIfAbsent(key, k -> new long[1])[0]++;
      samples.putIfAbsent(key, row);
    }
    var ordered = new ArrayList<>(counts.entrySet());
    ordered.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
    var out = Json.array();
    for (var entry : ordered.subList(0, Math.min(Constants.HEATMAP_POINT_LIMIT, ordered.size()))) {
      var sample = samples.get(entry.getKey());
      var row = Json.object();
      row.put("x", sample.x());
      row.put("y", sample.y());
      row.put("pageX", sample.pageX());
      row.put("pageY", sample.pageY());
      row.put("pageW", sample.pageW());
      row.put("pageH", sample.pageH());
      row.put("viewportW", sample.viewportW());
      row.put("viewportH", sample.viewportH());
      row.put("count", entry.getValue()[0]);
      out.add(row);
    }
    return out;
  }

  private static ObjectNode scrollBuckets(List<io.akka.umami.domain.Recordings.HeatmapEvent> rows) {
    var deepest = new LinkedHashMap<String, io.akka.umami.domain.Recordings.HeatmapEvent>();
    for (var row : rows) {
      var current = deepest.get(row.visitId());
      if (current == null
          || nullToZero(row.scrollPct()) > nullToZero(current.scrollPct())) {
        deepest.put(row.visitId(), row);
      }
    }
    var buckets = new TreeMap<Integer, long[]>();
    var geometry = new HashMap<Integer, io.akka.umami.domain.Recordings.HeatmapEvent>();
    for (var row : deepest.values()) {
      int depth = (nullToZero(row.scrollPct()) / Constants.SCROLL_BUCKET_SIZE)
          * Constants.SCROLL_BUCKET_SIZE;
      buckets.computeIfAbsent(depth, d -> new long[1])[0]++;
      geometry.putIfAbsent(depth, row);
    }
    var out = Json.object();
    var array = Json.array();
    long total = 0;
    for (var entry : buckets.entrySet()) {
      var sample = geometry.get(entry.getKey());
      var row = Json.object();
      row.put("depth", entry.getKey());
      row.put("sessions", entry.getValue()[0]);
      row.put("pageW", sample.pageW());
      row.put("pageH", sample.pageH());
      row.put("viewportW", sample.viewportW());
      row.put("viewportH", sample.viewportH());
      array.add(row);
      total += entry.getValue()[0];
    }
    out.set("buckets", array);
    out.put("totalSessions", total);
    // The geometry the snapshot is taken at: the viewport the most sessions used, and the
    // widest and tallest page seen at it.
    var viewports = new LinkedHashMap<List<Integer>, long[]>();
    var extent = new LinkedHashMap<List<Integer>, int[]>();
    for (var element : array) {
      var key = List.of(element.get("viewportW").asInt(), element.get("viewportH").asInt());
      viewports.computeIfAbsent(key, v -> new long[1])[0] += element.get("sessions").asLong();
      var reach = extent.computeIfAbsent(key, v -> new int[2]);
      reach[0] = Math.max(reach[0], element.get("pageW").asInt());
      reach[1] = Math.max(reach[1], element.get("pageH").asInt());
    }
    List<Integer> best = null;
    for (var entry : viewports.entrySet()) {
      if (best == null || entry.getValue()[0] > viewports.get(best)[0]) {
        best = entry.getKey();
      }
    }
    if (best == null) {
      out.putNull("pageW");
      out.putNull("pageH");
      out.putNull("viewportW");
      out.putNull("viewportH");
    } else {
      out.put("pageW", extent.get(best)[0]);
      out.put("pageH", extent.get(best)[1]);
      out.put("viewportW", best.get(0));
      out.put("viewportH", best.get(1));
    }
    return out;
  }

  /** The scroll answer where there is nothing to answer with: every key, none of them filled. */
  private static ObjectNode emptyScroll() {
    var out = Json.object();
    out.set("buckets", Json.array());
    out.put("totalSessions", 0);
    out.putNull("pageW");
    out.putNull("pageH");
    out.putNull("viewportW");
    out.putNull("viewportH");
    return out;
  }

  private JsonNode snapshot(String websiteId, String urlPath,
      List<io.akka.umami.domain.Recordings.HeatmapEvent> rows) {
    if (rows.isEmpty()) {
      return Json.object().nullNode();
    }
    var busiest = rows.get(0);
    var counts = new LinkedHashMap<String, long[]>();
    for (var row : rows) {
      counts.computeIfAbsent(row.viewportW() + "x" + row.viewportH(), k -> new long[1])[0]++;
    }
    long best = -1;
    String bestViewport = null;
    for (var entry : counts.entrySet()) {
      if (entry.getValue()[0] > best) {
        best = entry.getValue()[0];
        bestViewport = entry.getKey();
      }
    }
    int pageW = 0;
    int pageH = 0;
    for (var row : rows) {
      if ((row.viewportW() + "x" + row.viewportH()).equals(bestViewport)) {
        pageW = Math.max(pageW, nullToZero(row.pageW()));
        pageH = Math.max(pageH, nullToZero(row.pageH()));
        busiest = row;
      }
    }
    if (pageW == 0 || pageH == 0 || busiest.viewportW() == null) {
      return Json.object().nullNode();
    }
    var website = store.website(websiteId);
    var domain = website == null || website.domain() == null ? "" : website.domain().split(",")[0];
    var scheme =
        domain.startsWith("localhost") || domain.startsWith("127.0.0.1") || domain.startsWith("[::1]")
            ? "http://"
            : "https://";
    var out = Json.object();
    out.put("kind", "iframe");
    out.put("id", "iframe:" + websiteId + ":" + urlPath + ":" + busiest.viewportW() + "x"
        + busiest.viewportH());
    out.put("url", scheme + domain + urlPath);
    out.put("pageW", pageW);
    out.put("pageH", Math.min(Math.max(pageH, 640), 1080));
    out.put("viewportW", busiest.viewportW());
    out.put("viewportH", busiest.viewportH());
    return out;
  }

  private static int nullToZero(Integer value) {
    return value == null ? 0 : value;
  }

  private static String optionalText(JsonNode node, String field) {
    var value = node.get(field);
    return value == null || value.isNull() || value.asText().isEmpty() ? null : value.asText();
  }

  private static String blankToNull(String value) {
    return value == null || value.isEmpty() ? null : value;
  }

  private static boolean notBlank(String value) {
    return value != null && !value.isEmpty();
  }

  public Rollup rollup() {
    return rollup;
  }
}
