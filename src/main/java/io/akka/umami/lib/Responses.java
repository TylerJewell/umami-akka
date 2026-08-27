package io.akka.umami.lib;

import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.model.HttpEntities;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.StatusCode;
import akka.http.javadsl.model.StatusCodes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * The one envelope every answer arrives in.
 *
 * <p>An error is always {@code {error:{message, code, status, ...}}} and the route's own additions
 * are applied last, so a route may replace the message or the code — which several do.
 */
public final class Responses {

  private Responses() {}

  public static HttpResponse json(JsonNode body) {
    return HttpResponse.create()
        .withStatus(StatusCodes.OK)
        .withEntity(
            HttpEntities.create(
                ContentTypes.APPLICATION_JSON, Json.write(body).getBytes(StandardCharsets.UTF_8)));
  }

  public static HttpResponse json(StatusCode status, JsonNode body) {
    return HttpResponse.create()
        .withStatus(status)
        .withEntity(
            HttpEntities.create(
                ContentTypes.APPLICATION_JSON, Json.write(body).getBytes(StandardCharsets.UTF_8)));
  }

  public static HttpResponse ok() {
    var body = Json.object();
    body.put("ok", true);
    return json(body);
  }

  public static HttpResponse error(int status, String message, String code, ObjectNode extra) {
    return json(StatusCodes.get(status), errorBody(status, message, code, extra));
  }

  /** The envelope on its own, for the batch route, which reports a refusal inside its answer. */
  public static ObjectNode errorBody(int status, String message, String code, ObjectNode extra) {
    var error = Json.object();
    error.put("message", message);
    error.put("code", code);
    error.put("status", status);
    if (extra != null) {
      extra.fieldNames().forEachRemaining(name -> error.set(name, extra.get(name)));
    }
    var body = Json.object();
    body.set("error", error);
    return body;
  }

  public static HttpResponse badRequest() {
    return error(400, "Bad request", "bad-request", null);
  }

  public static HttpResponse badRequest(String message) {
    var extra = Json.object();
    extra.put("message", message);
    return error(400, "Bad request", "bad-request", extra);
  }

  public static HttpResponse badRequest(String message, String code) {
    var extra = Json.object();
    extra.put("message", message);
    extra.put("code", code);
    return error(400, "Bad request", "bad-request", extra);
  }

  public static HttpResponse badRequest(ObjectNode extra) {
    return error(400, "Bad request", "bad-request", extra);
  }

  public static HttpResponse unauthorized() {
    return error(401, "Unauthorized", "unauthorized", null);
  }

  public static HttpResponse unauthorized(String message, String code) {
    var extra = Json.object();
    if (message != null) {
      extra.put("message", message);
    }
    if (code != null) {
      extra.put("code", code);
    }
    return error(401, "Unauthorized", "unauthorized", extra);
  }

  public static HttpResponse forbidden() {
    return error(403, "Forbidden", "forbidden", null);
  }

  public static HttpResponse forbidden(String message, String code) {
    var extra = Json.object();
    if (message != null) {
      extra.put("message", message);
    }
    if (code != null) {
      extra.put("code", code);
    }
    return error(403, "Forbidden", "forbidden", extra);
  }

  public static HttpResponse notFound() {
    return error(404, "Not found", "not-found", null);
  }

  public static HttpResponse notFound(String message, String code) {
    var extra = Json.object();
    if (message != null) {
      extra.put("message", message);
    }
    if (code != null) {
      extra.put("code", code);
    }
    return error(404, "Not found", "not-found", extra);
  }

  public static HttpResponse payloadTooLarge(ObjectNode extra) {
    return error(413, "Payload too large", "payload-too-large", extra);
  }

  /**
   * The rate-limit answer, which is the one place the envelope is not used: the original builds it
   * by hand and it carries no {@code status} key.
   */
  public static HttpResponse tooManyAttempts(String message, Long lockedUntil) {
    var error = Json.object();
    error.put("code", "two-factor-error-too-many-attempts");
    error.put("message", message);
    if (lockedUntil != null) {
      error.put("lockedUntil", lockedUntil);
    }
    var body = Json.object();
    body.set("error", error);
    return json(StatusCodes.get(429), body);
  }

  public static HttpResponse serviceUnavailable(String message, String code) {
    var extra = Json.object();
    if (message != null) {
      extra.put("message", message);
    }
    if (code != null) {
      extra.put("code", code);
    }
    return error(503, "Service unavailable", "service-unavailable", extra);
  }

  public static HttpResponse serverError() {
    return error(500, "Server error", "server-error", null);
  }

  /**
   * A failure on a route that does not guard its own work: the status alone, with no body.
   *
   * <p>umami's route handlers mostly call {@code serverError}, which describes what went wrong.
   * A handful — creating a link, a pixel or a board, and reading a dimension the filter algebra
   * has no column for — let the store's own error escape, and the framework answers it with an
   * empty 500. A client that reads the body finds nothing there, which is a difference worth
   * keeping.
   */
  public static HttpResponse uncaught() {
    return HttpResponse.create().withStatus(StatusCodes.get(500));
  }

  public static HttpResponse serverError(String message) {
    var extra = Json.object();
    extra.put("message", message);
    return error(500, "Server error", "server-error", extra);
  }

  public static HttpResponse text(String contentTypeValue, byte[] body, Map<String, String> headers) {
    var response =
        HttpResponse.create()
            .withStatus(StatusCodes.OK)
            .withEntity(
                HttpEntities.create(
                    akka.http.javadsl.model.ContentTypes.parse(contentTypeValue), body));
    for (var entry : headers.entrySet()) {
      response =
          response.addHeader(
              akka.http.javadsl.model.headers.RawHeader.create(entry.getKey(), entry.getValue()));
    }
    return response;
  }
}
