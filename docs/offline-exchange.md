# Offline Work Assignments, Contributions and Subject Snapshots

> Sprint 24 operating contract, verified 26 November 2026. See [#276](https://github.com/corneym/Exam/issues/276) and [Sprint 24 verification](design/sprint-24-offline-exchange.md). This describes the audited release candidate, not a claim that a new installer has already been published.

## One application, independent data roots

Coordinator, contributor (worker) and Head of Department (HoD) describe responsibilities, not separate applications, accounts or selectable roles. The same Corpus Dashboard is used in all installations. Capabilities depend on persisted Subject authority and active Work Assignment context.

- A locally authoritative Subject may be maintained by its installation. An imported Subject is read-only; subsequent approved snapshots from its originating authority update it.
- Each contributor works in an independent local data root. A live SQLite database must not be shared or concurrently edited between installations.
- Exchange packages use `.examwork` files. Transfer packages outside the managed data root using an approved means of file transfer. The sender and receiver need not be online at the same time.
- Treat packages as educational data containing records and potentially source PDFs. Only distribute them to authorised recipients. Keep production, testing and development data roots separate.

For testing, prepare a restored production backup in a disposable coordinator root and empty contributor and HoD roots. Do not pre-create Subjects in empty receiving banks. In Eclipse, the JVM argument `-Deqb.config.file="D:/ExamTesting/contributor.properties"` selects an existing alternate properties file containing, for example, `data.root=D:/ExamTesting/Contributor`. Use a different configuration and root for each instance. This is a development/test convenience, not an application role switch.

## Coordinator: issue a Work Assignment

1. Select the locally authoritative Working Subject and the Exam in the Corpus Dashboard.
2. Choose **Create Contributor Assignment** and complete the contributor details and package destination prompts. Preflight validates the assignment and required source assets before publication.
3. Transfer the resulting Work Assignment to the contributor. The Exam is now assigned/locked (blue row and ASSIGNED status). It remains inspectable, but ordinary protected capture, asset, metadata, classification and lifecycle changes are prohibited.

A Work Assignment represents a whole Exam, including the relevant curriculum, booklets, source assets and issued baseline. Each assignment has a unique immutable identity. Only one active assignment per Exam is allowed. Complete or otherwise ineligible Exams cannot be issued.

The coordinator may explicitly cancel an outstanding assignment without accepting a return. This unlocks the Exam. A later return for that cancelled assignment must not mutate the authoritative bank. Further work requires a new assignment.

## Contributor: install, capture and submit

1. In the receiving installation, choose **Import → Import Work Assignment** and select the package. The application validates the archive and installs the assigned Exam and dependencies. An empty bank is supported.
2. Use the Dashboard to capture new work. Selecting another active assignment switches context automatically. Baseline content and authoritative structure are protected; the contributor may complete or correct their own unsent additions and capture a missing Answer for an issued Question. Classify new Questions against the issued curriculum, not by changing issued classifications.
3. Choose **Export → Export Assignment Contribution** once the work is complete. An unchanged assignment cannot produce an empty Contribution. Save the validated final archive outside the managed data root and transfer it back to the coordinator.

Successful publication records the assignment as SUBMITTED. It disappears from the active assignment selector and Dashboard workspace; the same assignment cannot be reopened for additional work or exported again. Keep the original exported archive so it can be resent without generating another return. Do not treat submission as confirmation that the coordinator has received or accepted it.

An unchanged assignment can instead be removed from active use and reimported. Removal of an assignment with saved work is refused; submit the work rather than discard it through that control.

**Current limitation:** submitted working records, original baseline and submission evidence remain stored locally. Some retained records can still appear read-only in Search, and HTML/SCORM output may remain available to the worker. Do not use the worker installation as a continuing revision bank after submission. Physical disposal and complete post-submission scope isolation are deferred (#301, #303).

## Coordinator: accept a Contribution

1. Choose **Import → Import Contribution** and select the returned `.examwork` file.
2. Inspect the review dialog. It presents the returned work and summary counts. Only the matching outstanding assignment may be accepted; invalid, unrelated, duplicate or closed-assignment returns must not mutate the authoritative data.
3. Choose **Merge Contribution** to accept the validated return as a whole, or **Cancel** to leave the authoritative corpus unchanged. The valid pending review can be reopened while the assignment remains active.
4. A successful merge creates the automatic full pre-import backup, applies the return transactionally, records its outcome and unlocks the Exam. Confirm the new Questions, Answers and associated content are available through ordinary inspection.

There is one final return per assignment. No manual import-receipt export, worker receipt acknowledgement or routine two-way rebase is required. Internal identities, revision evidence, import outcomes and recovery information are retained.

**Current limitation:** the Import Contribution command may remain available in installations without an outstanding coordinator assignment (#306). Its availability is not permission to merge; service validation still requires matching active authoritative lineage.

## HoD: initial Subject Snapshot

1. The coordinator selects a locally authoritative Working Subject and chooses **Export → Export Working Subject Snapshot**. Save the complete `.examwork` snapshot outside the managed data root.
2. The HoD chooses **Import → Import Subject Snapshot** and selects that archive. An entirely empty installation is supported; additional independent Subjects may coexist in the same bank.
3. Review the proposed import and approve it. The application automatically creates the full pre-import backup, validates the archive and installs the Subject, curriculum, Exams, Question/Answer content and managed assets. The ordinary Dashboard is refreshed.
4. Use the Working Subject selector and Search/inspection for the imported Subject. The Subject is read-only. Do not attempt to edit its authoritative Exam/curriculum data or originate assignments or another authoritative Subject Snapshot from it.

Ordinary assessment, capture, asset, legacy-import and curriculum mutation APIs enforce imported-Subject authority in addition to the UI. Ownership is resolved from persisted records rather than trusting stale or forged object associations. Approved import/refresh remains the authorised replacement route.

**Current limitation:** direct Exam/Assets PDF inspection is not yet available in the HoD context. Curriculum editing is deliberately disabled. Search and normal question inspection remain available. See #304 for read-only asset viewing and standalone PDF menu cleanup.

## HoD: subsequent Subject refresh

Keep the HoD bank and its accepted Subject intact between imports.

1. The authoritative coordinator adds or corrects data in the Subject and exports a newer complete Subject Snapshot.
2. The HoD imports it through **Import Subject Snapshot**. The application distinguishes an existing imported Subject from a new Subject and validates originating authority and freshness.
3. Inspect the proposed comparison and approve or cancel. Cancel leaves the existing accepted Subject unchanged. Approval is bound to the exact archive and comparison; a changed archive or changed destination is not silently accepted.
4. The application creates a verified full backup before mutation, applies the supported authoritative graph and managed-asset changes, records accepted freshness and reloads the Dashboard. Other Subjects must remain unchanged.
5. An exact already-accepted archive is a non-duplicating replay. Older or foreign snapshots cannot rewind accepted authority. A newer identical snapshot can advance accepted freshness without duplicating content.

Refresh supports additions, corrections, removals and managed-file replacement within its validated scope. Complex natural-key conflicts involving surviving dependencies and shared-resource conflicts can be refused safely; do not resolve such failures by overwriting files or directly editing SQLite. The comparison currently contains low-level change records; a more readable presentation is tracked in #308.

## Backup, migration and recovery

The Sprint 24 candidate uses schema v49. The v48 migration adds imported Subject authority and contributor visibility/disposition evidence; v49 adds originating snapshot freshness evidence. Existing locally authoritative Subjects remain local unless authority is deliberately recorded by import. Earlier Subject-first layout migration is a separate prerequisite where applicable.

Back up production data before upgrading or transferring it to a new installation. Imports that mutate a corpus create automatic full pre-import backups under `backups/pre-import/` in the receiving data root. Publication journals and validated recovery protect database and managed-file consistency across failures; do not delete journal or staging evidence to force an operation through. Backups are not substitutes for retaining original exchange archives.

The application does not currently delete imported package files automatically. Retain them outside the managed data root until no longer required; safe cleanup is tracked in #309. In particular, do not delete a worker's original published Contribution when it may need to be resent.

## Accepted release limitations and follow-up

The maintainer accepted these limitations for the initial multi-Subject deployment. None should be represented as a resolved defect.

| Issue | Limitation / improvement |
| --- | --- |
| [#301](https://github.com/corneym/Exam/issues/301) | Submitted corpus retained; some subsequent assignments to the same worker fail closed because historical acceptance evidence, authoritative deletions, changed shared curriculum or occupied PDF replacement cannot be reconciled. Simplify by disposing of obsolete working data. |
| [#302](https://github.com/corneym/Exam/issues/302) | Delay before applicable Dashboard capture controls enable after returning from capture. |
| [#303](https://github.com/corneym/Exam/issues/303) | Residual contributor Search/revision-output access after submission. |
| [#304](https://github.com/corneym/Exam/issues/304) | HoD read-only asset/PDF inspection and redundant standalone PDF menu actions. |
| [#305](https://github.com/corneym/Exam/issues/305) | Subject Snapshot export progress lacks the standard spinner. |
| [#306](https://github.com/corneym/Exam/issues/306) | Import Contribution availability is not fully gated by outstanding coordinator assignments. |
| [#307](https://github.com/corneym/Exam/issues/307) | Mixed-Exam MCQ Answer/Explanation capture workflow and outstanding-work reporting. |
| [#308](https://github.com/corneym/Exam/issues/308) | Subject refresh comparison needs meaningful human-readable records. |
| [#309](https://github.com/corneym/Exam/issues/309) | Safe cleanup of successfully imported package files. |

Where a later assignment is refused by the current retained-corpus reconciliation rules, a separate fresh contributor data root is a practical temporary option. Preserve other outstanding assignments, unsent work and original published Contributions before doing so. Do not bypass integrity validation. Use the issue tracker for any unanticipated failure.
