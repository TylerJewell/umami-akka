package io.akka.umami.lib;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * What can be told about a visitor from the request alone.
 *
 * <p>Every field here may be overridden by the collected payload, which is what lets one server
 * report another's traffic. The browser and operating-system tables are the ones the original's
 * detector carries, in order, first match winning.
 */
public final class Detect {

  private Detect() {}

  public record ClientInfo(
      String userAgent,
      String ip,
      String browser,
      String os,
      String device,
      String country,
      String region,
      String city) {}

  // --- browsers, in the order the original tries them ---------------------------------------
  private static final List<Map.Entry<String, Pattern>> BROWSERS =
      List.of(
          Map.entry("aol", Pattern.compile("AOLShield/([0-9._]+)")),
          Map.entry("edge", Pattern.compile("Edge/([0-9._]+)")),
          Map.entry("edge-ios", Pattern.compile("EdgiOS/([0-9._]+)")),
          Map.entry("yandexbrowser", Pattern.compile("YaBrowser/([0-9._]+)")),
          Map.entry("kakaotalk", Pattern.compile("KAKAOTALK\\s([0-9.]+)")),
          Map.entry("samsung", Pattern.compile("SamsungBrowser/([0-9.]+)")),
          Map.entry("silk", Pattern.compile("\\bSilk/([0-9._-]+)\\b")),
          Map.entry("miui", Pattern.compile("MiuiBrowser/([0-9.]+)$")),
          Map.entry("beaker", Pattern.compile("BeakerBrowser/([0-9.]+)")),
          Map.entry("edge-chromium", Pattern.compile("EdgA?/([0-9.]+)")),
          Map.entry(
              "chromium-webview",
              Pattern.compile("(?!Chrom.*OPR)wv\\).*Chrom(?:e|ium)/([0-9.]+)(:?\\s|$)")),
          Map.entry("chrome", Pattern.compile("(?!Chrom.*OPR)Chrom(?:e|ium)/([0-9.]+)(:?\\s|$)")),
          Map.entry("phantomjs", Pattern.compile("PhantomJS/([0-9.]+)(:?\\s|$)")),
          Map.entry("crios", Pattern.compile("CriOS/([0-9.]+)(:?\\s|$)")),
          Map.entry("firefox", Pattern.compile("Firefox/([0-9.]+)(?:\\s|$)")),
          Map.entry("fxios", Pattern.compile("FxiOS/([0-9.]+)")),
          Map.entry("opera-mini", Pattern.compile("Opera Mini.*Version/([0-9.]+)")),
          Map.entry("opera", Pattern.compile("Opera/([0-9.]+)(?:\\s|$)")),
          Map.entry("opera", Pattern.compile("OPR/([0-9.]+)(:?\\s|$)")),
          Map.entry(
              "pie", Pattern.compile("^Microsoft Pocket Internet Explorer/(\\d+\\.\\d+)$")),
          Map.entry(
              "pie",
              Pattern.compile(
                  "^Mozilla/\\d\\.\\d+\\s\\(compatible;\\s(?:MSP?IE|MSInternet Explorer)"
                      + " (\\d+\\.\\d+);.*Windows CE.*\\)$")),
          Map.entry("netfront", Pattern.compile("^Mozilla/\\d\\.\\d+.*NetFront/(\\d.\\d)")),
          Map.entry("ie", Pattern.compile("Trident/7\\.0.*rv:([0-9.]+).*\\).*Gecko$")),
          Map.entry("ie", Pattern.compile("MSIE\\s([0-9.]+);.*Trident/[4-7].0")),
          Map.entry("ie", Pattern.compile("MSIE\\s(7\\.0)")),
          Map.entry("bb10", Pattern.compile("BB10;\\sTouch.*Version/([0-9.]+)")),
          Map.entry("android", Pattern.compile("Android\\s([0-9.]+)")),
          Map.entry("ios", Pattern.compile("Version/([0-9._]+).*Mobile.*Safari.*")),
          Map.entry("safari", Pattern.compile("Version/([0-9._]+).*Safari")),
          Map.entry("facebook", Pattern.compile("FB[AS]V/([0-9.]+)")),
          Map.entry("instagram", Pattern.compile("Instagram\\s([0-9.]+)")),
          Map.entry("ios-webview", Pattern.compile("AppleWebKit/([0-9.]+).*Mobile")),
          Map.entry("ios-webview", Pattern.compile("AppleWebKit/([0-9.]+).*Gecko\\)$")));

