# Sprint Design: Question Retrieval (version 2)

## Branch

`feature/question-retrieval`

## Purpose

This sprint will turn the completed curriculum-applicability work into practical question retrieval.

The system can already determine the current curriculum applicability of a stored question without overwriting its original classification.

This sprint will add the inverse capability:

```text
current curriculum node
        |
        v
find all stored questions applicable here
```

The retrieval must work regardless of how a question entered the question bank.

Examples include:

- questions captured directly against the current syllabus;
- historical questions imported through the legacy metadata workflow;
- historical questions captured manually against an older syllabus.

The import mechanism is not part of retrieval semantics. Retrieval is based on the stored `Question` classification and confirmed curriculum mappings.

The sprint should establish the retrieval service/repository boundary first, prove it against SQLite, and then add a minimal curriculum-aware question-search UI.

---

## Core Design Principles

### 1. Search is against current curriculum applicability

The main use case is:

> Show me questions suitable for this part of the current syllabus.

A current curriculum search should therefore include both:

```text
questions directly classified to the current curriculum
+
historical questions connected through CONFIRMED mappings
```

Example:

```text
2025 Descriptor X
    |
    +-- Question A classified directly to 2025 Descriptor X
    |
    +-- 2019 Descriptor B -> 2025 Descriptor X
            |
            +-- Question B classified to 2019 Descriptor B
```

A search for `2025 Descriptor X` should return both Question A and Question B.

### 2. Original classification remains authoritative provenance

Retrieval must not rewrite a question's stored classification.

A result should remain able to distinguish:

```text
Original classification:
2019 Descriptor B

Current applicability:
2025 Descriptor X
```

The retrieval layer may derive applicability, but it must not mutate `Question`.

### 3. Only confirmed mappings affect retrieval

`CONFIRMED` mappings are authoritative.

The following must not cause a historical question to appear in current-syllabus results:

- `SUGGESTED` mappings;
- explicit `NO_MATCH` reviews;
- unreviewed mapping candidates.

### 4. Descriptor precision must not be invented

A historical question classified only to a subtopic remains subtopic-level evidence.

For example:

```text
2019 Subtopic A
    -> 2025 Subtopic X
```

allows the question to be retrieved for `2025 Subtopic X`.

It does not make the question applicable to every descriptor beneath `2025 Subtopic X`.

### 5. Hierarchical search semantics must be explicit

Searching for a broad curriculum area includes questions applicable to valid descendant question-classification nodes.

Questions themselves are classified only to `SUBTOPIC` or `DESCRIPTOR` nodes.

`TOPIC` and `UNIT` are search scopes. They are not question-classification levels and broad retrieval must not manufacture Topic-level or Unit-level applicability.

The supported search semantics are:

```text
DESCRIPTOR
    -> exact Descriptor only

SUBTOPIC
    -> that Subtopic
    + Descriptor children

TOPIC
    -> valid question-classification nodes beneath that Topic

UNIT
    -> valid question-classification nodes beneath all Topics in the Unit
```

Do not hide hierarchy expansion inside the mapping model or `Question` domain object.

### 6. Retrieval must not return duplicate questions

One question may be reachable through more than one valid path.

For example:

- one historical descriptor maps to multiple current descriptors under the same searched subtopic;
- several current applicability paths converge on the same broad curriculum area.

The result set must contain each question only once.

Where a question is applicable through multiple current nodes, those applicability nodes must still be preserved in the retrieval result.

### 7. SQLite is the real retrieval boundary

The production implementation should not load all questions into memory and filter them application-side unless a small diagnostic helper explicitly requires it.

The repository/service design should support efficient SQLite-backed retrieval.

Indexes should only be added where the retrieval queries demonstrate a real need.

---

# Sprint Work Packages

## Work Package 1 — Define the retrieval contract

### Goal

Establish a clear application-facing API for curriculum-aware question lookup.

Likely concepts include:

```java
findQuestionsApplicableTo(CurriculumNode currentNode)
```

or an equivalent query/service abstraction.

The exact API may evolve during implementation, but it should express current-curriculum retrieval rather than expose raw mapping traversal to UI code.

### Required behaviour

For a current descriptor:

```text
current descriptor
    -> questions directly classified there
    + historical descriptor mappings leading there
    -> questions classified under those historical descriptors
```

For a current subtopic:

```text
current subtopic
    -> questions directly classified there
    + historical subtopic mappings leading there
```

The hierarchical inclusion of descriptor-classified questions beneath a searched subtopic is defined in Work Package 2.

### Tasks

- define the application/service boundary;
- keep mapping traversal out of `Question`;
- reject or clearly define behaviour for non-current search nodes;
- preserve deterministic result ordering;
- define how provenance/current-applicability information is exposed to callers;
- add focused unit tests for the contract.

---

## Work Package 2 — Define and implement hierarchical curriculum search semantics

### Goal

Define explicit retrieval behaviour for all current curriculum search levels:

- Descriptor;
- Subtopic;
- Topic;
- Unit.

Questions themselves remain classified only to Descriptor or Subtopic nodes. Topic and Unit are search scopes, not question-classification levels.

### Descriptor search

A Descriptor search is exact.

```text
current Descriptor
    -> questions applicable to that Descriptor only
```

It must not include questions known only to the parent Subtopic.

### Subtopic search

A Subtopic search includes:

```text
current Subtopic
    -> questions applicable directly to that Subtopic
    + questions applicable to Descriptor children beneath it
```

This includes both direct current classifications and historical classifications connected through confirmed mappings.

A question known only at Subtopic level must not be treated as Descriptor-specific.

### Topic search

A Topic has exactly one of two valid hierarchy shapes.

#### Descriptor-mode Topic

All immediate children are Descriptors.

```text
Topic
    +-- Descriptor
    +-- Descriptor
    +-- Descriptor
```

Searching the Topic retrieves questions applicable to all of those Descriptors.

#### Subtopic-mode Topic

All immediate children are Subtopics.

```text
Topic
    +-- Subtopic
    |      +-- Descriptor
    |      +-- Descriptor
    |
    +-- Subtopic
           +-- Descriptor
```

Searching the Topic retrieves:

- questions directly applicable to each Subtopic;
- questions applicable to Descriptor children beneath those Subtopics.

Empty Subtopics are still retrieval nodes because questions may be classified directly to them.

A Topic must not contain a mixture of direct Descriptor children and Subtopic children. Such a hierarchy is invalid and retrieval expansion must reject it.

An empty Topic expands to no question-classification nodes.

### Unit search

A Unit search traverses all Topics beneath that Unit.

Each Topic independently follows either Descriptor-mode or Subtopic-mode semantics.

The Unit search therefore retrieves all questions applicable to valid Descriptor and Subtopic classification nodes beneath the Unit.

### Important constraints

Hierarchy expansion must not reverse applicability.

```text
Subtopic applicability
    does NOT imply
Descriptor applicability
```

Topic and Unit searches broaden the search scope only. They do not:

- change stored question classification;
- manufacture Topic or Unit applicability;
- invent Descriptor precision.

Hierarchy traversal belongs in the dedicated curriculum search-node expansion service, not in `Question`, the mapping model or UI code.

### Ordering

Hierarchy expansion follows `CurriculumRepository.findChildren(...)` ordering and traverses descendants depth-first.

Retrieval results retain the deterministic ordering defined by the retrieval service.

### Tasks

- implement exact Descriptor semantics;
- implement Subtopic plus Descriptor-child expansion;
- support both valid Topic hierarchy modes;
- reject mixed Descriptor/Subtopic Topic children;
- include empty Subtopics as searchable classification nodes;
- support Unit traversal across independently structured Topics;
- preserve original question classifications;
- prevent broad searches from inventing finer applicability;
- add tests for Descriptor, Subtopic, Topic and Unit behaviour.

