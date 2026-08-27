package io.akka.umami.api;

import akka.http.javadsl.model.HttpEntity;
import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Delete;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.akka.umami.application.Store;
import io.akka.umami.domain.Content;
import io.akka.umami.domain.Recordings;
import io.akka.umami.lib.Constants;
import io.akka.umami.lib.Crypto;
import io.akka.umami.lib.Json;
import io.akka.umami.lib.Responses;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Saved filter sets, and the recordings of what people did. */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint
public class SegmentEndpoint extends Api {

  public SegmentEndpoint(ComponentClient client, akka.stream.Materializer materializer) {
    super(client, materializer);
  }

  // ------------------------------------------------------------------ segments

  @Get("/api/websites/{websiteId}/segments")
  public HttpResponse list(String websiteId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canViewSharedWebsiteFilters(caller, websiteId));
      var type = queryParam("type");
      if (!Constants.SEGMENT_TYPES.contains(type)) {
        return Responses.badRequest(
            Schema.problem("type", Schema.notAnOption(Constants.SEGMENT_TYPES)));
      }
      var query = filters(null);
      var segments =
          new ArrayList<>(store.byParent(Store.SEGMENT, websiteId, Content.Segment.class));
      segments.removeIf(segment -> !type.equals(segment.type()));
      var search = query.search();
      if (search != null && !search.isBlank()) {
        segments.removeIf(segment ->
            !AccountEndpoint.containsIgnoringCase(segment.name(), search));
      }
      return Responses.json(page(segments, query, Writers::segment));
    });
  }

  @Post("/api/websites/{websiteId}/segments")
  public HttpResponse create(String websiteId, HttpEntity.Strict requestBody) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canUpdateWebsite(caller, websiteId));
      var schema = Schema.object();
      schema.string("type").options(Constants.SEGMENT_TYPES).required();
      schema.string("name").max(200).required();
      schema.objectField("parameters").required();
      var request = validate(schema, body(requestBody));

      var now = Instant.now();
      var segment =
          new Content.Segment(Crypto.uuid().toString(), websiteId, request.get("type").asText(),
              request.get("name").asText(),
              request.get("parameters") instanceof ObjectNode parameters ? parameters
                  : Json.object(),
              now, now);
      store.put(Store.SEGMENT, segment.id(), segment);
      return Responses.json(Writers.segment(segment));
    });
  }

  @Get("/api/websites/{websiteId}/segments/{segmentId}")
  public HttpResponse read(String websiteId, String segmentId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canViewSharedWebsiteFilters(caller, websiteId));
      var segment = store.segment(segmentId);
      if (segment == null || !websiteId.equals(segment.websiteId())) {
        return Responses.notFound();
      }
      return Responses.json(Writers.segment(segment));
    });
  }

  @Post("/api/websites/{websiteId}/segments/{segmentId}")
  public HttpResponse update(String websiteId, String segmentId, HttpEntity.Strict requestBody) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canUpdateWebsite(caller, websiteId));
      var schema = Schema.object();
      schema.string("type").options(Constants.SEGMENT_TYPES).required();
      schema.string("name").max(200).required();
      schema.objectField("parameters").required();
      var request = validate(schema, body(requestBody));

      var segment = store.segment(segmentId);
      if (segment == null || !websiteId.equals(segment.websiteId())) {
        return Responses.notFound();
      }
      var updated =
          new Content.Segment(segment.id(), websiteId, request.get("type").asText(),
              request.get("name").asText(),
              request.get("parameters") instanceof ObjectNode parameters ? parameters
                  : segment.parameters(),
              segment.createdAt(), Instant.now());
      store.put(Store.SEGMENT, segmentId, updated);
      return Responses.json(Writers.segment(updated));
    });
  }

  /** Removing a saved filter set needs the right to remove the website, not to change it. */
  @Delete("/api/websites/{websiteId}/segments/{segmentId}")
  public HttpResponse delete(String websiteId, String segmentId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canDeleteWebsite(caller, websiteId));
      var segment = store.segment(segmentId);
      if (segment == null || !websiteId.equals(segment.websiteId())) {
        return Responses.notFound();
      }
      store.remove(Store.SEGMENT, segmentId);
      return Responses.ok();
    });
  }

  // ------------------------------------------------------------------ recordings

  @Get("/api/websites/{websiteId}/replays")
  public HttpResponse replays(String websiteId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canViewAuthenticatedWebsite(caller, websiteId));
      var query = filters(websiteId);
      var rows = summaries(websiteId, null);
      if (query.minDuration() != null) {
        rows.removeIf(row -> row.duration() < query.minDuration() * 1000L);
      }
      var search = query.search();
      if (search != null && !search.isBlank()) {
        rows.removeIf(row ->
            !AccountEndpoint.containsIgnoringCase(row.browser(), search)
                && !AccountEndpoint.containsIgnoringCase(row.os(), search)
                && !AccountEndpoint.containsIgnoringCase(row.device(), search)
                && !AccountEndpoint.containsIgnoringCase(row.city(), search)
                && !AccountEndpoint.containsIgnoringCase(row.distinctId(), search));
      }
      return Responses.json(page(rows, query, Writers::replaySummary));
    });
  }

  @Get("/api/websites/{websiteId}/sessions/{sessionId}/replays")
  public HttpResponse sessionReplays(String websiteId, String sessionId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canViewAuthenticatedWebsite(caller, websiteId));
      var query = filters(websiteId);
      return Responses.json(page(summaries(websiteId, sessionId), query, Writers::replaySummary));
    });
  }

  /** The chunks of each visit folded into one row, newest first. */
  private ArrayList<Recordings.ReplaySummary> summaries(String websiteId, String sessionId) {
    var chunks = store.replayChunks(websiteId);
    var byVisit = new java.util.LinkedHashMap<String, List<Recordings.ReplayChunk>>();
    for (var chunk : chunks) {
      if (sessionId != null && !sessionId.equals(chunk.sessionId())) {
        continue;
      }
      byVisit.computeIfAbsent(chunk.visitId(), v -> new ArrayList<>()).add(chunk);
    }
    var out = new ArrayList<Recordings.ReplaySummary>();
    for (var entry : byVisit.entrySet()) {
      long events = 0;
      long duration = 0;
      Instant started = null;
      Instant ended = null;
      Instant created = null;
      String session = null;
      for (var chunk : entry.getValue()) {
        events += chunk.eventCount();
        duration += Math.max(0,
            chunk.endedAt().toEpochMilli() - chunk.startedAt().toEpochMilli());
        if (started == null || chunk.startedAt().isBefore(started)) {
          started = chunk.startedAt();
        }
        if (ended == null || chunk.endedAt().isAfter(ended)) {
          ended = chunk.endedAt();
        }
        if (created == null || chunk.createdAt().isAfter(created)) {
          created = chunk.createdAt();
        }
        session = chunk.sessionId();
      }
      var record = store.session(websiteId, session);
      out.add(
          new Recordings.ReplaySummary(
              entry.getKey(),
              session,
              websiteId,
              record == null ? null : record.browser(),
              record == null ? null : record.os(),
              record == null ? null : record.device(),
              record == null ? null : record.country(),
              record == null ? null : record.city(),
              record == null ? null : record.distinctId(),
              events,
              entry.getValue().size(),
              started,
              ended,
              duration,
              created));
    }
    out.sort(
        Comparator.comparing((Recordings.ReplaySummary summary) ->
            summary.createdAt() == null ? Instant.EPOCH : summary.createdAt()).reversed());
    return out;
  }

  @Get("/api/websites/{websiteId}/replays/saved")
  public HttpResponse savedReplays(String websiteId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canViewAuthenticatedWebsite(caller, websiteId));
      var query = filters(null);
      var rows = new ArrayList<>(store.savedReplays(websiteId));
      var search = query.search();
      if (search != null && !search.isBlank()) {
        rows.removeIf(row -> !AccountEndpoint.containsIgnoringCase(row.name(), search));
      }
      rows.sort(
          Comparator.comparing((Recordings.SavedReplay replay) ->
              replay.createdAt() == null ? Instant.EPOCH : replay.createdAt()).reversed());
      return Responses.json(page(rows, query, Writers::savedReplay));
    });
  }

  @Get("/api/websites/{websiteId}/replays/saved/{replayId}")
  public HttpResponse isSaved(String websiteId, String replayId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canViewAuthenticatedWebsite(caller, websiteId));
      var body = Json.object();
      body.put("isSaved", store.savedReplay(websiteId, replayId) != null);
      return Responses.json(body);
    });
  }

  @Post("/api/websites/{websiteId}/replays/saved/{replayId}")
  public HttpResponse save(String websiteId, String replayId, HttpEntity.Strict requestBody) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canUpdateWebsite(caller, websiteId));
      var schema = Schema.object();
      schema.bool("isSaved").required();
      schema.string("name").max(100);
      var request = validate(schema, body(requestBody));

      var existing = store.savedReplay(websiteId, replayId);
      if (request.get("isSaved").asBoolean()) {
        var now = Instant.now();
        var id = existing == null ? Crypto.uuid().toString() : existing.id();
        store.put(Store.SAVED_REPLAY, id,
            new Recordings.SavedReplay(id,
                request.has("name") ? request.get("name").asText() : null, websiteId, replayId,
                existing == null ? now : existing.createdAt(), now));
      } else if (existing != null) {
        store.remove(Store.SAVED_REPLAY, existing.id());
      }
      return Responses.ok();
    });
  }

  /** One recording, its chunks merged and re-ordered, optionally cut short. */
  @Get("/api/websites/{websiteId}/replays/{replayId}")
  public HttpResponse replay(String websiteId, String replayId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canViewAuthenticatedWebsite(caller, websiteId));
      var chunks = store.replayChunks(websiteId, replayId);
      var events = Json.array();
      Instant started = null;
      Instant ended = null;
      String sessionId = null;
      var until = longParam(queryParam("until"));
      var chunkIndex = intParam(queryParam("chunkIndex"));
      var eventIndex = intParam(queryParam("eventIndex"));
      for (var chunk : chunks) {
        if (chunkIndex != null && chunk.chunkIndex() > chunkIndex) {
          continue;
        }
        if (until != null && chunk.startedAt().toEpochMilli() > until) {
          continue;
        }
        sessionId = chunk.sessionId();
        if (started == null || chunk.startedAt().isBefore(started)) {
          started = chunk.startedAt();
        }
        if (ended == null || chunk.endedAt().isAfter(ended)) {
          ended = chunk.endedAt();
        }
        var stored = Json.readArray(chunk.events());
        if (stored != null) {
          stored.forEach(events::add);
        }
      }
      var trimmed = Json.array();
      for (int i = 0; i < events.size(); i++) {
        if (eventIndex != null && i > eventIndex) {
          break;
        }
        trimmed.add(events.get(i));
      }
      var body = Json.object();
      body.put("sessionId", sessionId);
      body.set("events", trimmed);
      body.put("startedAt", Writers.stamp(started));
      body.put("endedAt", Writers.stamp(ended));
      body.put("eventCount", trimmed.size());
      body.put("chunkCount", chunks.size());
      return Responses.json(body);
    });
  }
}
