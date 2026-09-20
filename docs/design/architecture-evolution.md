# Architecture Evolution

> Updated 20 September 2026.  
> This document records why major design directions changed. It distinguishes
> implemented architecture from Sprint 10 decisions that are planned but not yet
> implemented.

## 1. Legacy workbook/filesystem application -> relational Question bank

### Earlier

The predecessor application used an Excel workbook plus named image snips as the
de facto database and generated LaTeX/PDF, hierarchical HTML and SCORM-style
resources.

### Current

**IMPLEMENTED / CURRENT**

SQLite is the live datastore. Excel is import/exchange only. Questions retain
source provenance and curriculum-version relationships independently of any one
output generator.

Reason: durable identity, referential integrity, historical curriculum mapping,
correction workflows and retrieval could not safely remain implicit in workbook
rows and filenames.

## 2. Image snips -> authoritative PDFs plus normalised regions

### Earlier

Named PNG/JPEG snippets were canonical content.

### Current

**IMPLEMENTED / CURRENT**

```text
managed PDF + owning document/booklet + page + normalised rectangle(s)
```

Generated images are derived artefacts. This preserves source fidelity and keeps
future vector-oriented print output possible.

## 3. Machine-specific paths -> managed portable data root

**IMPLEMENTED / CURRENT**

One `data.root` derives managed PDF, curriculum and SQLite locations. Persisted
source-document paths are portable relative paths beneath that root.

Sprint 09 extended this boundary so authoritative Exam provider/year correction
also relocates managed PDFs and updates persisted paths transactionally.

## 4. Pixel coordinates -> normalised source coordinates

**IMPLEMENTED / CURRENT**

Question, Answer and shared-context regions use normalised proportional
coordinates and one-based domain page numbers. Rendering DPI is therefore an
output concern rather than persisted content semantics.

## 5. Direct PDFBox use -> PDF service/session boundary

**IMPLEMENTED / CURRENT**

`PdfSession`, `QuestionExtractor` and PDF-store boundaries contain PDF lifecycle
and rendering concerns so JavaFX panes work with images/regions rather than raw
PDFBox ownership.

## 6. In-memory prototypes -> SQLite repositories/services

Early smoke-test repositories proved vertical slices. Runtime persistence is now
SQLite with migrations, transactions and reconstruction tests. In-memory
repositories remain useful for focused tests rather than runtime authority.

## 7. Current classification overwrite -> historical provenance plus derived applicability

### Rejected direction

Reclassifying historical Questions directly into the current syllabus would lose
what the source metadata originally meant.

### Current

**IMPLEMENTED / CURRENT**

Questions retain one original Subtopic/Descriptor classification. Confirmed
historical -> current mappings derive present applicability. Mapping may be
one-to-many; only reviewed/confirmed relationships affect retrieval.

## 8. Preamble flag -> separate multipart identity and shared source material

### Earlier

Legacy metadata carried a Boolean preamble hint and an earlier application had a
multipart concept. Import could not safely infer durable relationships from that
alone.

### Current

**IMPLEMENTED / CURRENT**

```text
SourceQuestion          = original multipart identity
SharedQuestionContext   = reusable source material
```

They are intentionally independent. One context may be linked to several
Questions without making those Questions one multipart source question.

Multipart grouping occurs only after final curriculum placement and only from
persisted SourceQuestion identity. Shared context alone never groups Questions.

Sprint 10 will expose general capture/reuse of SharedQuestionContext for otherwise
independent Questions, including MCQs and Questions in different curriculum
buckets. This is a UI/workflow extension of existing domain semantics, not a new
persistence model.

## 9. One region -> ordered multi-region content

**IMPLEMENTED / CURRENT**

Question, Answer and SharedQuestionContext content can use ordered region lists.
Ordinary multi-region Questions remain distinct from shared context and
multipart identity.

## 10. Booklet-level response assumptions -> Question response type

**IMPLEMENTED / CURRENT**

`QuestionResponseType` is persisted on each Question. Mixed-response booklets are
supported and Answer completeness depends on Question type rather than booklet
name.

Sprint 10 will add capture defaults from strong metadata cues. Those defaults do
not become hard domain constraints and do not override persisted response type
while editing/importing an existing Question.

## 11. Blocking UI operations -> asynchronous persistence and stale-request protection

**IMPLEMENTED / CURRENT**

