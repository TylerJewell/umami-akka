package io.akka.umami.lib;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The tables the whole system is decided by. */
public final class Constants {

  private Constants() {}

  public static final String CURRENT_VERSION = "3.3.1";

  // --- event types ---------------------------------------------------------------
  public static final int PAGE_VIEW = 1;
  public static final int CUSTOM_EVENT = 2;
  public static final int LINK_EVENT = 3;
  public static final int PIXEL_EVENT = 4;
  public static final int PERFORMANCE_EVENT = 5;

  // --- entity types, which are also share types ------------------------------------
  public static final int ENTITY_WEBSITE = 1;
  public static final int ENTITY_LINK = 2;
  public static final int ENTITY_PIXEL = 3;
  public static final int ENTITY_BOARD = 4;

  // --- heatmap event types ----------------------------------------------------------
  public static final int HEATMAP_CLICK = 1;
  public static final int HEATMAP_SCROLL = 2;

  // --- data types --------------------------------------------------------------------
  public static final int DATA_STRING = 1;
  public static final int DATA_NUMBER = 2;
  public static final int DATA_BOOLEAN = 3;
  public static final int DATA_DATE = 4;
  public static final int DATA_ARRAY = 5;

  // --- roles ---------------------------------------------------------------------------
  public static final String ROLE_ADMIN = "admin";
  public static final String ROLE_USER = "user";
  public static final String ROLE_VIEW_ONLY = "view-only";
  public static final String ROLE_TEAM_OWNER = "team-owner";
  public static final String ROLE_TEAM_MANAGER = "team-manager";
  public static final String ROLE_TEAM_MEMBER = "team-member";
  public static final String ROLE_TEAM_VIEW_ONLY = "team-view-only";

  public static final Map<String, Integer> TEAM_ROLE_RANK =
      Map.of(ROLE_TEAM_VIEW_ONLY, 0, ROLE_TEAM_MEMBER, 1, ROLE_TEAM_MANAGER, 2, ROLE_TEAM_OWNER, 3);

  // --- permissions ------------------------------------------------------------------------
  public static final String PERM_ALL = "all";
  public static final String PERM_WEBSITE_CREATE = "website:create";
  public static final String PERM_WEBSITE_UPDATE = "website:update";
  public static final String PERM_WEBSITE_DELETE = "website:delete";
  public static final String PERM_WEBSITE_TRANSFER_TO_TEAM = "website:transfer-to-team";
  public static final String PERM_WEBSITE_TRANSFER_TO_USER = "website:transfer-to-user";
  public static final String PERM_TEAM_CREATE = "team:create";
  public static final String PERM_TEAM_UPDATE = "team:update";
  public static final String PERM_TEAM_DELETE = "team:delete";

  /**
   * Holding {@code all} does not satisfy a check for a named permission; that is a literal
   * membership test in the original and administrator access works because every predicate tests
   * the role before it reaches here.
   */
  public static final Map<String, List<String>> ROLE_PERMISSIONS =
      Map.of(
          ROLE_ADMIN, List.of(PERM_ALL),
          ROLE_USER,
              List.of(
                  PERM_WEBSITE_CREATE, PERM_WEBSITE_UPDATE, PERM_WEBSITE_DELETE, PERM_TEAM_CREATE),
          ROLE_VIEW_ONLY, List.of(),
          ROLE_TEAM_OWNER,
              List.of(
                  PERM_TEAM_UPDATE,
                  PERM_TEAM_DELETE,
                  PERM_WEBSITE_CREATE,
                  PERM_WEBSITE_UPDATE,
                  PERM_WEBSITE_DELETE,
                  PERM_WEBSITE_TRANSFER_TO_TEAM,
                  PERM_WEBSITE_TRANSFER_TO_USER),
          ROLE_TEAM_MANAGER,
              List.of(
                  PERM_TEAM_UPDATE,
                  PERM_WEBSITE_CREATE,
                  PERM_WEBSITE_UPDATE,
                  PERM_WEBSITE_DELETE,
                  PERM_WEBSITE_TRANSFER_TO_TEAM),
          ROLE_TEAM_MEMBER,
              List.of(PERM_WEBSITE_CREATE, PERM_WEBSITE_UPDATE, PERM_WEBSITE_DELETE),
          ROLE_TEAM_VIEW_ONLY, List.of());

