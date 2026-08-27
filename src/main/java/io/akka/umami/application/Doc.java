package io.akka.umami.application;

import akka.javasdk.annotations.TypeName;

/**
 * A stored record, as the entities hold it.
 *
 * <p>The document travels as text rather than as a typed record. That is a decision worth stating:
 * umami has twenty-five tables whose rows are field lists with almost no behaviour, and the rules
 * about them live in the endpoints and in {@code io.akka.umami.analytics}. Twenty-five entity
 * classes would differ only in their name. Identity is namespaced instead — {@code user:<id>},
 * {@code website:<id>}, {@code share:<slug>} — which keeps every row addressable on its own.
 */
public sealed interface Doc {

  /** The state one record is in. A removed record keeps its row so a read answers "gone". */
  record State(String key, String document, boolean deleted) implements Doc {

    public static State empty() {
      return new State(null, null, false);
    }

    public boolean exists() {
      return document != null && !deleted;
    }
  }

  sealed interface Event extends Doc {}

  @TypeName("created")
  record Created(String key, String document) implements Event {}

  @TypeName("updated")
  record Updated(String document) implements Event {}

  @TypeName("deleted")
  record Deleted() implements Event {}
}
