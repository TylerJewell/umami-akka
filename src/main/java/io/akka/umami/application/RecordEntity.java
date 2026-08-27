package io.akka.umami.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;

/**
 * One row of one of umami's administrative tables: an account, a team, a membership, a website, a
 * link, a pixel, a board, a saved report, a segment, a share, a second factor, a stored sign-in or
 * a setting.
 *
 * <p>Identity is {@code <kind>:<id>}, so a write to a website never contends with a write to the
 * account that owns it. It is event-sourced rather than key-value because a removal has to reach
 * the read side as a change rather than as an absence: several of umami's lists are defined by what
 * is *not* removed, and a view row that simply stopped arriving would leave the last state behind.
 */
@Component(id = "record")
public class RecordEntity extends EventSourcedEntity<Doc.State, Doc.Event> {

  /**
   * The document of a write, wrapped.
   *
   * <p>A bare string cannot be a command type: the wire serializer records the concrete class it
   * was handed, and a string arrives at the runtime as a value with no type of its own.
   */
  public record Write(String document) {}

  @Override
  public Doc.State emptyState() {
    return Doc.State.empty();
  }

  /** Writes a record whether or not it is there. Re-sending a create overwrites, as a caller expects. */
  public Effect<Doc.State> put(Write command) {
    if (currentState().exists()) {
      return effects().persist(new Doc.Updated(command.document())).thenReply(state -> state);
    }
    return effects()
        .persist(new Doc.Created(commandContext().entityId(), command.document()))
        .thenReply(state -> state);
  }

  /** Moves a record that is already there. */
  public Effect<Doc.State> update(Write command) {
    if (!currentState().exists()) {
      return effects().error("not found");
    }
    return effects().persist(new Doc.Updated(command.document())).thenReply(state -> state);
  }

  /** Read-only, and said so: a region that holds a replica may answer it without the leader. */
  public ReadOnlyEffect<Doc.State> read() {
    return effects().reply(currentState());
  }

  /** Removes a record. Removing one that is not there is not an error; the caller wanted it gone. */
  public Effect<Doc.State> remove() {
    if (!currentState().exists()) {
      return effects().reply(currentState());
    }
    return effects().persist(new Doc.Deleted()).thenReply(state -> state);
  }

  @Override
  public Doc.State applyEvent(Doc.Event event) {
    return switch (event) {
      case Doc.Created e -> new Doc.State(e.key(), e.document(), false);
      case Doc.Updated e -> new Doc.State(currentState().key(), e.document(), false);
      case Doc.Deleted ignored ->
          new Doc.State(currentState().key(), currentState().document(), true);
    };
  }
}
