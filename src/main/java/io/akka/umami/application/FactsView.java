package io.akka.umami.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;
import io.akka.umami.lib.Json;
import java.util.List;

/**
 * Every collected fact, indexed by the website it belongs to and the instant it happened.
 *
 * <p>Every analytical answer umami gives is a grouping, a counting or a percentile over a range of
 * one website's facts. Akka's views hold rows and filter them; they do not group and they do not
 * aggregate — {@code count(*)} counts a whole query and never a group, and there is no sum, no
 * average and no distinct count. So this view answers the range and {@code io.akka.umami.analytics}
 * does the arithmetic. That is the port's central decision and it is recorded as such in
 * SPEC-001 D1.
 */
@Component(id = "facts")
public class FactsView extends View {

  /**
   * @param factKey the entity's namespaced identity
   * @param kind one of {@code event}, {@code session}, {@code sessionData}, {@code revenue},
   *     {@code heatmap}, {@code replay}, {@code sessionLink}
   * @param websiteId which website the fact belongs to
   * @param sessionId which session, or the empty string
   * @param visitId which visit, or the empty string
   * @param groupKey a second grouping column: a heatmap row's path, an identity link's distinct
   *     identifier, a property row's key
   * @param createdAt millisecond stamp; the range every analytical query narrows by
   * @param removed whether the fact is gone, as one or zero
   * @param document the whole fact
   */
  public record Row(
      String factKey,
      String kind,
      String websiteId,
      String sessionId,
      String visitId,
      String groupKey,
      long createdAt,
      int removed,
      String document) {}

  public record Rows(List<Row> items) {}

  public record ByRange(String kind, String websiteId, long from, long to) {}

  public record BySession(String kind, String websiteId, String sessionId) {}

  public record ByVisit(String kind, String websiteId, String visitId) {}

  public record ByGroup(String kind, String websiteId, String groupKey) {}

  public record ByWebsite(String kind, String websiteId) {}

  @Consume.FromEventSourcedEntity(FactEntity.class)
  public static class Facts extends TableUpdater<Row> {

    public Effect<Row> onEvent(Doc.Event event) {
      var key = updateContext().eventSubject().orElseThrow();
      var previous = rowState();
      return switch (event) {
        case Doc.Created e -> effects().updateRow(row(key, e.document(), false));
        case Doc.Updated e -> effects().updateRow(row(key, e.document(),
            previous != null && previous.removed() == 1));
        case Doc.Deleted ignored -> {
          if (previous == null) {
            yield effects().ignore();
          }
          yield effects().updateRow(new Row(previous.factKey(), previous.kind(),
              previous.websiteId(), previous.sessionId(), previous.visitId(), previous.groupKey(),
              previous.createdAt(), 1, previous.document()));
        }
      };
    }

    private static Row row(String key, String document, boolean removed) {
      int separator = key.indexOf(':');
      var kind = key.substring(0, separator);
      var node = Json.readObject(document);
      return new Row(
          key,
          kind,
          text(node, "websiteId"),
          text(node, "sessionId"),
          text(node, "visitId"),
          groupOf(kind, node),
          millis(node),
          removed ? 1 : 0,
          document);
    }

    private static String groupOf(String kind, com.fasterxml.jackson.databind.JsonNode node) {
      return switch (kind) {
        case "heatmap" -> text(node, "urlPath");
        case "sessionLink" -> text(node, "distinctId");
        case "sessionData" -> text(node, "dataKey");
        case "revenue" -> text(node, "currency");
        default -> "";
      };
    }

    private static String text(com.fasterxml.jackson.databind.JsonNode node, String field) {
      if (node == null) {
        return "";
      }
      var value = node.get(field);
      return value == null || value.isNull() ? "" : value.asText();
    }

    private static long millis(com.fasterxml.jackson.databind.JsonNode node) {
      var value = text(node, "createdAt");
      if (value.isEmpty()) {
        return 0;
      }
      try {
        return java.time.Instant.parse(value).toEpochMilli();
      } catch (RuntimeException e) {
        return 0;
      }
    }
  }

  /**
   * Every query here is a stream, and none of them is a single reply.
   *
   * <p>A query that projects its rows into one response is refused past a thousand of them, and
   * every analytical answer this service gives is a walk over a window of one website's facts —
   * so a website with a thousand events in the window made every question fail rather than
   * answer slowly. A stream has no such ceiling; what it costs is that the caller assembles the
   * list, which {@link Store} does in one place.
   */
  @Query("SELECT * FROM facts WHERE kind = :kind AND websiteId = :websiteId"
      + " AND createdAt >= :from AND createdAt <= :to AND removed = 0 ORDER BY createdAt")
  public QueryStreamEffect<Row> byRange(ByRange params) {
    return queryStreamResult();
  }

  @Query("SELECT * FROM facts WHERE kind = :kind AND websiteId = :websiteId"
      + " AND removed = 0 ORDER BY createdAt")
  public QueryStreamEffect<Row> byWebsite(ByWebsite params) {
    return queryStreamResult();
  }

  @Query("SELECT * FROM facts WHERE kind = :kind AND websiteId = :websiteId"
      + " AND sessionId = :sessionId AND removed = 0 ORDER BY createdAt")
  public QueryStreamEffect<Row> bySession(BySession params) {
    return queryStreamResult();
  }

  @Query("SELECT * FROM facts WHERE kind = :kind AND websiteId = :websiteId"
      + " AND visitId = :visitId AND removed = 0 ORDER BY createdAt")
  public QueryStreamEffect<Row> byVisit(ByVisit params) {
    return queryStreamResult();
  }

  @Query("SELECT * FROM facts WHERE kind = :kind AND websiteId = :websiteId"
      + " AND groupKey = :groupKey AND removed = 0 ORDER BY createdAt")
  public QueryStreamEffect<Row> byGroup(ByGroup params) {
    return queryStreamResult();
  }
}
