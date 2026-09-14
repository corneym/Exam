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

The new authoring workflow must not infer node type from the number of components in a dotted code. Codes identify curriculum nodes; they do not define the hierarchy.

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
    code
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

No hierarchy decision may depend upon counting dots in the curriculum code.

Outcome: curriculum hierarchy can be constructed independently of the source document's numbering convention.

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
    Edit wording/code as required
          ↓
    Reorder nodes
          ↓
    Review the complete draft tree
          ↓
    Validate
          ↓
    Save

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

For a legacy question, allow correction of the metadata that can legitimately have been wrong in the old workbook, including:

    question code
    marks
    curriculum classification
    legacy preamble-required flag

Exam/booklet/source-document identity remains fixed by this operation.

The important preamble rule is bidirectional:

    false → true
    true → false

Changing the legacy hint from true to false means:

> Inspection of the original question shows that the legacy capture hint was a false positive.

It must not mean “delete any real shared context which has subsequently been captured”.

Likewise, setting the hint to true establishes that shared/introduction material still needs to be dealt with; it does not itself invent or capture that material.

The implementation needs a metadata-only persistence path so a metadata-only imported question can be corrected before it has question regions. It should not force such a question through the normal Question Edit operation, which currently assumes replacement regions.

Add explicit regression coverage for an MCQ question requiring introductory/shared material. Begin by reproducing the current observed MCQ problem: the current workbook parser itself does not impose a Paper 1/Paper 2-only preamble rule, so no parser redesign should be made unless that regression demonstrates one is required.

Outcome: inaccurate historical capture hints can be corrected safely, and MCQ preambles are supported on the same semantic basis as other questions.

### Slice 7 — Corpus audit queue and completeness reporting

Replace the narrow idea of “questions awaiting capture” with a broader question-bank work queue.

A question should be auditable for independent completion dimensions, including:

    question source regions
    answer source regions
    unresolved shared-context/preamble decision
    classification
    source/booklet identity

The queue should support useful filters such as:

    Subject
    provider
    year
    booklet
    completion/problem state

It should distinguish, rather than collapse, cases such as:

    no question regions
    no answer regions
    neither captured
    shared context still unresolved
    complete

Legacy answer text should not be mistaken for captured answer source provenance. A question can have imported answer text and still lack answer regions.

Selecting a work item should take the user into the appropriate existing capture/edit workflow rather than creating a second capture system.

Add summary totals so the same facility answers questions such as “How many questions are completely captured?” and “How many unresolved preambles remain?”

Outcome: corpus completion is measurable and the application itself provides the work list required to finish it.

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

Legacy question metadata can be corrected before source-region capture, including changing the legacy preamble hint in either direction.

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
