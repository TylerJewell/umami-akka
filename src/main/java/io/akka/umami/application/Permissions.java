package io.akka.umami.application;

import com.fasterxml.jackson.databind.JsonNode;
import io.akka.umami.domain.Accounts;
import io.akka.umami.domain.Content;
import io.akka.umami.lib.Constants;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Who may see and change what.
 *
 * <p>Every predicate tests the administrator role first. That is why holding the {@code all}
 * permission is never consulted: the table is a literal membership test, and {@code all} is not a
 * wildcard in it. SPEC R91.
 */
public final class Permissions {

  private final Store store;

  public Permissions(Store store) {
    this.store = store;
  }

  public static boolean hasPermission(String role, String permission) {
    var granted = Constants.ROLE_PERMISSIONS.get(role);
    return granted != null && granted.contains(permission);
  }

  // ------------------------------------------------------------------ entities

  /** A website, a link, a pixel or a board — whichever the identifier turns out to name. */
  public record Entity(String id, String userId, String teamId, String kind) {}

  public Entity entity(String id) {
    var website = store.website(id);
    if (website != null) {
      return new Entity(id, website.userId(), website.teamId(), Store.WEBSITE);
    }
    var link = store.link(id);
    if (link != null) {
      return new Entity(id, link.userId(), link.teamId(), Store.LINK);
    }
    var pixel = store.pixel(id);
    if (pixel != null) {
      return new Entity(id, pixel.userId(), pixel.teamId(), Store.PIXEL);
    }
    var board = store.board(id);
    if (board != null) {
      return new Entity(id, board.userId(), board.teamId(), Store.BOARD);
    }
    return null;
  }

  public boolean canViewWebsite(Accounts.Auth auth, String websiteId) {
    if (auth == null) {
      return false;
    }
    if (auth.isAdmin()) {
      return true;
    }
    if (auth.shareToken() != null && auth.shareToken().names(websiteId)) {
      return true;
    }
    var entity = entity(websiteId);
    if (entity == null || auth.user() == null) {
      return false;
    }
    if (notBlank(entity.userId())) {
      return entity.userId().equals(auth.userId());
    }
    if (notBlank(entity.teamId())) {
      return store.teamUser(entity.teamId(), auth.userId()) != null;
    }
    return false;
  }

  /** Answers the subset that may be read rather than a verdict, which is what a chart wants. */
  public List<String> canViewBatchWebsites(Accounts.Auth auth, List<String> websiteIds) {
    var out = new ArrayList<String>();
    for (var id : new LinkedHashSet<>(websiteIds)) {
      if (canViewWebsite(auth, id)) {
        out.add(id);
      }
    }
    return out;
  }

  public boolean canViewAllWebsites(Accounts.Auth auth) {
    return auth != null && auth.isAdmin();
  }

  public boolean canCreateWebsite(Accounts.Auth auth) {
    if (auth == null || auth.user() == null) {
      return false;
    }
    return auth.isAdmin()
        || hasPermission(auth.user().role(), Constants.PERM_WEBSITE_CREATE);
  }

  public boolean canUpdateWebsite(Accounts.Auth auth, String websiteId) {
    return canChangeEntity(auth, websiteId, Constants.PERM_WEBSITE_UPDATE);
  }

  public boolean canDeleteWebsite(Accounts.Auth auth, String websiteId) {
    return canChangeEntity(auth, websiteId, Constants.PERM_WEBSITE_DELETE);
  }

  private boolean canChangeEntity(Accounts.Auth auth, String entityId, String permission) {
    if (auth == null || auth.user() == null) {
      return false;
    }
    if (auth.isAdmin()) {
      return true;
    }
    var entity = entity(entityId);
    if (entity == null) {
      return false;
    }
    if (notBlank(entity.userId())) {
      return entity.userId().equals(auth.userId());
    }
    if (notBlank(entity.teamId())) {
      var membership = store.teamUser(entity.teamId(), auth.userId());
      return membership != null && hasPermission(membership.role(), permission);
    }
    return false;
  }

