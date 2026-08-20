# Scenario 3 — Ambiguous: "analytics and reliability features"

**Requirement as given:** "...with analytics and reliability features."

Six words, two undefined nouns, and no acceptance criteria. This is the scenario that
separates asking from guessing. The wrong move is to pick the interpretation that is easiest
to build and never mention that a choice was made.

## Why it is ambiguous

"Analytics" spans a counter and a data platform. "Reliability" spans uptime, degradation
behaviour, and durability — and those three pull in *opposite* directions here. Making
analytics durable means putting a synchronous write in the redirect path, which makes the
redirect less reliable. **The requirement, read literally, contradicts itself.** Surfacing
that is more valuable than resolving it quietly.

## Interpretations considered

### "Analytics"

| Reading | What it implies | Assessment |
| --- | --- | --- |
| A click counter per link | One integer column, incremented on redirect | Too thin to be called analytics, and the obvious implementation is the row-lock failure in [ADR-004](../decisions/ADR-004-async-analytics.md) |
| **Dimensioned click events with aggregate queries** | Append-only event table; totals, unique visitors, daily series, top referrers | **Chosen.** Answers the questions a link owner actually asks, and the counter reading is a strict subset of it |
| Real-time dashboard with segmentation | Streaming pipeline, columnar store, UI | A product, not a service feature. Cannot be built credibly here, and a shallow version is worse than its absence |

### "Reliability"

| Reading | What it implies | Assessment |
| --- | --- | --- |
| An uptime number | An SLO, error budget, alerting | An SLO with no operational history behind it is a number, not a commitment |
| **Graceful degradation of dependencies** | Cache and limiter optional; redirects survive their loss | **Chosen.** Testable, demonstrable, and it is what protects the property users depend on |
| Durable analytics, no click ever lost | Synchronous writes or a durable queue in the path | **Explicitly rejected.** It trades redirect reliability for metric completeness — the wrong direction |

## The decision, and the assumption it rests on

> **Assumption:** for a URL shortener, the redirect is the product. Analytics inform
> decisions; they do not serve users. Where the two conflict, the redirect wins.

Everything else follows: analytics are dimensioned but best-effort; reliability means the
redirect path survives the loss of every optional dependency.

This assumption is **falsifiable in one sentence by a product owner.** If click data feeds
billing or a regulatory report, it is wrong, and the fix is known: replace the in-process
buffer with a durable log and accept the added latency. The component boundary at
`ClickRecorder` exists so that change stays local.

## How the ambiguity is handled in the product, not just the docs

The choice is not buried in a design document nobody reads. Every stats response carries it:

```json
{
  "totalClicks": 3,
  "accuracyNote": "Click counts are best-effort and eventually consistent; recent clicks may lag by a few seconds."
}
```

A caller cannot accidentally treat these numbers as exact, because the payload tells them
not to. That is the difference between documenting an assumption and shipping one.

## Validation

The ambiguity resolution is tested as behaviour: the analytics test asserts that stats read
**zero** immediately after three redirects while the buffer holds three events, then read
three after a flush. If someone later "fixes" the lag by making writes synchronous, that
test fails and points them at this document.

## Questions that would be asked of a product owner

Listed because knowing what to ask is the deliverable when nobody is available to answer:

1. Does click data ever feed billing, contractual reporting, or a regulatory obligation?
2. What is the retention period for click events, and does it fall under a privacy regime?
3. Is a link's destination allowed to change after creation, or is it immutable?
4. Should the same URL submitted twice by the same owner return the same code?
5. Who is allowed to read a link's analytics — anyone with the code, or only its creator?

Question 5 was the sharpest, and it has since been answered. In the original prototype,
anyone holding a short code could read its full stats — a simplification tied to the absence
of authentication (A-4), recorded as a limitation rather than left for a reviewer to
discover. It was then raised as a real requirement and closed: analytics are now readable
only by a link's owner or an administrator
([ADR-008](../decisions/ADR-008-authentication-and-ownership.md)).

Worth noting how that went, since it is the point of this exercise. Because the gap had been
written down with its reasoning attached, implementing the answer was a scoped change rather
than a rediscovery: `created_by` already existed for exactly this purpose, and the ambiguity
register recorded which option had been deferred and why. An undocumented shortcut would have
cost the same work plus the archaeology.
