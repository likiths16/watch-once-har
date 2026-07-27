# Decisions

This is a log of the real choices made building this, why, and what was cut — not a design
doc written after the fact. Section order roughly follows the build order.

## Problem interpretation

"Learn a user's process by watching them, then do it for them" is broad. The brief scopes it
to one specific, defensible interpretation: **a web task is, underneath, a sequence of HTTP
API calls; that sequence is the task in its most stable form.** So capture is a HAR export
(free — browsers already do it), and the product is entirely about what happens *after*
capture: generalizing two demonstrations into a reusable workflow, and replaying it.

**Why this over watching the DOM/UI** (what a click-recorder or browser extension would do):
a UI recording breaks the moment the frontend re-renders a button or renames a CSS class. The
API calls underneath usually don't change nearly as often, and replaying is a language-neutral
HTTP request — no browser, no Playwright/Selenium binary, no headless-Chrome flakiness. That's
the product argument and it's the reason replay uses plain `java.net.http.HttpClient` and
nothing else.

**What this leaves out on purpose**: tasks that are meaningfully expressed by mouse/keyboard
interaction rather than API calls (drag-and-drop reordering, canvas drawing, anything a
GraphQL-single-endpoint app expresses as one opaque POST with no discoverable structure — see
the GraphQL note below), desktop app automation, and computer-vision-based recording. All are
legitimate answers to the same prompt; each is its own project.

## The hard sub-problem: cross-request dependency chaining

This is where most of the build time went, per the brief's instruction to go deep on one
thing rather than shallow on several.

### Two demos, diffed, not one demo guessed

Exactly like the brief's approach: generalizing requires knowing which parts of a captured
sequence are "the shape of the task" versus "the data that changes." A single recording can't
answer that on its own without heuristics that guess wrong constantly (does this field look
like an email? a number? free text? none of that tells you whether a dropdown selection was
incidentally the same value twice or is *always* that value). Two demonstrations with
different data, diffed, answers it without guessing: identical across both = structural;
different = a variable. This is the same idea applied at the HTTP-call level instead of the
DOM-event level.

- **What was cut**: alignment between the two demos is purely by request *index* — request
  `i` in demo 1 pairs with request `i` in demo 2. There's no sequence alignment (edit
  distance / LCS) to tolerate the user taking a slightly different path the second time (an
  extra retried call, a different call order). Real re-demonstrations of the same task are
  almost always same-shape in the number and order of API calls, so a clear, actionable error
  ("demos don't look like the same task, redo the second one") was chosen over building
  alignment for a comparatively rare case. `Generalizer.generalize` fails fast with a specific
  message when the call count, an HTTP method, or a URL path's segment count doesn't match
  between the two demos at the same index — see `mismatchedPathShapeIsAHardError` and
  neighboring tests in `GeneralizerTest`.

### Dependency detection: the actual algorithm

For every value in every request (URL path segment, query param, header, JSON body leaf),
`Generalizer` runs a decision in a fixed order, implemented in `diffValue`:

