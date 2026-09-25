# Exam Question Bank — Project History

> Consolidated factual development history through 25 September 2026.  
> This is historical evidence, not the authority for current branch capability.
> Use `current-status.md` for current implementation and the active/final sprint
> record for sprint-specific state.

## 1. Legacy application and inherited problem

The predecessor Exam Builder used an Excel workbook as its metadata/database and
stored named Question, Answer and preamble image snips. It could assemble
curriculum-grouped content, generate LaTeX/PDF, build hierarchical HTML and create
SCORM-style ZIP output.

The redevelopment retained the useful publishing goals but rejected workbook and
snippet filenames as the long-term system of record.

Important inherited requirements were durable source provenance, multipart
Question handling, reusable shared source material, classification against
syllabus content and linked Question/Answer material.

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
SourceQuestion grouping or fake shared-context regions from metadata patterns.

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

## 14. 7–11 September 2026 — Sprint 07: shared-context-aware capture and UI redesign

Branch: `feature/preamble-capture`.

Schema v5/v6 introduced persisted SourceQuestion identity,
SharedQuestionContext, ordered shared-context regions and source-question shared
context status (then physically named with legacy `preamble_*` columns).

Multipart source identity and shared source material were deliberately separated.
One shared context may serve several Questions without making them one multipart
Question.

Capture/correction work added shared-context-aware workflows, context reuse,
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
- shared-context recapture/replacement;
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

Implementation head `11acb24` passed configured GitHub Actions jobs. Final
documentation commit was `57777aa`.

Sprint 09 merged to `main` on 20 September 2026 in commit `2533586`.

## 17. 20 September 2026 — protected-main workflow established

After Sprint 09 merge, the repository moved from convention-only main-branch
safety to protected-main workflow. A test change on `chore/test-pr-protection`
was merged through pull request #1, producing `main` commit `5a1e5a9`.

GitHub reports `main` as protected.

## 18. 20 September 2026 — Sprint 10 design begins

Branch: `feature/capture-output`, created from `5a1e5a9`.

Real use identified two immediate defects:

- a visible PDF selection could clear while logical pending selection remained;
- curriculum code entry could leave visible Topic state blank/stale.

Sprint 10 was scoped around capture hardening plus revision-output refinement:
Working Subject filtering, response-type defaults, independent shared-context
capture, configurable revision grouping, response ordering, page-local numbering,
empty-branch pruning, selected-Unit export and generated-site status.

## 19. 20–22 September 2026 — Sprint 10 capture hardening

Initial Sprint 10 implementation repaired capture-selection ownership and
curriculum selector synchronisation.

Booklet-level Question format was persisted in schema v9, allowing MCQ,
Written-Response and Mixed booklet behaviour without rewriting existing Question
response type.

Schema v10 added booklet-specific AnswerFile assignment so several booklets can
share one answer document while another booklet uses a different file.

Schema v11 added restart-safe, sequence-aware one-step Shared Context
continuation for independent MCQs.

The capture workspace gained a transient Working Subject filter and early
duplicate Question-code feedback.

TestFX/Xvfb workflow handling was hardened after Linux CI exposed modal-dialog
lifecycle races.

## 20. 22–23 September 2026 — revision output becomes student-facing and configurable

The revision renderer/presentation pipeline was refined to support:

- Subtopic or Descriptor grouping, with Descriptor mode available only when all
  renderable placements have Descriptor-level coverage and Subtopic fallback
  otherwise;
- Multiple Choice before Written Response;
- response-type section headings/navigation;
- page-local numbering from Question 1;
- student-facing multipart card semantics;
- empty curriculum-branch pruning;
- combined multipart source provenance;
- non-empty Unit selection.

Revision HTML and SCORM export dialogs both gained the same grouping and Unit
selection options. Selected Unit scope is transient and filters assets,
validation, statistics and generated output.

The subject index moved from internal corpus counts to student-facing revision
question/card counts.

## 21. 23–24 September 2026 — Search and Question correction refinement

Search Questions was refined after real editing use:

- selector labels no longer collapse under narrow layout;
- functional minimum dialog width was added;
- width/height and X/Y position survive edit hide/show cycles;
- selected Question classification is displayed separately from Search filters;
- a Subtopic classification can be refined to a child Descriptor inline;
- Descriptor refinement has explicit dirty/Save/Discard/Cancel behaviour;
- broader reclassification remains in Edit Question;
- Classification was removed from Edit Metadata;
- Edit Question restores the saved PDF location and shows the stored region
  overlay.

The dialog constructor was refactored after this feature growth.

## 22. 24 September 2026 — Question-specific revision-output applicability

A requirement emerged that a valid historical-to-current mapping may still be
too broad for one particular Question.

Schema v12 added `question_output_exclusions`.

The design preserves existing behaviour by default:

```text
no exclusion row -> ordinary derived applicability
exclusion row    -> suppress this Question at this current node
```

Exclusions do not rewrite mappings or historical classification.

`RevisionCorpusBuilder` applies exclusions before placement and output
statistics. Search exposes immediate Include/Exclude controls.

A final semantic correction separated Search-match applicability from
revision-output applicability: the output panel always resolves complete
Subject-wide applicability even when Search is narrowed to one Descriptor.

Commit `07b7b337` contains the final applicability correction.

## 23. 24 September 2026 — live persistence terminology aligned to Shared Context

Schema v13 renamed the live physical columns:

```text
preamble_capture_required -> shared_context_capture_required
preamble_status           -> shared_context_status
```

Historical migration scripts/tests deliberately retain old names so earlier
schemas remain reproducible.

Migration hardening found and corrected stale runtime SQL, stale latest-schema
tests and a semicolon-sensitive migration-comment issue. Populated migration,
snapshot, legacy import and repository tests were brought back to green.

## 24. 24 September 2026 — generated-site timestamp and initial Sprint 10 closeout

Revision export now captures one generation timestamp and renders it on the
student site. Tests inject/fix time so wall-clock variability does not make
output tests nondeterministic.

The student-facing subject count uses presentation semantics, so multipart source
members displayed as one card count once.

Commit `07b7b337` completed the planned Question-applicability implementation and
passed GitHub Actions CI run 59. Documentation closeout followed in `27f9fd2`.

An independent pre-merge review then found one remaining behavioural defect:
closing Search Questions through the native title-bar could bypass the dirty
Descriptor Save / Discard Changes / Cancel guard.

## 25. 25 September 2026 — native Search-close guard and public API Javadoc closeout

`QuestionSearchDialog` now guards the Dialog close request as well as the
DialogPane Close button. A dirty Descriptor refinement therefore cannot be
silently lost through the native window close control. Regression coverage
verifies Cancel preserves the dirty edit and Discard permits the close.

A final public API documentation pass added or corrected Javadoc across 31
production files spanning export, revision, capture, correction, curriculum,
exam, PDF, audit, Search, repository and backup boundaries.

`RevisionCorpusScope` gained an explicit documented public constructor equivalent
to its previous implicit public constructor; behaviour is unchanged.

Final validation reported:

- focused Search tests green;
- full Maven suite green;
- strict Javadoc generation with no warnings;
- `git diff --check` green;
- GitHub Actions CI run 61 successful.

Final verified Sprint 10 feature-branch head:

`810601c8` — `Javadoc updated`

At this history point Sprint 10 implementation and closeout validation are
complete on `feature/capture-output`;
Sprint 10 merged to main via pull request #2.
Merge commit: `a3dfda7e`
Post-merge GitHub Actions run 64: successful.


## 26. Future content-source extension

A later workflow may allow clipboard/Windows Snipping Tool images and other
managed attachments for Questions whose source is not a durable PDF. Storage,
provenance, backup and output integration remain future design decisions.

## 27. Development discipline

Git/GitHub branches, commits, sprint records, tests and CI are implementation
evidence. Current repository code must be inspected before exact implementation
instructions are based on remembered classes/methods.

Completed sprint documents remain historical records. Current capability belongs
in `current-status.md`, forward sequencing in `DEVELOPMENT_ROADMAP.md`, and
unimplemented non-sprint work in `design/backlog.md`.
