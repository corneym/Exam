# Exam Question Bank Backlog

> Reference date: 26 November 2026, Sprint 24 audited-candidate checkpoint. GitHub Issues and the development Project are authoritative for live state, sprint assignment and priority. This document preserves deferred design intent; it is not a parallel issue register.

## Active closeout — not backlog

Sprint 24 integration [#276](https://github.com/corneym/Exam/issues/276) is functionally complete and independently audited. The latest audited branch passed all 22 CI checks and strict Javadoc. Documentation, integration, PR/merge and formal release remain pending at this checkpoint. See [Sprint 24 record](sprint-24-offline-exchange.md). Historical Sprint 14 and Sprint 15 scope is preserved in their respective sprint design records; it is not current active scope.

## Deferred Sprint 24 findings

The maintainer explicitly accepted these limitations for the initial release. They are not release blockers and are not resolved. No priority, sprint or implementation order is assigned here.

| Issue | Deferred work |
| --- | --- |
| [#301](https://github.com/corneym/Exam/issues/301) | Disposable submitted contributor corpus; simplify independent subsequent assignment installation and remove redundant worker reconciliation (D21). |
| [#302](https://github.com/corneym/Exam/issues/302) | Measure and correct delayed Dashboard capture-control activation (D23). |
| [#303](https://github.com/corneym/Exam/issues/303) | Restrict contributor Search and revision output to active assignment scope (D24). |
| [#304](https://github.com/corneym/Exam/issues/304) | Read-only Exam asset/PDF inspection, remove redundant standalone PDF menu routes (D25/D28). |
| [#305](https://github.com/corneym/Exam/issues/305) | Consistent Subject Snapshot export spinner (D26). |
| [#306](https://github.com/corneym/Exam/issues/306) | Import Contribution menu availability based on active coordinator assignments (D27). |
| [#307](https://github.com/corneym/Exam/issues/307) | Mixed-Exam MCQ Answer/Explanation capture and reporting (D29). |
| [#308](https://github.com/corneym/Exam/issues/308) | Human-readable Subject refresh review (D30). |
| [#309](https://github.com/corneym/Exam/issues/309) | Safe cleanup of successfully imported package archives (D31). |

#301 is a candidate for Codex-led implementation after an approved dependency audit. The worker is finished when a final Contribution is durably published, regardless of whether the coordinator accepts it. Remove obsolete working data and unnecessary worker reconciliation rather than extend it. Retain durable replay/submission evidence and the original archive for resend; protect unsent work, unrelated Subjects, shared assets and other active assignments. Distinguish cache eviction from authoritative deletion and preserve production upgrade compatibility. Coordinate #303 and #309 with #301, but distinguish archive file cleanup from working-corpus disposal. The existing coordinator merge and HoD refresh mechanisms remain essential.

## Open shared-faculty and data lifecycle work

[#36](https://github.com/corneym/Exam/issues/36) remains the shared-faculty parent. The first offline distributed collection and faculty consolidation workflow is implemented in Sprint 24, rather than a future proposal. The chosen `.examwork` manifest/portable UUID/validated lineage design is described in [offline-exchange.md](../offline-exchange.md). Do not introduce a shared concurrently edited SQLite database, role-selection system or arbitrary two-way synchronisation to extend it.

- [#300](https://github.com/corneym/Exam/issues/300) — safe whole-Subject deletion and subsequent snapshot reimport. Do not conflate this with contributor cache eviction or current approved Subject refresh.
- [#297](https://github.com/corneym/Exam/issues/297) — consolidated baseline schema for new database installations. Preserve supported historical migration and existing corpus data.
- [#217](https://github.com/corneym/Exam/issues/217) — trusted signing of Windows installers and application binaries. Follow the [code-signing policy](../code-signing-policy.md).

## Later product areas

### Exam Builder — #37

Potential capabilities include Question selection, ordering, section structure, total marks, reproducible drafts, Answer/marking inclusion and retained provenance.

Exam Builder consumes the bank and must not become a dependency of revision/SCORM output.

### Printable/vector-preserving assessment and solution output — #38

For print-oriented generation, evaluate direct source-PDF page/viewport clipping rather than rasterising web assets. Preserve generated numbering, solution alignment and source attribution.

### Capture-workflow productivity assistance

Promote individual implementation issues when sustained real-corpus use demonstrates repeated cost. Current open issues are:

- [#166](https://github.com/corneym/Exam/issues/166) — optional Ctrl+S for capture work.
- [#167](https://github.com/corneym/Exam/issues/167) — suggest Question codes from PDF text.
- [#168](https://github.com/corneym/Exam/issues/168) — investigate teacher-confirmed Question-boundary suggestions.
- [#169](https://github.com/corneym/Exam/issues/169) — investigate PDF-assisted multipart/Shared Context grouping.

Suggestions remain non-authoritative until confirmed by a teacher. Earlier #95 design discussions included skip/defer and previous unresolved work; consult current issue history rather than silently reinstating older requirements.

## Some day / maybe

### Curriculum authoring follow-on work — #17 / #18

Possible assistance includes PDF region-based text extraction, diagram/image handling, manual maths exclusion regions, Markdown/LaTeX editing/preview and later equation-recognition support. Curriculum hierarchy remains expert-authored.

### Multiple original classifications — #19

Current `Question` stores one best-fit original Subtopic or Descriptor. Reconsider many-to-many original classification only if real use shows one provenance location is inadequate.

### Legacy image-snip import — #24

Decide whether legacy image-snip import remains needed; do not reintroduce workbook/filesystem naming as runtime authority.

## Historical design dispositions and evidence rules

Older backlog entries and completed/superseded issue decisions remain available in Git history and their issue records. The following design principles remain useful without reopening closed issues:

- Explicitly reviewed but intentionally incomplete corpus states (#20) must not be conflated with Exam `COMPLETE` or revision-output exclusions. Check current disposition before proposing new states.
- Retrieval/domain invariants, realistic reopen coverage and preview-rendering overlap (#25/#26/#28) should be revisited only against evidence of a current problem. Measure before optimisation.
- Mapping writers enforce same Subject, different syllabus versions, same level and allowed direction. Revisit direct-SQL enforcement, review assistance or richer mapping metadata (#29/#30/#31) only if justified by concrete needs and current implementation.
- Additional legacy workbook variants (#23) should be evidence-driven. Do not infer multipart or Shared Context relationships merely from patterned workbook data.
- Real-corpus completion/review (#5–#12) is distinct from product feature work. Check current issue state and actual corpus audit rather than treating historical open lists as active development.

## Rejected / superseded directions

### Multi-page Shared Context capture — rejected

A Shared Context is contained within one source page. Do not re-add multi-page Shared Context as planned/backlog work without an explicit new decision.

### Concurrent shared SQLite database for faculty collection — rejected as first distributed model

Independent local data roots and portable packages are the first deployed architecture. A future centrally hosted service may be reconsidered only if later deployment requirements justify it.

### Per-response-type expected counts for mixed Question booklets — #73 not planned

Sprint 12 retained one total expected top-level Question count for mixed booklets. Do not reintroduce expected-MCQ/expected-written subcounts without new evidence.

### Parallel import-audit/reconciliation system — #22 superseded

The broad #22 concept is covered by #58, #65, #68 and #81. Do not build another parallel reporting model.

### Routine receipt/rebase, multiple partial returns and general same-Subject merge — superseded

The Sprint 24 decision replaces these as ordinary workflows with one final Contribution and explicitly approved authoritative Subject refresh. Internal identity, import, recovery and audit evidence is retained as necessary; obsolete worker-side reconciliation is a future simplification under #301.

## Completed/scheduled elsewhere — not backlog

Do not re-add completed work. Managed syllabus PDFs (#83) were already implemented and moved to Subject-first managed storage under Sprint 14. The old-backup regression requested by #34 already existed and the issue was closed. Historical sprint records and GitHub issues retain their original evidence. First offline collection and HoD snapshot/refresh are implemented under #276; only the explicitly recorded follow-ups remain here.

## Backlog rules

- Keep deliberately deferred requirements represented until implemented, rejected or superseded.
- Do not duplicate active sprint or closeout work.
- Preserve completed sprint documents as historical evidence.
- Keep performance work measurement-driven.
- Prefer behaviour-focused regressions over coverage percentages.
- Do not invent placeholder data to resolve unclear ownership/semantics.
- Expected asset/question counts are planning/audit metadata, not permission to create fictitious authoritative records.
- Prefer safe, reviewable import/reconciliation over direct cross-database mutation.
- Confirm the actual issue and Project state before assigning any work to a sprint.
