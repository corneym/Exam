# Sprint 15 — Descriptor Workflow, Richer Search and Measured Performance

Status: design draft

Selected issues:

- #99 — Dashboard workflow for assigning missing Question descriptors
- #94 — Add richer retrieval-oriented filters to Question Search
- #27 — Benchmark broad Question searches before optimisation

Performance sub-issues:

- #109 — Add opt-in runtime performance instrumentation
- #110 — Add repeatable real-corpus performance benchmark runner
- #111 — Investigate Question reconstruction and SQLite hot paths using Sprint 15 measurements

Sprint 15 begins after Sprint 14 Subject-first managed-data work has completed its normal PR/merge closeout.

## 1. Purpose

Sprint 15 returns from the filesystem/data-layout work of Sprint 14 to day-to-day corpus use.

The sprint has three connected goals:

1. make missing Descriptor classification directly resolvable from the Corpus Dashboard;
2. make Question Search more useful for retrieval against the growing real corpus;
3. establish permanent, measurement-led performance diagnostics before attempting database or retrieval optimisation.

The third goal is now important because real use of the growing database is showing noticeable slowdown. Sprint 15 must determine where the time is actually spent rather than assuming that SQLite, JavaFX, PDF rendering or any particular query is responsible.

## 2. Scope and implementation order

Recommended implementation order:

1. #99 — Dashboard missing-Descriptor workflow;
2. #109 — runtime performance instrumentation;
3. #110 — repeatable benchmark runner and pre-#94 baseline;
4. #94 — richer Question Search filters;
5. repeat the same Search benchmarks against the richer #94 workload;
6. #111 — investigate demonstrated hot paths and perform only bounded, evidence-justified optimisation;
7. #27 — close the performance umbrella with recorded before/after findings and any follow-up issues.

This ordering provides a useful baseline before Search changes and keeps optimisation separate from feature implementation.

## 3. Issue #99 — Dashboard Descriptor resolution

### 3.1 Work-count semantics

The Exam-level `Work` value means the number of distinct Questions that require attention, not the number of independent problems.

Therefore a Question with, for example, both a missing Answer and a missing Descriptor contributes one Question to the Exam `Work` count rather than two.

The detailed Question Work presentation may still show the individual unresolved conditions.

### 3.2 Missing Descriptor as Question work

A Question without Descriptor-level classification must be visible in the Dashboard Question Work workflow as an ordinary unresolved condition, preferably through the existing Problem presentation such as:

```text
Missing descriptor
```

Exam and booklet selection must continue to scope Question Work consistently.

### 3.3 Booklet-level Assign Descriptors action

When a selected booklet has `No descriptor > 0`, expose an explicit action such as:

```text
Assign Descriptors
```

The action opens the existing Search/classification workflow with an immutable launch constraint consisting of:

- the selected booklet; and
- Questions currently lacking a Descriptor classification.

This is an operational workflow constraint, not an ordinary editable Search filter.

Questions from another booklet or Exam must not appear.

After a Descriptor is saved, the Question leaves the unresolved set and the workflow should reselect or advance sensibly until the scoped set is empty.

Returning to Dashboard must refresh both:

- booklet `No descriptor`; and
- Exam `Work`.

### 3.4 Reuse one classification workflow

Do not create a second Dashboard-only Descriptor editor.

Dashboard supplies the operational context; the existing Search/classification workflow remains the shared editing boundary.

## 4. Issue #94 — Richer Question Search filtering

Question Search remains a retrieval tool:

```text
Dashboard
    operational work
    What is incomplete/problematic and where do I fix it?

Search
    retrieval work
    Which Questions do I want to inspect or use?
```

Dashboard work-queue conditions such as missing Answer, missing Shared Context or missing Descriptor must not become ordinary Search filters merely because they exist.

### 4.1 Search narrowing precedence

Sprint 13 introduced immutable Exam/booklet narrowing from Dashboard.

That remains the outer boundary.

User-selected #94 filters may narrow the result further but must never broaden outside the launch scope.

Conceptually:

```text
Working Subject
    ↓
immutable Dashboard narrowing, if present
    ↓
current-curriculum Search scope / All Questions mode
    ↓
user retrieval filters
    ↓
results
```

### 4.2 Initial retrieval filter set

The preferred initial dimensions are:

- Exam provider;
- Exam year;
- Assessment/Exam;
- booklet;
- response type;
- revision-output exclusion state.

Current applicability should only receive another explicit control if it adds a clear behaviour beyond the current Search mode and curriculum hierarchy controls.

### 4.3 Search request model

Prefer one immutable Search criteria/request model rather than allowing individual UI controls to manipulate retrieval independently.

The model should preserve:

- authoritative Working Subject;
- optional immutable Dashboard narrowing;
- Search mode/current curriculum scope;
- retrieval filters;
- deterministic ordering.

Existing asynchronous search and stale-request suppression remain required.

## 5. Issue #27 — Performance measurement philosophy

Performance work is measurement-led.

Do not add an index, cache, bulk loader or query rewrite merely because it appears likely to help.

A performance change must have:

1. a pre-change baseline;
2. evidence identifying the bottleneck;
3. a bounded implementation change;
4. an equivalent post-change measurement;
5. unchanged behavioural correctness.

A measured conclusion that no optimisation is justified is a valid outcome.

## 6. #109 — Runtime performance instrumentation

### 6.1 Measurement boundary

For a user-facing operation, measure from the beginning of the workflow until the completed result has been published back to the JavaFX UI.

Example:

```text
user clicks Search / workflow triggers refresh
        ↓
start outer timing
        ↓
background repository/service work
        ↓
result reconstruction
        ↓
Platform.runLater / JavaFX publication
        ↓
complete outer timing
```

The outer timer answers:

> How long did the user wait?

Nested timers answer:

> Where was the time spent?

### 6.2 Diagnostic abstraction

Introduce a small application-level boundary such as:

```text
PerformanceRecorder
PerformanceOperation
```

Conceptual usage:

```java
try (PerformanceOperation operation =
        performanceRecorder.start("question.findAll")) {
    ...
}
```

For asynchronous work, explicitly complete the operation when the result has reached the intended completion boundary.

### 6.3 Initial operation names

User/workflow operations should include, where practical:

```text
dashboard.refresh
search.refresh
workingSubject.refresh
question.preview
revision.build
revision.html.export
scorm.export
```

Repository/service operations should include high-value corpus-size-sensitive boundaries such as:

```text
question.findAll
question.findById
question.findApplicable
corpus.audit
curriculum.load
```

Do not instrument every small method.

### 6.4 Diagnostic output

When enabled, write structured records such as CSV containing at least:

```text
timestamp
operation
duration_ms
success/failure
result/count metadata when supplied
correlation/parent information for nested operations
```

Do not record Question text, Answer text, file contents or other corpus content solely for diagnostics.

Diagnostics are disabled by default and should have negligible behavioural impact when disabled.

## 7. #110 — Repeatable benchmark runner

Normal-use instrumentation reveals what feels slow. A repeatable benchmark runner provides controlled before/after comparison.

Provide an administration/diagnostic entry point such as:

```text
au.edu.eq.questionbank.admin.PerformanceBenchmarkTool
```

It should operate against an explicitly supplied realistic database/data-root copy and use the real application repository/service boundaries.

It must not mutate corpus state.

### 7.1 Initial benchmark operations

Benchmark, where practical:

- `QuestionRepository.findAll()`;
- reconstruction of a known Question with `findById()`;
- current-curriculum Subject search;
- Unit search;
- Topic search;
- Descriptor search;
- Dashboard/corpus data preparation;
- richer #94 filter combinations after they exist.

### 7.2 Iteration model

For each operation:

1. perform at least one unmeasured warm-up;
2. run a configurable number of measured iterations;
3. record elapsed time and result count.

Report at least:

- minimum;
- median;
- p90 or equivalent high percentile;
- maximum;
- result count;
- Question count in database;
- database file size.

Median is the primary comparison statistic. This is an application/database benchmark, not a nanosecond-scale microbenchmark.

### 7.3 Before/after use

The same benchmark definition should be usable:

```text
baseline before #94
        ↓
implement #94
        ↓
repeat richer Search workload
        ↓
identify actual slow path
        ↓
optimise only if justified
        ↓
repeat identical benchmark
```

## 8. #111 — Hot-path investigation

### 8.1 Known candidate: full Question reconstruction

`SqliteQuestionRepository.findAll()` currently follows this broad shape:

```text
SELECT every Question id
        ↓
for each Question id
    findById(id)
        ↓
    reconstruct complete Question state
```

Complete `findById()` reconstruction may require dependent curriculum, content, Answer, SourceQuestion and Shared Context reads.

Curriculum-aware retrieval similarly obtains matching Question IDs and reconstructs matching Questions through `findById()`.

This is a credible N+1/repeated-round-trip candidate as corpus size grows, but Sprint 15 must measure it before redesigning it.

### 8.2 Diagnostic evidence

For demonstrated slow operations, gather as appropriate:

- total elapsed time;
- nested phase timings;
- number of Questions reconstructed;
- SQL statement count;
- connection count;
- `EXPLAIN QUERY PLAN` output;
- existing index inventory;
- Java Flight Recorder evidence if SQL timings do not explain the end-to-end delay.

### 8.3 Possible evidence-led outcomes

