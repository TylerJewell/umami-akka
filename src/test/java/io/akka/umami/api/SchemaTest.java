package io.akka.umami.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.akka.umami.lib.Json;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SPEC R117: what a caller is told when a request does not hold up.
 *
 * <p>The sentences are part of the surface — a client reads them — so they are checked
 * literally rather than by their shape.
 */
class SchemaTest {

  private static String firstMessage(Schema.Result result, String field) {
    return result.error().get("properties").get(field).get("errors").get(0).asText();
  }

  @Test
  void aMissingRequiredFieldNamesWhatWasExpectedAndWhatArrived() {
    var schema = Schema.object();
    schema.string("password").required();
    var result = schema.validate(Json.object());
    assertTrue(result.failed());
    assertEquals("Invalid input: expected string, received undefined",
        firstMessage(result, "password"));
  }

  @Test
  void aShortStringNamesTheLengthItNeeded() {
    var schema = Schema.object();
    schema.string("password").min(8).required();
    var request = Json.object();
    request.put("password", "short");
    assertEquals("Too small: expected string to have >=8 characters",
        firstMessage(schema.validate(request), "password"));
  }

  @Test
  void aValueOutsideAnEnumerationListsTheEnumeration() {
    var schema = Schema.object();
    schema.string("role").options(List.of("admin", "user", "view-only")).required();
    var request = Json.object();
    request.put("role", "wizard");
    assertEquals("Invalid option: expected one of \"admin\"|\"user\"|\"view-only\"",
        firstMessage(schema.validate(request), "role"));
  }

  @Test
  void aValueThatFailsAPatternQuotesThePattern() {
    var schema = Schema.object();
    schema.string("domain").pattern("^[a-z.]+$").required();
    var request = Json.object();
    request.put("domain", "not a domain");
    assertEquals("Invalid string: must match pattern /^[a-z.]+$/",
        firstMessage(schema.validate(request), "domain"));
  }

  @Test
  void anOversizedListNamesTheCeiling() {
    var schema = Schema.object();
    schema.arrayField("items").items(null, 2).required();
    var request = Json.object();
    var items = Json.array();
    items.add(1);
    items.add(2);
    items.add(3);
    request.set("items", items);
    assertEquals("Too big: expected array to have <=2 items",
        firstMessage(schema.validate(request), "items"));
  }

  @Test
  void aStrictObjectNamesEveryKeyItDidNotExpect() {
    var schema = Schema.object().strict();
    schema.string("token").min(6).max(6);
    schema.string("backupCode").min(1);
    var request = Json.object();
    request.put("token", "000000");
    request.put("backupCode", "AAAA");
    request.put("other", "x");
    var result = schema.validate(request);
    assertTrue(result.failed());
    var errors = result.error().get("errors");
    assertEquals(1, errors.size());
    assertEquals("Unrecognized key: \"other\"", errors.get(0).asText());
  }

  @Test
  void aRefinementNamesTheFieldItHangsOff() {
    var schema =
        Schema.object()
            .refine(node -> node.has("a") ^ node.has("b"), "a", "Exactly one of a or b");
    schema.string("a");
    schema.string("b");
    var request = Json.object();
    request.put("a", "1");
    request.put("b", "2");
    assertEquals("Exactly one of a or b", firstMessage(schema.validate(request), "a"));
  }

  @Test
  void aValidRequestComesBackCoercedRatherThanRaw() {
    var schema = Schema.object();
    schema.integer("page").positive();
    schema.bool("flag");
    schema.string("name").trim();
    var request = Json.object();
    request.put("page", "3");
    request.put("flag", "true");
    request.put("name", "  padded  ");
    var result = schema.validate(request);
    assertFalse(result.failed());
    assertEquals(3, result.value().get("page").asInt());
    assertTrue(result.value().get("flag").asBoolean());
    assertEquals("padded", result.value().get("name").asText());
  }

  @Test
  void aNumberOutsideItsRangeNamesTheBound() {
    var schema = Schema.object();
    schema.number("lcp").range(0, 60000);
    var request = Json.object();
    request.put("lcp", 70000);
    assertEquals("Too big: expected number to be <=60000", firstMessage(schema.validate(request),
        "lcp"));
  }

  @Test
  void anIdentifierHasToLookLikeOne() {
    var schema = Schema.object();
    schema.uuid("websiteId").required();
    var request = Json.object();
    request.put("websiteId", "not-a-uuid");
    assertEquals("Invalid UUID", firstMessage(schema.validate(request), "websiteId"));
    request.put("websiteId", "063310cd-9c7e-4465-b232-3cd399a4eff8");
    assertFalse(schema.validate(request).failed());
  }

  @Test
  void anExplicitNullIsKeptWhereTheFieldAllowsOne() {
    var schema = Schema.object();
    schema.string("shareId").nullable();
    var request = Json.object();
    request.putNull("shareId");
    var result = schema.validate(request);
    assertFalse(result.failed());
    assertTrue(result.value().has("shareId"));
    assertTrue(result.value().get("shareId").isNull());
  }
}
