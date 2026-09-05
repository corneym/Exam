# Sprint Design: Backup, Restore and Data Safety (version 1)

## Branch

`feature/backup-restore`

## Sprint Status

**Complete — Sprint 04, 5 September 2026.**

## Implementation Outcome

Sprint 04 delivered:

- versioned backup format version 1;
- SQLite-consistent snapshots using `VACUUM INTO`;
- snapshot schema and integrity validation;
- manual full backups containing the database, managed PDFs and curriculum files;
- automatic database-only backup on normal application close;
- bounded retention of the 10 newest successful automatic backups;
- a common backup-aware shutdown path for File → Exit and window close;
- explicit Retry / Exit Without Backup / Cancel Exit handling when automatic backup fails;
- validated restore staging with ZIP path-safety checks;
- validation that older database schemas can actually migrate before restore;
- database-only and full restore;
- full pre-restore safety backup;
- automatic rollback when destructive restore fails;
- forced restart after successful or partially destructive restore;
- focused service, SQLite and JavaFX lifecycle tests;
- successful manual full-backup and restore acceptance testing.

## Purpose

The application now stores increasingly valuable manual work:

- curriculum mappings;
- curriculum mapping reviews;
- question metadata;
- ordered question-region coordinates;
- answer/marking-region coordinates;
- imported historical classifications;
- source examination/booklet relationships;
- capture-completion state.

Much of this work is repetitive and expensive to recreate.

Before further large-scale capture and before the hierarchical HTML/SCORM export work begins, the application needs a reliable data-safety boundary.

This sprint will add:

1. a versioned backup format;
2. a consistent SQLite snapshot mechanism;
3. explicit manual full backup;
4. automatic database backup on normal application close;
5. validated restore;
6. retention and failure handling;
7. tests proving backup/restore across fresh application/repository instances.

The sprint is about **recoverability**, not merely copying files.

---

## Core Design Principles

### 1. A backup must be restorable

A successful backup is not defined by "a ZIP file was written".

It is defined by:

```text
backup created
    ↓
live data removed/changed in a test environment
    ↓
backup restored
    ↓
fresh application/repository instances
    ↓
questions, mappings, regions and documents are usable again
```

Round-trip restore tests are mandatory.

### 2. Do not copy a live SQLite database blindly

The database may be accessed through short-lived connections while the application is running.

A backup must use a SQLite-consistent snapshot mechanism rather than relying on `Files.copy(...)` of a potentially active database file.

The implementation may use an appropriate SQLite/JDBC backup facility or a supported SQLite snapshot operation such as `VACUUM INTO`, but the chosen approach must be tested against the actual JDBC/SQLite runtime used by this project.

The resulting snapshot must be reopened and validated before it is accepted as a backup.

### 3. Frequent automatic backup and full archival backup have different costs

The managed PDF library can be large and changes much less frequently than mappings/regions in SQLite.

Therefore this sprint distinguishes:

#### Automatic close backup

Frequent, lightweight protection:

```text
backup manifest
+ consistent SQLite database snapshot
```

This is intended primarily to protect the high-value manual database work between application versions and work sessions.

#### Manual full backup

Portable disaster-recovery archive:

```text
backup manifest
+ consistent SQLite database snapshot
+ managed pdf/
+ managed curriculum/
```

The automatic backup directory itself must never be recursively included in a full backup.

### 4. Machine-specific configuration is not portable backup data

`questionbank.properties` contains machine-specific configuration and must not be restored as though it were managed question-bank data.

A restored dataset should work under the receiving machine's configured `data.root`.

### 5. Backup writes must be staged safely

A failed backup must not leave a file that appears complete.

The expected pattern is:

```text
create temporary output
        ↓
write snapshot/archive
        ↓
validate
        ↓
close all streams
        ↓
rename/move to final backup filename
```

Only the final successfully validated file is presented as a usable backup.

### 6. Restore must validate before destructive replacement

A restore operation must not overwrite the current dataset until the selected backup has been fully validated.

Validation should include:

- supported backup format version;
- required entries;
- safe relative paths;
- database can be opened;
- database schema version is supported or migratable;
- database integrity check succeeds;
- full-backup managed files can be staged successfully.

### 7. Restore should protect the state it is replacing

Before a destructive restore, create a pre-restore safety backup of the current database/data where possible.

