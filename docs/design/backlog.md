# Exam Question Bank Backlog

> Authoritative deferred/unresolved-work list at 20 September 2026.
>
> Sprint 08 is complete and merged.
> Sprint 09 implementation is complete on `feature/capture-workflow`; completed
> Sprint 09 items are recorded under **Completed / not backlog** rather than kept
> as future work.

## High-value next design input — revision HTML presentation

These items were deliberately kept outside Sprint 09 so capture/correction work
did not expand into a presentation redesign.

### Select revision-output curriculum grouping depth

Origin: application-use review, 17 September 2026.

Where Descriptors are present, allow export to choose presentation grouping at
Descriptor or Subtopic level.

If Subtopic grouping is selected:

- Questions classified to Descriptors are flattened into the parent Subtopic;
- no separate Descriptor section is emitted;
- ordering should still respect Descriptor order, then deterministic Question
  order.

The persisted curriculum classification is not rewritten by this presentation
choice.

### Multipart provenance presentation

Origin: application-use review, 17 September 2026.

For multipart output with one shared preamble, repeated decorations such as
`Source part 26a` and `Source part 26b` are unnecessary.

Prefer a single source/original-classification provenance block for the multipart
presentation while retaining traceability to source parts.

Do not change persisted multipart identity merely to simplify display.

### MCQ before written-response Questions

Origin: application-use review, 17 September 2026.

Within an output curriculum bucket, present multiple-choice Questions before
written-response Questions while retaining deterministic ordering within each
response-type group.

### Optional MCQ and Written Response sections

Origin: application-use review, 17 September 2026.

Consider explicit `Multiple Choice` and `Written Response` sections beneath each
Subtopic/output bucket. Decide this together with grouping-depth and ordering
semantics.

## Capture / correction follow-on work

### General shared-context capture for independent Questions

Origin: real capture use during Sprint 09, 20 September 2026.

The domain already separates `SharedQuestionContext` from `SourceQuestion`, so
otherwise independent Questions may legitimately share one stimulus/context.
This includes successive MCQs using the same graph, table, passage or diagram.

The current normal capture UI still makes convenient shared-context capture/reuse
primarily part of multipart/preamble workflows.

Future behaviour should provide an explicit general control such as:

```text
Shared context:
    None
    Capture new
    Reuse existing
```

Requirements:

- do not invent a `SourceQuestion` relationship merely to share context;
- support Multiple Choice and Written Response equally;
- allow a later independent Question to reuse a previously captured context;
- do not silently carry context forward to the next Question;
- preserve current multipart automatic behaviour;
- add persistence/reload and UI workflow regressions.

### Multi-page automatic shared-preamble capture

Origin: Sprint 07 preamble-aware Question capture.

Persistence supports multiple ordered `SharedQuestionContextRegion` records.
The automatic imported-question preamble workflow currently completes after one
accepted source region.

Future work should:

- allow multiple ordered shared-context regions;
- allow page navigation between accepted preamble regions;
- provide an explicit finish action before ordinary Question-region capture;
- preserve the single-page workflow;
- retain shared-context reuse for later source parts;
- keep regions in source/page order;
- add multi-page regressions.

### MCQ explanation capture

Origin: Sprint 07 Answer-capture review.

Future behaviour:

- retain A/B/C/D as the primary Answer;
- optionally capture one or more Answer-PDF regions as explanation;
- render the correct option plus explanation in revision output.

Explanation regions are supplementary and must not be required for corpus
completeness.

## Curriculum authoring follow-on work

### Curriculum code entry can leave hierarchy controls visually out of sync

Origin: real application use during Sprint 09.

Entering a valid hierarchy code such as `3.1` / `3.1.1` can correctly update the
underlying curriculum selection while the Topic ComboBox displays blank even
though a child Subtopic is selected.

Required work:

- identify the model-to-control synchronisation path;
- ensure displayed ComboBox values match the actual selected hierarchy;
- add UI-level assertions for visible ComboBox values, not only selection-model
  state;
- preserve current code-entry behaviour where the model is already correct.

### Curriculum capture refinements

Origin: Sprint 08 curriculum authoring.

Deferred assistance:

- PDF region-based text extraction;
- detection/highlighting of embedded images and diagrams;
- manual exclusion regions for maths/figures;
- Markdown/LaTeX maths editing and rendered preview;
- maths-authoring helper buttons and eventual equation-recognition assistance;
- further keyboard/efficiency improvements.

Curriculum hierarchy remains expert-authored; assistance must not infer
authoritative structure.

## Remaining Question-model decisions

### Decide whether `Question` requires multiple original classifications

Origin: Batching Exam Questions / early metadata design.

