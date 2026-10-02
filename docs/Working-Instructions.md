## Working instructions for this chat

This chat is the working chat for the next sprint of the Exam Question Bank project.
THESE INSTRUCTIONS ARE IMPERATIVES.  DO NOT DEVIATE.
RE-READ THESE INSTRUCTIONS before every code change.  Follow the instructions explcitly.

### Repository Working Rule

Before giving me any code change for the Exam Question Bank:

Read docs/Working-Instructions.md from the current working branch. Do this for every code-change response, even if you have read it earlier in the conversation.

Re-read the requirements I gave for the current feature or slice. Treat my stated behaviour and layout requirements as acceptance criteria.

Inspect the current implementation and relevant tests on the current branch. Do not rely only on conversation summaries, previous proposed code, or assumptions about what I have applied.

Before writing code, check that the proposed change satisfies every relevant acceptance criterion. Do not silently reinterpret or weaken a requirement.

Tests must verify the acceptance criteria, not merely verify the implementation you happened to choose.

When I report that tests are green, do not automatically declare the slice complete. Compare the implemented behaviour against the original acceptance criteria first.  If I need to inspect the application visually, YOU MUST LET ME KNOW.

If an earlier instruction from you was wrong or incomplete, explicitly correct it rather than building later work on top of it.

Do not move on to the next feature while an earlier stated requirement remains unimplemented.

Never give me code based solely on the conversation summary when the repository can be inspected.

Follow the code-change format in docs/Working-Instructions.md exactly.

When giving Markdown text to copy make sure it is delimited with 4 backticks so it is directly copyable as MD.

### Source of truth

The GitHub repository is:

corneym/Exam

GitHub remains authoritative for established state, but during one continuous implementation sequence you can rely on the exact edits you have applied and reported green until the next checkpoint push.

Before giving exact implementation instructions that depend on current classes,
methods, schemas, tests, documentation, or branch state, inspect the current
repository rather than relying on remembered versions of files.

If I tell you that I have committed, pushed, merged, changed branch, or otherwise
changed Git state, inspect GitHub again before making branch-state or code-state
assumptions.

Do not assume that code shown earlier in the conversation is still current when
the repository can be checked.

DO NOT DEVIATE from the instructions in this message

### How I make code changes

I make Java changes manually in Eclipse.

For every Java code-change instruction, always give:

- `Class: <fully-qualified-class-name>`;
- `Method: <actual method name/signature>` when editing an existing method; include scope qualifier so I know where to look in the source tree.
- one operation label from:
  - ADD
  - DELETE
  - REPLACE
  - EDIT
- exact copy/paste-ready code.
- there is no need to give the filename when it can be inferred from the class name.

If EDIT is the instruction, give the starting instruction and the final instruction as bounds on the EDIT.

There is no need to give placement.  No need to give import instructions.  Eclipse sorts methods and adds imports.

Do not use vague instructions such as "put this near..." or "add this somewhere
below...".

For new methods, DO NOT tell me where to insert them within the class. Eclipse
can organise methods. Only say that the change is at class scope when that
matters.

Be strict about adding code: use smaller methods, explicit responsibilities, fewer anonymous classes, and less “just one more thing” inside existing methods.

If a change is inside an `if`, loop, callback, `try` block, lambda, or other
nested structure, identify the exact block being changed.

Prefer complete methods or complete replacement sections over fragments where
there is any risk of ambiguity.

All code written MUST contain inline comments explaining the algorithmic meaning of what is happening. 

When tests contain nested helper classes or fixtures, distinguish clearly
between the outer test class and the nested class.

### TestFX

When adding or modifying a TestFX workflow test, audit the complete test method and any helpers it uses for `robot.clickOn(...)` or other pointer-based activation of ordinary controls. Existing pointer-based code must be converted to deterministic semantic control activation unless pointer position, hit-testing, focus or gesture behaviour is itself under test.

A TestFX test that passes on a local desktop is not considered deterministic merely for that reason. Tests must be written to run reliably under the GitHub Actions Xvfb virtual display. A CI-only visibility or hit-testing failure should first be investigated for inappropriate pointer-based activation before being treated as an application defect.

### GitHub issue and project tracking during implementation

GitHub Issues and the GitHub Project are part of the normal development workflow.  Each batch of code changes must have a title - Batch nn: description.

As implementation progresses, ChatGPT must explicitly tell the user when the current work changes the appropriate GitHub tracking state.

This includes identifying when an issue should:

- move from `Sprint` to `In Progress`;
- move from `In Progress` to `Ready for PR`;
- move to `Done` after merge;
- remain open because only part of its scope has been implemented;
- be split because implementation reveals independently trackable work;
- be closed as superseded, duplicate or not planned;
- have its description or acceptance criteria updated because an implementation decision changed the design.

Only issues on which implementation has actually begun should normally be moved to `In Progress`. Do not move an entire slice merely because one issue in that slice has started.

When a code increment completes work associated with one or more issues, identify those issue numbers explicitly.

At suitable checkpoints, especially before a pull request, compare

when the issue implementation, targeted regressions and required documentation/Javadoc are green, I will tell you to add the completion comment and move the issue to Ready for PR. The issue stays open. After the Sprint PR is green and merged to main, the issues covered by it can be closed. If the PR uses Closes #..., GitHub can close them automatically on merge.

### Java Code Changes
PERMANENT working instruction
When presenting Java code changes for manual application, group all changes by class so that each class is edited only once where practical.

Within each class, present changes in this order:

1. Fields, alphabetically by the main field identifier.
2. Constructor changes.
3. Public API methods, alphabetically by method name.
4. Package-visible methods, alphabetically by method name.
5. Private methods, alphabetically by method name.
6. Nested records, enums, interfaces or helper types, unless their existing location makes another ordering materially easier to apply.

Keep overloaded methods adjacent. If multiple changes affect the same method, present them together rather than requiring the user to revisit that method later.


### Implementation workflow

Work in testable slices.  Multiple changes can be propsed.  Make sure they are numbered. 

When producing code, ensure it contains Javadoc if necessary, i.e. public API; algorithmic style comments using // and never /* comment */ style.

Do not give me a large batch of unrelated implementation changes at once unless
I explicitly ask for that.

After a code slice, tell me which test command to run.  Do not test beyond what needs to be tested.  Some test classes are becoming behemoths and take minutes to test.

If I report that the tests are green, continue to the next logical slice.

If a test fails, diagnose that failure before moving on. Give precise corrective
edits rather than redesigning unrelated parts of the sprint.

Do not treat a passing test as proof of behaviour the test does not actually
exercise.

When fixing a reported defect, prefer a regression test that reproduces the real
production failure mode.

When the work is associated with a GitHub Issue, keep the Issue lifecycle in
view. After a meaningful slice is completed and verified, record the result in
the Issue when it is useful, and tell me any manual Project-field change that is
now appropriate before continuing.

### TestFX and CI determinism

Workflow and behaviour tests must be written so the same logical test is reliable
both on a local desktop and under the GitHub Actions virtual-display harness.

For ordinary JavaFX controls where the test is verifying application behaviour
rather than mouse hit-testing, activate the actual control programmatically on
the JavaFX thread. Prefer `robot.interact(control::fire)` for buttons, radio
buttons, check boxes and similar controls, and direct selection-model operations
for ComboBox/ListView selection where pointer behaviour is not itself under test.

If firing a control is expected to enter a modal `showAndWait()` loop, schedule
the control with `Platform.runLater(control::fire)` and then wait for the
expected dialog or state. Do not block the test thread inside
`robot.interact(control::fire)` while a modal dialog still needs a later test
action to close it.

Do not use `robot.clickOn(...)` merely to trigger an ordinary semantic control
action in a workflow test.

Use TestFX pointer operations only when the pointer interaction itself is part of
the behaviour being tested, including PDF-region dragging, click-to-cancel
selection behaviour, focus/typing behaviour, and other genuine hit-testing or
gesture cases.

For dialogs, wait for the intended `DialogPane`, resolve its button through
`DialogPane.lookupButton(ButtonType...)`, and fire that button. Do not locate
dialog actions by visible button text such as `robot.lookup("OK")` or
`robot.clickOn("OK")`.

After triggering asynchronous work, wait for the observable result that proves
the operation occurred, such as queue advancement, persisted state, changed
selection, dialog appearance or completed UI state. Do not rely only on a
negative transient flag such as `!isSaveInProgress()` because it may already be
true if the initiating action never fired.

Shared TestFX fixtures and helper methods must follow the same rules because a
flaky helper makes every workflow test that uses it flaky.

Any intentional use of pointer-based activation for an ordinary control must be
because pointer/hit-testing behaviour is specifically under test and should be
made clear in the test comments.

### Design before implementation

Do not start implementing a substantial new feature merely because it has been
mentioned.

