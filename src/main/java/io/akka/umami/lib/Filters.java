package io.akka.umami.lib;

import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The filter algebra: how a request's query string becomes a predicate, and what that predicate
 * does to a row.
 *
 * <p>Ten of the sixteen operators mean nothing on a dimension. The original does not refuse them —
 * it emits an empty clause and the store rejects the whole query — so they raise {@link
 * MalformedFilter} here and the endpoint answers a server error, which is what the original answers.
 */
public final class Filters {

  private Filters() {}

  /** Raised where an operator reaches a dimension it has no meaning for. SPEC R55. */
  public static class MalformedFilter extends RuntimeException {
    public MalformedFilter(String message) {
      super(message);
    }
  }

  /** One filter on one dimension. The name keeps its numeric suffix so repeats stay distinct. */
  public record Clause(String name, String baseName, String column, String operator,
      List<String> values) {}

  /** One filter on a named property, whose declared type decides the comparison. */
  public record PropertyFilter(String propertyName, int dataType, String operator, String value) {}

  /** A second filter set over its own range, restricting the answer to the sessions it selected. */
  public record Cohort(List<Clause> clauses, String match, Instant startDate, Instant endDate,
      String actionName) {}

  public record Query(
      Instant startDate,
      Instant endDate,
      String timezone,
      String unit,
      List<Clause> clauses,
      String match,
      List<PropertyFilter> eventPropertyFilters,
      List<PropertyFilter> sessionPropertyFilters,
      boolean excludeBounce,
      Integer eventType,
      Cohort cohort,
      Integer page,
      Integer pageSize,
      String orderBy,
      Boolean sortDescending,
      String search,
      String compare,
      Integer maxResults,
      Integer minDuration) {

    public boolean isAnyMatch() {
      return "any".equals(match);
    }

    /** Whether any clause touches something only an event carries. SPEC R29 turns on this. */
    public boolean hasEventFilters() {
      if (!eventPropertyFilters.isEmpty()) {
        return true;
      }
      for (var clause : clauses) {
        if (Constants.EVENT_COLUMNS.contains(clause.baseName())) {
          return true;
        }
      }
      return false;
    }

    public Query withRange(Instant start, Instant end) {
      return new Query(start, end, timezone, unit, clauses, match, eventPropertyFilters,
          sessionPropertyFilters, excludeBounce, eventType, cohort, page, pageSize, orderBy,
          sortDescending, search, compare, maxResults, minDuration);
    }

    public Query withEventType(Integer type) {
      return new Query(startDate, endDate, timezone, unit, clauses, match, eventPropertyFilters,
          sessionPropertyFilters, excludeBounce, type, cohort, page, pageSize, orderBy,
          sortDescending, search, compare, maxResults, minDuration);
    }

    public Query withClause(Clause clause) {
      var next = new ArrayList<>(clauses);
      next.add(clause);
      return new Query(startDate, endDate, timezone, unit, List.copyOf(next), match,
          eventPropertyFilters, sessionPropertyFilters, excludeBounce, eventType, cohort, page,
          pageSize, orderBy, sortDescending, search, compare, maxResults, minDuration);
    }
  }

  /** Whatever a predicate is evaluated against: it answers a column by dimension name. */
  public interface Row {
    String column(String name);
  }

  // --- parsing --------------------------------------------------------------------------------

  private static final Pattern TRAILING_DIGITS = Pattern.compile("\\d+$");

  public static String baseName(String name) {
    return TRAILING_DIGITS.matcher(name).replaceAll("");
  }

  /** A value may carry its operator in front: {@code browser=eq.chrome}, {@code path=c.blog}. */
  public static Clause parseClause(String name, String value) {
    var base = baseName(name);
    var column = Constants.FILTER_COLUMNS.get(base);
    if (column == null) {
      return null;
    }
    var operator = Constants.OP_EQUALS;
    var body = value;
    int dot = value.indexOf('.');
    if (dot > 0) {
      var candidate = value.substring(0, dot);
      if (Constants.OPERATORS.contains(candidate)) {
        operator = candidate;
        body = value.substring(dot + 1);
      }
    }
    List<String> values =
        (operator.equals(Constants.OP_EQUALS) || operator.equals(Constants.OP_NOT_EQUALS))
            ? Arrays.asList(body.split(",", -1))
            : List.of(body);
    return new Clause(name, base, column, operator, values);
  }

