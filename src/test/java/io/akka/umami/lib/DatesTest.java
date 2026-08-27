package io.akka.umami.lib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** SPEC R32 to R36: ranges, buckets, and the units a span may be read at. */
class DatesTest {

  private static final Instant NOW = Instant.parse("2026-07-24T12:00:00Z");

  @Test
  void aNamedRangeIsParsedIntoACountAndAUnit() {
    assertEquals(new Dates.Parsed(1, "hour"), Dates.parseDateValue("1hour"));
    assertEquals(new Dates.Parsed(24, "hour"), Dates.parseDateValue("24hour"));
    assertEquals(new Dates.Parsed(7, "day"), Dates.parseDateValue("7day"));
    assertEquals(new Dates.Parsed(1, "week"), Dates.parseDateValue("1week"));
    assertEquals(new Dates.Parsed(12, "month"), Dates.parseDateValue("12month"));
    assertEquals(new Dates.Parsed(1, "year"), Dates.parseDateValue("1year"));
    assertEquals(new Dates.Parsed(0, "day"), Dates.parseDateValue("0day"));
  }

  @Test
  void aValueThatIsNotOneOfTheFiveUnitsIsNotARange() {
    assertNull(Dates.parseDateValue("1minute"));
    assertNull(Dates.parseDateValue("1decade"));
    assertNull(Dates.parseDateValue("range:1:2"));
    assertNull(Dates.parseDateValue(""));
    assertNull(Dates.parseDateValue("day"));
    assertNull(Dates.parseDateValue(null));
  }

  @Test
  void twentyFourHoursRunsFromTheStartOfTheHourTwentyFourBack() {
    var range = Dates.parseDateRange("24hour", null, "UTC", NOW);
    assertEquals(Instant.parse("2026-07-23T12:00:00Z"), range.startDate());
    assertEquals("hour", range.unit());
    assertTrue(range.endDate().isAfter(Instant.parse("2026-07-24T12:59:00Z")));
  }

  @Test
  void sevenDaysRunsFromTheStartOfTheDaySevenBack() {
    var range = Dates.parseDateRange("7day", null, "UTC", NOW);
    assertEquals(Instant.parse("2026-07-17T00:00:00Z"), range.startDate());
    assertEquals("day", range.unit());
  }

  @Test
  void aCountOfZeroMeansThisPeriodAndTakesTheNextFinerUnit() {
    assertEquals("hour", Dates.parseDateRange("0day", null, "UTC", NOW).unit());
    assertEquals("day", Dates.parseDateRange("0month", null, "UTC", NOW).unit());
    assertEquals(Instant.parse("2026-07-24T00:00:00Z"),
        Dates.parseDateRange("0day", null, "UTC", NOW).startDate());
    assertEquals(Instant.parse("2026-07-01T00:00:00Z"),
        Dates.parseDateRange("0month", null, "UTC", NOW).startDate());
  }

  @Test
  void aRequestedUnitOverridesTheOneTheRangeWouldHaveChosen() {
    assertEquals("week", Dates.parseDateRange("7day", "week", "UTC", NOW).unit());
  }

  @Test
  void anExplicitRangeCarriesItsOwnBoundsAndTakesTheMinimumUnit() {
    var start = Instant.parse("2026-07-20T00:00:00Z");
    var end = Instant.parse("2026-07-24T00:00:00Z");
    var range =
        Dates.parseDateRange("range:" + start.toEpochMilli() + ":" + end.toEpochMilli(), null,
            "UTC", NOW);
    assertEquals(start, range.startDate());
    assertEquals(end, range.endDate());
    assertEquals("day", range.unit(), "beyond forty-eight hours a named range reads by day");
  }

  @Test
  void theMinimumUnitFollowsTheSpan() {
    assertEquals("minute",
        Dates.getMinimumUnit(NOW, NOW.plusSeconds(3600), false));
    assertEquals("hour",
        Dates.getMinimumUnit(NOW, NOW.plusSeconds(29L * 24 * 3600), false));
    assertEquals("day",
        Dates.getMinimumUnit(NOW, NOW.plusSeconds(120L * 24 * 3600), false));
    assertEquals("month",
        Dates.getMinimumUnit(NOW, NOW.plusSeconds(600L * 24 * 3600), false));
    assertEquals("year",
        Dates.getMinimumUnit(NOW, NOW.plusSeconds(1000L * 24 * 3600), false));
  }

