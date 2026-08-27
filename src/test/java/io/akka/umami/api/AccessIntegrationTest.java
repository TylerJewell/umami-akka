package io.akka.umami.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import akka.javasdk.testkit.TestKitSupport;
import io.akka.umami.lib.Json;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** SPEC R84 to R99: who may see and change what, and what a refusal looks like. */
class AccessIntegrationTest extends TestKitSupport {

  private HttpClientSupport http;
  private String adminId;

  @BeforeEach
  void signIn() {
    http = new HttpClientSupport("http://localhost:" + testKit.getPort());
    var answer =
        Settle.untilStarted(() -> http.signIn("admin", "umami"), a -> a.status() == 200,
            "the first administrator");
    adminId = answer.body().get("user").get("id").asText();
  }

  private HttpClientSupport as(String username, String password) {
    var other = new HttpClientSupport("http://localhost:" + testKit.getPort());
    var answer =
        Settle.until(() -> other.signIn(username, password), a -> a.status() == 200, username);
    assertEquals(200, answer.status());
    return other;
  }

  private String createUser(String username, String password, String role) {
    var body = Json.object();
    body.put("username", username);
    body.put("password", password);
    body.put("role", role);
    var answer = http.post("/api/users", body);
    assertEquals(200, answer.status(), answer.body() == null ? "" : answer.body().toString());
    return answer.text("id");
  }

  private String createWebsite(String name, String domain) {
    var body = Json.object();
    body.put("name", name);
    body.put("domain", domain);
    return http.post("/api/websites", body).text("id");
  }

  // ------------------------------------------------------------------ refusals

  @Test
  void everyRefusalLooksTheSameWhateverIsMissing() {
    var anonymous = new HttpClientSupport("http://localhost:" + testKit.getPort());
    var none = anonymous.get("/api/me");
    assertEquals(401, none.status());
    assertEquals("Unauthorized", none.errorMessage());
    assertEquals("unauthorized", none.errorCode());

    anonymous.useToken("nonsense");
    var bad = anonymous.get("/api/me");
    assertEquals(401, bad.status());
    assertEquals("unauthorized", bad.errorCode());
  }

  @Test
  void aWrongPasswordAndAnUnknownAccountAreIndistinguishable() {
    var anonymous = new HttpClientSupport("http://localhost:" + testKit.getPort());
    var wrong = anonymous.signIn("admin", "not-the-password");
    assertEquals(401, wrong.status());
    assertEquals("incorrect-username-password", wrong.errorCode());
    assertEquals("Unauthorized", wrong.errorMessage());

    var unknown = anonymous.signIn("nobody-at-all", "x");
    assertEquals(401, unknown.status());
    assertEquals("incorrect-username-password", unknown.errorCode());
  }

  @Test
  void aMalformedRequestNamesTheFieldThatFailed() {
    var anonymous = new HttpClientSupport("http://localhost:" + testKit.getPort());
    var body = Json.object();
    body.put("username", "admin");
    var answer = anonymous.post("/api/auth/login", body);
    assertEquals(400, answer.status());
    assertEquals("bad-request", answer.errorCode());
    assertEquals("Invalid input: expected string, received undefined",
        answer.body().get("error").get("properties").get("password").get("errors").get(0).asText());
  }

  // ------------------------------------------------------------------ accounts

  @Test
  void anAccountNameIsLowerCasedAndTakenOnce() {
    var id = createUser("ProbeCase", "password1", "user");
    assertNotNull(id);
    var read = Settle.until(() -> http.get("/api/users/" + id), a -> a.status() == 200, "the account");
    assertEquals("probecase", read.text("username"));

    var body = Json.object();
    body.put("username", "probecase");
    body.put("password", "password1");
    body.put("role", "user");
    var again =
        Settle.until(() -> http.post("/api/users", body), a -> a.status() == 400, "the clash");
    assertEquals("User already exists", again.errorMessage());
  }

  @Test
  void aShortPasswordAndAnUnknownRoleAreBothRefusedByName() {
    var short_ = Json.object();
    short_.put("username", "probeshort");
    short_.put("password", "short");
    short_.put("role", "user");
    var answer = http.post("/api/users", short_);
    assertEquals(400, answer.status());
    assertEquals("Too small: expected string to have >=8 characters",
        answer.body().get("error").get("properties").get("password").get("errors").get(0).asText());

    var role = Json.object();
    role.put("username", "proberole");
    role.put("password", "password1");
    role.put("role", "wizard");
    var refused = http.post("/api/users", role);
    assertEquals(400, refused.status());
    assertEquals("Invalid option: expected one of \"admin\"|\"user\"|\"view-only\"",
        refused.body().get("error").get("properties").get("role").get("errors").get(0).asText());
  }

