package io.akka.umami.api;

import akka.http.javadsl.model.HttpEntity;
import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Delete;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import io.akka.umami.application.Claims;
import io.akka.umami.application.Store;
import io.akka.umami.domain.Content;
import io.akka.umami.lib.Constants;
import io.akka.umami.lib.Crypto;
import io.akka.umami.lib.Dates;
import io.akka.umami.lib.Json;
import io.akka.umami.lib.Responses;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Short links and tracking pixels: the two things that collect without a script on the page.
 *
 * <p>A duplicate slug behaves differently on the two write paths, and that is the original's own
 * asymmetry: a create hits the store's constraint and answers a server error, while an update
 * checks first and answers a refusal with a message. SPEC R104.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint
public class LinkEndpoint extends Api {

  public LinkEndpoint(ComponentClient client, akka.stream.Materializer materializer) {
    super(client, materializer);
  }

  // ------------------------------------------------------------------ links

  @Get("/api/links")
  public HttpResponse listLinks() {
    return answer(() -> {
      var caller = caller();
      var query = filters(null);
      var links = new ArrayList<>(store.byOwner(Store.LINK, caller.userId(), Content.Link.class));
      links.removeIf(link -> link.deletedAt() != null);
      var search = query.search();
      if (search != null && !search.isBlank()) {
        links.removeIf(link -> !AccountEndpoint.containsIgnoringCase(link.name(), search)
            && !AccountEndpoint.containsIgnoringCase(link.url(), search)
            && !AccountEndpoint.containsIgnoringCase(link.slug(), search));
      }
      var orderBy =
          effectiveOrderBy(query.orderBy(), List.of("name", "slug", "url", "createdAt"), null);
      if (orderBy != null) {
        Comparator<Content.Link> comparator =
            switch (orderBy) {
              case "slug" -> Comparator.comparing(link ->
                  AccountEndpoint.nullToEmpty(link.slug()));
              case "url" -> Comparator.comparing(link -> AccountEndpoint.nullToEmpty(link.url()));
              case "createdAt" -> Comparator.comparing(link ->
                  link.createdAt() == null ? Instant.EPOCH : link.createdAt());
              default -> Comparator.comparing(link -> AccountEndpoint.nullToEmpty(link.name()));
            };
        links.sort(Boolean.TRUE.equals(query.sortDescending()) ? comparator.reversed()
            : comparator);
      }
      return Responses.json(page(links, query, Writers::link));
    });
  }

  @Post("/api/links")
  public HttpResponse createLink(HttpEntity.Strict requestBody) {
    return answer(() -> {
      var caller = caller();
      var schema = Schema.object();
      schema.string("name").max(100).required();
      schema.string("url").max(500).required();
      schema.string("slug").min(8).max(100).required();
      schema.string("teamId").nullable();
      schema.uuid("id").nullable();
      var request = validate(schema, body(requestBody));

      var teamId = textOrNull(request, "teamId");
      require((teamId == null || permissions.canCreateTeamWebsite(caller, teamId))
          && permissions.canCreateWebsite(caller));

      var slug = request.get("slug").asText();
      var now = Instant.now();
      var id = textOrNull(request, "id") != null ? request.get("id").asText()
          : Crypto.uuid().toString();
      if (!claims.take(Claims.LINK_SLUG, slug, id)) {
        // The create path has no check of its own; the store refuses it. SPEC R104.
        return Responses.uncaught();
      }
      var link =
          new Content.Link(id, request.get("name").asText(), request.get("url").asText(), slug,
              teamId == null ? caller.userId() : null, teamId, now, now, null);
      store.put(Store.LINK, id, link);
      return Responses.json(Writers.link(link));
    });
  }

  @Get("/api/links/charts")
  public HttpResponse linkCharts() {
    return charts(true);
  }

