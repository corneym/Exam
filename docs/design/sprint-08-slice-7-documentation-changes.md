# Sprint 08 — Slice 7 Documentation Changes

## Purpose

Record the documentation changes required by the decision to pull Question-level response type into Sprint 08 Slice 7.

This document is documentation-only. The production code changes should be implemented separately in small tested slices.

---

## Confirmed design decisions

### Question response type

Persist response type at Question level:

```text
MULTIPLE_CHOICE
WRITTEN_RESPONSE
UNKNOWN
```

Response type must belong to the Question, not the booklet, because a single booklet may contain both multiple-choice and written-response Questions.

### Multiple-choice Answers

For `MULTIPLE_CHOICE` Questions:

- the existing A/B/C/D radio buttons remain the primary Answer-entry UI;
- a valid stored answer letter is sufficient for Answer completeness;
- Answer regions are not required;
- optional Answer regions may later be supported as explanation material;
- explanation regions are supplementary and must not be required for completeness.

### Written-response Answers

For `WRITTEN_RESPONSE` Questions:

- at least one Answer region is required for Answer completeness;
- textual Answer content alone is not sufficient;
- the A/B/C/D controls must not be shown.

### Unknown response type

For `UNKNOWN` Questions:

- Answer completeness cannot yet be determined;
- ordinary Answer capture should be blocked until response type is resolved;
- corpus audit should report `UNKNOWN_RESPONSE_TYPE`;
- do not additionally report `MISSING_ANSWER` solely because response type is unresolved.

### Mixed-response booklets

Mixed-response booklets are explicitly supported.

Runtime Answer behaviour must not depend on booklet-name inference once Question-level response type is implemented.

### Existing data preservation

Changing response type must not silently delete existing:

- Answer text;
- Answer regions;
- Question regions;
- classification;
- SourceQuestion relationship;
- SharedQuestionContext relationship.

---

## `docs/design/sprint-08-Curriculum-and-Corpus-Completion.md`

Update Slice 7 so it no longer assumes every complete Answer requires Answer regions.

Replace the current answer-completeness wording with response-type-aware semantics:

```text
MULTIPLE_CHOICE
    stored valid answer letter required
    Answer regions optional

WRITTEN_RESPONSE
    one or more Answer regions required
    textual Answer alone insufficient

UNKNOWN
    response type must be resolved before Answer completeness can be determined
```

Add Question response type as a prerequisite for reliable corpus completeness.

Add an explicit corpus problem state:

```text
UNKNOWN_RESPONSE_TYPE
```

The Slice 7 work queue should distinguish at least:

```text
missing Question source
missing Answer
unknown response type
unresolved shared context
complete
```

Do not describe legacy answer text as universally insufficient. For MCQ Questions, the stored A/B/C/D answer is the complete required Answer representation.

Retain the rule that written-response text without Answer regions is incomplete.

Update the Slice 7 outcome so corpus completeness is explicitly response-type-aware.

Update Sprint 08 acceptance criteria so they include:

- persisted Question-level response type;
- mixed-response booklet support;
- MCQ completeness from stored answer letter;
- written-response completeness from Answer regions;
- unknown response type surfaced for correction;
- corpus queue using these semantics.

Also remove the duplicate obsolete Slice 6 `Outcome:` paragraph if it remains in the document.

---

## `docs/current-status.md`

After implementation is complete, update the current status to record:

- latest schema version increased to the new version used for response type;
- `Question` now persists response type;
- supported values are `MULTIPLE_CHOICE`, `WRITTEN_RESPONSE`, and `UNKNOWN`;
- mixed-response booklets are supported;
- Answer UI is driven by Question response type rather than booklet naming;
- MCQ Answers use the existing A/B/C/D controls and do not require Answer regions;
- written-response Answers require one or more Answer regions;
- UNKNOWN Questions must have response type resolved before ordinary Answer capture;
- corpus completeness uses response-type-aware Answer rules;
- status of the corpus audit queue and filters once implemented.

Remove or revise any wording that says MCQ behaviour is currently inferred from booklet naming.

---

## `docs/roadmap.md`

The roadmap currently places Question-level response type under a later milestone.

Once implementation is complete:

- remove Question-level response type from the later unresolved-semantics milestone;
- record it as completed/current Sprint 08 work;
- retain optional MCQ explanation-region capture as later work;
- update corpus-completion wording so it says `missing Answer` rather than assuming `missing Answer regions` for every Question.

The long-term optional MCQ explanation feature remains:

```text
correct answer letter
+
optional captured explanation regions
```

The explanation is not required for corpus completeness.

---

## `docs/DEVELOPMENT_ROADMAP.md`

Update the current Sprint 08 section to state that Question-level response type was pulled forward because Slice 7 cannot determine Answer completeness without it.

Update the Slice 7 queue description from:

```text
Answer regions missing
```

to response-type-aware semantics:

```text
missing Answer
unknown response type
```

Document:

```text
MULTIPLE_CHOICE -> stored answer letter required
WRITTEN_RESPONSE -> Answer region(s) required
UNKNOWN -> response type unresolved
```

Once implemented, remove Question-level response type from the section describing unresolved future Question-model decisions.

Update the recorded latest SQLite schema version when the migration is implemented.

---

## `docs/design/backlog.md`

Once response type is implemented:

- remove the deferred `Question-level response type` item;
- retain `MCQ explanation capture`;
- revise the broad capture/audit queue item so it no longer assumes every Question requires Answer regions;
- use `missing Answer` as the general state;
- explain that MCQ and written-response Questions have different Answer-completeness rules.

The MCQ explanation backlog item should remain explicitly optional and should not block corpus completeness.

---

## Documentation timing

### Before production implementation

Update only the Sprint 08 design document so the active design reflects the agreed semantics before code changes begin.

### After production implementation is green

Update:

```text
docs/current-status.md
docs/roadmap.md
docs/DEVELOPMENT_ROADMAP.md
docs/design/backlog.md
```

These should describe implemented behaviour, not planned behaviour.

---

## Documentation closeout rule

At Slice 7 closeout, the documentation should consistently state:

```text
Question response type is authoritative application state.

MULTIPLE_CHOICE
    answer letter required
    Answer regions optional

WRITTEN_RESPONSE
    Answer region(s) required
    text alone insufficient

UNKNOWN
    response type unresolved
    Answer completeness cannot yet be decided
```

No document should continue to present booklet naming as the authoritative MCQ detector once the new model is implemented.
