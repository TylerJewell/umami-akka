package io.akka.umami.api;

import static org.junit.jupiter.api.Assertions.fail;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Waiting for the read side to catch up.
 *
 * <p>The original writes an event and reads it back in the same transaction, so a figure asked for
 * immediately after a page view already counts it. Here the fact is durable the moment it is
 * written and the index a query reads is brought up to date just after, so the same question asked
 * in the same millisecond can answer the older number. That is a difference a caller can see and it
 * is declared in the README; every check that writes and then reads waits for the catch-up rather
 * than pretending it does not happen.
 */
public final class Settle {

  private static final Duration LIMIT = Duration.ofSeconds(20);

  /**
   * The wait for a runtime to be up and the first account to exist.
   *
   * <p>Longer than the one above, and for a different reason: an index catching up takes
   * milliseconds, while starting a runtime and running its bootstrap takes seconds and takes
   * more of them on a machine doing something else. Twenty seconds was enough until this
   * project's tests were run beside a build, and then it was not; ninety was enough until they
   * were run beside several. The limit is a backstop against waiting for ever, not an assertion
   * about how long a start takes, so it costs nothing when the machine is idle.
   */
  private static final Duration STARTUP_LIMIT = Duration.ofSeconds(240);

  private static final long STEP_MILLIS = 50;

  private Settle() {}

  /** Runs the read until it satisfies the condition, or fails saying what it last saw. */
  public static <T> T until(Supplier<T> read, java.util.function.Predicate<T> holds,
      String what) {
    return until(read, holds, what, LIMIT);
  }

  /** The same, waiting long enough for a runtime to start rather than for an index to catch up. */
  public static <T> T untilStarted(Supplier<T> read, java.util.function.Predicate<T> holds,
      String what) {
    return until(read, holds, what, STARTUP_LIMIT);
  }

  private static <T> T until(Supplier<T> read, java.util.function.Predicate<T> holds,
      String what, Duration limit) {
    var deadline = System.nanoTime() + limit.toNanos();
    T last = null;
    while (System.nanoTime() < deadline) {
      try {
        last = read.get();
        if (holds.test(last)) {
          return last;
        }
      } catch (RuntimeException notYet) {
        // A service still coming up refuses the connection rather than answering, which is
        // the same "not yet" as an index that has not caught up.
        last = null;
      }
      try {
        Thread.sleep(STEP_MILLIS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    fail("the read side never showed " + what + "; the last answer was " + last);
    return last;
  }
}
