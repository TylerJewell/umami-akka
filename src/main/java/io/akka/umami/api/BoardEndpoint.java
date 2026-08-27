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
import io.akka.umami.lib.Constants;
import io.akka.umami.lib.Crypto;
import io.akka.umami.lib.Json;
import io.akka.umami.lib.Responses;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Boards, and the one board that is not in the list.
 *
 * <p>Every account has a dashboard whose identifier <em>is</em> the account's, of type
 * {@code dashboard}, and that one is hidden from the ordinary board list. SPEC R103.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint
public class BoardEndpoint extends Api {

  public BoardEndpoint(ComponentClient client) {
    super(client);
  }

  @Get("/api/boards")
  public HttpResponse list() {
    return answer(() -> {
      var caller = caller();
      var query = filters(null);
      var boards =
          new ArrayList<>(store.byOwner(Store.BOARD, caller.userId(), Content.Board.class));
      boards.removeIf(board -> "dashboard".equals(board.type()));
      var search = query.search();
      if (search != null && !search.isBlank()) {
        boards.removeIf(board -> !AccountEndpoint.containsIgnoringCase(board.name(), search)
            && !AccountEndpoint.containsIgnoringCase(board.description(), search));
      }
      return Responses.json(page(boards, query, Writers::board));
    });
  }

  @Post("/api/boards")
  public HttpResponse create(HttpEntity.Strict requestBody) {
    return answer(() -> {
      var caller = caller();
      var schema = Schema.object();
      schema.string("type")
          .options(List.of("mixed", "website", "pixel", "link", "open"))
          .required();
      schema.string("name").max(100).required();
      schema.string("description").max(500);
      schema.uuid("userId").nullable();
      schema.uuid("teamId").nullable();
      schema.objectField("parameters");
      var request = validate(schema, body(requestBody));

      var teamId = textOrNull(request, "teamId");
      require((teamId == null || permissions.canCreateTeamWebsite(caller, teamId))
          && permissions.canCreateWebsite(caller));

      var parameters =
          request.get("parameters") instanceof ObjectNode given ? given : Json.object();
      if (!permissions.canViewBoardEntities(caller, parameters)) {
        return Responses.badRequest("Board contains inaccessible entities.");
      }
      if (!permissions.hasValidBoardReports(parameters)) {
        return Responses.badRequest("Board contains invalid saved reports.");
      }
      // The store requires a description and the request schema does not, so a board without
      // one is a server error rather than a refusal. SPEC R100.
      if (!request.has("description")) {
        return Responses.uncaught();
      }

      var now = Instant.now();
      var board =
          new Content.Board(Crypto.uuid().toString(), normalizeType(request.get("type").asText()),
              request.get("name").asText(), request.get("description").asText(), parameters,
              teamId == null ? caller.userId() : null, teamId, now, now);
      store.put(Store.BOARD, board.id(), board);
      return Responses.json(Writers.board(board));
    });
  }

  private static String normalizeType(String type) {
    return "open".equals(type) ? "mixed" : type;
  }

