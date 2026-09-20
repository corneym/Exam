# Exam Question Bank Backlog

> **Authoritative deferred/unresolved-work list:** 20 September 2026.  
> Sprints 01–09 are complete.  
> Sprint 10 active work is intentionally excluded from backlog sections and is
> listed near the end under **Scheduled in Sprint 10 — not backlog**.

## Revision-output follow-on work

### Multipart provenance presentation

For multipart output with one shared preamble, repeated source decorations such
as `Source part 26a` and `Source part 26b` may be unnecessarily noisy.

Future work may present source/original-classification provenance once for the
multipart presentation while retaining traceability to source parts.

Do not change persisted multipart identity merely to simplify display.

### Optional response-type section headings

After Sprint 10 establishes MCQ-before-written ordering, assess whether explicit
`Multiple Choice` and `Written Response` headings beneath each output bucket add
useful navigation or only visual noise.

### SCORM export-option parity

Sprint 10 is focused on Revision HTML configuration. If any new grouping or Unit
selection option is initially exposed only through the HTML dialog, add equivalent
SCORM configuration later while continuing to reuse the same presentation/render
semantics rather than creating a second SCORM-specific grouping system.

### Printable/vector-preserving output

For print-oriented PDF generation, evaluate direct source-PDF page/viewport
clipping rather than rasterising web assets. Preserve generated numbering,
solution alignment and source attribution.

## Capture / correction follow-on work

### Search dialog position persistence

Sprint 09 preserves the Search Questions dialog's resized width and height when
an edit action temporarily hides and redisplays the dialog. The dialog does not
currently preserve its user-adjusted screen position across that cycle.

Future work should preserve the last valid X/Y position as well as dimensions,
without restoring an off-screen position after monitor/work-area changes. This
is usability polish and is not part of Sprint 10.

### Remove empty managed directories after Exam relocation

Sprint 09 correctly relocates managed exam/answer PDF files and updates
persisted `SourceDocument` paths when corrected provider/year metadata changes
the authoritative storage directory. The relocation service does not remove
source directories that become empty after a successful move.

Future housekeeping may safely prune empty managed year/provider directories,
working upwards only within the managed PDF root and stopping at the first
non-empty or protected directory. The authoritative file relocation itself is
already implemented; this item concerns empty-directory cleanup only and is not
part of Sprint 10.

### Multi-page automatic shared-preamble capture

Persistence supports several ordered `SharedQuestionContextRegion` records. The
automatic imported-question preamble workflow currently completes after one
accepted source region.

Future work should allow multiple ordered context regions across pages, explicit
finish, source-order preservation and regression coverage while keeping the
single-page path efficient.

### MCQ explanation capture

Retain A/B/C/D as the primary MCQ Answer, but optionally allow one or more
marking-PDF regions as explanation material for revision output.

Explanation regions must remain supplementary and must not become required for
corpus completeness.

### Additional capture efficiency

Possible later assistance:

- PDF Question-boundary suggestions;
- multipart/dependency suggestions;
- further keyboard/efficiency improvements.

Suggestions remain non-authoritative until confirmed.

## Curriculum authoring follow-on work

Deferred assistance includes:

- PDF region-based text extraction;
- detection/highlighting of embedded images and diagrams;
- manual exclusion regions for maths/figures;
- Markdown/LaTeX maths editing and rendered preview;
- maths-authoring helper buttons and later equation-recognition assistance;
- further keyboard/efficiency improvements.

Curriculum hierarchy remains expert-authored; assistance must not infer
authoritative structure.

## Remaining Question-model decisions

### Multiple original classifications

Current `Question` stores one best-fit Subtopic or Descriptor. Decide whether to
affirm that policy or implement multiple direct original classifications.

If changed, review schema/repositories, capture/import UI, applicability,
retrieval duplicate semantics, export placement, provenance and tests.

### Explicit out-of-scope source disposition

A later ingestion/audit workflow may need to distinguish:

```text
not yet reviewed
captured/classified
explicitly out of scope
```

without forcing deliberately out-of-scope source Questions into the bank.

### Question-level applicability exceptions

A curriculum mapping can remain valid while one historical Question tests only
content that did not carry forward.

Future behaviour may preserve provenance/mapping while explicitly excluding that
Question from one mapped current target. Prefer node-specific exceptions when a
historical node maps to several current targets.

## Import / reconciliation

### Managed document hashing and duplicate detection

Consider persisted SHA-256 content hashing so identical files can be recognised
independently of filename/storage path. Current collision handling compares file
content where needed but no hash is persisted.

### Import audit and reconciliation reporting

Potential reporting:

- records created;
- records already identical;
- conflicts rejected;
- missing PDFs/booklets;
- Questions still needing regions;
- Answers supplied/absent/unknown;
- unresolved classifications;
- unresolved shared-context requirements.

A dry-run mode may later reuse the same validation.

### Additional real-world workbook variants

