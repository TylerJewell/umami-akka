package io.akka.umami.lib;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * How an arbitrary property becomes a stored row.
 *
 * <p>A nested object is flattened into dotted keys, and the declared type decides both what is
 * stored and how a filter later compares it. The number convention — four decimal places in the
 * string column as well as the numeric one — is visible to callers, because the value that comes
 * back from a property lookup is the string with the trailing zeros stripped.
 */
public final class Values {

  private static final Pattern DATE_TIME =
      Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,3})?(Z|[+-]\\d{2}:\\d{2})?$");

  private static final int MAX_STRING = 500;

  private Values() {}

  public record Property(String key, String stringValue, BigDecimal numberValue, Instant dateValue,
      int dataType) {}

  /** Every leaf of the tree, as one row each. */
  public static List<Property> flatten(JsonNode node) {
    var out = new ArrayList<Property>();
    flattenInto("", node, out);
    return out;
  }

  private static void flattenInto(String prefix, JsonNode node, List<Property> out) {
    if (node == null || node.isNull()) {
      return;
    }
    if (node.isObject()) {
      var names = node.fieldNames();
      while (names.hasNext()) {
        var name = names.next();
        flattenInto(prefix.isEmpty() ? name : prefix + "." + name, node.get(name), out);
      }
      return;
    }
    var property = toProperty(prefix, node);
    if (property != null) {
      out.add(property);
    }
  }

  static Property toProperty(String key, JsonNode node) {
    var truncatedKey = truncate(key, Constants.FIELD_LENGTH.get("dataKey"));
    if (node.isNumber()) {
      var number = node.decimalValue();
      return new Property(
          truncatedKey,
          number.setScale(4, RoundingMode.HALF_UP).toPlainString(),
          number,
          null,
          Constants.DATA_NUMBER);
    }
    if (node.isBoolean()) {
      return new Property(truncatedKey, String.valueOf(node.asBoolean()), null, null,
          Constants.DATA_BOOLEAN);
    }
    if (node.isTextual()) {
      var text = node.asText();
      if (DATE_TIME.matcher(text).matches()) {
        try {
          return new Property(truncatedKey, text, null, parseInstant(text), Constants.DATA_DATE);
        } catch (Exception ignored) {
          // A string that looks like an instant but is not one is stored as a string.
        }
      }
      return new Property(truncatedKey, truncate(text, MAX_STRING), null, null,
          Constants.DATA_STRING);
    }
    if (node.isArray()) {
      var text = Json.write(node);
      // An array whose text is too long is stored as nothing rather than as truncated,
      // invalid JSON that no later reader could take apart.
      return new Property(
          truncatedKey, text.length() > MAX_STRING ? null : text, null, null, Constants.DATA_ARRAY);
    }
    return null;
  }

  private static Instant parseInstant(String text) {
    if (text.endsWith("Z") || text.matches(".*[+-]\\d{2}:\\d{2}$")) {
      return Instant.parse(text.endsWith("Z") ? text : text);
    }
    return Instant.parse(text + "Z");
  }

  /** The value a property lookup reports, which strips the number convention's trailing zeros. */
  public static String displayValue(Property property) {
    if (property.dataType() == Constants.DATA_NUMBER && property.stringValue() != null) {
      return property.stringValue().replace(".0000", "");
    }
    return property.stringValue();
  }

  public static String truncate(String value, Integer max) {
    if (value == null || max == null || value.length() <= max) {
      return value;
    }
    return value.substring(0, max);
  }

  public static String truncate(String value, String field) {
    return truncate(value, Constants.FIELD_LENGTH.get(field));
  }
}