Current `Question` stores one best-fit Subtopic or Descriptor. Decide whether to
affirm that policy or implement multiple original classifications.

If changed, review schema/repositories, capture/import UI, applicability,
retrieval duplicate semantics, export placement, provenance and tests.

Do not confuse multiple original classifications with one historical node
mapping to several current nodes.

### Decide whether source-question processing needs an explicit out-of-scope disposition

Origin: Batching Exam Questions prototype.

A later ingestion/audit workflow may need to distinguish:

```text
not yet reviewed
captured/classified
explicitly out of scope
```

without forcing out-of-scope source Questions into the bank.

### Support Question-level applicability exceptions after curriculum mapping

Origin: historical-to-current curriculum mapping / Question review.

A curriculum mapping can remain valid while one historical Question tests only
content that did not carry forward.

Future behaviour may:

- preserve original historical classification and mapping;
- allow explicit exclusion from a mapped current target;
- show a clear viewer marker;
- make retrieval/revision/export respect the exclusion;
- distinguish applicable, not applicable and, if required, not yet reviewed.

Prefer node-specific exceptions where one historical node maps to several target
nodes.

## Import / reconciliation

### Managed document content hashing and duplicate detection

Origin: managed-document storage review.

Consider SHA-256 content hashing so identical files can be recognised
independently of filename/storage path.

Current exam/answer import already compares bytes where destination names
collide; no document hash is persisted.

Potential uses include duplicate detection, safe content reuse and
backup/integrity verification.

### Add import audit and reconciliation reporting

Origin: Legacy Metadata Import follow-on work.

Report:

- records created;
- records already identical;
- conflicts rejected;
- missing PDFs/booklets;
- Questions still needing regions;
- Answers supplied/absent/unknown;
- unresolved classifications;
- unresolved shared-context requirements.

A dry-run mode may later reuse the same validation.

### Validate additional real-world legacy workbook variants

Origin: Legacy Metadata Import follow-on work.

Test representative files for multiple Subjects/providers, unexpected formatting,
blank/formula-driven cells, duplicate conflicting rows, unusual Question codes,
missing MCQ answers, preamble evidence and relocated source PDFs.

Do not infer multipart/shared-context relationships merely because workbook data
appears to contain a pattern.

### Decide whether legacy image-snip import/support is still required

Origin: Batching Exam Questions.

Determine whether irreplaceable legacy Questions/Answers exist only as image
snips and therefore require one-time attachment import or managed-file
compatibility.

## Retrieval / performance hardening

### Strengthen retrieval-domain invariants

Review `QuestionApplicabilityMatch` and `QuestionRetrievalResult` rules,
including compatible curriculum levels, same-Subject enforcement, duplicate
handling and rejection of invalid applicability levels.

### Extend retrieval integration coverage

Add realistic SQLite integration cases for Subject-wide retrieval, one-to-many
mapped Subtopics, same-Subject isolation, empty Subtopics, Topic-level Descriptor
structures and database reopen reconstruction.

### Benchmark broad Question searches

Measure Subject/Unit/Topic-wide searches, query plans, reconstruction cost and
deterministic ordering before adding indexes or redesigning queries.

### Measure preview image conversion and superseded render overlap

Measure realistic large/multi-region previews and rapid selection changes before
changing threading or serialisation.

## Curriculum mapping hardening

### Decide whether mapping invariants need database enforcement

Application writers enforce same Subject, different versions, same level and
allowed direction. Decide whether direct-SQL bypass risk justifies triggers or
other database enforcement.

### Mapping workflow extensions

Possible later work:

- bulk mapping import;
- assisted review of gaps;
- improved similarity scoring;
- optional AI-assisted suggestions;
- broader coverage reporting where justified.

Any assisted mechanism must still require explicit confirmation.

### Decide whether richer curriculum mapping relation metadata should be persisted

The standalone mapping work distinguishes direct/reworded, split, merged,
partial, removed and new content. Decide whether relation type/confidence/notes
should remain external review/audit metadata or become persisted application
data.

## UI / usability — later polish

Spacing, alignment, sizing, label wording and visual consistency should remain
separate from functional work unless they block workflow or create incorrect
data. Workflow-critical items discovered in real use can be promoted into a
sprint.

Possible assistance later:

- PDF Question-boundary suggestions;
- multipart/dependency suggestions;
- additional keyboard/efficiency improvements.

Suggestions remain non-authoritative until confirmed.

## Output / future sprint inputs

### Later printable output — preserve vector source content

For print-oriented PDF generation, evaluate direct source-PDF page/viewport
clipping rather than rasterising web assets. Preserve generated numbering,
solution alignment and source attribution.

