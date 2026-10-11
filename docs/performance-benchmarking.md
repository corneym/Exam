# Repeatable Corpus Benchmarking — Issue #110

## Purpose and scope

`au.edu.eq.questionbank.admin.PerformanceBenchmarkTool` runs application-level retrieval benchmarks using the production Question and curriculum repositories. It measures an existing, explicitly supplied SQLite database snapshot without starting JavaFX, opening PDF assets, changing application configuration, initialising schema, or mutating the corpus.

This tool is separate from the opt-in runtime instrumentation documented in `performance-diagnostics.md` (Issue #109). It establishes comparable baselines for Issue #94 and any later evidence-justified optimisations under Issue #111; it does not authorise optimisation by itself.

## Snapshot preparation

Use a frozen, independent SQLite snapshot, **not** the live production or Eclipse development database. `immutable=1` requires the file to remain unchanged for the duration of a run and cannot be trusted against concurrent writers. The benchmark runner refuses an adjacent `-wal` or `-shm` file, but this check cannot prove an otherwise ordinary database is not being modified elsewhere.

Example from Git Bash, assuming the destination does not exist:

```bash
mkdir -p /d/ExamData/Benchmarks
sqlite3 "D:/ExamData/Development/questionbank.db" "VACUUM INTO 'D:/ExamData/Benchmarks/questionbank-baseline.db';"
sqlite3 "D:/ExamData/Benchmarks/questionbank-baseline.db" "PRAGMA integrity_check; SELECT COUNT(*) FROM questions;"
```

`VACUUM INTO` creates a transactionally consistent standalone SQLite database. It usually changes file size compared with the source because it compacts the database. Do not compare raw file sizes as evidence of code optimisation. The destination must not exist before running `VACUUM INTO`.

No other assets are required for the current Question-reconstruction and curriculum-applicability workloads. PDF loading and rendering benchmarks, if introduced later, would require matching asset snapshots.

Preserve the baseline file unchanged for comparisons; create a *new, separately named snapshot* when you need to benchmark a different corpus. Do not edit or migrate the baseline snapshot in place.

## Running the command-line tool

In Eclipse, run `PerformanceBenchmarkTool` as a Java application. Supply **Program arguments** (not VM arguments) such as:

```text
--snapshot D:\ExamData\Benchmarks\questionbank-baseline.db --operation question.findAll --warmups 2 --iterations 10 --label pre94-<git-short-sha> --csv D:\ExamData\Benchmarks\findall-pre94.csv
```

Replace `<git-short-sha>` with the actual short Git commit identifier; it is a documentation placeholder. The CLI does not discover Git revision information automatically. Supply `--label` explicitly to identify the built code, especially for before/after runs. The label allows letters, digits, dots, underscores and hyphens, and must be 1–80 characters long. If omitted, the report records `unlabelled`.

`--snapshot` is required and must be an absolute path. `--operation` may be repeated to select multiple workloads; duplicate selectors are rejected. `--warmups` and `--iterations` are positive integers, defaulting to one and five respectively. `--csv` is an optional **absolute path to a new file**; an existing file is never overwritten, and writing to the snapshot itself is rejected. Run `--help` to display the current options.

Supported selectors:

| Selector | Measured production operation |
| --- | --- |
| `question.findAll` | Fully reconstruct all persisted Questions |
| `question.findById:<id>` | Reconstruct a known Question by its persistent ID |
| `search.current.subject:<id>` | Find applicable Questions within the selected Subject's current syllabus |
| `search.current.unit:<id>` | Find applicable Questions within the selected current Unit |
| `search.current.topic:<id>` | Find applicable Questions within the selected current Topic |
| `search.current.descriptor:<id>` | Find applicable Questions for the selected current Descriptor |
| `dashboard.audit.subject:<id>` | Audit all Exams belonging to the selected Subject using the production corpus-audit service; report the number of Exams |

Use real persisted identifiers from the *same snapshot*. Curriculum scopes are resolved into current-syllabus Subtopics and Descriptors **outside** each timed execution. Search workloads invoke `SqliteQuestionRepository.findApplicableToNodes(...)`, including confirmed historical mappings; a Question applicable to multiple requested nodes is counted once. A scope with no retrievable nodes, a historical node, or a missing ID fails rather than producing a misleading measurement.

## Measurement and CSV

Each workload receives at least one unmeasured warm-up followed by the configured measured iterations. Elapsed duration is measured by `System.nanoTime()`, expressed in milliseconds for summary reporting. The primary comparison metric is **median**, supported by minimum, nearest-rank p90 and maximum. These are end-to-end application/database measurements, not nanosecond-scale microbenchmarks. A changing measured result count invalidates the run.

With `--csv`, the tool writes a separate file once **all** selected workloads complete. It does not overwrite an existing destination. The report has one CSV data row per selected workload, with the same run timestamp and snapshot metadata repeated in each row. Its stable header is:

```csv
timestamp_utc,code_label,snapshot_path,snapshot_sha256,database_bytes,question_count,schema_version,sqlite_version,java_version,operation,warmups,iterations,result_count,min_ms,median_ms,p90_ms,max_ms,elapsed_ns_samples
```

- `timestamp_utc`: UTC ISO-8601 report timestamp shared by a CLI invocation.
- `code_label`: user-provided code-version label (for example, a Git commit), or `unlabelled`.
- `snapshot_path`: source snapshot path. CSV quoting escapes commas and quote characters in paths or names.
- `snapshot_sha256`: lowercase SHA-256 of the SQLite snapshot bytes, which must match for a strict same-corpus comparison.
- `database_bytes`, `question_count`, `schema_version`, `sqlite_version`, `java_version`: environment and corpus context.
- `operation`: complete selection including its persistent target ID, where applicable.
- `warmups`, `iterations`, `result_count`: workload parameters and output volume.
- `min_ms`, `median_ms`, `p90_ms`, `max_ms`: summaries in milliseconds to three decimal places.
- `elapsed_ns_samples`: individual monotonic-clock intervals in nanoseconds, separated by semicolons within one CSV field. They are elapsed intervals, **not precision claims**.

The report contains no Question text, Answer text, Question codes or image/PDF content. The CSV stores an absolute filesystem path; decide whether that path should be shared outside your development environment.

## Recorded initial baseline — 11 October 2026

Baseline snapshot: `D:\ExamData\Benchmarks\questionbank-baseline.db`; schema version 19; SQLite 3.53.2; file size 819,200 bytes; 624 Questions. `question.findAll` used two unmeasured warm-ups and ten measured iterations, returning 624 Questions on each. Minimum 1063.795 ms; median **1087.573 ms**; nearest-rank p90 1095.838 ms; maximum 1111.612 ms.

This measurement preceded the new CSV format; the historical results were recorded manually. Do not manufacture a historical CSV from these four summaries. Re-run the unchanged code and snapshot after enabling CSV for a recorded pre-change artefact.

## Recorded CSV-enabled baseline — 11 October 2026

After adding Batch 06 CSV reporting, the unchanged baseline snapshot was benchmarked again. Keep this second run separate from the earlier manually recorded measurement; ordinary run-to-run variation must not be presented as an optimisation result.

| Property | Recorded value |
| --- | --- |
| Snapshot | `D:\ExamData\Benchmarks\questionbank-baseline.db` |
| SHA-256 | `9e1690c136206c1b49233a097e033ff92654e227323cbd4e7f2c031412e0722f` |
| Database size | 819,200 bytes |
| Question count | 624 |
| Schema version | 19 |
| SQLite version | 3.53.2 |
| Java version | 25.0.4 |
| Operation | `question.findAll` |
| Warm-ups / measured iterations | 2 / 10 |
| Result count per measured iteration | 624 |
| Minimum | 1098.698 ms |
| Median | **1126.442 ms** |
| Nearest-rank p90 | 1143.949 ms |
| Maximum | 1143.968 ms |
| CSV file | `D:\ExamData\Benchmarks\findall-pre94-a10c1ed.csv` |
| Supplied code label | `pre94-a10c1ed` |

**Provenance qualification:** The code label was entered manually and refers to the pre-#94 comparison stage. Batch 06 CSV functionality was implemented locally *after* the pushed commit `a10c1ed`, so this label alone does **not** identify the exact compiled code revision. Record the actual Batch 06 commit for future runs, and do not edit or overwrite this existing CSV artefact. The SHA-256 fingerprint, by contrast, identifies the precise snapshot bytes.

## Before/after procedure

1. Preserve the baseline SQLite snapshot. Check that it has no WAL/SHM sidecars and no other process writes to it.
2. Record the full Git commit identifier, JVM version, machine/environment, iteration counts and selected workload IDs. Use a code-identifying `--label`.
3. Run the same selector set with the same warm-ups and measured iterations. Give each run a new CSV filename; retain the raw individual timings.
4. Make the intended feature change under Issue #94, or a separately evidenced optimisation under Issue #111. Do not modify the snapshot.
5. Rebuild and repeat with the **same snapshot SHA-256**, schema, selected IDs and iteration counts. Change only the code label and output filename.
6. Compare median and p90 with run-to-run variability in mind; compare result counts and behavioural regression tests before attributing any change to performance. Repeat across several independent JVM launches if the difference is small.
7. Record the before/after evidence and functional verification on the relevant GitHub issue. No speed-up alone is proof of correctness.

## Boundaries and pending workloads

The CLI covers whole-bank reconstruction, single-Question reconstruction, current-curriculum Subject/Unit/Topic/Descriptor applicability, and Subject-level Dashboard corpus auditing. The `dashboard.audit.subject:<id>` workload uses `ExamCorpusAuditService.assessSubject(...)`, including Question reconstruction, Exam/booklet retrieval, AnswerFile assignment and calculated completeness. PDF assets are deliberately unavailable to this workload; therefore its PDF-availability findings must not be compared with those from a complete installed data root.

This is one production preparation boundary, not the entire Dashboard snapshot. The complete Dashboard also prepares curriculum mapping coverage and performs a separate whole-bank Question retrieval. User-visible JavaFX responsiveness is measured through Issue #109 runtime diagnostics. Future richer Question Search filters from Issue #94 can be registered without redesigning `PerformanceBenchmarkRunner` or the CSV reporting schema.

Do not confuse these isolated repository timings with user-visible JavaFX responsiveness. For end-to-end interaction timings use the separate opt-in Issue #109 recorder and its `performance.csv` file.
