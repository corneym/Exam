# Exam Question Bank Backlog

> Authoritative scheduled/deferred-work list at 17 September 2026.
>
> Completed sprint documents remain historical records. Work that is scheduled
> but not yet implemented remains visible here with its sprint marker until
> closeout.
>
> Sprint 08 is complete and merged. Sprint 09 is planned in
> `sprint-09-capture-workflow-and-corpus-correction.md`.

## Scheduled — Sprint 09: Capture Workflow and Corpus Correction

The following items come directly from sustained use of the application after
Sprint 08. They remain backlog items until implemented and verified.

### Answer-pane responsive layout and status placement

Origin: application-use review, 17 September 2026; extends the earlier Sprint 07
Answer-pane layout backlog item.

Problems observed:

- Answer-pane buttons can lose their text when the left workspace pane is made
  narrower with the split-pane divider;
- after selecting an Answer region and pressing Add Region, the Answer pane can
  extend vertically well below the Remove button with excessive blank space;
- `Regions: n / Page n` status text competes with button width and contributes
  to control compression.

Desired behaviour:

- button labels remain readable across the supported left-pane width;
- adding an Answer region does not create unnecessary vertical growth;
- move region/page status to the row above the buttons, right aligned, or use an
  equivalent layout that cannot consume the button row;
- Answer-region previews use only the space required.

### Answer-capture queue natural ordering

Origin: application-use review, 17 September 2026.

Questions awaiting Answer capture should appear in source/natural order rather
than database insertion/capture-time order.

Preferred ordering:

```text
exam provider / authority
-> year
-> booklet
-> natural Question number / part
```

Natural Question ordering must handle text codes such as `3`, `3a`, `3b`, `10`
without lexicographic anomalies.

### Capture ownership reflected in Question controls

Origin: application-use review, 17 September 2026.

When the Answer pane owns the active capture interaction, Question-pane Add
Region and Clear controls should be disabled so the visible UI reflects the same
selection ownership enforced by the underlying capture state.

### Region-action enablement

Origin: application-use review, 17 September 2026.

Review Question and Answer Add Region / Clear controls so actions that require a
current selection are disabled until a selection exists. Enablement should
follow actual pending-selection ownership rather than merely pane visibility.

### Question-pane responsive labels and control sizing

Origin: application-use review, 17 September 2026.

Problems observed:

- Question-pane entry-field labels lose letters or collapse to `...` when the
  left pane is narrowed;
- the Question number text field is wider than necessary.

Desired behaviour:

- important field labels remain readable at supported pane widths;
- Question number entry uses a compact width suitable for normal exam codes;
- layout should degrade predictably rather than truncating labels into ambiguity.

### Question response type: radio buttons in capture UI

Origin: application-use review, 17 September 2026.

Replace the Question capture Written Response / Multiple Choice combo box with a
small explicit radio-button choice. Persisted response-type semantics remain
unchanged (`MULTIPLE_CHOICE`, `WRITTEN_RESPONSE`, `UNKNOWN`); this is a capture
UI improvement, not a model redesign.

### Edit Question Metadata dialog label layout

Origin: application-use review, 17 September 2026.

Labels on the left of Edit Question Metadata can be compressed to `...`.
Rework the dialog layout so field names remain readable at the normal dialog
size and reasonable resizing.

### Imported Questions mode performance

Origin: application-use review, 17 September 2026.

Pressing Imported Questions can take a noticeable time before the selected
imported-question controls appear.

Investigate before optimising. Measure the work done when entering imported
capture, including source-question backfill, shared-context reconciliation,
repository refresh/reconstruction and UI population. Remove or relocate repeated
work where safe rather than merely adding a progress indicator around avoidable
synchronous work.

Long-running persistence/reconstruction must not regress the JavaFX
responsiveness rules established in earlier sprints.

### Replace or recapture an existing shared preamble

Origin: application-use review, 17 September 2026.

Provide a supported workflow to replace the source image/region(s) of an already
captured `SharedQuestionContext`.

Requirements:

- the replacement edits the shared context deliberately rather than creating
  unrelated per-part copies;
- all Questions legitimately linked to that context continue to reference one
  consistent shared context after successful replacement;
- cancellation/failure leaves the previous context intact;
- source-question consistency rules remain enforced;
- ordered multi-region persistence must not be weakened even if the initial UI
  replacement workflow remains single-page;
- add persistence/reload and presentation regressions.

This item is distinct from the separate multi-page automatic preamble-capture
backlog item.

### Import Exam dialog responsive labels

Origin: application-use review, 17 September 2026.

The left-side labels in the Import Exam dialog can be too narrow and end in
`...`. Rework the layout so exam metadata fields are identifiable without
requiring an unnecessarily wide dialog.

### Reuse metadata when opening a PDF already known to the database

Origin: application-use review, 17 September 2026.

