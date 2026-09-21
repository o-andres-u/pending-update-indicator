# Pending Template Updates — Design Document (Part 1)

Show firms which engagement files have pending template updates, with a human-readable summary sufficient to apply or decline. Applying content is out of scope.

*Companions: `target-architecture-diagrams.md` (Figures 1–2), `current-state-architecture.md` (what exists today).*

## 0. Assumptions

Everything here is given in the brief, or is one of these three:

- **A1 — ceiling $8,000/month**, inclusive of inference. «$X» is blank in the brief; this is ≈ $2/firm/month, and the design lands 2.3× under, so the conclusion tolerates estimate error.
- **A2 — the engagement-load dependency exposes a configurable cap on concurrent loads, default 16**, owned by that team and changeable without redeploying us. "Limited capacity" is unquantified; a contract is the only honest response.
- **A3 — engagement identifiers are enumerable cheaply**, without loading each file. Derived, not invented: "see at a glance which of their engagement files have pending updates" presupposes the product lists them. **Verify first** — if false, the sweep has no work list.

I would challenge the real-time requirement ([§5](#5-tradeoffs-riskiest-part-omissions)).

## 1. Architecture and data ownership

Template versions are global; engagement state is per-firm, region-pinned, and costs ~60s to read. Rather than erase that boundary, put a queryable **projection** of the engagement side beside the template side and keep every expensive read behind one gate. **See Figure 1.**

The global plane owns template facts and never stores a firm or engagement identifier; the regional plane owns firm facts. The Engagement Management System stays the sole writer of engagement truth — the index is derived and has no authority.

Residency then falls out rather than being retrofitted: template content is not firm-specific, so summaries are generated once globally and replicated read-only into the EU and Canada, firm data never leaves its region, and **no engagement content is ever sent to a language model** — in a client-confidential audit domain, the property that makes this approvable. One signal crosses: the **occupancy feed**, the bare set of `(templateId, version)` pairs some engagement occupies, with no firm identifiers or counts.

1. **Fan-out writes nothing per engagement.** A publish writes ~50 rows to Available Updates; the per-engagement answer is a **read-time join** against it. Cost and publish latency scale with live version count, not 800,000 engagements.
2. **The slow dependency has one caller** — the worker — so **A2**'s budget is enforced in one place. Steady state never calls it.
3. **Events for freshness, reconciliation for truth** — hooks are never trusted for correctness.

## 2. Correctness and production evolution

**What "declined" pins.** A decline pins **one node in the version graph — not a frontier, not a set of changes.** The decision log appends `(engagementId, templateId, declinedVersion, decidedAt, decidedBy)`. An update is pending when some version is latest on the engagement's branch, descends from its current version, is not withdrawn, and is not in its declined set. Because the rule tests the *latest node* rather than the changes it carries, the brief's case resolves: a firm that declined v5 is offered v7, with a `current → v7` summary including v5's changes. Declining v5 declined v5, not its content forever. Accumulation follows — several publishes before a decision do not queue several decisions; the engagement is offered the single latest version with one cumulative summary, which works only because summaries are keyed on the `(fromVersion, toVersion)` pair.

**Withdrawal** breaks naive designs: it marks the node, recomputes latest-version pointers, and invalidates Available Updates rows in the same write. Affected summaries are tombstoned, never deleted — a user may have acted on one in March.

**Unreliable delivery.** A transactional outbox writes each event in the same transaction as its state change, so none is lost relative to a commit; delivery is at-least-once, consumers idempotent via a monotonic `(engagementId, sourceSequence)`. That makes the index fast, not correct. Correctness comes from the worker re-reading a random sample through the slow path and publishing the divergence rate.

**Migration.** Against 800,000 live engagements the index starts empty, so check-state is **tri-state** — `UPDATE_AVAILABLE`, `UP_TO_DATE`, `NOT_YET_CHECKED` — never a boolean. **The most important product decision here:** "up to date" on an unchecked engagement tells an auditor no update is outstanding when one may be, so the unknown state must be visible and must not look reassuring. Rollout is four phases: dark-deploy behind a flag; **opportunistic capture**, recording the version whenever a user opens a file, at zero marginal cost since the load already happened, so active engagements self-populate without touching **A2**; a **prioritised sweep** for the cold tail; then enablement per firm, most skewed last. Rollback at any phase is a flag flip, and the index is derived, so rebuilding loses no firm data.

## 3. Scale, cost and operational characteristics

**Given:** 4,000 firms · 800,000 engagements · 40 products · median 40 per firm, largest ~40,000 · ~1 publish/week/product.

**Live versions per product** is not given and distorts everything downstream. Templates publish weekly, an engagement covers "the course of a year", and the brief counts 800,000 *active* files — so one on v1 in January is still on v1 when v52 ships: ~52 live versions, ~51 pairs per publish.

```
Publishes            40 products × 1/week                    = 40/week ≈ 173/month
Engagements/product  800,000 / 40                             ≈ 20,000
Summaries/month      40/week × ~51 occupied pairs × 52/12     ≈ 8,840
Cost per summary     ~20,000 in @ $3/M + ~1,000 out @ $15/M   = $0.075 → budget $0.10
Inference            8,840 × $0.10 = $884; with 3× retry/eval ≈ $2,652/month
```

Per engagement instead: `20,000 × $0.10 × 173 ≈ $346,000/month` — **~390× more for identical output**, since template content is not firm-specific. The ratio is **independent of token price and count** (per-summary cost cancels), so it survives both estimates above being wrong.

**Occupancy filtering, not a version cap.** Summarising every pair in a product's full history costs $2,652 in year one, $5,356 in year two and $8,060 by year three — breaching the ceiling on inference alone. Cost grows with history, which grows forever; the useful population does not. Generating only for occupied pairs holds it near 52 per product indefinitely, so **cost is flat over time rather than linear in history**. A cap is worse: twelve versions costs ~$624/month but denies a summary to any engagement over three months old — most of the population. An engagement on an unoccupied version is summarised on demand at first read.

Everything else is immaterial — index ~240 MB, Available Updates ~2,080 rows, artifacts ~6.4 GB/year, containers and replication across three regions ~$750/month. Total ≈ **$3,400/month against $8,000 (A1)**, 2.3× headroom, inference dominant. The budget now rests on the two least certain numbers here: measure both against ten real diffs before committing.

**Skew.** The largest firm has ~40,000 engagements, so the glance view reads the Firm Rollup rather than scanning files. Distinct `(templateId, version)` pairs are bounded by 40 × 52 ≈ 2,080 whatever the firm size, so a thousandfold difference in engagement count costs at most fiftyfold more work.

```
Full sweep  800,000 × 60s = 13,333 pod-hours
  concurrency 16 (A2), continuous ≈ 833 h ≈ 35 days | concurrency 8, off-peak ≈ 139 days
```

Thirty-five days of another team's capacity is not a reasonable ask, and this design does not make it: opportunistic capture covers what users touch for free, the sweep takes the rest at whatever rate that team grants, `NOT_YET_CHECKED` being the public burn-down. **The scarce resource is not dollars but another team's capacity.**

**Objectives:** indicator freshness p99 < 30 s; summary availability p99 < 10 min; glance latency p99 < 300 ms at any firm size; index divergence < 0.1 %; coverage-gate pass rate > 99 %. Every stateful component is derived and rebuildable except the append-only decision log — the only system of record needing backup.

## 4. Generating and evaluating the summaries

A publish triggers ~51 independent generations, one per occupied `fromVersion`, each the cumulative diff to the new version. **The language model does phrasing; it does not do selection.** Which JSON paths changed, how they group, and which are material are computed deterministically. A model that decides what is worth mentioning will eventually decide something material is not. **See Figure 2.**

**The coverage gate is mechanical, not a model judging a model.** Every summary item declares the JSON paths it covers; we assert the union equals the changed set. A summary that silently drops a change fails a set comparison, not a subjective evaluation. On failure, retry once, then fall back to a templated summary — duller, but structurally incapable of omitting anything. A golden set of historical version pairs with expert-written expected output guards prompt and model changes; fallback rate is alarmed.

**Defending a March summary in November.** Artifacts are immutable and content-addressed, the identifier hashing the inputs with prompt and model version, each storing its **input diff alongside its output**, so the chain is complete without re-deriving anything. Regeneration never mutates: a better prompt yields a *new* artifact, the old stays addressable. A **display log** records which artifact was shown to whom when, and the decision log records the decision against that same artifact — so November is answered by byte-for-byte retrieval joined to a decision, not by re-running a model that no longer exists. Model deprecation is why this cannot rely on reproducibility.

## 5. Tradeoffs, riskiest part, omissions

**I would challenge "real time — within seconds of a template being published"**, splitting it and accepting half. The *indicator* in seconds is free — a publish writes ~50 rows to a ~2,080-row table — so I commit to p99 < 30 s. The *summary* in seconds I refuse, proposing p99 < 10 minutes: not because seconds are hard but because there they have negative value, as publishes are weekly and firms deliberate over days. Buying seconds means generating ~51 summaries synchronously inside the publish transaction, coupling publishing to model availability — an inference outage becomes a publishing outage — and removing the time budget for the retry and coverage gate.

**Other tradeoffs.** The index is eventually consistent, mitigated by reconciliation rather than strong consistency across a boundary that does not support it. Summaries are per-version-pair, so untailored to a firm's own edits — ~390× cheaper, losing nothing since template content is not firm-specific.

**Riskiest: a summary that omits a material change.** A practitioner declines an update; in November a regulator asks why a required methodology change was never applied. "Our summary did not mention it" is an unrecoverable defect in the audit file — the decision is already signed. Everything in [§4](#4-generating-and-evaluating-the-summaries) exists for this. Close second is the index reporting `UP_TO_DATE` for an engagement that is not: the same failure in different clothes, and why `NOT_YET_CHECKED` is visible rather than an optimistic default.

**Left out.** Applying template content (out of scope, and where the hard merge problems live). **Conflict detection between a firm's edits and inbound changes — the most significant omission** — since it needs engagement content, precisely the expensive, confidential thing this design avoids. Bulk apply/decline, wanted by the 40,000-engagement firm. Notifications. And the authoring experience for branches and withdrawals, which I assume the version graph can be told about.