  @Test
  void anExplicitRangeCapsTheHourlyReadingAtFortyEightHoursRatherThanThirtyDays() {
    var span = NOW.plusSeconds(60L * 3600);
    assertEquals("hour", Dates.getMinimumUnit(NOW, span, false));
    assertEquals("day", Dates.getMinimumUnit(NOW, span, true));
  }

  @Test
  void theAllowedUnitsAreTheMinimumAndEverythingCoarser() {
    assertEquals(List.of("minute", "hour", "day", "month", "year"),
        Dates.getAllowedUnits(NOW, NOW.plusSeconds(600)));
    assertEquals(List.of("day", "month", "year"),
        Dates.getAllowedUnits(NOW, NOW.plusSeconds(120L * 24 * 3600)));
    assertEquals(List.of("month", "year"),
        Dates.getAllowedUnits(NOW, NOW.plusSeconds(1000L * 24 * 3600)),
        "a minimum of year is read down to month first");
  }

  @Test
  void aBucketWithNoZoneCarriesAZoneMarkerAndOneWithAZoneDoesNot() {
    var instant = Instant.parse("2026-07-24T13:37:00Z");
    assertEquals("2026-07-24T13:00:00Z", Dates.bucket(instant, "hour", null));
    assertEquals("2026-07-24T13:00:00Z", Dates.bucket(instant, "hour", "utc"));
    assertEquals("2026-07-24T13:00:00Z", Dates.bucket(instant, "hour", "UTC"));
    assertEquals("2026-07-24 09:00:00", Dates.bucket(instant, "hour", "America/New_York"));
  }

  @Test
  void aDayBucketIsFormattedWithItsHourAtZero() {
    var instant = Instant.parse("2026-07-24T13:37:00Z");
    assertEquals("2026-07-24T00:00:00Z", Dates.bucket(instant, "day", null));
    assertEquals("2026-07-01T00:00:00Z", Dates.bucket(instant, "month", null));
    assertEquals("2026-01-01T00:00:00Z", Dates.bucket(instant, "year", null));
    assertEquals("2026-07-24T13:37:00Z", Dates.bucket(instant, "minute", null));
  }

  @Test
  void theBucketListIsDenseSoAnEmptyPeriodIsARowRatherThanAbsent() {
    var buckets =
        Dates.buckets(Instant.parse("2026-07-24T00:00:00Z"),
            Instant.parse("2026-07-24T05:00:00Z"), "hour", null);
    assertEquals(6, buckets.size());
    assertEquals("2026-07-24T00:00:00Z", buckets.get(0));
    assertEquals("2026-07-24T05:00:00Z", buckets.get(5));
  }

  @Test
  void theComparisonWindowIsTheSpanBeforeOrTheSameSpanAYearBack() {
    var start = Instant.parse("2026-07-17T00:00:00Z");
    var end = Instant.parse("2026-07-24T00:00:00Z");
    var previous = Dates.compareRange("prev", start, end);
    assertEquals(Instant.parse("2026-07-10T00:00:00Z"), previous.startDate());
    assertEquals(start, previous.endDate(), "the earlier window ends where this one begins");

    var yearOnYear = Dates.compareRange("yoy", start, end);
    assertEquals(Instant.parse("2025-07-17T00:00:00Z"), yearOnYear.startDate());
    assertEquals(Instant.parse("2025-07-24T00:00:00Z"), yearOnYear.endDate());

    assertNull(Dates.compareRange("nonsense", start, end));
  }

  @Test
  void oneZoneNameIsReadAsAnother() {
    assertEquals("Asia/Kolkata", Dates.normalizeTimezone("Asia/Calcutta"));
    assertEquals("America/New_York", Dates.normalizeTimezone("America/New_York"));
    assertTrue(Dates.isValidTimezone("Asia/Calcutta"));
    assertTrue(Dates.isValidTimezone("America/New_York"));
    assertFalse(Dates.isValidTimezone("Not/AZone"));
  }

  @Test
  void theWeeklyKeyIsTheDayOfTheWeekAndTheHour() {
    // The twenty-fourth of July 2026 is a Friday, which is day five counting from Sunday.
    assertEquals("5:13", Dates.weeklyKey(Instant.parse("2026-07-24T13:37:00Z"), "UTC"));
    assertEquals("5:09", Dates.weeklyKey(Instant.parse("2026-07-24T13:37:00Z"),
        "America/New_York"));
  }
}
