package io.akka.umami.cli;

import com.fasterxml.jackson.databind.JsonNode;
import io.akka.umami.lib.Constants;
import io.akka.umami.lib.Env;
import io.akka.umami.lib.Json;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * The commands umami ships beside its server.
 *
 * <p>One of them, the password change, is named in the original's own command list and has no file
 * behind it — running it there fails with the module missing. It is implemented here, because a
 * deployment whose only administrator has lost their password otherwise has no way back in.
 *
 * <p>Run one with
 * {@code mvn compile exec:java -Dexec.mainClass=io.akka.umami.cli.Cli -Dexec.args="<command>"}.
 */
public final class Cli {

  private static final String DEFAULT_BASE = "http://127.0.0.1:9157";

  private final PrintStream out;
  private final PrintStream err;
  private final HttpClient client;
  private final String base;

  public Cli(PrintStream out, PrintStream err, String base) {
    this.out = out;
    this.err = err;
    this.base = base;
    this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  }

  public static void main(String[] args) {
    var base = Env.get("UMAMI_URL", DEFAULT_BASE);
    System.exit(new Cli(System.out, System.err, base).run(args));
  }

  /** Answers the exit status the command would leave. */
  public int run(String[] args) {
    if (args.length == 0 || args[0].equals("--help") || args[0].equals("-h")) {
      usage();
      return args.length == 0 ? 1 : 0;
    }
    try {
      return switch (args[0]) {
        case "check-env" -> checkEnv();
        case "check-db" -> checkStore();
        case "change-password" -> changePassword(rest(args));
        case "seed-data" -> seed(rest(args));
        case "update-tracker" -> updateTracker(rest(args));
        case "version" -> {
          out.println(Constants.CURRENT_VERSION);
          yield 0;
        }
        default -> {
          err.println("unknown command: " + args[0]);
          usage();
          yield 1;
        }
      };
    } catch (RuntimeException failure) {
      err.println(failure.getMessage());
      return 1;
    }
  }

  private void usage() {
    out.println("umami commands:");
    out.println("  check-env                       report every setting and whether it is set");
    out.println("  check-db                        report whether the store answers");
    out.println("  change-password <name> <word>   set an account's password");
    out.println("  seed-data [--days N] [--clear] [--verbose]");
    out.println("                                  write two demonstration sites of traffic");
    out.println("  update-tracker <file>           rewrite the built tracker's endpoint");
    out.println("  version                         print the version");
    out.println();
    out.println("The service is reached at " + base + "; set UMAMI_URL to change that.");
  }

  private static String[] rest(String[] args) {
    var out = new String[args.length - 1];
    System.arraycopy(args, 1, out, 0, out.length);
    return out;
  }

  // ------------------------------------------------------------------ settings

  /** Every setting the service reads, and whether this environment sets it. */
  private int checkEnv() {
    var required = List.of("APP_SECRET");
    var optional =
        List.of("TWO_FACTOR_ENCRYPTION_KEY", "SALT_ROTATION", "USE_UUIDV7", "CLOUD_MODE",
            "CLOUD_URL", "DISABLE_BOT_CHECK", "DISABLE_TELEMETRY", "DISABLE_UPDATES",
            "DISABLE_LOGIN", "DISABLE_UI", "PRIVATE_MODE", "ENABLE_TEST_CONSOLE", "IGNORE_IP",
            "CLIENT_IP_HEADER", "SKIP_LOCATION_HEADERS", "GEOLITE_DB_PATH",
            "REMOVE_TRAILING_SLASH", "COLLECT_API_ENDPOINT", "COLLECT_API_HOST",
            "TRACKER_SCRIPT_NAME", "TRACKER_SCRIPT_URL", "API_URL", "BASE_PATH",
            "ALLOWED_FRAME_URLS", "CORS_MAX_AGE", "FORCE_SSL", "FAVICON_URL", "LINKS_URL",
            "PIXELS_URL", "DEFAULT_LOCALE", "DEFAULT_CURRENCY", "UMAMI_SELF_TRACK",
            "UMAMI_SELF_RECORD", "DEFAULT_ADMIN_PASSWORD");
    int missing = 0;
    for (var name : required) {
      if (Env.isSet(name)) {
        out.println("  set      " + name);
      } else {
        out.println("  MISSING  " + name);
        missing++;
      }
    }
    for (var name : optional) {
      out.println((Env.isSet(name) ? "  set      " : "  unset    ") + name);
    }
    if (missing > 0) {
      err.println(missing + " required setting(s) missing.");
      // Without a secret the deployment falls back to a fixed one, which every derived
      // identifier then depends on; that is worth refusing rather than warning about.
      return 1;
    }
    out.println("Every required setting is present.");
    return 0;
  }

