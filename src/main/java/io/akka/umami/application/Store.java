package io.akka.umami.application;

import akka.javasdk.client.ComponentClient;
import io.akka.umami.domain.Accounts;
import io.akka.umami.domain.Content;
import io.akka.umami.domain.Recordings;
import io.akka.umami.domain.Security;
import io.akka.umami.domain.Traffic;
import io.akka.umami.lib.Json;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Everything the service reads and writes, in the vocabulary of umami's own tables.
 *
 * <p>Two entity kinds sit underneath: administrative rows and collected facts. Which one a record
 * belongs to is decided here rather than by every caller.
 */
public final class Store {

  public static final String USER = "user";
  public static final String TEAM = "team";
  public static final String TEAM_USER = "teamUser";
  public static final String WEBSITE = "website";
  public static final String LINK = "link";
  public static final String PIXEL = "pixel";
  public static final String BOARD = "board";
  public static final String REPORT = "report";
  public static final String SEGMENT = "segment";
  public static final String SHARE = "share";
  public static final String TWO_FACTOR = "twoFactor";
  public static final String AUTH_SESSION = "authSession";
  public static final String SETTING = "setting";
  public static final String SAVED_REPLAY = "savedReplay";

  public static final String EVENT = "event";
  public static final String SESSION = "session";
  public static final String SESSION_DATA = "sessionData";
  public static final String REVENUE = "revenue";
  public static final String HEATMAP = "heatmap";
  public static final String REPLAY = "replay";
  public static final String SESSION_LINK = "sessionLink";

  private final ComponentClient client;

  public Store(ComponentClient client) {
    this.client = client;
  }

  // ------------------------------------------------------------------ administrative rows

  public <T> T put(String kind, String id, T value) {
    client
        .forEventSourcedEntity(key(kind, id))
        .method(RecordEntity::put)
        .invoke(new RecordEntity.Write(Json.write(value)));
    return value;
  }

  public <T> T find(String kind, String id, Class<T> type) {
    if (id == null || id.isBlank()) {
      return null;
    }
    var state =
        client.forEventSourcedEntity(key(kind, id)).method(RecordEntity::read).invoke();
    return state.exists() ? Json.parse(state.document(), type) : null;
  }

  public void remove(String kind, String id) {
    client.forEventSourcedEntity(key(kind, id)).method(RecordEntity::remove).invoke();
  }

  public <T> List<T> all(String kind, Class<T> type) {
    return documents(
        client.forView().method(RecordsView::byKind).invoke(new RecordsView.ByKind(kind)), type);
  }

  public <T> List<T> byOwner(String kind, String ownerId, Class<T> type) {
    return documents(
        client.forView().method(RecordsView::byOwner).invoke(new RecordsView.ByOwner(kind, ownerId)),
        type);
  }

  public <T> List<T> byTeam(String kind, String teamId, Class<T> type) {
    return documents(
        client.forView().method(RecordsView::byTeam).invoke(new RecordsView.ByTeam(kind, teamId)),
        type);
  }

  public <T> List<T> byParent(String kind, String parentId, Class<T> type) {
    return documents(
        client
            .forView()
            .method(RecordsView::byParent)
            .invoke(new RecordsView.ByParent(kind, parentId)),
        type);
  }

  /** The one row a unique key names, or null. Removed rows are excluded by the caller's rule. */
  public <T> T byUnique(String kind, String uniqueKey, Class<T> type, boolean includeRemoved) {
    var rows =
        client
            .forView()
            .method(RecordsView::byUnique)
            .invoke(new RecordsView.ByUnique(kind, uniqueKey));
    for (var row : rows.items()) {
      if (includeRemoved || row.removed() == 0) {
        return Json.parse(row.document(), type);
      }
    }
    return null;
  }

  private static <T> List<T> documents(RecordsView.Rows rows, Class<T> type) {
    var out = new ArrayList<T>(rows.items().size());
    for (var row : rows.items()) {
      out.add(Json.parse(row.document(), type));
    }
    return out;
  }

  // ------------------------------------------------------------------ collected facts

  public <T> void putFact(String kind, String id, T value) {
    client
        .forEventSourcedEntity(factKey(kind, id))
        .method(FactEntity::put)
        .invoke(new FactEntity.Write(Json.write(value)));
  }

