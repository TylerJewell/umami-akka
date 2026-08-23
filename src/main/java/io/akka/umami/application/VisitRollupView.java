package io.akka.umami.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;
import io.akka.umami.domain.VisitEvent;
import java.util.List;

/**
 * The read side of the split — one row per visit, queried by website and date range and
 * reduced into the dashboard's five numbers by {@link
 * io.akka.umami.api.WebsiteStatsEndpoint} (R4–R8). The view itself does the grouping the
 * source's SQL does with {@code group by session_id, visit_id}; the aggregation across
 * groups (sums, distinct counts, the bounce and totaltime rules) is done in the endpoint
 * rather than in the view's query language, which is the one place this port's split does
 * not mirror the source's own SQL — see the published README's list of differences.
 */
@Component(id = "visit-rollup")
public class VisitRollupView extends View {

  public record VisitEntry(
      String websiteId,
      String sessionId,
      String visitId,
      long pageViews,
      long customEvents,
      long minCreatedAtMillis,
      long maxCreatedAtMillis) {}

  public record Visits(List<VisitEntry> visits) {}

  public record RangeQuery(String websiteId, long startMillis, long endMillis) {}

  @Consume.FromEventSourcedEntity(VisitEntity.class)
  public static class Updater extends TableUpdater<VisitEntry> {
    public Effect<VisitEntry> onEvent(VisitEvent event) {
      String visitId = updateContext().eventSubject().get();
      VisitEntry current = rowState();
      long createdAtMillis = switch (event) {
        case VisitEvent.PageViewRecorded e -> e.createdAtMillis();
        case VisitEvent.CustomEventRecorded e -> e.createdAtMillis();
      };
      boolean empty = current == null;
      long min = empty ? createdAtMillis : Math.min(current.minCreatedAtMillis(), createdAtMillis);
      long max = empty ? createdAtMillis : Math.max(current.maxCreatedAtMillis(), createdAtMillis);

      return switch (event) {
        case VisitEvent.PageViewRecorded e -> effects().updateRow(new VisitEntry(
            e.websiteId(), e.sessionId(), visitId,
            (empty ? 0 : current.pageViews()) + 1,
            empty ? 0 : current.customEvents(),
            min, max));
        case VisitEvent.CustomEventRecorded e -> effects().updateRow(new VisitEntry(
            e.websiteId(), e.sessionId(), visitId,
            empty ? 0 : current.pageViews(),
            (empty ? 0 : current.customEvents()) + 1,
            min, max));
      };
    }
  }

  @Query("""
      SELECT * AS visits FROM visit_rollup
      WHERE websiteId = :websiteId AND minCreatedAtMillis >= :startMillis AND maxCreatedAtMillis <= :endMillis
      """)
  public QueryEffect<Visits> inRange(RangeQuery query) {
    return queryResult();
  }
}