  private int checkStore() {
    var answer = get("/api/heartbeat");
    if (answer == null || !Json.flag(answer, "ok")) {
      err.println("The service did not answer at " + base + ".");
      return 1;
    }
    out.println("The service answers at " + base + ".");
    return 0;
  }

  // ------------------------------------------------------------------ accounts

  private int changePassword(String[] args) {
    if (args.length < 2) {
      err.println("usage: change-password <username> <new-password>");
      return 1;
    }
    var username = args[0];
    var password = args[1];
    if (password.length() < 8) {
      err.println("A password is at least eight characters.");
      return 1;
    }
    var token = signIn();
    if (token == null) {
      return 1;
    }
    var users = get("/api/admin/users?pageSize=" + Constants.MAX_PAGING_RESULTS, token);
    if (users == null) {
      err.println("Could not read the account list.");
      return 1;
    }
    String userId = null;
    for (var row : users.get("data")) {
      if (username.toLowerCase(Locale.ROOT).equals(Json.text(row, "username"))) {
        userId = Json.text(row, "id");
      }
    }
    if (userId == null) {
      err.println("No account called " + username + ".");
      return 1;
    }
    var body = Json.object();
    body.put("password", password);
    var answer = post("/api/users/" + userId, body, token);
    if (answer == null) {
      err.println("The password was not changed.");
      return 1;
    }
    out.println("Changed the password for " + username + ".");
    return 0;
  }

  private String signIn() {
    var body = Json.object();
    body.put("username", Env.get("UMAMI_USER", "admin"));
    body.put("password", Env.get("UMAMI_PASSWORD", "umami"));
    var answer = post("/api/auth/login", body, null);
    if (answer == null || Json.text(answer, "token") == null) {
      err.println("Could not sign in as " + Env.get("UMAMI_USER", "admin")
          + "; set UMAMI_USER and UMAMI_PASSWORD.");
      return null;
    }
    return Json.text(answer, "token");
  }

  // ------------------------------------------------------------------ demonstration traffic

  /** Two demonstration sites, so a fresh deployment has something on its screens. */
  private int seed(String[] args) {
    int days = 30;
    boolean clear = false;
    boolean verbose = false;
    for (int i = 0; i < args.length; i++) {
      var argument = args[i];
      if (argument.equals("--clear")) {
        clear = true;
      } else if (argument.equals("--verbose") || argument.equals("-v")) {
        verbose = true;
      } else if (argument.equals("--days") && i + 1 < args.length) {
        days = Integer.parseInt(args[++i]);
      } else if (argument.startsWith("--days=")) {
        days = Integer.parseInt(argument.substring("--days=".length()));
      } else if (argument.equals("--help") || argument.equals("-h")) {
        out.println("usage: seed-data [--days N] [--clear] [--verbose]");
        return 0;
      } else {
        err.println("unknown argument: " + argument);
        return 1;
      }
    }
    if (days <= 0) {
      err.println("--days takes a positive number.");
      return 1;
    }
    var token = signIn();
    if (token == null) {
      return 1;
    }

    var sites = List.of(new Site("Demo Blog", "demo-blog.test", 90, false),
        new Site("Demo SaaS", "demo-saas.test", 500, true));
    for (var site : sites) {
      var websiteId = websiteFor(site, token, clear);
      if (websiteId == null) {
        return 1;
      }
      long written = write(websiteId, site, days, verbose);
      out.println("Wrote " + written + " events to " + site.name() + ".");
    }
    return 0;
  }

  private record Site(String name, String domain, int perDay, boolean sells) {}

  private String websiteFor(Site site, String token, boolean clear) {
    var existing = get("/api/websites?pageSize=" + Constants.MAX_PAGING_RESULTS, token);
    if (existing != null) {
      for (var row : existing.get("data")) {
        if (site.name().equals(Json.text(row, "name"))) {
          var id = Json.text(row, "id");
          if (clear) {
            post("/api/websites/" + id + "/reset", Json.object(), token);
          }
          return id;
        }
      }
    }
    var body = Json.object();
    body.put("name", site.name());
    body.put("domain", site.domain());
    var created = post("/api/websites", body, token);
    return created == null ? null : Json.text(created, "id");
  }

  private static final List<String> PATHS =
      List.of("/", "/about", "/pricing", "/blog", "/blog/one", "/blog/two", "/docs", "/contact");
  private static final List<String> REFERRERS =
      List.of("", "https://www.google.com/search?q=x", "https://news.ycombinator.com/",
          "https://twitter.com/", "https://duckduckgo.com/", "https://example.org/");
  private static final List<String> AGENTS =
      List.of(
          "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
              + "Chrome/120.0.0.0 Safari/537.36",
          "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) "
              + "Chrome/120.0.0.0 Safari/537.36",
          "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 "
              + "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1",
          "Mozilla/5.0 (X11; Linux x86_64; rv:121.0) Gecko/20100101 Firefox/121.0");

