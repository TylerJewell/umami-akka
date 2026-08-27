package io.akka.umami.lib;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

/**
 * The tree the whole surface exchanges.
 *
 * <p>Bodies are built as trees rather than as classes because the answers are shaped by the request
 * — a key with no value is absent rather than null, which SPEC R115 and R120 both turn on, and a
 * record with a nullable field would serialise the null.
 */
public final class Json {

  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          // Instants are written as the string every stored document and every answer carries,
          // rather than as the numeric form Jackson defaults to.
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .setSerializationInclusion(JsonInclude.Include.NON_NULL);

  private Json() {}

  public static ObjectMapper mapper() {
    return MAPPER;
  }

  public static ObjectNode object() {
    return MAPPER.createObjectNode();
  }

  public static ArrayNode array() {
    return MAPPER.createArrayNode();
  }

  public static String write(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("could not write JSON", e);
    }
  }

  public static JsonNode read(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return MAPPER.readTree(value);
    } catch (Exception e) {
      return null;
    }
  }

  public static ObjectNode readObject(String value) {
    var node = read(value);
    return node instanceof ObjectNode object ? object : null;
  }

  public static ArrayNode readArray(String value) {
    var node = read(value);
    return node instanceof ArrayNode array ? array : null;
  }

  /** A tree from a map, used where a stored blob is round-tripped. */
  public static ObjectNode from(Map<String, ?> map) {
    return MAPPER.valueToTree(map);
  }

  public static ArrayNode of(List<? extends JsonNode> nodes) {
    var array = MAPPER.createArrayNode();
    nodes.forEach(array::add);
    return array;
  }

  public static <T> T convert(JsonNode node, Class<T> type) {
    if (node == null) {
      return null;
    }
    try {
      return MAPPER.treeToValue(node, type);
    } catch (Exception e) {
      throw new IllegalStateException("could not read a " + type.getSimpleName(), e);
    }
  }

  public static <T> T parse(String document, Class<T> type) {
    return document == null ? null : convert(read(document), type);
  }

  public static JsonNode tree(Object value) {
    return MAPPER.valueToTree(value);
  }

  public static String text(JsonNode node, String field) {
    if (node == null) {
      return null;
    }
    var value = node.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }

  public static Long number(JsonNode node, String field) {
    if (node == null) {
      return null;
    }
    var value = node.get(field);
    return value == null || !value.isNumber() ? null : value.asLong();
  }

  public static boolean flag(JsonNode node, String field) {
    if (node == null) {
      return false;
    }
    var value = node.get(field);
    return value != null && value.asBoolean(false);
  }
}