  /** {@code pf_<property>[n] = <dataType>.<operator>.<value>}, the data type optional. */
  public static List<PropertyFilter> parseScopedPropertyFilters(Map<String, String> query,
      String prefix) {
    var out = new ArrayList<PropertyFilter>();
    for (var entry : sorted(query).entrySet()) {
      var key = entry.getKey();
      if (!key.startsWith(prefix + "_")) {
        continue;
      }
      var name = TRAILING_DIGITS.matcher(key.substring(prefix.length() + 1)).replaceAll("");
      var parsed = parseTypedValue(entry.getValue());
      if (parsed != null) {
        out.add(new PropertyFilter(name, parsed.dataType(), parsed.operator(), parsed.value()));
      }
    }
    return List.copyOf(out);
  }

  /** {@code epfN} and {@code spfN}: {@code <dataType>.<operator>.<encodedName>.<value>}. */
  public static List<PropertyFilter> parseUniversalPropertyFilters(Map<String, String> query,
      String prefix) {
    var pattern = Pattern.compile("^" + prefix + "\\d+$");
    var out = new ArrayList<PropertyFilter>();
    for (var entry : sorted(query).entrySet()) {
      if (!pattern.matcher(entry.getKey()).matches()) {
        continue;
      }
      var parts = entry.getValue().split("\\.", 4);
      if (parts.length < 4) {
        continue;
      }
      int dataType;
      try {
        dataType = Integer.parseInt(parts[0]);
      } catch (NumberFormatException e) {
        continue;
      }
      if (!Constants.OPERATORS.contains(parts[1])) {
        continue;
      }
      var name = URLDecoder.decode(parts[2], StandardCharsets.UTF_8);
      out.add(new PropertyFilter(name, dataType, parts[1], parts[3]));
    }
    return List.copyOf(out);
  }

  private record Typed(int dataType, String operator, String value) {}

  private static Typed parseTypedValue(String raw) {
    var parts = raw.split("\\.", 3);
    if (parts.length == 3) {
      try {
        int dataType = Integer.parseInt(parts[0]);
        if (Constants.OPERATORS.contains(parts[1])) {
          return new Typed(dataType, parts[1], parts[2]);
        }
      } catch (NumberFormatException ignored) {
        // Not a leading data type; fall through to the two-part form.
      }
    }
    if (parts.length >= 2 && Constants.OPERATORS.contains(parts[0])) {
      return new Typed(Constants.DATA_STRING, parts[0],
          raw.substring(parts[0].length() + 1));
    }
    return null;
  }

  private static Map<String, String> sorted(Map<String, String> query) {
    var out = new LinkedHashMap<String, String>();
    query.keySet().stream().sorted().forEach(key -> out.put(key, query.get(key)));
    return out;
  }

  // --- matching ---------------------------------------------------------------------------------

  /**
   * Whether a row satisfies the whole filter set. Under {@code match=any} the eligible clauses form
   * one disjunction and the rest stay conjoined; the event type never joins the disjunction.
   */
  public static boolean matches(Query query, Row row) {
    var disjunction = new ArrayList<Clause>();
    var conjunction = new ArrayList<Clause>();
    for (var clause : query.clauses()) {
      if (query.isAnyMatch() && !clause.baseName().equals("eventType")) {
        disjunction.add(clause);
      } else {
        conjunction.add(clause);
      }
    }
    for (var clause : conjunction) {
      if (!matchesClause(clause, row)) {
        return false;
      }
    }
    if (!disjunction.isEmpty()) {
      boolean any = false;
      for (var clause : disjunction) {
        if (matchesClause(clause, row)) {
          any = true;
          break;
        }
      }
      if (!any) {
        return false;
      }
    }
    // A filter on the referrer additionally and unconditionally excludes a self-referral.
    for (var clause : query.clauses()) {
      if (clause.baseName().equals("referrer")) {
        var referrer = row.column("referrer");
        var host = row.column("hostname");
        if (referrer != null && !referrer.isEmpty() && host != null
            && referrer.equals(stripWww(host))) {
          return false;
        }
      }
    }
    return true;
  }

  public static boolean matchesClause(Clause clause, Row row) {
    var actual = row.column(clause.baseName());
    return compare(clause.operator(), clause.values(), actual, clause.baseName());
  }