When an exam PDF selected/opened through the import workflow is already known to
the application, populate the associated stored exam/booklet metadata instead
of requiring re-entry.

Design must use authoritative persisted document/exam relationships and existing
identity/conflict rules. Do not silently guess a match merely from a similar
filename.

### Supported exam-level metadata correction

Origin: capture/import use; expanded by application-use review,
17 September 2026.

Provide a supported correction workflow for persisted Exam metadata without
editing every linked Question individually.

Concrete current case:

- most legacy exams display as provider `QCAA`, year `2021`, `2022`, etc.;
- one legacy import used a tab/name equivalent to `2022 QCAA`, producing display
  such as `2022 QCAA 2022`;
- the provider/authority and year need to be represented correctly once at Exam
  level while preserving linked booklets, Questions, Answers and source
  documents.

Initial known requirements also include correction of an inaccurate exam name.

The design must define which Exam/ExamProvider fields are editable and how
natural-identity conflicts are handled transactionally.

### Dedicated legacy Question split workflow

Origin: application-use review, 17 September 2026.

Add a deliberate workflow to convert a legacy imported single Question such as:

```text
3
```

into multipart Questions such as:

```text
3a
3b
```

with source identity `3` and, where required, one shared preamble/context.

Requirements:

- retain/reuse the existing Question row as one part where safe rather than
  needlessly replacing persistent identity;
- create the additional Question part(s) transactionally;
- create/reuse the correct `SourceQuestion` identity;
- permit separate marks, classification, response type and Question regions for
  each part;
- guide capture or selection of a shared preamble and attach it consistently to
  the multipart group;
- preserve existing Answer data only where its ownership is unambiguous;
- reject duplicate destination question codes or incompatible existing source
  groups;
- cancellation/failure must not leave a half-split source group;
- add reload, corpus-audit and revision-presentation regressions.

### Search Questions: all-bank versus syllabus filtering

Origin: application-use review, 17 September 2026.

Search Questions should support intentionally displaying all stored Questions,
not only a current-syllabus scope. Provide an explicit scope choice such as:

```text
All Questions
Current syllabus / selected curriculum scope
```

Preserve the existing current-curriculum retrieval semantics when syllabus scope
is selected. An all-bank mode must not pretend that historical Questions are
current-applicable merely because they are displayed.

### Corpus Audit deterministic source ordering

Origin: application-use review, 17 September 2026.

The Corpus Audit work list currently reflects capture/insertion ordering too
strongly. Sort displayed Questions by:

```text
provider / authority
-> year
-> booklet
-> natural Question number / part
```

Filters and summary totals must remain unchanged by display ordering.

### Review redundant `Questions -> Capture New Questions` menu action

Origin: application-use review, 17 September 2026.

The `Questions -> Capture New Questions` command appears redundant with the
visible New Questions capture mode. Decide whether it provides a useful
navigation/reset action. Remove it if it adds no distinct safe workflow;
otherwise make the distinct behaviour clear.

## Revision HTML / presentation refinements — future output sprint input

These items were identified in the same application-use review but are kept out
of Sprint 09 so capture/correction work remains coherent.

### Select revision-output curriculum grouping depth

Origin: application-use review, 17 September 2026.

Where Descriptors are present, allow the export to choose presentation grouping
at Descriptor or Subtopic level.

If Subtopic grouping is chosen:

- Questions classified to Descriptors are flattened into the parent Subtopic;
- no separate Descriptor section is emitted;
- ordering should still respect Descriptor order, then deterministic Question
  order, so flattening does not become arbitrary.

The persisted curriculum classification is not rewritten by this presentation
choice.

### Multipart provenance presentation

Origin: application-use review, 17 September 2026.

For multipart output with one shared preamble, repeated decorations such as
`Source part 26a` and `Source part 26b` are unnecessary.

Prefer a single source/original-classification provenance block after all
Question regions in the multipart presentation, while retaining enough
information to trace the source parts correctly.

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
semantics so the revision presentation model has one coherent rule rather than
several ad-hoc sorts.

## Normal priority deferred work

### Managed document content hashing and duplicate detection

Origin: managed-document storage review.

Consider SHA-256 content hashing for managed documents so identical files can be
recognised independently of filename/storage path.

Current behaviour:

- exam and answer PDFs use byte comparison when a destination filename already
  exists;
- syllabus/curriculum PDFs use unique managed filenames and are not deduplicated
  by content;
- no document hash is persisted in SQLite.

Potential use:

- detect duplicate content even when filenames differ;
- safely reuse physical content where ownership/path semantics permit;
- assist backup/integrity verification.

Do not use SHA-1 for new work.

### Curriculum capture refinements

Origin: Sprint 08 curriculum authoring.

Deferred authoring assistance:

- PDF region-based text extraction;
- detection/highlighting of embedded images and diagrams;
- manual exclusion regions for maths/figures;
- Markdown/LaTeX maths editing and rendered preview;
- maths-authoring helper buttons and eventual equation-recognition assistance;
- further keyboard/efficiency improvements.

Curriculum hierarchy remains expert-authored; assistance must not infer
authoritative structure.

### Multi-page automatic shared-preamble capture

Origin: Sprint 07 preamble-aware Question capture.

Persistence already supports multiple ordered `SharedQuestionContextRegion`
records. The automatic imported-question preamble workflow currently completes
after one accepted source region.

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

### Decide whether `Question` requires multiple original classifications

Origin: Batching Exam Questions / early metadata design.

Evidence includes a real classification exercise where one Question reasonably
matched several descriptors. Current `Question` stores one best-fit Subtopic or
Descriptor.

Decision required:

- affirm one-best-fit as permanent policy; or
- implement multiple original classifications.

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

without forcing out-of-scope Questions into the bank.

### Support Question-level applicability exceptions after curriculum mapping

Origin: historical-to-current curriculum mapping / Question review.

A curriculum mapping can remain valid while one historical Question tests only
content that did not carry forward.

Required future behaviour:

- preserve original historical classification and mapping;
- allow explicit exclusion of a mapped-forward Question from a target current
  node/context;
- show a clear viewer marker;
- make retrieval/revision/export respect the exclusion;
- distinguish applicable, not applicable and, if required, not yet reviewed.

Prefer node-specific exceptions where one historical node maps to several target
nodes.

### Strengthen retrieval-domain invariants

Origin: Question Retrieval Sprint 03 / final review.

Review `QuestionApplicabilityMatch` and `QuestionRetrievalResult` rules,
including:

- compatible original/current curriculum levels;
- same Subject;
- only Subtopic/Descriptor applicability nodes;
- duplicate handling;
- rejection of historical/Unit/Topic applicability where inappropriate.

### Extend retrieval integration coverage

Origin: Question Retrieval Sprint 03 / final review.

Add realistic SQLite integration tests for:

- Subject-wide retrieval across multiple Units;
- one historical Subtopic mapping to multiple current Subtopics;
- strict same-Subject isolation;
- empty Subtopics;
- Topic-level Descriptor structures;
- database close/reopen reconstruction.

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

Test representative files for:

- multiple Subjects/providers;
- unexpected formatting;
- blank or formula-driven cells;
- duplicate conflicting rows;
- unusual Question codes;
- missing MCQ answers;
- shared/preamble evidence spanning rows;
- renamed/relocated source PDFs.

Do not infer multipart/shared-context relationships merely because workbook data
appears to contain a pattern.

### Decide whether legacy image-snip import/support is still required

Origin: Batching Exam Questions.

Determine whether irreplaceable legacy Questions/Answers exist only as image
snips and therefore need one-time attachment import or managed-file
compatibility. Do not convert all legacy snips merely for architectural purity
when original PDFs remain available.

### Decide whether richer curriculum mapping relation metadata should be persisted

Origin: Curriculum Descriptor Mapping.

The standalone mapping work distinguishes cases such as direct/reworded, split,
merged, partial, removed and new content. Current retrieval mainly needs
confirmed directional pairs.

Decide whether relation type/confidence/notes should remain external review/audit
metadata or become persisted application data.

## Performance

### Benchmark and optimise broad Question searches

Origin: Question Retrieval Sprint 03 / final review.

Measure before optimising:

- Subject/Unit/Topic-wide searches;
- SQLite query plans;
- requested-node/question cross-product behaviour;
- per-Question reconstruction/N+1 cost;
- deterministic ordering;
- mapped/direct equivalence.

Add indexes only when measurements justify them.

### Measure preview image conversion performance

Origin: Question Retrieval Sprint 03 / final review.

Measure large and multi-region previews. Move/restructure conversion work only if
visible FX-thread pauses are demonstrated.

### Measure overlap between superseded preview renders

Origin: Sprint 06 merge-readiness review.

Generation guards prevent stale previews reaching the UI, but superseded PDFBox
renders may overlap in the background. Measure realistic rapid selection changes
before deciding whether bounded serialisation is required.

## Curriculum mapping hardening

### Decide whether mapping invariants need database enforcement

Origin: curriculum mapping/retrieval hardening review.

Application writers enforce same Subject, different versions, same level and
allowed direction. Decide whether direct-SQL bypass risk justifies triggers or
other database enforcement.

### Mapping workflow extensions

Origin: Curriculum Descriptor Mapping.

Possible later work:

- bulk mapping import;
- assisted review of gaps;
- improved similarity scoring;
- optional AI-assisted suggestions;
- broader coverage reporting where justified.

Any assisted mechanism must still require explicit confirmation.

## UI / usability — later polish

