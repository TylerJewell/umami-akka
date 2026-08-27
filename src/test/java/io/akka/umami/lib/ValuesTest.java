package io.akka.umami.lib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** SPEC R20 and R21: how an arbitrary property becomes a stored row. */
class ValuesTest {

  private static Values.Property named(List<Values.Property> properties, String key) {
    return properties.stream().filter(p -> p.key().equals(key)).findFirst().orElse(null);
  }

  @Test
  void aNestedObjectIsFlattenedIntoDottedKeys() {
    var properties = Values.flatten(Json.read("{\"a\":{\"b\":{\"c\":1}}}"));
    assertEquals(1, properties.size());
    assertEquals("a.b.c", properties.get(0).key());
  }

  @Test
  void aNumberIsStoredBothWaysAndItsStringHasFourDecimals() {
    var property = named(Values.flatten(Json.read("{\"seats\":3}")), "seats");
    assertEquals(Constants.DATA_NUMBER, property.dataType());
    assertEquals("3.0000", property.stringValue());
    assertEquals(0, property.numberValue().compareTo(new java.math.BigDecimal("3")));
    assertEquals("3", Values.displayValue(property), "the reading strips the convention");
  }

  @Test
  void aBooleanIsStoredAsItsWord() {
    var property = named(Values.flatten(Json.read("{\"ok\":true}")), "ok");
    assertEquals(Constants.DATA_BOOLEAN, property.dataType());
    assertEquals("true", property.stringValue());
  }

  @Test
  void aStringShapedLikeAnInstantIsStoredAsOne() {
    var property = named(Values.flatten(Json.read("{\"at\":\"2026-01-02T03:04:05Z\"}")), "at");
    assertEquals(Constants.DATA_DATE, property.dataType());
    assertEquals(Instant.parse("2026-01-02T03:04:05Z"), property.dateValue());
  }

  @Test
  void anOrdinaryStringIsAString() {
    var property = named(Values.flatten(Json.read("{\"plan\":\"pro\"}")), "plan");
    assertEquals(Constants.DATA_STRING, property.dataType());
    assertEquals("pro", property.stringValue());
    assertNull(property.dateValue());
    assertNull(property.numberValue());
  }

  @Test
  void aListIsStoredAsItsOwnText() {
    var property = named(Values.flatten(Json.read("{\"tags\":[\"a\",\"b\"]}")), "tags");
    assertEquals(Constants.DATA_ARRAY, property.dataType());
    assertEquals("[\"a\",\"b\"]", property.stringValue());
  }

  @Test
  void anOversizedStringIsCutAndAnOversizedListIsNotStoredAtAll() {
    var long_ = "x".repeat(600);
    var property = named(Values.flatten(Json.read("{\"note\":\"" + long_ + "\"}")), "note");
    assertEquals(500, property.stringValue().length());

    var elements = new StringBuilder("[");
    for (int i = 0; i < 200; i++) {
      elements.append(i == 0 ? "" : ",").append("\"element-").append(i).append("\"");
    }
    elements.append("]");
    var list = named(Values.flatten(Json.read("{\"tags\":" + elements + "}")), "tags");
    assertNotNull(list);
    assertNull(list.stringValue(),
        "a truncated list would be text no later reader could take apart");
  }

  @Test
  void aNullIsNotAProperty() {
    assertTrue(Values.flatten(Json.read("{\"a\":null}")).isEmpty());
    assertTrue(Values.flatten(null).isEmpty());
  }

  @Test
  void everyStoredStringIsCutToItsFieldRatherThanRefused() {
    assertEquals(50, Values.truncate("y".repeat(80), "eventName").length());
    assertEquals(11, Values.truncate("1920x1080000", "screen").length());
    assertNull(Values.truncate(null, "eventName"));
    assertEquals("short", Values.truncate("short", "eventName"));
  }
}
