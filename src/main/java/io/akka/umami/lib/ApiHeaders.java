package io.akka.umami.lib;

import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.headers.RawHeader;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * The headers umami puts on an answer, independently of what answered.
 *
 * <p>In the original these are configuration rather than code: a rule per address pattern, applied
 * by the framework after the route has produced its answer. That ordering is part of the rule — the
 * recorder configuration route asks for a sixty-second cache and its caller receives {@code
 * no-cache}, because the pattern rule is applied last. So these replace rather than add.
 *
 * <p>Two of the four settings that shape them are read at image build time in the original and two
 * per request. All four are read here at the moment an answer is written, because this service has
 * no build step that reads settings; the difference is that {@code CORS_MAX_AGE} and {@code
 * FORCE_SSL} take effect on a restart rather than needing an image rebuilt. SPEC R147.
 */
public final class ApiHeaders {

  private static final String DEFAULT_MAX_AGE = "86400";
  private static final String HSTS = "max-age=63072000; includeSubDomains; preload";

  /**
   * True where the runtime writes {@code Access-Control-Allow-Origin} itself, which it does in
   * development and not in a deployment. A second copy of that header is not a stronger permission
   * — a browser reads the two together as the value {@code *, *}, matches nothing, and refuses the
   * whole cross-origin call. So this service writes it only where nothing else will.
   */
  private static volatile boolean runtimeWritesTheOrigin;

  /**
   * The settings a header list was built from, held with the list itself.
   *
   * <p>One reference rather than a key beside a value: a reader that saw the two separately could
   * see a key written by one thread and a list written by another, and answer headers built from
   * settings nobody set.
   */
  private record Built(boolean underApi, boolean runtimeWritesTheOrigin, String maxAge,
      boolean forceSsl, String apiUrl, String frameUrls, List<RawHeader> headers) {

    boolean matches(boolean underApi, boolean origin, String maxAge, boolean forceSsl,
        String apiUrl, String frameUrls) {
      return this.underApi == underApi
          && this.runtimeWritesTheOrigin == origin
          && this.maxAge.equals(maxAge)
          && this.forceSsl == forceSsl
          && this.apiUrl.equals(apiUrl)
          && this.frameUrls.equals(frameUrls);
    }
  }

  private static volatile Built underApi;

  private static volatile Built everyAddress;

  private ApiHeaders() {}

  /** Called once at startup, from the one place that is handed the runtime's own configuration. */
  public static void runtimeWritesTheOriginHeader(boolean it) {
    runtimeWritesTheOrigin = it;
    underApi = null;
    everyAddress = null;
  }

  /** Every header, for a route under {@code /api}. */
  public static HttpResponse api(HttpResponse response) {
    return replace(response, headers(true));
  }

  /**
   * The two that belong to every address, for the collection redirectors — which sit outside {@code
   * /api} in the original and so are given neither the access-control set nor {@code no-cache}.
   */
  public static HttpResponse everyAddress(HttpResponse response) {
    return replace(response, headers(false));
  }

  private static HttpResponse replace(HttpResponse response, List<RawHeader> headers) {
    var out = response;
    for (var header : headers) {
      out = out.removeHeader(header.name()).addHeader(header);
    }
    return out;
  }

  private static List<RawHeader> headers(boolean api) {
    var maxAge = Env.get("CORS_MAX_AGE", DEFAULT_MAX_AGE);
    var forceSsl = Env.isSet("FORCE_SSL");
    var apiUrl = Env.get("API_URL", "");
    var frameUrls = Env.get("ALLOWED_FRAME_URLS", "");
    var origin = runtimeWritesTheOrigin;
    var known = api ? underApi : everyAddress;
    if (known != null && known.matches(api, origin, maxAge, forceSsl, apiUrl, frameUrls)) {
      return known.headers();
    }
    var built = new ArrayList<RawHeader>();
    if (api) {
      if (!origin) {
        built.add(RawHeader.create("Access-Control-Allow-Origin", "*"));
      }
      built.add(RawHeader.create("Access-Control-Allow-Headers", "*"));
      built.add(RawHeader.create("Access-Control-Allow-Methods", "GET, DELETE, POST, PUT"));
      built.add(RawHeader.create("Access-Control-Max-Age", maxAge));
      built.add(RawHeader.create("Cache-Control", "no-cache"));
    }
    built.add(RawHeader.create("X-DNS-Prefetch-Control", "on"));
    built.add(RawHeader.create("Content-Security-Policy", policy(apiUrl, frameUrls)));
    if (forceSsl) {
      built.add(RawHeader.create("Strict-Transport-Security", HSTS));
    }
    var frozen =
        new Built(api, origin, maxAge, forceSsl, apiUrl, frameUrls, List.copyOf(built));
    if (api) {
      underApi = frozen;
    } else {
      everyAddress = frozen;
    }
    return frozen.headers();
  }

  /** The seven directives, in the original's order, with the two configurable ones filled in. */
  private static String policy(String apiUrl, String frameUrls) {
    var connect = new StringBuilder("'self' https:");
    var origin = origin(apiUrl);
    if (!origin.isEmpty()) {
      connect.append(' ').append(origin);
    }
    var ancestors = new StringBuilder("'self'");
    if (!frameUrls.isEmpty()) {
      ancestors.append(' ').append(frameUrls);
    }
    return "default-src 'self'; "
        + "img-src 'self' https: data: blob:; "
        + "script-src 'self' 'unsafe-eval' 'unsafe-inline'; "
        + "style-src 'self' 'unsafe-inline'; "
        + "connect-src "
        + connect
        + "; "
        + "frame-src 'self' http: https:; "
        + "frame-ancestors "
        + ancestors
        + ";";
  }

  /** Scheme, host and port, and nothing where the setting is a path rather than an address. */
  private static String origin(String url) {
    if (url.isEmpty()) {
      return "";
    }
    try {
      var parsed = URI.create(url);
      if (parsed.getScheme() == null || parsed.getHost() == null) {
        return "";
      }
      return parsed.getPort() < 0
          ? parsed.getScheme() + "://" + parsed.getHost()
          : parsed.getScheme() + "://" + parsed.getHost() + ":" + parsed.getPort();
    } catch (IllegalArgumentException notAnAddress) {
      return "";
    }
  }
}