  public static final Set<String> USER_ROLES = Set.of(ROLE_ADMIN, ROLE_USER, ROLE_VIEW_ONLY);

  /** {@code team-owner} is created with the team and can never be assigned. */
  public static final Set<String> TEAM_ROLES =
      Set.of(ROLE_TEAM_MEMBER, ROLE_TEAM_VIEW_ONLY, ROLE_TEAM_MANAGER);

  // --- share sections ------------------------------------------------------------------------
  public static final List<String> SHARE_SECTIONS =
      List.of(
          "overview",
          "events",
          "sessions",
          "realtime",
          "performance",
          "compare",
          "breakdown",
          "goals",
          "funnels",
          "journeys",
          "retention",
          "utm",
          "revenue",
          "attribution");

  // --- dimensions ------------------------------------------------------------------------------
  public static final List<String> SESSION_COLUMNS =
      List.of("browser", "os", "device", "screen", "language", "country", "city", "region",
          "distinctId");

  public static final List<String> EVENT_COLUMNS =
      List.of("path", "fullPath", "entry", "exit", "referrer", "domain", "title", "query", "event",
          "tag", "hostname", "utmSource", "utmMedium", "utmCampaign", "utmContent", "utmTerm");

  /** The dimension name to storage column map. {@code fullPath} has no column of its own. */
  public static final Map<String, String> FILTER_COLUMNS = filterColumns();

  private static Map<String, String> filterColumns() {
    var m = new LinkedHashMap<String, String>();
    m.put("path", "url_path");
    m.put("entry", "url_path");
    m.put("exit", "url_path");
    m.put("referrer", "referrer_domain");
    m.put("domain", "referrer_domain");
    m.put("hostname", "hostname");
    m.put("distinctId", "distinct_id");
    m.put("title", "page_title");
    m.put("query", "url_query");
    m.put("os", "os");
    m.put("browser", "browser");
    m.put("device", "device");
    m.put("country", "country");
    m.put("region", "region");
    m.put("city", "city");
    m.put("language", "language");
    m.put("event", "event_name");
    m.put("tag", "tag");
    m.put("eventType", "event_type");
    m.put("utmSource", "utm_source");
    m.put("utmMedium", "utm_medium");
    m.put("utmCampaign", "utm_campaign");
    m.put("utmContent", "utm_content");
    m.put("utmTerm", "utm_term");
    return Map.copyOf(m);
  }

  /** The twenty names a filter-value lookup accepts, in the order the original declares them. */
  public static final List<String> FIELD_NAMES =
      List.of("path", "referrer", "title", "query", "os", "browser", "device", "country", "region",
          "city", "tag", "hostname", "distinctId", "language", "event", "utmSource", "utmMedium",
          "utmCampaign", "utmContent", "utmTerm");

  // --- operators ---------------------------------------------------------------------------------
  public static final String OP_EQUALS = "eq";
  public static final String OP_NOT_EQUALS = "neq";
  public static final String OP_SET = "s";
  public static final String OP_NOT_SET = "ns";
  public static final String OP_CONTAINS = "c";
  public static final String OP_DOES_NOT_CONTAIN = "dnc";
  public static final String OP_MATCHES = "re";
  public static final String OP_DOES_NOT_MATCH = "nre";
  public static final String OP_TRUE = "t";
  public static final String OP_FALSE = "f";
  public static final String OP_GREATER = "gt";
  public static final String OP_LESS = "lt";
  public static final String OP_GREATER_OR_EQUAL = "gte";
  public static final String OP_LESS_OR_EQUAL = "lte";
  public static final String OP_BEFORE = "bf";
  public static final String OP_AFTER = "af";

  public static final List<String> OPERATORS =
      List.of(OP_EQUALS, OP_NOT_EQUALS, OP_SET, OP_NOT_SET, OP_CONTAINS, OP_DOES_NOT_CONTAIN,
          OP_MATCHES, OP_DOES_NOT_MATCH, OP_TRUE, OP_FALSE, OP_GREATER, OP_LESS,
          OP_GREATER_OR_EQUAL, OP_LESS_OR_EQUAL, OP_BEFORE, OP_AFTER);