If restore fails after replacement begins, the operation must either roll back from staging/pre-restore state or leave a clear recoverable state. Silent partial restore is unacceptable.

### 8. Restore is an application lifecycle operation

After a restore, existing in-memory objects and open PDF sessions may refer to the old dataset.

The application must not continue normal operation against stale objects.

A successful restore should therefore terminate the current application session and require a restart unless a later design proves that a complete safe reinitialisation is practical.

### 9. All normal exit paths should use one backup-aware shutdown path

Automatic backup must work whether the user closes through:

- `File → Exit`;
- the main window close control;
- another normal application-close route.

Avoid separate shutdown logic that can accidentally bypass backup.

### 10. Backup failure must not be silent

If an automatic backup cannot be created during a normal user-requested exit, the user must be informed.

The shutdown path should make the failure explicit and provide a deliberate choice rather than silently pretending protection succeeded.

An operating-system crash or forced process termination cannot be guaranteed to run close-backup logic; retained previous backups protect against that class of failure.

---

# Backup Package Contract

## Backup format version

The first implemented format is:

```text
backup format version 1
```

The backup manifest must contain at least:

- backup format version;
- backup kind;
- creation timestamp;
- database schema version;
- application/version information where available;
- expected managed roots/entries.

The manifest format should remain simple and dependency-light. A Java properties-style manifest is acceptable if the project does not already have a justified JSON dependency.

## Backup kinds

### AUTOMATIC_DATABASE

Contains:

```text
backup-manifest.properties
questionbank.db
```

### FULL

Contains:

```text
backup-manifest.properties
questionbank.db
pdf/
curriculum/
```

No backup archive should contain another backup archive merely because backups are stored under the configured data hierarchy.

## Suggested filenames

Automatic:

```text
question-bank-auto-2026-09-04T180500.zip
```

Manual:

```text
question-bank-full-2026-09-04T180500.zip
```

Use a filesystem-safe deterministic timestamp format.

## Automatic backup location

Default:

```text
<dataRoot>/backups/automatic/
```

This protects against accidental database loss, bad migrations and normal development/version mistakes.

A manual full backup can be saved to a user-selected external destination for broader disaster recovery.

## Retention

Automatic backups must use bounded retention.

Initial default:

```text
10 most recent successful automatic backups
```

Retention should only delete older confirmed-successful backups after the newest backup has been created successfully.

Do not expose a settings framework solely for this value during Sprint 04 unless it falls out naturally.

---

# Sprint Work Packages

## Work Package 1 — Define the backup domain/service boundary

### Goal

Create an application-level backup boundary that is independent of JavaFX controls.

Likely concepts may include:

```text
BackupService
BackupRequest
BackupResult
BackupManifest
BackupKind
RestoreService
```

Exact class names may evolve, but UI code must not contain ZIP traversal, SQLite snapshot or restore rules.

### Required behaviour

- distinguish automatic database backup from full backup;
- create deterministic backup paths/names;
- expose failures clearly;
- keep backup-format/version knowledge out of JavaFX controls;
- validate caller-supplied backup destinations;
- prevent output paths from escaping the requested destination.

### Tests

- null/invalid argument behaviour;
- automatic vs full package contract;
- deterministic manifest fields where appropriate;
- safe path handling.

---

## Work Package 2 — Implement consistent SQLite snapshots

### Goal

Create a trustworthy copy of the SQLite database while the application is running.

### Required behaviour

- use a SQLite-consistent mechanism;
- never accept a naive live-file copy as the production backup strategy;
- write the snapshot to a temporary/staging path;
- reopen the resulting snapshot through a fresh database boundary;
- confirm the stored schema version;
- run SQLite integrity validation;
- reject incomplete/corrupt snapshots.

### Persistence-sensitive test

A test should:

```text
write representative persisted data
        ↓
create database snapshot
        ↓
mutate/delete original data
        ↓
open snapshot with fresh repositories
        ↓
verify original persisted state exists
```

Representative data should include more than a trivial Subject row. At minimum, exercise data whose loss would matter, such as:

- question metadata;
- ordered question regions;
- confirmed curriculum mapping/review state.

---

## Work Package 3 — Manual full backup

### Goal

Allow the user to create a complete portable backup archive.

### UI

Add under `File`:

```text
Backup Now...
```

A file/directory chooser should allow the user to choose the backup destination.