Potential changes include:

- bulk Question reconstruction rather than one `findById()` per result;
- using one connection/read transaction across a bulk reconstruction;
- avoiding unnecessary whole-bank snapshots in a workflow;
- query redesign;
- an index justified by the measured query plan.

These are possibilities, not pre-approved implementation requirements.

If measurement points to a larger architectural change, record the evidence and create a separate follow-up issue.

## 9. Performance result format

A useful benchmark summary should make comparison obvious.

Example only:

```text
Operation: Chemistry Subject Search
Questions in bank: 1,842
Results: 1,397
Runs: 10

Median: 1,265 ms
P90:    1,410 ms
Min:    1,198 ms
Max:    1,431 ms
```

Nested instrumentation might explain the same operation as:

```text
search.refresh                 1,320 ms
    curriculum.expand            18 ms
    sqlite.applicability        126 ms
    question.reconstruct      1,131 ms
    result.assembly              24 ms
    javafx.publish               21 ms
```

The actual implementation may use different operation names, but stable naming is required for comparison.

## 10. Test strategy

### #99

Cover:

- distinct-Question Work counting without double-counting multiple problems;
- missing Descriptor appearing as Question work;
- booklet-scoped Assign Descriptors launch;
- strict exclusion of Questions outside that booklet;
- classification persistence;
- advancement/reselection after Save;
- immediate Dashboard count refresh on return.

### #94

Cover:

- each filter independently;
- deterministic combinations;
- Working Subject authority;
- immutable Dashboard narrowing precedence;
- broadening prevention;
- asynchronous refresh;
- stale-request suppression;
- refresh after classification/edit without losing valid filter state.

### #109

Cover:

- timing lifecycle;
- nested/correlated operations;
- asynchronous completion;
- failure recording without changing failure propagation;
- disabled/no-output behaviour;
- safe structured output.

### #110

Cover:

- command/argument validation;
- warm-up exclusion;
- statistics calculation;
- benchmark selection;
- read-only operation;
- deterministic result counts for a fixed fixture.

### #111

Use existing retrieval/reconstruction regressions as the behavioural boundary for any optimisation. Add focused regressions where a changed bulk-loading/query path introduces new risk.

## 11. Manual acceptance

Using a realistic copy of the Chemistry corpus:

1. enable diagnostics;
2. use Dashboard, Search, Working Subject and preview workflows normally;
3. verify diagnostic records are created and contain timing/count metadata only;
4. identify visibly slow operations and compare their nested phase timings;
5. run the repeatable benchmark suite and retain the baseline;
6. implement #94;
7. rerun the same benchmark definitions plus representative new filter combinations;
8. investigate the dominant demonstrated hot path;
9. if a bounded optimisation is implemented, rerun the same benchmark and record before/after evidence;
10. manually confirm that Search/Dashboard behaviour remains correct.

## 12. Out of scope

Sprint 15 does not include:

- speculative database indexing;
- generic caching without measured need;
- distributed worker/coordinator packages;
- Exam Builder;
- printable assessment generation;
- replacing SQLite;
- production telemetry or remote performance reporting;
- recording corpus content in diagnostics;
- a general-purpose Java profiler UI.

Java Flight Recorder may be used as a developer diagnostic when needed but is not being reimplemented inside the application.

## 13. Definition of done

Sprint 15 is complete when:

1. #99 provides a direct Dashboard route for resolving missing Descriptor work through the shared classification workflow;
2. Exam `Work` uses the documented distinct-Question counting rule;
3. #94 provides the agreed retrieval-oriented filters without weakening Working Subject or Dashboard narrowing semantics;
4. opt-in performance instrumentation records meaningful real-use elapsed-time evidence;
5. a repeatable benchmark runner records a reproducible baseline against a realistic corpus copy;
6. the richer #94 workload is benchmarked using the same measurement model;
7. the dominant observed slowdown has been identified sufficiently to confirm or reject the current N+1/reconstruction suspicion;
8. any optimisation performed has equivalent before/after evidence;
9. any larger deferred optimisation has a separate issue with supporting measurements;
10. focused tests, full regression suites, Javadoc and CI are green.

## 14. GitHub tracking

Sprint 15 selected issues and performance decomposition:

| Issue | Purpose | Planned order |
|---|---|---:|
| #99 | Dashboard missing-Descriptor workflow | 1 |
| #109 | Runtime performance instrumentation | 2 |
| #110 | Repeatable benchmark runner / baseline | 3 |
| #94 | Richer Question Search filters | 4 |
| #111 | Evidence-led hot-path investigation | 5 |
| #27 | Performance umbrella and final findings | closeout |

#27 remains the performance umbrella rather than duplicating the implementation details of #109–#111.
