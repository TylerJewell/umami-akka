package io.akka.umami.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;

/**
 * One collected fact: an event, a session, a session property, a revenue record, a heatmap row, a
 * replay chunk or an identity link.
 *
 * <p>Separate from {@link RecordEntity} because the access pattern is the opposite one. An
 * administrative row is read on its own and written rarely; a fact is written once and only ever
 * read as part of a range over a website. Keeping them apart keeps a website's traffic out of the
 * index every account list walks.
 */
@Component(id = "fact")
public class FactEntity extends EventSourcedEntity<Doc.State, Doc.Event> {

  public record Write(String document) {}

  @Override
  public Doc.State emptyState() {
    return Doc.State.empty();
  }

  /** Records a fact. A fact written twice with the same identity overwrites rather than duplicates. */
  public Effect<Doc.State> put(Write command) {
    if (currentState().exists()) {
      return effects().persist(new Doc.Updated(command.document())).thenReply(state -> state);
    }
    return effects()
        .persist(new Doc.Created(commandContext().entityId(), command.document()))
        .thenReply(state -> state);
  }

  /** Read-only, and said so: a region that holds a replica may answer it without the leader. */
  public ReadOnlyEffect<Doc.State> read() {
    return effects().reply(currentState());
  }

  /** Removes a fact: a website reset, a website removal, or a session a person asked to forget. */
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
