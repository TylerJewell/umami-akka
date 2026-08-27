package io.akka.umami.api;

import akka.http.javadsl.model.HttpEntity;
import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Delete;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.akka.umami.application.Store;
import io.akka.umami.domain.Content;
import io.akka.umami.lib.Constants;
import io.akka.umami.lib.Crypto;
import io.akka.umami.lib.Dates;
import io.akka.umami.lib.Filters;
import io.akka.umami.lib.Json;
import io.akka.umami.lib.Responses;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Saved reports, and the ten questions a report answers. */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint
public class ReportEndpoint extends Api {

  public ReportEndpoint(ComponentClient client, akka.stream.Materializer materializer) {
    super(client, materializer);
  }

  // ------------------------------------------------------------------ saved reports

  @Get("/api/reports")
  public HttpResponse list() {
    return answer(() -> {
      var caller = caller();
      var websiteId = queryParam("websiteId");
      if (websiteId == null) {
        return Responses.badRequest();
      }
      var type = queryParam("type");
      var section = type == null ? null : io.akka.umami.application.Permissions.reportSection(type);
      if (section != null) {
        require(permissions.canViewWebsiteSection(caller, websiteId, List.of(section)));
      } else {
        require(permissions.canViewAuthenticatedWebsite(caller, websiteId));
      }
      var query = filters(null);
      var reports = new ArrayList<>(store.byParent(Store.REPORT, websiteId, Content.Report.class));
      if (type != null) {
        reports.removeIf(report -> !type.equals(report.type()));
      }
      reports.removeIf(report -> {
        var website = store.website(report.websiteId());
        return website == null || website.isDeleted();
      });
      reports.sort(java.util.Comparator.comparing(report ->
          AccountEndpoint.nullToEmpty(report.name())));
      return Responses.json(page(reports, query, Writers::report));
    });
  }

  @Post("/api/reports")
  public HttpResponse create(HttpEntity.Strict requestBody) {
    return answer(() -> {
      var caller = caller();
      var request = validate(reportSchema(), body(requestBody));
      var websiteId = request.get("websiteId").asText();
      require(permissions.canUpdateWebsite(caller, websiteId));
      var now = Instant.now();
      var report =
          new Content.Report(Crypto.uuid().toString(), caller.userId(), websiteId,
              request.get("type").asText(), request.get("name").asText(),
              request.has("description") ? request.get("description").asText() : "",
              request.get("parameters") instanceof ObjectNode parameters ? parameters
                  : Json.object(),
              now, now);
      store.put(Store.REPORT, report.id(), report);
      return Responses.json(Writers.report(report));
    });
  }

  private static Schema reportSchema() {
    var schema = Schema.object();
    schema.uuid("websiteId").required();
    schema.string("type").options(Constants.REPORT_TYPES).required();
    schema.string("name").max(200).required();
    schema.string("description").max(500);
    schema.objectField("parameters").required();
    return schema;
  }

  @Get("/api/reports/{reportId}")
  public HttpResponse read(String reportId) {
    return answer(() -> {
      var caller = caller();
      var report = store.report(reportId);
      if (report == null) {
        return Responses.notFound();
      }
      require(permissions.canViewReport(caller, report));
      return Responses.json(Writers.report(report));
    });
  }

  @Post("/api/reports/{reportId}")
  public HttpResponse update(String reportId, HttpEntity.Strict requestBody) {
    return answer(() -> {
      var caller = caller();
      var request = validate(reportSchema(), body(requestBody));
      var report = store.report(reportId);
      if (report == null) {
        return Responses.notFound();
      }
      require(permissions.canUpdateReport(caller, report));
      // The website named in the body is written through without a second check, which is
      // what the original does.
      var updated =
          new Content.Report(report.id(), report.userId(), request.get("websiteId").asText(),
              request.get("type").asText(), request.get("name").asText(),
              request.has("description") ? request.get("description").asText()
                  : report.description(),
              request.get("parameters") instanceof ObjectNode parameters ? parameters
                  : report.parameters(),
              report.createdAt(), Instant.now());
      store.put(Store.REPORT, reportId, updated);
      return Responses.json(Writers.report(updated));
    });
  }

