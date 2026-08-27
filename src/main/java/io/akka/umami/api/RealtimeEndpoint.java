package io.akka.umami.api;

import akka.NotUsed;
import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.HttpResponses;
import akka.stream.javadsl.Source;
import com.fasterxml.jackson.databind.JsonNode;
import io.akka.umami.lib.Constants;
import io.akka.umami.lib.Json;
import io.akka.umami.lib.Responses;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * What is happening now, and the two streams a page watches it through.
 *
 * <p>The original asks again on a timer — ten seconds for the live screen, sixty for the badge in
 * the header. Here the page subscribes instead, and the server sends only when the answer has
 * changed. That is a change a caller can see and it is declared as one: a poller re-reads whole
 * state and so misses nothing across a gap, while a stream that drops has to be caught up on
 * reconnection. This one is caught up by sending the current answer in full as its first element,
 * so a page that reconnects converges rather than reconstructing a backlog. RENDERING R1, R2.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint
public class RealtimeEndpoint extends Api {

  /** How often the stream looks for a change. RENDERING R1.2 asks for under 250 ms at the 95th. */
  private static final Duration TICK = Duration.ofMillis(100);

  public RealtimeEndpoint(ComponentClient client) {
    super(client);
  }

  @Get("/api/realtime/{websiteId}")
  public HttpResponse realtime(String websiteId) {
    return answer(() -> {
      var caller = caller();
      require(permissions.canViewWebsiteSection(caller, websiteId, List.of("realtime")));
      return Responses.json(currentRealtime(websiteId));
    });
  }

  /** The window is forced to the last half hour whatever the caller asked for. SPEC R78. */
  private com.fasterxml.jackson.databind.node.ObjectNode currentRealtime(String websiteId) {
    var now = Instant.now();
    var start = now.minus(Constants.REALTIME_RANGE_MINUTES, ChronoUnit.MINUTES)
        .truncatedTo(ChronoUnit.MINUTES);
    var query = filtersOver(websiteId, start, now, query());
    return insight.realtime(websiteId, query, now);
  }

  // ------------------------------------------------------------------ the streams

  @Get("/api/realtime/{websiteId}/stream")
  public HttpResponse realtimeStream(String websiteId) {
    try {
      var caller = streamCaller();
      if (!permissions.canViewWebsiteSection(caller, websiteId, List.of("realtime"))) {
        return Responses.unauthorized();
      }
    } catch (Refusal refusal) {
      return refusal.response();
    }
    return stream(() -> currentRealtime(websiteId));
  }

  @Get("/api/websites/{websiteId}/active/stream")
  public HttpResponse activeStream(String websiteId) {
    try {
      var caller = streamCaller();
      if (!permissions.canViewWebsiteSection(caller, websiteId, List.of("overview", "realtime"))) {
        return Responses.unauthorized();
      }
    } catch (Refusal refusal) {
      return refusal.response();
    }
    return stream(
        () -> {
          var body = Json.object();
          body.put("visitors", insight.activeVisitors(websiteId, Instant.now()));
          return body;
        });
  }

  /**
   * A stream that sends the current answer first and then only when it changes.
   *
   * <p>Answering in full rather than as a difference is what makes a reconnection converge: a page
   * that missed changes does not have to replay them, it just takes the first element of its new
   * stream as the whole truth. RENDERING R1.3, R1.4.
   */
  private HttpResponse stream(java.util.function.Supplier<JsonNode> read) {
    var previous = new AtomicReference<String>();
    // The element is the answer itself, with nothing around it: the interface reads
    // `data.visitors` off a stream element exactly as it read it off the reply to a request,
    // which is what keeps the change to it confined to how it subscribes.
    Source<JsonNode, NotUsed> frames =
        Source.tick(Duration.ZERO, TICK, NotUsed.getInstance())
            // Off the stream's own thread: reading the answer walks a window of the store, and
            // waiting for it here would hold a thread every other open page shares.
            .mapAsync(1, ignored -> CompletableFuture.supplyAsync(read::get))
            .mapConcat(
                answer -> {
                  var written = Json.write(answer);
                  // The live payload carries the instant it was built, which changes every
                  // tick; comparing without it is what makes an unchanged answer silent.
                  var compared = withoutTimestamp(written);
                  if (compared.equals(previous.get())) {
                    return List.of();
                  }
                  previous.set(compared);
                  return List.of(answer);
                })
            .mapMaterializedValue(ignored -> NotUsed.getInstance());
    return HttpResponses.serverSentEvents(frames);
  }

  private static String withoutTimestamp(String written) {
    var node = Json.readObject(written);
    if (node == null) {
      return written;
    }
    node.remove("timestamp");
    return Json.write(node);
  }
}