---

## Work Package 3 — Implement SQLite-backed question retrieval

### Goal

Provide production retrieval against persisted questions and mappings.

### Retrieval sources

The query path must account for:

1. direct current classification;
2. historical classification with confirmed current mappings;
3. hierarchy expansion defined in Work Package 2.

### Tasks

- add the required repository query/query methods;
- join question classifications to curriculum nodes and mappings as appropriate;
- include only `CONFIRMED` mappings;
- exclude mappings whose target syllabus is no longer current;
- ensure same-subject/current-version rules remain respected;
- deduplicate questions reached through multiple valid mapping paths;
- preserve all valid current applicability nodes when a question has more than one;
- preserve stable ordering;
- reconstruct normal `Question` objects through existing repository boundaries rather than creating a parallel partial question model unless a clear performance need appears;
- inspect query plans before adding indexes;
- add indexes only if justified;
- add SQLite integration tests using fresh repository/service instances after writes.

### Restart requirement

The behaviour must work after application restart.

Tests should therefore:

```text
write questions/mappings
        |
        v
discard writer/service instances
        |
        v
construct fresh repositories/services
        |
        v
retrieve expected questions
```

---

## Work Package 4 — Prove direct and mapped retrieval across import origins

### Goal

Demonstrate that retrieval does not depend on the question-import workflow.

### Scenarios

Tests should cover at least:

- a question captured directly against the current descriptor;
- a current subtopic-classified question;
- a legacy-imported historical descriptor question with a confirmed current descriptor mapping;
- a legacy-imported historical subtopic question with a confirmed current subtopic mapping;
- a historical question with only a suggested mapping;
- a historical question with a `NO_MATCH` review;
- a historical question mapped to a non-current syllabus;
- multiple historical source nodes mapping to the same current node;
- one historical question reachable through multiple current descendants of a searched subtopic;
- duplicate prevention;
- original classification remaining unchanged after retrieval.

Where practical, use the real legacy-import persistence path for at least one integration scenario rather than fabricating every question row directly in SQL.

---

## Work Package 5 — Add a minimal curriculum-aware question search UI

### Goal

Expose the retrieval capability without attempting the full Question Bank Browser sprint.

### Minimum UI

The user should be able to:

1. choose a subject;
2. work against the current syllabus;
3. navigate/select a current curriculum area;
4. run or automatically refresh the search;
5. see matching questions;
6. see enough source metadata to identify each result;
7. preview the stored question;
8. inspect its original classification and current applicability;
9. open or identify the original source examination where practical.

### Result display

A result should make useful provenance visible without overwhelming the list.

For example:

```text
QCAA 2020 — Paper 1 — Q3 — 3 marks
Original: 2019 Descriptor 3.2.4
Current: 2025 Descriptor 2.4.7
```

For a current-direct question:

```text
QCAA 2026 — Paper 1 — Q5 — 4 marks
Current: 2025 Descriptor 2.4.7
```

### Out of scope for this UI slice

Do not require:

- full question editing;
- saved searches;
- favourites;
- exam-builder selection;
- sophisticated multi-filter combinations;
- analytics;
- bulk actions.

These belong to later Phase 7 work unless a tiny supporting piece is necessary for the minimal UI.

---

## Work Package 6 — Quality gate and merge readiness

### Goal

Make this retrieval boundary reliable enough to become the foundation of the full browser.

### Required review

Before merge:

- review the full branch against `main`;
- check public API Javadocs;
- add missing unit tests;
- add SQLite integration tests;
- add JavaFX/TestFX tests for important UI state;
- run the complete Maven suite;
- manually exercise realistic retrieval using the development database.

### Particular review risks

Look for:

- duplicate question results;
- accidental inclusion of suggested mappings;
- accidental descriptor precision from subtopic mappings;
- incorrect handling when the current syllabus changes;
- stale/reversed mapping assumptions;
- query behaviour that differs after restart;
- N+1 query behaviour or full-bank in-memory filtering;
- UI code directly traversing mappings;
- mutation of stored question classification;
- search semantics that differ incorrectly between Descriptor, Subtopic, Topic and Unit paths;
- result ordering that changes unpredictably.

---

# Testing Expectations

The sprint should add or update tests covering at least the following.

## Direct current retrieval

- a question classified directly to a current descriptor is returned for that descriptor;
- a current descriptor search does not return unrelated current questions;
- a current subtopic-classified question is returned for that subtopic.

## Historical descriptor applicability

- a historical descriptor question is returned through a confirmed mapping;
- a one-to-many historical descriptor mapping can make a question applicable to multiple current descriptors;
- `SUGGESTED` mappings do not return questions;
- mappings to non-current syllabus versions do not return questions;
- `NO_MATCH` does not return questions;
- original historical classification remains unchanged.

## Historical subtopic applicability

- a historical subtopic question is returned through a confirmed subtopic mapping;
- one-to-many subtopic mappings are respected;
- a subtopic mapping does not make the question descriptor-specific.

## Hierarchical search

- Descriptor searches are exact;
- Descriptor searches do not include questions known only at parent Subtopic level;
- Subtopic searches include direct Subtopic questions;
- Subtopic searches include questions applicable to descendant Descriptors;
- historical Descriptor questions mapped to descendant current Descriptors are included;
- Subtopic applicability does not imply Descriptor applicability;
- Descriptor-mode Topic searches include all direct Descriptor children;
- Subtopic-mode Topic searches include direct Subtopic questions and descendant Descriptor questions;
- mixed Descriptor/Subtopic Topic hierarchies are rejected;
- empty Subtopics remain searchable;
- empty Topics produce no retrieval classification nodes;
- Unit searches traverse all Topics using each Topic's valid hierarchy mode;
- a question reachable through multiple descendants appears only once;
- multiple valid applicability nodes for one returned question are preserved.

## Persistence/restart

- direct current retrieval works after repository reconstruction;
- descriptor-mapped retrieval works after repository reconstruction;
- subtopic-mapped retrieval works after repository reconstruction;
- Topic and Unit hierarchy retrieval works after repository reconstruction;
- edited mapping reviews immediately change retrieval after reconstruction;
- MATCHED -> NO_MATCH removes the question from current applicability results after reload;
- NO_MATCH -> MATCHED restores the question after reload;
- one-to-many mappings retain all current applicability nodes after reload.

## UI

- selecting a current descriptor displays direct and mapped questions;
- selecting a current subtopic follows the agreed hierarchical semantics;
- selecting a Topic or Unit follows the agreed broad-search semantics;
- result provenance is displayed correctly;
- empty result sets are clear and not presented as errors;
- changing curriculum selection refreshes or invalidates stale results;
- a mapped historical result retains its original classification in the UI.

## Regression

Run the complete Maven and TestFX suites before merging.

---

# Guidelines for Success

## 1. Keep retrieval semantics simple and explicit

The sprint succeeds if a teacher can understand why a question was returned.

Every result should be explainable as either:

```text
direct current classification
```

or:

```text
historical classification
    -> confirmed mapping
    -> current applicability
```

Hierarchy expansion should add another explicit, testable rule rather than implicit behaviour.

## 2. Do not corrupt provenance

A historical question must still say it was historically classified.

Search convenience must never change stored classification.

## 3. Prefer repository/service logic over UI logic

The JavaFX UI should ask for applicable questions.

It should not know how to:

- traverse mapping tables;
- interpret `CONFIRMED` versus `SUGGESTED`;
- expand curriculum hierarchy;
- deduplicate results.

Those are application/repository responsibilities.

## 4. Prove real persistence behaviour

An in-memory test is useful for service contracts but is not sufficient for retrieval.

Critical retrieval cases must be proven against SQLite.