  /**
   * The ten operators that reach a dimension and produce nothing. Applied to one, the original's
   * query is left malformed and the answer is a server error; SPEC R55.
   */
  public static final Set<String> OPERATORS_WITHOUT_A_DIMENSION_MEANING =
      Set.of(OP_SET, OP_NOT_SET, OP_TRUE, OP_FALSE, OP_GREATER, OP_LESS, OP_GREATER_OR_EQUAL,
          OP_LESS_OR_EQUAL, OP_BEFORE, OP_AFTER);

  // --- report types --------------------------------------------------------------------------------
  public static final List<String> REPORT_TYPES =
      List.of("attribution", "breakdown", "funnel", "goal", "heatmap", "journey", "performance",
          "retention", "revenue", "utm");

  public static final List<String> SEGMENT_TYPES = List.of("segment", "cohort");

  /**
   * The dimensions a caller may name where the request schema restricts the field to a set,
   * rather than taking any string and checking it against the columns afterwards.
   */
  public static final List<String> FIELD_TYPES =
      List.of("path", "referrer", "title", "query", "os", "browser", "device", "country",
          "region", "city", "tag", "hostname", "distinctId", "language", "event", "utmSource",
          "utmMedium", "utmCampaign", "utmContent", "utmTerm");

  public static final List<String> UNIT_TYPES = List.of("year", "month", "hour", "day", "minute");

  public static final List<String> BOARD_TYPES =
      List.of("dashboard", "mixed", "website", "pixel", "link");

  // --- channel classification --------------------------------------------------------------------
  public static final List<String> PAID_AD_PARAMS =
      List.of("ad_id=", "aid=", "dclid=", "epik=", "gclid=", "li_fat_id=", "msclkid=",
          "ob_click_id=", "pc_id=", "rdt_cid=", "scid=", "ttclid=", "twclid=", "utm_medium=cpc",
          "utm_medium=paid", "utm_medium=paid_social", "utm_source=google");

  public static final List<String> LLM_DOMAINS =
      List.of("chatgpt.com", "claude.ai", "copilot.microsoft.com", "gemini.google.com", "meta.ai",
          "perplexity.ai");

  public static final List<String> SEARCH_DOMAINS =
      List.of("baidu.com", "bing.com", "duckduckgo.com", "ecosia.org", "google.", "msn.com",
          "search.brave.com", "yandex.");

  public static final List<String> SOCIAL_DOMAINS =
      List.of("bsky.app", "facebook.com", "fb.com", "ig.com", "instagram.com", "linkedin.",
          "news.ycombinator.com", "pinterest.", "reddit.", "snapchat.", "t.co", "threads.net",
          "tiktok.", "twitter.com", "x.com");

  public static final List<String> SHOPPING_DOMAINS =
      List.of("alibaba.com", "aliexpress.com", "amazon.", "bestbuy.com", "ebay.com", "etsy.com",
          "newegg.com", "target.com", "walmart.com");

  public static final List<String> EMAIL_DOMAINS =
      List.of("gmail.", "hotmail.", "mail.yahoo.", "outlook.", "proton.me", "protonmail.");

  public static final List<String> VIDEO_DOMAINS = List.of("twitch.", "youtube.");

  /** Referrer host fragments folded into one canonical site by the {@code domain} dimension. */
  public static final List<Map.Entry<List<String>, String>> GROUPED_DOMAINS = groupedDomains();

  private static List<Map.Entry<List<String>, String>> groupedDomains() {
    return List.of(
        Map.entry(List.of("baidu."), "baidu.com"),
        Map.entry(List.of("bing."), "bing.com"),
        Map.entry(List.of("brave."), "brave.com"),
        Map.entry(List.of("chatgpt."), "chatgpt.com"),
        Map.entry(List.of("duckduckgo."), "duckduckgo.com"),
        Map.entry(List.of("facebook."), "facebook.com"),
        Map.entry(List.of("github."), "github.com"),
        Map.entry(List.of("google."), "google.com"),
        Map.entry(List.of("news.ycombinator.com"), "news.ycombinator.com"),
        Map.entry(List.of("instagram.", "ig.com"), "instagram.com"),
        Map.entry(List.of("linkedin."), "linkedin.com"),
        Map.entry(List.of("pinterest."), "pinterest.com"),
        Map.entry(List.of("reddit."), "reddit.com"),
        Map.entry(List.of("snapchat."), "snapchat.com"),
        Map.entry(List.of("twitter.", "t.co", "x.com"), "twitter.com"),
        Map.entry(List.of("yahoo."), "yahoo.com"),
        Map.entry(List.of("yandex."), "yandex.ru"));
  }

