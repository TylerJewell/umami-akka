package io.akka.umami.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;
import io.akka.umami.lib.Json;
import java.util.List;

/**
 * Every administrative row, indexed by the handful of columns anything narrows on.
 *
 * <p>The row carries the whole document beside those columns. That is the shape a view can hold:
 * umami's list endpoints search across several columns at once, order by a related row's name and
 * count the whole set for a pager, and none of that is expressible in a view query. So the view
 * narrows by what it can index — the kind, the owner, the team, the parent, a unique key — and the
 * endpoint searches, orders and pages over what comes back. The answers are umami's; the cost is
 * that a list reads more rows than a query would, which is recorded in the README as a difference
 * in how it scales rather than in what it says.
 *
 * <p>Every column is non-null, with an empty string where a document has nothing. A null in a view
 * row stops the view's own update stream, and a stopped stream makes every query against it answer
 * an empty result rather than an error.
 */
@Component(id = "records")
public class RecordsView extends View {

  /**
   * @param recordKey the entity's namespaced identity, {@code <kind>:<id>}
   * @param kind which table the row belongs to
   * @param recordId the identity within that kind
   * @param ownerId whichever account owns the row, or the empty string
   * @param teamId whichever team owns it, or the empty string
   * @param parentId what the row hangs off: a membership's team, a report's website, a share's
   *     entity, a saved replay's website
   * @param uniqueKey the value the row is looked up by when it is not looked up by identity: an
   *     account's name, a link's or a share's slug, a team's access code, a saved replay's visit
   * @param name the row's name, lower-cased, so ordering by name needs no second read
   * @param createdAt millisecond stamp, zero when the row carries none
   * @param removed whether the row is gone, as one or zero
   * @param document the whole row, as it would be written to a caller
   */
  public record Row(
      String recordKey,
      String kind,
      String recordId,
      String ownerId,
      String teamId,
      String parentId,
      String uniqueKey,
      String name,
      long createdAt,
      int removed,
      String document) {}

  public record Rows(List<Row> items) {}

  public record ByKind(String kind) {}

  public record ByOwner(String kind, String ownerId) {}

  public record ByTeam(String kind, String teamId) {}

  public record ByParent(String kind, String parentId) {}

  public record ByUnique(String kind, String uniqueKey) {}

  @Consume.FromEventSourcedEntity(RecordEntity.class)
  public static class Records extends TableUpdater<Row> {

    public Effect<Row> onEvent(Doc.Event event) {
      var key = updateContext().eventSubject().orElseThrow();
      var previous = rowState();
      return switch (event) {
        case Doc.Created e -> effects().updateRow(row(key, e.document(), false));
        case Doc.Updated e -> effects().updateRow(row(key, e.document(), previous != null
            && previous.removed() == 1));
        case Doc.Deleted ignored -> {
          if (previous == null) {
            yield effects().ignore();
          }
          yield effects().updateRow(new Row(previous.recordKey(), previous.kind(),
              previous.recordId(), previous.ownerId(), previous.teamId(), previous.parentId(),
              previous.uniqueKey(), previous.name(), previous.createdAt(), 1, previous.document()));
        }
      };
    }

    private static Row row(String key, String document, boolean removed) {
      int separator = key.indexOf(':');
      var kind = key.substring(0, separator);
      var identity = key.substring(separator + 1);
      var node = Json.readObject(document);
      return new Row(
          key,
          kind,
          identity,
          text(node, "userId"),
          text(node, "teamId"),
          parentOf(kind, node),
          uniqueOf(kind, node),
          text(node, "name").toLowerCase(java.util.Locale.ROOT),
          millis(node, "createdAt"),
          removed ? 1 : 0,
          document);
    }

    private static String parentOf(String kind, com.fasterxml.jackson.databind.JsonNode node) {
      return switch (kind) {
        case "teamUser" -> text(node, "teamId");
        case "report", "segment", "savedReplay" -> text(node, "websiteId");
        case "share" -> text(node, "entityId");
        case "twoFactor", "authSession" -> text(node, "userId");
        default -> "";
      };
    }

    private static String uniqueOf(String kind, com.fasterxml.jackson.databind.JsonNode node) {
      return switch (kind) {
        case "user" -> text(node, "username");
        case "team" -> text(node, "accessCode");
        case "link", "pixel", "share" -> text(node, "slug");
        case "teamUser" -> text(node, "teamId") + ":" + text(node, "userId");
        case "savedReplay" -> text(node, "websiteId") + ":" + text(node, "visitId");
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

    private static long millis(com.fasterxml.jackson.databind.JsonNode node, String field) {
      var value = text(node, field);
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
   * Every query here is a stream, for the same reason {@link FactsView}'s are: a query that
   * projects its rows into one response is refused past a thousand of them, and a deployment
   * with a thousand websites is an ordinary deployment.
   */
  @Query("SELECT * FROM records WHERE kind = :kind AND removed = 0 ORDER BY createdAt")
  public QueryStreamEffect<Row> byKind(ByKind params) {
    return queryStreamResult();
  }

  @Query("SELECT * FROM records WHERE kind = :kind AND ownerId = :ownerId"
      + " AND removed = 0 ORDER BY createdAt")
  public QueryStreamEffect<Row> byOwner(ByOwner params) {
    return queryStreamResult();
  }

  @Query("SELECT * FROM records WHERE kind = :kind AND teamId = :teamId"
      + " AND removed = 0 ORDER BY createdAt")
  public QueryStreamEffect<Row> byTeam(ByTeam params) {
    return queryStreamResult();
  }

  @Query("SELECT * FROM records WHERE kind = :kind AND parentId = :parentId"
      + " AND removed = 0 ORDER BY createdAt")
  public QueryStreamEffect<Row> byParent(ByParent params) {
    return queryStreamResult();
  }

  @Query("SELECT * FROM records WHERE kind = :kind AND uniqueKey = :uniqueKey"
      + " ORDER BY createdAt")
  public QueryStreamEffect<Row> byUnique(ByUnique params) {
    return queryStreamResult();
  }
}
