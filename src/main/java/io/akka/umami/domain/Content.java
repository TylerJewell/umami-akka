package io.akka.umami.domain;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;

/** The things a person creates around a website: sites, links, pixels, boards, reports, shares. */
public final class Content {

  private Content() {}

  public record ReplayConfig(
      Boolean replayEnabled,
      Boolean heatmapEnabled,
      Double sampleRate,
      Double heatmapSampleRate,
      String maskLevel,
      Integer maxDuration,
      String blockSelector) {

    public static final ReplayConfig EMPTY =
        new ReplayConfig(null, null, null, null, null, null, null);

    public boolean recorderEnabled() {
      return Boolean.TRUE.equals(replayEnabled) || Boolean.TRUE.equals(heatmapEnabled);
    }

    /** The settings the recorder is served, with the defaults filled in. */
    public ReplayConfig withDefaults() {
      return new ReplayConfig(
          replayEnabled != null && replayEnabled,
          heatmapEnabled != null && heatmapEnabled,
          sampleRate != null ? sampleRate : 0.15,
          heatmapSampleRate != null ? heatmapSampleRate : 0.15,
          maskLevel != null ? maskLevel : "moderate",
          maxDuration != null ? maxDuration : 300000,
          blockSelector != null ? blockSelector : "");
    }

    /** A partial update merges rather than replaces; a null one resets to nothing at all. */
    public ReplayConfig merge(ReplayConfig update) {
      if (update == null) {
        return EMPTY;
      }
      return new ReplayConfig(
          update.replayEnabled() != null ? update.replayEnabled() : replayEnabled,
          update.heatmapEnabled() != null ? update.heatmapEnabled() : heatmapEnabled,
          update.sampleRate() != null ? update.sampleRate() : sampleRate,
          update.heatmapSampleRate() != null ? update.heatmapSampleRate() : heatmapSampleRate,
          update.maskLevel() != null ? update.maskLevel() : maskLevel,
          update.maxDuration() != null ? update.maxDuration() : maxDuration,
          update.blockSelector() != null ? update.blockSelector() : blockSelector);
    }
  }

  public record Website(
      String id,
      String name,
      String domain,
      Instant resetAt,
      String userId,
      String teamId,
      String createdBy,
      Instant createdAt,
      Instant updatedAt,
      Instant deletedAt,
      boolean recorderEnabled,
      ReplayConfig replayConfig) {

    public boolean isDeleted() {
      return deletedAt != null;
    }
  }

  public record Link(
      String id,
      String name,
      String url,
      String slug,
      String userId,
      String teamId,
      Instant createdAt,
      Instant updatedAt,
      Instant deletedAt) {}

  public record Pixel(
      String id,
      String name,
      String slug,
      String userId,
      String teamId,
      Instant createdAt,
      Instant updatedAt,
      Instant deletedAt) {}

  public record Board(
      String id,
      String type,
      String name,
      String description,
      ObjectNode parameters,
      String userId,
      String teamId,
      Instant createdAt,
      Instant updatedAt) {}

  public record Report(
      String id,
      String userId,
      String websiteId,
      String type,
      String name,
      String description,
      ObjectNode parameters,
      Instant createdAt,
      Instant updatedAt) {}

  public record Segment(
      String id,
      String websiteId,
      String type,
      String name,
      ObjectNode parameters,
      Instant createdAt,
      Instant updatedAt) {}

  public record Share(
      String id,
      String entityId,
      String name,
      int shareType,
      String slug,
      ObjectNode parameters,
      Instant createdAt,
      Instant updatedAt) {}

  /** A page of anything, in the one envelope every list answers in. */
  public record Page<T>(
      List<T> data,
      long count,
      int page,
      int pageSize,
      String orderBy,
      String search,
      Boolean isCapped) {}
}
