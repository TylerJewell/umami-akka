package io.akka.umami.domain;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.akka.umami.lib.Constants;
import io.akka.umami.lib.Filters;
import io.akka.umami.lib.Values;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** What a visit leaves behind: a session, its events, their properties, and any revenue. */
public final class Traffic {

  private Traffic() {}

  public record Session(
      String id,
      String websiteId,
      String browser,
      String os,
      String device,
      String screen,
      String language,
      String country,
      String region,
      String city,
      String distinctId,
      Instant createdAt) {}

  /**
   * One recorded event. This is the row every analytical answer is computed over, so it carries
   * every dimension a filter can name, including the ones that live on the session — resolved when
   * the row is assembled rather than joined at read time.
   */
  public record Event(
      String id,
      String websiteId,
      String sessionId,
      String visitId,
      Instant createdAt,
      String urlPath,
      String urlQuery,
      String referrerPath,
      String referrerQuery,
      String referrerDomain,
      String pageTitle,
      String utmSource,
      String utmMedium,
      String utmCampaign,
      String utmContent,
      String utmTerm,
      String gclid,
      String fbclid,
      String msclkid,
      String ttclid,
      String twclid,
      String lifatid,
      int eventType,
      String eventName,
      String tag,
      String hostname,
      BigDecimal lcp,
      BigDecimal inp,
      BigDecimal cls,
      BigDecimal fcp,
      BigDecimal ttfb,
      List<Values.Property> properties) {

    /** An event that counts towards a page view: neither a named event nor a vitals record. */
    public boolean isViewLike() {
      return eventType != Constants.CUSTOM_EVENT && eventType != Constants.PERFORMANCE_EVENT;
    }

    public boolean isNamed() {
      return eventType == Constants.CUSTOM_EVENT;
    }
  }

  /** An event with its session's own fields resolved, which is what a filter is evaluated against. */
  public record Reading(Event event, Session session) implements Filters.Row {

    @Override
    public String column(String name) {
      return switch (name) {
        case "path", "entry", "exit" -> event.urlPath();
        case "fullPath" -> event.urlQuery() == null || event.urlQuery().isEmpty()
            ? event.urlPath()
            : event.urlPath() + "?" + event.urlQuery();
        case "referrer", "domain" -> event.referrerDomain();
        case "hostname" -> event.hostname();
        case "title" -> event.pageTitle();
        case "query" -> event.urlQuery();
        case "event" -> event.eventName();
        case "tag" -> event.tag();
        case "eventType" -> String.valueOf(event.eventType());
        case "utmSource" -> event.utmSource();
        case "utmMedium" -> event.utmMedium();
        case "utmCampaign" -> event.utmCampaign();
        case "utmContent" -> event.utmContent();
        case "utmTerm" -> event.utmTerm();
        case "browser" -> session == null ? null : session.browser();
        case "os" -> session == null ? null : session.os();
        case "device" -> session == null ? null : session.device();
        case "screen" -> session == null ? null : session.screen();
        case "language" -> session == null ? null : session.language();
        case "country" -> session == null ? null : session.country();
        case "region" -> session == null ? null : session.region();
        case "city" -> session == null ? null : session.city();
        case "distinctId" -> session == null ? null : session.distinctId();
        default -> null;
      };
    }
  }

  public record SessionProperty(
      String id,
      String websiteId,
      String sessionId,
      String distinctId,
      Values.Property property,
      Instant createdAt) {}

  public record Revenue(
      String id,
      String websiteId,
      String sessionId,
      String eventId,
      String eventName,
      String currency,
      BigDecimal revenue,
      Instant createdAt) {}

  public record SessionLink(
      String websiteId, String distinctId, String sessionId, Instant createdAt) {}

  /** What a collected payload turns into before anything is written. */
  public record Collected(Event event, Session session, ObjectNode data) {}
}
