package io.akka.umami.api;

import akka.http.javadsl.model.HttpEntity;
import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Delete;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import io.akka.umami.application.Store;
import io.akka.umami.domain.Accounts;
import io.akka.umami.domain.Content;
import io.akka.umami.lib.Constants;
import io.akka.umami.lib.Crypto;
import io.akka.umami.lib.Json;
import io.akka.umami.lib.Responses;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Teams, their members, and what each team owns. */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint
public class TeamEndpoint extends Api {

  public TeamEndpoint(ComponentClient client) {
    super(client);
  }

  @Get("/api/teams")
  public HttpResponse list() {
    return answer(() -> {
      var caller = caller();
      var query = filters(null);
      var teams = new ArrayList<Accounts.Team>();
      for (var membership : store.membershipsOf(caller.userId())) {
        var team = store.team(membership.teamId());
        if (team != null && !team.isDeleted()) {
          teams.add(team);
        }
      }
      var orderBy = effectiveOrderBy(query.orderBy(), List.of("name", "createdAt"), null);
      if (orderBy != null) {
        Comparator<Accounts.Team> comparator =
            "name".equals(orderBy)
                ? Comparator.comparing(team -> AccountEndpoint.nullToEmpty(team.name()))
                : Comparator.comparing(team ->
                    team.createdAt() == null ? Instant.EPOCH : team.createdAt());
        teams.sort(Boolean.TRUE.equals(query.sortDescending()) ? comparator.reversed()
            : comparator);
      }
      return Responses.json(page(teams, query, Writers::team));
    });
  }