  private long write(String websiteId, Site site, int days, boolean verbose) {
    // Seeded from the site's own name, so two runs of the same command write the same traffic.
    var random = new Random(site.name().hashCode());
    long now = System.currentTimeMillis() / 1000;
    long written = 0;
    var batch = Json.array();
    for (int day = days; day >= 0; day--) {
      int visits = Math.max(1, site.perDay() / (site.sells() ? 1 : 30));
      for (int visit = 0; visit < visits; visit++) {
        long at = now - (long) day * 86400 + random.nextInt(86400);
        var address = "203.0." + random.nextInt(200) + "." + (1 + random.nextInt(200));
        var agent = AGENTS.get(random.nextInt(AGENTS.size()));
        var referrer = REFERRERS.get(random.nextInt(REFERRERS.size()));
        int pages = 1 + random.nextInt(4);
        for (int page = 0; page < pages; page++) {
          batch.add(event(websiteId, PATHS.get(random.nextInt(PATHS.size())), null, at + page * 30L,
              agent, address, page == 0 ? referrer : null, site.domain(), null));
          written++;
        }
        if (site.sells() && random.nextInt(10) == 0) {
          var data = Json.object();
          data.put("revenue", 10 + random.nextInt(190));
          data.put("currency", "USD");
          batch.add(event(websiteId, "/thanks", "purchase", at + pages * 30L, agent, address, null,
              site.domain(), data));
          written++;
        }
        if (batch.size() >= 400) {
          send(batch, verbose);
          batch = Json.array();
        }
      }
    }
    if (batch.size() > 0) {
      send(batch, verbose);
    }
    return written;
  }

  private JsonNode event(String websiteId, String path, String name, long at, String agent,
      String address, String referrer, String domain, JsonNode data) {
    var payload = Json.object();
    payload.put("website", websiteId);
    payload.put("url", path);
    payload.put("hostname", domain);
    payload.put("userAgent", agent);
    payload.put("ip", address);
    payload.put("timestamp", at);
    payload.put("screen", "1512x982");
    payload.put("language", "en-GB");
    if (name != null) {
      payload.put("name", name);
    }
    if (referrer != null && !referrer.isEmpty()) {
      payload.put("referrer", referrer);
    }
    if (data != null) {
      payload.set("data", data);
    }
    var element = Json.object();
    element.put("type", "event");
    element.set("payload", payload);
    return element;
  }

  private void send(com.fasterxml.jackson.databind.node.ArrayNode batch, boolean verbose) {
    var answer = post("/api/batch", batch, null);
    if (verbose) {
      out.println("  sent " + batch.size() + " -> "
          + (answer == null ? "no answer" : Json.write(answer)));
    }
  }

  // ------------------------------------------------------------------ the tracker

  /** Rewrites a built tracker so it posts somewhere other than where it was built to post. */
  private int updateTracker(String[] args) {
    if (args.length < 1) {
      err.println("usage: update-tracker <path-to-script.js>");
      return 1;
    }
    var endpoint = Env.get("COLLECT_API_ENDPOINT");
    if (endpoint == null) {
      out.println("COLLECT_API_ENDPOINT is not set; the tracker is left alone.");
      return 0;
    }
    var path = Path.of(args[0]);
    try {
      var script = Files.readString(path, StandardCharsets.UTF_8);
      Files.writeString(path, script.replace("/api/send", endpoint), StandardCharsets.UTF_8);
      out.println("Updated tracker endpoint: " + endpoint + ".");
      return 0;
    } catch (java.io.IOException e) {
      err.println("Could not rewrite " + path + ": " + e.getMessage());
      return 1;
    }
  }

  // ------------------------------------------------------------------ talking to the service

  private JsonNode get(String path) {
    return get(path, null);
  }

  private JsonNode get(String path, String token) {
    return send("GET", path, null, token);
  }

  private JsonNode post(String path, JsonNode body, String token) {
    return send("POST", path, body, token);
  }

  private JsonNode send(String method, String path, JsonNode body, String token) {
    try {
      var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofMinutes(2));
      if (body == null) {
        builder.method(method, HttpRequest.BodyPublishers.noBody());
      } else {
        builder.header("Content-Type", "application/json");
        builder.method(method,
            HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8));
      }
      if (token != null) {
        builder.header("Authorization", "Bearer " + token);
      }
      var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 400) {
        return null;
      }
      return Json.read(response.body());
    } catch (Exception e) {
      return null;
    }
  }

  /** The arguments a command was given, for a caller that wants to check them. */
  public static List<String> arguments(String[] args) {
    return new ArrayList<>(List.of(args));
  }
}
