package io.akka.umami.domain;

import akka.javasdk.annotations.TypeName;

/**
 * A visit's own event log — SPEC-001 R1, R2. One {@code VisitEntity} instance is one
 * {@code (sessionId, visitId)} pair, so every event here already belongs to it; the
 * entity id supplies {@code websiteId} and {@code sessionId} once at creation rather
 * than repeating them on every event.
 */
public sealed interface VisitEvent {

  @TypeName("page-view-recorded")
  record PageViewRecorded(String websiteId, String sessionId, String urlPath, long createdAtMillis)
      implements VisitEvent {}

  @TypeName("custom-event-recorded")
  record CustomEventRecorded(String websiteId, String sessionId, String eventName, long createdAtMillis)
      implements VisitEvent {}
}
