# Glossary

Companion to `part1-design-document.md`. Terms are also expanded on first use in the document itself; this file is the full reference.

## Acronyms

| Term | Expansion and meaning |
|---|---|
| **DAG** | Directed acyclic graph. Nodes with one-way links and no cycles — the shape of template version history once markets branch, since a version can have several descendants but never becomes its own ancestor. |
| **JSON** | JavaScript Object Notation. The structured text format in which product template content is stored. |
| **KMS** | AWS Key Management Service. Managed encryption keys. |
| **LLM** | Large language model. The model used to phrase summaries — and, in this design, used for nothing else. |
| **SLI** | Service level indicator. A measured quantity used to judge service health, e.g. index divergence rate. |
| **SLO** | Service level objective. The committed target for an SLI, e.g. "p99 < 30 s". |
| **UI / UX** | User interface / user experience. |

## Terms of art

| Term | Meaning as used here |
|---|---|
| **p99** | The 99th percentile — the value 99 % of observations fall below. "p99 < 30 s" means all but the slowest 1 % complete within 30 seconds. Chosen over an average because averages hide the tail, and the tail is what users complain about. |
| **Concurrency budget** | The maximum number of slow engagement loads permitted in flight simultaneously (assumption **A2**). |
| **Projection / read model** | A derived, query-optimised copy of data owned elsewhere. It holds no authority: if it disagrees with the source system, the source wins and the projection is rebuilt. |
| **Read-time join** | Combining two datasets when a user asks a question, rather than pre-computing and storing every combined answer. Here it is what avoids writing 800,000 rows per publish. |
| **Live / occupied version** | A template version at least one engagement currently sits on. Roughly 52 per product, because publishes are weekly and engagements last about a year — not the same as the number of versions ever published, which grows forever. |
| **Occupancy feed** | The only signal crossing from the regional plane to the global plane: the bare set of `(templateId, version)` pairs some engagement occupies, with no firm identifiers, engagement identifiers or counts. |
| **Occupancy filtering** | Generating summaries only for occupied version pairs, so inference cost stays flat over time instead of growing linearly with published template history. |
| **Cold start** | The first read of a summary that was never pre-generated, because no engagement was known to occupy that version. That reader waits while it is generated on demand. |
| **Transactional outbox** | A pattern where an event is written in the same database transaction as the state change it describes, then relayed by a separate process — so an event can never be lost relative to a committed change. |
| **At-least-once delivery** | A guarantee permitting duplicates but not omissions. It forces consumers to be idempotent. |
| **Idempotent** | Safe to apply repeatedly: processing the same event twice leaves the same result as processing it once. |
| **Eventually consistent** | The index may lag the source system briefly after a change, then converge. Contrast with strongly consistent, which this design does not attempt across the region boundary. |
| **Reconciliation sweep** | A background re-read of a random sample of engagements through the authoritative slow path, to measure how often the projection is wrong. Events provide freshness; reconciliation provides truth. |
| **Tri-state** | The check-state of an engagement is one of `UPDATE_AVAILABLE`, `UP_TO_DATE`, `NOT_YET_CHECKED` rather than a boolean, so "not yet checked" can never be silently rendered as "up to date". |
| **Dark deploy** | Shipping code to production switched off behind a feature flag, so it can be enabled and disabled without a release. |
| **Feature flag** | A runtime switch that turns behaviour on or off without redeploying. The rollback mechanism for every phase here. |
| **Backfill** | Populating the index for the 800,000 engagements that existed before the feature shipped. |
| **Burn-down** | A metric tracked as it shrinks toward zero — here, the count of `NOT_YET_CHECKED` engagements. |
| **Content-addressed** | Identified by a hash of its own contents, so identical inputs produce the same identifier and any change produces a different one. Makes a stored summary tamper-evident. |
| **Tombstoned** | Marked as no longer valid for use while being retained in storage. Applied to summaries for withdrawn versions, which must stay retrievable for audit. |
| **Provenance** | The recorded inputs behind an output: input diff, prompt version, model identifier and version, generation parameters. What makes a March summary defensible in November. |
| **Golden set** | A fixed collection of real historical inputs with expert-approved expected outputs, re-run on every prompt or model change to detect quality regressions. |
| **Coverage gate** | The mechanical check that the union of JSON paths declared by a summary equals the set of paths that actually changed. A set comparison, not a judgement. |
| **Templated fallback** | A deterministic, non-model-generated summary used when the coverage gate fails. Less readable, but structurally incapable of omitting a change. |
| **Pod** | A running unit of the Engagement Management System into whose memory an engagement is rehydrated. "Pod-hours" is the unit of the ~60-second constraint. |
| **Skew** | The uneven distribution of engagements across firms — median 40, largest ~40,000 — which is why the glance view reads a rollup rather than scanning engagements. |
