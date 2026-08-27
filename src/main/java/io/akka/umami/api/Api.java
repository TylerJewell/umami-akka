package io.akka.umami.api;

import akka.http.javadsl.model.HttpEntity;
import akka.http.javadsl.model.HttpHeader;
import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.AbstractHttpEndpoint;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.akka.umami.analytics.Insight;
import io.akka.umami.analytics.Reports;
import io.akka.umami.analytics.Rollup;
import io.akka.umami.application.Auth;
import io.akka.umami.application.Claims;
import io.akka.umami.application.Collect;
import io.akka.umami.application.Permissions;
import io.akka.umami.application.Store;
import io.akka.umami.domain.Accounts;
import io.akka.umami.domain.Content;
import io.akka.umami.lib.Constants;
import io.akka.umami.lib.Dates;
import io.akka.umami.lib.Filters;
import io.akka.umami.lib.Json;
import io.akka.umami.lib.Responses;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * What every endpoint shares: who is calling, how a request is read, and how a page is written.
 *
 * <p>The refusal a failed filter causes is worth stating. Ten of the sixteen operators mean nothing
 * on a dimension; the original emits an empty condition and its store rejects the query, so the
 * answer is a server error. That is reproduced here rather than tidied into a refusal, because a
 * caller that sends one is told the same thing either way and a port that answered 400 would
 * disagree with the original on a real request. SPEC D3.
 */
public abstract class Api extends AbstractHttpEndpoint {

  protected final Store store;
  protected final Claims claims;
  protected final Auth auth;
  protected final Permissions permissions;
  protected final Collect collect;
  protected final Rollup rollup;
  protected final Reports reports;
  protected final Insight insight;
  protected final io.akka.umami.analytics.Pivot pivot;

  protected Api(ComponentClient client) {
    this.store = new Store(client);
    this.claims = new Claims(store);
    this.auth = new Auth(store);
    this.permissions = new Permissions(store);
    this.collect = new Collect(store);
    this.rollup = new Rollup(store);
    this.reports = new Reports(store);
    this.insight = new Insight(store);
    this.pivot = new io.akka.umami.analytics.Pivot(store, insight.loader());
  }

  // ------------------------------------------------------------------ the request

  protected Map<String, String> headers() {
    var out = new LinkedHashMap<String, String>();
    for (HttpHeader header : requestContext().allRequestHeaders()) {
      out.put(header.name().toLowerCase(Locale.ROOT), header.value());
    }
    return out;
  }

  protected String header(String name) {
    return requestContext().requestHeader(name).map(HttpHeader::value).orElse(null);
  }

  protected Map<String, String> query() {
    var out = new LinkedHashMap<String, String>();
    out.putAll(requestContext().queryParams().toMap());
    return out;
  }

  protected String queryParam(String name) {
    return requestContext().queryParams().getString(name).orElse(null);
  }

  protected static ObjectNode body(HttpEntity.Strict entity) {
    var text = entity.getData().utf8String();
    var node = Json.read(text);
    return node instanceof ObjectNode object ? object : Json.object();
  }

  protected static JsonNode bodyNode(HttpEntity.Strict entity) {
    return Json.read(entity.getData().utf8String());
  }

  /** The query string as a tree, so a GET is validated the same way a body is. */
  protected ObjectNode queryAsObject() {
    var out = Json.object();
    query().forEach(out::put);
    return out;
  }

  protected String remoteAddress() {
    return requestContext()
        .requestHeader("Remote-Address")
        .map(HttpHeader::value)
        .map(value -> value.split(":")[0])
        .orElse(null);
  }

  // ------------------------------------------------------------------ the caller

  /** Raised where a request cannot go on; the message carries the answer to write. */
  public static final class Refusal extends RuntimeException {
    private final transient HttpResponse response;

    public Refusal(HttpResponse response) {
      super("refused");
      this.response = response;
    }

    public HttpResponse response() {
      return response;
    }
  }

  protected Accounts.Auth caller() {
    var resolved = auth.check(headers());
    if (resolved == null) {
      throw new Refusal(Responses.unauthorized());
    }
    return resolved;
  }

