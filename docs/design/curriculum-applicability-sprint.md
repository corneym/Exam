# Sprint Design: Directional Curriculum Applicability

## Branch

`feature/curriculum-applicability`

## Purpose

This sprint will make curriculum mappings explicitly directional from a historical syllabus to the current syllabus, add reviewed subtopic-to-subtopic mappings, and define how historical questions become applicable to the current syllabus without overwriting their original classification.

The intended direction is:

```text
historical syllabus  ->  current syllabus
2019                 ->  2025
```

Mappings are not reversible. The system may query a mapping from either endpoint for retrieval purposes, but the stored semantic relationship remains historical-to-current.

## Core Design Principles

### 1. Mapping direction is explicit

A curriculum mapping always means:

```text
historical source node  ->  current target node
```

For the present Chemistry data this is:

```text
2019  ->  2025
```

The current syllabus must not be accepted as a mapping source when an older syllabus is the intended source.

The UI and persistence/service layers must both enforce this rule. Direction must not depend only on a UI convention.

### 2. The current syllabus is the target

For a selected subject:

- the current syllabus is the mapping target;
- source syllabus choices are non-current syllabus versions for the same subject;
- source and target must belong to the same subject;
- source and target must be different syllabus versions.

The mapping review UI should make this relationship obvious and difficult to misuse.

### 3. Mapping endpoints must be at the same curriculum level

Supported mappings for this sprint are:

```text
DESCRIPTOR -> DESCRIPTOR
SUBTOPIC   -> SUBTOPIC
```

Unsupported mappings include:

```text
SUBTOPIC   -> DESCRIPTOR
DESCRIPTOR -> SUBTOPIC
```

The existing `CurriculumMapping` model already requires source and target nodes to be at the same curriculum level.

### 4. One-to-many mappings are valid

Curriculum changes can split one historical concept across multiple current concepts.

Examples:

```text
2019 Descriptor A
    -> 2025 Descriptor B
    -> 2025 Descriptor C
```

and:

```text
2019 Subtopic A
    -> 2025 Subtopic X
    -> 2025 Subtopic Y
```

The model and review workflow must continue to support this.

### 5. Confirmed mappings are authoritative

Only confirmed mappings may affect question applicability.

Suggestions are advisory only.

The system must preserve the distinction between:

- suggested;
- confirmed;
- explicitly reviewed as no match;
- not yet reviewed.

`NO_MATCH` is a deliberate review result and must not be treated as equivalent to missing mapping data.

---

## Subtopic Mapping

### Requirement

A specific `SUBTOPIC -> SUBTOPIC` mapping mechanism is required.

Legacy question metadata may classify questions at either descriptor or subtopic level, so descriptor mappings alone are not sufficient.

### Inference from descriptor mappings

Confirmed descriptor mappings should be used to generate subtopic mapping suggestions.

Example:

```text
2019 Subtopic A
    Descriptor 1 -> 2025 Subtopic X / Descriptor 7
    Descriptor 2 -> 2025 Subtopic X / Descriptor 8
    Descriptor 3 -> 2025 Subtopic X / Descriptor 9
```

This provides strong evidence for:

```text
2019 Subtopic A -> 2025 Subtopic X
```

However, automatic confirmation is not appropriate.

A historical subtopic may split across multiple current subtopics:

```text
2019 Subtopic A
    Descriptor 1 -> 2025 Subtopic X
    Descriptor 2 -> 2025 Subtopic X
    Descriptor 3 -> 2025 Subtopic Y
```

This may legitimately imply:

```text
2019 Subtopic A -> 2025 Subtopic X
                  2025 Subtopic Y
```

Descriptor mappings may also be incomplete or include explicit no-match outcomes.

Therefore:

> Descriptor mappings should generate subtopic mapping suggestions, but a user must confirm the resulting subtopic mappings before they are persisted as confirmed mappings.