  // --- operating systems, in the order the original tries them --------------------------------
  private static final List<Map.Entry<String, Pattern>> OPERATING_SYSTEMS =
      List.of(
          Map.entry("iOS", Pattern.compile("iP(hone|od|ad)")),
          Map.entry("Android OS", Pattern.compile("Android")),
          Map.entry("BlackBerry OS", Pattern.compile("BlackBerry|BB10")),
          Map.entry("Windows Mobile", Pattern.compile("IEMobile")),
          Map.entry("Amazon OS", Pattern.compile("Kindle")),
          Map.entry("Windows 3.11", Pattern.compile("Win16")),
          Map.entry("Windows 95", Pattern.compile("(Windows 95)|(Win95)|(Windows_95)")),
          Map.entry("Windows 98", Pattern.compile("(Windows 98)|(Win98)")),
          Map.entry("Windows 2000", Pattern.compile("(Windows NT 5.0)|(Windows 2000)")),
          Map.entry("Windows XP", Pattern.compile("(Windows NT 5.1)|(Windows XP)")),
          Map.entry("Windows Server 2003", Pattern.compile("(Windows NT 5.2)")),
          Map.entry("Windows Vista", Pattern.compile("(Windows NT 6.0)")),
          Map.entry("Windows 7", Pattern.compile("(Windows NT 6.1)")),
          Map.entry("Windows 8", Pattern.compile("(Windows NT 6.2)")),
          Map.entry("Windows 8.1", Pattern.compile("(Windows NT 6.3)")),
          Map.entry("Windows 10", Pattern.compile("(Windows NT 10.0)")),
          Map.entry("Windows ME", Pattern.compile("Windows ME")),
          Map.entry(
              "Windows CE",
              Pattern.compile("Windows CE|WinCE|Microsoft Pocket Internet Explorer")),
          Map.entry("Open BSD", Pattern.compile("OpenBSD")),
          Map.entry("Sun OS", Pattern.compile("SunOS")),
          Map.entry("Chrome OS", Pattern.compile("CrOS")),
          Map.entry("Linux", Pattern.compile("(Linux)|(X11)")),
          Map.entry("Mac OS", Pattern.compile("(Mac_PowerPC)|(Macintosh)")),
          Map.entry("QNX", Pattern.compile("QNX")),
          Map.entry("BeOS", Pattern.compile("BeOS")),
          Map.entry("OS/2", Pattern.compile("OS/2")));

