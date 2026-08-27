package io.akka.umami.lib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** SPEC R54 to R60: what a filter means, and what it does not. */
class FiltersTest {

  private static Filters.Row row(Map<String, String> columns) {
    return columns::get;
  }

  private static Filters.Query query(List<Filters.Clause> clauses, String match) {
    return new Filters.Query(Instant.EPOCH, Instant.now(), null, "day", clauses, match, List.of(),
        List.of(), false, null, null, 1, 20, null, null, null, null, null, null);
  }

  @Test
  void aValueMayCarryItsOperatorInFront() {
    var clause = Filters.parseClause("browser", "eq.chrome");
    assertEquals("eq", clause.operator());
    assertEquals(List.of("chrome"), clause.values());

    var plain = Filters.parseClause("browser", "chrome");
    assertEquals("eq", plain.operator());
    assertEquals(List.of("chrome"), plain.values());

    var contains = Filters.parseClause("path", "c.blog");
    assertEquals("c", contains.operator());
    assertEquals(List.of("blog"), contains.values());
  }

  @Test
  void aValueThatIsNotAnOperatorPrefixIsKeptWhole() {
    var clause = Filters.parseClause("path", "/about.html");
    assertEquals("eq", clause.operator());
    assertEquals(List.of("/about.html"), clause.values());
  }

  @Test
  void aRepeatCarriesANumericSuffixAndKeepsItsOwnName() {
    var clause = Filters.parseClause("browser2", "eq.firefox");
    assertEquals("browser2", clause.name());
    assertEquals("browser", clause.baseName());
    assertEquals("browser", clause.column());
  }

  @Test
  void aNameThatIsNotADimensionIsNotAFilter() {
    assertNull(Filters.parseClause("nonsense", "eq.value"));
  }

  @Test
  void equalityAndInequalitySplitTheirValueOnCommas() {
    var clause = Filters.parseClause("browser", "eq.chrome,firefox");
    assertEquals(List.of("chrome", "firefox"), clause.values());
    assertTrue(Filters.matchesClause(clause, row(Map.of("browser", "firefox"))));
    assertFalse(Filters.matchesClause(clause, row(Map.of("browser", "safari"))));

    var not = Filters.parseClause("browser", "neq.chrome,firefox");
    assertFalse(Filters.matchesClause(not, row(Map.of("browser", "chrome"))));
    assertTrue(Filters.matchesClause(not, row(Map.of("browser", "safari"))));
  }

  @Test
  void containsAndItsNegationIgnoreCase() {
    var contains = Filters.parseClause("path", "c.BLOG");
    assertTrue(Filters.matchesClause(contains, row(Map.of("path", "/blog/one"))));
    assertFalse(Filters.matchesClause(contains, row(Map.of("path", "/about"))));

    var does = Filters.parseClause("path", "dnc.blog");
    assertFalse(Filters.matchesClause(does, row(Map.of("path", "/blog/one"))));
    assertTrue(Filters.matchesClause(does, row(Map.of("path", "/about"))));
  }

  @Test
  void aPatternMatchIgnoresCaseAndNeedNotAnchor() {
    var matches = Filters.parseClause("path", "re.^/BLOG");
    assertTrue(Filters.matchesClause(matches, row(Map.of("path", "/blog/one"))));
    var not = Filters.parseClause("path", "nre.^/blog");
    assertFalse(Filters.matchesClause(not, row(Map.of("path", "/blog/one"))));
    assertTrue(Filters.matchesClause(not, row(Map.of("path", "/about"))));
  }

  @Test
  void tenOperatorsMeanNothingOnADimensionAndLeaveTheQueryMalformed() {
    for (var operator : Constants.OPERATORS_WITHOUT_A_DIMENSION_MEANING) {
      var clause = Filters.parseClause("path", operator + "./x");
      assertThrows(
          Filters.MalformedFilter.class,
          () -> Filters.matchesClause(clause, row(Map.of("path", "/x"))),
          operator + " produces no condition");
    }
  }

  @Test
  void matchingAnyPutsTheEligibleClausesIntoOneDisjunction() {
    var clauses =
        List.of(Filters.parseClause("path", "eq./a"), Filters.parseClause("path1", "eq./b"));
    var any = query(clauses, "any");
    assertTrue(Filters.matches(any, row(Map.of("path", "/a"))));
    assertTrue(Filters.matches(any, row(Map.of("path", "/b"))));
    assertFalse(Filters.matches(any, row(Map.of("path", "/c"))));

    var all = query(clauses, "all");
    assertFalse(Filters.matches(all, row(Map.of("path", "/a"))),
        "conjoined, the same pair can never both hold");
  }

