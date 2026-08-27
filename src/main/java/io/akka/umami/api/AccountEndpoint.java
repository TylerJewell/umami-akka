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
import io.akka.umami.domain.Accounts;
import io.akka.umami.lib.Constants;
import io.akka.umami.lib.Crypto;
import io.akka.umami.lib.Env;
import io.akka.umami.lib.Json;
import io.akka.umami.lib.Passwords;
import io.akka.umami.lib.Responses;
import io.akka.umami.lib.TwoFactor;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Signing in, the caller's own record, and every account the administration screen manages. */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint
public class AccountEndpoint extends Api {

  public AccountEndpoint(ComponentClient client, akka.stream.Materializer materializer) {
    super(client, materializer);
  }

  // ------------------------------------------------------------------ signing in

  @Post("/api/auth/login")
  public HttpResponse login(HttpEntity.Strict requestBody) {
    return answer(() -> {
      var schema = Schema.object();
      schema.string("username").required();
      schema.string("password").required();
      var request = validate(schema, body(requestBody));

      var user = store.userByUsername(request.get("username").asText(), false);
      if (user == null
          || !Passwords.checkPassword(request.get("password").asText(), user.password())) {
        return Responses.unauthorized(null, "incorrect-username-password");
      }

      if (!Env.isSet("CLOUD_MODE")) {
        var twoFactor = store.twoFactor(user.id());
        if (twoFactor.enabled()) {
          if (!TwoFactor.isConfigured()) {
            return Responses.serviceUnavailable(
                TwoFactor.configurationErrorMessage(), TwoFactor.configurationErrorCode());
          }
          var body = Json.object();
          body.put("requiresTwoFactor", true);
          body.put("partialToken", auth.createPartialToken(user.id()));
          return Responses.json(body);
        }
      }
      return Responses.json(sessionBody(user, true));
    });
  }

  /** The body a completed sign-in answers, whichever route completed it. */
  com.fasterxml.jackson.databind.node.ObjectNode sessionBody(Accounts.User user,
      boolean withFingerprint) {
    var body = Json.object();
    body.put("token", auth.createSessionToken(user, withFingerprint));
    var account = Json.object();
    account.put("id", user.id());
    account.put("username", user.username());
    account.put("role", user.role());
    account.put("createdAt", Writers.stamp(user.createdAt()));
    account.put("isAdmin", user.isAdmin());
    account.set("teams", teamsOf(user.id()));
    body.set("user", account);
    return body;
  }

  private com.fasterxml.jackson.databind.node.ArrayNode teamsOf(String userId) {
    var out = Json.array();
    for (var membership : store.membershipsOf(userId)) {
      var team = store.team(membership.teamId());
      if (team == null || team.isDeleted()) {
        continue;
      }
      var row = Json.object();
      row.put("id", team.id());
      row.put("name", team.name());
      row.put("logoUrl", team.logoUrl());
      out.add(row);
    }
    return out;
  }

  @Post("/api/auth/logout")
  public HttpResponse logout() {
    return answer(() -> {
      var caller = caller();
      auth.removeStoredSession(caller.authKey());
      return Responses.ok();
    });
  }

  /**
   * The exchange that hands out a sign-in held on the server.
   *
   * <p>The original refuses this outright without its cache, because that is where it keeps such a
   * sign-in. This port's session store is always present, so the route works. SPEC D6.
   */
  @Post("/api/auth/sso")
  public HttpResponse sso() {
    return answer(() -> {
      var caller = caller();
      var user = store.user(caller.userId());
      if (user == null) {
        return Responses.unauthorized();
      }
      var body = Json.object();
      body.set("user", Writers.user(caller.user()));
      body.put("token", auth.createStoredSession(user, 86400L));
      return Responses.json(body);
    });
  }