1. **Dependency check first.** Does this value match a value from an *earlier response in
   the same demo* — checked independently in demo 1 and demo 2 (`findMatch` against a running
   per-capture index built by `flattenResponseLeaves`/`JsonPaths.flatten` as each step is
   processed)? A match is only trusted as a real dependency if **both** demos agree on the
   exact same source (same step index, same JSON path) — see
   `idReturnedByEarlierResponseIsADependencyNotAVariable` and
   `dependencyWinsEvenThoughValueAlsoDiffersBetweenDemos` in `GeneralizerTest`. This runs
   *before* the variable check specifically because the brief calls out that dependency must
   win even when the value also differs across demos (an auth token is different every login,
   but the user still can't supply it — it has to come from the live response).
2. **Variable check.** If no dependency match, does the value differ between the two demos?
   If so, it's a named `Variable` slot. If not, it's a `Literal`.

**Exact vs. substring matching** (`findMatch`/`splitAroundMatch`): a value might *be* the
referenced value (`"tok_abc123"`) or *contain* it as a substring (`"Bearer tok_abc123"`,
or an id embedded in a URL path segment like `/invoices/inv_9001/pdf`). Substring matches are
represented as `Literal + ResponseRef + Literal` — see
`substringDependencyEmbeddedInLargerStringIsSplitAroundTheReference`. Matching requires a
minimum length of 4 characters on both the candidate and the source value
(`MIN_MATCH_LEN`); shorter strings (`"1"`, `"ok"`, `"true"`) are excluded from dependency
matching entirely, on purpose — a value that short would produce false-positive matches
constantly (a status code, a boolean, a single-digit count) and there's no reasonable
confidence signal at that length to distinguish coincidence from a real reference.

**A value repeated in many places** (the brief's explicit example: an auth token in every
header) falls out of the algorithm for free — each occurrence is checked independently against
the same response index, so the same source gets recorded as a dependency at every site that
uses it; see `tokenRepeatedInManyHeadersIsADependencyAtEveryOccurrence`.

**Ambiguous / inconsistent matches are flagged, not guessed.** If a value matches a prior
response in only one of the two demos, or matches a *different* source in each demo, that's
recorded as a `Warning` rather than silently picked one way — see
`ambiguousPartialDependencyMatchIsFlaggedAsAWarning` and the "present in only one demo" branch
of `diffValue`. This is the brief's "no match found for a value that varies but isn't
user-supplied → flag it" requirement, generalized slightly to cover partial matches too, not
just total misses.

**A real false positive found via end-to-end testing, not a unit test**: `Content-Type:
application/json` was, at first, diffed like any other header — and because
`"application/json"` is exactly 16 identical characters, it matched almost every prior
response's own `Content-Type` header, misclassifying a structural header as a chained
dependency. It still happened to *work* at replay time (the value really was stable), but it
was the wrong classification and it polluted the dependency list with noise on every single
workflow. Fixed two ways together: `Content-Type` was added to `Generalizer`'s
`HEADER_BLOCKLIST` (never diffed at all), and `ReplayEngine.contentTypeFor` derives it directly
from the body template's own kind (`application/json` for a JSON body, `application/
x-www-form-urlencoded` for a form body, the captured MIME type for a raw body) instead of
replaying a captured value. This is arguably more correct than the original design anyway —
Content-Type is dictated by the payload encoding, not by workflow data, so it shouldn't have
been eligible for variable/dependency classification in the first place.

**Response headers and cookies are dependency sources too, not just JSON bodies.** A session
token often comes back as a `Set-Cookie` header, not a JSON field. `flattenResponseLeaves`
indexes both the JSON body (as `$.foo.bar` style paths) and every response header (as
`header:Name`), plus, for `Set-Cookie` specifically, the individual cookie crumb
(`cookie:sessionId`) — see `resolvesCookieDependencyFromSetCookieHeader` in
`ReplayEngineTest`. On the request side, a `Cookie` header is likewise split into individual
`name=value` crumbs and each is diffed independently (`diffCookieHeader`), so one stable
crumb and one session-specific crumb in the same header don't get conflated into one giant
string that always looks "different."

**What was cut**: only one dependency reference is detected per templated string
(`prefix + ResponseRef + suffix`). A value built from *two* different prior responses
concatenated together isn't detected as such — out of scope for the time available. Also cut:
when a JSON array's length differs between the two demos (a different number of line items,
say), there's no per-element diff; the array is kept as demo 1's literal value verbatim (with
a `Warning`) rather than attempting a variable-length items model — see the array-length
branch of `diffJson`. Real invoices/orders very plausibly have this shape, but modeling
"N variable rows" is a materially different, bigger feature (closer to the CSV-batch feature
than to a single dependency edge case) and was consciously left for a v2.

## Architecture

- **`core` (pure Java, framework-free logic)**: `Model`-equivalent records
  (`CapturedRequest`, `Capture`, `Workflow`, `WorkflowStep`, `TemplatedValue`, `ValuePart`,
  `JsonTemplate`, `BodyTemplate`), the HAR parser, and the generalizer. Zero Spring, zero
  `java.net.http` — the one deliberate exception is Jackson's `JsonNode`/`ObjectMapper` (and,
  for polymorphic serialization, `@JsonTypeInfo`/`@JsonSubTypes` on the three sealed
  hierarchies). That's a JSON *library*, not a web framework, and hand-rolling a JSON parser
  to keep the package dependency-free would have been pure busywork with zero bearing on the
  actual hard problem. This is also why a `Workflow` round-trips through Jackson exactly —
  it's what gets persisted as a DB text column and reloaded for every `/runs` call, so this
  isn't cosmetic; `WorkflowJsonRoundTripTest` exists because a silent Jackson
  polymorphism gap here would corrupt every stored workflow.
- **Sealed `Result<T, E>`** (`Ok`/`Err`) instead of exceptions for expected failure paths
  (a HAR that isn't a HAR, two demos that don't align, a run that can't resolve a value): the
  compiler forces an exhaustive `switch`/`instanceof` check at every call site, so a caller
  can't accidentally let a parse failure propagate as an unchecked exception into, say, a
  half-built HTTP request.
- **`run` (also framework-free)**: `ReplayEngine` fires the real sequence with
  `java.net.http.HttpClient` — no browser, no Playwright, because replay is "just HTTP" once
  generalization has produced a template. `CsvUtil` is a small hand-rolled RFC-4180-ish parser
  (quoted fields, embedded commas/quotes, CRLF/LF) rather than a dependency, since batch input
  is exactly "header row = variable names, each row = one run" and a full CSV library would be
  a lot of surface area for that.
- **`web` (Spring Boot)**: thin `@RestController`s over `core`/`run`, plus `Store`
  (`JdbcTemplate` + SQLite). Captures store the *raw uploaded HAR text*, not a serialized
  `Capture` — re-parsing via `HarParser` on read is cheap, deterministic, and avoids a second
  serialization format for a type that holds `JsonNode` fields. Workflows and run results
  *are* serialized directly (they're what `ReplayEngine` and the API actually need back in
  full fidelity).
- **JdbcTemplate + SQLite over JPA/Postgres**: three tables, each one text-blob column read
  and written whole, never queried by its internals — an ORM buys nothing here, and SQLite
  means zero setup for anyone cloning the repo. `spring.datasource.hikari.maximum-pool-size=1`
  is deliberate: SQLite allows exactly one writer at a time regardless of pool size, so a
  bigger pool just trades a clear queued wait for a confusing "database is locked" exception
  under concurrent requests. I'd reach for Postgres the moment this needed multiple app
  instances or genuinely concurrent writers — not before.
- **Demo target server** (`com.watchonce.demo.DemoTargetServer`, built on the JDK's own
  `com.sun.net.httpserver` — zero extra dependency, consistent with the "no browser, no heavy
  binaries" thesis): a small fake "vendor onboarding + invoicing" API (login → create
  customer → create invoice → fetch receipt) that auto-starts alongside the Spring Boot app
  (`DemoServerLifecycle`, port `8089` by default). **This is not part of the product** — Watch
  Once replays against whatever real host a HAR points to. It exists purely so the two sample
  HAR files committed under `samples/` are replayable end-to-end by a stranger (or a grader)
  without first standing up their own backend, both locally and in the deployed instance,
  satisfying the brief's "seed the deployed instance... so it isn't an empty page" note.
- **`SampleHarGenerator`** actually drives the demo server over real HTTP and records the
  genuine request/response pairs into HAR JSON, rather than hand-authoring HAR text — hand
  authoring risks silently drifting from what `HarParser` actually expects (wrong field names,
  wrong nesting) in a fixture nobody would notice was subtly wrong. It's a build-time tool
  (`mvn compile exec:java -Dexec.mainClass=com.watchonce.demo.SampleHarGenerator`), not part of
  the running app, and it deliberately binds to the *same* fixed port (`DEMO_PORT`, default
  `8089`) the live demo server uses, not an ephemeral one — the sample HARs' URLs are baked in
  at generation time, so they have to point at a port something will actually be listening on
  later.

## Seeding the deployed instance

The brief asks for the deployed instance to not be an empty page. `SampleSeeder` runs once at
boot and, only if the `captures` table is empty (a fresh database), uploads and generalizes
the two committed sample HARs automatically — so `GET /workflows` and the dashboard already
show a real, runnable workflow the first time anyone opens the deployed URL, with no manual
step. It's best-effort (a failure just logs a warning, never blocks startup) and idempotent
across restarts (it only acts on a genuinely empty database, so redeploys don't pile up
duplicate seed data). The two sample HARs also had to be duplicated into
`src/main/resources/samples/` for this — the repo-root `samples/` directory is for a human to
point `curl -F file=@samples/...` at directly per the README, but only classpath resources get
packaged into the runnable jar.

## A real deploy failure found on Railway

The first Railway deploy attempt failed at the build step: `dockerfile invalid: docker VOLUME
at Line 23 is not supported, use Railway Volumes`. Railway's builder rejects a Dockerfile that
declares its own `VOLUME` instruction outright, wanting persistent storage attached through its
own UI/config instead. Fixed by dropping the `VOLUME ["/data"]` line entirely — `/data` still
works fine as a mount point for a platform-attached volume without the Dockerfile declaring it
itself; the instruction was doing nothing Railway's own volume attachment doesn't already cover,
and every other host (Render, Fly, plain `docker run -v`) is equally happy with a plain `WORKDIR`
+ writable path and no `VOLUME` line.

## HAR filtering heuristic

A HAR captures every network request a page made, including static assets and full document
navigations — none of that is "the task." `HarParser.isApiEntry` trusts Chrome's
`_resourceType` field (`"xhr"`/`"fetch"` → keep, `"document"`/`"script"`/`"image"`/etc. →
drop) when present, and falls back to file-extension and response-MIME-type heuristics when
it's absent (Firefox/other HAR exporters don't stamp `_resourceType`). This is a judgment
call, not a spec — a hand-rolled API that serves JSON from a path ending in `.html` would be
misclassified — but it's the same heuristic real HAR-based tooling uses, and the alternative
(asking the user to manually tag which of possibly hundreds of HAR entries are "the real
ones") would undermine "capture is free."

**What was cut**: GraphQL. A GraphQL app often makes all its calls to one endpoint
(`POST /graphql`) with the actual "which operation is this" encoded in the request body,
not the URL. The generalizer's request-shape alignment (method + path) would treat every
GraphQL call as "the same call," which is wrong. Detecting and specially handling
`{"query": "...", "variables": {...}}` bodies would be a reasonable v2 feature; it wasn't in
scope here.

## Batch runs

Rows execute sequentially, one full HTTP sequence per row, not in parallel. A production
version handling hundreds of CSV rows against a real rate-limited API would want bounded
concurrency; for the scope here, sequential is simpler, easier to reason about when a middle
row fails, and avoids surprising a demo API with a thundering herd of concurrent logins.

## What was deliberately left out entirely

- **Auth / multi-user.** No login for *this* app; single implicit workspace. A real product
  needs this day one — irrelevant to demonstrating the generalization/dependency problem.
- **Editing a generalized workflow.** If the diff gets something wrong (an ambiguous slot, a
  mis-detected literal), the fix today is re-uploading better demos, not an "edit this field"
  UI. The warnings list is designed to make a wrong guess visible, not to make it fixable
  in-place.
- **Undo/rollback on a failed run.** `ReplayEngine` stops at the first failed or unresolved
  step and reports exactly which one and why, but never attempts to reverse a partial
  submission (e.g., an already-created customer from a run that failed on the invoice step).
  Genuinely task-specific ("how do I undo this API call") and out of scope for the time box.
- **Retry / idempotency keys.** A flaky network blip on step 2 of 4 just fails the run; there's
  no automatic retry, and no idempotency-key support to make a retried "create invoice" call
  safe to repeat. Worth having in a real product; not central to the problem being solved here.