### Maintain a deliberate UI polish backlog

Origin: Exam Builder Design Slice.

Spacing, alignment, sizing, label wording and visual consistency should remain
separate from functional work unless they block workflow or create incorrect
data. Workflow-critical items identified in real use may be promoted into a
sprint, as with Sprint 09.

### Consider assisted PDF Question-boundary detection

Origin: Batching Exam Questions.

Possible workflow:

- detect headings such as `QUESTION 12` using PDF text positions;
- estimate crop boundaries;
- present a suggested region;
- require user correction/confirmation.

Do not assume text extraction is reliable enough to replace visual source
content.

### Consider assisted part/dependency recognition

Origin: Batching Exam Questions.

Future tooling may suggest multipart/dependency relationships from Question codes
or phrases such as “using your answer to part a”. Suggestions remain
non-authoritative until confirmed.

## Output / future sprint inputs

### Later printable output — preserve vector source content

Origin: Batching Exam Questions / legacy output behaviour.

For print-oriented PDF generation, evaluate direct source-PDF page/viewport
clipping through LaTeX `graphicx` or equivalent vector-preserving output rather
than rasterising web assets. Preserve generated numbering, solution alignment and
source attribution.

## Future content sources

### Clipboard / image-attachment Questions

Origin: future image-snips discussion.

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

Origin: Batching Exam Questions.

Investigate `jpackage` once deployment becomes a priority.

Requirements:

- Maven-driven reproducible packaging;
- runtime/dependencies bundled appropriately;
- writable SQLite/data root outside installed application files;
- upgrade/migration/backup safety.

### Shared faculty operation

Origin: Batching Exam Questions.

Do not treat a live SQLite database on SharePoint/network sync as a safe
concurrently edited datastore.

Possible later models:

- local SQLite plus controlled import/export/merge;
- SharePoint for backups, exports and distributed source files;
- IT-supported central database when simultaneous multi-user editing becomes
  necessary;
- SharePoint/Graph integration if justified.

## Testing / reliability

### Add SQLite-backed revision-export integration coverage

Origin: Sprint 05 merge-readiness review.

Add a temporary-SQLite end-to-end export test using fresh production
repository/service instances to cover:

- persisted current/historical curriculum reconstruction;
- confirmed mapping retrieval;
- ordered Question and Answer regions;
- Answer reconstruction;
- SourceQuestion/shared-context reconstruction;
- complete revision export after database reopen.

Real Chemistry acceptance has exercised the production path manually, so this
remains hardening rather than a release blocker.

## Technical debt

### Public API documentation

Origin: Question Retrieval Sprint 03 / final review.

Review semantic API documentation where still required for:

- single-use/disposal behaviour of Question Search UI;
- Subject-search no-current/multiple-current semantics;
- hierarchy expansion;
- historical applicability rejection;
- strengthened retrieval invariants adopted later.

Javadoc currently passes doclint; this item concerns semantic completeness, not
warning cleanup.

## Completed / not backlog

Do not re-add these as unimplemented work:

- Sprint 04 backup/restore/data safety;
- Sprint 05 deterministic revision corpus/static HTML foundation;
- Sprint 06 SCORM 1.2 generation and QLearn acceptance;
- Sprint 07 persisted SourceQuestion/shared-context semantics and preamble-aware
  capture;
- existing Question and Answer correction;
- syllabus-sensitive capture/classification;
- guarded transient selection ownership;
- resizable capture workspace;
- Descriptor-aware classification controls;
- multipart/shared-context HTML and SCORM presentation foundation;
- asynchronous Question/Answer saves and Answer-PDF transition;
- registered Answer-PDF reuse;
- Sprint 08 curriculum authoring and lifecycle;
- Sprint 08 mapping coverage;
- Sprint 08 legacy metadata correction;
- persisted Question response type and mixed-response booklet support;
- response-type-aware corpus completeness;
- corpus audit filters/work-queue foundation;
- asynchronous Question Search closeout regressions;
- Full-width pending-selection clearing for Question, SharedQuestionContext and
  Answer capture;
- destination shared-context integrity checks for multipart metadata moves;
- finalisation failure/retry regression coverage.

## Backlog rules

- Include an origin for each item.
- Preserve completed sprint documents as history.
- Keep every known user-observed requirement represented until implemented,
  deliberately rejected or superseded.
- When an item is scheduled into a sprint, mark it as scheduled and reference
  the sprint design; do not silently remove it before closeout.
- At sprint closeout, remove or move implemented items to Completed/Not Backlog
  after the canonical sprint record and current status have been updated.
- Keep performance work measurement-driven.
- Prefer behaviour-focused tests over coverage percentages.
- Do not implement unresolved ownership/semantics by inventing placeholder data.
- Do not promote chat prototypes or standalone mapping artefacts to application
  implementation without repository/persistence evidence.
