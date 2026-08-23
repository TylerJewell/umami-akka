# umami-akka

Records a pageview or a custom event for a website, and answers how many pageviews,
visitors, visits and bounces it had over a date range.

A port of [umami-software/umami](https://github.com/umami-software/umami) onto **Akka**,
built with **Akka Specify**.

![The reference page showing Visitors, Visits, Views and Bounce rate for one website](docs/images/console.png)

---

## Where it came from

umami is a self-hosted, privacy-focused alternative to Google Analytics: a script on a
website sends events to it, and a dashboard shows what came back. It was ported to derive
a specification format precise enough to regenerate a system on a different stack — the
port is the vehicle, the specification is the deliverable.

The specifications the port was generated from are in
[TylerJewell/akka-specify-harness](https://github.com/TylerJewell/akka-specify-harness)
under `umami-port/`.

---

## umami-software/umami → this port

📉 445 TypeScript lines → **268 Java lines**<br>
📁 3 files → **7 files**<br>
🎯 4 of 4 workload answers agree → **4 of 4**<br>
⏱️ 15.0 → **2.0** seconds per write, over HTTP through a container (bench/REPORT.md §2 — not quoted as a ratio; both figures are dominated by deployment apparatus, not the rule being compared)<br>
🧪 0 tests → **15 tests**

Full method and the numbers that did *not* make this list:
[`bench/REPORT.md`](bench/REPORT.md).

---

## What it took to build

⏱️ **1.2 hours** from the first command to the published repository, **1.2** of them active<br>
💬 **572** exchanges with the model<br>
✍️ **197,702** tokens written by the model, **124,912,796** counting everything sent and re-sent<br>
🙋 **0** questions to a human<br>
🧪 **15** tests

```bash
python toolkit/tokens.py --port umami    # turns, tokens, elapsed and active time
```

The record of every question, and where the time went, is in
[`port-log/`](https://github.com/TylerJewell/akka-specify-harness/tree/main/port-log).

---

## What it does

From the specification:

- **A pageview and a custom event are recorded the same way, and counted differently.**
  Both are one call to the same route; only a pageview counts toward the pageview total.
- **A visit bounces only when it has exactly one pageview and no custom event.** A custom
  event in that visit stops it from bouncing, even though the custom event itself never
  counts as a pageview.
- **A request from something that is not a browser is accepted and recorded nowhere.** The
  caller gets an ordinary response either way; nothing distinguishes the two from outside.
- **The total time spent is the span between a visit's earliest and latest event.** A visit
  with one event contributes nothing to it.

---

## Design decisions

**One visit is one entity.** umami groups events by website, session and visit at query
time, over every event ever recorded for the date range asked about. Here, each visit
keeps its own running count and its own earliest and latest event, so answering a question
about one visit never means rereading events that belong to a different one.

**Session and visit identity is recomputed, not cached.** The original hands a signed token
back to the caller so a repeat call within one browsing session can skip recomputing who it
is. This port recomputes it every time from the caller's website, address and browser
string, because nothing here needs to detect a caller drifting away from an earlier value —
it only needs to arrive at the same identity twice for the same inputs, which recomputing
already does.

**The rollup is read as rows, and added up outside the query.** The original's database
does the sums, the distinct counts and the bounce rule in one query. Here, the equivalent
query returns one row per visit and a second step adds them up, because that is what
answers the question without asking the read side to compute a rule that belongs to it.

---

## Running it — the short path

You do not need Java, Maven, or the Akka CLI installed. Akka Specify installs them for you.

**1. Install Akka Specify** in Claude Code:

```
/plugin marketplace add akka/ai-marketplace
/plugin install akka@akka-ai-marketplace
```

Restart Claude Code when it asks.

**2. Give it this prompt:**

> Clone https://github.com/TylerJewell/umami-akka into a new directory and open it.
> Then run /akka:setup to install everything this project needs, and /akka:build to
> compile it, run the tests, and start it locally.

**3. Open** http://localhost:9075.

---

## Running it — the developer path

### Requirements

- Java 21 or newer
- Maven 3.9 or newer
- An Akka download token — run `akka code token` once

### Start the service

```bash
mvn compile
akka local run
```

The service starts on **port 9075**.

### Record something and read it back

```bash
curl -X POST localhost:9075/api/send -H 'Content-Type: application/json' \
  -H 'User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36' \
  -d '{"type": "event", "payload": {"website": "11111111-1111-1111-1111-111111111111", "url": "/home", "hostname": "example.com"}}'

curl "localhost:9075/api/websites/11111111-1111-1111-1111-111111111111/stats?startAt=0&endAt=9999999999999"
```

Or open http://localhost:9075, enter the website ID, and press Load.

---

## Configuration

| Variable | Default | Notes |
|---|---|---|
| `akka.javasdk.dev-mode.http-port` | `9075` | In `src/main/resources/application.conf`. Where the service is served. |

There is nothing else. The original reads database and secret configuration from
environment variables at startup; this port has no external store to configure.

---

## Where it differs from umami-software/umami

Everything not listed here behaves the same way on purpose, including the parts that look
like mistakes.

- **A repeat call within one browsing session is not detected.** The original signs a
  cache token back to the caller so a second call can notice its session drifted (a
  different address or browser string arriving under the same identity) and start a fresh
  visit, and it also expires a visit after 30 minutes of inactivity. This port recomputes
  the identity from scratch every call and has no notion of a visit going stale on its own,
  because reproducing the token handshake was judged not to change any answer this port's
  slice is asked about — only when a long-idle caller's next event is treated as the same
  visit or a new one, which nothing in this port's rollup distinguishes.
- **Bot detection is a small heuristic, not the original's pattern library.** The original
  classifies a caller using the `isbot` npm package's database of known bot signatures.
  This port treats a `User-Agent` as a bot when it is blank, names an obvious crawler, or
  carries no parenthesised platform block — the shape every real browser's string has and
  the one case run against the original (question-log row 6). The two will disagree on
  user agents neither of those checks was built to recognise.
- **The rollup query is not aggregated in one pass.** The original computes pageviews,
  visitors, visits, bounces and total time in a single SQL query. This port's read side
  returns one row per visit and adds them up in the endpoint that serves the request,
  because Akka's view query language is built for point and range lookups rather than the
  original's mix of sums, distinct counts and a conditional bounce rule in one statement.
  Both produce the same numbers for every workload run (`bench/REPORT.md`); the difference
  is where the arithmetic happens, not what it computes.
- **Only pageview and custom-event collection are ported.** The original's same endpoint
  also accepts `identify`, `performance`, `pixel`, and `link` event types, a ClickHouse and
  Kafka storage backend, and a rollup path narrowed by event properties — all out of scope
  for this slice (`docs/scope.md`). A call naming any of those is rejected outright rather
  than silently accepted and ignored.

---

## Licence

umami-software/umami is MIT-licensed, © 2022 Umami Software, Inc. This port reimplements
the behaviour described above without copied source; see `ACKNOWLEDGEMENTS.md`.