  public boolean canViewEntity(Accounts.Auth auth, String entityId) {
    if (auth == null || auth.user() == null) {
      return false;
    }
    if (auth.isAdmin()) {
      return true;
    }
    var entity = entity(entityId);
    if (entity == null) {
      return false;
    }
    if (notBlank(entity.userId())) {
      return entity.userId().equals(auth.userId());
    }
    if (notBlank(entity.teamId())) {
      return store.teamUser(entity.teamId(), auth.userId()) != null;
    }
    return false;
  }

  public boolean canUpdateEntity(Accounts.Auth auth, String entityId) {
    return canChangeEntity(auth, entityId, Constants.PERM_WEBSITE_UPDATE);
  }

  public boolean canDeleteEntity(Accounts.Auth auth, String entityId) {
    return canChangeEntity(auth, entityId, Constants.PERM_WEBSITE_DELETE);
  }

  /** Only a team-owned site may go to a person, and only to the person asking. */
  public boolean canTransferWebsiteToUser(Accounts.Auth auth, String websiteId, String userId) {
    if (auth == null || auth.user() == null) {
      return false;
    }
    var website = store.website(websiteId);
    if (website == null) {
      return false;
    }
    if (auth.isAdmin()) {
      return true;
    }
    if (!notBlank(website.teamId()) || !auth.userId().equals(userId)) {
      return false;
    }
    var membership = store.teamUser(website.teamId(), auth.userId());
    return membership != null
        && hasPermission(membership.role(), Constants.PERM_WEBSITE_TRANSFER_TO_USER);
  }

  /** Only a personally owned site may go to a team, and only to one the caller may add to. */
  public boolean canTransferWebsiteToTeam(Accounts.Auth auth, String websiteId, String teamId) {
    if (auth == null || auth.user() == null) {
      return false;
    }
    if (auth.isAdmin()) {
      return true;
    }
    var website = store.website(websiteId);
    if (website == null || !auth.userId().equals(website.userId())) {
      return false;
    }
    var membership = store.teamUser(teamId, auth.userId());
    return membership != null
        && hasPermission(membership.role(), Constants.PERM_WEBSITE_TRANSFER_TO_TEAM);
  }

  // ------------------------------------------------------------------ teams

  public Accounts.TeamUser canViewTeam(Accounts.Auth auth, String teamId) {
    if (auth == null || auth.user() == null) {
      return null;
    }
    var membership = store.teamUser(teamId, auth.userId());
    if (auth.isAdmin()) {
      return membership == null
          ? new Accounts.TeamUser(null, teamId, auth.userId(), Constants.ROLE_ADMIN, null, null)
          : membership;
    }
    return membership;
  }

  public boolean canCreateTeam(Accounts.Auth auth) {
    if (auth == null || auth.user() == null) {
      return false;
    }
    return auth.isAdmin() || hasPermission(auth.user().role(), Constants.PERM_TEAM_CREATE);
  }

  public boolean canUpdateTeam(Accounts.Auth auth, String teamId) {
    return canActOnTeam(auth, teamId, Constants.PERM_TEAM_UPDATE);
  }

  public boolean canDeleteTeam(Accounts.Auth auth, String teamId) {
    return canActOnTeam(auth, teamId, Constants.PERM_TEAM_DELETE);
  }

  public boolean canCreateTeamWebsite(Accounts.Auth auth, String teamId) {
    return canActOnTeam(auth, teamId, Constants.PERM_WEBSITE_CREATE);
  }

  private boolean canActOnTeam(Accounts.Auth auth, String teamId, String permission) {
    if (auth == null || auth.user() == null) {
      return false;
    }
    if (auth.isAdmin()) {
      return true;
    }
    var membership = store.teamUser(teamId, auth.userId());
    return membership != null && hasPermission(membership.role(), permission);
  }

  /** Leaving a team is always allowed; removing anybody else needs the right to manage it. */
  public boolean canDeleteTeamUser(Accounts.Auth auth, String teamId, String removeUserId) {
    if (auth == null || auth.user() == null) {
      return false;
    }
    if (auth.isAdmin()) {
      return true;
    }
    if (auth.userId().equals(removeUserId)) {
      return true;
    }
    var membership = store.teamUser(teamId, auth.userId());
    return membership != null && hasPermission(membership.role(), Constants.PERM_TEAM_UPDATE);
  }

  public boolean canViewAllTeams(Accounts.Auth auth) {
    return auth != null && auth.isAdmin();
  }

