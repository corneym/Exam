# Sprint 24 Release Audit Progress

## Starting point

- Starting branch: `issue/276`
- Starting commit: `cb88f4007c74d6277943e1d3e63375d878ebefe1`
- Audit branch: `codex/issue-276-release-audit`
- Working tree at start: clean

## Audit scope

Source-level Sprint 24 release-readiness audit under Issue #276, concentrating on data integrity, managed assets, authority enforcement, backup and recovery, database migration, JavaFX stability, and regression coverage. Tests, builds, Javadoc generation, CI runs, production-database changes, and permanent documentation edits are prohibited.

## Completed review areas

- Read the supplied Sprint 24 final release-audit brief.
- Read `docs/Working-Instructions.md`.
- Confirmed the clean starting tree and exact starting commit.
- Created the requested audit branch from `issue/276`.
- Established the Sprint 24 change surface, Maven suite partitioning and CI discovery classes.
- Audited Assignment issuance, installation, visibility/removal, Contribution publication/intake/application and replay/ownership checks.
- Audited Subject Snapshot preview, freshness classification, approval binding, initial import, refresh publication, rollback journal and managed-asset recovery.
- Audited schema versions 48 and 49, sequential migration wiring, verification and historical-fixture cleanup.
- Traced imported-Subject authority enforcement across the UI, service and persistence layers.
- Audited automatic full-backup creation, restore publication, rollback and restored v48/v49 provenance coverage.
- Audited Sprint 24 JavaFX background tasks, progress dismissal, workflow-flag reset and stale Dashboard generation checks.
- Completed a repository-wide search for ordinary mutations of Subject Snapshot graph tables and traced direct-SQL services that bypass the main writers.

## Remaining review areas

- None. Maintainer verification remains outstanding and was deliberately not executed during this audit.

## Findings

- **P1 — Imported Subject read-only authority was not enforced at several persistence boundaries. Corrected.** The authority table was consulted by JavaFX state and existing-record Question/Answer guards, but ordinary repository APIs could still add or alter Exam, curriculum and capture data. Direct-SQL asset replacement/reassignment and legacy import paths also bypassed the guard, while assignment and Subject Snapshot export services could incorrectly operate on an imported Subject. This compromised authoritative snapshot integrity and could re-originate snapshot lineage locally.

## Files modified and reasons

- `src/main/java/au/edu/eq/questionbank/repository/sqlite/SqliteImportedSubjectAuthorityRepository.java` — added transactional authority checks that resolve persisted syllabus ownership.
- `src/main/java/au/edu/eq/questionbank/repository/assessment/SqliteContributorBaselineWriteGuard.java` — distinguished valid contributor creation from prohibited ordinary/imported-Subject capture.
- `src/main/java/au/edu/eq/questionbank/repository/assessment/SqliteExamWriter.java` — guarded Exam, capture-state, expectation and source-document hash mutations.
- `src/main/java/au/edu/eq/questionbank/repository/assessment/SqliteQuestionWriter.java`, `SqliteSourceQuestionRepository.java` and `SqliteSharedQuestionContextRepository.java` — guarded new capture paths.
- `src/main/java/au/edu/eq/questionbank/repository/assessment/AnswerFileReassignmentService.java`, `QuestionBookletPdfReplacementService.java` and `LegacyExamImportEligibility.java` — guarded asset reassignment/replacement and legacy import eligibility.
- `src/main/java/au/edu/eq/questionbank/repository/curriculum/SqliteCurriculumWriter.java`, `SqliteCurriculumImporter.java`, `SqliteCurriculumAuthoringWriter.java`, `SqliteCurriculumLifecycleRepository.java`, `SqliteCurriculumSourcePdfRepository.java`, `SqliteCurriculumMappingWriter.java` and `SqliteCurriculumMappingReviewWriter.java` — guarded curriculum mutations using persisted ownership, including forged parent/version protection.
- `src/main/java/au/edu/eq/questionbank/service/workpackage/ExamAssignmentPreflight.java` and `SubjectSnapshotExporter.java` — blocked assignment issuance and false local snapshot re-origination for imported Subjects.
- `src/test/java/au/edu/eq/questionbank/repository/assessment/SqliteImportedSubjectWriteBoundaryTest.java` — added the consolidated imported-authority boundary regression.
- `sprint24-release-audit-progress.md` — temporary audit-continuity checkpoint required by the audit brief; exclude from the intended release commit.

## Completed corrections

- Added caller-connection authority preconditions and persisted syllabus-owner resolution to `SqliteImportedSubjectAuthorityRepository`.
- Guarded ordinary Exam structure/state and source-document hash mutation.
- Guarded new Question, Source Question and Shared Context capture while preserving valid active-contributor creation.
- Guarded curriculum import, authoring, lifecycle, source-PDF and mapping mutations.
- Guarded AnswerFile reassignment and Question-booklet PDF replacement before destructive database or managed-file changes, while preserving read-only inspection and same-file no-op behaviour where applicable.
- Blocked legacy metadata import, contributor assignment preflight and Subject Snapshot export for imported Subjects.
- No schema migration was introduced; the dedicated approved Subject import/refresh graph writer remains the only path that may replace imported authoritative content.

## New or modified regression tests

- Added `SqliteImportedSubjectWriteBoundaryTest`, covering representative assessment, curriculum, managed-PDF, assignment and snapshot-export boundaries, including forged/stale domain ownership and unchanged persisted state after rejection.

## Recommended maintainer verification commands

- `./mvnw -Dtest=au.edu.eq.questionbank.repository.assessment.SqliteImportedSubjectWriteBoundaryTest test`
- `./mvnw clean test`
- `./mvnw -Pheadless-ui-tests test`
- `./mvnw -DskipTests javadoc:javadoc`

None of these commands has been executed during the audit, as expressly required by Issue #276.

## Current unresolved findings

- No unresolved P0/P1 finding identified by source inspection.
- No separate P2/P3 correction was implemented. Explicitly deferred Issues #301–#309 remain deferred.

## Exact next action

The maintainer should run the recommended commands in order. If they are green, review the diff, exclude this checkpoint from the release commit, and decide whether Issue #276 may move to Ready for PR.

## Overall completion status

AUDIT COMPLETE — CORRECTIONS AWAIT MAINTAINER VERIFICATION. No tests, build, Javadoc or CI command has been executed during this audit.
