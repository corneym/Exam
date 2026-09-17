# Exam Question Bank Backlog

> Authoritative deferred-work list at 16 September 2026.
>
> Completed sprint documents remain historical records. Items intentionally
> deferred from completed work belong here until scheduled into a future sprint.
>
> Sprint 07 is complete and merged. Work scheduled into Sprint 08 is governed by
> `sprint-08-Curriculum-and-Corpus-Completion.md`; implemented Sprint 08 work
> should be removed from this backlog as it is completed.

## Normal Priority

### Managed document content hashing and duplicate detection

Consider adding content hashing for managed documents so identical files can be recognised independently of filename or storage path.

Current behaviour:
- exam and answer PDFs use `Files.mismatch(...)` to detect byte-identical files when the destination filename already exists;
- syllabus/curriculum PDFs are stored using unique managed filenames and are not deduplicated by content;
- no document hash is currently persisted in SQLite.

Proposed improvement:
- compute a SHA-256 hash when a managed document is imported;
- persist the hash alongside document metadata;
- use the hash to detect duplicate content even when filenames differ;
- reuse an existing managed file where appropriate instead of storing another identical copy;
- retain existing path and ownership semantics so different logical document records may still refer to the same physical content if that is safe;
- consider using the persisted hash for backup/integrity verification as well as deduplication.

Do not use SHA-1 for new work; use SHA-256 or a stronger contemporary hash.

This is a storage-integrity/deduplication improvement, not required for Sprint 08 closure.

### Curriculum capture
- PDF region-based text extraction for curriculum authoring.
- Detection/highlighting of embedded images and diagrams.
- Manual exclusion regions for maths/figures.
- Markdown/LaTeX maths editing and rendered preview.
- Maths-authoring helper buttons and eventual equation-recognition assistance.
- Further keyboard/efficiency improvements to curriculum capture.

### Answer pane layout annoyances

Origin: Sprint 07 capture use.

Remaining UI issues:

- adding an answer region can introduce excessive blank vertical space;
- the `Regions: n` / selection-status text can cause adjacent buttons to shrink
  and lose their text;
- the page number is not useful enough to justify destabilising the controls.

Desired behaviour:

- answer controls remain stable in size;
- buttons retain their text;
- status text must not force unnecessary layout growth;
- answer-region previews use only the space required.

### Multi-page automatic shared-preamble capture

Origin: Sprint 07 preamble-aware question capture.

Sprint 07 supports shared preamble/context capture and reuse, including large
same-page regions using anchored selection.

The automatic imported-question preamble workflow currently completes after one
accepted source region.

Future work should support a shared preamble spanning multiple PDF pages by:

- allowing multiple ordered `SharedQuestionContextRegion` records to be
  captured;
- allowing page navigation between accepted preamble regions;
- providing an explicit way to finish preamble capture before ordinary Question
  region capture begins;
- preserving the existing single-page workflow;
- retaining shared-context reuse for later parts of the same SourceQuestion;
- keeping regions in PDF-page order;
- adding regression coverage for multi-page preambles.

The persistence model already supports multiple ordered shared-context regions;
the deferred work is primarily the automatic capture workflow.

### Edit exam-specific metadata

Origin: capture/import use.

Provide a supported correction workflow for persisted exam metadata.

Initial known requirement:

- an incorrect exam name must be editable without re-importing or corrupting
  linked booklets/questions.

### MCQ explanation capture

Origin: Sprint 07 answer-capture review.

Future behaviour:

- retain A/B/C/D as the primary answer;
- optionally capture one or more answer-PDF regions as explanation;
- render the correct option plus explanation in revision output.

### Decide whether `Question` requires multiple original classifications

Origin: Batching Exam Questions / early metadata design.

Evidence:

- the 2021 Neap classification exercise identified a question reasonably mapped
  to three descriptors;
- the early domain design proposed many-to-many
  `Question <-> SyllabusDescriptor`;
- the current `Question` model stores one best-fit Subtopic/Descriptor
  classification.

Decision required:

- explicitly affirm one-best-fit as permanent policy; or
- implement multiple original classifications.

If multiple classifications are required, review:

- schema/repositories;
- capture/import UI;
- applicability derivation;
- retrieval duplicate semantics;
- export placement;
- provenance display;
- tests.

Do not confuse multiple original classifications with one historical node
mapping to several current nodes.

### Decide whether source-question processing needs an explicit out-of-scope disposition

Origin: Batching Exam Questions prototype.

During the Neap 2021 classification exercise, several questions were
deliberately marked out of scope.

A later exam-ingestion/audit workflow may need to distinguish:

```text
not yet reviewed
captured/classified
explicitly out of scope
```

without forcing out-of-scope questions into the bank.

### Strengthen retrieval-domain invariants

Origin: Question Retrieval Sprint v3 / final review.

Review `QuestionApplicabilityMatch` and `QuestionRetrievalResult` rules,
including:

- compatible original/current curriculum levels;
- same Subject;
- only Subtopic/Descriptor applicability nodes;
- duplicate handling;
- rejection of historical/Unit/Topic applicability where inappropriate.

### Extend retrieval integration coverage

Origin: Question Retrieval Sprint v3 / final review.

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
- questions still needing regions;
- answers supplied/absent/unknown;
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
- unusual question codes;
- missing MCQ answers;
- shared/preamble evidence spanning rows;
- renamed/relocated source PDFs.

Do not infer multipart/shared-context relationships merely because workbook data
appears to contain a pattern.

### Decide whether legacy image-snip import/support is still required

Origin: Batching Exam Questions.

The legacy application has many named question/answer snips. The current
redesign prefers original PDFs plus regions.

Decide whether irreplaceable legacy items exist only as image snips and
therefore need:

- one-time attachment import;
- managed-file compatibility;
- or no special support because original PDFs are available.

Do not convert all legacy snips merely for architectural purity.

### Decide whether richer curriculum mapping relation metadata should be persisted

Origin: Curriculum Descriptor Mapping.

The standalone mapping work distinguishes conceptual cases such as
direct/reworded, split, consolidated/merged, partial, removed and new content.

Current retrieval mainly needs confirmed directional pairs.

Decide whether relation type/confidence/notes should remain external
review/audit metadata or become persisted application data for reporting and
assisted review.

### Support question-level applicability exceptions after curriculum mapping

Origin: historical-to-current curriculum mapping / question review.

A historical curriculum descriptor may contain several concepts. A confirmed
mapping to a newer descriptor may carry forward only some of those concepts.

Therefore, a Question classified to the historical descriptor must not be
assumed to be applicable to the newer syllabus merely because its classification
has a confirmed forward mapping.

Example:

- historical Descriptor A contains concepts X, Y and Z;
- current Descriptor B carries forward X and Y but not Z;
- Question Q is classified to Descriptor A but tests only Z;
- A -> B remains a valid curriculum mapping;
- Question Q must nevertheless be marked not applicable to the newer syllabus.

Required future behaviour:

- support an explicit question-level applicability review/override;
- preserve the original historical classification and curriculum mapping;
- allow a reviewer to mark a mapped-forward Question as not applicable to a
  target syllabus/current curriculum context;
- show a clear marker in the question viewer when such an exception exists;
- do not silently rewrite or remove the underlying curriculum mapping;
- retrieval and revision/export workflows must respect the confirmed exclusion;
- retain enough information to distinguish applicable, not applicable and, where
  required, not yet reviewed.

The applicability exception should remain separate from curriculum mapping
because the mapping describes a relationship between curriculum concepts,
whereas the exception describes whether a particular Question tests the
carried-forward content.

Design must determine whether the exception is recorded against:

- the target syllabus version as a whole; or
- a specific target curriculum node when one historical node maps to several
  current nodes.

Prefer node-specific applicability where a Question may remain valid for one
mapped target but not another.

## Output / Future Sprint Inputs

### Later printable output — preserve vector source content

Origin: Batching Exam Questions / legacy output behaviour.

For print-oriented PDF generation, evaluate direct source-PDF page/viewport
clipping through LaTeX `graphicx` or equivalent vector-preserving output rather
than rasterising web assets.

Preserve generated numbering and original source attribution. Question and
solution numbering should stay aligned.

## Performance

### Benchmark and optimise broad question searches

Origin: Question Retrieval Sprint v3 / final review.

Measure before optimising:

- Subject/Unit/Topic-wide searches;
- SQLite query plans;
- current requested-node/question cross-product behaviour;
- per-question reconstruction/N+1 cost;
- deterministic ordering;
- mapped/direct equivalence.

Add indexes only when measurements justify them.

### Measure preview image conversion performance

Origin: Question Retrieval Sprint v3 / final review.

Measure large and multi-region previews. Investigate moving or restructuring
image conversion only if visible FX-thread pauses are demonstrated.

### Measure overlap between superseded preview renders

Origin: Sprint 06 merge-readiness review.

Question preview cancellation deliberately does not interrupt PDFBox rendering
because interruption can close a channel while PDFBox is still reading it.

The generation guard prevents a superseded preview from reaching the UI, but a
new preview may begin before the superseded render has finished in the
background.

Measure realistic rapid selection changes before deciding whether preview
rendering needs serialisation or another bounded-work mechanism.

## Curriculum Mapping Hardening

### Decide whether mapping invariants need database enforcement

Origin: Curriculum mapping/retrieval hardening review.

Application writers enforce same Subject, different versions, same level and
allowed direction.

Decide whether direct SQL bypass risk justifies triggers or other database
enforcement.

### Mapping workflow extensions

Origin: Curriculum Descriptor Mapping.

Possible later work:

- bulk mapping import;
- assisted review of gaps;
- improved similarity scoring;
- optional AI-assisted suggestions;
- whole-version coverage reporting.

Any assisted mechanism must still require explicit confirmation.