Search, Question/Answer persistence and relevant PDF transitions use background
work with JavaFX completion handling. Stale/lifecycle protection prevents older
work from overwriting newer UI state.

## 12. Shared PDF rectangle -> explicit capture-selection ownership

### Sprint 07

**IMPLEMENTED / CURRENT**

Transient ownership distinguishes `QUESTION`, `SHARED_CONTEXT` and `ANSWER` so
one workflow does not silently clear another workflow's pending selection.

### Sprint 10 hardening

**DECIDED / PLANNED**

Visible rectangle state and logical pending-selection state must be one coherent
interaction state. A user action that visibly cancels a pending rectangle must
also clear the owning workflow's logical selection. Programmatic visual cleanup
must not create callback loops.

## 13. Independent per-pane subject context -> workspace Working Subject

**DECIDED / SPRINT 10 PLANNED**

Question and Answer capture need one working Subject context so sustained
Chemistry capture does not surface unrelated Engineering work.

The Working Subject is a transient UI filter/default. It does not rewrite stored
Question Subject, classification, Exam ownership or global Search/Audit scope.

## 14. Flat capture UI growth -> focused feature packages/panes

**IMPLEMENTED / CURRENT**

The JavaFX UI is organised into capture, search, audit, curriculum, correction,
exam, PDF and export packages. Persistence and service logic remain outside
layout code.

Sprint 09 completed a substantial package refactor without changing persisted
domain semantics.

## 15. Exam Builder first -> revision resources first

### Earlier priority

Assessment assembly and printable output were inherited central goals.

### Current priority

**IMPLEMENTED STRATEGIC PATH**

```text
data safety
 -> current-curriculum corpus
 -> static HTML/assets
 -> SCORM
 -> QLearn validation
 -> capture/corpus completion
 -> revision presentation refinement
 -> later Exam Builder
```

This allowed real student revision output to exercise the bank before rebuilding
the full assessment-authoring pipeline.

## 16. Static revision output: fixed corpus traversal -> configurable presentation

### Current implementation

**IMPLEMENTED**

The revision corpus preserves current curriculum structure and placements.
`RevisionPresentationPlanner` groups SourceQuestion members within a final bucket
and currently assigns one global presentation-number sequence. The renderer
currently creates pages/links for the full curriculum traversal.

### Sprint 10 direction

**DECIDED / PLANNED**

Presentation configuration is separate from persisted curriculum semantics:

- choose Subtopic or Descriptor grouping at export time;
- roll Descriptor-classified Questions upward for Subtopic-mode presentation;
- order MCQ before written-response presentations;
- number Questions locally per generated question page;
- omit empty navigation branches by default;
- export all non-empty or selected Units;
- show generation metadata rather than internal capture-part statistics.

No output choice rewrites Question classification or mapping data.

## 17. Global generated numbering -> page-local student numbering

**DECIDED / SPRINT 10 PLANNED**

A student-facing Question number is presentation state for one generated page,
not durable Question identity and not a corpus-wide invariant. Each generated
question page therefore begins at Question 1. Multipart members presented as one
card retain one displayed number.

Source Question code remains provenance and is unaffected.

## 18. Byte-deterministic export -> explicit generation metadata

### Current

The existing revision pipeline is deterministic for a fixed corpus/request.

### Sprint 10

**DECIDED / PLANNED**

The student site should record when it was generated. Generation timestamp is
therefore intentional output metadata. Tests should inject/fix the export time so
rendering remains deterministic for a fixed request/time rather than depending
on the test machine clock.

Internal stored-part/capture counts are not student-facing output semantics.

## 19. Static HTML -> SCORM packaging layer

**IMPLEMENTED / CURRENT**

SCORM 1.2 packages the static revision site rather than implementing separate
Question grouping rules. Authoritative source PDFs are not normally copied into
the package.

Any later SCORM option parity should reuse the same presentation configuration
rather than fork output semantics.

## 20. Local desktop -> future deployment choices

**CURRENT DIRECTION**

Local SQLite keeps the first deployable application realistic. A live
SharePoint-synchronised/network SQLite file is explicitly not considered safe for
concurrent editing.

Future options include controlled import/export/merge, an IT-supported central
database and `jpackage` self-contained deployment.

## 21. PDF-only content -> future attachment extension

**PROPOSED / LATER**

Some future Questions may originate from transient web/doc content captured via
Windows Snipping Tool. A future extension may allow text plus managed image
attachments, but it must extend rather than weaken PDF provenance.