### Full-backup contents

Include:

- validated SQLite snapshot;
- managed `pdf/` hierarchy;
- managed `curriculum/` hierarchy;
- backup manifest.

Exclude:

- `backups/`;
- generated output;
- Maven `target/`;
- temporary files;
- machine-specific configuration.

### Required behaviour

- preserve relative paths exactly;
- preserve empty managed directories only if useful/necessary;
- safely handle source files changing/disappearing during backup;
- fail the backup rather than silently producing a partial archive;
- write to a temporary filename first;
- publish/rename only after successful completion;
- do not overwrite an existing user backup without explicit user intent.

### Tests

Use a temporary data root containing:

- SQLite data;
- nested PDF files;
- nested curriculum files.

Inspect the ZIP and prove that expected entries exist and excluded entries do not.

---

## Work Package 4 — Automatic backup on normal application close

### Goal

Create a lightweight database backup automatically whenever the application closes normally.

### Required behaviour

- centralise normal shutdown;
- ensure `File → Exit` and the main window close control follow the same path;
- create an `AUTOMATIC_DATABASE` backup before final shutdown;
- retain the configured/default number of successful automatic backups;
- do not delete older backups if creation of the newest backup fails;
- avoid producing duplicate close backups from more than one lifecycle callback;
- close PDF/session resources reliably;
- make backup failure visible.

### Failure behaviour

For a user-requested normal exit:

```text
automatic backup succeeds
    → close application

automatic backup fails
    → show clear failure
    → allow deliberate Retry / Exit Without Backup / Cancel Exit behaviour
```

Exact button wording may be refined in implementation, but silent failure is not acceptable.

### Test expectations

- File Exit route invokes backup once;
- window-close route invokes backup once;
- repeated shutdown callback does not create duplicate backups;
- backup failure does not claim success;
- retention removes only excess older successful backups.

---

## Work Package 5 — Restore

### Goal

Restore either a database-only automatic backup or a full backup without leaving the application in a partially restored state.

### UI

Add under `File`:

```text
Restore Backup...
```

### Restore sequence

Expected high-level flow:

```text
select backup
    ↓
validate archive and manifest
    ↓
stage contents under a temporary restore area
    ↓
validate staged SQLite database
    ↓
confirm destructive restore with user
    ↓
create pre-restore safety backup
    ↓
close active PDF/session resources
    ↓
replace applicable live data
    ↓
verify restored data
    ↓
terminate application
    ↓
user restarts against restored data
```

### Database-only restore

Restores:

```text
questionbank.db
```

Managed PDFs/curriculum remain as currently configured.

### Full restore

Restores:

```text
questionbank.db
pdf/
curriculum/
```

under the current machine's configured `data.root`.

### Required safety

- reject unsupported future backup-format versions;
- reject unsafe ZIP paths (`../`, absolute paths, drive escapes);
- reject missing required entries;
- reject corrupt/unopenable database snapshots;
- reject a database newer than the running application supports;
- allow normal migration of older supported database versions only through the application's existing migration rules;
- do not restore machine-specific absolute path configuration;
- create a pre-restore safety backup before replacement;
- do not continue using stale repositories/models after successful restore.

### Round-trip integration test

The core acceptance test should:

```text
build representative temporary data root
    ↓
create full backup
    ↓
change/delete database and managed files
    ↓
restore
    ↓
construct fresh repositories/services
    ↓
verify database state
    ↓
verify managed PDFs/curriculum files
```

---

## Work Package 6 — Quality gate and merge readiness

### Required review

Before merge:

- compare the full branch with `main`;
- review filesystem path safety;
- review ZIP traversal protection;
- review SQLite consistency assumptions;
- review failure/rollback paths;
- review shutdown lifecycle;
- review restore/restart semantics;
- review public API Javadocs;
- run focused backup/restore tests;
- run SQLite integration tests;
- run UI/TestFX tests for File menu and close behaviour;
- run the complete suite;
- manually create and restore a backup using a disposable development data root.

### Particular review risks

Look for:

- copying a live SQLite file directly;
- backup archives containing themselves recursively;
- partial ZIPs presented as successful backups;
- deleting old backups before a new backup succeeds;
- restore writing outside `data.root`;
- ZIP-slip/path traversal;
- accidental restoration of machine-specific configuration;
- partial restore after an exception;
- continuing to use stale repositories/models after restore;
- automatic backup occurring twice on exit;
- File Exit bypassing automatic backup;
- window close bypassing automatic backup;
- backup failure being swallowed during shutdown;
- full backups becoming unreasonably large because prior backups/generated artefacts are included.

