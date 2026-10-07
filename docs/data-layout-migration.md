# Subject-First Data Layout Migration

Sprint 14 replaces the former managed-data layout:

```text
<dataRoot>/
    questionbank.db
    pdf/
    curriculum/
```

with the Subject-first layout:

```text
<dataRoot>/
    questionbank.db
    subjects/
        <Subject>/
            exams/
            curriculum/
            legacy/
    migration-archive/
```

Normal application startup does not operate with mixed old and new managed-path semantics.

If an existing data root still contains persisted references that depend on the old `pdf/` or `curriculum/` roots, startup stops with **Data Migration Required**.

## Administration entry point

Run:

```text
au.edu.eq.questionbank.admin.DataLayoutMigrationTool
```

as a Java application.

Always run a dry-run before apply.

Program arguments:

```text
--data-root "<dataRoot>" --dry-run
```

The dry-run:

- changes no files;
- changes no database references;
- reports every planned old → new managed path;
- reports existing byte-identical destinations that can be reused;
- reports archive-only legacy material;
- reports every blocker.

A blocked migration must not be applied until every blocker is resolved.

## Legacy curriculum workbook assignment

Pre-Sprint-14 retained curriculum workbooks were stored beneath the old curriculum root without persisted Subject/version provenance.

The migration service does not guess that ownership.

Assign each reported workbook explicitly:

```text
--assign-workbook "<legacy-relative.xlsx>" "<Subject>" "<Version>"
```

Example:

```text
--data-root "D:/ExamData" ^
--assign-workbook "chemistry-2025.xlsx" "Chemistry" "2025" ^
--dry-run
```

Multiple `--assign-workbook` arguments may be supplied.

The named Subject and syllabus version must already exist in SQLite.

## Apply

After the dry-run has zero blockers, run the same arguments using:

```text
--apply
```

Example:

```text
--data-root "D:/ExamData" ^
--assign-workbook "chemistry-2025.xlsx" "Chemistry" "2025" ^
--apply
```

Apply performs the migration in conservative phases:

```text
calculate complete plan
        ↓
copy/reuse Subject-first destinations
        ↓
verify copied bytes
        ↓
transactionally rewrite Exam and curriculum source paths
        ↓
verify all rewritten references resolve
        ↓
commit SQLite transaction
        ↓
archive old pdf/ and curriculum/ trees
        ↓
remove old active roots
        ↓
run final migration-plan verification
```

Destination files containing different bytes are never overwritten.

A destination already containing the expected bytes may be reused after interruption.

A stale or late database-reference change causes the SQLite publication transaction to roll back. Verified destination copies may remain and are safely reusable on the next run.

## Recovery archive

After successful path publication, the old roots are retained under:

```text
migration-archive/
    pre-subject-first/
        pdf/
        curriculum/
```

The archive includes both formerly referenced source assets and unrelated regular files found in the old roots.

The old active `pdf/` and `curriculum/` roots are removed only after their contents are copied or verified in the recovery archive.

Do not move or delete old managed files manually as part of the supported migration procedure.

## Successful completion

A migration is complete only when:

- every persisted Exam source path is data-root-relative beneath `subjects/`;
- every persisted curriculum source-PDF path is data-root-relative beneath `subjects/`;
- assigned legacy curriculum workbooks exist under their Subject/version `workbooks/` directory;
- every migrated managed file resolves and exists;
- the old active managed roots have been archived and removed;
- a fresh migration plan reports `Migration required: no`;
- normal application startup passes the migration guard.

Running the same successful `--apply` command again is a safe no-op.
