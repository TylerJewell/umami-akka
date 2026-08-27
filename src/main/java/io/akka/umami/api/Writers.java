package io.akka.umami.api;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.akka.umami.analytics.Insight;
import io.akka.umami.analytics.Rollup;
import io.akka.umami.domain.Accounts;
import io.akka.umami.domain.Content;
import io.akka.umami.domain.Recordings;
import io.akka.umami.domain.Traffic;
import io.akka.umami.lib.Json;
import java.time.Instant;

/**
 * How each record is written to a caller.
 *
 * <p>Written by hand rather than reflected, because which fields appear is part of the surface: an
 * account never carries its password, a website carries a share identifier it does not store, and a
 * value that is unset is absent rather than null.
 */
public final class Writers {

  private Writers() {}

  public static ObjectNode user(Accounts.User user) {
    var out = Json.object();
    out.put("id", user.id());
    out.put("username", user.username());
    out.put("role", user.role());
    out.put("createdAt", stamp(user.createdAt()));
    out.put("twoFactorRequired", user.twoFactorRequired());
    return out;
  }

  /** The fuller shape the administration list answers, which carries the removal stamp too. */
  public static ObjectNode adminUser(Accounts.User user, long websiteCount) {
    var out = Json.object();
    out.put("id", user.id());
    out.put("username", user.username());
    out.put("role", user.role());
    out.put("logoUrl", user.logoUrl());
    out.put("displayName", user.displayName());
    out.put("twoFactorRequired", user.twoFactorRequired());
    out.put("createdAt", stamp(user.createdAt()));
    out.put("updatedAt", stamp(user.updatedAt()));
    out.put("deletedAt", stamp(user.deletedAt()));
    var count = Json.object();
    count.put("websites", websiteCount);
    out.set("_count", count);
    return out;
  }

  public static ObjectNode team(Accounts.Team team) {
    var out = Json.object();
    out.put("id", team.id());
    out.put("name", team.name());
    out.put("accessCode", team.accessCode());
    out.put("logoUrl", team.logoUrl());
    out.put("twoFactorRequired", team.twoFactorRequired());
    out.put("createdAt", stamp(team.createdAt()));
    out.put("updatedAt", stamp(team.updatedAt()));
    out.put("deletedAt", stamp(team.deletedAt()));
    return out;
  }

  public static ObjectNode teamUser(Accounts.TeamUser member) {
    var out = Json.object();
    out.put("id", member.id());
    out.put("teamId", member.teamId());
    out.put("userId", member.userId());
    out.put("role", member.role());
    out.put("createdAt", stamp(member.createdAt()));
    out.put("updatedAt", stamp(member.updatedAt()));
    return out;
  }

  /** The account a website row names, under whichever key the query that fetched it asked for. */
  public static ObjectNode withAccount(ObjectNode row, String field, Accounts.User account) {
    if (account != null) {
      var out = Json.object();
      out.put("username", account.username());
      out.put("id", account.id());
      row.set(field, out);
    }
    return row;
  }

  public static ObjectNode website(Content.Website website, String shareId) {
    var out = Json.object();
    out.put("id", website.id());
    out.put("name", website.name());
    out.put("domain", website.domain());
    out.put("resetAt", stamp(website.resetAt()));
    out.put("userId", website.userId());
    out.put("teamId", website.teamId());
    out.put("createdBy", website.createdBy());
    out.put("createdAt", stamp(website.createdAt()));
    out.put("updatedAt", stamp(website.updatedAt()));
    out.put("deletedAt", stamp(website.deletedAt()));
    out.put("recorderEnabled", website.recorderEnabled());
    if (website.replayConfig() == null || isEmpty(website.replayConfig())) {
      out.putNull("replayConfig");
    } else {
      out.set("replayConfig", replayConfig(website.replayConfig()));
    }
    out.put("shareId", shareId);
    return out;
  }

  private static boolean isEmpty(Content.ReplayConfig configuration) {
    return configuration.replayEnabled() == null
        && configuration.heatmapEnabled() == null
        && configuration.sampleRate() == null
        && configuration.heatmapSampleRate() == null
        && configuration.maskLevel() == null
        && configuration.maxDuration() == null
        && configuration.blockSelector() == null;
  }