  /** Creating a team answers the team and the membership that made the creator its owner. */
  @Post("/api/teams")
  public HttpResponse create(HttpEntity.Strict requestBody) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canCreateTeam(caller));
      var schema = Schema.object();
      schema.string("name").max(50).required();
      schema.uuid("ownerId");
      var request = validate(schema, body(requestBody));

      var ownerId =
          caller.isAdmin() && request.has("ownerId") ? request.get("ownerId").asText()
              : caller.userId();
      var now = Instant.now();
      var team =
          new Accounts.Team(Crypto.uuid().toString(), request.get("name").asText(),
              "team_" + Crypto.getRandomChars(16), null, false, now, now, null);
      store.put(Store.TEAM, team.id(), team);
      var membership =
          new Accounts.TeamUser(Crypto.uuid().toString(), team.id(), ownerId,
              Constants.ROLE_TEAM_OWNER, now, now);
      store.put(Store.TEAM_USER, store.teamUserKey(team.id(), ownerId), membership);

      var body = Json.array();
      body.add(Writers.team(team));
      body.add(Writers.teamUser(membership));
      return Responses.json(body);
    });
  }

  @Post("/api/teams/join")
  public HttpResponse join(HttpEntity.Strict requestBody) {
    return answer(() -> {
      var caller = caller();
      var schema = Schema.object();
      schema.string("accessCode").max(50).required();
      var request = validate(schema, body(requestBody));

      var team = store.teamByAccessCode(request.get("accessCode").asText());
      if (team == null || team.isDeleted()) {
        return Responses.notFound("Team not found.", "team-not-found");
      }
      if (store.teamUser(team.id(), caller.userId()) != null) {
        return Responses.badRequest("User is already a team member.");
      }
      var now = Instant.now();
      var membership =
          new Accounts.TeamUser(Crypto.uuid().toString(), team.id(), caller.userId(),
              Constants.ROLE_TEAM_MEMBER, now, now);
      store.put(Store.TEAM_USER, store.teamUserKey(team.id(), caller.userId()), membership);
      return Responses.json(Writers.teamUser(membership));
    });
  }

  @Get("/api/teams/{teamId}")
  public HttpResponse read(String teamId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canViewTeam(caller, teamId) != null);
      var team = store.team(teamId);
      if (team == null || team.isDeleted()) {
        return Responses.notFound("Team not found.", null);
      }
      var body = Writers.team(team);
      var members = Json.array();
      store.teamMembers(teamId).forEach(member -> members.add(Writers.teamUser(member)));
      body.set("members", members);
      return Responses.json(body);
    });
  }

  @Post("/api/teams/{teamId}")
  public HttpResponse update(String teamId, HttpEntity.Strict requestBody) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canUpdateTeam(caller, teamId),
          "You must be the owner/manager of this team.", null);
      var schema = Schema.object();
      schema.string("name").max(50);
      schema.string("accessCode").max(50);
      var request = validate(schema, body(requestBody));

      var team = store.team(teamId);
      if (team == null) {
        return Responses.notFound("Team not found.", null);
      }
      var updated =
          new Accounts.Team(team.id(),
              request.has("name") ? request.get("name").asText() : team.name(),
              request.has("accessCode") ? request.get("accessCode").asText() : team.accessCode(),
              team.logoUrl(), team.twoFactorRequired(), team.createdAt(), Instant.now(),
              team.deletedAt());
      store.put(Store.TEAM, teamId, updated);
      return Responses.json(Writers.team(updated));
    });
  }

  @Delete("/api/teams/{teamId}")
  public HttpResponse delete(String teamId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canDeleteTeam(caller, teamId),
          "You must be the owner/manager of this team.", null);
      store.teamMembers(teamId)
          .forEach(member ->
              store.remove(Store.TEAM_USER, store.teamUserKey(teamId, member.userId())));
      for (var kind : List.of(Store.LINK, Store.PIXEL, Store.BOARD)) {
        store.byTeam(kind, teamId, com.fasterxml.jackson.databind.JsonNode.class)
            .forEach(node -> store.remove(kind, node.get("id").asText()));
      }
      store.sharesOf(teamId).forEach(share -> store.remove(Store.SHARE, share.id()));
      store.remove(Store.TEAM, teamId);
      return Responses.ok();
    });
  }

  // ------------------------------------------------------------------ members

  @Get("/api/teams/{teamId}/users")
  public HttpResponse members(String teamId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canViewTeam(caller, teamId) != null,
          "You must be a member of this team.", null);
      var query = filters(null);
      var members = new ArrayList<>(store.teamMembers(teamId));
      members.removeIf(member -> {
        var user = store.user(member.userId());
        return user == null || user.isDeleted();
      });
      var search = query.search();
      if (search != null && !search.isBlank()) {
        members.removeIf(member -> {
          var user = store.user(member.userId());
          return user == null || !AccountEndpoint.containsIgnoringCase(user.username(), search);
        });
      }
      return Responses.json(
          page(members, query.page() == null ? 1 : query.page(),
              query.pageSize() == null ? Constants.DEFAULT_PAGE_SIZE : query.pageSize(),
              query.orderBy(), query.search(),
              member -> {
                var row = Writers.teamUser(member);
                var user = store.user(member.userId());
                if (user != null) {
                  var account = Json.object();
                  account.put("id", user.id());
                  account.put("username", user.username());
                  row.set("user", account);
                }
                return row;
              }));
    });
  }

  @Post("/api/teams/{teamId}/users")
  public HttpResponse addMember(String teamId, HttpEntity.Strict requestBody) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canUpdateTeam(caller, teamId));
      var schema = Schema.object();
      schema.uuid("userId").required();
      schema.string("role").options(new ArrayList<>(List.of(Constants.ROLE_TEAM_MEMBER,
          Constants.ROLE_TEAM_VIEW_ONLY, Constants.ROLE_TEAM_MANAGER))).required();
      var request = validate(schema, body(requestBody));

      var userId = request.get("userId").asText();
      if (store.teamUser(teamId, userId) != null) {
        return Responses.badRequest("User is already a member of the Team.");
      }
      var now = Instant.now();
      var membership =
          new Accounts.TeamUser(Crypto.uuid().toString(), teamId, userId,
              request.get("role").asText(), now, now);
      store.put(Store.TEAM_USER, store.teamUserKey(teamId, userId), membership);
      return Responses.json(Writers.teamUser(membership));
    });
  }

  @Get("/api/teams/{teamId}/users/{userId}")
  public HttpResponse readMember(String teamId, String userId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canUpdateTeam(caller, teamId));
      var membership = store.teamUser(teamId, userId);
      if (membership == null) {
        return Responses.json(Json.object().nullNode());
      }
      return Responses.json(Writers.teamUser(membership));
    });
  }

  @Post("/api/teams/{teamId}/users/{userId}")
  public HttpResponse updateMember(String teamId, String userId, HttpEntity.Strict requestBody) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canUpdateTeam(caller, teamId),
          "You must be the owner/manager of this team.", null);
      var schema = Schema.object();
      schema.string("role").options(new ArrayList<>(List.of(Constants.ROLE_TEAM_MEMBER,
          Constants.ROLE_TEAM_VIEW_ONLY, Constants.ROLE_TEAM_MANAGER))).required();
      var request = validate(schema, body(requestBody));

      require(permissions.outranks(caller, teamId, userId),
          "You do not have permission to modify this user.", null);
      var membership = store.teamUser(teamId, userId);
      if (membership == null) {
        return Responses.badRequest("The User does not exists on this team.");
      }
      var updated =
          new Accounts.TeamUser(membership.id(), teamId, userId, request.get("role").asText(),
              membership.createdAt(), Instant.now());
      store.put(Store.TEAM_USER, store.teamUserKey(teamId, userId), updated);
      return Responses.json(Writers.teamUser(updated));
    });
  }

  @Delete("/api/teams/{teamId}/users/{userId}")
  public HttpResponse removeMember(String teamId, String userId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canDeleteTeamUser(caller, teamId, userId));
      var membership = store.teamUser(teamId, userId);
      if (membership == null) {
        return Responses.badRequest("The User does not exists on this team.");
      }
      if (!caller.isAdmin() && Constants.ROLE_TEAM_OWNER.equals(membership.role())) {
        return Responses.unauthorized("You do not have permission to remove this user.", null);
      }
      if (!userId.equals(caller.userId())) {
        require(permissions.outranks(caller, teamId, userId),
            "You do not have permission to remove this user.", null);
      }
      store.remove(Store.TEAM_USER, store.teamUserKey(teamId, userId));
      return Responses.ok();
    });
  }

  // ------------------------------------------------------------------ what a team owns

  @Get("/api/teams/{teamId}/websites")
  public HttpResponse websites(String teamId) {
    return owned(teamId, Store.WEBSITE, Content.Website.class,
        website -> {
          var row = (Content.Website) website;
          // A team's websites carry whoever created them, not whoever owns them.
          return Writers.withAccount(
              Writers.website(row, WebsiteEndpoint.shareIdOf(store, row.id())),
              "createUser", store.user(row.createdBy()));
        });
  }

  @Get("/api/teams/{teamId}/boards")
  public HttpResponse boards(String teamId) {
    return owned(teamId, Store.BOARD, Content.Board.class,
        board -> Writers.board((Content.Board) board));
  }

  @Get("/api/teams/{teamId}/links")
  public HttpResponse links(String teamId) {
    return owned(teamId, Store.LINK, Content.Link.class,
        link -> Writers.link((Content.Link) link));
  }

  @Get("/api/teams/{teamId}/pixels")
  public HttpResponse pixels(String teamId) {
    return owned(teamId, Store.PIXEL, Content.Pixel.class,
        pixel -> Writers.pixel((Content.Pixel) pixel));
  }

  private <T> HttpResponse owned(String teamId, String kind, Class<T> type,
      java.util.function.Function<Object, com.fasterxml.jackson.databind.JsonNode> writer) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canViewTeam(caller, teamId) != null);
      var query = filters(null);
      var rows = new ArrayList<>(store.byTeam(kind, teamId, type));
      return Responses.json(page(rows, query, writer::apply));
    });
  }

  // ------------------------------------------------------------------ administration

  @Get("/api/admin/teams")
  public HttpResponse adminTeams() {
    return answer(() -> {
      var caller = caller();
      require(permissions.canViewAllTeams(caller));
      var query = filters(null);
      var teams = new ArrayList<>(store.teams());
      teams.removeIf(Accounts.Team::isDeleted);
      var search = query.search();
      if (search != null && !search.isBlank()) {
        teams.removeIf(team -> !AccountEndpoint.containsIgnoringCase(team.name(), search));
      }
      teams.sort(
          Comparator.comparing((Accounts.Team team) ->
              team.createdAt() == null ? Instant.EPOCH : team.createdAt()).reversed());
      return Responses.json(
          page(teams, query.page() == null ? 1 : query.page(),
              query.pageSize() == null ? Constants.DEFAULT_PAGE_SIZE : query.pageSize(),
              "createdAt", query.search(),
              team -> {
                var row = Writers.team(team);
                var members = Json.array();
                for (var member : store.teamMembers(team.id())) {
                  var entry = Writers.teamUser(member);
                  var user = store.user(member.userId());
                  if (user != null && !user.isDeleted()) {
                    var account = Json.object();
                    account.put("id", user.id());
                    account.put("username", user.username());
                    entry.set("user", account);
                    members.add(entry);
                  }
                }
                row.set("members", members);
                var count = Json.object();
                count.put("websites", store.byTeam(Store.WEBSITE, team.id(),
                    Content.Website.class).stream().filter(w -> !w.isDeleted()).count());
                count.put("members", members.size());
                row.set("_count", count);
                return row;
              }));
    });
  }

  @Get("/api/admin/websites")
  public HttpResponse adminWebsites() {
    return answer(() -> {
      var caller = caller();
      require(permissions.canViewAllWebsites(caller));
      var query = filters(null);
      var websites = new ArrayList<>(store.websites());
      websites.removeIf(Content.Website::isDeleted);
      var search = query.search();
      if (search != null && !search.isBlank()) {
        websites.removeIf(
            website ->
                !AccountEndpoint.containsIgnoringCase(website.name(), search)
                    && !AccountEndpoint.containsIgnoringCase(website.domain(), search));
      }
      websites.sort(
          Comparator.comparing((Content.Website website) ->
              website.createdAt() == null ? Instant.EPOCH : website.createdAt()).reversed());
      return Responses.json(
          page(websites, query.page() == null ? 1 : query.page(),
              query.pageSize() == null ? Constants.DEFAULT_PAGE_SIZE : query.pageSize(),
              "createdAt", query.search(),
              website -> {
                var row = Writers.website(website, WebsiteEndpoint.shareIdOf(store, website.id()));
                var owner = store.user(website.userId());
                if (owner != null) {
                  var account = Json.object();
                  account.put("username", owner.username());
                  account.put("id", owner.id());
                  row.set("user", account);
                }
                if (website.teamId() != null) {
                  var team = store.team(website.teamId());
                  if (team != null) {
                    var entry = Writers.team(team);
                    var members = Json.array();
                    for (var member : store.teamMembers(team.id())) {
                      if (Constants.ROLE_TEAM_OWNER.equals(member.role())) {
                        members.add(Writers.teamUser(member));
                      }
                    }
                    entry.set("members", members);
                    row.set("team", entry);
                  }
                }
                return row;
              }));
    });
  }
}
