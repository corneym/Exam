# Sprint 09 — Capture Workflow and Corpus Correction

> **Status at 20 September 2026:** implementation complete on
> `feature/capture-workflow`; final documentation/merge closeout follows.
>
> Current implementation head: `11acb24` (`complete sprint 09 capture workflow`).

## Purpose

Sprint 09 was driven by sustained use of the application while completing real
Question-bank data.

Sprint 08 established curriculum-authoring, mapping-coverage,
metadata-correction and corpus-audit foundations. Sprint 09 addressed the next
set of practical problems: capture controls that were difficult to use for long
sessions, persistence-order lists that did not match exam source order, and
correction operations needed by real legacy data.

The sprint therefore had three connected goals:

1. make Question and Answer capture controls remain usable across supported
   workspace widths and selection states;
2. make queues, Search and Corpus Audit follow source/natural Question order
   rather than persistence/capture order; and
3. add correction operations for shared preamble replacement, Exam-level
   metadata correction and deliberate splitting of one imported Question into
   multipart parts.

Revision HTML presentation redesign remained outside this sprint.

## Starting point

Sprint 08 was complete and merged to `main`.

The architecture already provided:

- explicit `SourceQuestion` multipart identity;
- explicit `SharedQuestionContext` and ordered context regions;
- transactional Question capture/edit persistence;
- legacy metadata correction;
- response-type-aware Question/Answer semantics;
- explicit capture-selection ownership;
- asynchronous Question Search and background save work;
- Corpus Audit filters and routing into existing correction workflows.

Sprint 09 reused these concepts rather than creating parallel systems.

## Design principles retained

### User-visible state matches logical capture ownership

If Answer capture owns the active selection, Question actions that cannot safely
act on it are disabled. Region actions are enabled only when a compatible
pending selection exists.

### Responsive layout is functional

Supported narrow capture widths must not hide the meaning of important labels or
actions.

### Source order is distinct from database identity

Insertion IDs remain persistence identities. User-facing source order is
deterministic by:

```text
provider / authority
-> year
-> booklet
-> natural Question code
```

### Corrections change the authoritative entity

- wrong Exam metadata -> correct the Exam/provider relationship;
- wrong shared preamble -> replace the SharedQuestionContext regions;
- legacy `3` that is really `3a` + `3b` -> create explicit multipart identity.

Sprint 09 additionally established that provider/year correction must keep the
managed PDF filesystem location and persisted `SourceDocument` path consistent.

### Slow UI transitions are measured before optimisation

Imported Questions activation was measured and avoidable synchronous work was
removed rather than hidden behind cosmetic progress.

### No silent inference

Known-PDF metadata reuse is based on persisted document relationships, not
filename guesses. Legacy split/preamble operations require explicit structure.

## Slice 1 — Baseline, source ordering and UI-state regressions

**COMPLETE**

Delivered:

- natural Question-code ordering;
- provider/year/booklet/question source ordering;
- reusable ordering behaviour;
- capture-selection ownership exposed to UI enablement;
- Add Region/Clear disabled when no compatible pending selection exists;
- narrow-width regressions for important capture controls.

## Slice 2 — Answer capture usability and ownership

**COMPLETE**

Delivered:

- readable Answer action controls at supported narrow widths;
- removal of excessive blank pane growth after accepted regions;
- stable region/page status placement;
- compact Answer-region preview behaviour;
- unanswered queue source/natural ordering;
- pending-selection-aware Add Region/Clear enablement;
- Question-region actions disabled while Answer capture owns the selection.

Answer completeness semantics were not changed.

## Slice 3 — Question capture and metadata-dialog usability

**COMPLETE**

Delivered:

- readable Question labels at supported narrow widths;
- compact Question-number entry;
- explicit Multiple Choice / Written Response radio buttons;
- readable metadata-dialog labels;
- pending-selection-aware Question region actions;
- deliberate removal of the redundant
  `Questions -> Capture New Questions` menu action.

## Slice 4 — Imported Questions activation performance

**COMPLETE**

The imported-question transition was profiled and avoidable repeated work was
removed/repositioned. Entering Imported Questions no longer performs the
pathologically slow activation observed at sprint start.

Asynchronous/stale-result rules remain consistent with Search/PDF precedents.

## Slice 5 — Shared-preamble replacement / recapture

**COMPLETE**

An existing persisted `SharedQuestionContext` can be recaptured deliberately.

The workflow:

- retains the same shared-context identity;
- preserves links from all legitimate Questions;
- stages replacement content before commit;
- leaves old persisted regions untouched on cancel/failure;
- reloads correctly;
- feeds Search/revision presentation after replacement.

Persistence remains capable of multiple ordered context regions even though the
automatic imported-preamble workflow remains single-region.

## Slice 6 — Exam metadata correction and known-PDF recognition

**COMPLETE**

### Exam metadata correction

A supported Exam-level correction workflow changes the owning Exam/provider/year
data while preserving linked Booklets, Questions, Answers, SourceQuestions and
SharedQuestionContexts.

Sprint-end hardening added filesystem consistency:

- managed booklet/answer PDFs are relocated when corrected provider/year changes
  their authoritative managed directory;
- persisted `SourceDocument.relativePath` values are updated in the same
  correction operation;
- destination collisions and shared-source conflicts are rejected;
- completed file moves are reversed if later database persistence fails;
- assessment-name-only edits do not move files because the assessment name is
  not part of the managed path.

