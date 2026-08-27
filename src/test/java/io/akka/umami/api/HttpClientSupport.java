package io.akka.umami.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.akka.umami.lib.Json;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A caller that reaches the service the way a browser does.
 *
 * <p>Every check here goes over HTTP rather than through the component client, because that is the
 * only way the routing, the request parsing, the token handling and the serialisers are all in the
 * path — and each of those has its own rules.
 */
public final class HttpClientSupport {

  private final String base;
  private final HttpClient client;
  private String token;

  public HttpClientSupport(String base) {
    this.base = base;
    this.client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  public record Answer(int status, JsonNode body, Map<String, String> headers) {

    public String text(String field) {
      return body == null ? null : Json.text(body, field);
    }

    public long number(String field) {
      return body == null || body.get(field) == null ? -1 : body.get(field).asLong();
    }

    public String errorCode() {
      if (body == null || body.get("error") == null) {
        return null;
      }
      return Json.text(body.get("error"), "code");
    }

    public String errorMessage() {
      if (body == null || body.get("error") == null) {
        return null;
      }
      return Json.text(body.get("error"), "message");
    }
  }

  public void useToken(String value) {
    this.token = value;
  }

  public String token() {
    return token;
  }

  public Answer get(String path) {
    return send("GET", path, null, Map.of());
  }

  public Answer get(String path, Map<String, String> headers) {
    return send("GET", path, null, headers);
  }

  public Answer post(String path, JsonNode body) {
    return send("POST", path, body, Map.of());
  }

  public Answer post(String path, JsonNode body, Map<String, String> headers) {
    return send("POST", path, body, headers);
  }

  public Answer delete(String path) {
    return send("DELETE", path, null, Map.of());
  }

  public Answer send(String method, String path, JsonNode body, Map<String, String> headers) {
    try {
      var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(60));
      if (body == null) {
        builder.method(method, HttpRequest.BodyPublishers.noBody());
      } else {
        builder.header("Content-Type", "application/json");
        builder.method(method,
            HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8));
      }
      if (token != null && !headers.containsKey("Authorization")) {
        builder.header("Authorization", "Bearer " + token);
      }
      headers.forEach(builder::header);
      var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      var out = new LinkedHashMap<String, String>();
      response.headers().map().forEach((name, values) -> out.put(name, values.get(0)));
      return new Answer(response.statusCode(), Json.read(response.body()), out);
    } catch (Exception e) {
      throw new IllegalStateException("the request failed: " + method + " " + path, e);
    }
  }

  /** Signs in as the account the first start creates. */
  public Answer signIn(String username, String password) {
    var body = Json.object();
    body.put("username", username);
    body.put("password", password);
    var answer = post("/api/auth/login", body);
    if (answer.status() == 200 && answer.text("token") != null) {
      useToken(answer.text("token"));
    }
    return answer;
  }

  public ObjectNode object(String... pairs) {
    var node = Json.object();
    for (int i = 0; i < pairs.length; i += 2) {
      node.put(pairs[i], pairs[i + 1]);
    }
    return node;
  }
}