---

# Testing Expectations

## Backup format

- manifest format version is written;
- backup kind is written;
- schema version is written;
- required entries are present;
- unsupported format versions are rejected.

## SQLite snapshot

- snapshot contains committed data;
- snapshot opens through a fresh `SqliteDatabase`;
- integrity validation passes;
- regions and mappings survive snapshot/reopen;
- corrupt snapshot is rejected.

## Automatic backup

- normal close creates one automatic database backup;
- automatic backup name is unique/deterministic enough for repeated sessions;
- retention keeps the newest successful backups;
- failed newest backup does not trigger retention deletion;
- backup failure is surfaced.

## Full backup

- database snapshot included;
- managed PDFs included;
- curriculum files included;
- backups directory excluded;
- temporary/generated/configuration files excluded;
- nested relative paths preserved.

## Restore

- database-only restore round-trips;
- full restore round-trips;
- missing manifest rejected;
- missing database rejected;
- corrupt database rejected;
- unsafe ZIP entry rejected;
- unsupported newer backup format rejected;
- unsupported newer database schema rejected;
- pre-restore backup is created;
- successful restore requires/reaches application restart boundary.

## Regression

Run the complete Maven/TestFX suite before merging.

---

# Manual Acceptance Exercise

Before merge, use a disposable copy of a realistic development data root.

1. Start with known curriculum mappings and captured question regions.
2. Create a manual full backup.
3. Make obvious test changes to the disposable database/data.
4. Restore the backup.
5. Restart the application.
6. Verify:
   - mappings are restored;
   - question metadata is restored;
   - question regions reconstruct correctly;
   - answer data survives;
   - source PDFs open;
   - curriculum source files are present.
7. Close the application normally.
8. Verify a new automatic database backup exists.
9. Repeat enough closes to confirm retention behaviour.

Do not perform destructive restore testing against the only copy of real production/development data.

---

# Out of Scope

The following are not required for Sprint 04:

- cloud backup;
- SharePoint synchronisation;
- network/server backup;
- incremental or deduplicated PDF backup;
- encrypted backup archives;
- scheduled hourly/daily backups while the application remains open;
- crash-time backup after forced process termination;
- multi-user backup coordination;
- human-readable CSV/JSON interchange export;
- SCORM generation;
- hierarchical question export;
- Exam Builder;
- unrelated question-capture UX changes.

A future human-readable raw-data export may be useful for inspection/interchange, but Sprint 04's first responsibility is a **restorable** backup.

---

# Acceptance Criteria

Sprint 04 is complete when:

1. The application can create a SQLite-consistent database backup.
2. The backup is validated by reopening it through fresh database/repository boundaries.
3. A manual full backup includes the database, managed PDFs and curriculum data.
4. Backup metadata includes a backup-format version and database schema version.
5. A normal application close creates exactly one automatic database backup.
6. File Exit and window close use the same backup-aware shutdown behaviour.
7. Automatic backups use bounded retention.
8. Backup failure is visible and does not silently masquerade as success.
9. A database-only backup can be restored safely.
10. A full backup can be restored safely.
11. Restore validates the backup before replacing live data.
12. Restore protects the existing state with a pre-restore safety backup.
13. Restore rejects unsafe/corrupt/incompatible backup packages.
14. A successful restore does not continue normal operation with stale in-memory state.
15. Round-trip tests prove mappings, question metadata and region data survive.
16. Manual acceptance proves managed source documents survive full backup/restore.
17. The full automated suite is green apart from any separately documented pre-existing TestFX flake that is proven unrelated.
18. The sprint does not expand into cloud synchronisation, SCORM or general deployment work.

---

# Definition of Done

The teacher can perform large amounts of curriculum mapping and PDF-region capture with confidence that the work is recoverable.

The application automatically protects the SQLite data on normal close, can create an explicit full portable backup, and can restore validated backups without silently corrupting or partially replacing the live question bank.

The resulting data-safety boundary is stable enough that subsequent hierarchical HTML and SCORM export work can proceed without risking the accumulated corpus.
