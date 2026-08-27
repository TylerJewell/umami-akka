package io.akka.umami.lib;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Ranges, buckets and the units a span is allowed to be read at. */
public final class Dates {

  private Dates() {}

  private static final Pattern RANGE_VALUE = Pattern.compile("^(?<num>[0-9-]+)(?<unit>hour|day|week|month|year)$");

  private static final Map<String, String> ZONE_ALIASES = Map.of("Asia/Calcutta", "Asia/Kolkata");

  /** The bucket format for a named zone: a local stamp with no zone marker. */
  private static final Map<String, String> ZONED_FORMATS =
      Map.of(
          "minute", "yyyy-MM-dd HH:mm:00",
          "hour", "yyyy-MM-dd HH:00:00",
          "day", "yyyy-MM-dd HH:00:00",
          "week", "yyyy-MM-dd HH:00:00",
          "month", "yyyy-MM-01 HH:00:00",
          "year", "yyyy-01-01 HH:00:00");

  /** The bucket format with no zone: an instant that says so. */
  private static final Map<String, String> UTC_FORMATS =
      Map.of(
          "minute", "yyyy-MM-dd'T'HH:mm:00'Z'",
          "hour", "yyyy-MM-dd'T'HH:00:00'Z'",
          "day", "yyyy-MM-dd'T'HH:00:00'Z'",
          "week", "yyyy-MM-dd'T'HH:00:00'Z'",
          "month", "yyyy-MM-01'T'HH:00:00'Z'",
          "year", "yyyy-01-01'T'HH:00:00'Z'");

  public record Range(Instant startDate, Instant endDate, String unit, Integer num, String value) {}

  public record Parsed(int num, String unit) {}

  public static String normalizeTimezone(String timezone) {
    if (timezone == null) {
      return null;
    }
    return ZONE_ALIASES.getOrDefault(timezone, timezone);
  }