  // --- device families ------------------------------------------------------------------------
  private static final Pattern CONSOLE =
      Pattern.compile("playstation|xbox|nintendo|ouya|nuon", Pattern.CASE_INSENSITIVE);
  private static final Pattern SMART_TV =
      Pattern.compile(
          "smart-?tv|hbbtv|appletv|googletv|netcast|nettv|roku|crkey|dtv|viera|aquos|inettvbrowser"
              + "|\\btv\\b",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern WEARABLE =
      Pattern.compile("watch|glass|sm-r\\d+", Pattern.CASE_INSENSITIVE);
  private static final Pattern EMBEDDED =
      Pattern.compile("crkey|electron|qtcarbrowser|tesla", Pattern.CASE_INSENSITIVE);
  private static final Pattern TABLET =
      Pattern.compile(
          "ipad|playbook|\\bnexus (7|9|10)\\b|kindle|silk|tablet|sm-t\\d+|\\bkf[a-z]{2,4}\\b",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern ANDROID_TABLET =
      Pattern.compile("android(?!.*mobile)", Pattern.CASE_INSENSITIVE);
  private static final Pattern MOBILE =
      Pattern.compile(
          "iphone|ipod|windows phone|iemobile|blackberry|bb10|opera mini|opera mobi|mobile"
              + "|palm|symbian|webos",
          Pattern.CASE_INSENSITIVE);

  /** The one screen-width breakpoint the original has. */
  private static final int LAPTOP_WIDTH = 1920;

  // --- geography providers, first country header present wins -----------------------------------
  private record Provider(String country, String region, String city, boolean hostedOnly) {}

  private static final List<Provider> PROVIDERS =
      List.of(
          new Provider("x-umami-client-country", "x-umami-client-region", "x-umami-client-city",
              true),
          new Provider("cf-ipcountry", "cf-region-code", "cf-ipcity", false),
          new Provider("x-vercel-ip-country", "x-vercel-ip-country-region", "x-vercel-ip-city",
              false),
          new Provider("cloudfront-viewer-country", "cloudfront-viewer-country-region",
              "cloudfront-viewer-city", false),
          new Provider("eo-ipcountry", "eo-region-code", "eo-ipcity", false));

  // --- address headers, in the order the original tries them ------------------------------------
  private static final List<String> IP_HEADERS =
      List.of(
          "true-client-ip",
          "cf-connecting-ip",
          "fastly-client-ip",
          "x-nf-client-connection-ip",
          "do-connecting-ip",
          "x-real-ip",
          "x-appengine-user-ip",
          "x-forwarded-for",
          "forwarded",
          "x-client-ip",
          "x-cluster-client-ip",
          "x-forwarded");

  public static String browserName(String userAgent) {
    if (userAgent == null) {
      return null;
    }
    for (var rule : BROWSERS) {
      if (rule.getValue().matcher(userAgent).find()) {
        return rule.getKey();
      }
    }
    return null;
  }

  public static String detectOS(String userAgent) {
    if (userAgent == null) {
      return null;
    }
    for (var rule : OPERATING_SYSTEMS) {
      if (rule.getValue().matcher(userAgent).find()) {
        return rule.getKey();
      }
    }
    return null;
  }

  /**
   * The device family, and then the single rule the original layers on top: a desktop reporting a
   * screen no wider than 1920 is a laptop.
   */
  public static String getDevice(String userAgent, String screen) {
    var type = deviceType(userAgent);
    if ("desktop".equals(type) && screen != null && !screen.isBlank()) {
      var width = screen.split("x")[0];
      try {
        if (Integer.parseInt(width.trim()) <= LAPTOP_WIDTH) {
          return "laptop";
        }
      } catch (NumberFormatException ignored) {
        // A screen that is not a pair of numbers leaves the family unchanged.
      }
    }
    return type;
  }

  private static String deviceType(String userAgent) {
    if (userAgent == null) {
      return "desktop";
    }
    if (CONSOLE.matcher(userAgent).find()) {
      return "console";
    }
    if (WEARABLE.matcher(userAgent).find()) {
      return "wearable";
    }
    if (SMART_TV.matcher(userAgent).find()) {
      return "smarttv";
    }
    if (TABLET.matcher(userAgent).find()) {
      return "tablet";
    }
    if (MOBILE.matcher(userAgent).find()) {
      return "mobile";
    }
    if (ANDROID_TABLET.matcher(userAgent).find()) {
      return "tablet";
    }
    if (EMBEDDED.matcher(userAgent).find()) {
      return "embedded";
    }
    return "desktop";
  }

  /**
   * Whether the request came from something that announces itself as a robot.
   *
   * <p>The list is the original's own — {@link Bots}, generated from the same 209 patterns its
   * `isbot` dependency holds — rather than a set of substrings chosen to resemble it. The answer
   * decides whether an event is stored at all, so a list that merely resembles it drops or keeps
   * real traffic the original would not.
   */
  public static boolean isBot(String userAgent) {
    return Bots.isBot(userAgent);
  }


  /**
   * The address the request came from. A configured header wins outright; otherwise the list is
   * tried in order and the forwarding chain takes its first entry.
   */
  public static String getIpAddress(Map<String, String> headers) {
    var configured = Env.get("CLIENT_IP_HEADER");
    if (configured != null) {
      var value = header(headers, configured);
      if (value != null) {
        return resolve(configured, value);
      }
    }
    if (Env.isSet("CLOUD_MODE")) {
      var hosted = header(headers, "x-umami-client-ip");
      if (hosted != null) {
        return resolve("x-umami-client-ip", hosted);
      }
    }
    for (var name : IP_HEADERS) {
      var value = header(headers, name);
      if (value != null) {
        return resolve(name, value);
      }
    }
    return null;
  }

  private static final Pattern FORWARDED_FOR =
      Pattern.compile("for=(\\[?[0-9a-fA-F:.]+]?)");

  private static String resolve(String name, String value) {
    if (name.equalsIgnoreCase("x-forwarded-for")) {
      return normalizeIp(value.split(",")[0].trim());
    }
    if (name.equalsIgnoreCase("forwarded")) {
      var match = FORWARDED_FOR.matcher(value);
      return match.find() ? normalizeIp(match.group(1)) : null;
    }
    return normalizeIp(value.trim());
  }

  /** An address mapped from the older family into the newer is reported in the older form. */
  public static String normalizeIp(String value) {
    if (value == null) {
      return null;
    }
    var address = stripPort(value.trim());
    var lower = address.toLowerCase(Locale.ROOT);
    if (lower.startsWith("::ffff:")) {
      var mapped = address.substring(7);
      if (mapped.contains(".")) {
        return mapped;
      }
    }
    return address;
  }

  public static String stripPort(String value) {
    if (value == null || value.isEmpty()) {
      return value;
    }
    if (value.startsWith("[")) {
      int close = value.indexOf(']');
      return close < 0 ? value : value.substring(0, close + 1);
    }
    int colons = (int) value.chars().filter(c -> c == ':').count();
    if (colons == 1) {
      return value.substring(0, value.indexOf(':'));
    }
    return value;
  }

  /** Whether the address is one nothing outside this machine could have come from. */
  public static boolean isLocalAddress(String ip) {
    if (ip == null || ip.isBlank()) {
      return true;
    }
    var address = stripPort(ip);
    if (address.equals("::1") || address.equals("[::1]") || address.startsWith("127.")
        || address.equals("0.0.0.0")) {
      return true;
    }
    if (address.startsWith("10.") || address.startsWith("192.168.")) {
      return true;
    }
    if (address.startsWith("172.")) {
      try {
        int second = Integer.parseInt(address.split("\\.")[1]);
        if (second >= 16 && second <= 31) {
          return true;
        }
      } catch (Exception ignored) {
        // Not a dotted address; fall through.
      }
    }
    var lower = address.toLowerCase(Locale.ROOT);
    return lower.startsWith("fc") || lower.startsWith("fd") || lower.startsWith("fe80:");
  }

  public static boolean isValidIp(String ip) {
    if (ip == null || ip.isBlank()) {
      return false;
    }
    var address = stripPort(ip);
    if (address.startsWith("[") && address.endsWith("]")) {
      address = address.substring(1, address.length() - 1);
    }
    if (address.contains(":")) {
      return address.matches("[0-9a-fA-F:.]+");
    }
    var parts = address.split("\\.");
    if (parts.length != 4) {
      return false;
    }
    for (var part : parts) {
      try {
        int value = Integer.parseInt(part);
        if (value < 0 || value > 255) {
          return false;
        }
      } catch (NumberFormatException e) {
        return false;
      }
    }
    return true;
  }

  public record Location(String country, String region, String city) {}

  /**
   * Where the request came from. A local or unreadable address has no location at all; otherwise
   * the provider headers are read unless the caller supplied the address itself, in which case
   * only a configured geography database can answer.
   */
  public static Location getLocation(String ip, Map<String, String> headers, boolean skipHeaders) {
    if (!isValidIp(ip) || isLocalAddress(ip)) {
      return null;
    }
    if (!skipHeaders && !Env.isSet("SKIP_LOCATION_HEADERS")) {
      boolean hosted = Env.isSet("CLOUD_MODE");
      for (var provider : PROVIDERS) {
        if (provider.hostedOnly() && !hosted) {
          continue;
        }
        var country = header(headers, provider.country());
        if (country != null) {
          return new Location(
              decodeHeader(country),
              regionCode(decodeHeader(country), decodeHeader(header(headers, provider.region()))),
              decodeHeader(header(headers, provider.city())));
        }
      }
    }
    return Geo.lookup(ip);
  }

  /** A value a proxy wrote as one encoding and declared as another. */
  static String decodeHeader(String value) {
    if (value == null) {
      return null;
    }
    var bytes = new byte[value.length()];
    for (int i = 0; i < value.length(); i++) {
      bytes[i] = (byte) value.charAt(i);
    }
    return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
  }

  public static String regionCode(String country, String region) {
    if (country == null || region == null || country.isBlank() || region.isBlank()) {
      return null;
    }
    return region.contains("-") ? region : country + "-" + region;
  }

  /** Whether the address appears on the refusal list, which holds addresses and ranges. */
  public static boolean hasBlockedIp(String clientIp) {
    var list = Env.get("IGNORE_IP");
    if (list == null || clientIp == null) {
      return false;
    }
    for (var entry : list.split(",")) {
      var candidate = entry.trim();
      if (candidate.isEmpty()) {
        continue;
      }
      if (candidate.equals(clientIp)) {
        return true;
      }
      if (candidate.contains("/") && inRange(clientIp, candidate)) {
        return true;
      }
    }
    return false;
  }

  private static boolean inRange(String ip, String cidr) {
    try {
      var parts = cidr.split("/");
      int prefix = Integer.parseInt(parts[1]);
      var network = toBits(parts[0]);
      var address = toBits(ip);
      if (network == null || address == null || network.length != address.length) {
        return false;
      }
      for (int i = 0; i < prefix; i++) {
        if (network[i] != address[i]) {
          return false;
        }
      }
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  private static boolean[] toBits(String ip) {
    var parts = ip.split("\\.");
    if (parts.length != 4) {
      return null;
    }
    var bits = new boolean[32];
    for (int i = 0; i < 4; i++) {
      int value = Integer.parseInt(parts[i]);
      for (int b = 0; b < 8; b++) {
        bits[i * 8 + b] = ((value >> (7 - b)) & 1) == 1;
      }
    }
    return bits;
  }

  public static String header(Map<String, String> headers, String name) {
    if (headers == null || name == null) {
      return null;
    }
    var value = headers.get(name.toLowerCase(Locale.ROOT));
    return value == null || value.isBlank() ? null : value;
  }
}