  @Get("/api/links/{linkId}")
  public HttpResponse readLink(String linkId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canViewWebsite(caller, linkId));
      var link = store.link(linkId);
      if (link == null) {
        return Responses.json(Json.object().nullNode());
      }
      return Responses.json(Writers.link(link));
    });
  }

  @Post("/api/links/{linkId}")
  public HttpResponse updateLink(String linkId, HttpEntity.Strict requestBody) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canUpdateEntity(caller, linkId));
      var schema = Schema.object();
      schema.string("name").max(100);
      schema.string("url").max(500);
      schema.string("slug").min(8).max(100);
      var request = validate(schema, body(requestBody));

      var link = store.link(linkId);
      if (link == null) {
        return Responses.notFound();
      }
      if (request.has("slug")) {
        if (!claims.take(Claims.LINK_SLUG, request.get("slug").asText(), linkId)) {
          return Responses.badRequest("That slug is already taken.");
        }
        if (!request.get("slug").asText().equals(link.slug())) {
          claims.release(Claims.LINK_SLUG, link.slug());
        }
      }
      var updated =
          new Content.Link(link.id(),
              request.has("name") ? request.get("name").asText() : link.name(),
              request.has("url") ? request.get("url").asText() : link.url(),
              request.has("slug") ? request.get("slug").asText() : link.slug(),
              link.userId(), link.teamId(), link.createdAt(), Instant.now(), link.deletedAt());
      store.put(Store.LINK, linkId, updated);
      return Responses.json(Writers.link(updated));
    });
  }

  @Delete("/api/links/{linkId}")
  public HttpResponse deleteLink(String linkId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canDeleteEntity(caller, linkId));
      var link = store.link(linkId);
      if (link != null) {
        claims.release(Claims.LINK_SLUG, link.slug());
      }
      store.sharesOf(linkId).forEach(share -> store.remove(Store.SHARE, share.id()));
      store.remove(Store.LINK, linkId);
      return Responses.ok();
    });
  }

  @Get("/api/links/{linkId}/shares")
  public HttpResponse linkShares(String linkId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canViewWebsite(caller, linkId));
      var query = filters(null);
      return Responses.json(page(new ArrayList<>(store.sharesOf(linkId)), query, Writers::share));
    });
  }

  @Post("/api/links/{linkId}/shares")
  public HttpResponse createLinkShare(String linkId, HttpEntity.Strict requestBody) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canUpdateEntity(caller, linkId));
      return Responses.json(
          ShareEndpoint.createShareFor(store, linkId, Constants.ENTITY_LINK, body(requestBody)));
    });
  }

  // ------------------------------------------------------------------ pixels

  @Get("/api/pixels")
  public HttpResponse listPixels() {
    return answer(() -> {
      var caller = caller();
      var query = filters(null);
      var pixels =
          new ArrayList<>(store.byOwner(Store.PIXEL, caller.userId(), Content.Pixel.class));
      var search = query.search();
      if (search != null && !search.isBlank()) {
        pixels.removeIf(pixel -> !AccountEndpoint.containsIgnoringCase(pixel.name(), search)
            && !AccountEndpoint.containsIgnoringCase(pixel.slug(), search));
      }
      var orderBy = effectiveOrderBy(query.orderBy(), List.of("name", "slug", "createdAt"), null);
      if (orderBy != null) {
        Comparator<Content.Pixel> comparator =
            switch (orderBy) {
              case "slug" -> Comparator.comparing(pixel ->
                  AccountEndpoint.nullToEmpty(pixel.slug()));
              case "createdAt" -> Comparator.comparing(pixel ->
                  pixel.createdAt() == null ? Instant.EPOCH : pixel.createdAt());
              default -> Comparator.comparing(pixel -> AccountEndpoint.nullToEmpty(pixel.name()));
            };
        pixels.sort(Boolean.TRUE.equals(query.sortDescending()) ? comparator.reversed()
            : comparator);
      }
      return Responses.json(page(pixels, query, Writers::pixel));
    });
  }

  @Post("/api/pixels")
  public HttpResponse createPixel(HttpEntity.Strict requestBody) {
    return answer(() -> {
      var caller = caller();
      var schema = Schema.object();
      schema.string("name").max(100).required();
      schema.string("slug").min(8).max(100).required();
      schema.string("teamId").nullable();
      schema.uuid("id").nullable();
      var request = validate(schema, body(requestBody));

      var teamId = textOrNull(request, "teamId");
      require((teamId == null || permissions.canCreateTeamWebsite(caller, teamId))
          && permissions.canCreateWebsite(caller));

      var slug = request.get("slug").asText();
      var now = Instant.now();
      var id = textOrNull(request, "id") != null ? request.get("id").asText()
          : Crypto.uuid().toString();
      if (!claims.take(Claims.PIXEL_SLUG, slug, id)) {
        return Responses.uncaught();
      }
      var pixel =
          new Content.Pixel(id, request.get("name").asText(), slug,
              teamId == null ? caller.userId() : null, teamId, now, now, null);
      store.put(Store.PIXEL, id, pixel);
      return Responses.json(Writers.pixel(pixel));
    });
  }

  @Get("/api/pixels/charts")
  public HttpResponse pixelCharts() {
    return charts(false);
  }

  @Get("/api/pixels/{pixelId}")
  public HttpResponse readPixel(String pixelId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canViewWebsite(caller, pixelId));
      var pixel = store.pixel(pixelId);
      if (pixel == null) {
        return Responses.json(Json.object().nullNode());
      }
      return Responses.json(Writers.pixel(pixel));
    });
  }

  @Post("/api/pixels/{pixelId}")
  public HttpResponse updatePixel(String pixelId, HttpEntity.Strict requestBody) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canUpdateEntity(caller, pixelId));
      var schema = Schema.object();
      schema.string("name").max(100);
      schema.string("slug").min(8).max(100);
      var request = validate(schema, body(requestBody));

      var pixel = store.pixel(pixelId);
      if (pixel == null) {
        return Responses.notFound();
      }
      if (request.has("slug")) {
        if (!claims.take(Claims.PIXEL_SLUG, request.get("slug").asText(), pixelId)) {
          return Responses.badRequest("That slug is already taken.");
        }
        if (!request.get("slug").asText().equals(pixel.slug())) {
          claims.release(Claims.PIXEL_SLUG, pixel.slug());
        }
      }
      var updated =
          new Content.Pixel(pixel.id(),
              request.has("name") ? request.get("name").asText() : pixel.name(),
              request.has("slug") ? request.get("slug").asText() : pixel.slug(),
              pixel.userId(), pixel.teamId(), pixel.createdAt(), Instant.now(), pixel.deletedAt());
      store.put(Store.PIXEL, pixelId, updated);
      return Responses.json(Writers.pixel(updated));
    });
  }

  @Delete("/api/pixels/{pixelId}")
  public HttpResponse deletePixel(String pixelId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canDeleteEntity(caller, pixelId));
      var pixel = store.pixel(pixelId);
      if (pixel != null) {
        claims.release(Claims.PIXEL_SLUG, pixel.slug());
      }
      store.sharesOf(pixelId).forEach(share -> store.remove(Store.SHARE, share.id()));
      store.remove(Store.PIXEL, pixelId);
      return Responses.ok();
    });
  }

  @Get("/api/pixels/{pixelId}/shares")
  public HttpResponse pixelShares(String pixelId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canViewWebsite(caller, pixelId));
      var query = filters(null);
      return Responses.json(page(new ArrayList<>(store.sharesOf(pixelId)), query, Writers::share));
    });
  }

  @Post("/api/pixels/{pixelId}/shares")
  public HttpResponse createPixelShare(String pixelId, HttpEntity.Strict requestBody) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canUpdateEntity(caller, pixelId));
      return Responses.json(
          ShareEndpoint.createShareFor(store, pixelId, Constants.ENTITY_PIXEL, body(requestBody)));
    });
  }

  // ------------------------------------------------------------------ their sparklines

  private HttpResponse charts(boolean links) {
    return answer(() -> {
      var caller = caller();
      var ids = queryParam("ids");
      if (ids == null || ids.isBlank()) {
        return Responses.badRequest();
      }
      var wanted = new ArrayList<>(List.of(ids.split(",")));
      if (wanted.size() > 20) {
        var error = Json.object();
        var errors = Json.array();
        errors.add("Too big: expected array to have <=20 items");
        error.set("errors", errors);
        return Responses.badRequest(error);
      }
      var allowed = new ArrayList<String>();
      for (var id : wanted) {
        if (permissions.canViewWebsite(caller, id)) {
          allowed.add(id);
        }
      }
      var timezone = queryParam("timezone");
      var startAt = longParam(queryParam("startAt"));
      var endAt = longParam(queryParam("endAt"));
      Instant start;
      Instant end;
      if (startAt != null && endAt != null) {
        start = Instant.ofEpochMilli(startAt);
        end = Instant.ofEpochMilli(endAt);
      } else {
        var range = Dates.parseDateRange("7day", null, timezone, Instant.now());
        start = range.startDate();
        end = range.endDate();
      }
      int eventType = links ? Constants.LINK_EVENT : Constants.PIXEL_EVENT;
      return Responses.json(insight.listCharts(allowed, start, end, timezone, eventType));
    });
  }
}