  /**
   * The caller of an event stream, whose assertions arrive on the query string.
   *
   * <p>A browser's event source sends no headers, so a page that subscribes has nowhere to put
   * the token it puts in an ordinary request's `Authorization`. The two stream routes therefore
   * read the same three assertions from the query string as well, and nothing else does — an
   * ordinary route that accepted a token there would put it in every server log and referrer
   * header the original never puts it in.
   */
  protected Accounts.Auth streamCaller() {
    var merged = new java.util.LinkedHashMap<>(headers());
    var query = query();
    if (!merged.containsKey(Constants.AUTH_HEADER) && query.get("token") != null) {
      merged.put(Constants.AUTH_HEADER, "Bearer " + query.get("token"));
    }
    for (var name : List.of(Constants.SHARE_TOKEN_HEADER, Constants.SHARE_CONTEXT_HEADER)) {
      if (!merged.containsKey(name) && query.get(name) != null) {
        merged.put(name, query.get(name));
      }
    }
    var resolved = auth.check(merged);
    if (resolved == null) {
      throw new Refusal(Responses.unauthorized());
    }
    return resolved;
  }

  protected void require(boolean allowed) {
    if (!allowed) {
      throw new Refusal(Responses.unauthorized());
    }
  }

  protected void require(boolean allowed, String message, String code) {
    if (!allowed) {
      throw new Refusal(Responses.unauthorized(message, code));
    }
  }

  /** Runs the body, turning a refusal into its answer and anything else into an unguarded 500. */
  protected HttpResponse answer(Supplier<HttpResponse> work) {
    try {
      return work.get();
    } catch (Refusal refusal) {
      return refusal.response();
    } catch (Filters.MalformedFilter malformed) {
      return Responses.uncaught();
    } catch (RuntimeException failure) {
      return Responses.uncaught();
    }
  }

  /** Validates a field that is itself an object, reporting under that field's own key. */
  protected ObjectNode validateNested(Schema outer, JsonNode input, String field, Schema inner) {
    var result = outer.validateNested(input, field, inner);
    if (result.failed()) {
      throw new Refusal(Responses.badRequest(result.error()));
    }
    return result.value();
  }

  protected ObjectNode validate(Schema schema, JsonNode input) {
    var result = schema.validate(input);
    if (result.failed()) {
      throw new Refusal(Responses.badRequest(result.error()));
    }
    return result.value();
  }

  // ------------------------------------------------------------------ filters

  /** The date range, the dimension filters and the property filters a request carries. */
  protected Filters.Query filters(String websiteId) {
    var query = query();
    var startAt = longParam(query.get("startAt"));
    var endAt = longParam(query.get("endAt"));
    Instant start = startAt == null ? null : Instant.ofEpochMilli(startAt);
    Instant end = endAt == null ? null : Instant.ofEpochMilli(endAt);
    if (start == null && query.get("startDate") != null) {
      start = parseInstant(query.get("startDate"));
    }
    if (end == null && query.get("endDate") != null) {
      end = parseInstant(query.get("endDate"));
    }
    if (start == null) {
      start = Instant.EPOCH;
    }
    if (end == null) {
      end = Instant.now();
    }
    return filtersOver(websiteId, start, end, query);
  }

