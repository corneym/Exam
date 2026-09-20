# Exam Question Bank — Project History

> Consolidated factual development history through 20 September 2026.  
> This is historical evidence, not the authority for current branch capability.
> Use `current-status.md` for current implementation and the active sprint design
> for planned work.

## 1. Legacy application and inherited problem

The predecessor Exam Builder used an Excel workbook as its metadata/database and
stored named Question, Answer and preamble image snips. It could assemble
curriculum-grouped content, generate LaTeX/PDF, build hierarchical HTML and create
SCORM-style ZIP output.

The redevelopment retained the useful publishing goals but rejected workbook and
snippet filenames as the long-term system of record.

Important inherited requirements were durable source provenance, multipart
Question handling, reusable preamble material, classification against syllabus
content and linked Question/Answer material.

## 2. 17 August 2026 — batching exercise exposes redevelopment requirements

A representative 2021 Neap Paper 1 MCQ set was manually classified for a
curriculum batching experiment.

The exercise established that:

- source formatting should be preserved visually rather than retyped;
- generated resources need independent sequential numbering plus original source
  attribution;
- some source Questions can reasonably match more than one descriptor;
- some source Questions may be deliberately out of scope;
- Question and Answer/solution linking must survive rebatching.

This evidence later informed the PDF-region source model and the decision to
preserve original classification/provenance separately from current
applicability.

## 3. August 2026 — redevelopment foundation

Early work established a Java/JavaFX/Maven desktop application, Git/GitHub
workflow, curriculum model, PDF viewing/region capture and the move toward SQLite
as the runtime datastore.

The application adopted one configured `data.root`, portable managed paths and
PDFBox-based rendering/extraction. Normalised source rectangles replaced
render-size-dependent pixel coordinates.

Question identity became booklet-based with text Question codes, allowing mixed
booklets and multipart-like codes without assuming integers.

## 4. August 2026 — curriculum and persistence foundation

SQLite schema/migration work established Subjects, syllabus versions, curriculum
hierarchy, Exams/Booklets/source documents, Questions and source regions.

The application deliberately allowed metadata-only Questions with zero source
regions so legacy records could be imported before capture was complete.

Historical classification was preserved rather than automatically rewritten to a
new syllabus.

## 5. Sprint 01 — Legacy Metadata Import

Branch: `feature/legacy-metadata-import`.

Legacy workbook import reconstructed Exam/Booklet metadata, Questions,
classification and MCQ answer letters where reliable. Managed source documents
were registered and import was made conflict-aware/idempotent.

Legacy preamble evidence was preserved conservatively. Import did not invent
SourceQuestion grouping or fake preamble regions from metadata patterns.

## 6. 2–3 September 2026 — Sprint 02: Directional Curriculum Applicability

Branch: `feature/curriculum-applicability`.

Historical -> current mapping direction was made explicit. Descriptor and
Subtopic same-level mapping, one-to-many relationships and reviewed-state
semantics were implemented. Only confirmed mappings affect applicability.

Original historical classification remains provenance; current applicability is
derived.

## 7. 3 September 2026 — legacy capture completion improves

Repository selection and capture workflows for metadata-only legacy Questions
were improved so missing source regions and later Answer capture could be filled
without re-importing metadata.

## 8. 3–4 September 2026 — Sprint 03: Question Retrieval

Branch: `feature/question-retrieval`.

Current-curriculum retrieval was implemented across Subject, Unit, Topic,
Subtopic and Descriptor scopes. Direct current Questions and confirmed-mapped
historical Questions are returned together while preserving original provenance.

Search and stored Question preview became asynchronous with stale/lifecycle
protection.

## 9. 4 September 2026 — product priority changes

Exam Builder ceased to be the immediate next feature. The near-term product path
became data safety -> deterministic current-curriculum revision corpus -> static
HTML -> SCORM -> real QLearn validation.

This shifted development toward exercising the bank through student revision
output before rebuilding the full assessment-authoring pipeline.

## 10. 5 September 2026 — standalone Chemistry 2019 -> 2025 mapping workbook

A separate Chemistry mapping workbook was produced from supplied 2019 and 2025
descriptor workbooks. It demonstrated structural syllabus change including
splits, combinations, removals and additions and reinforced the need for
one-to-many mapping plus human review.

The workbook remains a reference artefact; application SQLite review state is
authoritative.

## 11. Sprint 04 — Backup, Restore and Data Safety

Branch: `feature/backup-restore`.

Versioned backup archives, SQLite-consistent snapshots, manual and automatic
backup, bounded retention, validated restore, pre-restore safety backup, rollback
and migration compatibility checks became first-class application features.

This established captured/reviewed bank data as expensive project data requiring
recoverability before further schema/workflow expansion.

## 12. Sprint 05 — Hierarchical Revision Corpus and Static HTML Export