### Suggested evidence shown in the UI

The subtopic review interface should present evidence such as:

```text
Historical subtopic: Equilibrium

Suggested current subtopics:

Chemical equilibrium
- 5 confirmed descendant descriptor mappings lead here

Acid-base equilibria
- 2 confirmed descendant descriptor mappings lead here

Descriptor review coverage: 8 / 8
No-match descriptors: 1
```

Text similarity may be used as a secondary suggestion signal, but confirmed descriptor evidence should be the primary signal where available.

A user may still confirm a subtopic mapping when descriptor evidence is incomplete. The evidence guides review; it is not a persistence constraint.

---

## Question Classification and Applicability

### Original classification must be preserved

A historical question must retain the curriculum classification under which it was originally imported or created.

Example:

```text
Question 123
Original classification:
2019 Descriptor 3.2.4
```

The system must not rewrite this to a 2025 classification.

Original classification is source provenance and remains part of the historical record.

### Current applicability is derived

Current applicability is a separate concept.

Example:

```text
Question 123
Original:
2019 Descriptor 3.2.4

Confirmed mapping:
2019 Descriptor 3.2.4
    -> 2025 Descriptor 2.4.7

Derived current applicability:
2025 Descriptor 2.4.7
```

For a subtopic-classified historical question:

```text
Question 456
Original:
2019 Subtopic A

Confirmed mapping:
2019 Subtopic A
    -> 2025 Subtopic X

Derived current applicability:
2025 Subtopic X
```

The question record itself should not be rewritten merely because a curriculum mapping exists.

### Descriptor precision must not be invented

If a historical question is classified only at subtopic level and that subtopic maps to a current subtopic, the question must not automatically be assigned to every descriptor beneath the current subtopic.

Example:

```text
2019 Subtopic A
    -> 2025 Subtopic X
```

means the question is applicable to `2025 Subtopic X`.

It does not mean the system knows which descriptor under `2025 Subtopic X` the question specifically assesses.

A future workflow may allow a user to manually refine a historical question to one or more current descriptors, but that is outside this sprint.

---

## Sprint Work Packages

### Work Package 1: Enforce mapping direction

Harden existing mapping behaviour so that mappings are explicitly historical-to-current.

Required changes:

- target syllabus is the current syllabus;
- source syllabus choices are non-current versions for the same subject;
- the current syllabus cannot be selected as the historical source;
- persistence/service validation enforces the same rule independently of the UI;
- same-subject and different-version validation remains;
- existing valid 2019 -> 2025 mappings remain compatible.

Repository lookup methods may still support querying from either endpoint. For example, finding historical sources that lead to a current target is legitimate and does not make the mapping reversible.

Consider clearer naming such as:

```java
findOutgoingMappingsFrom(source)
findIncomingMappingsTo(target)
```

instead of names that could imply bidirectional semantics.

### Work Package 2: Generalise mapping review for subtopics

Extend the existing mapping review infrastructure to support both:

```text
DESCRIPTOR
SUBTOPIC
```

The database mapping record should be reused if practical.

Do not create separate descriptor and subtopic mapping tables unless the existing structure proves unsuitable.

The review workflow should continue to support:

- one-to-one;
- one-to-many;
- confirmed mapping;
- no match;
- editing an existing review.

### Work Package 3: Add inferred subtopic suggestions

Generate candidate target subtopics from confirmed descendant descriptor mappings.

The suggestion algorithm should:

- count confirmed descriptor mappings by target subtopic;
- identify splits across multiple target subtopics;
- expose review coverage;
- expose no-match descriptor counts;
- never persist a subtopic mapping automatically;
- allow human confirmation of one or more suggested target subtopics.

### Work Package 4: Add current-question applicability lookup

Add a read-only application/service/repository boundary for deriving current applicability from confirmed mappings.

The exact API should be designed during this work package, but responsibilities should include concepts such as:

```java
findCurrentApplicability(Question question)
```

and/or:

```java
findQuestionsApplicableTo(CurriculumNode currentNode)
```

Do not place mapping traversal logic inside the `Question` domain object.

For a current descriptor search, the eventual behaviour should be equivalent to:

```text
current descriptor
    -> questions directly classified there
    + historical descriptor mappings leading there
    -> questions classified under those historical descriptors
```

For current subtopics, applicability should include questions directly classified to the current subtopic and historical subtopic classifications that have confirmed mappings to it.

Search UI construction itself is not required in this sprint unless needed for a minimal proof of the applicability API.

---

## Out of Scope

The following should not be added during this sprint unless required to fix a defect introduced by the work above:

- rewriting historical question classifications to current syllabus nodes;
- assigning subtopic-classified questions automatically to current descriptors;
- manual per-question reclassification/refinement UI;
- Exam Builder UI;
- broad question search/filter UI;
- automatic transitive mapping across three or more syllabus generations;
- generic curriculum-version chronology modelling beyond what is needed to distinguish historical source from current target;
- unrelated capture UI improvements;
- preamble capture changes;
- general exam-import PDF-storage refactoring.

If another future syllabus is introduced, chained mapping such as:

```text
2019 -> 2025 -> future syllabus
```

should be designed explicitly rather than assumed during this sprint.

---

## Testing Expectations

The sprint should add or update tests covering at least the following.

### Direction

- historical source -> current target succeeds;
- current source -> historical target is rejected;
- source and target from different subjects are rejected;
- same syllabus as source and target is rejected;
- valid existing 2019 -> 2025 mappings continue to load.

### Descriptor mappings

- existing confirmed descriptor mappings remain unchanged;
- one-to-many descriptor mappings continue to work;
- no-match reviews remain distinguishable from unreviewed nodes.

### Subtopic mappings

- subtopic -> subtopic mapping succeeds;
- subtopic -> descriptor is rejected;
- descriptor -> subtopic is rejected;
- one-to-many subtopic mappings succeed;
- no-match subtopic review is supported;
- editing an existing subtopic review preserves transactional behaviour.

### Subtopic inference

- all mapped descendant descriptors leading to one target subtopic produces that target as the strongest suggestion;
- descriptor mappings split across target subtopics produce multiple suggestions;
- incomplete descriptor review is reported;
- no-match descendant descriptors are counted correctly;
- inferred suggestions are not automatically persisted as confirmed mappings.

### Question applicability

- directly current-classified questions remain applicable to their current node;
- historical descriptor-classified questions are applicable through confirmed descriptor mappings;
- historical subtopic-classified questions are applicable through confirmed subtopic mappings;
- suggested-only mappings do not affect applicability;
- no-match mappings do not create applicability;
- a subtopic-level mapping does not imply descriptor-level applicability;
- original question classification remains unchanged.

### Regression

Run the complete Maven and TestFX suites before merging.

---

## Acceptance Criteria

This sprint is complete when:

1. The application cannot create mappings in the wrong syllabus direction.
2. Existing descriptor mapping review continues to work.
3. Subtopic-to-subtopic mappings can be reviewed and confirmed.
4. Descriptor mapping evidence is used to suggest subtopic mappings.
5. Suggested subtopic mappings require user confirmation.
6. Historical questions retain their original classification.
7. Confirmed mappings can be used to derive current-syllabus applicability.
8. No current descriptor precision is invented for a question classified only at subtopic level.
9. The full automated test suite is green.
10. No unrelated feature expansion has been introduced.

## Design Summary

The central model for this sprint is:

```text
Original historical classification
            |
            v
Confirmed one-way curriculum mapping
            |
            v
Derived current applicability
```

The original classification remains authoritative provenance.

The mapping is explicitly historical-to-current.

Current applicability is derived from confirmed mappings and can later support question search and exam construction without corrupting the historical classification of imported questions.
