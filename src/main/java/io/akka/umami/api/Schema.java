package io.akka.umami.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.akka.umami.lib.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * What a request has to look like, and what a caller is told when it does not.
 *
 * <p>The refusal is not just a status: umami answers a tree naming each failing field and the
 * sentence its validator produced, and a client reads those sentences. So the sentences are
 * reproduced rather than paraphrased — the wording is part of the surface.
 */
public final class Schema {

  /** One field's rules. */
  public static final class Field {

    private final String name;
    private final Kind kind;
    private boolean required;
    private boolean nullable;
    private Integer min;
    private Integer max;
    private Integer minItems;
    private Integer maxItems;
    private List<String> options;
    private Pattern pattern;
    private boolean trim;
    private boolean positive;
    private Schema nested;
    private Double minimum;
    private Double maximum;
    private String patternMessage;

    private enum Kind {
      STRING,
      NUMBER,
      INTEGER,
      BOOLEAN,
      UUID,
      OBJECT,
      ARRAY,
      DATE,
      ANY
    }

    private Field(String name, Kind kind) {
      this.name = name;
      this.kind = kind;
    }

    public Field required() {
      this.required = true;
      return this;
    }

    public Field nullable() {
      this.nullable = true;
      return this;
    }

    public Field min(int value) {
      this.min = value;
      return this;
    }

    public Field max(int value) {
      this.max = value;
      return this;
    }

    public Field items(Integer minimum, Integer maximum) {
      this.minItems = minimum;
      this.maxItems = maximum;
      return this;
    }

    public Field options(List<String> values) {
      this.options = values;
      return this;
    }

    public Field pattern(String regex) {
      this.pattern = Pattern.compile(regex);
      return this;
    }

    /** The sentence a failure carries, where the rule has one of its own. */
    public Field pattern(String regex, String message) {
      this.pattern = Pattern.compile(regex);
      this.patternMessage = message;
      return this;
    }

    public Field trim() {
      this.trim = true;
      return this;
    }

    public Field positive() {
      this.positive = true;
      return this;
    }

    public Field range(double lowest, double highest) {
      this.minimum = lowest;
      this.maximum = highest;
      return this;
    }

    public Field nested(Schema schema) {
      this.nested = schema;
      return this;
    }
  }

  private final Map<String, Field> fields = new LinkedHashMap<>();
  private boolean strict;
  private final List<Refinement> refinements = new ArrayList<>();

  /** A rule about the whole object rather than about one field. */
  public record Refinement(java.util.function.Predicate<ObjectNode> holds, String path,
      String message) {}

  public static Schema object() {
    return new Schema();
  }

  /** Refuses an unrecognised key rather than dropping it, which one route does. */
  public Schema strict() {
    this.strict = true;
    return this;
  }

  public Schema refine(java.util.function.Predicate<ObjectNode> holds, String path,
      String message) {
    refinements.add(new Refinement(holds, path, message));
    return this;
  }

  public Field string(String name) {
    return add(new Field(name, Field.Kind.STRING));
  }

  public Field number(String name) {
    return add(new Field(name, Field.Kind.NUMBER));
  }

  public Field integer(String name) {
    return add(new Field(name, Field.Kind.INTEGER));
  }

  public Field bool(String name) {
    return add(new Field(name, Field.Kind.BOOLEAN));
  }

  public Field uuid(String name) {
    return add(new Field(name, Field.Kind.UUID));
  }

  public Field objectField(String name) {
    return add(new Field(name, Field.Kind.OBJECT));
  }

  public Field arrayField(String name) {
    return add(new Field(name, Field.Kind.ARRAY));
  }

  public Field date(String name) {
    return add(new Field(name, Field.Kind.DATE));
  }

  public Field any(String name) {
    return add(new Field(name, Field.Kind.ANY));
  }

  private Field add(Field field) {
    fields.put(field.name, field);
    return field;
  }

  /** What validation produced: either the coerced object, or the tree describing the refusal. */
  public record Result(ObjectNode value, ObjectNode error) {