  protected Filters.Query filtersOver(String websiteId, Instant start, Instant end,
      Map<String, String> query) {
    var timezone = query.get("timezone");
    if (timezone != null && !Dates.isValidTimezone(timezone)) {
      throw new Refusal(Responses.badRequest(Schema.problem("timezone", "Invalid timezone")));
    }
    timezone = Dates.normalizeTimezone(timezone);

    // A website that was reset answers nothing before the instant it was reset at.
    if (websiteId != null) {
      var website = store.website(websiteId);
      if (website != null && website.resetAt() != null && website.resetAt().isAfter(start)) {
        start = website.resetAt();
      }
    }

    // A bucket that is not one of the five is refused; a bucket that is one of the five but too
    // coarse or too fine for the window is quietly replaced.
    var unit = query.get("unit");
    if (unit != null && !Constants.UNIT_TYPES.contains(unit)) {
      throw new Refusal(Responses.badRequest(Schema.problem("unit", "Invalid unit")));
    }
    var allowed = Dates.getAllowedUnits(start, end);
    if (unit == null || !allowed.contains(unit)) {
      unit = Dates.getMinimumUnit(start, end, false);
    }

    var clauses = new ArrayList<Filters.Clause>();
    Integer eventType = null;
    for (var entry : query.entrySet()) {
      var base = Filters.baseName(entry.getKey());
      if (!Constants.FILTER_COLUMNS.containsKey(base)) {
        continue;
      }
      if ("eventType".equals(base)) {
        eventType = intParam(entry.getValue());
        continue;
      }
      var clause = Filters.parseClause(entry.getKey(), entry.getValue());
      if (clause != null) {
        if (Constants.OPERATORS_WITHOUT_A_DIMENSION_MEANING.contains(clause.operator())) {
          throw new Filters.MalformedFilter(
              "the operator " + clause.operator() + " produces no condition on "
                  + clause.baseName());
        }
        clauses.add(clause);
      }
    }

    var eventProperties = Filters.parseUniversalPropertyFilters(query, "epf");
    var sessionProperties = Filters.parseUniversalPropertyFilters(query, "spf");

    var match = query.get("match");
    Filters.Cohort cohort = null;
    var segmentId = query.get("segment");
    if (segmentId != null) {
      var segment = store.segment(segmentId);
      if (segment != null && websiteId != null && websiteId.equals(segment.websiteId())) {
        var merged = segmentClauses(segment.parameters());
        clauses.addAll(merged);
        var segmentMatch = Json.text(segment.parameters(), "match");
        if (segmentMatch != null) {
          match = segmentMatch;
        }
      }
    }
    var cohortId = query.get("cohort");
    if (cohortId != null) {
      var segment = store.segment(cohortId);
      if (segment != null && websiteId != null && websiteId.equals(segment.websiteId())) {
        cohort = cohortOf(segment, start, end);
      }
    }

    return new Filters.Query(
        start,
        end,
        timezone,
        unit,
        List.copyOf(clauses),
        match,
        eventProperties,
        sessionProperties,
        "true".equals(query.get("excludeBounce")),
        eventType,
        cohort,
        intParam(query.getOrDefault("page", "1")),
        intParam(query.get("pageSize")),
        query.get("orderBy"),
        query.get("sortDescending") == null ? null : "true".equals(query.get("sortDescending")),
        query.get("search"),
        query.get("compare"),
        intParam(query.get("maxResults")),
        intParam(query.get("minDuration")));
  }

  private static List<Filters.Clause> segmentClauses(ObjectNode parameters) {
    var out = new ArrayList<Filters.Clause>();
    if (parameters == null) {
      return out;
    }
    var stored = parameters.get("filters");
    if (stored == null || !stored.isArray()) {
      return out;
    }
    var seen = new LinkedHashMap<String, Integer>();
    for (var filter : stored) {
      var name = Json.text(filter, "name");
      var operator = Json.text(filter, "operator");
      var value = Json.text(filter, "value");
      if (name == null || value == null) {
        continue;
      }
      int repeat = seen.merge(name, 1, Integer::sum) - 1;
      var key = repeat == 0 ? name : name + repeat;
      var clause = Filters.parseClause(key, (operator == null ? "eq" : operator) + "." + value);
      if (clause != null) {
        out.add(clause);
      }
    }
    return out;
  }

  private Filters.Cohort cohortOf(Content.Segment segment, Instant start, Instant end) {
    var parameters = segment.parameters();
    var clauses = segmentClauses(parameters);
    var range = Json.text(parameters, "dateRange");
    Instant cohortStart = start;
    Instant cohortEnd = end;
    if (range != null) {
      var parsed = Dates.parseDateRange(range, null, null, Instant.now());
      if (parsed != null) {
        cohortStart = parsed.startDate();
        cohortEnd = parsed.endDate();
      }
    }
    String actionName = null;
    var action = parameters == null ? null : parameters.get("action");
    if (action != null && !action.isNull()) {
      var type = Json.text(action, "type");
      var value = Json.text(action, "value");
      if (type != null && value != null) {
        var clause = Filters.parseClause(type, "eq." + value);
        if (clause != null) {
          clauses.add(clause);
          actionName = type;
        }
      }
    }
    return new Filters.Cohort(List.copyOf(clauses), Json.text(parameters, "match"), cohortStart,
        cohortEnd, actionName);
  }