## 5. Avoid premature search complexity

Do not turn the sprint into a generic search framework.

The first target is curriculum-aware retrieval.

Other filters such as year, provider, marks and question text can be layered on later once the core retrieval boundary is sound.

## 6. Avoid premature performance engineering

Use sensible SQL and inspect behaviour with realistic development data.

Do not add caching, search indexes or new infrastructure unless measurements show a need.

## 7. Preserve subject neutrality

Chemistry provides the development data, but retrieval logic must work from Subject, SyllabusVersion and CurriculumNode relationships rather than Chemistry-specific codes or assumptions.

## 8. Keep the full test suite green

A retrieval feature that destabilises capture, legacy import or curriculum review is not complete.

## 9. Use realistic manual testing

Before merge, manually verify at least:

- a directly current-classified question;
- a legacy 2019 descriptor question mapped to 2025;
- a historical subtopic question mapped to a current subtopic;
- a suggestion-only mapping that does not produce a result;
- a no-match review that does not produce a result;
- a broad Subtopic search that demonstrates descendant Descriptor retrieval;
- a Topic search;
- a Unit search.

## 10. Stop at the sprint boundary

Do not begin the full editing workflow or Exam Builder merely because retrieved questions are now visible.

The next sprint can build the richer Question Bank Browser on top of this stable retrieval boundary.

---

# Out of Scope

The following should not be added during this sprint unless required to fix a defect introduced by the work above:

- rewriting historical classifications;
- manual per-question current reclassification/refinement;
- full question editing UI;
- saved searches or favourites;
- question tagging;
- free-text search;
- difficulty classification;
- exam-builder selection and ordering;
- assessment construction;
- output generation;
- AI classification;
- automatic transitive mapping across three or more syllabus generations;
- broad migration changes;
- preamble pinning;
- unrelated capture UI refinements.

---

# Acceptance Criteria

This sprint is complete when:

1. A current descriptor can retrieve questions classified directly to it.
2. A current descriptor can retrieve historical descriptor questions through confirmed mappings.
3. Suggested mappings and no-match reviews cannot create retrieval results.
4. A current subtopic can retrieve questions through confirmed subtopic mappings.
5. Descriptor, Subtopic, Topic and Unit searches follow the explicit hierarchy rules and are tested.
6. Subtopic-level applicability does not invent descriptor-level applicability.
7. A question is returned only once even when multiple valid applicability paths reach the searched curriculum area.
8. Multiple valid current applicability nodes for a returned question are preserved.
9. Retrieval works from persisted SQLite data after fresh repository/service construction.
10. Import technique does not affect retrieval semantics.
11. Historical question classifications remain unchanged.
12. A minimal UI allows a teacher to select current curriculum and see matching questions.
13. The UI can distinguish original historical classification from current applicability where relevant.
14. The full automated test suite is green.
15. The sprint has not expanded into full question editing or Exam Builder work.

---

# Definition of Done

A teacher can select a current Descriptor, Subtopic, Topic or Unit and see the stored questions applicable within that search scope, regardless of whether those questions were originally classified against the current syllabus or a historical syllabus.

The system can explain each historical result through confirmed curriculum mappings without changing the original question record.

Broad Topic and Unit searches expand only to valid Descriptor and Subtopic classification nodes and do not manufacture broader stored applicability or finer Descriptor precision.

The resulting retrieval API is suitable for reuse by the later Question Bank Browser, hierarchical SCORM/HTML output and Exam Builder selection workflows.

---

# Design Summary

The central retrieval model for this sprint is:

```text
Current curriculum search node
        |
        +-- explicit hierarchy expansion
        |
        +-- direct current classifications
        |
        +-- confirmed historical mappings
        |
        v
Unique applicable Questions
        |
        +-- original classification preserved
        |
        +-- all valid current applicability nodes preserved
```

This sprint converts curriculum applicability from a per-question concept into a practical question-bank retrieval capability.
