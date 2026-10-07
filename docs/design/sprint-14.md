# Sprint 14 — Subject-First Managed Data Layout

Status: design draft

Parent issue: #100 — Data directory Restructure

Planned issues:

- #101 — Subject-first managed data layout contract
- #102 — Move Exam and Answer PDFs to the subject-first layout
- #103 — Move curriculum assets to the subject-first layout
- #104 — Retain legacy import workbooks as managed Subject assets
- #105 — Migrate existing data roots to the subject-first layout safely
- #106 — Update backup and restore for the subject-first data layout

## 1. Purpose

Sprint 14 restructures managed source data so that Subject becomes the primary filesystem partition.

The current application is organised largely by file type:

```text
<dataRoot>/
    questionbank.db
    pdf/
        <Subject>/
            <Provider>/
                <Year>/
                    ...
    curriculum/
        ...
    backups/
        ...
```

That structure is workable for a single corpus, but it does not match the planned direction in which a Subject should behave as a coherent portable data domain containing its curriculum sources, historical import sources and examination sources.

The Sprint 14 target is:

```text
<dataRoot>/
    questionbank.db
    backups/
    subjects/
        Chemistry/
            curriculum/
            legacy/
            exams/
        Physics/
            curriculum/
            legacy/
            exams/
        Biology/
            curriculum/
            legacy/
            exams/
```

The `subjects/` namespace is preferred over placing Subject directories directly beside `questionbank.db` and `backups/`. It keeps application-level files distinct from Subject packages, removes reserved-name ambiguity and provides a stable root for future Subject-scoped export or distributed collection.

SQLite remains authoritative. The Subject directories contain managed source assets, not independent databases.

## 2. Starting architecture

The live repository currently has several explicit filesystem contracts:

- `ApplicationConfig` derives separate `pdfDataRoot` and `curriculumDataRoot` locations beneath `data.root`;
- `PdfStore` owns Exam PDF import and resolves persisted `SourceDocument.relativePath` values beneath the configured PDF root;
- `CurriculumSourcePdfStore` owns syllabus-source PDFs beneath the curriculum root;
- Sprint 13 issue #97 adds `CurriculumWorkbookStore`, which allows an Excel workbook to be selected anywhere and retains a managed copy under the current curriculum root;
- legacy Question Excel workbooks are still read from the user-selected external path and are not retained as managed assets;
- Exam metadata correction and PDF replacement services relocate or replace managed PDFs;
- full backup/restore explicitly archives and restores separate `pdf/` and `curriculum/` trees;
- persisted managed paths are already relative rather than machine-specific absolute paths.

The existing relative-path design is an important migration advantage. Sprint 14 changes the root-relative meaning of those stored paths rather than having to remove absolute workstation paths from the database.

## 3. Core design decisions

### 3.1 One layout abstraction owns managed paths

Introduce one application-level `ManagedDataLayout` or equivalent.

It owns all path construction for:

```text
subjectsRoot()
subjectDirectory(subject)
curriculumDirectory(subject)
curriculumVersionDirectory(subject, syllabusVersion)
curriculumWorkbookDirectory(subject, syllabusVersion)
curriculumSourceDirectory(subject, syllabusVersion)
legacyImportDirectory(subject, syllabusVersion)
examsDirectory(subject)
examDirectory(exam)
```

Exact public method names may change during implementation, but path construction must not remain distributed across `PdfStore`, curriculum stores, UI code and migration code.

Containment and path-component validation belong at this boundary.

### 3.2 Managed paths become data-root-relative

New managed persistence should use one root semantic.

Examples:

```text
subjects/Chemistry/exams/QCAA/2024/External Assessment/Paper 1.pdf
subjects/Chemistry/curriculum/2025/workbooks/Chemistry-2025.xlsx
subjects/Chemistry/curriculum/2025/sources/<managed-source>.pdf
```

This replaces the current situation in which Exam source paths are interpreted relative to the PDF root while curriculum-source paths are interpreted relative to the curriculum root.

A single data-root-relative contract simplifies resolution, backup, migration and future Subject packaging.

### 3.3 Subject is the filesystem partition; SQLite is still authoritative

The directory structure must not become a second metadata database.

SQLite remains authoritative for:

- Subject and syllabus identities;
- Exam, booklet and Answer-file relationships;
- Questions and Answers;
- Descriptor mappings;
- region capture;
- Shared Context;
- response type;
- audit/completion state;
- other corpus relationships.

Managed files remain source evidence used by those persisted relationships.

### 3.4 Exam storage is Exam-scoped

Target Exam layout:

```text
subjects/<Subject>/exams/<Provider>/<Year>/<Assessment>/...
```

Including Assessment avoids treating `Provider + Year` as the complete filesystem identity and matches the persisted Exam model more closely.

Question and Answer PDFs may remain together in the Exam directory because SQLite already distinguishes their roles.

### 3.5 Curriculum storage is syllabus-version-scoped

Target curriculum layout:

```text
subjects/<Subject>/curriculum/<Version>/
    workbooks/
    sources/
```

