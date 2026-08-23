# Acknowledgements

This project is a port of **[umami-software/umami](https://github.com/umami-software/umami)**.

- **Licence and copyright.** `umami-software/umami` is MIT-licensed, copyright (c) 2022
  Umami Software, Inc. `<hello@umami.is>` (read from its own `LICENSE` file). MIT does not
  require a downstream port to carry the same licence, and this project is not a
  redistribution of the original's source — it is an independent implementation of a
  behaviour read and run from it.
- **Nothing was copied verbatim.** No file, fixture, or block of source from
  `umami-software/umami` is present in this repository. `python toolkit/copied_strings.py
  umami --source umami-src` found three literal strings of ten characters or more shared
  between the two, checked here individually:
  - `Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)
    Chrome/120.0.0.0 Safari/537.36` (truncated by the tool to `Mozilla/5.0`) — a standard
    browser `User-Agent` string, not umami's own text; both this port's tests and the
    original happen to use it as a realistic non-bot example.
  - `/api/websites/` — the route segment umami's own API uses for a website resource; this
    port's endpoint reproduces the same path deliberately (SPEC-001, question-log rows 1–7),
    so the two match by design, not by copying prose.
  - `bot detection` — an ordinary English phrase describing what `BotDetector` does; not
    quoted from umami's source.
- **Behaviour is derived even where no text was copied.** `EventEndpoint`, `VisitEntity`,
  `VisitRollupView`, and `WebsiteStatsEndpoint` reproduce specific rules read and run
  against the real source (question-log rows 1–7; SPEC-001 R1–R8): which event types count
  as a pageview, how a visit bounces, how `totaltime` is computed, and that a bot
  `User-Agent` is silently dropped. The Java implementing those rules is this port's own,
  but the rules themselves are umami's, established by running it — not invented here.

## Also used

- Akka
