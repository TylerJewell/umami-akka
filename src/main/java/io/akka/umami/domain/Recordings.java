package io.akka.umami.domain;

import java.time.Instant;
import java.util.List;

/** What the recorder leaves behind: replay chunks, saved replays, and heatmap rows. */
public final class Recordings {

  private Recordings() {}

  public record ReplayChunk(
      String id,
      String websiteId,
      String sessionId,
      String visitId,
      int chunkIndex,
      String events,
      int eventCount,
      Instant startedAt,
      Instant endedAt,
      Instant createdAt) {}

  public record SavedReplay(
      String id, String name, String websiteId, String visitId, Instant createdAt,
      Instant updatedAt) {}

  public record HeatmapEvent(
      String id,
      String websiteId,
      String sessionId,
      String visitId,
      String urlPath,
      int eventType,
      Integer x,
      Integer y,
      Integer pageX,
      Integer pageY,
      Integer pageW,
      Integer pageH,
      Integer viewportW,
      Integer viewportH,
      Integer scrollPct,
      Instant createdAt) {}

  /** One replay in the list: the chunks of one visit, folded together. */
  public record ReplaySummary(
      String id,
      String sessionId,
      String websiteId,
      String browser,
      String os,
      String device,
      String country,
      String city,
      String distinctId,
      long eventCount,
      long chunkCount,
      Instant startedAt,
      Instant endedAt,
      long duration,
      Instant createdAt) {}

  public record ReplayPlayback(
      String sessionId,
      List<com.fasterxml.jackson.databind.JsonNode> events,
      Instant startedAt,
      Instant endedAt,
      int eventCount,
      int chunkCount) {}
}