Sprint 13 #97 is the transitional prerequisite: it makes external workbook selection independent of the managed destination. Sprint 14 changes that destination to the final Subject/version location.

### 3.6 Legacy workbooks become retained managed source assets

Target legacy layout:

```text
subjects/<Subject>/legacy/<SyllabusVersion>/...
```

`LegacyQuestionWorkbookStore` now copies or safely reuses the selected workbook in managed storage before preflight proceeds. Byte-identical same-name workbooks reuse the retained file; same-name/different-content workbooks receive distinct UUID-qualified filenames rather than overwriting existing provenance.

Once retention succeeds, the pending legacy-import transaction carries only the managed workbook path. Initial preflight, Exam/Assets Recheck and final metadata import therefore continue from the retained copy even if the original external file is removed.

The workbook remains source/provenance material rather than an alternative database. SQLite remains authoritative for imported Questions, Exams, classifications, Answers and capture state. Distributed work-package design remains outside Sprint 14.

### 3.7 Do not run mixed old/new path semantics

An upgraded application must not silently open an old data root and begin creating new Subject-first assets while old persisted paths still require the former `pdf/` or `curriculum/` roots.

Sprint 14 migration therefore needs an explicit legacy-layout detector.

Normal startup must fail safely with a clear migration-required path until the data root is either already current or has been successfully migrated.

## 4. Planned implementation slices

### Slice 1 — #101 Subject-first managed data layout contract

Create and test the central layout abstraction.

Primary outcomes:

- canonical `subjects/` tree;
- central path validation and containment;
- data-root-relative resolution;
- `ApplicationConfig.dataRoot()` becomes the path basis for new managed stores;
- existing separate roots remain only where legacy compatibility/migration requires them.

No existing managed files move in this slice.

### Slice 2 — #102 Move Exam and Answer PDFs

Refactor the Exam managed-file path.

Affected areas include:

- `PdfStore`;
- Question booklet import;
- Answer-file import/replacement;
- Exam metadata correction and relocation;
- managed-source deletion;
- capture source resolution;
- Exam/Assets preview;
- Question preview;
- revision rendering;
- audit paths and any other `PdfStore` consumers.

Newly created `SourceDocument.relativePath` values use the new data-root-relative path contract.

### Slice 3 — #103 Move curriculum assets

Refactor:

- `CurriculumWorkbookStore`;
- `CurriculumSourcePdfStore`;
- curriculum import wiring;
- curriculum authoring source-PDF handling;
- persisted curriculum source-path resolution.

New curriculum workbooks and source PDFs are isolated by Subject and syllabus version.

### Slice 4 — #104 Retain legacy import workbooks

Introduce a managed legacy-workbook store.

Required flow:

```text
user selects workbook anywhere
        ↓
copy/reuse beneath Subject + syllabus version
        ↓
preflight from managed copy
        ↓
resolve missing Exam/booklet structure if required
        ↓
import from managed copy
```

A copy failure prevents import from beginning.

The existing conflict-aware/idempotent metadata importer remains authoritative for database behaviour.

### Slice 5 — #105 Existing-data migration

Migration is a coordinated data operation, not a manual Explorer move.

Required phases:

```text
detect legacy layout
        ↓
inspect database + managed roots
        ↓
calculate complete old → new plan
        ↓
dry-run/report blockers
        ↓
copy to new hierarchy
        ↓
verify bytes/hashes
        ↓
transactionally update persisted relative paths
        ↓
verify every managed database reference
        ↓
publish migration completion
        ↓
archive/remove old managed roots only after success
```

Migration must detect:

- unsafe old relative paths;
- missing referenced files;
- destination collisions;
- same-name/different-content conflicts;
- partially migrated state;
- verification failure.

A failed migration must leave the installation recoverable.

A successful migration must leave no persisted Exam or curriculum source reference dependent on the old root semantics.

### Slice 6 — #106 Backup/restore compatibility

The new full-backup contract becomes conceptually:

```text
backup-manifest.properties
questionbank.db
subjects/
```

Automatic database-only backups remain database-only.

The backup format should be versioned when the archive contract changes.

Sprint 14 must define a supported path for existing full backups that contain:

```text
pdf/
curriculum/
```

The recommended compatibility path is to stage the legacy archive safely and translate its managed data through the same migration/layout rules rather than teaching ordinary runtime stores to support both layouts indefinitely.

Backup/restore must retain:

- SQLite-consistent snapshots;
- archive traversal protection;
- staging before publication;
- pre-restore safety backup;
- rollback/recovery behaviour;
- restart after successful restore;
- exclusion of machine-specific configuration and backup recursion.

## 5. Migration and compatibility rules

### 5.1 Pre-migration backup

Before applying a real layout migration to an existing installation, create a full recoverable backup using the pre-Sprint-14 backup implementation or an equivalent migration safety snapshot.

Migration must not delete the only old copy of source assets before successful verification.

### 5.2 Persisted path updates

The migration service should collect every affected persisted path before file changes are published.

Database updates should occur only after destination copies verify.

Affected persisted path families include at least:

- `source_documents.relative_path`;
- curriculum source-PDF persisted relative paths.

No database schema change is expected solely for the directory restructure unless implementation discovers a missing persistence contract.

### 5.3 Idempotence and interruption

Migration must be safe to re-run after interruption.

A destination file already containing the expected bytes may be reused.

A destination containing different bytes is a blocker and must not be overwritten silently.

### 5.4 Cleanup

Deletion of the old `pdf/` and `curriculum/` roots is the final step, not part of the copy phase.

Archiving or explicit retained-old-tree handling is preferable until post-migration verification is complete.

## 6. Test strategy

Focused tests should be added with each slice rather than deferring all coverage to migration closeout.

### Layout tests

Verify:

- deterministic Subject/curriculum/legacy/Exam paths;
- absolute paths rejected where relative paths are required;
- `..` traversal rejected;
- path components cannot escape their intended directory;
- Subject/version/Exam isolation.

### Exam tests

Verify:

- import destination;
- byte-identical reuse;
- same-name/different-byte collision;
- metadata correction relocation;
- replacement and retirement cleanup;
- deletion containment;
- capture/preview/render resolution.

### Curriculum tests

Verify:

- external workbook selection;
- managed workbook destination;
- workbook reuse/collision;
- source-PDF storage;
- source path persistence/resolution;
- multiple Subject/version isolation.

### Legacy workbook tests

Verify:

- external source copied before import;
- import uses managed copy;
- copy failure prevents database import;
- same-name/different-content collision handling;
- pending import/resume continues from the managed path.

### Migration integration tests

Build a realistic pre-Sprint-14 temporary data root containing:

- database;
- Exam PDFs;
- Answer PDFs;
- curriculum workbook;
- curriculum source PDF;
- persisted source-document references.

Then verify:

- dry-run changes nothing;
- successful migration copies and verifies files;
- persisted paths are rewritten;
- fresh repositories/services resolve all migrated files;
- old roots are not required afterwards;
- collision fails before publication;
- missing source fails safely;
- late failure preserves recoverability;
- re-running after an interrupted/partial copy is safe.

### Backup/restore tests

Verify:

- new full backup contains `subjects/`;
- new full backup round-trips all Subject assets;
- retained legacy workbook is included;
- backup directory remains excluded;
- format/version validation is correct;
- supported old-format backup remains recoverable;
- restore restart/shutdown behaviour remains unchanged.

## 7. Manual acceptance

Before Sprint 14 is considered complete, use a disposable realistic data root.

1. Start from a pre-Sprint-14 layout containing real-style Chemistry data.
2. Create a safety full backup.
3. Run migration dry-run and inspect the plan.
4. Apply migration.
5. Start the application normally.
6. Verify:
   - curriculum workbook opens/import state is intact;
   - curriculum source PDFs are present;
   - Exam/Answer PDFs open;
   - Question and Answer capture open the expected source;
   - Question preview and revision rendering work;
   - Exam metadata correction still relocates source assets correctly;
   - legacy workbook import uses the retained managed copy.
7. Create a new full backup.
8. Restore that backup into a second disposable data root.
9. Restart and repeat representative source/capture checks.
10. Test at least one existing pre-Sprint-14 full backup through the documented compatibility path.

## 8. Out of scope

Sprint 14 does not implement:

- distributed worker/coordinator work packages;
- multi-user merge/conflict resolution;
- network or cloud synchronisation;
- per-Subject SQLite databases;
- automatic file watching;
- content deduplication across Subjects;
- general asset-version history;
- unrelated Dashboard/capture UX changes.

The directory restructure is intended to make later distributed collection cleaner, but that later feature requires its own data-exchange design.

## 9. Definition of done

Sprint 14 is complete when:

1. one central layout abstraction owns managed filesystem structure;
2. Subject is the primary partition for Exam, curriculum and retained legacy source assets;
3. new persisted managed paths use one data-root-relative contract;
4. Exam/Answer workflows operate against the Subject-first structure;
5. curriculum workflows operate against the Subject/version structure;
6. legacy Question workbooks are retained as managed Subject assets before import;
7. existing installations can be migrated conservatively with dry-run, collision detection, copy verification and controlled persisted-path updates;
8. normal application startup cannot silently use incompatible legacy path semantics;
9. full backup/restore uses the new Subject tree and preserves a tested compatibility path for supported older backups;
10. focused regression tests and the full suite are green;
11. manual migration and backup/restore acceptance succeeds on a disposable realistic data root.

## 10. GitHub tracking

#100 remains the umbrella issue.

Planned Sprint 14 project settings:

| Issue | Planned Slice | Status at sprint start |
|---|---:|---|
| #101 | 1 | Sprint |
| #102 | 2 | Sprint |
| #103 | 3 | Sprint |
| #104 | 4 | Sprint |
| #105 | 5 | Sprint |
| #106 | 6 | Sprint |

All six should use `Sprint = Sprint 14`.

Only the issue currently being implemented should move from `Sprint` to `In Progress`. Completed and verified issues move to `Ready for PR` but remain open until the Sprint 14 pull request is merged.
