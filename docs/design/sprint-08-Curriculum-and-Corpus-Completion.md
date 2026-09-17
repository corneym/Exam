# Sprint 08 — Curriculum and Corpus Completion

## Purpose

Sprint 08 moves the application from having the core question-bank architecture in place to being able to build, audit and complete real curriculum and question-bank data using the application itself.

The sprint has two connected goals:

1. Make curriculum creation and mapping genuinely subject-neutral rather than dependent on Chemistry workbook conventions.
2. Provide the tools needed to determine whether curriculum mappings and the question corpus are actually complete.

The sprint should also close several small correctness gaps exposed by sustained use of the capture and search workflows.

## Core design principles

Implementation checkpoint (15 September 2026): production authoring now includes
creation/opening, transactional node persistence, managed syllabus-PDF attachment
and explicit finalise/reopen transitions. The stable persistence contracts and
documentation deferred pending blocker fixes are recorded in
[`current-status.md`](../current-status.md#resumable-curriculum-authoring--sprint-08).
This checkpoint does not declare the sprint acceptance criteria complete or
rescope the remaining mapping, metadata and corpus work.

Curriculum structure is expert-authored.

A syllabus PDF is source material, not an authority from which the application automatically infers curriculum structure. The application may extract text and retain page references, but the subject-matter expert explicitly decides whether each node is a Unit, Topic, Subtopic or Descriptor and explicitly chooses its parent.

Subtopic is optional.

Both of these structures are first-class:

    Unit
      → Topic
          → Subtopic
              → Descriptor

and:

    Unit
      → Topic
          → Descriptor

Curriculum codes use the hierarchical numeric form used by the supported syllabuses, for example `1`, `1.1`, `1.1.1` and `1.1.1.1`.

The authoring workflow generates codes automatically from the explicitly authored parent relationship. A newly created node receives the lowest available positive child number beneath its parent. Existing node codes remain stable during ordinary editing: moving or deleting a node does not renumber surviving nodes. Deletion may therefore leave a temporary numbering gap, and a later node created beneath the same parent may reuse that available number.

Node type and parent relationships remain explicit authoring decisions. The application must not decide whether a node is a Unit, Topic, Subtopic or Descriptor merely from the number of components in its dotted code.

SQLite remains authoritative application state.

The existing Excel curriculum files remain import/exchange artefacts. The Chemistry 2019 → 2025 mapping workbook remains useful historical/reference analysis, but it is not authoritative application mapping state and Sprint 08 will not reconcile application mappings against it.

Curriculum mappings become authoritative only through the application's human-reviewed mapping workflow.

Legacy import metadata is evidence, not immutable truth.

The legacy `preambleCaptureRequired` value recorded what the old application believed was required for capture. Sprint 08 must permit that evidence to be corrected when inspection of the original paper shows it was wrong. Correcting that flag must remain conceptually separate from the existence of an actually captured `SharedQuestionContext`.

PDF authoring and Excel import are separate input workflows feeding the same curriculum domain and persistence model. Sprint 08 should not replace the working Excel importer merely to accommodate PDF authoring.

## Work slices

### Slice 1 — Sprint baseline and hierarchy regression

First establish the behaviour that already ought to work before building the new curriculum UI.

Add focused regression coverage for a syllabus containing:

    Unit → Topic → Descriptor

with no Subtopics.

Exercise the existing domain hierarchy, SQLite persistence/loading and curriculum-selection behaviour. Extend this far enough to expose assumptions in classification and retrieval rather than merely proving that a `Descriptor` object can be constructed.

The existing Excel importer must remain green.

This slice also updates the planning documents so that the previous “reconcile the 5 September mapping workbook with SQLite” requirement is superseded by UI-authoritative mapping completion and coverage reporting.

Outcome: we know which parts of the existing application already support a no-Subtopic syllabus and which genuinely require changes.

### Slice 2 — Explicit curriculum-authoring draft model

Introduce a transient authoring representation that describes curriculum structure explicitly.

Each draft node needs, conceptually:

    node type
    automatically generated hierarchical code
    text/name
    explicit parent
    sibling/display order
    source page/reference where available
    
Validation must enforce legal domain relationships:

    Unit → Topic
    Topic → Subtopic
    Topic → Descriptor
    Subtopic → Descriptor

It must reject orphaned nodes, illegal parent types, duplicate codes within a syllabus and other structures that cannot become valid persisted curriculum nodes.

The explicit parent relationship determines curriculum structure. Code generation follows that authored structure, but code depth must not be used to infer node type.

Existing codes are not compacted or renumbered merely because another node is moved or deleted. When a new node is added, the lowest available child number beneath its parent is used.

Outcome: curriculum hierarchy is explicitly authored while conventional hierarchical curriculum numbering is generated automatically and remains stable during correction.

### Slice 3 — PDF-assisted curriculum authoring

Add the guided authoring workspace.

The workflow is:

    Select syllabus PDF
          ↓
    View/search/select syllabus text
          ↓
    Create an explicitly typed curriculum node
          ↓
    Choose its parent
          ↓
    Edit wording as required
          ↓
    Reorder nodes
          ↓
    Review the complete draft tree
          ↓
    Validate
          ↓
    Save

Curriculum codes are assigned automatically. The user does not need to enter or maintain hierarchical numbering manually. If an incorrectly authored node or branch is deleted, surviving codes remain unchanged and a subsequently created replacement may reuse the lowest available number beneath that parent.

The user must be able to create either a new Subject or, importantly, a new SyllabusVersion belonging to an existing Subject.

The PDF should be retained as syllabus provenance, and selected/extracted material should retain enough page information to locate its source again.

The application may assist by copying selected PDF text into the proposed node. It must not decide that a heading is a Unit, Topic, Subtopic or Descriptor.

Outcome: a subject-matter expert can construct a syllabus hierarchy from its authoritative PDF without first manufacturing an Excel workbook.

### Slice 4 — Resumable Curriculum Persistence and Non-Chemistry Acceptance Syllabus

Persist curriculum authoring as an editable, resumable workflow rather than a one-shot import.

The persistence model must support both newly authored curricula and curricula that already exist in SQLite because they were imported from Excel.

The following requirements apply:

- curriculum authoring is resumable rather than one-shot;
- an existing Excel-imported syllabus can be opened and edited in place;
- existing persistent curriculum-node IDs must be preserved when existing nodes are edited;
- the authoritative source PDF becomes a managed application file;
- source-page provenance is persisted where available;
- curriculum text must preserve authored content exactly, including future Markdown/LaTeX markup;
- each syllabus curriculum has an `IN_PROGRESS` or `FINAL` lifecycle state;
- a `FINAL` curriculum is not directly editable and must be explicitly reopened for editing, returning it to `IN_PROGRESS`;
- `Unit → Topic → Descriptor` and `Unit → Topic → Subtopic → Descriptor` are alternative syllabus structures and must not be mixed within the same syllabus version;
- further PDF-capture refinements, including region-based extraction, formula/image detection and maths-rendering assistance, remain backlog items and are not blockers for persistence.

For newly authored curricula, the workflow must support:

    select authoritative syllabus PDF
          ↓
    author part or all of the curriculum
          ↓
    save
          ↓
    copy the PDF into managed curriculum storage
          ↓
    persist the hierarchy and source-page provenance
          ↓
    close
          ↓
    reopen the syllabus later
          ↓
    continue editing
          ↓
    save again
          ↓
    optionally mark the curriculum FINAL

For an existing Excel-imported syllabus, the workflow must support:

    select existing Subject / SyllabusVersion
          ↓
    load the existing persisted hierarchy
          ↓
    preserve all existing curriculum-node IDs
          ↓
    optionally attach the authoritative syllabus PDF
          ↓
    copy the PDF into managed curriculum storage
          ↓
    edit, add, reorder or remove curriculum nodes as permitted
          ↓
    save changes in place
          ↓
    optionally mark the curriculum FINAL

Existing Excel-imported curricula must initially remain `IN_PROGRESS`. Successful import does not imply that the curriculum wording and hierarchy have been manually checked against the authoritative syllabus.

The managed source PDF should be stored beneath the curriculum data root using a subject/version-specific structure such as:

    data/
    └── curriculum/
        └── <subject>/
            └── <syllabus-version>/
                └── sources/
                    └── <authoritative-syllabus>.pdf

The database should store a managed relative path rather than the original external filesystem path.

Existing persisted curriculum nodes must be updated in place rather than deleted and recreated. This is necessary because questions, mappings and other persisted data may already refer to their database IDs.

Newly created draft nodes have no persistent curriculum-node ID until first save. The authoring session must therefore maintain a relationship between transient draft identity and persistent database identity.

Deleting an existing persisted curriculum node must be guarded appropriately when that node is already referenced by questions, mappings or other application data.

Marking a curriculum `FINAL` must first run structural validation. A final curriculum represents a syllabus whose hierarchy, numbering and wording have been checked and accepted as authoritative application data. Reopening a final curriculum for editing must explicitly return it to `IN_PROGRESS`.

Use one real non-Chemistry syllabus, such as Engineering or Psychology, whose structure includes:

    Unit
      → Topic
          → Descriptor

with no Subtopics.

This becomes the Sprint 08 subject-neutral acceptance dataset.

After persistence, exercise the syllabus through the existing application rather than stopping at successful storage or reload:

    curriculum selection/classification
    question capture
    Question Search/retrieval
    revision HTML generation
    SCORM generation

Any failure caused by an assumption that every Topic has Subtopics is a Sprint 08 defect.

This slice does not require a complete Engineering or Psychology question bank. The objective is to prove that the application architecture supports a real second syllabus structure, can persist and resume curriculum authoring safely, and can edit both newly authored and previously imported curriculum data without losing persistent identity.

**Outcome:** curriculum persistence is resumable and editable; authoritative source provenance is retained; finalisation state is explicit; existing imported curricula can be corrected in place; and subject neutrality is demonstrated against a real non-Chemistry syllabus rather than inferred from unit tests alone.


### Slice 5 — Mapping coverage and deliberate mapping completion

Add pair-specific mapping coverage reporting for the mapping levels currently supported by the review workflow: Descriptor and Subtopic.

For a selected historical syllabus and current target syllabus, report at least:

    total historical source nodes
    confirmed/matched
    explicit no-match
    unreviewed
    percentage deliberately reviewed
    current target nodes with no confirmed predecessor from that historical version

“Unreviewed” means no persisted review outcome. Absence from the mapping table must never silently mean “no equivalent”.

Use this report to work systematically through the existing mapping review UI until the relevant historical curriculum has either confirmed mapping(s) or an explicit no-match decision.

Do Descriptors first, then Subtopics.

Topic mapping is not added merely for symmetry. The current mapping-review workflow supports Descriptor and Subtopic review, and Sprint 08 should only introduce Topic review if a concrete retrieval or applicability requirement demonstrates that it is needed.

The separate Chemistry mapping workbook can be consulted by the teacher as reference material, but it does not determine application state and there is no workbook reconciliation pass.

Outcome: mapping completeness becomes measurable and every relevant historical node can be shown to have received a deliberate human decision.

### Slice 6 — Legacy question metadata correction and preamble handling

Add an Edit Metadata action to the imported/legacy Question workflow.

For a legacy question, allow correction of the metadata that can legitimately
have been wrong in the old workbook, including:

    question code
    marks
    curriculum classification
    response type
    legacy preamble-required flag

Exam/booklet/source-document identity remains fixed by this operation.

The legacy `preambleCaptureRequired` value remains historical capture evidence,
not immutable truth. It may be corrected in either direction:

    false → true
    true → false

Setting the legacy hint to true records that introductory/shared material still
needs to be dealt with. It does not itself invent a multipart source-question
identity, create a shared context or capture any source material.

Changing the hint from true to false requires different handling depending on
the corrected question identity.

For a resulting single-part question with no captured shared context, the
metadata flag is simply corrected.

For a resulting single-part question with a captured shared context, the
correction must preserve the captured source material rather than discard it.
The metadata correction transaction therefore:

1. copies the shared-context regions into the question as leading ordinary
   question regions, preserving their order;
2. appends the existing ordinary question regions, preserving their order;
3. sets `preamble_capture_required` to false;
4. clears the question's `shared_context_id`;
5. removes an obsolete multipart source-question relationship where required;
6. deletes the former shared context only if no other question still references
   it; and
7. commits all metadata and region changes atomically.

The converted regions are not geometrically merged or deduplicated. They are a
safe persisted representation of the material captured by the old workflow.

If another question still references the shared context, that shared context
and its regions must remain intact. The corrected single-part question receives
its own copied ordinary regions and is unlinked from the context.

For a resulting multipart question, a captured shared context represents
source-question/group material rather than material owned by only one part.
Changing one part from `preambleCaptureRequired=true` to false while that shared
context remains attached is therefore rejected. An already-false multipart
question may still receive unrelated metadata corrections.

If a multipart question is corrected to an ordinary single-part question in
the same metadata operation, the resulting identity is single-part and the
shared-context conversion rules apply.

Metadata correction and shared-context conversion form one SQLite transaction.
Any failure, including a late shared-context cleanup failure, must restore the
original metadata, question regions, shared-context relationship and context
regions.

After a successful shared-context conversion, the service reports that
conversion explicitly to the UI. The user is then offered:

    Recapture complete question
    Keep converted regions

Choosing `Keep converted regions` leaves the safe converted representation in
place.

Choosing `Recapture complete question` enters the existing Question Edit
workflow with an empty transient region list. The converted regions remain
persisted until a replacement question capture is successfully saved.
Cancelling recapture or failing to save therefore leaves the converted state
intact.

The implementation includes a metadata-only persistence path so an imported
question with no question regions can be corrected without being forced
through ordinary Question Edit.

Explicit regression coverage also confirms that an MCQ question may legitimately
have `preambleCaptureRequired=true` without inventing multipart
source-question identity. The current workbook parser does not impose a
Paper 1/Paper 2-only preamble rule, so no parser redesign is required.

Outcome: inaccurate historical metadata can be corrected safely; obsolete
legacy preamble captures can be migrated without loss; genuine multipart shared
context remains protected; optional full-question recapture is safe; and MCQ
preambles use the same semantics as other questions.

### Slice 7 — Corpus audit queue and completeness reporting

Replace the narrow idea of “questions awaiting capture” with a broader
question-bank completeness model and work queue.

#### Persisted response type

Question response type is persisted explicitly rather than inferred from booklet
naming.

The supported values are:

    MULTIPLE_CHOICE
    WRITTEN_RESPONSE
    UNKNOWN

Schema version 8 adds the persisted response type.

Existing Questions migrate to `UNKNOWN`, except where the exact legacy
`MCQ booklet` identity provides reliable evidence for `MULTIPLE_CHOICE`.

Legacy workbook import similarly treats paper code `MCQ` as
`MULTIPLE_CHOICE`.

Paper 1/Paper 2 naming does not imply `WRITTEN_RESPONSE`, and an A/B/C/D answer
value is not used to infer response type.

New manual Question capture requires an explicit choice between multiple choice
and written response. Existing imported/migrated Questions may remain `UNKNOWN`
until reviewed. Response type can also be corrected through Edit Metadata.

Changing response type does not delete an existing Answer, Answer text or
Answer regions.

#### Answer-completeness semantics

Answer completeness depends upon the persisted Question response type.

For `MULTIPLE_CHOICE`:

- the authoritative Answer is one of A, B, C or D;
- an Answer source region is not required;
- the existing A/B/C/D controls are used;
- ordinary Answer-PDF/region controls are hidden;
- any historical Answer regions already present remain persisted and are not
  silently deleted.

For `WRITTEN_RESPONSE`:

- at least one persisted Answer region is required;
- legacy/imported Answer text alone is not sufficient evidence that the Answer
  source has been captured;
- A/B/C/D controls are hidden;
- Answer-PDF and region capture remain available.

For `UNKNOWN`:

- Answer capture is disabled until response type is resolved;
- the unresolved response type itself is the actionable corpus problem;
- the same Question is not additionally reported as `MISSING_ANSWER`, because
  the required Answer representation is not yet known.

#### Corpus audit model

A normal persisted Question is audited independently for:

    question source captured
    response type resolved
    answer complete
    shared context resolved

Booklet/source identity and classification are already structural Question-domain
requirements and are not represented as nullable normal corpus states. Database
corruption or integrity auditing remains a separate concern.

The implemented actionable corpus problems are:

    MISSING_QUESTION_SOURCE
    MISSING_ANSWER
    UNRESOLVED_SHARED_CONTEXT
    UNKNOWN_RESPONSE_TYPE

A Question is complete only when no corpus problems remain.

#### Work queue and reporting

The corpus audit UI provides filters for:

    Subject
    provider
    year
    booklet
    completion state
    specific problem

It reports summary totals including:

    total Questions
    complete Questions
    incomplete Questions
    missing question source
    missing Answer
    unresolved shared context
    unknown response type

Subject/provider/year/booklet establish the reporting scope. Completion/problem
filters narrow the displayed work list without changing the scope-wide summary.

Selecting an incomplete work item routes into the existing correction/capture
workflows rather than creating a second capture implementation.

Resolution priority is:

    UNKNOWN_RESPONSE_TYPE
        -> Edit Metadata

    MISSING_QUESTION_SOURCE
    or UNRESOLVED_SHARED_CONTEXT
        -> existing Question/imported capture workflow

    MISSING_ANSWER
        -> existing Answer capture/edit workflow

This ordering ensures that response identity is resolved before Answer
requirements are interpreted, and Question/shared-context source work is resolved
before Answer work when several problems coexist.

The former imported-question queue remains available for focused source capture,
but corpus completeness is now represented by the broader audit facility.

Outcome: Question response semantics are explicit and persisted, Answer
completeness is response-type aware, corpus completion is measurable, and the
application provides a filtered work queue that routes outstanding work through
the existing safe capture and correction workflows.

### Slice 8 — Regression and capture hardening

Finish the outstanding asynchronous Question Search regression coverage identified in the existing backlog, including the important stale-result/lifecycle/error cases that are not yet exercised.

Also address the small Full-width-selection defect: invoking Full Width must clear or replace any pending selection state consistently so that the visible PDF selection and the Question/Answer capture state cannot disagree.

These remain separate regression slices; they should not be mixed into the curriculum-authoring implementation.

Outcome: known high-priority correctness debt is not carried into the next development phase.

## Sprint acceptance criteria

Sprint 08 is complete when all of the following are true.

A real syllabus PDF can be used as the source for expert-authored curriculum without automatic hierarchy inference.

An authored syllabus can contain Topic-level Descriptors without Subtopics and persists/reloads correctly.

At least one real non-Chemistry syllabus using that structure has been exercised through classification, capture, retrieval and revision/SCORM output.

The existing Excel curriculum workflow remains operational.

Mapping coverage can distinguish matched, explicit no-match and unreviewed source nodes for Descriptor and Subtopic review.

For the chosen Chemistry historical/current mapping pair, deliberate review completeness can be demonstrated from application state rather than comparison with the external workbook.

Legacy question metadata can be corrected before source-region capture, including changing the legacy preamble hint in either direction; obsolete captured single-part preambles are converted without source-material loss, while multipart shared context is protected from per-part removal.

An MCQ with genuine introductory/shared material has explicit regression coverage and works through the intended capture workflow.

The corpus queue can identify outstanding question regions, answer regions and unresolved shared context and can filter the work meaningfully.

The remaining agreed asynchronous Question Search regression cases are covered.

Full-width selection no longer leaves inconsistent pending-selection state.

The Sprint 08 design, current status, backlog and roadmaps reflect implemented behaviour and the revised mapping policy.

## Explicitly outside Sprint 08

Automatic interpretation of arbitrary syllabus-PDF structure is rejected by design, not postponed.

Topic-to-Topic mapping is not added unless implementation work exposes a concrete need for it.

Automatic detection of shared preambles across multiple PDF pages remains separate future work.

The historical Chemistry mapping workbook is not imported or reconciled into application mapping state.

Interactive student responses in revision HTML/SCORM remain future work. That feature needs its own design around question-level response type and, for multiple-choice questions, an authoritative representation of choices/options rather than merely captured question and answer images.

The broader Exam Builder remains later roadmap work.

## Documentation consequences

The Sprint 08 documentation pass should update:

`docs/current-status.md`

`docs/design/backlog.md`

`docs/roadmap.md`

`docs/DEVELOPMENT_ROADMAP.md`

and add the Sprint 08 design document alongside the existing Sprint 01–07 design records.

In particular, the workbook-reconciliation wording should be replaced with application-authoritative mapping review and coverage reporting.

The enduring architectural rule should be recorded explicitly:

> Curriculum structure is expert-authored. PDF extraction may assist entry but must not infer authoritative Unit, Topic, Subtopic or Descriptor relationships. Optional hierarchy levels such as Subtopic must remain optional, and the authoritative syllabus source must remain traceable for later review.

## Branch

Recommended branch:

`feature/data-completion`

The two-word scope, “data completion”, covers both halves of the sprint: constructing and validating curriculum data, and auditing/completing the question corpus.