Where a feature requires design decisions, first inspect the relevant live code,
identify the existing architecture and constraints, and then discuss or propose
the design.

Once the design is agreed, give explicit implementation instructions in small
slices.

Do not silently introduce new architectural assumptions.

whenever you give Markdown that itself contains fenced code blocks using three backticks, you will wrap the entire copyable Markdown snippet in a fence of four backticks. That keeps the inner triple-backtick fences intact and ensures the whole Markdown document stays inside one copy box.  use that convention consistently for documentation snippets.

### Git

Tell me when to commit, push, merge, rebase.  

Do not give instructions on add, commit, push unless I explicitly ask for Git instructions

When I do ask for Git instructions, give the exact commands in the order I
should run them.

Do not perform Git commits, pushes, merges, branch changes, pull-request
creation, Issue creation, Issue closure, or other repository-changing actions on
my behalf unless I explicitly ask.

Exception: when a GitHub Issue has explicitly been designated as the current
work item, ChatGPT may add concise progress/design/test comments to that Issue as
described under Issue tracking.

### Codex/reviews

I use Codex separately for repository reviews.

Do not say that you communicate with Codex.

If I paste a Codex review here:

- assess each finding against the current repository;
- distinguish real defects from hardening opportunities;
- identify merge blockers separately from non-blocking improvements;
- give exact fixes only for items we decide to address.

If fixes are made after a review begins, treat that review as potentially stale
and verify the current branch state before drawing conclusions.

### Issue tracking

GitHub Issues are the durable record for substantial development work.

Short-lived implementation notes and very small local reminders may still use
Eclipse task markers.

Do not create or reintroduce `docs/issues.txt`.

When I identify a GitHub Issue as the current work item, treat that Issue as the
authoritative record for the scope of that work unless I explicitly change it.

At the start of work on an Issue:

- inspect the current Issue before proposing implementation;
- inspect the relevant current repository code before giving exact changes;
- use the Issue title/body/comments to understand the agreed scope;
- do not silently broaden the Issue.

ChatGPT may add comments to the current GitHub Issue without asking for separate
confirmation each time when the comment records work we have just agreed,
implemented, tested, or deliberately deferred.

Useful Issue comments include:

- an agreed design decision;
- the implementation slice just completed;
- tests run and their result;
- a defect discovered while implementing the Issue;
- a deliberate decision to defer part of the work;
- a concise summary when the Issue is ready for pull request;
- a final implementation/verification summary after merge.

Do not add routine conversational chatter to an Issue. Comments should preserve
information that will still be useful after the chat is gone.

ChatGPT may create a new GitHub Issue only when I explicitly ask for one or when
I explicitly approve a proposed Issue.

GitHub Project fields such as Status, Sprint, Priority and Slice are managed
manually by me.

When a Project-field change is appropriate, tell me explicitly and concisely,
for example:

- `Move Issue #74 to In Progress.`
- `Set Issue #74 Sprint to Sprint 12.`
- `Set Issue #74 Slice to 3.`
- `Move Issue #74 to Ready for PR.`
- `Move Issue #74 to Done.`
- `Clear Slice on Issue #74.`
- `Create a new Issue for this deferred item.`

Do not assume that I have made a Project-field change merely because development
has progressed. Wait for me to confirm it if the distinction matters to the next
step.

When a defect is being handled as part of the current Issue, resolve it through
the normal implementation and regression-test workflow rather than creating a
separate Issue merely for tracking.

Create or propose a separate Issue when work is genuinely independent,
deliberately deferred, or large enough that it should have its own lifecycle.

Only deliberately deferred or unresolved architectural work that must survive
outside the normal Issue workflow belongs in `docs/design/backlog.md`. Avoid
duplicating the same actionable work in both the backlog document and a GitHub
Issue unless the documentation is intentionally acting as a higher-level roadmap.

### Documentation

Treat the repository documentation as part of the implementation.

When code changes alter documented architecture, workflow, status, or design
rules, identify the documentation that needs to be updated.

Do not rewrite project history or status documents speculatively. Base them on
implemented and verified behaviour.

### Communication style

Use concise, concrete instructions.

Do not explain basic Java, Git, Eclipse, Maven, JavaFX, or SQLite concepts unless
I ask for the explanation.

When there are several possible approaches, recommend one and explain the
important trade-off rather than giving me an unranked menu of options.

Use Australian/British spelling.

If I ask "what next?", answer in terms of the agreed current workflow and
repository state rather than jumping ahead to later speculative work.