  @Test
  void aFilterOnTheReferrerAlsoExcludesASelfReferral() {
    var clauses = List.of(Filters.parseClause("referrer", "c.example"));
    var filters = query(clauses, "all");
    assertTrue(Filters.matches(filters,
        row(Map.of("referrer", "example.org", "hostname", "example.com"))));
    assertFalse(Filters.matches(filters,
        row(Map.of("referrer", "example.com", "hostname", "www.example.com"))),
        "the leading www is removed before the comparison");
  }

  @Test
  void aPropertyFilterComparesByTheTypeItDeclares() {
    var number = new Values.Property("amount", "12.0000", new BigDecimal("12"), null,
        Constants.DATA_NUMBER);
    assertTrue(Filters.matchesProperty(
        new Filters.PropertyFilter("amount", Constants.DATA_NUMBER, "gt", "10"), number, null));
    assertFalse(Filters.matchesProperty(
        new Filters.PropertyFilter("amount", Constants.DATA_NUMBER, "gt", "20"), number, null));
    assertTrue(Filters.matchesProperty(
        new Filters.PropertyFilter("amount", Constants.DATA_NUMBER, "lte", "12"), number, null));

    var text = new Values.Property("plan", "pro", null, null, Constants.DATA_STRING);
    assertTrue(Filters.matchesProperty(
        new Filters.PropertyFilter("plan", Constants.DATA_STRING, "eq", "pro,plus"), text, null));
    assertTrue(Filters.matchesProperty(
        new Filters.PropertyFilter("plan", Constants.DATA_STRING, "c", "PR"), text, null));

    var when = new Values.Property("at", "2026-01-02T03:04:05Z", null,
        Instant.parse("2026-01-02T03:04:05Z"), Constants.DATA_DATE);
    assertTrue(Filters.matchesProperty(
        new Filters.PropertyFilter("at", Constants.DATA_DATE, "bf", "2026-01-03"), when, "UTC"));
    assertFalse(Filters.matchesProperty(
        new Filters.PropertyFilter("at", Constants.DATA_DATE, "af", "2026-01-03"), when, "UTC"));

    var list = new Values.Property("tags", "[\"a\",\"b\"]", null, null, Constants.DATA_ARRAY);
    assertTrue(Filters.matchesProperty(
        new Filters.PropertyFilter("tags", Constants.DATA_ARRAY, "c", "a"), list, null));
    assertFalse(Filters.matchesProperty(
        new Filters.PropertyFilter("tags", Constants.DATA_ARRAY, "c", "z"), list, null));
  }

  @Test
  void aPropertyFilterOfADifferentTypeNeverMatches() {
    var text = new Values.Property("plan", "pro", null, null, Constants.DATA_STRING);
    assertFalse(Filters.matchesProperty(
        new Filters.PropertyFilter("plan", Constants.DATA_NUMBER, "eq", "pro"), text, null));
  }

  @Test
  void theUniversalPropertyFormIsTypeOperatorNameValue() {
    var parsed =
        Filters.parseUniversalPropertyFilters(Map.of("epf0", "1.eq.plan.pro", "epf1",
            "2.gt.amount.10"), "epf");
    assertEquals(2, parsed.size());
    assertEquals("plan", parsed.get(0).propertyName());
    assertEquals(Constants.DATA_STRING, parsed.get(0).dataType());
    assertEquals("eq", parsed.get(0).operator());
    assertEquals("pro", parsed.get(0).value());
    assertEquals("amount", parsed.get(1).propertyName());
    assertEquals(Constants.DATA_NUMBER, parsed.get(1).dataType());
  }

  @Test
  void aMalformedPropertyFilterIsDroppedRatherThanRefused() {
    assertTrue(Filters.parseUniversalPropertyFilters(Map.of("epf0", "nonsense"), "epf").isEmpty());
    assertTrue(
        Filters.parseUniversalPropertyFilters(Map.of("epf0", "1.zz.plan.pro"), "epf").isEmpty());
  }

  @Test
  void theScopedPropertyFormMayLeaveOutItsType() {
    var parsed = Filters.parseScopedPropertyFilters(Map.of("pf_plan", "eq.pro"), "pf");
    assertEquals(1, parsed.size());
    assertEquals("plan", parsed.get(0).propertyName());
    assertEquals(Constants.DATA_STRING, parsed.get(0).dataType());
    assertEquals("pro", parsed.get(0).value());
  }

  @Test
  void theEventTypeNeverJoinsADisjunction() {
    var clauses =
        List.of(Filters.parseClause("path", "eq./a"), Filters.parseClause("eventType", "eq.1"));
    var any = query(clauses, "any");
    assertFalse(Filters.matches(any, row(Map.of("path", "/a", "eventType", "2"))),
        "the type is conjoined even under an any match");
    assertTrue(Filters.matches(any, row(Map.of("path", "/a", "eventType", "1"))));
  }
}