    public boolean failed() {
      return error != null;
    }
  }

  /**
   * Validates a field that is itself an object, reporting its failures under its own key.
   *
   * <p>A request whose payload is a field of it reports {@code properties.payload.properties.url}
   * rather than {@code properties.url}: the shape says where in the request the trouble is, and
   * a client reads it to point at the right control.
   */
  public Result validateNested(JsonNode input, String field, Schema inner) {
    var outer = validate(input);
    if (outer.failed()) {
      return outer;
    }
    var value = outer.value().get(field);
    var result = inner.validate(value);
    if (!result.failed()) {
      return new Result(result.value(), null);
    }
    var wrapper = Json.object();
    wrapper.set("errors", Json.array());
    var properties = Json.object();
    var nested = Json.object();
    nested.set("errors", result.error().get("errors"));
    if (result.error().has("properties")) {
      nested.set("properties", result.error().get("properties"));
    }
    properties.set(field, nested);
    wrapper.set("properties", properties);
    return new Result(null, wrapper);
  }

  public Result validate(JsonNode input) {
    var source = input instanceof ObjectNode node ? node : Json.object();
    var out = Json.object();
    var problems = new LinkedHashMap<String, List<String>>();
    var topLevel = new ArrayList<String>();

    if (strict) {
      var names = source.fieldNames();
      while (names.hasNext()) {
        var name = names.next();
        if (!fields.containsKey(name)) {
          topLevel.add("Unrecognized key: \"" + name + "\"");
        }
      }
    }

    for (var field : fields.values()) {
      var value = source.get(field.name);
      if (value == null || value.isNull()) {
        if (value != null && field.nullable) {
          out.set(field.name, value);
          continue;
        }
        if (field.required) {
          problems
              .computeIfAbsent(field.name, n -> new ArrayList<>())
              .add("Invalid input: expected " + expected(field) + ", received undefined");
        }
        continue;
      }
      var messages = new ArrayList<String>();
      var coerced = coerce(field, value, messages);
      if (!messages.isEmpty()) {
        problems.computeIfAbsent(field.name, n -> new ArrayList<>()).addAll(messages);
        continue;
      }
      out.set(field.name, coerced);
    }

    if (problems.isEmpty() && topLevel.isEmpty()) {
      for (var refinement : refinements) {
        if (!refinement.holds().test(out)) {
          problems
              .computeIfAbsent(refinement.path(), n -> new ArrayList<>())
              .add(refinement.message());
        }
      }
    }

    if (problems.isEmpty() && topLevel.isEmpty()) {
      return new Result(out, null);
    }
    return new Result(null, errorTree(problems, topLevel));
  }

  /**
   * The refusal tree for one field, for a check that is made outside a schema.
   *
   * <p>The date range, the zone and the bucket are read off the query string by the code that
   * builds a filter rather than by a schema, and a client reads their failures from the same
   * place it reads a body's.
   */
  public static ObjectNode problem(String field, String message) {
    return errorTree(Map.of(field, List.of(message)), List.of());
  }

  /** Puts a refusal tree under an outer field, the way a nested object's failures are reported. */
  public static ObjectNode under(String field, ObjectNode inner) {
    var wrapper = Json.object();
    wrapper.set("errors", Json.array());
    var properties = Json.object();
    properties.set(field, inner);
    wrapper.set("properties", properties);
    return wrapper;
  }

  /** The sentence a field that names one of a fixed set gets when it names something else. */
  public static String notAnOption(List<String> options) {
    return "Invalid option: expected one of "
        + String.join("|", options.stream().map(o -> "\"" + o + "\"").toList());
  }

  private static ObjectNode errorTree(Map<String, List<String>> problems, List<String> topLevel) {
    var error = Json.object();
    var errors = Json.array();
    topLevel.forEach(errors::add);
    error.set("errors", errors);
    if (!problems.isEmpty()) {
      var properties = Json.object();
      problems.forEach(
          (name, messages) -> {
            var field = Json.object();
            var list = Json.array();
            messages.forEach(list::add);
            field.set("errors", list);
            properties.set(name, field);
          });
      error.set("properties", properties);
    }
    return error;
  }

