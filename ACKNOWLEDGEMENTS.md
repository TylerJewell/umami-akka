# Acknowledgements

This project is a rebuild of **[umami-software/umami](https://github.com/umami-software/umami)**
v3.3.1 on the Akka SDK. It is derived from someone else's licensed work in two different ways,
and the two are worth keeping apart.

## The licence

`umami-software/umami` is MIT-licensed, copyright (c) 2022 Umami Software, Inc.
`<hello@umami.is>`, read from its own `LICENSE` file. **This repository carries that same
licence and that same copyright notice**, in `LICENSE`, because it ships umami's own source —
see the next section. MIT permits that as long as the notice travels with it, which it does.

## What was copied, and it is a lot

**`webapp/` is umami's own front end, shipped as it is.** Not reimplemented, not imitated —
the files themselves. `RENDERING.md` R3 in the harness that produced this port requires it: a
port reuses the interface the source already has and changes only where it gets its data, so
that a comparison of the two screens has an answer worth having.

The numbers, from `python probes/interface_diff.py` in `umami-port`:

| | |
|---|---:|
| Files `webapp/` shares with the original | 1,313 |
| Byte-for-byte identical | **1,295** |
| Changed | 18 |
| Added by this port | 6 |
| The original's server half, not shipped here | 364 |

Every one of the eighteen changed files is named in that script with a sentence about what
changed in it, and the script exits non-zero if a file outside that list is touched. All
eighteen are the data layer: which address a call goes to, whether a view subscribes or asks
again, and where a response type is imported from. No component, no stylesheet, no route, no
asset and no layout is modified.

The six added files are four build outputs (`.env.local`, `next-env.d.ts`, `public/script.js`,
`public/recorder.js`) and two source files this port wrote: `src/lib/stream.ts`, the hook that
subscribes to a server-sent stream, and `src/lib/api-types.ts`, the response types that used to
be imported from the server half.

**The 364 files not shipped** are umami's API routes, its Prisma schema and client, its database
and cache access, its SQL query modules and its seed and migration scripts. This rebuild answers
those routes instead; it does not contain them.

## What was not copied: the service

Everything under `src/main/java` is this port's own. No file, fixture or block of source from
`umami-software/umami` is present there. What is shared is vocabulary, and it is shared on
purpose, because a rebuild that answers a different route or a different error message is not a
rebuild.

`python toolkit/copied_strings.py umami --source umami-src` finds **1,168 literals of ten
characters or more in `umami-akka`, 346 of which also occur in `umami-src`**. The full list is
`umami-port/bench/shared-literals.txt`. They fall into eight kinds and every kind is accounted
for here:

- **Route paths (18 named literally, and the rest of the 129 built from segments)** —
  `/api/auth/login`, `/api/2fa/verify`, `/api/websites/{websiteId}/event-data-pivot` and so on.
  A port whose addresses differed would not be a port; the whole of `probes/route_census.py`
  exists to check that all 129 of them match.
- **Header names (26)** — `authorization`, `x-umami-cache`, `x-umami-share-token`,
  `cf-connecting-ip`, `x-vercel-ip-country` and the rest of the address and geography headers
  umami reads, plus the three it *writes* on every answer: `Content-Security-Policy`,
  `X-DNS-Prefetch-Control` and `Strict-Transport-Security`. These are the wire, not umami's
  prose; several are other companies' names for their own headers and three are the web's own.
- **Header values, and the content policy (6)** — `GET, DELETE, POST, PUT`, `'self' https:`,
  `connect-src `, `frame-ancestors `, `max-age=63072000; includeSubDomains; preload`. The whole
  content policy is composed in umami's `src/lib/csp.ts` out of seven directives and two
  settings, and this rebuild composes the same seven from the same two settings so a caller of
  either receives the same policy. A policy that differed would permit a different set of
  things, which is the behaviour being copied. The sixth, `/relative/path`, is not umami's
  text at all: it is an address this rebuild's own test uses for a setting that is a path
  rather than an address, and it occurs in the original's test data too.
- **Messages a caller sees (46)** — `Website not found.`, `Exactly one of website, link, or
  pixel must be provided`, `Value must not start with =, +, -, @, tab, or carriage return`,
  `You must be the owner/manager of this team.` and so on. **These are umami's own wording and
  are reproduced deliberately**, because a client that switches from the original to this
  rebuild reads the same sentence. Each was checked by running the original and recording what
  came back (`umami-port/docs/question-log.md`), not transcribed from its source. Some are not
  umami's at all — `Android OS`, `Windows 10`, `Los Angeles`, `Facebook / Meta`,
  `Google Ads`, `TikTok Ads`, `Twitter Ads (X)`, `LinkedIn Ads`, `Microsoft Ads` — those are
  the names of other people's products, which both systems spell the only way they can be
  spelled.
- **Patterns (14)** — the domain regular expression, the date-range grammar, the
  sixty-four-hexadecimal-character key test, the session-hash shapes. A pattern that differed
  would accept a different set of inputs, which is the behaviour being copied.
- **Permission names (8)** — `website:create`, `website:transfer-to-team`, `team:delete` and
  the rest. They appear in stored roles and in API answers.
- **Campaign parameters (8)** — `utm_source`, `utm_medium`, `utm_campaign`, `utm_content`, and
  the `utm_medium=cpc` / `utm_source=google` values the channel classifier reads. `utm_*` is a
  web-wide convention, not umami's invention; the specific values are the rules its channel
  classifier applies.
- **Vocabulary (222)** — dimension names (`utmCampaign`, `referrerDomain`, `distinctId`),
  operator names, data-type names, report type names, browser and operating-system identifiers
  from the user-agent tables, and the field names of every JSON body the API exchanges. These
  are the surface itself.

Two literals in the list are neither: `' is unavailable'` and `' missing from '` are fragments
of this port's own sentences that happen to occur inside longer English in the clone.

## One more thing that was copied: the robot list

`src/main/java/io/akka/umami/lib/Bots.java` holds **209 regular expressions taken verbatim from
the [`isbot`](https://github.com/omrilotan/isbot) package**, version 5.2.1, which is what umami
itself asks whether a user agent is a robot. `isbot` is MIT-licensed, copyright (c) Omri Lotan.
The patterns were exported from the copy installed in `webapp/node_modules` by
`umami-port/probes/generate_bots.py` and written into a Java source file; the file says so, and
so does this.

This is a deliberate copy rather than a reimplementation, and the reason is measurable. This
port's first attempt was sixty substrings chosen to look like that list, and on a set of
thirty-one user agents driven through both systems it called two ordinary browsers robots —
`Cubots/1.0` and `CamScanner/5.0`, because they contain `bot` and `scan`. umami accepts a
robot's request with 200 and stores nothing, so calling a browser a robot throws that visitor's
traffic away and reports nothing anywhere. The real patterns are `(?<! cu)bots?(?:\b|_)` and
`(?<!cam)scan`; the lookarounds are the whole of the difference and cannot be approximated.
With the real list, 31 of 31 agree (`umami-port/bench/bot-check.json`).

One of those 209 patterns is the word `playwright`, which also occurs in the clone — in
its own test configuration, where it is the name of the browser driver rather than a
robot pattern. The two are the same word about different things.

## Behaviour is derived even where no text was copied

The Java is this port's own; the rules it implements are umami's. Which event types count
towards a page-view total, when a visit rolls over, what makes a bounce, how a session
identifier is derived, how a channel is classified, what each of the ten reports computes, what
the permission model allows, what the second-factor ceremony does — every one of those was
established by **running the original** and is recorded with the probe that produced it in
`umami-port/docs/question-log.md` and specified in `umami-port/specs/SPEC-001-umami.md`. The
implementation is new work; the specification it implements is a description of somebody else's
program.

## Also used

- **Akka SDK** (`io.akka:akka-javasdk-parent` 3.6.3) — Business Source Licence 1.1, Lightbend
  Inc. The runtime and components this is built on.
- **at.favre.lib:bcrypt** 0.10.2 — Apache 2.0. The original stores account passwords and
  two-factor backup codes as bcrypt hashes at cost 10, and a rebuild has to accept a database
  the original wrote.
- **com.google.zxing:javase** 3.5.3 — Apache 2.0. The two-factor setup hands back a scannable
  image of the provisioning address.
- **Next.js, React and the packages `webapp/package.json` names** — those are the original's own
  dependency list, unchanged except for the server half's entries being removed.

---

## The full list of shared literals

Every one of the 336, under the heading whose sentence above accounts for it. Generated
from `python toolkit/copied_strings.py umami --source umami-src`; the same list is in
`umami-port/bench/shared-literals.txt`.

### Route paths (18)

```
/api/2fa/disable
/api/2fa/setup/confirm
/api/2fa/setup/initiate
/api/2fa/status
/api/2fa/verify
/api/admin/users
/api/auth/login
/api/auth/logout
/api/auth/verify
/api/config
/api/links
/api/pixels
/api/record
/api/scripts/telemetry
/api/teams
/api/teams/
/api/users
/api/users/
```

### Header names (26)

```
Content-Security-Policy
Strict-Transport-Security
X-DNS-Prefetch-Control
authorization
cf-connecting-ip
content-type
fastly-client-ip
true-client-ip
user-agent
x-appengine-user-ip
x-client-ip
x-cluster-client-ip
x-custom-ip
x-forwarded
x-forwarded-for
x-nf-client-connection-ip
x-umami-cache
x-umami-client-city
x-umami-client-country
x-umami-client-ip
x-umami-client-region
x-umami-share-context
x-umami-share-token
x-vercel-ip-city
x-vercel-ip-country
x-vercel-ip-country-region
```

### Header values, and the content policy umami composes (6)

Every answer umami gives from `/api` carries a fixed set of headers, and their values are as
much a part of what a caller receives as any body. These are umami's own — the whole content
policy comes out of `src/lib/csp.ts`, which composes it from seven directives and two settings,
and this rebuild composes the same seven from the same two settings so that a caller of either
receives the same policy. `SPEC-001` R147; the workload `api-response-headers` puts them to the
running original.

```
'self' https:
GET, DELETE, POST, PUT
connect-src 
frame-ancestors 
max-age=63072000; includeSubDomains; preload
/relative/path
```

The last is not umami's text: it is an address this rebuild's own test uses to check that a
setting which is a path rather than an address contributes nothing to the policy, and it happens
to appear in the original's own test data as well.

### Messages a caller sees (44)

```
Android OS
Bad request
Board cannot be cloned.
Board contains inaccessible entities.
Board contains invalid saved reports.
Code already used
Content-Type, x-umami-cache
Current password is incorrect
Exactly one of website, link, or pixel must be provided
Facebook / Meta
GET, POST, OPTIONS
Google Ads
Incorrect password
Invalid backup code
Invalid session token.
Invalid timezone
Invalid unit
Invalid verification code
LinkedIn Ads
Los Angeles
Microsoft Ads
Missing session token.
No pending 2FA setup found
Payload too large
Server error
Service unavailable
TWO_FACTOR_ENCRYPTION_KEY is missing or invalid
Team not found.
That share ID is already taken.
That slug is already taken.
The User does not exists on this team.
TikTok Ads
Updated tracker endpoint: 
User already exists
User is already a member of the Team.
User is already a team member.
User not found
Website not found.
Windows 10
You cannot delete yourself.
You do not have permission to modify this user.
You do not have permission to remove this user.
You must be a member of this team.
You must be the owner/manager of this team.
```

### Patterns (14)

```
(xn--)?[a-z0-9-_]{2,63})$
/* telemetry disabled */
Twitter Ads (X)
Value must not start with =, +, -, @, tab, or carriage return
[0-9a-fA-F:.]+
^(?<num>[0-9-]+)(?<unit>hour|day|week|month|year)$
^(localhost(:[1-9]\\d{0,4})?|((?=[a-z0-9-_]{1,63}\\.)(xn--)?[a-z0-9-_]+(-[a-z0-9-_]+)*\\.)+
^[0-9A-F]{16}-[0-9A-F]{16}$
^[0-9a-fA-F]{64}$
^[0-9a-f]{128}$
^[0-9a-f]{32}$
^[0-9a-zA-Z]+$
document.body.appendChild(i);})();
for=(\\[?[0-9a-fA-F:.]+]?)
```

### Patterns, as the Java source writes them (2)

The same two patterns as above, with the escape doubled the way a Java string literal writes it.

```
^(localhost(:[1-9]\d{0,4})?|((?=[a-z0-9-_]{1,63}\.)(xn--)?[a-z0-9-_]+(-[a-z0-9-_]+)*\.)+
for=(\[?[0-9a-fA-F:.]+]?)
```

### Permission names (8)

```
team:create
team:delete
team:update
website:create
website:delete
website:transfer-to-team
website:transfer-to-user
website:update
```

### Campaign parameters (8)

```
utm_campaign
utm_content
utm_medium
utm_medium=cpc
utm_medium=paid
utm_medium=paid_social
utm_source
utm_source=google
```

### Vocabulary (222)

```
 is unavailable
 missing from 
/daterange
/event-data-pivot
/event-data/fields
/event-data/values
/pageviews
/session-data-pivot
/session-data/array-series
/session-data/date-series
/session-data/numeric-series
/session-data/numeric-stats
/session-data/property-series
/session-data/stats
/sessions?
/undefined
0123456789abcdef
2026-01-01T00:00:00Z
2026-07-01T00:00:00Z
2FA is already enabled
2FA is not enabled
2FA is required and cannot be disabled
41e2b680-648e-4b09-bcd7-3e2b10c06264
::ffff:1.2.3.4
ALLOWED_FRAME_URLS
APP_SECRET
Access-Control-Allow-Headers
Access-Control-Allow-Methods
Access-Control-Allow-Origin
Access-Control-Max-Age
America/New_York
Asia/Calcutta
Asia/Kolkata
Authorization
CLIENT_IP_HEADER
CLOUD_MODE
COLLECT_API_ENDPOINT
COLLECT_API_HOST
CORS_MAX_AGE
Cache-Control
Content-Type
DATABASE_URL
DEFAULT_CURRENCY
DEFAULT_LOCALE
DISABLE_BOT_CHECK
DISABLE_LOGIN
DISABLE_TELEMETRY
DISABLE_UPDATES
FAVICON_URL
GEOLITE_DB_PATH
JBSWY3DPEHPK3PXP
PIXELS_URL
PRIVATE_MODE
REMOVE_TRAILING_SLASH
SALT_ROTATION
SKIP_LOCATION_HEADERS
TRACKER_SCRIPT_NAME
TRACKER_SCRIPT_URL
TWO_FACTOR_ENCRYPTION_KEY
UMAMI_PASSWORD
UMAMI_SELF_RECORD
UMAMI_SELF_TRACK
UMAMI_USER
USE_UUIDV7
Unauthorized
^[A-Z2-7]+$
accessCode
allowFilter
attribution
backupCode
backupCodes
bad-request
blockSelector
cf-ipcountry
cf-region-code
change-password
chromium-webview
chunkCount
chunkIndex
cloudfront-viewer-city
cloudfront-viewer-country
cloudfront-viewer-country-region
comparison
components
createUser
currentPassword
data:image/png;base64,
description
dimensions
displayName
distinctId
distinct_id
do-connecting-ip
duckduckgo.
edge-chromium
eo-ipcountry
eo-region-code
eventCount
eventIndex
eventProperties
event_name
event_type
excludeBounce
faviconUrl
first-click
for=192.0.2.60;proto=http;by=203.0.113.43
globalRequired
hasSubscription
heatmapEnabled
heatmapSampleRate
heatmap_disabled
includeTeams
incorrect-username-password
instagram.
ios-webview
isConfigured
isRequired
issuer=Umami
last-click
li_fat_id=
lockedUntil
mail.yahoo.
maxDuration
maxResults
middle-click
minDuration
newPassword
no-cache, no-store, must-revalidate
node-fetch
not-a-uuid
numberValue
ob_click_id=
opera-mini
organicSearch
organicShopping
organicSocial
organicVideo
otpauth://totp/
pageTitles
page_title
paidSearch
paidShopping
paidSocial
paid_social
parameters
partial-auth
partialToken
payload-too-large
payload_too_large
percentage
performance
pinterest.
privateMode
production
properties
propertyKeys
propertyName
propertyValues
protonmail.
public, max-age=60, stale-while-revalidate=300
qrCodeDataUrl
recorderEnabled
recorder_disabled
referrerDomain
referrerPath
referrerQuery
referrer_domain
registered_country
replayConfig
replayEnabled
replay_disabled
requiredReason
requiresTwoFactor
retargeting
returnVisitors
sampleRate
server-error
service-unavailable
sessionData
sessionDeletionEnabled
sessionLink
sessionLinkId
shareToken
sortDescending
stitchedSessionCount
stringValue
subdivisions
team-manager
team-member
team-not-found
team-owner
team-view-only
telemetryDisabled
totalSessions
total_sessions
trackerScriptName
two-factor-error-already-enabled
two-factor-error-code-used
two-factor-error-disable-not-allowed
two-factor-error-incorrect-password
two-factor-error-invalid-backup-code
two-factor-error-invalid-code
two-factor-error-invalid-partial-token
two-factor-error-missing-token
two-factor-error-no-pending-setup
two-factor-error-not-configured
two-factor-error-not-enabled
two-factor-error-too-many-attempts
twoFactorAuth
twoFactorRequired
twoFactorRequiredGlobal
unauthorized
uniqueEvents
unique_count
unlimitedWebsites
update-tracker
updatesDisabled
utmCampaign
utmContent
websiteIds
yandexbrowser
yyyy-MM-dd HH:00:00
```