  @Delete("/api/reports/{reportId}")
  public HttpResponse delete(String reportId) {
    return answer(() -> {
      var caller = caller();
      var report = store.report(reportId);
      if (report == null) {
        return Responses.notFound();
      }
      require(permissions.canDeleteReport(caller, report));
      store.remove(Store.REPORT, reportId);
      return Responses.ok();
    });
  }

  @Get("/api/websites/{websiteId}/reports")
  public HttpResponse websiteReports(String websiteId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canViewAuthenticatedWebsite(caller, websiteId));
      var query = filters(null);
      var type = queryParam("type");
      var reports = new ArrayList<>(store.byParent(Store.REPORT, websiteId, Content.Report.class));
      if (type != null) {
        reports.removeIf(report -> !type.equals(report.type()));
      }
      return Responses.json(page(reports, query, Writers::report));
    });
  }

  // ------------------------------------------------------------------ running one

  /** What every execution route shares: the body's shape, the section, and the window. */
  private record Execution(String websiteId, Filters.Query query, JsonNode parameters) {}

  private Execution begin(HttpEntity.Strict requestBody, String type, String section,
      boolean authenticatedOnly) {
    var caller = caller();
    var schema = Schema.object();
    schema.uuid("websiteId").required();
    schema.objectField("filters").required();
    schema.string("type").options(List.of(type)).required();
    schema.objectField("parameters").required();
    var request = validate(schema, body(requestBody));
    var websiteId = request.get("websiteId").asText();
    if (authenticatedOnly) {
      require(permissions.canViewAuthenticatedWebsite(caller, websiteId));
    } else {
      require(permissions.canViewWebsiteSection(caller, websiteId, List.of(section)));
    }
    var parameters = request.get("parameters");
    var start = parseInstant(text(parameters, "startDate"));
    var end = parseInstant(text(parameters, "endDate"));
    if (start == null || end == null) {
      throw new Refusal(Responses.badRequest());
    }
    var filterMap = new java.util.LinkedHashMap<String, String>();
    var given = request.get("filters");
    if (given != null && given.isObject()) {
      given.fieldNames().forEachRemaining(name -> {
        var value = given.get(name);
        if (value != null && !value.isNull()) {
          filterMap.put(name, value.asText());
        }
      });
    }
    var timezone = text(parameters, "timezone");
    if (timezone != null) {
      filterMap.put("timezone", timezone);
    }
    var unit = text(parameters, "unit");
    if (unit != null) {
      filterMap.put("unit", unit);
    }
    var query = filtersOver(websiteId, start, end, filterMap);
    return new Execution(websiteId, query, parameters);
  }

  private static String text(JsonNode node, String field) {
    return Json.text(node, field);
  }

  @Post("/api/reports/funnel")
  public HttpResponse funnel(HttpEntity.Strict requestBody) {
    return answer(() -> {
      var run = begin(requestBody, "funnel", "funnels", false);
      var steps = run.parameters().get("steps");
      if (steps == null || !steps.isArray() || steps.size() < 2 || steps.size() > 8) {
        return Responses.badRequest(stepsError(steps));
      }
      return Responses.json(reports.funnel(run.websiteId(), run.query(), run.parameters()));
    });
  }

  private static ObjectNode stepsError(JsonNode steps) {
    var error = Json.object();
    error.set("errors", Json.array());
    var properties = Json.object();
    var parameters = Json.object();
    parameters.set("errors", Json.array());
    var inner = Json.object();
    var stepsNode = Json.object();
    var messages = Json.array();
    if (steps == null || !steps.isArray() || steps.size() < 2) {
      messages.add("Too small: expected array to have >=2 items");
    } else {
      messages.add("Too big: expected array to have <=8 items");
    }
    stepsNode.set("errors", messages);
    inner.set("steps", stepsNode);
    parameters.set("properties", inner);
    properties.set("parameters", parameters);
    error.set("properties", properties);
    return error;
  }

  @Post("/api/reports/goal")
  public HttpResponse goal(HttpEntity.Strict requestBody) {
    return answer(() -> {
      var run = begin(requestBody, "goal", "goals", false);
      return Responses.json(reports.goal(run.websiteId(), run.query(), run.parameters()));
    });
  }

  @Post("/api/reports/journey")
  public HttpResponse journey(HttpEntity.Strict requestBody) {
    return answer(() -> {
      var run = begin(requestBody, "journey", "journeys", false);
      var query = run.query();
      var eventType = run.parameters().get("eventType");
      if (eventType != null && eventType.isNumber()) {
        query = query.withEventType(eventType.asInt());
      }
      return Responses.json(reports.journey(run.websiteId(), query, run.parameters()));
    });
  }

  @Post("/api/reports/retention")
  public HttpResponse retention(HttpEntity.Strict requestBody) {
    return answer(() -> {
      var run = begin(requestBody, "retention", "retention", false);
      return Responses.json(
          reports.retention(run.websiteId(), run.query(), text(run.parameters(), "timezone")));
    });
  }

  @Post("/api/reports/utm")
  public HttpResponse utm(HttpEntity.Strict requestBody) {
    return answer(() -> {
      var run = begin(requestBody, "utm", "utm", false);
      return Responses.json(reports.utm(run.websiteId(), run.query()));
    });
  }

  @Post("/api/reports/attribution")
  public HttpResponse attribution(HttpEntity.Strict requestBody) {
    return answer(() -> {
      var run = begin(requestBody, "attribution", "attribution", false);
      var model = text(run.parameters(), "model");
      if (!List.of("first-click", "last-click").contains(model)) {
        return Responses.badRequest();
      }
      return Responses.json(reports.attribution(run.websiteId(), run.query(), run.parameters()));
    });
  }

  @Post("/api/reports/breakdown")
  public HttpResponse breakdown(HttpEntity.Strict requestBody) {
    return answer(() -> {
      var run = begin(requestBody, "breakdown", "breakdown", false);
      var fields = run.parameters().get("fields");
      if (fields == null || !fields.isArray()) {
        return Responses.badRequest();
      }
      for (var field : fields) {
        if (!Constants.FIELD_NAMES.contains(field.asText())) {
          return Responses.badRequest();
        }
      }
      return Responses.json(reports.breakdown(run.websiteId(), run.query(), run.parameters()));
    });
  }

  @Post("/api/reports/performance")
  public HttpResponse performance(HttpEntity.Strict requestBody) {
    return answer(() -> {
      var run = begin(requestBody, "performance", "performance", false);
      var metric = text(run.parameters(), "metric");
      if (metric == null) {
        metric = "lcp";
      }
      var measures = List.of("lcp", "inp", "cls", "fcp", "ttfb");
      if (!measures.contains(metric)) {
        return Responses.badRequest(
            Schema.under("parameters", Schema.problem("metric", Schema.notAnOption(measures))));
      }
      var unit = run.query().unit();
      var timezone = run.query().timezone();
      var body = reports.performance(run.websiteId(), run.query(), metric, unit, timezone);
      body.set("pages",
          reports.performanceMetrics(run.websiteId(), run.query(), "path", metric, 500));
      body.set("pageTitles",
          reports.performanceMetrics(run.websiteId(), run.query(), "title", metric, 500));
      body.set("devices",
          reports.performanceMetrics(run.websiteId(), run.query(), "device", metric, null));
      body.set("browsers",
          reports.performanceMetrics(run.websiteId(), run.query(), "browser", metric, 500));
      return Responses.json(body);
    });
  }

  @Post("/api/reports/revenue")
  public HttpResponse revenue(HttpEntity.Strict requestBody) {
    return answer(() -> {
      var run = begin(requestBody, "revenue", "revenue", false);
      var currency = text(run.parameters(), "currency");
      if (currency == null) {
        return Responses.badRequest();
      }
      var unit = run.query().unit();
      var timezone = run.query().timezone();
      var body = Json.object();
      body.set("chart",
          reports.revenueChart(run.websiteId(), run.query(), currency, unit, timezone));
      var total = reports.revenueStats(run.websiteId(), run.query(), currency);
      var compare = text(run.parameters(), "compare");
      var comparison =
          Dates.compareRange(compare == null ? "prev" : compare, run.query().startDate(),
              run.query().endDate());
      total.set("comparison",
          reports.revenueStats(run.websiteId(),
              run.query().withRange(comparison.startDate(), comparison.endDate()), currency));
      body.set("total", total);
      body.set("country",
          reports.revenueMetrics(run.websiteId(), run.query(), currency, "country"));
      body.set("region",
          reports.revenueMetrics(run.websiteId(), run.query(), currency, "region"));
      body.set("referrer",
          reports.revenueMetrics(run.websiteId(), run.query(), currency, "referrer"));
      body.set("channel",
          reports.revenueMetrics(run.websiteId(), run.query(), currency, "channel"));
      return Responses.json(body);
    });
  }

  /** The one report a share token may never run. SPEC R76. */
  @Post("/api/reports/heatmap")
  public HttpResponse heatmap(HttpEntity.Strict requestBody) {
    return answer(() -> {
      var run = begin(requestBody, "heatmap", null, true);
      return Responses.json(
          reports.heatmap(run.websiteId(), run.query(), text(run.parameters(), "urlPath"),
              text(run.parameters(), "mode")));
    });
  }

  // ------------------------------------------------------------------ revenue on its own

  @Get("/api/websites/{websiteId}/revenue/stats")
  public HttpResponse revenueStats(String websiteId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canViewWebsiteSection(caller, websiteId, List.of("revenue")));
      var query = filters(websiteId);
      var currency = queryParam("currency");
      var body = reports.revenueStats(websiteId, query, currency);
      var comparison =
          Dates.compareRange(query.compare() == null ? "prev" : query.compare(),
              query.startDate(), query.endDate());
      body.set("comparison",
          reports.revenueStats(websiteId,
              query.withRange(comparison.startDate(), comparison.endDate()), currency));
      return Responses.json(body);
    });
  }

  @Get("/api/websites/{websiteId}/revenue/chart")
  public HttpResponse revenueChart(String websiteId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canViewWebsiteSection(caller, websiteId, List.of("revenue")));
      var query = filters(websiteId);
      return Responses.json(
          reports.revenueChart(websiteId, query, queryParam("currency"), query.unit(),
              query.timezone()));
    });
  }

  @Get("/api/websites/{websiteId}/revenue/metrics")
  public HttpResponse revenueMetrics(String websiteId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canViewWebsiteSection(caller, websiteId, List.of("revenue")));
      var type = queryParam("type");
      if (!List.of("country", "region", "referrer", "channel").contains(type)) {
        return Responses.badRequest();
      }
      var query = filters(websiteId);
      return Responses.json(
          reports.revenueMetrics(websiteId, query, queryParam("currency"), type));
    });
  }

  @Get("/api/websites/{websiteId}/revenue/sessions")
  public HttpResponse revenueSessions(String websiteId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canViewWebsiteSection(caller, websiteId, List.of("revenue")));
      var query = filters(websiteId);
      var currency = queryParam("currency");
      var earning = new java.util.HashSet<String>();
      for (var record : store.revenue(websiteId, query.startDate(), query.endDate())) {
        if (currency == null
            || currency.equalsIgnoreCase(AccountEndpoint.nullToEmpty(record.currency()))) {
          earning.add(record.sessionId());
        }
      }
      var rows = new ArrayList<>(insight.sessions(websiteId, query));
      rows.removeIf(row -> !earning.contains(row.id()));
      return Responses.json(analyticsPage(rows, query, Writers::sessionRow));
    });
  }
}
