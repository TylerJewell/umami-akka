package io.akka.umami.api;

import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.HttpResponses;
import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.http.AbstractHttpEndpoint;
import io.akka.umami.application.VisitRollupView;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The read side — SPEC-001 R4–R8. Mirrors {@code GET /api/websites/{websiteId}/stats}'s
 * non-event-filtered Postgres path in {@code getWebsiteStats.ts}: pageviews, distinct
 * visitors/visits, bounces, totaltime, for a website over {@code [startAt, endAt]}
 * (milliseconds, matching the source's own query parameter names and units).
 */
@HttpEndpoint
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
public class WebsiteStatsEndpoint extends AbstractHttpEndpoint {

  private final ComponentClient componentClient;

  public WebsiteStatsEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  public record WebsiteStats(long pageviews, long visitors, long visits, long bounces, long totaltime) {}

  @Get("/api/websites/{websiteId}/stats")
  public HttpResponse stats(String websiteId) {
    long startAt = requestContext().queryParams().getString("startAt").map(Long::parseLong).orElse(0L);
    long endAt = requestContext().queryParams().getString("endAt").map(Long::parseLong)
        .orElse(Long.MAX_VALUE);

    VisitRollupView.Visits visits = componentClient
        .forView()
        .method(VisitRollupView::inRange)
        .invoke(new VisitRollupView.RangeQuery(websiteId, startAt, endAt));

    return HttpResponses.ok(aggregate(visits.visits()));
  }

  /** R4–R7, applied to the view's per-visit rows rather than in the view's own query. */
  static WebsiteStats aggregate(List<VisitRollupView.VisitEntry> rows) {
    long pageviews = 0;
    long bounces = 0;
    long totaltime = 0;
    Set<String> sessionIds = new HashSet<>();

    for (VisitRollupView.VisitEntry row : rows) {
      pageviews += row.pageViews();
      sessionIds.add(row.sessionId());
      if (row.pageViews() == 1 && row.customEvents() == 0) {
        bounces++;
      }
      totaltime += row.maxCreatedAtMillis() - row.minCreatedAtMillis();
    }

    // R6: the source reports totaltime in seconds (its getTimestampDiffSQL), not millis.
    return new WebsiteStats(pageviews, sessionIds.size(), rows.size(), bounces, totaltime / 1000);
  }
}