  /**
   * Only the settings that were given. A recorder configuration is stored as the object it
   * arrived as, so a setting nobody chose is absent rather than null.
   */
  public static ObjectNode replayConfig(Content.ReplayConfig configuration) {
    var out = Json.object();
    if (configuration.replayEnabled() != null) {
      out.put("replayEnabled", configuration.replayEnabled());
    }
    if (configuration.heatmapEnabled() != null) {
      out.put("heatmapEnabled", configuration.heatmapEnabled());
    }
    if (configuration.sampleRate() != null) {
      out.put("sampleRate", configuration.sampleRate());
    }
    if (configuration.heatmapSampleRate() != null) {
      out.put("heatmapSampleRate", configuration.heatmapSampleRate());
    }
    if (configuration.maskLevel() != null) {
      out.put("maskLevel", configuration.maskLevel());
    }
    if (configuration.maxDuration() != null) {
      out.put("maxDuration", configuration.maxDuration());
    }
    if (configuration.blockSelector() != null) {
      out.put("blockSelector", configuration.blockSelector());
    }
    return out;
  }

  public static ObjectNode link(Content.Link link) {
    var out = Json.object();
    out.put("id", link.id());
    out.put("name", link.name());
    out.put("url", link.url());
    out.put("slug", link.slug());
    out.put("userId", link.userId());
    out.put("teamId", link.teamId());
    out.put("createdAt", stamp(link.createdAt()));
    out.put("updatedAt", stamp(link.updatedAt()));
    out.put("deletedAt", stamp(link.deletedAt()));
    return out;
  }

  public static ObjectNode pixel(Content.Pixel pixel) {
    var out = Json.object();
    out.put("id", pixel.id());
    out.put("name", pixel.name());
    out.put("slug", pixel.slug());
    out.put("userId", pixel.userId());
    out.put("teamId", pixel.teamId());
    out.put("createdAt", stamp(pixel.createdAt()));
    out.put("updatedAt", stamp(pixel.updatedAt()));
    out.put("deletedAt", stamp(pixel.deletedAt()));
    return out;
  }

  public static ObjectNode board(Content.Board board) {
    var out = Json.object();
    out.put("id", board.id());
    out.put("type", board.type());
    out.put("name", board.name());
    out.put("description", board.description());
    out.set("parameters", board.parameters() == null ? Json.object() : board.parameters());
    out.put("userId", board.userId());
    out.put("teamId", board.teamId());
    out.put("createdAt", stamp(board.createdAt()));
    out.put("updatedAt", stamp(board.updatedAt()));
    return out;
  }

  public static ObjectNode report(Content.Report report) {
    var out = Json.object();
    out.put("id", report.id());
    out.put("userId", report.userId());
    out.put("websiteId", report.websiteId());
    out.put("type", report.type());
    out.put("name", report.name());
    out.put("description", report.description());
    out.set("parameters", report.parameters() == null ? Json.object() : report.parameters());
    out.put("createdAt", stamp(report.createdAt()));
    out.put("updatedAt", stamp(report.updatedAt()));
    return out;
  }

  public static ObjectNode segment(Content.Segment segment) {
    var out = Json.object();
    out.put("id", segment.id());
    out.put("websiteId", segment.websiteId());
    out.put("type", segment.type());
    out.put("name", segment.name());
    out.set("parameters", segment.parameters() == null ? Json.object() : segment.parameters());
    out.put("createdAt", stamp(segment.createdAt()));
    out.put("updatedAt", stamp(segment.updatedAt()));
    return out;
  }

  public static ObjectNode share(Content.Share share) {
    var out = Json.object();
    out.put("id", share.id());
    out.put("entityId", share.entityId());
    out.put("name", share.name());
    out.put("shareType", share.shareType());
    out.put("slug", share.slug());
    out.set("parameters", share.parameters() == null ? Json.object() : share.parameters());
    out.put("createdAt", stamp(share.createdAt()));
    out.put("updatedAt", stamp(share.updatedAt()));
    return out;
  }

  public static ObjectNode savedReplay(Recordings.SavedReplay replay) {
    var out = Json.object();
    out.put("id", replay.id());
    out.put("name", replay.name());
    out.put("websiteId", replay.websiteId());
    out.put("visitId", replay.visitId());
    out.put("createdAt", stamp(replay.createdAt()));
    out.put("updatedAt", stamp(replay.updatedAt()));
    return out;
  }

  public static ObjectNode replaySummary(Recordings.ReplaySummary summary) {
    var out = Json.object();
    out.put("id", summary.id());
    out.put("sessionId", summary.sessionId());
    out.put("websiteId", summary.websiteId());
    out.put("browser", summary.browser());
    out.put("os", summary.os());
    out.put("device", summary.device());
    out.put("country", summary.country());
    out.put("city", summary.city());
    out.put("distinctId", summary.distinctId());
    out.put("eventCount", summary.eventCount());
    out.put("chunkCount", summary.chunkCount());
    out.put("startedAt", stamp(summary.startedAt()));
    out.put("endedAt", stamp(summary.endedAt()));
    out.put("duration", summary.duration());
    out.put("createdAt", stamp(summary.createdAt()));
    return out;
  }