  static boolean compare(String operator, List<String> values, String actual, String dimension) {
    switch (operator) {
      case Constants.OP_EQUALS:
        return values.contains(actual == null ? "" : actual);
      case Constants.OP_NOT_EQUALS:
        return !values.contains(actual == null ? "" : actual);
      case Constants.OP_CONTAINS:
        return actual != null
            && actual.toLowerCase(Locale.ROOT).contains(values.get(0).toLowerCase(Locale.ROOT));
      case Constants.OP_DOES_NOT_CONTAIN:
        return actual == null
            || !actual.toLowerCase(Locale.ROOT).contains(values.get(0).toLowerCase(Locale.ROOT));
      case Constants.OP_MATCHES:
        return actual != null
            && Pattern.compile(values.get(0), Pattern.CASE_INSENSITIVE).matcher(actual).find();
      case Constants.OP_DOES_NOT_MATCH:
        return actual == null
            || !Pattern.compile(values.get(0), Pattern.CASE_INSENSITIVE).matcher(actual).find();
      default:
        throw new MalformedFilter(
            "the operator " + operator + " produces no condition on " + dimension);
    }
  }

  /** How a property filter compares, which is decided by the type the caller declared. */
  public static boolean matchesProperty(PropertyFilter filter, Values.Property property,
      String timezone) {
    if (!filter.propertyName().equals(property.key())) {
      return false;
    }
    if (filter.dataType() != property.dataType()) {
      return false;
    }
    return switch (filter.dataType()) {
      case Constants.DATA_NUMBER -> compareNumber(filter, property);
      case Constants.DATA_DATE -> compareDate(filter, property, timezone);
      case Constants.DATA_ARRAY -> compareArray(filter, property);
      default -> compareString(filter, property);
    };
  }

  private static boolean compareNumber(PropertyFilter filter, Values.Property property) {
    if (property.numberValue() == null) {
      return false;
    }
    BigDecimal wanted;
    try {
      wanted = new BigDecimal(filter.value());
    } catch (NumberFormatException e) {
      wanted = BigDecimal.ZERO;
    }
    int comparison = property.numberValue().compareTo(wanted);
    return switch (filter.operator()) {
      case Constants.OP_NOT_EQUALS -> comparison != 0;
      case Constants.OP_GREATER -> comparison > 0;
      case Constants.OP_LESS -> comparison < 0;
      case Constants.OP_GREATER_OR_EQUAL -> comparison >= 0;
      case Constants.OP_LESS_OR_EQUAL -> comparison <= 0;
      default -> comparison == 0;
    };
  }

  private static boolean compareDate(PropertyFilter filter, Values.Property property,
      String timezone) {
    if (property.dateValue() == null || filter.value() == null || filter.value().isBlank()) {
      return false;
    }
    LocalDate actual = Dates.localDate(property.dateValue(), timezone);
    LocalDate wanted;
    try {
      wanted = LocalDate.parse(filter.value().substring(0, Math.min(10, filter.value().length())));
    } catch (Exception e) {
      return false;
    }
    return switch (filter.operator()) {
      case Constants.OP_BEFORE -> actual.isBefore(wanted);
      case Constants.OP_AFTER -> actual.isAfter(wanted);
      default -> actual.equals(wanted);
    };
  }

  private static boolean compareArray(PropertyFilter filter, Values.Property property) {
    var array = Json.readArray(property.stringValue());
    boolean present = false;
    if (array != null) {
      for (var element : array) {
        if (element.asText().equals(filter.value())) {
          present = true;
          break;
        }
      }
    }
    return filter.operator().equals(Constants.OP_CONTAINS) == present;
  }

  private static boolean compareString(PropertyFilter filter, Values.Property property) {
    var actual = property.stringValue();
    switch (filter.operator()) {
      case Constants.OP_EQUALS:
        return Arrays.asList(filter.value().split(",", -1)).contains(actual);
      case Constants.OP_NOT_EQUALS:
        return !Arrays.asList(filter.value().split(",", -1)).contains(actual);
      case Constants.OP_MATCHES:
        return actual != null
            && Pattern.compile(filter.value(), Pattern.CASE_INSENSITIVE).matcher(actual).find();
      case Constants.OP_DOES_NOT_MATCH:
        return actual == null
            || !Pattern.compile(filter.value(), Pattern.CASE_INSENSITIVE).matcher(actual).find();
      case Constants.OP_CONTAINS:
        return actual != null
            && actual.toLowerCase(Locale.ROOT).contains(filter.value().toLowerCase(Locale.ROOT));
      default:
        return actual == null
            || !actual.toLowerCase(Locale.ROOT).contains(filter.value().toLowerCase(Locale.ROOT));
    }
  }

  public static String stripWww(String host) {
    if (host == null) {
      return null;
    }
    return host.startsWith("www.") ? host.substring(4) : host;
  }
}