  @Post("/api/auth/verify")
  public HttpResponse verify() {
    return answer(() -> {
      var caller = caller();
      var body = Json.object();
      body.put("id", caller.userId());
      body.put("username", caller.user() == null ? null : caller.user().username());
      body.put("role", caller.user() == null ? null : caller.user().role());
      body.put("createdAt",
          caller.user() == null ? null : Writers.stamp(caller.user().createdAt()));
      body.put("isAdmin", caller.isAdmin());
      body.put("twoFactorRequired",
          caller.user() != null && caller.user().twoFactorRequired());
      body.set("teams", teamsOf(caller.userId()));
      return Responses.json(body);
    });
  }

  /**
   * What umami's own hosted service says about a subscription.
   *
   * <p>That service is a third party's, and a self-hosted deployment cannot reach it, so the answer
   * is the unsubscribed shape a self-hosted deployment already gets.
   */
  @Get("/api/auth/subscription")
  public HttpResponse subscription() {
    return answer(() -> {
      var caller = caller();
      var teamId = queryParam("teamId");
      if (teamId != null) {
        require(permissions.canViewTeam(caller, teamId) != null);
      }
      var body = Json.object();
      body.put("hasSubscription", false);
      body.put("plan", (String) null);
      body.put("unlimitedWebsites", false);
      body.put("hasBilling", false);
      return Responses.json(body);
    });
  }

  // ------------------------------------------------------------------ the caller's own record

  @Get("/api/me")
  public HttpResponse me() {
    return answer(() -> {
      var caller = caller();
      var body = Json.object();
      body.put("token", caller.token());
      body.put("authKey", caller.authKey());
      if (caller.shareToken() != null) {
        body.put("shareToken", caller.shareToken().shareId());
      }
      if (caller.user() != null) {
        var account = Writers.user(caller.user());
        account.put("isAdmin", caller.isAdmin());
        body.set("user", account);
      }
      return Responses.json(body);
    });
  }

  @Post("/api/me/password")
  public HttpResponse changePassword(HttpEntity.Strict requestBody) {
    return answer(() -> {
      var caller = caller();
      var schema = Schema.object();
      schema.string("currentPassword").required();
      schema.string("newPassword").min(8).required();
      var request = validate(schema, body(requestBody));

      var user = store.user(caller.userId());
      if (user == null
          || !Passwords.checkPassword(request.get("currentPassword").asText(), user.password())) {
        return Responses.badRequest("Current password is incorrect");
      }
      var updated =
          new Accounts.User(user.id(), user.username(),
              Passwords.hashPassword(request.get("newPassword").asText()), user.role(),
              user.logoUrl(), user.displayName(), user.twoFactorRequired(), user.createdAt(),
              Instant.now(), user.deletedAt());
      store.put(Store.USER, user.id(), updated);
      return Responses.json(Writers.user(updated));
    });
  }

  @Get("/api/me/teams")
  public HttpResponse myTeams() {
    return answer(() -> {
      var caller = caller();
      return Responses.json(teamPage(caller.userId()));
    });
  }

  private com.fasterxml.jackson.databind.node.ObjectNode teamPage(String userId) {
    var query = filters(null);
    var teams = new ArrayList<Accounts.Team>();
    for (var membership : store.membershipsOf(userId)) {
      var team = store.team(membership.teamId());
      if (team != null && !team.isDeleted()) {
        teams.add(team);
      }
    }
    var orderBy = effectiveOrderBy(query.orderBy(), List.of("name", "createdAt"), null);
    if (orderBy != null) {
      sortTeams(teams, orderBy, query.sortDescending());
    }
    var search = query.search();
    if (search != null && !search.isBlank()) {
      teams.removeIf(team -> !containsIgnoringCase(team.name(), search));
    }
    return page(teams, query.page() == null ? 1 : query.page(),
        query.pageSize() == null ? Constants.DEFAULT_PAGE_SIZE : query.pageSize(), orderBy,
        query.search(), team -> {
          var row = Writers.team(team);
          var count = Json.object();
          long websites = store.byTeam(Store.WEBSITE, team.id(),
              io.akka.umami.domain.Content.Website.class).stream()
              .filter(website -> !website.isDeleted()).count();
          count.put("websites", websites);
          count.put("members", store.teamMembers(team.id()).size());
          row.set("_count", count);
          return row;
        });
  }