  @Test
  void anAccountMayNotDeleteItself() {
    var answer = http.delete("/api/users/" + adminId);
    assertEquals(400, answer.status());
    assertEquals("You cannot delete yourself.", answer.errorMessage());
  }

  @Test
  void aNonAdministratorCannotRaiseItsOwnRoleAndIsNotToldSo() {
    var id = createUser("proberaise", "password1", "user");
    var them = as("proberaise", "password1");
    var body = Json.object();
    body.put("role", "admin");
    var answer = them.post("/api/users/" + id, body);
    assertEquals(200, answer.status(), "the request succeeds");
    assertEquals("user", answer.text("role"), "and the field is unchanged");
  }

  @Test
  void aNonAdministratorSeesNeitherAnotherAccountNorTheAdministrationList() {
    createUser("probesees", "password1", "user");
    var them = as("probesees", "password1");
    assertEquals(401, them.get("/api/users/" + adminId).status());
    assertEquals(401, them.get("/api/admin/users").status());
    assertEquals(401, them.get("/api/admin/teams").status());
    assertEquals(401, them.get("/api/admin/websites").status());
  }

  // ------------------------------------------------------------------ teams

  @Test
  void creatingATeamAnswersTheTeamAndTheOwnership() {
    var body = Json.object();
    body.put("name", "probe team");
    var answer = http.post("/api/teams", body);
    assertEquals(200, answer.status());
    assertTrue(answer.body().isArray());
    assertEquals(2, answer.body().size());
    assertEquals("probe team", answer.body().get(0).get("name").asText());
    assertTrue(answer.body().get(0).get("accessCode").asText().startsWith("team_"));
    assertEquals("team-owner", answer.body().get(1).get("role").asText());
    assertEquals(adminId, answer.body().get(1).get("userId").asText());
  }

  @Test
  void ownershipCannotBeAssignedThroughTheInterface() {
    var create = Json.object();
    create.put("name", "probe owner");
    var teamId = http.post("/api/teams", create).body().get(0).get("id").asText();
    var userId = createUser("probeowner", "password1", "user");

    var add = Json.object();
    add.put("userId", userId);
    add.put("role", "team-member");
    assertEquals(200,
        Settle.until(() -> http.post("/api/teams/" + teamId + "/users", add),
            a -> a.status() == 200, "the membership").status());

    var promote = Json.object();
    promote.put("role", "team-owner");
    var answer = http.post("/api/teams/" + teamId + "/users/" + userId, promote);
    assertEquals(400, answer.status());
    assertEquals(
        "Invalid option: expected one of \"team-member\"|\"team-view-only\"|\"team-manager\"",
        answer.body().get("error").get("properties").get("role").get("errors").get(0).asText());
  }

  @Test
  void aMemberMayNotChangeTheOwner() {
    var create = Json.object();
    create.put("name", "probe rank");
    var teamId = http.post("/api/teams", create).body().get(0).get("id").asText();
    var userId = createUser("proberank", "password1", "user");
    var add = Json.object();
    add.put("userId", userId);
    add.put("role", "team-member");
    Settle.until(() -> http.post("/api/teams/" + teamId + "/users", add), a -> a.status() == 200,
        "the membership");

    var them = as("proberank", "password1");
    var demote = Json.object();
    demote.put("role", "team-view-only");
    var answer = them.post("/api/teams/" + teamId + "/users/" + adminId, demote);
    assertEquals(401, answer.status());
    assertEquals("You must be the owner/manager of this team.", answer.errorMessage());
  }

  @Test
  void joiningWithAnUnknownCodeIsTheOneNotFoundThatCarriesItsOwnCode() {
    var body = Json.object();
    body.put("accessCode", "team_nothing");
    var answer = http.post("/api/teams/join", body);
    assertEquals(404, answer.status());
    assertEquals("Team not found.", answer.errorMessage());
    assertEquals("team-not-found", answer.errorCode());
  }

