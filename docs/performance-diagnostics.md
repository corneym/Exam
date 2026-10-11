# Performance Diagnostics and Benchmarking

## Purpose

Exam Question Bank performance diagnostics provide repeatable elapsed-time evidence from normal application use. They distinguish JavaFX work, background retrieval, Question reconstruction, and export processing before any optimisation is attempted. This document describes the runtime instrumentation implemented for Sprint 15 Issue #109. The separately implemented, read-only corpus benchmark runner for Issue #110 is documented in [performance-benchmarking.md](performance-benchmarking.md).

## Enabling runtime diagnostics

Diagnostics are disabled by default. Enable them with the Java VM argument:

```text
-Deqb.performance.enabled=true
```

For Eclipse, add this under **Run Configurations → Arguments → VM arguments**, not Program arguments. Restart the application to enable or disable collection. The code reads this property at application startup.

`PerformanceRecorder.fromSystemProperties(ApplicationPaths.diagnosticsDirectory())` writes to:

```text
<ApplicationPaths.configurationDirectory()>/diagnostics/performance.csv
```

`ApplicationPaths.configurationDirectory()` uses the platform's user-writable application configuration location; it is separate from the authoritative Question-bank data root. See `ApplicationPaths` for the platform-specific path. The file is appended to across application sessions. Copy or rename it between controlled comparisons if a separate baseline is needed. Disabled diagnostics create no CSV file.

## CSV format

The header is:

```csv
timestamp,operation_id,parent_id,operation,elapsed_ms,success,result_count
```

- `timestamp`: UTC ISO-8601 time when the measurement completes.
- `operation_id`: a unique, increasing identifier within a `PerformanceRecorder` instance. It resets when the application restarts.
- `parent_id`: parent operation ID, or `0` for a root/unlinked operation.
- `operation`: stable identifier for the measured work.
- `elapsed_ms`: elapsed wall-clock duration derived from monotonic `System.nanoTime()`; includes time spent awaiting background completion for outer operations.
- `success`: `true` for normal completion, `false` for failure or a deliberately discarded obsolete asynchronous result. Check the UI/console to distinguish cancellation from an exception.
- `result_count`: an optional non-negative count, or `-1` when not supplied. The interpretation is operation-specific (for example, Questions returned, exportable Questions, or curriculum entries).

Use `(application session, operation_id)` to identify a specific operation across appended runs; IDs are not globally unique across restarts. A child normally finishes before its parent, so CSV row order is not execution start order. Nested timings can overlap or be re-entrant and should not automatically be summed to equal their parent. Diagnostics do not record Question/Answer text, PDF content, pasted image bytes or stored Question codes.

## Implemented runtime operations

The following names identify representative operations; additional nested names appear in the CSV:

| Workflow | Examples |
| --- | --- |
| Application startup | `application.startup` |
| Dashboard | `dashboard.refresh`, `dashboard.snapshot`, `dashboard.audit`, `dashboard.question.findAll` |
| Search and applicability | `search.refresh`, `search.repository.applicability`, `search.repository.reconstruct`, `search.output-applicability.load` |
| Search previews | `search.preview.load`, `search.preview.render` |
| Question capture | `capture.question.code-change`, `capture.question.code.context.corpus`, `capture.question.region.accept`, `capture.question.save` |
| Curriculum selection | `capture.curriculum.unit`, `capture.curriculum.topic`, `capture.curriculum.subtopic`, `capture.curriculum.descriptor` |
| Dashboard Answer capture | `dashboard.answer-capture.open` |
| Working Subject capture refresh | `workingSubject.refresh`, `workingSubject.curriculum`, `workingSubject.questions`, `workingSubject.publish` |
| Revision HTML export | `revision.html.export`, `revision.html.export.work` |
| Revision SCORM export | `revision.scorm.export`, `revision.scorm.export.work` |

Working Subject's `workingSubject.refresh` span measures the curriculum and Question/Answer capture snapshot through JavaFX publication. Dashboard refresh runs separately and is measured by `dashboard.refresh`. Export outer spans run from accepted export launch through progress-dialog cleanup; they deliberately end **before** the user acknowledges a result alert. Their `.work` child spans measure the background export service.

## Collecting a representative run

1. Start the development application with diagnostics enabled.
2. Use a safe real-corpus development database (for example, the Chemistry corpus); do not conduct tests against production data inadvertently.
3. Perform representative Dashboard, Search, preview, Question-capture, Working Subject, and export operations.
4. Retain `performance.csv` and note the date, branch/commit, corpus size, and operations exercised.
5. Separate appended application sessions before comparing timings; analyse repeated operations with median, p90, and maximum rather than a single extreme event.
6. Inspect nested operations and failure flags to locate the expensive phase. Do not infer the bottleneck from an outer duration alone.