Test representative legacy files for multiple Subjects/providers, unexpected
formatting, blank/formula-driven cells, conflicting duplicates, unusual Question
codes, missing MCQ answers, preamble evidence and relocated source PDFs.

Do not infer multipart/shared-context relationships merely because workbook data
appears patterned.

### Legacy image-snip import/support decision

Determine whether irreplaceable legacy Questions/Answers exist only as image
snips and therefore require one-time attachment import or managed-file
compatibility.

## Retrieval / performance hardening

### Strengthen retrieval-domain invariants

Review applicability/result rules including compatible curriculum levels,
same-Subject enforcement, duplicate handling and invalid-level rejection.

### Extend SQLite retrieval integration coverage

Add realistic Subject-wide retrieval, one-to-many mapped Subtopics, same-Subject
isolation, empty Subtopics, direct-Descriptor Topic structures and database
reopen cases.

### Benchmark broad searches

Measure Subject/Unit/Topic-wide searches, query plans, reconstruction cost and
deterministic ordering before adding indexes or redesigning queries.

### Measure preview rendering overlap

Measure large/multi-region previews and rapid selection changes before changing
threading or serialisation.

## Curriculum mapping hardening

### Database enforcement decision

Application writers enforce same Subject, different versions, same level and
allowed mapping direction. Decide whether direct-SQL bypass risk justifies DB
triggers or other enforcement.

### Mapping workflow extensions

Possible later work:

- bulk mapping import;
- assisted review of gaps;
- improved similarity scoring;
- optional AI-assisted suggestions;
- broader coverage reporting.

Any assisted mechanism must still require explicit teacher confirmation.

### Richer mapping-relation metadata

Decide whether relation type/confidence/notes such as split, merged, partial,
removed and new should remain external review metadata or become persisted
application data.

## Future content sources

### Clipboard / image-attachment Questions

Support an eventual workflow such as Windows Snipping Tool -> system clipboard ->
Paste Image / Ctrl+V -> Question attachment.

Design requirements include text plus zero or more images, optional PNG/JPEG
drag/drop, storage choice, provenance, backup/restore and consistent HTML/PDF/
SCORM rendering. OCR is optional later work.

## Packaging / deployment

### Self-contained desktop packaging

Investigate `jpackage` when deployment becomes a priority. Keep writable data
outside installed application files and retain migration/backup safety.

### Shared faculty operation

Do not treat a live SQLite database on SharePoint/network sync as a safe
concurrently edited datastore. Possible later models include local SQLite plus
controlled import/export/merge or an IT-supported central database.

## Testing / reliability

### SQLite-backed revision-export integration coverage

Add a temporary-SQLite end-to-end export test using fresh production
repository/service instances to cover persisted curriculum reconstruction,
confirmed mapping retrieval, ordered regions, Answer reconstruction,
SourceQuestion/shared-context reconstruction and complete export after reopen.

Real Chemistry acceptance has exercised the production path manually, so this is
hardening rather than a current blocker.

### Public API documentation

Continue reviewing Javadoc/doclint where public or semantic contracts change.

## Repository housekeeping

Protected `main` is now established and was exercised through pull request #1.
The former backlog item "Protect main with required CI checks" is therefore
closed and must not be re-added as future product work.

Old feature/chore branches may be cleaned up when convenient; branch cleanup is
repository housekeeping rather than product scope.

## Scheduled in Sprint 10 — not backlog

Do not duplicate these as deferred items while Sprint 10 is active:

- stale logical pending selection after the visible PDF rectangle is cleared;
- curriculum-code / ComboBox hierarchy synchronisation defects;
- capture-workspace Working Subject filtering;
- Written Response defaults from part-letter and marks evidence;
- shared-context capture/reuse for independent Questions including MCQs;
- Subtopic-versus-Descriptor revision-output grouping;
- MCQ-before-written presentation ordering;
- per-page numbering starting at 1;
- empty curriculum-branch pruning and all-non-empty/selected Unit export;
- generated-site status metadata including creation date/time and a
  student-facing revision-question count.

## Completed / not backlog

Do not re-add completed Sprint 04–09 work, including backup/restore, deterministic
revision/SCORM foundations, persisted SourceQuestion/shared-context semantics,
Question/Answer correction, curriculum authoring, response-type persistence,
Corpus Audit, Sprint 09 shared-preamble recapture, known-PDF reuse, Exam metadata
correction, managed-PDF relocation, legacy split, Search scope, audit ordering,
Search dialog size persistence, MCQ Answer-PDF visibility, UI package refactoring
and CI hardening.

## Backlog rules

- Keep every known deferred requirement represented until implemented,
  deliberately rejected or superseded.
- Do not keep active Sprint 10 items duplicated as future work.
- Preserve completed sprint documents as history.
- Keep performance work measurement-driven.
- Prefer behaviour-focused regressions over coverage percentages.
- Do not invent placeholder data to resolve unclear ownership/semantics.
- Do not promote chat prototypes or standalone mapping artefacts to application
  implementation without repository/persistence evidence.