  public <T> T findFact(String kind, String id, Class<T> type) {
    var state = client.forEventSourcedEntity(factKey(kind, id)).method(FactEntity::read).invoke();
    return state.exists() ? Json.parse(state.document(), type) : null;
  }

  public void removeFact(String kind, String id) {
    client.forEventSourcedEntity(factKey(kind, id)).method(FactEntity::remove).invoke();
  }

  public <T> List<T> factsInRange(String kind, String websiteId, Instant from, Instant to,
      Class<T> type) {
    var rows =
        client
            .forView()
            .method(FactsView::byRange)
            .invoke(new FactsView.ByRange(kind, websiteId, from.toEpochMilli(), to.toEpochMilli()));
    return facts(rows, type);
  }

  public <T> List<T> allFacts(String kind, String websiteId, Class<T> type) {
    var rows =
        client.forView().method(FactsView::byWebsite).invoke(new FactsView.ByWebsite(kind,
            websiteId));
    return facts(rows, type);
  }

  public <T> List<T> factsBySession(String kind, String websiteId, String sessionId, Class<T> type) {
    var rows =
        client
            .forView()
            .method(FactsView::bySession)
            .invoke(new FactsView.BySession(kind, websiteId, sessionId));
    return facts(rows, type);
  }

  public <T> List<T> factsByVisit(String kind, String websiteId, String visitId, Class<T> type) {
    var rows =
        client
            .forView()
            .method(FactsView::byVisit)
            .invoke(new FactsView.ByVisit(kind, websiteId, visitId));
    return facts(rows, type);
  }

  public <T> List<T> factsByGroup(String kind, String websiteId, String groupKey, Class<T> type) {
    var rows =
        client
            .forView()
            .method(FactsView::byGroup)
            .invoke(new FactsView.ByGroup(kind, websiteId, groupKey));
    return facts(rows, type);
  }

  /** The identities of every fact of one kind belonging to a website, for removal. */
  public List<String> factKeys(String kind, String websiteId) {
    var rows =
        client.forView().method(FactsView::byWebsite).invoke(new FactsView.ByWebsite(kind,
            websiteId));
    var out = new ArrayList<String>(rows.items().size());
    for (var row : rows.items()) {
      out.add(row.factKey().substring(row.factKey().indexOf(':') + 1));
    }
    return out;
  }

  private static <T> List<T> facts(FactsView.Rows rows, Class<T> type) {
    var out = new ArrayList<T>(rows.items().size());
    for (var row : rows.items()) {
      out.add(Json.parse(row.document(), type));
    }
    return out;
  }

  // ------------------------------------------------------------------ named readers

  public Accounts.User user(String id) {
    return find(USER, id, Accounts.User.class);
  }

  /** An account is looked up by its lower-cased name, which is how it is stored. */
  public Accounts.User userByUsername(String username, boolean includeRemoved) {
    if (username == null) {
      return null;
    }
    return byUnique(USER, username.toLowerCase(Locale.ROOT), Accounts.User.class, includeRemoved);
  }

  public List<Accounts.User> users() {
    return all(USER, Accounts.User.class);
  }

  public Accounts.Team team(String id) {
    return find(TEAM, id, Accounts.Team.class);
  }

  public Accounts.Team teamByAccessCode(String accessCode) {
    return byUnique(TEAM, accessCode, Accounts.Team.class, false);
  }

  public List<Accounts.Team> teams() {
    return all(TEAM, Accounts.Team.class);
  }

  /** Addressed by the pair, so asking whether a membership exists is a settled read. */
  public Accounts.TeamUser teamUser(String teamId, String userId) {
    return find(TEAM_USER, teamId + ":" + userId, Accounts.TeamUser.class);
  }

  public String teamUserKey(String teamId, String userId) {
    return teamId + ":" + userId;
  }

  public List<Accounts.TeamUser> teamMembers(String teamId) {
    var members = new ArrayList<>(byParent(TEAM_USER, teamId, Accounts.TeamUser.class));
    members.sort(Comparator.comparing(Accounts.TeamUser::createdAt));
    return members;
  }

  public List<Accounts.TeamUser> membershipsOf(String userId) {
    return byOwner(TEAM_USER, userId, Accounts.TeamUser.class);
  }

  public Content.Website website(String id) {
    return find(WEBSITE, id, Content.Website.class);
  }

  public List<Content.Website> websites() {
    return all(WEBSITE, Content.Website.class);
  }