  @Test
  void joiningTwiceIsRefused() {
    var create = Json.object();
    create.put("name", "probe join");
    var team = http.post("/api/teams", create).body().get(0);
    createUser("probejoin", "password1", "user");
    var them = as("probejoin", "password1");

    var body = Json.object();
    body.put("accessCode", team.get("accessCode").asText());
    assertEquals(200,
        Settle.until(() -> them.post("/api/teams/join", body), a -> a.status() == 200,
            "the first joining").status());
    var again = them.post("/api/teams/join", body);
    assertEquals(400, again.status());
    assertEquals("User is already a team member.", again.errorMessage());
  }

  // ------------------------------------------------------------------ sharing

  @Test
  void aShareReachesTheSectionsItNamesAndNothingElse() {
    var websiteId = createWebsite("probe share", "probeshare.test");
    var create = Json.object();
    create.put("name", "public");
    var parameters = Json.object();
    parameters.put("overview", true);
    create.set("parameters", parameters);
    var share =
        Settle.until(() -> http.post("/api/websites/" + websiteId + "/shares", create),
            a -> a.status() == 200, "the share");
    var slug = share.text("slug");

    var anonymous = new HttpClientSupport("http://localhost:" + testKit.getPort());
    var resolved =
        Settle.until(() -> anonymous.get("/api/share/" + slug), a -> a.status() == 200,
            "the resolved share");
    assertEquals(1, resolved.body().get("shareType").asInt());
    assertEquals(websiteId, resolved.text("websiteId"));
    var token = resolved.text("token");
    assertNotNull(token);

    var window = "?startAt=1755996400000&endAt=1756003600000&timezone=UTC";
    var headers = Map.of("x-umami-share-token", token, "x-umami-share-context", "1");
    assertEquals(200,
        anonymous.get("/api/websites/" + websiteId + "/stats" + window, headers).status(),
        "the section the share names");
    assertEquals(401,
        anonymous.get("/api/websites/" + websiteId + "/sessions" + window, headers).status(),
        "a section it does not");
    assertEquals(401,
        anonymous.get("/api/websites/" + websiteId + "/stats" + window,
            Map.of("x-umami-share-token", token)).status(),
        "without the context header the assertion counts for nothing");
    assertEquals(401,
        anonymous.get("/api/websites/" + websiteId + "/shares" + window, headers).status(),
        "some routes are closed to a share outright");
  }

  @Test
  void anUnknownSlugIsNotFoundAndAnUnknownShareIdIsAServerError() {
    var anonymous = new HttpClientSupport("http://localhost:" + testKit.getPort());
    var slug = anonymous.get("/api/share/does-not-exist");
    assertEquals(404, slug.status());
    assertEquals("not-found", slug.errorCode());

    var identifier = http.get("/api/share/id/" + UUID.randomUUID());
    assertEquals(500, identifier.status(),
        "the record is read before it is checked, which is what the original does");
  }

  // ------------------------------------------------------------------ paging

  @Test
  void theEnvelopeCarriesOnlyTheKeysThatHaveValues() {
    createWebsite("probe page one", "pageone.test");
    createWebsite("probe page two", "pagetwo.test");
    createWebsite("probe page three", "pagethree.test");

    var page =
        Settle.until(() -> http.get("/api/websites?page=1&pageSize=2&orderBy=name"),
            a -> a.status() == 200 && a.body().get("count").asInt() >= 3, "three websites");
    assertEquals(2, page.body().get("data").size());
    assertEquals(1, page.body().get("page").asInt());
    assertEquals(2, page.body().get("pageSize").asInt());
    assertEquals("name", page.body().get("orderBy").asText());
    assertFalse(page.body().has("search"), "a term nobody gave is absent rather than null");

    var searched = http.get("/api/websites?search=probe%20page");
    assertEquals("probe page", searched.body().get("search").asText());
  }

  @Test
  void anUnknownSortFieldFallsBackToTheDefault() {
    createWebsite("probe sort", "probesort.test");
    var answer =
        Settle.until(() -> http.get("/api/websites?orderBy=nonsense"),
            a -> a.status() == 200, "the list");
    assertEquals("name", answer.body().get("orderBy").asText(),
        "the field asked for is dropped and the module's own default applies");
  }

  // ------------------------------------------------------------------ websites

  @Test
  void aDomainHasToLookLikeOne() {
    var body = Json.object();
    body.put("name", "bad");
    body.put("domain", "not a domain");
    var answer = http.post("/api/websites", body);
    assertEquals(400, answer.status());
    assertTrue(
        answer.body().get("error").get("properties").get("domain").get("errors").get(0).asText()
            .startsWith("Invalid string: must match pattern /"));
  }

