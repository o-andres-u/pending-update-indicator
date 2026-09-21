# Target Architecture — Diagrams

Companion to `part1-design-document.md`. Referenced there as **Figure 1** and **Figure 2**.
Renders natively on GitHub, in VS Code, and at mermaid.live.

---

## Figure 1 — Pending-update system, target state

Two planes with a single crossing. The global plane holds template facts only and never stores a firm or engagement identifier; the per-region plane holds firm facts and never leaves its region. The only signal crossing the boundary is the **occupancy feed** — the bare set of `(templateId, version)` pairs that some engagement occupies, carrying no firm identifiers and no counts.

The **Fan-out Worker** is the sole caller of the ~60-second engagement-load dependency, so the concurrency budget (assumption **A2**) is enforced in one place. Steady-state operation never calls it; it exists for backfill and reconciliation only. It is the component implemented in Part 2.

```mermaid
flowchart TB
    ContentTeams["Content Teams"]
    FirmUsers["Firm Users"]

    subgraph GlobalPlane["Global plane — template data only, no firm data"]
        TemplateDatabase[("Product Template Database<br/><i>existing</i>")]
        VersionGraph["<b>Template Version Graph</b><br/>version history as a directed acyclic graph:<br/>branches, withdrawals, ancestry, latest-per-branch"]
        AvailableUpdates[("<b>Available Updates</b><br/>(templateId, fromVersion) →<br/>latest version, summary id<br/><b>~2,080 rows total</b>")]
        OccupancySet[("<b>Occupied Version Set</b><br/>pairs some engagement sits on<br/><i>no firm identifiers, no counts</i>")]
        SummaryPipeline["<b>Summary Pipeline</b><br/>diff → deterministic grouping →<br/>language-model phrasing → coverage gate<br/><i>occupied pairs only</i>"]
        SummaryArtifacts[("<b>Summary Artifacts</b><br/>immutable, content-addressed,<br/>retained for audit")]
    end

    subgraph RegionalPlane["Per-region plane — firm data, stays in-region (US / EU / Canada)"]
        EngagementIndex[("<b>Engagement Template Index</b><br/>engagement → templateId,<br/>current version, check-state")]
        DecisionLog[("<b>Decision Log</b><br/>append-only apply/decline")]
        FirmRollup[("<b>Firm Rollup</b><br/>counts by templateId + version")]
        PendingApi["Pending-Updates service"]
    end

    EngagementSystem["<b>Engagement Management System</b><br/><i>existing · ~60s per load · capacity-limited</i>"]
    FanoutWorker["<b>Fan-out Worker</b><br/>sole gate to the slow dependency<br/>backfill + reconciliation only<br/><i>(Part 2)</i>"]

    ContentTeams -->|publish| TemplateDatabase
    TemplateDatabase -->|"publish hook"| VersionGraph
    VersionGraph --> SummaryPipeline
    OccupancySet -->|"which pairs<br/>are worth summarising"| SummaryPipeline
    SummaryPipeline --> SummaryArtifacts
    VersionGraph -->|"recompute latest"| AvailableUpdates
    SummaryArtifacts --> AvailableUpdates

    EngagementSystem -->|"events: created, opened, decided<br/><i>(transactional outbox, at-least-once)</i>"| EngagementIndex
    EngagementSystem --> DecisionLog
    EngagementIndex --> FirmRollup
    DecisionLog --> EngagementIndex
    FirmRollup -.->|"occupancy feed:<br/>distinct (templateId, version) only"| OccupancySet

    AvailableUpdates -.->|"replicated read-only,<br/>template data only"| PendingApi
    FirmRollup --> PendingApi
    EngagementIndex --> PendingApi
    PendingApi -->|"glance view + summary"| FirmUsers
    FirmUsers -->|"apply / decline"| EngagementSystem

    FanoutWorker -->|"bounded concurrency<br/>~60s per call"| EngagementSystem
    FanoutWorker -->|"seed current version"| EngagementIndex
    AvailableUpdates -.->|"prioritise work"| FanoutWorker

    classDef global fill:#eff6ff,stroke:#2563eb,stroke-width:2px,color:#1e3a8a
    classDef regional fill:#fefce8,stroke:#a16207,stroke-width:2px,color:#713f12
    classDef existing fill:#f0fdf4,stroke:#15803d,stroke-width:2px,color:#14532d
    classDef worker fill:#fef2f2,stroke:#dc2626,stroke-width:2px,color:#991b1b
    classDef actor fill:#f1f5f9,stroke:#94a3b8,color:#0f172a
    class TemplateDatabase,VersionGraph,AvailableUpdates,SummaryPipeline,SummaryArtifacts,OccupancySet global
    class EngagementIndex,DecisionLog,FirmRollup,PendingApi regional
    class EngagementSystem existing
    class FanoutWorker worker
    class ContentTeams,FirmUsers actor
```

**Legend.** Blue = global template plane · yellow = per-region firm plane · green = existing system, unchanged · red = the single gate to the capacity-limited dependency · dashed = replication or metadata-only flow.

---

## Figure 2 — Summary generation and the coverage gate

One publish triggers roughly 51 independent generations, one per occupied `fromVersion`, each summarising the cumulative diff from that version to the new one.

The division of labour is the point: **everything blue is deterministic, only the purple step is a language model, and the red step is a mechanical check on it.** Selection — which paths changed, how they group, which are material — never goes near the model. The coverage gate is a set comparison, not a model judging a model: each summary item declares the JSON paths it covers, and the union must equal the changed set. A summary that silently drops a change therefore fails an assertion rather than an opinion, and the fallback is duller prose that is structurally incapable of omission.

```mermaid
flowchart LR
    Publish["Publish<br/>(templateId, vN)"] --> Fanout{"For each <b>occupied</b><br/>fromVersion (~51)"}
    Fanout --> Diff["JSON diff<br/>fromVersion → vN<br/><i>existing capability</i>"]
    Diff --> Group["<b>Deterministic step</b><br/>group + classify changed paths"]
    Group --> Phrase["Language model: phrase each group.<br/>Structured output — every item<br/>declares the paths it covers"]
    Phrase --> Gate{"<b>Coverage gate</b><br/>union of declared paths<br/>== all changed paths?"}
    Gate -->|pass| Artifact["Summary artifact<br/>+ full provenance"]
    Gate -->|"fail, after one retry"| Fallback["<b>Templated fallback</b><br/>'14 changes across 3 sections…'<br/>no narrative, no omission"]
    Fallback --> Artifact
    Artifact --> DisplayLog["Display log:<br/>who saw which artifact, when"]

    classDef deterministic fill:#eff6ff,stroke:#2563eb,color:#1e3a8a
    classDef model fill:#faf5ff,stroke:#7c3aed,color:#4c1d95
    classDef gate fill:#fef2f2,stroke:#dc2626,color:#991b1b
    class Diff,Group,Artifact,DisplayLog,Fanout deterministic
    class Phrase model
    class Gate,Fallback gate
```

**Legend.** Blue = deterministic · purple = language model (phrasing only) · red = mechanical gate and fallback.