### Known-PDF recognition

Selecting a source PDF already known to persistence reuses stored metadata and
does not create duplicate Exam/Booklet records merely because the user reopened
the source.

## Slice 7 — Dedicated legacy Question split workflow

**COMPLETE — 19 September 2026**

Delivered:

- Search Questions exposes `Split Question...` for eligible legacy single
  Questions;
- destination part codes, marks, classification and response type are confirmed;
- Question regions for all parts are staged before persistence;
- original Question identity is retained as one resulting part where safe;
- an existing Answer is retained only on an explicitly selected part;
- no shared preamble, newly captured shared preamble and compatible existing
  shared-context reuse are supported;
- SourceQuestion/Question/context relationships are persisted atomically;
- duplicate/incompatible destinations are rejected;
- cancellation leaves the original persisted Question unchanged;
- reload preserves multipart/shared-context identities;
- Corpus Audit and revision-presentation regressions cover the result.

## Slice 8 — Search Questions and Corpus Audit usability

**COMPLETE — 20 September 2026**

### Search Questions

Search now has explicit scope:

```text
Current syllabus
All Questions
```

Current-syllabus scope retains the established applicability/retrieval semantics.

All Questions displays the complete stored bank without claiming that historical
Questions are currently applicable merely because they are visible.

Both modes use deterministic source ordering. Stale background all-bank results
cannot overwrite a newer scope/search request.

### Corpus Audit

Displayed work follows provider/year/booklet/natural Question order while
existing filters and totals retain their semantics.

Year filter values are explicitly numerically sorted.

## Additional Sprint 09 sustained-use corrections

The following issues were discovered during the same real-use pass and were
completed before closeout.

### Search dialog size persistence

The Search dialog remembers user-resized width/height when a correction action
temporarily hides and then redisplays the same dialog.

### MCQ Answer-PDF visibility

MCQ completeness remains letter based; MCQs do not require Answer regions.

However, selecting an MCQ can now display/reuse the registered exam answer PDF
because that document is often where the correct option is read. If no answer
PDF is registered, one may be chosen. Region controls remain response-type
appropriate.

### Exam correction moves managed files

Provider/year correction now relocates managed exam/answer PDFs and updates
persisted source paths with rollback protection.

### UI package refactor and CI hardening

The JavaFX UI was reorganised into focused feature packages without changing
domain semantics.

GitHub Actions CI was hardened into separate jobs:

- Non-UI tests;
- remaining UI tests;
- workflow UI matrix: capture;
- workflow UI matrix: state-editing;
- workflow UI matrix: application.

## Slice 9 — Regression, acceptance and documentation closeout

**IMPLEMENTATION COMPLETE; FINAL REPOSITORY CLOSEOUT IN PROGRESS**

Formal closeout commands remain:

```bash
./mvnw test
./mvnw -Pheadless-ui-tests test
./mvnw javadoc:javadoc
git diff --check
```

Verified at feature-branch head `11acb24`:

- GitHub Actions run `35491556692` completed successfully;
- Non-UI tests green;
- workflow UI capture green;
- workflow UI state-editing green;
- workflow UI application green;
- remaining UI tests green;
- headless UI tests reported green under JUnit in Eclipse.

Javadoc and whitespace validation should be rerun after the final documentation
commit before merge.

## Sprint acceptance status

The implementation acceptance criteria are satisfied for:

- readable Answer/Question controls at supported narrow width;
- stable Answer-region layout/status;
- deterministic Answer and Corpus Audit source ordering;
- correct pending-selection enablement and capture ownership;
- explicit response-type radio-button UI;
- readable metadata/import controls;
- improved Imported Questions activation;
- shared-preamble replacement surviving reload;
- supported Exam-level metadata correction;
- known-PDF metadata reuse;
- transactional legacy multipart split;
- Search all-bank versus current-syllabus scope;
- deliberate resolution of redundant capture-menu navigation;
- MCQ answer-document visibility;
- managed-PDF relocation after provider/year correction.

Final merge readiness additionally requires the closeout commands above to remain
clean after documentation changes.

## Explicitly outside Sprint 09 / transferred to backlog

The following are not Sprint 09 completion requirements:

- revision HTML Descriptor-versus-Subtopic grouping selection;
- multipart provenance decoration redesign;
- MCQ-before-written output grouping and optional response-type sections;
- automatic multi-page shared-preamble capture;
- optional MCQ explanation regions;
- multiple original classifications;
- Question-level mapped-applicability exceptions;
- explicit out-of-scope disposition;
- Exam Builder and printable assessment generation;
- clipboard/image-attachment Questions;
- packaging/deployment.

Two real-use issues discovered late in the sprint are also deliberately retained
as backlog rather than misreported as implemented:

- a general shared-context capture/reuse workflow for independent Questions,
  including successive MCQs sharing one stimulus;
- curriculum-code entry can leave hierarchy ComboBox display visually out of
  sync even when the underlying selection model is correct.

## Documentation relationship

This file is the canonical Sprint 09 design/final-state record.

Current application capability belongs in `docs/current-status.md`.
Forward sequencing belongs in `docs/DEVELOPMENT_ROADMAP.md`.
Unimplemented work belongs in `docs/design/backlog.md`.

## Branch

Sprint 09 implementation branch:

`feature/capture-workflow`
