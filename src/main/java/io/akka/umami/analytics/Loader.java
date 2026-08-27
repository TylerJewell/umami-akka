package io.akka.umami.analytics;

import io.akka.umami.application.Store;
import io.akka.umami.domain.Traffic;
import io.akka.umami.lib.Constants;
import io.akka.umami.lib.Filters;
import io.akka.umami.lib.Values;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turning a request into the rows an answer is computed from.
 *
 * <p>Everything downstream reads a {@link Reading}: one event with its session's own fields
 * resolved. The order the restrictions are applied in is the order the original's query applies
 * them, and it matters — the set a bounce is decided over is not the set a filter selected.
 */
public final class Loader {

  private final Store store;

  public Loader(Store store) {
    this.store = store;
  }

  /** One session and visit together, which is the unit almost every figure is grouped by. */
  public record VisitKey(String sessionId, String visitId) {}

  /**
   * What one visit did, over whichever set of events the caller's question is about.
   *
   * @param views how many view-like events the visit holds
   * @param first the earliest view-like instant
   * @param last the latest view-like instant
   * @param hasNamedEvent whether the visit holds an event of type 2 — which, under an event-level
   *     filter, is answered from a lookup the filter does not reach
   */
  public record Visit(VisitKey key, int views, Instant first, Instant last, boolean hasNamedEvent) {

    public long seconds() {
      if (first == null || last == null) {
        return 0;
      }
      return Math.max(0, (last.toEpochMilli() - first.toEpochMilli()) / 1000);
    }

    /** A visit with one view and nothing named. SPEC R26. */
    public boolean isBounce() {
      return views == 1 && !hasNamedEvent;
    }
  }

  /** Every row a question is answered over, with the pieces the answer will need again. */
  public record Selection(
      List<Traffic.Reading> readings,
      Map<String, Traffic.Session> sessions,
      Filters.Query query,
      String websiteId) {}

  /**
   * The events of one website in one window, joined to their sessions and narrowed by every
   * restriction the request carries.
   */
  public Selection select(String websiteId, Filters.Query query) {
    var sessions = sessionsOf(websiteId);
    var events = store.events(websiteId, query.startDate(), query.endDate());

    Set<String> cohortSessions = cohortSessions(websiteId, query, sessions);
    Set<VisitKey> nonBouncing =
        query.excludeBounce() ? nonBouncingVisits(websiteId, query) : null;

    var readings = new ArrayList<Traffic.Reading>();
    for (var event : events) {
      if (cohortSessions != null && !cohortSessions.contains(event.sessionId())) {
        continue;
      }
      if (nonBouncing != null
          && !nonBouncing.contains(new VisitKey(event.sessionId(), event.visitId()))) {
        continue;
      }
      var reading = new Traffic.Reading(event, sessions.get(event.sessionId()));
      if (query.eventType() != null && event.eventType() != query.eventType()) {
        continue;
      }
      if (!Filters.matches(query, reading)) {
        continue;
      }
      if (!matchesEventProperties(query, event)) {
        continue;
      }
      if (!matchesSessionProperties(websiteId, query, event)) {
        continue;
      }
      readings.add(reading);
    }
    return new Selection(readings, sessions, query, websiteId);
  }

  /** The same window with no filter at all, which is what several rules are decided over. */
  public List<Traffic.Reading> unfiltered(String websiteId, Instant from, Instant to) {
    var sessions = sessionsOf(websiteId);
    var out = new ArrayList<Traffic.Reading>();
    for (var event : store.events(websiteId, from, to)) {
      out.add(new Traffic.Reading(event, sessions.get(event.sessionId())));
    }
    return out;
  }

  public Map<String, Traffic.Session> sessionsOf(String websiteId) {
    var out = new HashMap<String, Traffic.Session>();
    for (var session : store.sessions(websiteId)) {
      out.put(session.id(), session);
    }
    return out;
  }

