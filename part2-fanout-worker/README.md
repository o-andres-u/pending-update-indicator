# Template-Publish Fan-out Worker

Part 2 of the take-home. Java 21, Maven, no runtime dependencies (JUnit 5 is test-scope only).

```bash
mvn test        # 26 tests
```

This is one narrow component, not an application: no HTTP layer, no persistence, no wiring. Every collaborator is an interface, and the tests supply in-memory implementations.

## What it does

Fans a template publish out across the engagements created from that template, recording each one's current template version in the index by way of the ~60-second load call owned by another team.

It is deliberately **not** on the steady-state path. In the Part 1 design, the pending-update indicator is served by a read-time join against a ~2,080-row table and needs no per-engagement work at all. This worker exists for the two jobs that genuinely require the expensive call — **backfill** of the 800,000 engagements that predate the feature, and continuous **reconciliation** sampling that measures whether the derived index has drifted. Making it the single caller of that dependency is what allows the concurrency cap to be enforced and observed in one place.

## Key implementation tradeoffs

**Two gates, not one.** An *admission* semaphore bounds how many units are in flight; a separate `CapacityBudget` bounds how many are actually calling downstream. Conflating them is the obvious simplification and it is wrong: a unit sleeping between retries would go on occupying scarce downstream capacity while doing nothing. Permits wrap only the `load` call and are released before any backoff sleep.

**The capacity cap is resizable at runtime.** Assumption A2 in the design document says the cap belongs to the team that owns the dependency. A constant would put our deploy cycle in the path of their capacity decisions, so `CapacityBudget.setLimit` adjusts a fair `Semaphore` up or down while running. Shrinking withholds future permits until the pool drains rather than interrupting accepted work — we give capacity back without abandoning a load already half-finished.

**Overload is handled globally, not per unit.** A `Transient` failure backs off one unit; an `Overloaded` failure puts the whole budget into a shared cooldown. Every unit shares one downstream, so backing off only the caller that happened to be rejected would keep the other fifteen hammering a struggling dependency. Overlapping signals extend the cooldown and never shorten it.

**Failures are classified by the dependency, not guessed by us.** `DownstreamException` is a sealed hierarchy of `Transient` / `Permanent` / `Overloaded`, so the retry decision is a total function over three cases instead of string-matching error messages. `Permanent` is dead-lettered on the first attempt: spending another minute of shared capacity to fail identically buys nothing. `maxAttempts` defaults to 4 for the same reason — persistent failures belong in a queue for humans, not in a retry loop.

**Idempotency lives in the conditional write, not in the worker's bookkeeping.** Upstream delivery is at-least-once, so a redelivered publish is expected rather than exceptional. Two mechanisms, in order of authority: the index write is conditional on a monotonic `sourceSequence` from the Engagement Management System, so a stale or out-of-order observation can never regress newer state; the `ProgressStore` is then a cheap skip-list that avoids paying ~60 seconds to rediscover what we already know. Correctness comes from the first; cost comes from the second.

**A stale write completes the unit.** When the index rejects our observation as older than what it holds, that is the guard working, not a failure — the index is already ahead of us, so re-reading would burn another minute to lose the same race. The unit is marked done. Getting this backwards produces a unit that retries forever.

**Progress is marked after the write, never before.** A crash in between therefore re-does the unit rather than skipping it. Duplicated work costs a minute; a silently skipped unit means an engagement whose `NOT_YET_CHECKED` state is quietly wrong, and in this domain that is the failure that matters.

**`ProgressStore` is not a lock.** There is no claim or lease protocol, so two workers handed the same publish may both process a unit. That is wasteful but not incorrect, because the write is idempotent and sequence-guarded. Distributed leases would cost more complexity than the duplicate work costs capacity. A second *concurrent* run of the same `publishId` in one process is rejected outright, since it is pure duplication with no upside.

**Virtual threads, bounded by the budget rather than by pool size.** Each unit is ~60 seconds of blocking I/O, so a platform thread per unit would sit idle almost the entire time. Concurrency is governed by the permit pool, which means the thread model and the capacity contract are independent — the budget can be retuned without resizing anything. `ExecutorService` is managed explicitly rather than via try-with-resources, because cancellation needs `shutdownNow` to interrupt in-flight loads and the automatic `close` only awaits them.

**The work list is streamed and admitted under backpressure.** `EngagementDirectory` returns a lazy `Stream`; laziness is in the contract, not an optimisation. A publish can cover ~20,000 engagements and one firm alone holds ~40,000, so materialising the list — or eagerly submitting a task per unit — would put memory pressure in the path of a job whose real constraint is someone else's capacity. A test asserts the directory is never fully drained when a run is cut short.

**Cancellation is a first-class operation.** Published versions can be *withdrawn*. Once that happens the remaining work is not merely unnecessary, it is work toward an answer we no longer want to show, so `cancel` stops enumeration and interrupts in-flight loads. Units already completed stay completed: they are observations of engagement state, which withdrawal does not invalidate.

**Every unit lands in exactly one terminal bucket.** `FanoutReport.balanced()` asserts `enumerated == skipped + completed + deadLettered + abandoned`. A report that does not balance means units were lost, which is precisely the silent failure worth alarming on, so it is checked in most tests rather than left as documentation.

**Full jitter on backoff.** A publish can fail thousands of units against the same dependency in the same instant. Without jitter they retry in lockstep and the retry storm is worse than the original fault. Backoff doubles iteratively against the cap so it cannot overflow, which a test pins at attempt 99.

## What I deliberately left out

- **A circuit breaker.** Bounded concurrency, the shared cooldown, and capped retries already stop us overwhelming the dependency. A breaker would add state and tuning for a marginal gain; the cooldown is the 80% of it.
- **Distributed coordination.** Single-process. Multi-instance safety rests on the idempotent conditional write, as above, rather than on leases or partition assignment.
- **Persistence, transport, configuration, DI, logging.** All behind interfaces. A real deployment supplies a DynamoDB progress store, an SQS dead-letter sink, and a metrics implementation.
- **Rate limiting by throughput.** The cap is on concurrency, not calls per second. With a fixed ~60s latency those are near-equivalent, and concurrency is the quantity the owning team can actually reason about.
- **Prioritisation of the work list.** The design calls for sweeping in priority order; here that is the directory's concern, and this component honours whatever order it is given.

## Test coverage

26 tests, no mocking framework. Notable cases: concurrency never exceeds the budget; a mid-flight budget shrink loses no units; redelivery reloads nothing; permanent failures skip retries entirely; retry exhaustion dead-letters without stalling the publish; a stale observation is terminal and does not regress the index; divergence is detected on re-observation; cancellation stops enumeration early and still balances; the work list is consumed lazily; double-closing a permit cannot leak capacity; a shorter overload signal cannot shorten an active cooldown.

Timing-sensitive tests use a no-delay retry policy and an injected `Sleeper`, so the suite runs in about two seconds and does not depend on wall-clock timing for its assertions.