  public Content.Link link(String id) {
    return find(LINK, id, Content.Link.class);
  }

  public Content.Link linkBySlug(String slug) {
    return byUnique(LINK, slug, Content.Link.class, false);
  }

  public Content.Pixel pixel(String id) {
    return find(PIXEL, id, Content.Pixel.class);
  }

  public Content.Pixel pixelBySlug(String slug) {
    return byUnique(PIXEL, slug, Content.Pixel.class, false);
  }

  public Content.Board board(String id) {
    return find(BOARD, id, Content.Board.class);
  }

  public Content.Report report(String id) {
    return find(REPORT, id, Content.Report.class);
  }

  public Content.Segment segment(String id) {
    return find(SEGMENT, id, Content.Segment.class);
  }

  public Content.Share share(String id) {
    return find(SHARE, id, Content.Share.class);
  }

  public Content.Share shareBySlug(String slug) {
    return byUnique(SHARE, slug, Content.Share.class, false);
  }

  public List<Content.Share> sharesOf(String entityId) {
    return byParent(SHARE, entityId, Content.Share.class);
  }

  public Security.TwoFactorState twoFactor(String userId) {
    var found = find(TWO_FACTOR, userId, Security.TwoFactorState.class);
    return found == null ? Security.TwoFactorState.empty(userId) : found;
  }

  public Security.AuthSession authSession(String authKey) {
    return find(AUTH_SESSION, authKey, Security.AuthSession.class);
  }

  public String setting(String key) {
    var found = find(SETTING, key, Security.AppSetting.class);
    return found == null ? null : found.value();
  }

  public void setSetting(String key, String value) {
    put(SETTING, key, new Security.AppSetting(key, value));
  }

  public Traffic.Session session(String websiteId, String sessionId) {
    return findFact(SESSION, websiteId + ":" + sessionId, Traffic.Session.class);
  }

  public List<Traffic.Event> events(String websiteId, Instant from, Instant to) {
    return factsInRange(EVENT, websiteId, from, to, Traffic.Event.class);
  }

  public List<Traffic.Session> sessions(String websiteId) {
    return allFacts(SESSION, websiteId, Traffic.Session.class);
  }

  public List<Traffic.SessionProperty> sessionProperties(String websiteId, String sessionId) {
    return factsBySession(SESSION_DATA, websiteId, sessionId, Traffic.SessionProperty.class);
  }

  public List<Traffic.Revenue> revenue(String websiteId, Instant from, Instant to) {
    return factsInRange(REVENUE, websiteId, from, to, Traffic.Revenue.class);
  }

  public List<Recordings.HeatmapEvent> heatmap(String websiteId, Instant from, Instant to) {
    return factsInRange(HEATMAP, websiteId, from, to, Recordings.HeatmapEvent.class);
  }

  public List<Recordings.ReplayChunk> replayChunks(String websiteId, String visitId) {
    var chunks = new ArrayList<>(factsByVisit(REPLAY, websiteId, visitId,
        Recordings.ReplayChunk.class));
    chunks.sort(Comparator.comparingInt(Recordings.ReplayChunk::chunkIndex));
    return chunks;
  }

  public List<Recordings.ReplayChunk> replayChunks(String websiteId) {
    return allFacts(REPLAY, websiteId, Recordings.ReplayChunk.class);
  }

  public List<Traffic.SessionLink> identityLinks(String websiteId, String distinctId) {
    return factsByGroup(SESSION_LINK, websiteId, distinctId, Traffic.SessionLink.class);
  }

  public List<Traffic.SessionLink> identityLinksOfSession(String websiteId, String sessionId) {
    return factsBySession(SESSION_LINK, websiteId, sessionId, Traffic.SessionLink.class);
  }

  public Recordings.SavedReplay savedReplay(String websiteId, String visitId) {
    return byUnique(SAVED_REPLAY, websiteId + ":" + visitId, Recordings.SavedReplay.class, false);
  }

  public List<Recordings.SavedReplay> savedReplays(String websiteId) {
    return byParent(SAVED_REPLAY, websiteId, Recordings.SavedReplay.class);
  }

  // ------------------------------------------------------------------ keys

  private static String key(String kind, String id) {
    return kind + ":" + id;
  }

  private static String factKey(String kind, String id) {
    return kind + ":" + id;
  }
}