  /**
   * What each visit did.
   *
   * <p>Without an event-level filter the set is every event of the window bar the vitals rows, the
   * view count is the view-like ones among them, and a visit with no views at all is dropped. With
   * one, the set is the filtered view-like events and the named-event flag comes from a second,
   * unfiltered pass — which is why a filter that matches one page still does not turn a visit into
   * a bounce when something else in it was named. SPEC R28, R29.
   */
  public Map<VisitKey, Visit> visits(Selection selection) {
    var query = selection.query();
    var grouped = new LinkedHashMap<VisitKey, int[]>();
    var extremes = new HashMap<VisitKey, Instant[]>();
    var named = new HashSet<VisitKey>();

    boolean filtered = query.hasEventFilters();
    for (var reading : selection.readings()) {
      var event = reading.event();
      if (event.eventType() == Constants.PERFORMANCE_EVENT) {
        continue;
      }
      if (filtered && !event.isViewLike()) {
        continue;
      }
      var key = new VisitKey(event.sessionId(), event.visitId());
      var counts = grouped.computeIfAbsent(key, k -> new int[1]);
      if (event.isViewLike()) {
        counts[0]++;
        var bounds = extremes.computeIfAbsent(key, k -> new Instant[2]);
        if (bounds[0] == null || event.createdAt().isBefore(bounds[0])) {
          bounds[0] = event.createdAt();
        }
        if (bounds[1] == null || event.createdAt().isAfter(bounds[1])) {
          bounds[1] = event.createdAt();
        }
      }
      if (!filtered && event.isNamed()) {
        named.add(key);
      }
    }

    if (filtered && !query.excludeBounce()) {
      for (var reading : unfiltered(selection.websiteId(), query.startDate(), query.endDate())) {
        if (reading.event().isNamed()) {
          named.add(new VisitKey(reading.event().sessionId(), reading.event().visitId()));
        }
      }
    }

    var out = new LinkedHashMap<VisitKey, Visit>();
    for (var entry : grouped.entrySet()) {
      int views = entry.getValue()[0];
      if (!filtered && views == 0) {
        continue;
      }
      var bounds = extremes.getOrDefault(entry.getKey(), new Instant[2]);
      out.put(
          entry.getKey(),
          new Visit(entry.getKey(), views, bounds[0], bounds[1], named.contains(entry.getKey())));
    }
    return out;
  }

  /**
   * The visits that are not bounces: two or more views, or one view and at least one named event.
   * Computed over the unfiltered window, which is what the original's own join does.
   */
  private Set<VisitKey> nonBouncingVisits(String websiteId, Filters.Query query) {
    var views = new HashMap<VisitKey, int[]>();
    for (var reading : unfiltered(websiteId, query.startDate(), query.endDate())) {
      var event = reading.event();
      if (event.eventType() == Constants.PERFORMANCE_EVENT) {
        continue;
      }
      var key = new VisitKey(event.sessionId(), event.visitId());
      var counts = views.computeIfAbsent(key, k -> new int[2]);
      if (event.isViewLike()) {
        counts[0]++;
      }
      if (event.isNamed()) {
        counts[1]++;
      }
    }
    var out = new HashSet<VisitKey>();
    for (var entry : views.entrySet()) {
      int viewCount = entry.getValue()[0];
      int namedCount = entry.getValue()[1];
      if (viewCount > 1 || (viewCount == 1 && namedCount > 0)) {
        out.add(entry.getKey());
      }
    }
    return out;
  }

  /** The sessions a cohort selected, over the cohort's own range rather than the request's. */
  private Set<String> cohortSessions(String websiteId, Filters.Query query,
      Map<String, Traffic.Session> sessions) {
    var cohort = query.cohort();
    if (cohort == null) {
      return null;
    }
    var out = new HashSet<String>();
    for (var event : store.events(websiteId, cohort.startDate(), cohort.endDate())) {
      var reading = new Traffic.Reading(event, sessions.get(event.sessionId()));
      boolean matched;
      if ("any".equals(cohort.match())) {
        matched = false;
        for (var clause : cohort.clauses()) {
          if (clause.baseName().equals(cohort.actionName())) {
            continue;
          }
          if (Filters.matchesClause(clause, reading)) {
            matched = true;
            break;
          }
        }
        for (var clause : cohort.clauses()) {
          if (clause.baseName().equals(cohort.actionName())
              && !Filters.matchesClause(clause, reading)) {
            matched = false;
          }
        }
      } else {
        matched = true;
        for (var clause : cohort.clauses()) {
          if (!Filters.matchesClause(clause, reading)) {
            matched = false;
            break;
          }
        }
      }
      if (matched) {
        out.add(event.sessionId());
      }
    }
    return out;
  }

  /** Every declared property has to be satisfied by the same event, each possibly on its own row. */
  private boolean matchesEventProperties(Filters.Query query, Traffic.Event event) {
    for (var filter : query.eventPropertyFilters()) {
      boolean satisfied = false;
      for (var property : event.properties()) {
        if (Filters.matchesProperty(filter, property, query.timezone())) {
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

  private final Map<String, List<Values.Property>> sessionPropertyCache = new HashMap<>();

  private boolean matchesSessionProperties(String websiteId, Filters.Query query,
      Traffic.Event event) {
    if (query.sessionPropertyFilters().isEmpty()) {
      return true;
    }
    var properties =
        sessionPropertyCache.computeIfAbsent(
            event.sessionId(),
            id ->
                store.sessionProperties(websiteId, id).stream()
                    .map(Traffic.SessionProperty::property)
                    .toList());
    for (var filter : query.sessionPropertyFilters()) {
      boolean satisfied = false;
      for (var property : properties) {
        if (Filters.matchesProperty(filter, property, query.timezone())) {
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
}
