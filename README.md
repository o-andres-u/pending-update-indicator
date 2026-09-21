# Caseware Take-Home — Architecture Design Exercise

Read in this order.

**1. [`part1-design-document.md`](part1-design-document.md) — start here.** The design, 4 pages. Everything else supports it.

**2. [`target-architecture-diagrams.md`](target-architecture-diagrams.md)** — Figures 1 and 2, referenced from §1 and §4. Best opened alongside the document. Kept separate because the brief excludes diagrams from the page limit.

**3. [`current-state-architecture.md`](current-state-architecture.md)** — what runs in production today, before any of this. Context for §1.

**4. [`part2-fanout-worker/`](part2-fanout-worker/)** — Java 21 implementation of the template-publish fan-out worker, the red component in Figure 1. Read its own `README.md` first for the implementation tradeoffs, then the code.

```bash
cd part2-fanout-worker && mvn test     # 26 tests
```

`Problem.pdf` is the original brief.