A deterministic current-curriculum revision corpus and static student website
were implemented. Output includes rendered Question/Answer assets, hierarchical
navigation, generated numbering/marks, answer disclosure and provenance.

Zero-region Questions remain diagnostic/non-renderable rather than being
invented as content.

Real Chemistry output was exercised in a browser.

## 13. Sprint 06 — SCORM 1.2 and QLearn validation

Static revision content became the source for deterministic SCORM 1.2 single-SCO
packaging. Manifest/reference validation was implemented and a real generated
Chemistry package successfully imported and launched in QLearn.

Authoritative source PDFs are not normally copied into the SCORM package.

## 14. 7–11 September 2026 — Sprint 07: Preamble-aware capture and UI redesign

Branch: `feature/preamble-capture`.

Schema v5/v6 introduced persisted SourceQuestion identity,
SharedQuestionContext, ordered shared-context regions and source-question
PreambleStatus.

Multipart source identity and shared source material were deliberately separated.
One shared context may serve several Questions without making them one multipart
Question.

Capture/correction work added preamble-aware workflows, shared-context reuse,
anchored same-page selection, explicit transient selection ownership,
syllabus-sensitive classification, Question correction preserving Answer
identity, Answer correction, MCQ letter entry and asynchronous save/PDF work.

Revision presentation groups SourceQuestion members only within the final current
curriculum bucket and renders shared context where needed.

## 15. 12–17 September 2026 — Sprint 08: Curriculum and Corpus Completion

Branch: `feature/data-completion`.

Sprint 08 added subject-neutral curriculum authoring/lifecycle, mapping coverage
and review support, legacy metadata correction, persisted Question response type,
mixed-response booklet support, response-type-aware completeness and Corpus
Audit tooling.

The sprint was completed and merged to `main` on 17 September 2026.

## 16. 17–20 September 2026 — Sprint 09: Capture Workflow and Corpus Correction

Branch: `feature/capture-workflow`.

Sustained real-corpus use drove a capture/correction sprint rather than a new
high-level feature.

Implemented work included:

- deterministic source/natural ordering in capture/management views;
- responsive Question/Answer controls and explicit response-type radio buttons;
- imported-question activation performance correction;
- shared-preamble recapture/replacement;
- authoritative Exam metadata correction;
- managed booklet/answer PDF relocation when corrected provider/year changes;
- known-PDF metadata reuse;
- transactional legacy single-Question -> multipart split;
- explicit existing-Answer ownership during split;
- Search Questions `Current syllabus` versus `All Questions` scope;
- deterministic Corpus Audit ordering and numeric year ordering;
- Search dialog size persistence;
- MCQ Answer-PDF visibility/reuse;
- UI package refactoring;
- GitHub Actions CI hardening.

Implementation head `11acb24` passed the configured GitHub Actions jobs. Final
documentation commit was `57777aa`.

Sprint 09 merged to `main` on 20 September 2026 in commit `2533586`.

## 17. 20 September 2026 — protected-main workflow established

After Sprint 09 merge, the repository moved from convention-only main-branch
safety to protected-main workflow. A test change on `chore/test-pr-protection`
was merged through pull request #1, producing `main` commit `5a1e5a9`.

GitHub reports `main` as protected. Repository governance is therefore no longer
an unfinished Sprint 09 backlog item.

## 18. 20 September 2026 — Sprint 10 design begins

Branch: `feature/capture-output`, created from `5a1e5a9`.

Real use identified two immediate defects:

- a visible PDF selection can be cleared by a later click while logical pending
  selection remains; and
- curriculum code entry can leave visible Topic state blank/stale, including
  restoring a previous Topic when the code is shortened to only the Unit.

Sprint 10 was then scoped around capture hardening plus revision-output
refinement:

- Working Subject filtering for Question/Answer capture;
- Written Response defaults from part-letter and marks evidence;
- general shared-context capture/reuse for independent Questions/MCQs;
- export-time Subtopic/Descriptor grouping;
- MCQ-before-written ordering;
- per-page numbering beginning at 1;
- empty-branch pruning and selected-Unit export;
- generated-site status including creation date/time and student-facing revision
  question count.

At this history point these Sprint 10 items are design/planned work, not
implemented capability.

## 19. Future content-source extension

A later workflow may allow clipboard/Windows Snipping Tool images and other
managed attachments for Questions whose source is not a durable PDF. Storage,
provenance, backup and output integration remain future design decisions.

## 20. Development discipline

Git/GitHub branches, commits, sprint records, tests and CI are the implementation
evidence. Current repository code must be inspected before exact implementation
instructions are based on remembered classes/methods.

Completed sprint documents remain historical records. Current capability belongs
in `current-status.md`, forward sequencing in `DEVELOPMENT_ROADMAP.md`, and
unimplemented non-sprint work in `design/backlog.md`.
