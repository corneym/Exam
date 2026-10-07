# Performance Diagnostics and Benchmarking

> Sprint 15 design reference. Exact command-line/property names should be updated here if implementation chooses different names.

## Purpose

The Exam Question Bank uses performance diagnostics to answer two separate questions:

1. what operations are slow during normal real-corpus use; and
2. whether a proposed implementation change actually improves those operations.

Performance work is measurement-led. Diagnostics should identify the slow layer before indexes, caches or query redesign are introduced.

## Runtime instrumentation

Runtime instrumentation measures real workflows while the application is used normally.

A user-facing measurement begins when the operation starts and normally ends after the completed result has been published back to the JavaFX UI.

Example:

```text
Search refresh requested
        ↓
start search.refresh
        ↓
background hierarchy/retrieval work
        ↓
Question reconstruction
        ↓
publish result to JavaFX
        ↓
complete search.refresh
```

Nested operations identify where the outer elapsed time was spent.

Example operation hierarchy:

```text
search.refresh
    curriculum.expand
    question.findApplicable
    question.reconstruct
    search.resultAssembly
    javafx.publish
```

Operation names should remain stable once introduced so results can be compared across runs and commits.

## Enabling diagnostics

Diagnostics must be disabled by default.

Sprint 15 may expose enablement through an application property, JVM property or explicit diagnostic setting. The final implementation should record the exact mechanism here.

When disabled:

- no performance CSV should be created;
- normal application behaviour must be unchanged;
- timing overhead should be negligible.

## Diagnostic data

A performance record should contain only information required for analysis, such as:

```text
timestamp
operation name
duration milliseconds
success/failure
result count
Question count where relevant
parent/correlation identifier for nested operations
```

Do not record Question text, Answer text, image contents, PDF contents or other corpus material solely for performance analysis.

A dedicated diagnostics directory is preferred, for example:

```text
<dataRoot>/diagnostics/
```

The exact location should be updated here after implementation.

## Normal-use collection procedure

A useful real-use performance session is:

1. start the application with diagnostics enabled;
2. open the normal Chemistry corpus;
3. use the application as normal rather than repeatedly clicking one control unnaturally;
4. include representative Dashboard refreshes, Search scopes, Question previews and Working Subject transitions;
5. retain the generated diagnostic file;
6. summarise repeated operations using median and a high percentile such as p90;
7. inspect nested timings for operations whose outer elapsed time is visibly high.

This mode is intended to reveal the workflows that matter in practice.

## Repeatable benchmark runner

Runtime instrumentation is complemented by a controlled benchmark runner.

The planned administration entry point is conceptually:

```text
au.edu.eq.questionbank.admin.PerformanceBenchmarkTool
```

The final implemented class and arguments should replace this placeholder if they differ.

The benchmark runner should operate against an explicitly supplied realistic data-root/database copy and must not mutate the corpus.

Initial benchmark cases include:

- whole-bank `QuestionRepository.findAll()`;
- one complete `findById()` reconstruction;
- Subject search;
- Unit search;
- Topic search;
- Descriptor search;
- Dashboard/corpus data preparation where directly callable;
- representative Sprint 15 #94 filter combinations.

## Benchmark iteration rules

For each selected benchmark:

1. run at least one unmeasured warm-up;
2. run the configured measured iterations;
3. retain result counts as a correctness/sanity check;
4. report minimum, median, p90 and maximum elapsed time.

Also record corpus context where practical:

- Question count;
- database file size;
- benchmark name;
- application version/commit where available.

Median should be treated as the primary before/after comparison value.

These are application/database timings. Do not interpret them as nanosecond-scale Java microbenchmarks.

## Baseline procedure

Before performance-affecting Sprint 15 changes:

1. create/use a safe realistic copy of the current corpus;
2. run the standard benchmark set;
3. retain the output as the baseline;
4. implement the feature or optimisation;
5. rerun the same benchmark definition against equivalent data;
6. compare result counts and timing statistics.

For #94 specifically:

```text
pre-#94 baseline
        ↓
implement richer Search filters
        ↓
repeat existing Search benchmarks
        ↓
add representative new filter combinations
        ↓
identify demonstrated bottleneck
```

## SQL and reconstruction diagnostics

If repository/service timing identifies SQLite or Question reconstruction as expensive, collect more specific evidence.

Useful diagnostics include:

- SQL statement count per outer operation;
- SQLite connection count per outer operation;
- number of Questions reconstructed;
- applicability query time separately from reconstruction time;
- `EXPLAIN QUERY PLAN` for demonstrated slow SQL;
- current index inventory before proposing new indexes.

`SqliteQuestionRepository.findAll()` is a known candidate for investigation because it currently reads all Question IDs and reconstructs each Question through `findById()`.

Curriculum-aware retrieval similarly reconstructs matching Questions through `findById()`.

This is a candidate N+1/repeated-round-trip pattern, not a pre-decided diagnosis.

## Java Flight Recorder

Use Java Flight Recorder when application elapsed time remains high but SQL/repository timings do not explain it.

JFR can help identify:

- CPU hotspots;
- allocation pressure;
- garbage collection;
- blocking/thread activity;
- expensive Java reconstruction or rendering paths.

JFR is a developer diagnostic and does not need to be reimplemented as an application feature.

## Interpreting a result

An outer timing alone says what the user experienced:

```text
search.refresh = 1,320 ms
```

Nested measurements explain the cause:

```text
search.refresh                 1,320 ms
    curriculum.expand            18 ms
    sqlite.applicability        126 ms
    question.reconstruct      1,131 ms
    result.assembly              24 ms
    javafx.publish               21 ms
```

That evidence would direct investigation toward reconstruction rather than JavaFX or the applicability SQL.

A different run might instead show JavaFX publication or PDF preview dominating. The instrumentation exists specifically to avoid guessing.

## Optimisation evidence rule

A performance optimisation is justified only when there is:

1. a reproducible baseline;
2. evidence of the dominant cost;
3. a bounded change addressing that cost;
4. an equivalent post-change run;
5. unchanged functional results.

A useful closeout statement is therefore quantitative, for example:

```text
Chemistry Subject Search
before median: 2.31 s
after median:  0.24 s
result count unchanged
```

or:

```text
Dashboard refresh measurement showed database work below 100 ms;
most elapsed time was preview/layout work.
No SQLite change was justified.
```

Both are valid outcomes.