  // ------------------------------------------------------------------ answers

  /**
   * The envelope every list answers in.
   *
   * <p>A key with no value is absent rather than null, which is what the original's own
   * serialiser produces and what its client reads. SPEC R115.
   */
  protected static <T> ObjectNode page(List<T> rows, Filters.Query query,
      java.util.function.Function<T, JsonNode> writer) {
    int pageSize = query.pageSize() == null ? Constants.DEFAULT_PAGE_SIZE : query.pageSize();
    int page = query.page() == null ? 1 : query.page();
    return page(rows, page, pageSize, query.orderBy(), query.search(), writer);
  }

  protected static <T> ObjectNode page(List<T> rows, int page, int pageSize, String orderBy,
      String search, java.util.function.Function<T, JsonNode> writer) {
    var out = Json.object();
    var data = Json.array();
    int from = Math.max(0, (page - 1) * pageSize);
    int to = pageSize <= 0 ? rows.size() : Math.min(rows.size(), from + pageSize);
    if (from < rows.size()) {
      rows.subList(from, to).forEach(row -> data.add(writer.apply(row)));
    }
    out.set("data", data);
    out.put("count", rows.size());
    out.put("page", page);
    out.put("pageSize", pageSize);
    if (orderBy != null) {
      out.put("orderBy", orderBy);
    }
    if (search != null) {
      out.put("search", search);
    }
    return out;
  }

  /**
   * The envelope an analytics list answers in, which is not the administrative one.
   *
   * <p>It reports whether the count was cut short by a ceiling on results and never echoes the
   * search term back.
   */
  protected static <T> ObjectNode analyticsPage(List<T> rows, Filters.Query query,
      java.util.function.Function<T, JsonNode> writer) {
    int pageSize = query.pageSize() == null ? Constants.DEFAULT_PAGE_SIZE : query.pageSize();
    int page = query.page() == null ? 1 : query.page();
    var out = page(rows, page, pageSize, query.orderBy(), null, writer);
    // A ceiling on results stops the counting, not the paging: the count reports the ceiling
    // and the page still holds as many rows as the page size asked for.
    boolean capped = query.maxResults() != null && rows.size() >= query.maxResults();
    if (capped) {
      out.put("count", query.maxResults());
    }
    out.put("isCapped", capped);
    return out;
  }

  /** Ordering by a field the module does not allow falls back to the module's own default. */
  protected static <T> void order(List<T> rows, String orderBy, Boolean descending,
      List<String> allowed, String fallback, java.util.function.BiFunction<T, String, Comparable<Object>> reader) {
    var field = orderBy != null && allowed.contains(orderBy) ? orderBy : fallback;
    if (field == null) {
      return;
    }
    var comparator = Comparator.comparing((T row) -> reader.apply(row, field),
        Comparator.nullsFirst(Comparator.naturalOrder()));
    rows.sort(Boolean.TRUE.equals(descending) ? comparator.reversed() : comparator);
  }

  protected static String effectiveOrderBy(String orderBy, List<String> allowed, String fallback) {
    return orderBy != null && allowed.contains(orderBy) ? orderBy : fallback;
  }

  protected static Integer intParam(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return (int) Double.parseDouble(value.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  protected static Long longParam(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return (long) Double.parseDouble(value.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  protected static Instant parseInstant(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(value);
    } catch (RuntimeException e) {
      try {
        return Instant.ofEpochMilli(Long.parseLong(value));
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
  }

  protected static byte[] utf8(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  protected static String textOrNull(JsonNode node, String field) {
    return Json.text(node, field);
  }
}