  private static void sortTeams(List<Accounts.Team> teams, String orderBy, Boolean descending) {
    Comparator<Accounts.Team> comparator =
        "name".equals(orderBy)
            ? Comparator.comparing(team -> nullToEmpty(team.name()))
            : Comparator.comparing(team -> team.createdAt() == null ? Instant.EPOCH
                : team.createdAt());
    teams.sort(Boolean.TRUE.equals(descending) ? comparator.reversed() : comparator);
  }

  @Get("/api/me/websites")
  public HttpResponse myWebsites() {
    return answer(() -> {
      var caller = caller();
      boolean includeTeams = queryParam("includeTeams") != null;
      return Responses.json(WebsiteEndpoint.websitePage(this, store, caller.userId(), includeTeams,
          filters(null)));
    });
  }

  // ------------------------------------------------------------------ accounts

  @Post("/api/users")
  public HttpResponse createUser(HttpEntity.Strict requestBody) {
    return answer(() -> {
      var caller = caller();
      require(permissions.isAdmin(caller));
      var schema = Schema.object();
      schema.uuid("id");
      schema.string("username").max(255).required();
      schema.string("password").min(8).max(255).required();
      schema.string("role").options(new ArrayList<>(List.of(Constants.ROLE_ADMIN,
          Constants.ROLE_USER, Constants.ROLE_VIEW_ONLY))).required();
      var request = validate(schema, body(requestBody));

      var username = request.get("username").asText().toLowerCase(Locale.ROOT);
      var id = request.has("id") ? request.get("id").asText() : Crypto.uuid().toString();
      // A removed account still holds its name, so re-creating it is refused rather than
      // silently taking the name over.
      if (!claims.take(Claims.USERNAME, username, id)) {
        return Responses.badRequest("User already exists");
      }
      var now = Instant.now();
      var user =
          new Accounts.User(id, username,
              Passwords.hashPassword(request.get("password").asText()),
              request.get("role").asText(), null, null, false, now, now, null);
      store.put(Store.USER, id, user);
      var body = Json.object();
      body.put("id", id);
      body.put("username", username);
      body.put("role", user.role());
      return Responses.json(body);
    });
  }

