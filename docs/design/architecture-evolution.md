# Architecture Evolution

> Updated 24 September 2026.  
> This document records why major design directions changed. It distinguishes
> implemented architecture from deferred/proposed work.

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

**IMPLEMENTED / CURRENT**

Early smoke-test repositories proved vertical slices. Runtime persistence is now
SQLite with migrations, transactions and reconstruction tests. In-memory
repositories remain focused test tools rather than runtime authority.

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

Sprint 10 exposes general SharedQuestionContext capture/reuse for independent
MCQs. Sequence-aware booklet continuation lets an immediate successor inherit the
same context without manufacturing SourceQuestion identity.

## 9. One region -> ordered multi-region content

**IMPLEMENTED / CURRENT**

Question, Answer and SharedQuestionContext content can use ordered region lists.
Ordinary multi-region Questions remain distinct from shared context and
multipart identity.

## 10. Booklet-name assumptions -> explicit booklet format plus Question response type

**IMPLEMENTED / CURRENT**

`QuestionResponseType` remains persisted on each Question because mixed-response
booklets require per-Question semantics and Answer completeness depends on actual
Question type.

Sprint 10 introduced persisted `ExamBookletQuestionFormat`:

```text
MULTIPLE_CHOICE
WRITTEN_RESPONSE
MIXED
UNSPECIFIED
```

The resulting rules are:

- MCQ-only booklets fix new Questions to Multiple Choice and exactly one mark;
- Written-Response-only booklets fix new Questions to Written Response while
  marks remain editable;
- Mixed booklets permit manual response-type selection and conservative Written
  Response inference;
- one mark alone never implies Multiple Choice;
- existing/imported/editing Questions retain persisted response type;
- `UNSPECIFIED` is retained for unresolved legacy booklet metadata.

Reason: booklet format is a real source-document property, but it must not
rewrite individual Question identity.

## 11. Blocking UI operations -> asynchronous persistence and stale-request protection

**IMPLEMENTED / CURRENT**

Search, Question/Answer persistence and relevant PDF transitions use background
work with JavaFX completion handling. Stale/lifecycle protection prevents older
work from overwriting newer UI state.

SQLite connections use a bounded busy timeout because JavaFX reads and
background persistence can legitimately overlap briefly.

Sprint 10 additionally hardened TestFX lifecycle handling around modal
`showAndWait()` dialogs and reusable hidden Dialog nodes so CI under Xvfb tests
actual showing windows rather than stale retained controls.

## 12. Shared PDF rectangle -> explicit capture-selection ownership

### Sprint 07

**IMPLEMENTED / CURRENT**

Transient ownership distinguishes `QUESTION`, `SHARED_CONTEXT` and `ANSWER` so
one workflow does not silently clear another workflow's pending selection.

### Sprint 10 hardening

**IMPLEMENTED / CURRENT**

Visible rectangle state and logical pending-selection state are one coherent
interaction state. A user action that visually cancels a pending rectangle also
clears the owning workflow's logical selection. Programmatic visual cleanup does
not create callback loops.

## 13. Independent per-pane subject context -> workspace Working Subject

**IMPLEMENTED / CURRENT**

Question and Answer capture use one Working Subject context so sustained
Chemistry capture does not surface unrelated work.

The Working Subject is transient UI state. It does not rewrite stored Question
Subject, classification, Exam ownership or global Search/Audit scope.

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

## 16. Static revision output: fixed traversal -> configurable presentation

### Earlier

The revision corpus preserved current curriculum structure, while presentation
used a largely fixed traversal and global numbering. Empty branches and
student-irrelevant internal counts could leak into the generated site.

### Current

**IMPLEMENTED / CURRENT**

Presentation configuration is separate from persisted curriculum semantics:

- choose Subtopic or Descriptor grouping at export time;
- roll Descriptor-classified Questions upward for Subtopic presentation;
- retain directly Subtopic-classified Questions under Descriptor grouping;
- order Multiple Choice before Written Response, with an Other section for
  unresolved renderable material;
- use explicit response-type sections/navigation where useful;
- number Questions locally per generated question page;
- omit empty navigation branches and unnecessary pages;
- export all non-empty Units by default or an explicit Unit subset;
- use the same grouping/Unit scope for HTML and SCORM;
- show student-facing card counts and generation metadata.

No presentation choice rewrites Question classification or mapping data.

## 17. Global generated numbering -> page-local student numbering