  // --- field lengths -------------------------------------------------------------------------------
  public static final Map<String, Integer> FIELD_LENGTH = fieldLength();

  private static Map<String, Integer> fieldLength() {
    var m = new LinkedHashMap<String, Integer>();
    m.put("urlPath", 500);
    m.put("urlQuery", 500);
    m.put("referrerPath", 500);
    m.put("referrerQuery", 500);
    m.put("referrerDomain", 500);
    m.put("pageTitle", 500);
    m.put("eventName", 50);
    m.put("tag", 50);
    m.put("hostname", 100);
    m.put("browser", 20);
    m.put("os", 20);
    m.put("device", 20);
    m.put("screen", 11);
    m.put("language", 35);
    m.put("country", 2);
    m.put("region", 20);
    m.put("city", 50);
    m.put("distinctId", 50);
    m.put("dataKey", 500);
    m.put("stringValue", 500);
    m.put("currency", 10);
    m.put("utm", 255);
    m.put("clickId", 255);
    return Map.copyOf(m);
  }

  // --- other settings --------------------------------------------------------------------------------
  public static final int DEFAULT_PAGE_SIZE = 20;
  public static final int MAX_PAGING_RESULTS = 10000;
  public static final int REALTIME_RANGE_MINUTES = 30;
  public static final int ACTIVE_VISITOR_MINUTES = 5;
  public static final int VISIT_TIMEOUT_SECONDS = 1800;
  public static final int BACKUP_CODE_COUNT = 10;
  public static final int TWO_FACTOR_MAX_ATTEMPTS = 5;
  public static final int TWO_FACTOR_LOCKOUT_MINUTES = 15;
  public static final long OTP_REMEMBERED_MILLIS = 90_000L;
  public static final int CHART_BUCKET_HOURS = 12;
  public static final int HEATMAP_POINT_LIMIT = 5000;
  public static final int HEATMAP_PAGE_LIMIT = 100;
  public static final int SCROLL_BUCKET_SIZE = 10;
  public static final int METRIC_LIMIT = 500;
  public static final String DEFAULT_RESET_DATE = "2000-01-01T00:00:00Z";
  public static final String DEFAULT_LOCALE = "en-US";
  public static final String DEFAULT_CURRENCY = "USD";
  public static final String DEFAULT_DATE_RANGE = "24hour";
  public static final String DEFAULT_FAVICON_URL =
      "https://icons.duckduckgo.com/ip3/{{domain}}.ico";

  public static final String AUTH_HEADER = "authorization";
  public static final String SHARE_TOKEN_HEADER = "x-umami-share-token";
  public static final String SHARE_CONTEXT_HEADER = "x-umami-share-context";
  public static final String CACHE_HEADER = "x-umami-cache";
  public static final String SHARE_TOKEN_TYPE = "share";
  public static final String CACHE_TOKEN_TYPE = "cache";
  public static final String PARTIAL_AUTH_TOKEN_TYPE = "partial-auth";

  /** What a website's domain has to look like. */
  public static final String DOMAIN_PATTERN =
      "^(localhost(:[1-9]\\d{0,4})?|((?=[a-z0-9-_]{1,63}\\.)(xn--)?[a-z0-9-_]+(-[a-z0-9-_]+)*\\.)+"
          + "(xn--)?[a-z0-9-_]{2,63})$";

  /** The thresholds the interface colours a vital by: good below the first, poor above the second. */
  public static final Map<String, double[]> WEB_VITALS_THRESHOLDS =
      Map.of(
          "lcp", new double[] {2500, 4000},
          "inp", new double[] {200, 500},
          "cls", new double[] {0.1, 0.25},
          "fcp", new double[] {1800, 3000},
          "ttfb", new double[] {800, 1800});
}
