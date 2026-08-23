package io.akka.umami.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import akka.javasdk.testkit.EventSourcedTestKit;
import io.akka.umami.domain.VisitEvent;
import org.junit.jupiter.api.Test;

/** SPEC-001 R1, R2, R6 as the visit entity carries them out. */
class VisitEntityTest {

  private static EventSourcedTestKit<VisitEntity.State, VisitEvent, VisitEntity> kit() {
    return EventSourcedTestKit.of("v1", VisitEntity::new);
  }

  @Test
  void pageViewRecordsPageViewType() {
    var kit = kit();
    kit.method(VisitEntity::recordPageView)
        .invoke(new VisitEntity.RecordPageView("w1", "s1", "/home", 1000));

    assertEquals(1, kit.getState().pageViews());
    assertEquals(0, kit.getState().customEvents());
    assertEquals("w1", kit.getState().websiteId());
    assertEquals("s1", kit.getState().sessionId());
  }

  @Test
  void customEventRecordsCustomEventType() {
    var kit = kit();
    kit.method(VisitEntity::recordCustomEvent)
        .invoke(new VisitEntity.RecordCustomEvent("w1", "s1", "signup", 1000));

    assertEquals(0, kit.getState().pageViews());
    assertEquals(1, kit.getState().customEvents());
  }

  @Test
  void totaltimeIsTheSpanBetweenTheEarliestAndLatestEvent() {
    var kit = kit();
    kit.method(VisitEntity::recordPageView).invoke(new VisitEntity.RecordPageView("w1", "s1", "/a", 1000));
    kit.method(VisitEntity::recordPageView).invoke(new VisitEntity.RecordPageView("w1", "s1", "/b", 4500));

    assertEquals(1000, kit.getState().minCreatedAtMillis());
    assertEquals(4500, kit.getState().maxCreatedAtMillis());
  }

  @Test
  void aSinglePageViewLeavesMinAndMaxEqual() {
    var kit = kit();
    kit.method(VisitEntity::recordPageView).invoke(new VisitEntity.RecordPageView("w1", "s1", "/a", 1000));

    assertEquals(1000, kit.getState().minCreatedAtMillis());
    assertEquals(1000, kit.getState().maxCreatedAtMillis());
  }
}