## Future content sources

### Clipboard / image-attachment Questions

Support an eventual workflow such as:

```text
Windows Snipping Tool / copied image
        -> system clipboard
        -> Paste Image / Ctrl+V
        -> Question content attachment
```

Design requirements:

- Question content can be text plus zero or more images;
- optional drag/drop PNG/JPEG;
- choose SQLite BLOB versus application-managed file storage;
- define provenance metadata;
- include images in backup/restore;
- render consistently to HTML/PDF/SCORM;
- avoid separate mutually exclusive text/image Question hierarchies unless
  evidence requires it.

OCR is optional later work.

## Packaging / deployment

### Self-contained desktop packaging

Investigate `jpackage` once deployment becomes a priority.

Requirements include reproducible Maven packaging, bundled runtime/dependencies,
writable data outside installed application files and migration/backup safety.

### Shared faculty operation

Do not treat a live SQLite database on SharePoint/network sync as a safe
concurrently edited datastore.

Possible later models include local SQLite plus controlled import/export/merge
or an IT-supported central database.

## Testing / reliability

### Add SQLite-backed revision-export integration coverage

Origin: Sprint 05 merge-readiness review.

Add a temporary-SQLite end-to-end export test using fresh production
repository/service instances to cover persisted curriculum reconstruction,
confirmed mapping retrieval, ordered regions, Answer reconstruction,
SourceQuestion/shared-context reconstruction and complete export after reopen.

Real Chemistry acceptance has exercised the production path manually, so this
remains hardening rather than a current release blocker.

### Public API documentation

Javadoc/doclint has passed at earlier sprint closeouts. Continue reviewing
semantic API documentation where contracts change; this item concerns
documentation completeness rather than warning cleanup.

## Repository governance

### Protect `main` with required CI checks

Origin: CI hardening discussion during Sprint 09.

The CI workflow now runs on feature/refactor/chore branches and pull requests to
`main`, with separate non-UI, remaining-UI and workflow-UI matrix jobs.

After Sprint 09 is merged:

- configure a `main` branch ruleset/protection policy;
- require the intended successful CI checks before merge;
- prevent accidental direct changes that bypass the normal feature-branch
  workflow;
- keep the exact required-check names aligned with the workflow.

This is repository governance, not unfinished Sprint 09 product behaviour.

## Completed / not backlog

Do not re-add these as unimplemented work:

- Sprint 04 backup/restore/data safety;
- Sprint 05 deterministic revision corpus/static HTML foundation;
- Sprint 06 SCORM 1.2 generation and QLearn acceptance;
- Sprint 07 persisted SourceQuestion/shared-context semantics and preamble-aware
  capture;
- Sprint 08 curriculum authoring/lifecycle, mapping coverage, response-type and
  corpus-completion tooling;
- existing Question and Answer correction;
- syllabus-sensitive capture/classification;
- guarded transient selection ownership;
- resizable capture workspace;
- Descriptor-aware classification controls;
- multipart/shared-context HTML and SCORM presentation foundation;
- asynchronous Question/Answer saves and Answer-PDF transitions;
- Sprint 09 responsive Question/Answer controls;
- Sprint 09 natural/source Answer queue ordering;
- Sprint 09 selection-owner action enablement;
- Sprint 09 Question response-type radio buttons;
- Sprint 09 Imported Questions activation performance fix;
- Sprint 09 shared-preamble recapture;
- Sprint 09 known-PDF metadata reuse;
- Sprint 09 Exam metadata correction;
- Sprint 09 managed-PDF relocation when corrected provider/year changes;
- Sprint 09 legacy single-Question -> multipart split workflow;
- Sprint 09 Search Questions `All Questions` versus `Current syllabus`;
- Sprint 09 Corpus Audit deterministic source order;
- Sprint 09 numeric Corpus Audit year ordering;
- Sprint 09 removal of redundant Capture New Questions menu navigation;
- Sprint 09 Search dialog resize persistence;
- Sprint 09 MCQ Answer-PDF visibility/reuse;
- Sprint 09 UI package refactoring and CI workflow hardening.

## Backlog rules

- Include an origin for material user-observed items.
- Preserve completed sprint documents as history.
- Keep every known user-observed requirement represented until implemented,
  deliberately rejected or superseded.
- Remove completed work from future/scheduled sections at sprint closeout.
- Keep performance work measurement-driven.
- Prefer behaviour-focused tests over coverage percentages.
- Do not implement unresolved ownership/semantics by inventing placeholder data.
- Do not promote chat prototypes or standalone mapping artefacts to application
  implementation without repository/persistence evidence.