For the multipart Shared Context case discovered in Sprint 15, the reproducible workflow is: capture and save part `a` **with its Shared Context**; then enter part `b` for the same source Question. Batch 18 captured repeated whole-corpus retrieval in that path. Any change to eliminate duplicate retrieval belongs to **Issue #111**, with a regression confirming correct context inheritance.

## Diagnostic design and interpretation

`PerformanceRecorder` and `PerformanceOperation` support monotonic timing, explicit parent IDs across threads, optional result counts, and failure recording without swallowing the original exception. Diagnostics are opt-in and must not alter persistence or functional outcomes. Core unit tests cover enabled/disabled recording, nesting, concurrency, failure propagation, and file-output failures; application workflow tests cover representative parent/child correlations.

For an expensive outer operation, compare the named inner spans. For example, a 2.6-second Question Save may contain two ~1.25-second full-corpus reads and only ~0.1 second of persistence. That evidence calls for reconstruction/retrieval improvements, not a change to the save transaction. The large corpus provides more reliable evidence than a small synthetic fixture.

## Repeatable benchmark runner — Issue #110

The controlled benchmark runner is separate from the opt-in runtime `PerformanceRecorder`. The implemented entry point is `au.edu.eq.questionbank.admin.PerformanceBenchmarkTool`; run it in Eclipse with **Program arguments**, not VM arguments. It requires an explicitly supplied, immutable SQLite database snapshot. Do not point it at the live Eclipse development or production database, and do not apply schema migrations to the frozen baseline.

The runner uses the production repositories to benchmark `question.findAll`, `question.findById:<id>` and current-curriculum Subject, Unit, Topic or Descriptor applicability retrieval. Scope expansion and corpus validation occur outside timed iterations. Each operation has at least one unmeasured warm-up, configurable measured iterations and stable result counts. The console reports minimum, median, nearest-rank p90 and maximum in milliseconds along with database size, Question count, schema and SQLite version.

The Issue #110 CSV report is **not** the same format as runtime `diagnostics/performance.csv`. Supply optional `--csv <absolute-new-file-path>` and `--label <code-version-label>` to record one row per completed workload, with snapshot SHA-256, corpus metadata, Java/SQLite versions, all summary timings and the individual measured samples. Existing CSV files are not overwritten. A label is supplied by the operator; it is **not** independently verified against a Git commit. See [performance-benchmarking.md](performance-benchmarking.md) for the CSV header, snapshot creation, CLI selectors, controls and before/after comparison procedure.

A frozen schema-v19 snapshot containing 624 Questions was established on 11 October 2026. Its SHA-256 fingerprint is `9e1690c136206c1b49233a097e033ff92654e227323cbd4e7f2c031412e0722f`. A subsequent CSV-enabled `question.findAll` run used two warm-ups and ten measured iterations; result count 624, median **1126.442 ms**, p90 **1143.949 ms**. Preserve the original snapshot unchanged when comparing code versions. Issue #128 migrations belong on a **separate copy**; an upgraded schema may not be readable by the pre-migration benchmark executable.

Dashboard corpus-audit preparation is now benchmarked through dashboard.audit.subject:<id>, using the production ExamCorpusAuditService with physical PDF assets deliberately unavailable. The frozen 624-Question snapshot produced 14 Exam audit statuses, with a median execution time of 1114.287 ms across ten measured iterations. This measures one production Dashboard preparation boundary, not the complete Dashboard refresh. Richer Search filter workloads will be introduced alongside Issue #94.

## Additional investigation tools

If repository timing dominates, investigate SQL statement/connection counts, how many Questions are reconstructed, `EXPLAIN QUERY PLAN`, and index inventory before changing SQLite access. `SqliteQuestionRepository.findAll()` and applicability retrieval can repeatedly reconstruct full Questions through `findById()`; Sprint 15 measurements established this as a material source of latency, not merely a hypothesis.

Java Flight Recorder is useful when application elapsed time remains high but SQL/repository spans do not explain the delay. It can identify CPU, allocation, garbage collection, blocking and JavaFX rendering costs. JFR is an external developer diagnostic; it is not reimplemented in Issue #109.

## Scope boundary

Issue #109 establishes measurement only. It does not change the Question repository, Shared Context inference, export semantics or user-visible workflow to make operations faster. Performance improvements and before/after comparisons belong to Issue #111, supported by the repeatable baseline runner in Issue #110.
