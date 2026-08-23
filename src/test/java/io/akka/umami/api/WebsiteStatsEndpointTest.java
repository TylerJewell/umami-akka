package io.akka.umami.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.akka.umami.application.VisitRollupView;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SPEC-001 R4–R8 — question-log rows 3, 4, 5: three same-instant pageviews in one visit,
 * a lone pageview, and a pageview plus a custom event in the same visit, run against the
 * real source and reproduced here as fixed rows.
 */
class WebsiteStatsEndpointTest {

  private static VisitRollupView.VisitEntry row(
      String sessionId, String visitId, long pageViews, long customEvents, long min, long max) {
    return new VisitRollupView.VisitEntry("w1", sessionId, visitId, pageViews, customEvents, min, max);
  }

  @Test
  void pageviewsCountsOnlyPageViewType() {
    var stats = WebsiteStatsEndpoint.aggregate(List.of(row("s1", "v1", 3, 0, 1000, 1000)));
    assertEquals(3, stats.pageviews());
  }

  @Test
  void visitorsAndVisitsCountDistinct() {
    var stats = WebsiteStatsEndpoint.aggregate(List.of(row("s1", "v1", 3, 0, 1000, 1000)));
    assertEquals(1, stats.visitors());
    assertEquals(1, stats.visits());
  }

  @Test
  void totaltimeSumsMaxMinusMinPerVisitInSeconds() {
    var stats = WebsiteStatsEndpoint.aggregate(List.of(row("s1", "v1", 3, 0, 1000, 1000)));
    assertEquals(0, stats.totaltime());

    var withSpan = WebsiteStatsEndpoint.aggregate(List.of(row("s1", "v1", 2, 0, 1_000, 5_500)));
    assertEquals(4, withSpan.totaltime());
  }

  @Test
  void aLonePageviewBounces() {
    var stats = WebsiteStatsEndpoint.aggregate(List.of(row("s1", "v1", 1, 0, 1000, 1000)));
    assertEquals(1, stats.bounces());
  }

  @Test
  void bounceRequiresNoCustomEventInVisit() {
    var stats = WebsiteStatsEndpoint.aggregate(List.of(row("s1", "v1", 1, 1, 1000, 1000)));
    assertEquals(0, stats.bounces());
    // The custom event still does not count as a pageview.
    assertEquals(1, stats.pageviews());
  }
}
