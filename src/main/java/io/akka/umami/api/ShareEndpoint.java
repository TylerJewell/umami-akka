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
import io.akka.umami.lib.Jwt;
import io.akka.umami.lib.Responses;
import java.time.Instant;

/**
 * Read-only links into a website, a link, a pixel or a board.
 *
 * <p>Resolving a slug is the one route with no authentication at all, and it is what mints the
 * anonymous assertion every other share-aware route reads. The assertion is signed, unencrypted and
 * never expires, so a slug handed out is a slug handed out until the share is removed.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint
public class ShareEndpoint extends Api {

  public ShareEndpoint(ComponentClient client, akka.stream.Materializer materializer) {
    super(client, materializer);
  }

  @Post("/api/share")
  public HttpResponse create(HttpEntity.Strict requestBody) {
    return answer(() -> {
      var caller = caller();
      var schema = Schema.object();
      schema.uuid("entityId").required();
      schema.integer("shareType").required();
      schema.string("name").max(200).required();
      schema.string("slug").max(100);
      schema.objectField("parameters").required();
      var request = validate(schema, body(requestBody));

      var entityId = request.get("entityId").asText();
      require(permissions.canUpdateEntity(caller, entityId));

      var now = Instant.now();
      var share =
          new Content.Share(
              Crypto.uuid().toString(),
              entityId,
              request.get("name").asText(),
              request.get("shareType").asInt(),
              request.has("slug") ? request.get("slug").asText() : Crypto.getRandomChars(16),
              request.get("parameters") instanceof ObjectNode parameters ? parameters
                  : Json.object(),
              now,
              now);
      store.put(Store.SHARE, share.id(), share);
      return Responses.json(Writers.share(share));
    });
  }

  /** Adds a share to one entity, which four routes do identically. */
  static ObjectNode createShareFor(Store store, String entityId, int shareType,
      ObjectNode request) {
    var now = Instant.now();
    var name = Json.text(request, "name");
    var parameters =
        request.get("parameters") instanceof ObjectNode given ? given : Json.object();
    var share =
        new Content.Share(Crypto.uuid().toString(), entityId, name, shareType,
            Crypto.getRandomChars(16), parameters, now, now);
    store.put(Store.SHARE, share.id(), share);
    return Writers.share(share);
  }

  /** Public: the slug becomes an assertion naming what it may read. */
  @Get("/api/share/{slug}")
  public HttpResponse resolve(String slug) {
    return answer(() -> {
      var share = store.shareBySlug(slug);
      if (share == null) {
        return Responses.notFound();
      }
      var body = Json.object();
      body.put("shareId", share.id());
      body.put("shareType", share.shareType());
      body.set("parameters", share.parameters() == null ? Json.object() : share.parameters());

      var claims = Json.object();
      claims.put("shareId", share.id());
      claims.put("shareType", share.shareType());
      claims.set("parameters", share.parameters() == null ? Json.object() : share.parameters());

      switch (share.shareType()) {
        case Constants.ENTITY_BOARD -> {
          var board = store.board(share.entityId());
          if (board == null) {
            return Responses.notFound();
          }
          body.put("boardId", board.id());
          claims.put("boardId", board.id());
          var websites = Json.array();
          var pixels = Json.array();
          var links = Json.array();
          if (board.parameters() != null) {
            addIfPresent(board.parameters(), "websiteId", websites);
            addIfPresent(board.parameters(), "pixelId", pixels);
            addIfPresent(board.parameters(), "linkId", links);
          }
          body.set("websiteIds", websites);
          body.set("pixelIds", pixels);
          body.set("linkIds", links);
          claims.set("websiteIds", websites);
          claims.set("pixelIds", pixels);
          claims.set("linkIds", links);
        }
        case Constants.ENTITY_PIXEL -> {
          if (store.pixel(share.entityId()) == null) {
            return Responses.notFound();
          }
          body.put("websiteId", share.entityId());
          body.put("pixelId", share.entityId());
          claims.put("websiteId", share.entityId());
          claims.put("pixelId", share.entityId());
        }
        case Constants.ENTITY_LINK -> {
          if (store.link(share.entityId()) == null) {
            return Responses.notFound();
          }
          body.put("websiteId", share.entityId());
          body.put("linkId", share.entityId());
          claims.put("websiteId", share.entityId());
          claims.put("linkId", share.entityId());
        }
        case Constants.ENTITY_WEBSITE -> {
          if (store.website(share.entityId()) == null) {
            return Responses.notFound();
          }
          body.put("websiteId", share.entityId());
          claims.put("websiteId", share.entityId());
        }
        default -> {
          return Responses.notFound();
        }
      }
      claims.put("type", Constants.SHARE_TOKEN_TYPE);
      body.put("token", Jwt.createToken(claims, Crypto.secret()));
      return Responses.json(body);
    });
  }

  private static void addIfPresent(ObjectNode parameters, String field,
      com.fasterxml.jackson.databind.node.ArrayNode target) {
    var value = parameters.get(field);
    if (value != null && !value.isNull() && !value.asText().isEmpty()) {
      target.add(value.asText());
    }
  }

  @Get("/api/share/id/{shareId}")
  public HttpResponse read(String shareId) {
    return answer(() -> {
      var caller = caller();
      var share = store.share(shareId);
      // The original dereferences the record before it checks it, so an unknown identifier is
      // a server error rather than a refusal. SPEC question-log row 41.
      require(permissions.canViewEntity(caller, share.entityId()));
      return Responses.json(Writers.share(share));
    });
  }

  @Post("/api/share/id/{shareId}")
  public HttpResponse update(String shareId, HttpEntity.Strict requestBody) {
    return answer(() -> {
      var caller = caller();
      var schema = Schema.object();
      schema.string("name").max(200).required();
      schema.string("slug").max(100).required();
      schema.objectField("parameters").required();
      var request = validate(schema, body(requestBody));

      var share = store.share(shareId);
      if (share == null) {
        return Responses.notFound();
      }
      require(permissions.canUpdateEntity(caller, share.entityId()));
      var updated =
          new Content.Share(share.id(), share.entityId(), request.get("name").asText(),
              share.shareType(), request.get("slug").asText(),
              request.get("parameters") instanceof ObjectNode parameters ? parameters
                  : Json.object(),
              share.createdAt(), Instant.now());
      store.put(Store.SHARE, shareId, updated);
      return Responses.json(Writers.share(updated));
    });
  }

  @Delete("/api/share/id/{shareId}")
  public HttpResponse delete(String shareId) {
    return answer(() -> {
      var caller = caller();
      var share = store.share(shareId);
      require(permissions.canDeleteEntity(caller, share.entityId()));
      store.remove(Store.SHARE, shareId);
      return Responses.ok();
    });
  }
}
