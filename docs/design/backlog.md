# Exam Question Bank Backlog

> **Authoritative deferred/unresolved-work list:** 25 September 2026.  
> Sprints 01–09 are complete and merged.  
> Sprint 10 implementation is complete and verified on `feature/capture-output`;
> merge to `main` is pending.  
> Completed Sprint 10 work is not duplicated below as future work.

## Revision-output follow-on work

### Printable/vector-preserving output

For print-oriented PDF generation, evaluate direct source-PDF page/viewport
clipping rather than rasterising web assets. Preserve generated numbering,
solution alignment and source attribution.

The current student HTML/SCORM pipeline already supports grouping, response-type
sections, page-local numbering, selected Units, empty-branch pruning,
Question-specific exclusions and generation metadata.

## Capture / correction follow-on work

### Remove empty managed directories after Exam relocation

Sprint 09 correctly relocates managed exam/answer PDF files and updates
persisted `SourceDocument` paths when corrected provider/year metadata changes
the authoritative storage directory. The relocation service does not remove
source directories that become empty after a successful move.

Future housekeeping may safely prune empty managed year/provider directories,
working upwards only within the managed PDF root and stopping at the first
non-empty or protected directory.

### Multi-page automatic shared-context capture

Persistence supports several ordered `SharedQuestionContextRegion` records. The
automatic independent-MCQ continuation workflow currently captures one context
region.

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

This is distinct from the implemented Question-specific output exclusion, which
suppresses one current revision placement for an otherwise stored Question.

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
codes, missing MCQ answers, shared-context evidence and relocated source PDFs.

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
SourceQuestion/shared-context reconstruction, Question-specific exclusions and
complete export after reopen.

Existing integration output tests use in-memory curriculum/retrieval boundaries,
and real Chemistry acceptance has exercised the production path manually. This
remains hardening rather than a current blocker.

### Pre-v13 backup restore/startup regression

Add one composed regression beginning with a populated pre-v13 backup snapshot,
restoring through the production restore path and then starting/reopening the
database at the current schema.

Existing migration tests cover populated v12 -> v13 upgrade, and restore
compatibility probing already migrates a disposable copy. The missing composed
scenario is therefore hardening rather than a current blocker.

## Repository housekeeping

Protected `main` is established and has been exercised through pull request #1.

Sprint 10 is in final protected-main merge closeout on
`feature/capture-output`. Merge requires the final feature-branch GitHub Actions
checks to be green.

Feature/chore branch cleanup may follow merge when convenient; branch cleanup is
repository housekeeping rather than product scope.

## Completed / not backlog

Do not re-add completed Sprint 04–10 work, including:

- backup/restore and deterministic revision/SCORM foundations;
- SourceQuestion/shared-context semantics and correction;
- curriculum authoring, response-type persistence and Corpus Audit;
- Sprint 09 shared-context recapture, known-PDF reuse, Exam correction,
  managed-PDF relocation, legacy split, Search scope and CI hardening;
- Working Subject capture filtering;
- booklet Question format and booklet-specific AnswerFile assignment;
- independent-MCQ Shared Context continuation;
- Search dialog size and position restoration;
- Search classification display/refinement, native-window dirty-close protection
  and stored-region edit navigation;
- safe Subtopic/Descriptor revision grouping with automatic Subtopic fallback
  where direct Subtopic placements prevent Descriptor grouping;
- response-type section ordering/headings;
- page-local numbering;
- empty-branch pruning;
- selected-Unit Revision HTML and SCORM export;
- student-facing revision-question counts and generated timestamp;
- sticky curriculum breadcrumb navigation on generated revision pages;
- Question-specific revision-output exclusions;
- Shared Context live-schema terminology migration;
- public API Javadoc closeout with strict warning-free generation.

## Backlog rules

- Keep every known deferred requirement represented until implemented,
  deliberately rejected or superseded.
- Do not keep completed sprint items duplicated as future work.
- Preserve completed sprint documents as history.
- Keep performance work measurement-driven.
- Prefer behaviour-focused regressions over coverage percentages.
- Do not invent placeholder data to resolve unclear ownership/semantics.
- Do not promote chat prototypes or standalone mapping artefacts to application
  implementation without repository/persistence evidence.