  public static boolean isValidTimezone(String timezone) {
    if (timezone == null) {
      return false;
    }
    try {
      ZoneId.of(normalizeTimezone(timezone));
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  public static ZoneId zone(String timezone) {
    if (timezone == null || timezone.equalsIgnoreCase("utc")) {
      return ZoneId.of("UTC");
    }
    return ZoneId.of(normalizeTimezone(timezone));
  }

  /** Whether a bucket is formatted as an instant rather than as a local stamp. */
  public static boolean isUtcBucket(String timezone) {
    return timezone == null || timezone.isBlank() || timezone.equalsIgnoreCase("utc");
  }

  /** {@code 24hour}, {@code 7day} and so on; a value the pattern does not match answers null. */
  public static Parsed parseDateValue(String value) {
    if (value == null) {
      return null;
    }
    var match = RANGE_VALUE.matcher(value);
    if (!match.matches()) {
      return null;
    }
    try {
      return new Parsed(Integer.parseInt(match.group("num")), match.group("unit"));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * A named range, or an explicit one written {@code range:<startMillis>:<endMillis>}. A zero count
   * means the current period and takes the next finer unit.
   */
  public static Range parseDateRange(String value, String unitValue, String timezone, Instant now) {
    if (value == null) {
      return null;
    }
    if (value.startsWith("range")) {
      var parts = value.split(":");
      if (parts.length < 3) {
        return null;
      }
      var start = Instant.ofEpochMilli(Long.parseLong(parts[1]));
      var end = Instant.ofEpochMilli(Long.parseLong(parts[2]));
      return new Range(start, end, getMinimumUnit(start, end, true), null, value);
    }
    var parsed = parseDateValue(value);
    if (parsed == null) {
      return null;
    }
    var zone = zone(timezone);
    var here = now.atZone(zone);
    int num = parsed.num();
    ZonedDateTime start;
    ZonedDateTime end;
    String unit;
    switch (parsed.unit()) {
      case "hour" -> {
        var base = here.truncatedTo(ChronoUnit.HOURS);
        start = num != 0 ? base.minusHours(num) : base;
        end = base.plusHours(1).minusNanos(1);
        unit = unitValue != null ? unitValue : "hour";
      }
      case "day" -> {
        var base = here.truncatedTo(ChronoUnit.DAYS);
        start = num != 0 ? base.minusDays(num) : base;
        end = base.plusDays(1).minusNanos(1);
        unit = unitValue != null ? unitValue : (num != 0 ? "day" : "hour");
      }
      case "week" -> {
        var base = here.truncatedTo(ChronoUnit.DAYS).minusDays(here.getDayOfWeek().getValue() % 7);
        start = num != 0 ? base.minusWeeks(num) : base;
        end = base.plusWeeks(1).minusNanos(1);
        unit = unitValue != null ? unitValue : "day";
      }
      case "month" -> {
        var base = here.truncatedTo(ChronoUnit.DAYS).withDayOfMonth(1);
        start = num != 0 ? base.minusMonths(num) : base;
        end = base.plusMonths(1).minusNanos(1);
        unit = unitValue != null ? unitValue : (num != 0 ? "month" : "day");
      }
      case "year" -> {
        var base = here.truncatedTo(ChronoUnit.DAYS).withDayOfYear(1);
        start = num != 0 ? base.minusYears(num) : base;
        end = base.plusYears(1).minusNanos(1);
        unit = unitValue != null ? unitValue : "month";
      }
      default -> {
        return null;
      }
    }
    return new Range(start.toInstant(), end.toInstant(), unit, num == 0 ? 1 : num, value);
  }

  /** The finest unit a span may be read at. */
  public static String getMinimumUnit(Instant start, Instant end, boolean isNamedRange) {
    long minutes = Duration.between(start, end).toMinutes();
    if (minutes <= 60) {
      return "minute";
    }
    if (isNamedRange) {
      if (Duration.between(start, end).toHours() <= 48) {
        return "hour";
      }
    } else if (Duration.between(start, end).toDays() <= 30) {
      return "hour";
    }
    long months = calendarMonths(start, end);
    if (months <= 7) {
      return "day";
    }
    if (months <= 24) {
      return "month";
    }
    return "year";
  }

  private static long calendarMonths(Instant start, Instant end) {
    var a = start.atZone(ZoneId.of("UTC"));
    var b = end.atZone(ZoneId.of("UTC"));
    return (b.getYear() - a.getYear()) * 12L + (b.getMonthValue() - a.getMonthValue());
  }

  /** The minimum unit and every coarser one, with a minimum of year read down to month first. */
  public static List<String> getAllowedUnits(Instant start, Instant end) {
    var units = List.of("minute", "hour", "day", "month", "year");
    var minimum = getMinimumUnit(start, end, false);
    if (minimum.equals("year")) {
      minimum = "month";
    }
    int index = units.indexOf(minimum);
    return units.subList(index, units.size());
  }

  /** The window a comparison is drawn against: the previous span, or the same span a year back. */
  public static Range compareRange(String compare, Instant start, Instant end) {
    if ("yoy".equals(compare)) {
      return new Range(
          start.atZone(ZoneId.of("UTC")).minusYears(1).toInstant(),
          end.atZone(ZoneId.of("UTC")).minusYears(1).toInstant(),
          null,
          null,
          compare);
    }
    if ("prev".equals(compare)) {
      long minutes = Duration.between(start, end).toMinutes();
      return new Range(
          start.minus(minutes, ChronoUnit.MINUTES), end.minus(minutes, ChronoUnit.MINUTES), null,
          null, compare);
    }
    return null;
  }

  /** The label an instant falls under, for the given unit and zone. */
  public static String bucket(Instant instant, String unit, String timezone) {
    var zone = zone(timezone);
    var truncated = truncate(instant.atZone(zone), unit);
    var pattern =
        isUtcBucket(timezone)
            ? UTC_FORMATS.getOrDefault(unit, UTC_FORMATS.get("day"))
            : ZONED_FORMATS.getOrDefault(unit, ZONED_FORMATS.get("day"));
    return DateTimeFormatter.ofPattern(pattern, Locale.US).format(truncated);
  }

  /**
   * An instant written out to the second, which is not a bucket: nothing is truncated.
   *
   * <p>A named zone reads it as local wall-clock with no zone marker; UTC, or no zone at all,
   * reads it as an instant that says so. The two are different strings for the same moment, and a
   * caller grouping on them groups differently depending on which it asked for.
   */
  public static String toSecond(Instant instant, String timezone) {
    if (instant == null) {
      return null;
    }
    var pattern =
        isUtcBucket(timezone) ? "yyyy-MM-dd'T'HH:mm:ss'Z'" : "yyyy-MM-dd'T'HH:mm:ss";
    return DateTimeFormatter.ofPattern(pattern, Locale.US)
        .format(instant.atZone(zone(timezone)));
  }

  public static ZonedDateTime truncate(ZonedDateTime when, String unit) {
    return switch (unit) {
      case "minute" -> when.truncatedTo(ChronoUnit.MINUTES);
      case "hour" -> when.truncatedTo(ChronoUnit.HOURS);
      case "week" -> when.truncatedTo(ChronoUnit.DAYS).minusDays(when.getDayOfWeek().getValue() % 7);
      case "month" -> when.truncatedTo(ChronoUnit.DAYS).withDayOfMonth(1);
      case "year" -> when.truncatedTo(ChronoUnit.DAYS).withDayOfYear(1);
      default -> when.truncatedTo(ChronoUnit.DAYS);
    };
  }

  public static ZonedDateTime advance(ZonedDateTime when, String unit) {
    return switch (unit) {
      case "minute" -> when.plusMinutes(1);
      case "hour" -> when.plusHours(1);
      case "week" -> when.plusWeeks(1);
      case "month" -> when.plusMonths(1);
      case "year" -> when.plusYears(1);
      default -> when.plusDays(1);
    };
  }

  /** Every bucket label between the two instants, inclusive, so a gap is a row rather than absent. */
  public static List<String> buckets(Instant start, Instant end, String unit, String timezone) {
    var zone = zone(timezone);
    var cursor = truncate(start.atZone(zone), unit);
    var last = truncate(end.atZone(zone), unit);
    var out = new ArrayList<String>();
    while (!cursor.isAfter(last)) {
      out.add(bucket(cursor.toInstant(), unit, timezone));
      cursor = advance(cursor, unit);
      if (out.size() > 100000) {
        break;
      }
    }
    return out;
  }

  /** The weekly-traffic key: the day of the week and the hour, in the requested zone. */
  public static String weeklyKey(Instant instant, String timezone) {
    var zone = timezone == null ? ZoneId.of("UTC") : ZoneId.of(normalizeTimezone(timezone));
    var when = instant.atZone(zone);
    int dayOfWeek = when.getDayOfWeek().getValue() % 7;
    return dayOfWeek + ":" + String.format("%02d", when.getHour());
  }

  public static String isoWeek(ZonedDateTime when) {
    return when.get(IsoFields.WEEK_BASED_YEAR) + "-W" + String.format("%02d",
        when.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
  }

  public static LocalDate localDate(Instant instant, String timezone) {
    return instant.atZone(zone(timezone)).toLocalDate();
  }
}
