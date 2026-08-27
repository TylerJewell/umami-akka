package io.akka.umami.lib;

import java.util.List;
import java.util.Locale;

/**
 * Where a visit came from, in one word.
 *
 * <p>The prefix test is case-sensitive and matches the start of the medium, so a medium of
 * {@code print} classifies as paid. That is not a slip to correct: it is what the original decides,
 * and a rebuild that tidied it would disagree with the original on real traffic.
 */
public final class Channels {

  private Channels() {}

  public static final String DIRECT = "direct";

  /** {@code paid} or {@code organic}, prefixed to search, social, shopping and video. */
  public static String prefix(String utmMedium) {
    if (utmMedium == null) {
      return "organic";
    }
    if (utmMedium.startsWith("p")
        || utmMedium.contains("ppc")
        || utmMedium.contains("retargeting")
        || utmMedium.contains("paid")) {
      return "paid";
    }
    return "organic";
  }

  /** The empty string where nothing classified, which a visit later reads as {@link #DIRECT}. */
  public static String classify(String referrerDomain, String urlQuery, String utmMedium,
      String utmSource, String hostname) {
    var referrer = referrerDomain == null ? "" : referrerDomain;
    var query = urlQuery == null ? "" : urlQuery;
    var medium = utmMedium == null ? "" : utmMedium;
    var source = utmSource == null ? "" : utmSource;
    var prefix = prefix(utmMedium);

    if (referrer.isEmpty() && query.isEmpty()) {
      return DIRECT;
    }
    if (containsAny(query, Constants.PAID_AD_PARAMS)) {
      return "paidAds";
    }
    if (containsAny(medium, List.of("referral", "app", "link"))) {
      return "referral";
    }
    if (contains(medium, "affiliate")) {
      return "affiliate";
    }
    if (contains(medium, "sms") || contains(source, "sms")) {
      return "sms";
    }
    if (containsAny(referrer, Constants.LLM_DOMAINS)) {
      return "llm";
    }
    if (containsAny(referrer, Constants.SEARCH_DOMAINS) || contains(medium, "organic")) {
      return prefix + "Search";
    }
    if (containsAny(referrer, Constants.SOCIAL_DOMAINS)) {
      return prefix + "Social";
    }
    if (containsAny(referrer, Constants.EMAIL_DOMAINS) || contains(medium, "mail")) {
      return "email";
    }
    if (containsAny(referrer, Constants.SHOPPING_DOMAINS) || contains(medium, "shop")) {
      return prefix + "Shopping";
    }
    if (containsAny(referrer, Constants.VIDEO_DOMAINS) || contains(medium, "video")) {
      return prefix + "Video";
    }
    if (!referrer.isEmpty() && !referrer.equals(Filters.stripWww(hostname))) {
      return "referral";
    }
    return "";
  }

  /** The canonical site the {@code domain} dimension folds a referrer into. */
  public static String groupedDomain(String referrerDomain) {
    if (referrerDomain == null) {
      return "Other";
    }
    var lower = referrerDomain.toLowerCase(Locale.ROOT);
    for (var group : Constants.GROUPED_DOMAINS) {
      for (var fragment : group.getKey()) {
        if (lower.contains(fragment.toLowerCase(Locale.ROOT))) {
          return group.getValue();
        }
      }
    }
    return "Other";
  }

  private static boolean contains(String haystack, String needle) {
    return haystack.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
  }

  private static boolean containsAny(String haystack, List<String> needles) {
    var lower = haystack.toLowerCase(Locale.ROOT);
    for (var needle : needles) {
      if (lower.contains(needle.toLowerCase(Locale.ROOT))) {
        return true;
      }
    }
    return false;
  }
}
