# Sprint 24 — Offline exchange and shared-faculty operating model

> Closeout checkpoint: 26 November 2026. Functional development is complete and verification is green on the audited branch. Final branch integration, documentation, PR/merge and release publication remain pending at this checkpoint.

## Scope and decision record

Parent: [#36](https://github.com/corneym/Exam/issues/36). Integration: [#276](https://github.com/corneym/Exam/issues/276), following the package infrastructure issues #268–#275.

The [approved operating model](https://github.com/corneym/Exam/issues/276#issuecomment-7030632231) supersedes earlier multi-return, receipt/rebase and arbitrary same-Subject reconciliation UI assumptions. The current operational guide is [offline-exchange.md](../offline-exchange.md).

One application and one Corpus Dashboard support locally authoritative Subjects, contributors with active whole-Exam assignments, and independently imported read-only Subjects. There is no account/role selector, shared live SQLite database or two-way Subject synchronisation. The coordinator issues one whole-Exam assignment and locks the authoritative Exam until one valid final Contribution is merged or the assignment is explicitly cancelled. A contributor can capture new work and correct their own unsent additions, but cannot alter the issued baseline; after submission the assignment is closed locally. HoD consolidation accepts complete Subject Snapshots and approved subsequent refreshes from the originating authority.

## Delivered behaviour

- Typed `.examwork` archives with portable identities, immutable assignment/baseline evidence, validated provenance, asset checksums and publication/recovery protection.
- Coordinator lock state, inspectability, issuance preflight, cancellation, final-return merge and automatic unlocking. Late, duplicate and invalid returns cannot mutate the authoritative Exam.
- Contributor automatic assignment selection, read-only issued content, new capture including missing Answers for issued Questions, own-work correction, unchanged-assignment removal/reimport and single final submission. Original published Contribution and durable return evidence remain available; physical disposal of the working corpus is deferred.
- Separate import routes for Work Assignment, Contribution and Subject Snapshot; manifest-type validation and meaningful archive filenames. Contribution review presents returned-work counts and merge/cancel, with internal validation and audit rather than routine per-record decisions or receipt UI.
- New Subject import into empty or populated installations alongside independent Subjects. Read-only origin authority is persisted; incoming authoritative revisions are preserved on first import.
- Subsequent Subject refresh compares against the accepted origin, previews changes, requires exact approval and full backup, revalidates freshness and destination, transactionally applies supported additions/updates/removals/asset changes, records authority and reloads the Dashboard. Exact replay is non-duplicating; stale or foreign origin is refused. Shared-resource and complex dependency conflicts fail closed.
- Sequential schema migrations to v48 (imported Subject authority and contributor visibility evidence) and v49 (originating snapshot freshness evidence); separate managed-data layout migration remains applicable to earlier layouts. No schema change was required by the final audit.

## Verification milestones

| Checkpoint | Evidence |
| --- | --- |
| `c4f040f89c4ea9f6e4493645fa7a6d87447f11ae` | Batch 41j Subject refresh application/UI, cancellation, approval, replay and revision-preservation tests; all 22 CI checks green. |
| `cb88f4007c74d6277943e1d3e63375d878ebefe1` | Functional development frozen; Batch 42a CI suite corrections pushed, maintainer reported clean Javadoc. |
| `fea74e90cccf6332d1c2a3bb43ed70d5e79bd97e` | Independent Codex audit corrections, all 22 CI checks green, post-audit Javadoc clean. |

The corrected CI discovery includes the Subject refresh plan/change classifier/identity normaliser/freshness/intake preview tests and the empty-Subject-snapshot Dashboard workflow. CI runs 11 non-UI shards, 10 UI shards and the non-UI aggregate check. The audited imported-authority regression is discovered by `NonUIRepositoryAssessmentTests`.

### Consolidated manual acceptance

The maintainer used independent disposable roots: restored production copy for coordinator, empty contributor, empty HoD. All three main workflows were demonstrated. The original detailed checklist was consolidated to 18 practical acceptance criteria.

| Exercise | Result | Evidence |
| --- | --- | --- |
| A — Distributed collection | 8/8 reported pass | Issue/lock, install, capture/classify/correct, issued protection, submission, contribution review, merge/backup/unlock. A06 Dashboard disappearance passed; residual submitted Search/export access subsequently observed. |
| B — Initial HoD import | 3/4 reported pass | Complete snapshot export, approved empty-bank installation, read-only Search/inspection. B03 partially unmet: direct HoD Exam assets/PDF viewing unavailable. |
| C — Subsequent HoD refresh | 6/6 reported pass | Coordinator additions/corrections, newer export, comparison, cancellation leaves data unchanged, approval applies changes, exact replay without duplicates. C03 change descriptions need clearer presentation. |

17/18 reported passes were accepted with known limitations; this is not a claim that all original manual criteria passed. See [acceptance report](https://github.com/corneym/Exam/issues/276#issuecomment-7155238629), [additional findings](https://github.com/corneym/Exam/issues/276#issuecomment-7155251192) and [maintainer release decision](https://github.com/corneym/Exam/issues/276#issuecomment-7155289901).

### Independent release audit

The Codex source audit branched from `cb88f4007c74d6277943e1d3e63375d878ebefe1` onto `codex/issue-276-release-audit`. It reviewed lineage, assignment lifecycle, snapshot preview/refresh, asset publication and recovery, v48/v49 migration wiring and historical fixtures, full backup/restore, authority, background operations and stale UI generations. Tests, builds and Javadoc generation were deliberately left to the maintainer.

One P1 defect was identified: imported Subject UI restrictions and some existing-record guards did not protect all ordinary repository and service mutation paths. Ordinary APIs could alter imported assessment or curriculum data, replace/reassign assets, perform legacy import, issue assignments or re-originate an imported Subject Snapshot.

The correction uses transactional checks against persisted ownership, not merely object-supplied Subject or syllabus associations. It covers:

- Exam metadata, lifecycle, expectations and source-document hash writes (`SqliteExamWriter`);
- new Question, SourceQuestion and SharedQuestionContext capture (`SqliteContributorBaselineWriteGuard`, `SqliteQuestionWriter`, `SqliteSourceQuestionRepository`, `SqliteSharedQuestionContextRepository`), preserving valid contributor additions;
- Answer-file reassignment, booklet PDF replacement and legacy import eligibility;
- curriculum creation/import, authoring, lifecycle, source PDF, mapping and mapping-review operations;
- assignment preflight and Subject Snapshot export.

`SqliteImportedSubjectAuthorityRepository` supplies caller-connection authority checks and persisted syllabus ownership resolution. Approved import/refresh remains the authorised graph replacement mechanism. No schema migration or deferred P2/P3 implementation was introduced.

The consolidated regression `au.edu.eq.questionbank.repository.assessment.SqliteImportedSubjectWriteBoundaryTest` checks ordinary mutation rejection, forged/stale ownership, preserved managed files and unchanged persisted state. The maintainer pushed audit commit `fea74e90cccf6332d1c2a3bb43ed70d5e79bd97e`; all 22 GitHub CI checks passed, and post-audit Javadoc was confirmed clean. See [audit checkpoint](https://github.com/corneym/Exam/issues/276#issuecomment-7174251550) and [Javadoc confirmation](https://github.com/corneym/Exam/issues/276#issuecomment-7174291128).

The audit reported no unresolved P0/P1 source findings. A local JavaFX `UploadingPainter` BufferOverflowException was observed during a ComboBox test, but no CI test failure was demonstrated. No rendering change was made. The temporary `sprint24-release-audit-progress.md` is not intended to be a release document and should be excluded from the final PR.

## Explicitly deferred follow-up

The maintainer accepted the residual D21 limitations and D23–D31 observations to avoid delaying initial use by other Subjects. They are tracked as independent issues, not resolved findings:

| Issue | Scope |
| --- | --- |
| [#301](https://github.com/corneym/Exam/issues/301) | D21: safely dispose of submitted worker corpus, detach submission evidence, independent reissue, remove unnecessary worker reconciliation after dependency audit. |
| [#302](https://github.com/corneym/Exam/issues/302) | D23: measure Dashboard capture-control activation delay. |
| [#303](https://github.com/corneym/Exam/issues/303) | D24: contributor Search/revision-output scope, coordinate with #301. |
| [#304](https://github.com/corneym/Exam/issues/304) | D25/D28: read-only Exam/Assets for HoD, redundant standalone PDF controls. |
| [#305](https://github.com/corneym/Exam/issues/305) | D26: Subject Snapshot export progress spinner. |
| [#306](https://github.com/corneym/Exam/issues/306) | D27: gate Import Contribution availability by outstanding assignment. |
| [#307](https://github.com/corneym/Exam/issues/307) | D29: mixed-exam MCQ Answer and Explanation workflow. |
| [#308](https://github.com/corneym/Exam/issues/308) | D30: human-readable Subject refresh review. |
| [#309](https://github.com/corneym/Exam/issues/309) | D31: safe removal of successfully imported package archives. |

See [follow-up issue registration](https://github.com/corneym/Exam/issues/276#issuecomment-7155374004). No new sprint assignment or priority is implied. #301 should be a dependency-audited simplification, not an extension of unnecessary worker-side reconciliation. The coordinator merge, HoD refresh, provenance, backups, ownership and recovery protections must be retained.

## Release handover

The verified candidate is not yet a published release. Preserve production data, complete the protected-branch PR process, use the formal Windows release build, and verify the installed MSI with isolated test data before publication. No production exchange packages had been issued or imported at the audit checkpoint; earlier exchanges were disposable test databases. Preserve normal production database upgrade compatibility.
