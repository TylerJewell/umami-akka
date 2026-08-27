package io.akka.umami.lib;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** SPEC R51, R52, R45: where a visit came from, in one word. */
class ChannelsTest {

  private static String classify(String referrer, String query, String medium, String source) {
    return Channels.classify(referrer, query, medium, source, "example.com");
  }

  @Test
  void nothingAtAllIsDirect() {
    assertEquals("direct", classify("", "", null, null));
    assertEquals("direct", classify(null, null, null, null));
  }

  @Test
  void aPaidParameterInTheQueryOutranksEverythingBelowIt() {
    assertEquals("paidAds", classify("google.com", "gclid=abc", null, null));
    assertEquals("paidAds", classify("", "utm_medium=cpc", "cpc", null));
    assertEquals("paidAds", classify("", "utm_source=google", null, null));
  }

  @Test
  void theMediumNamesAReferralAnAffiliateOrAMessage() {
    assertEquals("referral", classify("", "x=1", "referral", null));
    assertEquals("referral", classify("", "x=1", "app", null));
    assertEquals("referral", classify("", "x=1", "link", null));
    assertEquals("affiliate", classify("", "x=1", "affiliate", null));
    assertEquals("sms", classify("", "x=1", "sms-blast", null));
    assertEquals("sms", classify("", "x=1", "other", "sms"));
  }

  @Test
  void aLargeModelSiteIsItsOwnChannel() {
    assertEquals("llm", classify("chatgpt.com", "", null, null));
    assertEquals("llm", classify("claude.ai", "", null, null));
    assertEquals("llm", classify("perplexity.ai", "", null, null));
  }

  @Test
  void aSearchSiteTakesThePrefix() {
    assertEquals("organicSearch", classify("www.google.com", "", null, null));
    assertEquals("organicSearch", classify("bing.com", "", null, null));
    assertEquals("organicSearch", classify("", "x=1", "organic", null));
    assertEquals("paidSearch", classify("www.google.com", "", "ppc", null));
  }

  @Test
  void aSocialSiteTakesThePrefixToo() {
    assertEquals("organicSocial", classify("news.ycombinator.com", "", null, null));
    assertEquals("organicSocial", classify("t.co", "", null, null));
    assertEquals("paidSocial", classify("facebook.com", "", "paid_social", null));
  }

  @Test
  void mailShoppingAndVideoAreRecognisedByEitherHalf() {
    assertEquals("email", classify("outlook.com", "", null, null));
    assertEquals("email", classify("", "x=1", "newsletter-mail", null));
    assertEquals("organicShopping", classify("amazon.co.uk", "", null, null));
    assertEquals("paidShopping", classify("ebay.com", "", "paid", null));
    assertEquals("organicVideo", classify("youtube.com", "", null, null));
  }

  @Test
  void anythingElseThatIsNotThisSiteIsAReferral() {
    assertEquals("referral", classify("somebodyelse.example", "", null, null));
    assertEquals("", classify("example.com", "", null, null),
        "a referral from this site classifies as nothing and is later read as direct");
    // The leading www is stripped from the *host*, not from the referrer, so a referrer that
    // carries one and a host that does not are two different sites to this rule.
    assertEquals("referral", classify("www.example.com", "", null, null));
    assertEquals("", Channels.classify("example.com", "", null, null, "www.example.com"));
  }

  @Test
  void thePrefixIsACaseSensitiveStartsWith() {
    assertEquals("paid", Channels.prefix("ppc"));
    assertEquals("paid", Channels.prefix("paid"));
    assertEquals("paid", Channels.prefix("retargeting"));
    assertEquals("paid", Channels.prefix("print"),
        "a medium beginning with p classifies as paid, which is what the original decides");
    assertEquals("organic", Channels.prefix("PPC"), "the test is case sensitive");
    assertEquals("organic", Channels.prefix("email"));
    assertEquals("organic", Channels.prefix(null));
  }

  @Test
  void aReferrerIsFoldedIntoACanonicalSite() {
    assertEquals("google.com", Channels.groupedDomain("www.google.co.uk"));
    assertEquals("twitter.com", Channels.groupedDomain("t.co"));
    assertEquals("twitter.com", Channels.groupedDomain("x.com"));
    assertEquals("instagram.com", Channels.groupedDomain("ig.com"));
    assertEquals("news.ycombinator.com", Channels.groupedDomain("news.ycombinator.com"));
    assertEquals("Other", Channels.groupedDomain("somebodyelse.example"));
    assertEquals("Other", Channels.groupedDomain(null));
  }
}