  @Get("/api/users/{userId}")
  public HttpResponse readUser(String userId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canViewUser(caller, userId));
      var user = store.user(userId);
      if (user == null || user.isDeleted()) {
        return Responses.json(Json.object().nullNode());
      }
      return Responses.json(Writers.user(user));
    });
  }

  /**
   * A change to an account.
   *
   * <p>A non-administrator asking to change a name or a role is answered 200 with the field
   * unchanged rather than refused, which is what the original does. SPEC R85.
   */
  @Post("/api/users/{userId}")
  public HttpResponse updateUser(String userId, HttpEntity.Strict requestBody) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canUpdateUser(caller, userId));
      var schema = Schema.object();
      schema.string("username").max(255);
      schema.string("password").min(8).max(255);
      schema.string("role").options(new ArrayList<>(List.of(Constants.ROLE_ADMIN,
          Constants.ROLE_USER, Constants.ROLE_VIEW_ONLY)));
      var request = validate(schema, body(requestBody));

      var user = store.user(userId);
      if (user == null || user.isDeleted()) {
        return Responses.notFound();
      }
      var username = user.username();
      var role = user.role();
      if (caller.isAdmin()) {
        if (request.has("username")) {
          var wanted = request.get("username").asText().toLowerCase(Locale.ROOT);
          if (!claims.take(Claims.USERNAME, wanted, userId)) {
            return Responses.badRequest("User already exists");
          }
          if (!wanted.equals(username)) {
            claims.release(Claims.USERNAME, username);
          }
          username = wanted;
        }
        if (request.has("role")) {
          role = request.get("role").asText();
        }
      }
      var password =
          request.has("password")
              ? Passwords.hashPassword(request.get("password").asText())
              : user.password();
      var updated =
          new Accounts.User(user.id(), username, password, role, user.logoUrl(),
              user.displayName(), user.twoFactorRequired(), user.createdAt(), Instant.now(),
              user.deletedAt());
      store.put(Store.USER, userId, updated);
      var body = Json.object();
      body.put("id", updated.id());
      body.put("username", updated.username());
      body.put("role", updated.role());
      body.put("createdAt", Writers.stamp(updated.createdAt()));
      return Responses.json(body);
    });
  }

  @Delete("/api/users/{userId}")
  public HttpResponse deleteUser(String userId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.isAdmin(caller));
      if (userId.equals(caller.userId())) {
        return Responses.badRequest("You cannot delete yourself.");
      }
      var user = store.user(userId);
      if (user == null) {
        return Responses.ok();
      }
      for (var website : store.byOwner(Store.WEBSITE, userId,
          io.akka.umami.domain.Content.Website.class)) {
        collect.purge(website.id());
        store.remove(Store.WEBSITE, website.id());
      }
      for (var membership : store.membershipsOf(userId)) {
        store.remove(Store.TEAM_USER, store.teamUserKey(membership.teamId(), userId));
      }
      for (var kind : List.of(Store.LINK, Store.PIXEL, Store.BOARD)) {
        store.byOwner(kind, userId, com.fasterxml.jackson.databind.JsonNode.class)
            .forEach(node -> store.remove(kind, node.get("id").asText()));
      }
      store.remove(Store.TWO_FACTOR, userId);
      claims.release(Claims.USERNAME, user.username());
      store.remove(Store.USER, userId);
      return Responses.ok();
    });
  }

  @Get("/api/users/{userId}/teams")
  public HttpResponse userTeams(String userId) {
    return answer(() -> {
      var caller = caller();
      require(caller.isAdmin() || userId.equals(caller.userId()));
      return Responses.json(teamPage(userId));
    });
  }

  @Get("/api/users/{userId}/websites")
  public HttpResponse userWebsites(String userId) {
    return answer(() -> {
      var caller = caller();
      require(caller.isAdmin() || userId.equals(caller.userId()));
      boolean includeTeams = queryParam("includeTeams") != null;
      return Responses.json(
          WebsiteEndpoint.websitePage(this, store, userId, includeTeams, filters(null)));
    });
  }

  // ------------------------------------------------------------------ administration

  @Get("/api/admin/users")
  public HttpResponse adminUsers() {
    return answer(() -> {
      var caller = caller();
      require(permissions.isAdmin(caller));
      var query = filters(null);
      var users = new ArrayList<>(store.users());
      users.removeIf(Accounts.User::isDeleted);
      var search = query.search();
      if (search != null && !search.isBlank()) {
        users.removeIf(user -> !containsIgnoringCase(user.username(), search));
      }
      var orderBy =
          effectiveOrderBy(query.orderBy(), List.of("username", "role", "createdAt"), "createdAt");
      boolean descending =
          query.sortDescending() == null ? "createdAt".equals(orderBy) : query.sortDescending();
      var comparator = userComparator(orderBy);
      users.sort(descending ? comparator.reversed() : comparator);
      return Responses.json(
          page(users, query.page() == null ? 1 : query.page(),
              query.pageSize() == null ? Constants.DEFAULT_PAGE_SIZE : query.pageSize(), orderBy,
              query.search(),
              user -> Writers.adminUser(user, store.byOwner(Store.WEBSITE, user.id(),
                  io.akka.umami.domain.Content.Website.class).stream()
                  .filter(website -> !website.isDeleted()).count())));
    });
  }

  private static Comparator<Accounts.User> userComparator(String orderBy) {
    return switch (orderBy) {
      case "username" -> Comparator.comparing(user -> nullToEmpty(user.username()));
      case "role" -> Comparator.comparing(user -> nullToEmpty(user.role()));
      default -> Comparator.comparing(
          user -> user.createdAt() == null ? Instant.EPOCH : user.createdAt());
    };
  }

  static boolean containsIgnoringCase(String value, String needle) {
    return value != null
        && value.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
  }

  static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }
}