  @Test
  void resettingAWebsiteMakesEveryEarlierFigureZero() {
    var websiteId = createWebsite("probe reset", "probereset.test");
    var payload = Json.object();
    payload.put("website", websiteId);
    payload.put("url", "/");
    payload.put("userAgent",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0.0.0 Safari/537.36");
    payload.put("ip", "203.0.113.9");
    payload.put("timestamp", 1756000000L);
    var body = Json.object();
    body.put("type", "event");
    body.set("payload", payload);
    assertEquals(200, http.post("/api/send", body).status());

    var window = "?startAt=1755996400000&endAt=1756003600000&timezone=UTC";
    Settle.until(() -> http.get("/api/websites/" + websiteId + "/stats" + window),
        a -> a.status() == 200 && a.number("pageviews") == 1, "the page view");

    assertEquals(200, http.post("/api/websites/" + websiteId + "/reset", Json.object()).status());
    var after =
        Settle.until(() -> http.get("/api/websites/" + websiteId + "/stats" + window),
            a -> a.status() == 200 && a.number("pageviews") == 0, "nothing before the reset");
    assertEquals(0, after.number("pageviews"));
  }

  @Test
  void theRecorderSettingsAreServedWithoutASignIn() {
    var websiteId = createWebsite("probe recorder", "proberecorder.test");
    var anonymous = new HttpClientSupport("http://localhost:" + testKit.getPort());
    var off =
        Settle.until(() -> anonymous.get("/api/websites/" + websiteId + "/recorder"),
            a -> a.status() == 200, "the recorder settings");
    assertFalse(off.body().get("enabled").asBoolean());

    var update = Json.object();
    var configuration = Json.object();
    configuration.put("replayEnabled", true);
    configuration.put("heatmapEnabled", true);
    update.set("replayConfig", configuration);
    var changed = http.post("/api/websites/" + websiteId, update);
    assertEquals(200, changed.status());
    assertTrue(changed.body().get("recorderEnabled").asBoolean());

    var on =
        Settle.until(() -> anonymous.get("/api/websites/" + websiteId + "/recorder"),
            a -> a.status() == 200 && a.body().get("enabled").asBoolean(), "the recorder switched on");
    assertEquals(0.15, on.body().get("sampleRate").asDouble());
    assertEquals("moderate", on.body().get("maskLevel").asText());
    assertEquals(300000, on.body().get("maxDuration").asInt());
    assertEquals("", on.body().get("blockSelector").asText());
  }

  @Test
  void theExportIsOneArchiveOfSevenFiles() {
    var websiteId = createWebsite("probe export", "probeexport.test");
    var window = "?startAt=1755996400000&endAt=1756003600000&timezone=UTC";
    var answer =
        Settle.until(() -> http.get("/api/websites/" + websiteId + "/export" + window),
            a -> a.status() == 200, "the archive");
    assertNotNull(answer.text("zip"));
    var bytes = java.util.Base64.getDecoder().decode(answer.text("zip"));
    var names = new java.util.ArrayList<String>();
    try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bytes))) {
      java.util.zip.ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        names.add(entry.getName());
      }
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
    assertEquals(
        java.util.List.of("events.csv", "pages.csv", "referrers.csv", "browsers.csv", "os.csv",
            "devices.csv", "countries.csv"),
        names);
  }

  @Test
  void aCellThatWouldReadAsAFormulaIsPrefixed() {
    assertEquals("'=SUM(A1)", WebsiteEndpoint.cell("=SUM(A1)"));
    assertEquals("'+1", WebsiteEndpoint.cell("+1"));
    assertEquals("'-1", WebsiteEndpoint.cell("-1"));
    assertEquals("'@x", WebsiteEndpoint.cell("@x"));
    assertEquals("plain", WebsiteEndpoint.cell("plain"));
    assertEquals("\"a,b\"", WebsiteEndpoint.cell("a,b"));
  }

  @Test
  void theDashboardBoardIsTheAccountAndIsNotInTheList() {
    var write = Json.object();
    write.put("name", "mine");
    write.put("description", "");
    var parameters = Json.object();
    parameters.put("layout", "wide");
    write.set("parameters", parameters);
    var answer = http.post("/api/dashboard", write);
    assertEquals(200, answer.status());
    assertEquals(adminId, answer.text("id"));
    assertEquals("dashboard", answer.text("type"));

    var read = Settle.until(() -> http.get("/api/dashboard"), a -> a.status() == 200,
        "the dashboard");
    assertEquals(adminId, read.text("id"));

    var boards =
        Settle.until(() -> http.get("/api/boards"), a -> a.status() == 200, "the board list");
    boards.body().get("data").forEach(row ->
        assertFalse("dashboard".equals(row.get("type").asText()),
            "the dashboard board is not an ordinary board"));
  }

  @Test
  void aBoardWithoutADescriptionIsRefusedByTheStore() {
    var websiteId = createWebsite("probe board", "probeboard.test");
    var body = Json.object();
    body.put("type", "website");
    body.put("name", "no description");
    var parameters = Json.object();
    parameters.put("websiteId", websiteId);
    body.set("parameters", parameters);
    assertEquals(500, http.post("/api/boards", body).status(),
        "the request schema marks the field optional and the store requires it");

    body.put("description", "");
    var made = http.post("/api/boards", body);
    assertEquals(200, made.status());
    assertEquals("website", made.text("type"));
  }

  @Test
  void aSlugIsEightCharactersAndTakenOnce() {
    var short_ = Json.object();
    short_.put("name", "short");
    short_.put("url", "https://example.test/x");
    short_.put("slug", "abc");
    var answer = http.post("/api/links", short_);
    assertEquals(400, answer.status());
    assertEquals("Too small: expected string to have >=8 characters",
        answer.body().get("error").get("properties").get("slug").get("errors").get(0).asText());

    var first = Json.object();
    first.put("name", "probe slug");
    first.put("url", "https://example.test/x");
    first.put("slug", "probeslugone");
    assertEquals(200, http.post("/api/links", first).status());

    var duplicate = Json.object();
    duplicate.put("name", "duplicate");
    duplicate.put("url", "https://example.test/y");
    duplicate.put("slug", "probeslugone");
    assertEquals(500,
        Settle.until(() -> http.post("/api/links", duplicate), a -> a.status() == 500,
            "the clash on create").status(),
        "the create path has no check of its own; on update the same clash is a refusal");
  }

  @Test
  void theSameClashOnUpdateIsARefusalWithAMessage() {
    var one = Json.object();
    one.put("name", "probe update one");
    one.put("url", "https://example.test/x");
    one.put("slug", "probeupdone");
    var first = http.post("/api/links", one);
    assertEquals(200, first.status());

    var two = Json.object();
    two.put("name", "probe update two");
    two.put("url", "https://example.test/y");
    two.put("slug", "probeupdtwo");
    var second = http.post("/api/links", two);
    assertEquals(200, second.status());

    var move = Json.object();
    move.put("slug", "probeupdone");
    var answer =
        Settle.until(() -> http.post("/api/links/" + second.text("id"), move),
            a -> a.status() == 400, "the refusal");
    assertEquals("That slug is already taken.", answer.errorMessage());
  }

  /**
   * SPEC R148. Checked at the level a caller reads an identifier at, because the setting is about
   * what the service hands out rather than about the generator: a website, a team and a link are
   * three different creation paths and one of them could have kept a generator of its own.
   */
  @Test
  void useUuidV7ChangesTheVersionOfEveryIdentifierTheServiceHandsOut() {
    assertEquals(4, java.util.UUID.fromString(createWebsite("probe v4", "v4.example")).version());
    io.akka.umami.lib.Env.override("USE_UUIDV7", "1");
    try {
      assertEquals(7, java.util.UUID.fromString(createWebsite("probe v7", "v7.example")).version());

      var team = Json.object();
      team.put("name", "probe v7 team");
      // The team route answers the team and the owner's membership as a pair, so the identifier
      // is on the first element rather than on the answer.
      var created = http.post("/api/teams", team);
      assertEquals(200, created.status(), String.valueOf(created.body()));
      assertEquals(7,
          java.util.UUID.fromString(created.body().get(0).get("id").asText()).version());
      assertEquals(7,
          java.util.UUID.fromString(created.body().get(1).get("id").asText()).version());

      var link = Json.object();
      link.put("name", "probe v7 link");
      link.put("url", "https://example.test/v7");
      link.put("slug", "probev7slug");
      assertEquals(7, java.util.UUID.fromString(http.post("/api/links", link).text("id")).version());
    } finally {
      io.akka.umami.lib.Env.clearOverrides();
    }
  }
}
