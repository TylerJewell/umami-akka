package io.akka.umami.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.akka.umami.lib.Env;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The commands, driven without a service behind them where they do not need one. */
class CliTest {

  private final ByteArrayOutputStream out = new ByteArrayOutputStream();
  private final ByteArrayOutputStream err = new ByteArrayOutputStream();

  private Cli cli() {
    return new Cli(new PrintStream(out, true, StandardCharsets.UTF_8),
        new PrintStream(err, true, StandardCharsets.UTF_8), "http://127.0.0.1:1");
  }

  private String written() {
    return out.toString(StandardCharsets.UTF_8);
  }

  private String complained() {
    return err.toString(StandardCharsets.UTF_8);
  }

  @AfterEach
  void clear() {
    Env.clearOverrides();
  }

  @Test
  void withNoCommandItSaysWhatItCanDoAndFails() {
    assertEquals(1, cli().run(new String[] {}));
    assertTrue(written().contains("check-env"));
    assertTrue(written().contains("change-password"));
    assertTrue(written().contains("seed-data"));
  }

  @Test
  void askingForHelpIsNotAFailure() {
    assertEquals(0, cli().run(new String[] {"--help"}));
  }

  @Test
  void anUnknownCommandIsRefusedByName() {
    assertEquals(1, cli().run(new String[] {"nonsense"}));
    assertTrue(complained().contains("unknown command: nonsense"));
  }

  @Test
  void theVersionIsTheOneTheServiceReports() {
    assertEquals(0, cli().run(new String[] {"version"}));
    assertEquals("3.3.1", written().trim());
  }

  @Test
  void aMissingSecretIsRefusedRatherThanWarnedAbout() {
    Env.override("APP_SECRET", "");
    assertEquals(1, cli().run(new String[] {"check-env"}));
    assertTrue(written().contains("MISSING  APP_SECRET"));
  }

  @Test
  void everySettingIsReportedWhetherItIsSetOrNot() {
    Env.override("APP_SECRET", "something");
    assertEquals(0, cli().run(new String[] {"check-env"}));
    assertTrue(written().contains("set      APP_SECRET"));
    assertTrue(written().contains("unset    IGNORE_IP"));
    assertTrue(written().contains("Every required setting is present."));
  }

  @Test
  void aServiceThatDoesNotAnswerIsReportedRatherThanThrown() {
    assertEquals(1, cli().run(new String[] {"check-db"}));
    assertTrue(complained().contains("did not answer"));
  }

  @Test
  void aShortPasswordIsRefusedBeforeAnythingIsAsked() {
    assertEquals(1, cli().run(new String[] {"change-password", "admin", "short"}));
    assertTrue(complained().contains("at least eight characters"));
  }

  @Test
  void thePasswordChangeNamesItsArguments() {
    assertEquals(1, cli().run(new String[] {"change-password"}));
    assertTrue(complained().contains("usage: change-password"));
  }

  @Test
  void seedingRefusesADayCountThatIsNotPositive() {
    assertEquals(1, cli().run(new String[] {"seed-data", "--days", "0"}));
    assertTrue(complained().contains("positive"));
  }

  @Test
  void seedingRefusesAnArgumentItDoesNotKnow() {
    assertEquals(1, cli().run(new String[] {"seed-data", "--nonsense"}));
    assertTrue(complained().contains("unknown argument"));
  }

  @Test
  void theTrackerIsLeftAloneWhenNoEndpointIsConfigured() throws Exception {
    var script = Files.createTempFile("script", ".js");
    Files.writeString(script, "fetch('/api/send')");
    assertEquals(0, cli().run(new String[] {"update-tracker", script.toString()}));
    assertEquals("fetch('/api/send')", Files.readString(script));
    Files.deleteIfExists(script);
  }

  @Test
  void aConfiguredEndpointIsWrittenIntoTheBuiltTracker() throws Exception {
    Env.override("COLLECT_API_ENDPOINT", "/collect");
    var script = Files.createTempFile("script", ".js");
    Files.writeString(script, "fetch('/api/send');fetch('/api/send')");
    assertEquals(0, cli().run(new String[] {"update-tracker", script.toString()}));
    assertEquals("fetch('/collect');fetch('/collect')", Files.readString(script),
        "every occurrence, not the first");
    assertTrue(written().contains("Updated tracker endpoint: /collect."));
    Files.deleteIfExists(script);
  }

  @Test
  void rewritingATrackerThatIsNotThereIsReportedRatherThanThrown() {
    Env.override("COLLECT_API_ENDPOINT", "/collect");
    assertEquals(1, cli().run(new String[] {"update-tracker", "no-such-file.js"}));
    assertTrue(complained().contains("Could not rewrite"));
  }
}