  @Get("/api/boards/{boardId}")
  public HttpResponse read(String boardId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canViewBoard(caller, boardId));
      var board = store.board(boardId);
      if (board == null) {
        return Responses.json(Json.object().nullNode());
      }
      return Responses.json(Writers.board(board));
    });
  }

  @Post("/api/boards/{boardId}")
  public HttpResponse update(String boardId, HttpEntity.Strict requestBody) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canUpdateBoard(caller, boardId));
      var schema = Schema.object();
      schema.string("type")
          .options(List.of("dashboard", "mixed", "website", "pixel", "link", "open"));
      schema.string("name").max(200);
      schema.string("description").max(500);
      schema.objectField("parameters");
      var request = validate(schema, body(requestBody));

      var board = store.board(boardId);
      require(board != null);
      if (request.has("type") || request.has("parameters")) {
        var parameters =
            request.get("parameters") instanceof ObjectNode given ? given : board.parameters();
        if (!permissions.canViewBoardEntities(caller, parameters)) {
          return Responses.badRequest("Board contains inaccessible entities.");
        }
        if (!permissions.hasValidBoardReports(parameters)) {
          return Responses.badRequest("Board contains invalid saved reports.");
        }
      }
      var updated =
          new Content.Board(board.id(),
              request.has("type") ? normalizeType(request.get("type").asText()) : board.type(),
              request.has("name") ? request.get("name").asText() : board.name(),
              request.has("description") ? request.get("description").asText()
                  : board.description(),
              request.get("parameters") instanceof ObjectNode given ? given : board.parameters(),
              board.userId(), board.teamId(), board.createdAt(), Instant.now());
      store.put(Store.BOARD, boardId, updated);
      return Responses.json(Writers.board(updated));
    });
  }

  @Delete("/api/boards/{boardId}")
  public HttpResponse delete(String boardId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canDeleteBoard(caller, boardId));
      store.remove(Store.BOARD, boardId);
      return Responses.ok();
    });
  }

  /** A copy, with any saved report that no longer fits stripped rather than refused. */
  @Post("/api/boards/{boardId}/clone")
  public HttpResponse clone(String boardId, HttpEntity.Strict requestBody) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canUpdateBoard(caller, boardId));
      var schema = Schema.object();
      schema.string("name").max(200);
      schema.string("description").max(500);
      schema.objectField("parameters");
      var request = validate(schema, body(requestBody));

      var source = store.board(boardId);
      require(source != null);
      if (source.type() == null || "dashboard".equals(source.type())) {
        return Responses.badRequest("Board cannot be cloned.");
      }
      require((source.teamId() == null || permissions.canCreateTeamWebsite(caller,
          source.teamId())) && permissions.canCreateWebsite(caller));

      var parameters =
          request.get("parameters") instanceof ObjectNode given ? given.deepCopy()
              : (source.parameters() == null ? Json.object() : source.parameters().deepCopy());
      stripInvalidReports(parameters);
      if (!permissions.canViewBoardEntities(caller, parameters)) {
        return Responses.badRequest("Board contains inaccessible entities.");
      }
      if (!permissions.hasValidBoardReports(parameters)) {
        return Responses.badRequest("Board contains invalid saved reports.");
      }
      if (!request.has("description")) {
        return Responses.uncaught();
      }
      var now = Instant.now();
      var copy =
          new Content.Board(Crypto.uuid().toString(), source.type(),
              request.has("name") ? request.get("name").asText() : source.name(),
              request.get("description").asText(), parameters,
              source.userId() != null ? source.userId() : caller.userId(), source.teamId(), now,
              now);
      store.put(Store.BOARD, copy.id(), copy);
      return Responses.json(Writers.board(copy));
    });
  }

  private void stripInvalidReports(ObjectNode parameters) {
    var offending = permissions.collectReportProblems(parameters);
    if (offending.isEmpty()) {
      return;
    }
    var components = parameters.get("components");
    if (components == null || !components.isArray()) {
      return;
    }
    for (var component : components) {
      var props = component.get("props");
      if (props instanceof ObjectNode node) {
        var reportId = node.get("reportId");
        if (reportId != null && offending.contains(reportId.asText())) {
          node.remove("reportId");
        }
      }
    }
  }

  @Get("/api/boards/{boardId}/shares")
  public HttpResponse shares(String boardId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canViewBoard(caller, boardId));
      var query = filters(null);
      return Responses.json(page(new ArrayList<>(store.sharesOf(boardId)), query, Writers::share));
    });
  }

  @Post("/api/boards/{boardId}/shares")
  public HttpResponse createShare(String boardId, HttpEntity.Strict requestBody) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canUpdateBoard(caller, boardId));
      return Responses.json(
          ShareEndpoint.createShareFor(store, boardId, Constants.ENTITY_BOARD, body(requestBody)));
    });
  }

  // ------------------------------------------------------------------ the dashboard

  @Get("/api/dashboard")
  public HttpResponse dashboard() {
    return answer(() -> {
      var caller = caller();
      var board = store.board(caller.userId());
      if (board == null) {
        return Responses.json(Json.object().nullNode());
      }
      require(caller.userId().equals(board.userId()));
      return Responses.json(Writers.board(board));
    });
  }

  @Post("/api/dashboard")
  public HttpResponse saveDashboard(HttpEntity.Strict requestBody) {
    return answer(() -> {
      var caller = caller();
      var schema = Schema.object();
      schema.string("name").max(200);
      schema.string("description").max(500);
      schema.objectField("parameters");
      var request = validate(schema, body(requestBody));

      var existing = store.board(caller.userId());
      if (existing != null && !caller.userId().equals(existing.userId())) {
        return Responses.unauthorized();
      }
      var parameters =
          request.get("parameters") instanceof ObjectNode given ? given
              : (existing == null ? Json.object() : existing.parameters());
      if (!permissions.hasValidBoardReports(parameters)) {
        return Responses.badRequest("Board contains invalid saved reports.");
      }
      if (existing == null && !request.has("description")) {
        return Responses.uncaught();
      }
      var now = Instant.now();
      var board =
          new Content.Board(
              caller.userId(),
              "dashboard",
              request.has("name") ? request.get("name").asText()
                  : (existing == null ? null : existing.name()),
              request.has("description") ? request.get("description").asText()
                  : (existing == null ? null : existing.description()),
              parameters,
              caller.userId(),
              null,
              existing == null ? now : existing.createdAt(),
              now);
      store.put(Store.BOARD, caller.userId(), board);
      return Responses.json(Writers.board(board));
    });
  }
}
