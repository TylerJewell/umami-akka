package io.akka.umami.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import akka.javasdk.eventsourcedentity.EventSourcedEntityContext;
import io.akka.umami.domain.VisitEvent;

/**
 * One {@code (sessionId, visitId)} pair's event log — SPEC-001 §2, R1, R2, R6, R7.
 *
 * <p>Entity id is the {@code visitId}. Keeping one entity per visit, rather than one per
 * website, is what lets {@link VisitRollupView} answer the rollup with one row per visit
 * instead of re-deriving visit boundaries from a flat event stream on every query — the same
 * grouping the source computes with {@code group by session_id, visit_id} in
 * {@code getWebsiteStats.ts}.
 */
@Component(id = "visit")
public class VisitEntity extends EventSourcedEntity<VisitEntity.State, VisitEvent> {

  private final String visitId;

  public VisitEntity(EventSourcedEntityContext context) {
    this.visitId = context.entityId();
  }

  /**
   * @param pageViews count of {@code PAGE_VIEW} events recorded — R4
   * @param customEvents count of {@code CUSTOM_EVENT} events recorded — R7's second half
   * @param minCreatedAtMillis the earliest event's timestamp, or 0 before any event
   * @param maxCreatedAtMillis the latest event's timestamp, or 0 before any event
   */
  public record State(
      String websiteId,
      String sessionId,
      String visitId,
      long pageViews,
      long customEvents,
      long minCreatedAtMillis,
      long maxCreatedAtMillis) {

    static State empty(String visitId) {
      return new State(null, null, visitId, 0, 0, 0, 0);
    }

    boolean isEmpty() {
      return websiteId == null;
    }
  }

  public record RecordPageView(String websiteId, String sessionId, String urlPath, long createdAtMillis) {}

  public record RecordCustomEvent(String websiteId, String sessionId, String eventName, long createdAtMillis) {}

  @Override
  public State emptyState() {
    return State.empty(visitId);
  }

  public Effect<Done> recordPageView(RecordPageView cmd) {
    return effects()
        .persist(new VisitEvent.PageViewRecorded(
            cmd.websiteId(), cmd.sessionId(), cmd.urlPath(), cmd.createdAtMillis()))
        .thenReply(state -> Done.getInstance());
  }

  public Effect<Done> recordCustomEvent(RecordCustomEvent cmd) {
    return effects()
        .persist(new VisitEvent.CustomEventRecorded(
            cmd.websiteId(), cmd.sessionId(), cmd.eventName(), cmd.createdAtMillis()))
        .thenReply(state -> Done.getInstance());
  }

  @Override
  public State applyEvent(VisitEvent event) {
    State current = currentState();
    long createdAtMillis = switch (event) {
      case VisitEvent.PageViewRecorded e -> e.createdAtMillis();
      case VisitEvent.CustomEventRecorded e -> e.createdAtMillis();
    };
    long min = current.isEmpty() ? createdAtMillis : Math.min(current.minCreatedAtMillis(), createdAtMillis);
    long max = current.isEmpty() ? createdAtMillis : Math.max(current.maxCreatedAtMillis(), createdAtMillis);

    return switch (event) {
      case VisitEvent.PageViewRecorded e -> new State(
          e.websiteId(), e.sessionId(), visitId, current.pageViews() + 1, current.customEvents(), min, max);
      case VisitEvent.CustomEventRecorded e -> new State(
          e.websiteId(), e.sessionId(), visitId, current.pageViews(), current.customEvents() + 1, min, max);
    };
  }
}