  /** Whichever of the two ranks is higher wins, and equal ranks lose. SPEC R89. */
  public boolean outranks(Accounts.Auth auth, String teamId, String targetUserId) {
    if (auth.isAdmin()) {
      return true;
    }
    var actor = store.teamUser(teamId, auth.userId());
    var target = store.teamUser(teamId, targetUserId);
    if (actor == null || target == null) {
      return false;
    }
    int actorRank = Constants.TEAM_ROLE_RANK.getOrDefault(actor.role(), -1);
    int targetRank = Constants.TEAM_ROLE_RANK.getOrDefault(target.role(), -1);
    return actorRank > targetRank;
  }

  // ------------------------------------------------------------------ accounts

  public boolean isAdmin(Accounts.Auth auth) {
    return auth != null && auth.isAdmin();
  }

  public boolean canViewUser(Accounts.Auth auth, String userId) {
    return auth != null && auth.user() != null
        && (auth.isAdmin() || auth.userId().equals(userId));
  }

  public boolean canUpdateUser(Accounts.Auth auth, String userId) {
    return canViewUser(auth, userId);
  }

  // ------------------------------------------------------------------ shares

  /**
   * A section-scoped read.
   *
   * <p>A share whose parameters carry no boolean for any known section grants every section, which
   * is how a share made before sections existed keeps working. SPEC R96.
   */
  public boolean canViewWebsiteSection(Accounts.Auth auth, String websiteId, List<String> sections) {
    if (auth == null) {
      return false;
    }
    if (auth.user() != null) {
      return canViewWebsite(auth, websiteId);
    }
    var token = auth.shareToken();
    if (token == null || !token.names(websiteId)) {
      return false;
    }
    var parameters = token.parameters();
    if (parameters == null) {
      return true;
    }
    boolean declaresAny = false;
    for (var section : Constants.SHARE_SECTIONS) {
      var value = parameters.get(section);
      if (value != null && value.isBoolean()) {
        declaresAny = true;
        break;
      }
    }
    if (!declaresAny) {
      return true;
    }
    for (var section : sections) {
      var value = parameters.get(section);
      if (value != null && value.isBoolean() && value.asBoolean()) {
        return true;
      }
    }
    return false;
  }

  public boolean canViewSharedWebsite(Accounts.Auth auth, String websiteId) {
    if (auth == null) {
      return false;
    }
    if (auth.user() != null) {
      return canViewWebsite(auth, websiteId);
    }
    return auth.shareToken() != null && auth.shareToken().names(websiteId);
  }

  /** The segment routes, which a share may reach unless it says filtering is off. */
  public boolean canViewSharedWebsiteFilters(Accounts.Auth auth, String websiteId) {
    if (!canViewSharedWebsite(auth, websiteId)) {
      return false;
    }
    if (auth.user() != null) {
      return true;
    }
    var parameters = auth.shareToken().parameters();
    if (parameters == null) {
      return true;
    }
    var allow = parameters.get("allowFilter");
    return allow == null || !allow.isBoolean() || allow.asBoolean();
  }

  /** The routes a share may never reach at all. SPEC R97. */
  public boolean canViewAuthenticatedWebsite(Accounts.Auth auth, String websiteId) {
    if (auth == null || auth.user() == null) {
      return false;
    }
    return canViewWebsite(auth, websiteId);
  }

  // ------------------------------------------------------------------ boards and reports

  /** The share section a report type maps to; a heatmap maps to none. */
  public static String reportSection(String type) {
    return switch (type) {
      case "funnel" -> "funnels";
      case "goal" -> "goals";
      case "journey" -> "journeys";
      case "heatmap" -> null;
      default -> type;
    };
  }

  public boolean canViewReport(Accounts.Auth auth, Content.Report report) {
    if (report == null || auth == null) {
      return false;
    }
    if (auth.isAdmin()) {
      return true;
    }
    if (auth.user() != null && auth.userId().equals(report.userId())) {
      return true;
    }
    var section = reportSection(report.type());
    if (section != null) {
      return canViewWebsiteSection(auth, report.websiteId(), List.of(section));
    }
    return auth.user() != null && canViewWebsite(auth, report.websiteId());
  }

