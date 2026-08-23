package io.akka.umami.api;

import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.http.HttpResponses;

/**
 * A minimal reference UI for the rollup, served from
 * {@code src/main/resources/static-resources/} — RENDERING.md R7, gui/manifest.json.
 * Not a reproduction of umami's own dashboard; a plain caller of
 * {@link WebsiteStatsEndpoint}'s route, showing the same four numbers the dashboard's
 * stats bar shows (question-log row 7).
 */
@HttpEndpoint
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
public class UiEndpoint {

  @Get("/")
  public HttpResponse index() {
    return HttpResponses.staticResource("index.html");
  }
}