**IMPLEMENTED / CURRENT**

A student-facing Question number is presentation state for one generated page,
not durable Question identity and not a corpus-wide invariant.

Every generated question page starts at Question 1. Multipart members presented
as one card retain one displayed number. Source Question code remains provenance.

## 18. Byte-deterministic export -> explicit generation metadata

### Earlier

The revision pipeline was deterministic for a fixed corpus/request but had no
explicit generation time.

### Current

**IMPLEMENTED / CURRENT**

The student site records one export generation timestamp. `RevisionExportService`
captures it once and passes it to rendering. Tests use a fixed/injected `Clock`
or explicit generated time, so deterministic test output does not depend on wall
clock time.

Internal stored-part/capture counts are not student-facing output semantics.

## 19. Static HTML -> SCORM packaging layer

**IMPLEMENTED / CURRENT**

SCORM 1.2 packages the configured static revision site rather than implementing
separate grouping rules. Authoritative source PDFs are not normally copied into
the package.

Sprint 10 gives Revision HTML and SCORM matching grouping and selected-Unit
configuration, preserving one presentation system rather than two diverging
ones.

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

## 22. Exam-wide Answer PDF reuse -> booklet-specific AnswerFile assignment

**IMPLEMENTED / CURRENT**

Real examinations may contain several question booklets and several answer
documents. The persisted relationship is:

```text
ExamBooklet -> zero or one AnswerFile
AnswerFile  -> zero or many ExamBooklets
```

Schema version 10 adds nullable `exam_booklets.answer_file_id`. Null represents
an unresolved legacy or not-yet-selected relationship rather than an inferred
default.

Answer Capture resolves the PDF from the active Question's ExamBooklet. A mapped
booklet automatically restores its AnswerFile. An unmapped booklet clears the
previous file and presents `Choose PDF...`; selecting a PDF persists the mapping.

Persistence enforces same-Exam ownership and prevents one Answer from spanning
multiple AnswerFiles.

This supersedes the earlier exam-wide heuristic.

## 23. Mapping-only applicability -> mapping plus Question-specific output exceptions

### Earlier

A Question inherited every current placement derived from direct current
classification or confirmed historical mapping. There was no way to preserve a
valid mapping while suppressing one exceptional historical Question.

### Current

**IMPLEMENTED / CURRENT**

Schema version 12 adds:

```text
question_output_exclusions (
    question_id,
    current_curriculum_node_id
)
```

Absence of a row means normal derived applicability. A row suppresses only that
Question/current-node placement.

Exclusions are valid only for current Subtopic or Descriptor nodes in the same
Subject. They do not rewrite historical classification or curriculum mapping.

The revision corpus applies exclusions before placement/statistics/rendering.

Search exposes Include/Exclude controls, but the output-applicability panel does
not reuse the active Search filter as its truth source. It resolves the selected
Question's complete Subject-wide current applicability, so a narrow Descriptor
search cannot hide another valid output placement.

Reason: "why this Search matched" and "where this Question may appear in revision
output" are related but different concepts.

## 24. Mixed legacy/live preamble terminology -> Shared Context live schema

### Earlier

Domain/UI wording had moved to Shared Context while live physical database
columns still used historical `preamble_*` names.

### Current

**IMPLEMENTED / CURRENT**

Schema version 13 physically renames:

```text
questions.preamble_capture_required
    -> questions.shared_context_capture_required

source_questions.preamble_status
    -> source_questions.shared_context_status
```

Runtime SQL uses the new names. Historical migration files and historical-schema
tests keep the old names because they must still describe genuine earlier
versions. Legacy workbook vocabulary may also retain `Preamble` where that is the
external source term.

Reason: current runtime terminology should agree across UI, domain and live
persistence without falsifying historical schemas.

## 25. Generic metadata correction -> separated Search classification and Question editing responsibilities

**IMPLEMENTED / CURRENT**

Search now shows the selected Question's stored classification path separately
from the active Search filters.

A stored Subtopic may be refined inline to one child Descriptor with explicit
dirty state and Save/Discard/Cancel navigation behaviour. Broader
reclassification remains the responsibility of `Edit Question`.

`Edit Metadata` no longer contains classification controls.

When Search transfers to Edit Question, the Question PDF restores the saved
location and displays the persisted region with a light-grey overlay.

Reason: classification and source-region correction are substantive Question
editing concerns; general metadata correction should not become an ambiguous
second path for changing them.