## UI / Usability

### Maintain a deliberate UI polish backlog

Origin: Exam Builder Design Slice.

Spacing, alignment, sizing, label wording and visual consistency should remain
separate from functional work unless they block workflow or create incorrect
data.

Workflow-critical Sprint 07 UI issues were handled within Sprint 07. Remaining
cosmetic and convenience changes belong here.

### Consider assisted PDF question-boundary detection

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

Now that persisted source-question identity exists, future tooling may suggest
part relationships from question codes or phrases such as “using your answer to
part a”.

Suggestions must remain non-authoritative until confirmed.

## Future Content Sources

### Clipboard / image-attachment questions

Origin: future image-snips discussion.

Support an eventual workflow such as:

```text
Windows Snipping Tool / copied image
        -> system clipboard
        -> Paste Image / Ctrl+V
        -> question content attachment
```

Design requirements:

- question content can be text plus zero or more images;
- optional drag/drop PNG/JPEG;
- choose SQLite BLOB versus application-managed file storage;
- define provenance metadata;
- include images in backup/restore;
- render consistently to HTML/PDF/SCORM;
- avoid separate mutually exclusive “text question” and “image question”
  hierarchies unless evidence requires it.

OCR is optional later work.

## Packaging / Deployment

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

## Testing / Reliability

### Add SQLite-backed revision export integration coverage

Origin: Sprint 05 merge-readiness review.

The revision-export integration test exercises complete PDF-region-to-HTML
generation but uses an in-memory curriculum/retrieval fixture.

Add a temporary-SQLite end-to-end export test using fresh production
repository/service instances to cover:

- persisted current/historical curriculum reconstruction;
- confirmed mapping retrieval;
- ordered question and answer regions;
- answer reconstruction;
- source-question/shared-context reconstruction;
- complete revision export after database reopen.

Real Chemistry acceptance has exercised the production path manually, so this
remains hardening rather than a release blocker.

## Technical Debt

### Public API documentation

Origin: Question Retrieval Sprint v3 / final review.

Review and document where still required:

- single-use/disposal behaviour of question search UI;
- Subject-search no-current/multiple-current semantics;
- hierarchy-expansion behaviour;
- historical applicability rejection;
- strengthened retrieval invariants adopted in later work.

The current Javadoc build passes doclint; this item concerns semantic API
documentation rather than build cleanliness.

## Completed / Not Backlog

Do not re-add these as unimplemented work:

- Sprint 04 backup/restore/data safety;
- Sprint 05 deterministic revision corpus;
- static hierarchical revision HTML/assets;
- Subject/Unit/Topic/Subtopic revision navigation;
- empty-Descriptor suppression;
- static answer disclosure;
- revision-export staging and validation;
- JavaFX revision-export workflow and progress reporting;
- Sprint 06 SCORM 1.2 package generation;
- successful QLearn import/launch acceptance;
- current SCORM manifest/schema/ZIP profile;
- migrations through schema v4;
- syllabus selection;
- mapping persistence/review;
- curriculum-import idempotency;
- package reorganisation;
- legacy metadata import foundation;
- question/answer persistence;
- PDF-region capture;
- question retrieval Sprint 03;
- async hierarchy loading;
- stale-result protection;
- stored-question preview;
- Search Questions preview reconstruction of linked shared context before the
  selected Question's ordinary regions;
- arbitrary-PDF viewer mode;
- one-root managed-data configuration;
- persisted `SourceQuestion` identity;
- persisted reusable `SharedQuestionContext`;
- ordered shared-context regions;
- explicit separation of multipart identity and shared context;
- Sprint 07 preamble-aware Question capture and reuse;
- same-page anchored large-region preamble capture;
- existing Question correction;
- existing Answer correction;
- syllabus-sensitive capture/classification;
- guarded transient region selections and selection ownership;
- resizable capture workspace;
- Descriptor-aware classification controls;
- multipart grouping semantics in revision output;
- shared-context/multipart HTML output;
- shared-context/multipart SCORM output;
- background Question save persistence/refresh;
- background Answer save persistence;
- asynchronous post-save Answer PDF transition;
- automatic reuse of registered Answer PDFs during Answer capture;
- hiding Answer PDF chooser controls when the selected Question's answer PDF is
  already known;
- legacy-import opportunity to register an answer/marking-guide PDF when all
  required question booklets already exist.
- Save-button state recalculation after clearing a pending Question selection.
- JavaFX/TestFX suite isolation with separate non-UI and headless UI test execution.


## Backlog Rules

- Include an origin for each item.
- Preserve completed sprint documents as history.
- When an item is scheduled into a sprint, reference it there and remove it from
  the active backlog.
- Keep performance work measurement-driven.
- Prefer behaviour-focused tests over coverage percentages.
- Do not implement unresolved ownership/semantics by inventing placeholder
  data.
- Do not promote chat prototypes or standalone mapping artefacts to application
  implementation without repository/persistence evidence.