  private static String expected(Field field) {
    return switch (field.kind) {
      case NUMBER, INTEGER -> "number";
      case BOOLEAN -> "boolean";
      case OBJECT -> "object";
      case ARRAY -> "array";
      default -> "string";
    };
  }

  private static JsonNode coerce(Field field, JsonNode value, List<String> messages) {
    switch (field.kind) {
      case STRING, UUID, DATE -> {
        if (!value.isTextual() && !value.isNumber()) {
          messages.add("Invalid input: expected string, received " + received(value));
          return value;
        }
        var text = value.asText();
        if (field.trim) {
          text = text.trim();
        }
        if (field.min != null && text.length() < field.min) {
          messages.add("Too small: expected string to have >=" + field.min + " characters");
        }
        if (field.max != null && text.length() > field.max) {
          messages.add("Too big: expected string to have <=" + field.max + " characters");
        }
        if (field.options != null && !field.options.contains(text)) {
          messages.add(notAnOption(field.options));
        }
        if (field.pattern != null && !field.pattern.matcher(text).matches()) {
          messages.add(field.patternMessage != null ? field.patternMessage
              : "Invalid string: must match pattern /" + field.pattern.pattern() + "/");
        }
        if (field.kind == Field.Kind.UUID && !isUuid(text)) {
          messages.add("Invalid UUID");
        }
        return Json.mapper().getNodeFactory().textNode(text);
      }
      case NUMBER, INTEGER -> {
        Double number = null;
        if (value.isNumber()) {
          number = value.asDouble();
        } else if (value.isTextual()) {
          try {
            number = Double.parseDouble(value.asText());
          } catch (NumberFormatException ignored) {
            // A value that is not a number at all falls through to the message below.
          }
        }
        if (number == null) {
          messages.add("Invalid input: expected number, received " + received(value));
          return value;
        }
        if (field.positive && number <= 0) {
          messages.add("Too small: expected number to be >0");
        }
        if (field.minimum != null && number < field.minimum) {
          messages.add("Too small: expected number to be >=" + trim(field.minimum));
        }
        if (field.maximum != null && number > field.maximum) {
          messages.add("Too big: expected number to be <=" + trim(field.maximum));
        }
        if (field.kind == Field.Kind.INTEGER) {
          return Json.mapper().getNodeFactory().numberNode(number.longValue());
        }
        return Json.mapper().getNodeFactory().numberNode(number);
      }
      case BOOLEAN -> {
        if (value.isBoolean()) {
          return value;
        }
        if (value.isTextual() && List.of("true", "false").contains(value.asText())) {
          return Json.mapper().getNodeFactory().booleanNode("true".equals(value.asText()));
        }
        messages.add("Invalid input: expected boolean, received " + received(value));
        return value;
      }
      case ARRAY -> {
        if (!value.isArray()) {
          messages.add("Invalid input: expected array, received " + received(value));
          return value;
        }
        if (field.minItems != null && value.size() < field.minItems) {
          messages.add("Too small: expected array to have >=" + field.minItems + " items");
        }
        if (field.maxItems != null && value.size() > field.maxItems) {
          messages.add("Too big: expected array to have <=" + field.maxItems + " items");
        }
        return value;
      }
      case OBJECT -> {
        if (!value.isObject()) {
          messages.add("Invalid input: expected object, received " + received(value));
          return value;
        }
        return value;
      }
      default -> {
        return value;
      }
    }
  }

  private static String trim(double value) {
    return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
  }

  private static String received(JsonNode value) {
    if (value.isTextual()) {
      return "string";
    }
    if (value.isNumber()) {
      return "number";
    }
    if (value.isBoolean()) {
      return "boolean";
    }
    if (value.isArray()) {
      return "array";
    }
    if (value.isObject()) {
      return "object";
    }
    return "undefined";
  }

  public static boolean isUuid(String value) {
    return value != null
        && value.matches("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
            + "[0-9a-fA-F]{12}$");
  }
}