  public boolean canUpdateReport(Accounts.Auth auth, Content.Report report) {
    if (report == null || auth == null || auth.user() == null) {
      return false;
    }
    return auth.isAdmin()
        || auth.userId().equals(report.userId())
        || canUpdateWebsite(auth, report.websiteId());
  }

  public boolean canDeleteReport(Accounts.Auth auth, Content.Report report) {
    if (report == null || auth == null || auth.user() == null) {
      return false;
    }
    return auth.isAdmin()
        || auth.userId().equals(report.userId())
        || canDeleteWebsite(auth, report.websiteId());
  }

  public boolean canViewBoard(Accounts.Auth auth, String boardId) {
    if (auth == null) {
      return false;
    }
    if (auth.isAdmin()) {
      return true;
    }
    if (auth.shareToken() != null && boardId.equals(auth.shareToken().boardId())) {
      return true;
    }
    var board = store.board(boardId);
    if (board == null || auth.user() == null) {
      return false;
    }
    if (notBlank(board.userId())) {
      return board.userId().equals(auth.userId());
    }
    if (notBlank(board.teamId())) {
      return store.teamUser(board.teamId(), auth.userId()) != null;
    }
    return false;
  }

  public boolean canUpdateBoard(Accounts.Auth auth, String boardId) {
    return canChangeBoard(auth, boardId, Constants.PERM_WEBSITE_UPDATE);
  }

  public boolean canDeleteBoard(Accounts.Auth auth, String boardId) {
    return canChangeBoard(auth, boardId, Constants.PERM_WEBSITE_DELETE);
  }

  private boolean canChangeBoard(Accounts.Auth auth, String boardId, String permission) {
    if (auth == null || auth.user() == null) {
      return false;
    }
    if (auth.isAdmin()) {
      return true;
    }
    var board = store.board(boardId);
    if (board == null) {
      return false;
    }
    if (notBlank(board.userId())) {
      return board.userId().equals(auth.userId());
    }
    if (notBlank(board.teamId())) {
      var membership = store.teamUser(board.teamId(), auth.userId());
      return membership != null && hasPermission(membership.role(), permission);
    }
    return false;
  }

  /**
   * Whether every entity a board names is one the caller can see.
   *
   * <p>Checked with the share token deliberately stripped, so a board cannot be built out of the
   * very entities a share was granted.
   */
  public boolean canViewBoardEntities(Accounts.Auth auth, JsonNode parameters) {
    if (parameters == null || parameters.isNull()) {
      return true;
    }
    var userOnly = new Accounts.Auth(auth.token(), auth.authKey(), auth.user(), null);
    for (var field : List.of("websiteId", "pixelId", "linkId")) {
      var value = parameters.get(field);
      if (value == null || value.isNull() || value.asText().isEmpty()) {
        continue;
      }
      if (!canViewWebsite(userOnly, value.asText())) {
        return false;
      }
    }
    return true;
  }

  /** Whether every saved report a board names is of the right type and the right website. */
  public boolean hasValidBoardReports(JsonNode parameters) {
    return collectReportProblems(parameters).isEmpty();
  }

  public List<String> collectReportProblems(JsonNode parameters) {
    var problems = new ArrayList<String>();
    if (parameters == null || parameters.isNull()) {
      return problems;
    }
    var websiteId = parameters.get("websiteId");
    var components = parameters.get("components");
    if (components == null || !components.isArray()) {
      return problems;
    }
    for (var component : components) {
      var type = component.get("type");
      if (type == null || !List.of("Funnel", "Goal").contains(type.asText())) {
        continue;
      }
      var props = component.get("props");
      var reportId = props == null ? null : props.get("reportId");
      if (reportId == null || reportId.isNull() || reportId.asText().isEmpty()) {
        continue;
      }
      var report = store.report(reportId.asText());
      var wanted = "Funnel".equals(type.asText()) ? "funnel" : "goal";
      if (report == null
          || !wanted.equals(report.type())
          || websiteId == null
          || !websiteId.asText().equals(report.websiteId())) {
        problems.add(reportId.asText());
      }
    }
    return problems;
  }

  private static boolean notBlank(String value) {
    return value != null && !value.isEmpty();
  }
}