  public static ObjectNode sessionRow(Insight.SessionRow row) {
    var out = Json.object();
    out.put("id", row.id());
    out.put("websiteId", row.websiteId());
    out.put("hostname", row.hostname());
    out.put("browser", row.browser());
    out.put("os", row.os());
    out.put("device", row.device());
    out.put("screen", row.screen());
    out.put("language", row.language());
    out.put("country", row.country());
    out.put("region", row.region());
    out.put("city", row.city());
    out.put("firstAt", stamp(row.firstAt()));
    out.put("lastAt", stamp(row.lastAt()));
    out.put("visits", row.visits());
    out.put("views", row.views());
    out.put("events", row.events());
    out.put("createdAt", stamp(row.createdAt()));
    return out;
  }

  public static ObjectNode eventRow(Traffic.Reading reading) {
    var event = reading.event();
    var session = reading.session();
    var out = Json.object();
    out.put("id", event.id());
    out.put("websiteId", event.websiteId());
    out.put("sessionId", event.sessionId());
    out.put("createdAt", stamp(event.createdAt()));
    out.put("hostname", event.hostname());
    out.put("urlPath", event.urlPath());
    out.put("urlQuery", event.urlQuery());
    out.put("referrerPath", event.referrerPath());
    out.put("referrerQuery", event.referrerQuery());
    out.put("referrerDomain", event.referrerDomain());
    out.put("country", session == null ? null : session.country());
    out.put("city", session == null ? null : session.city());
    out.put("device", session == null ? null : session.device());
    out.put("os", session == null ? null : session.os());
    out.put("browser", session == null ? null : session.browser());
    out.put("pageTitle", event.pageTitle());
    out.put("eventType", event.eventType());
    out.put("eventName", event.eventName());
    out.put("hasData", !event.properties().isEmpty());
    return out;
  }

  public static ObjectNode stats(Rollup.Stats stats) {
    var out = Json.object();
    out.put("pageviews", stats.pageviews());
    out.put("visitors", stats.visitors());
    out.put("visits", stats.visits());
    out.put("bounces", stats.bounces());
    out.put("totaltime", stats.totaltime());
    return out;
  }

  public static ObjectNode metric(Rollup.Metric metric) {
    var out = Json.object();
    out.put("x", metric.x());
    out.put("y", metric.y());
    if (metric.country() != null) {
      out.put("country", metric.country());
    }
    return out;
  }

  /**
   * The expanded row.
   *
   * <p>Two of the six come back as strings and four as numbers. That is not tidy, and it is what
   * the original's own serialiser produces from a wide-integer column; a client that parses these
   * is written against it. SPEC-001 question-log row 22.
   */
  public static ObjectNode expanded(Rollup.Expanded row) {
    var out = Json.object();
    out.put("name", row.name());
    out.put("pageviews", String.valueOf(row.pageviews()));
    out.put("visitors", row.visitors());
    out.put("visits", row.visits());
    out.put("bounces", row.bounces());
    out.put("totaltime", String.valueOf(row.totaltime()));
    return out;
  }

  public static ObjectNode point(Rollup.Point point) {
    var out = Json.object();
    out.put("x", point.x());
    out.put("y", point.y());
    return out;
  }

  public static ObjectNode namedPoint(Rollup.NamedPoint point) {
    var out = Json.object();
    out.put("x", point.x());
    out.put("t", point.t());
    out.put("y", point.y());
    return out;
  }

  /**
   * One event and every property it holds, as two arrays read by position.
   *
   * <p>Keys and values are separate lists rather than an object because a key may repeat, and an
   * object would silently keep one of them.
   */
  public static ObjectNode eventPivotRow(io.akka.umami.analytics.Pivot.EventRow row) {
    var out = Json.object();
    out.put("eventId", row.eventId());
    out.put("sessionId", row.sessionId());
    out.put("eventName", row.eventName());
    out.put("urlPath", row.urlPath());
    out.put("createdAt", stamp(row.createdAt()));
    out.set("propertyKeys", strings(row.propertyKeys()));
    out.set("propertyValues", strings(row.propertyValues()));
    return out;
  }

  public static ObjectNode sessionPivotRow(io.akka.umami.analytics.Pivot.SessionRow row) {
    var out = Json.object();
    out.put("sessionId", row.sessionId());
    out.put("distinctId", row.distinctId());
    out.put("createdAt", stamp(row.createdAt()));
    out.set("propertyKeys", strings(row.propertyKeys()));
    out.set("propertyValues", strings(row.propertyValues()));
    return out;
  }

  private static com.fasterxml.jackson.databind.node.ArrayNode strings(
      java.util.List<String> values) {
    var out = Json.array();
    values.forEach(out::add);
    return out;
  }

  public static String stamp(Instant instant) {
    return instant == null ? null : instant.toString();
  }
}
