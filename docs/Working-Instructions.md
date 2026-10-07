## Working instructions for this chat

Use this chat as the working chat for the current sprint of the Exam Question Bank project.

Treat every instruction in this document as mandatory. Do not deviate.

Re-read this document before every code change. Follow it explicitly.

### Repository working rule

Before giving any code change for the Exam Question Bank:

1. Read `docs/Working-Instructions.md` from the current working branch. Do this for every code-change response, even if you have already read it in this conversation.
2. Re-read the requirements I gave for the current feature or slice. Treat my stated behaviour and layout requirements as acceptance criteria.
3. Inspect the current implementation and relevant tests on the current branch. Do not rely only on conversation summaries, previously proposed code, or assumptions about what I have applied.
4. Check every proposed change against every relevant acceptance criterion before writing code. Do not silently reinterpret or weaken a requirement.
5. Make tests verify the acceptance criteria, not merely the implementation you happen to choose.
6. When I report that tests are green, compare the implemented behaviour with the original acceptance criteria before declaring the slice complete.
7. Tell me explicitly when I need to inspect the application visually.
8. Correct any earlier wrong or incomplete instruction explicitly before building later work on top of it.
9. Do not move to the next feature while an earlier stated requirement remains unimplemented.
10. Never give me code based solely on the conversation summary when the repository can be inspected.
11. Follow the code-change format in this document exactly.
12. Wrap copyable Markdown that contains triple-backtick fences inside a four-backtick fence so the whole Markdown remains directly copyable.

### Source of truth

Use `corneym/Exam` as the authoritative GitHub repository.

Treat GitHub as authoritative for established state. During one continuous implementation sequence, rely on the exact edits I have applied and reported green only until the next checkpoint push.

Before giving exact implementation instructions that depend on current classes, methods, schemas, tests, documentation, or branch state, inspect the current repository rather than relying on remembered file versions.

If I tell you that I have committed, pushed, merged, changed branch, or otherwise changed Git state, inspect GitHub again before making branch-state or code-state assumptions.

Do not assume that code shown earlier in the conversation is still current when the repository can be checked.

### How I make code changes

Assume that I make Java changes manually in Eclipse.

For every Java code-change instruction, give:

- `Class: <fully-qualified-class-name>`;
- `Method: <actual method name/signature>` when editing an existing method, including enough scope information to locate it;
- exactly one operation label: `ADD`, `DELETE`, `REPLACE`, or `EDIT`;
- exact copy/paste-ready code.

Omit the filename when the class name makes it obvious.

For an `EDIT` instruction, give exact starting and ending bounds for the edit.

Omit placement instructions unless class scope materially matters. Omit import instructions. Let Eclipse organise methods and imports.

Do not use fully qualified type names in Java snippets. Let me choose the import.

Do not use vague directions such as “put this near...” or “add this somewhere below...”.

For new methods, do not tell me where to insert them within the class. State only that the change is at class scope when that matters.

Prefer smaller methods, explicit responsibilities, fewer anonymous classes, and less “just one more thing” inside an existing method.

If a change is inside an `if`, loop, callback, `try` block, lambda, or other nested structure, identify the exact block being changed.

Prefer complete methods or complete replacement sections whenever fragments could be ambiguous.

Include inline `//` comments that explain the algorithmic meaning of non-obvious code. Do not use `/* ... */` comments for algorithmic explanation.

When tests contain nested helper classes or fixtures, distinguish the outer test class from the nested class explicitly.

### TestFX

When adding or modifying a TestFX workflow test, audit the complete test method and every helper it uses for `robot.clickOn(...)` or other pointer-based activation of ordinary controls.

Convert existing pointer-based activation of ordinary controls to deterministic semantic activation unless pointer position, hit-testing, focus, or gesture behaviour is itself under test.

Do not treat a TestFX test as deterministic merely because it passes on a local desktop.

Write TestFX tests to run reliably under the GitHub Actions Xvfb virtual display.

When a failure appears only in CI and concerns visibility or hit-testing, investigate inappropriate pointer-based activation before treating it as an application defect.

### GitHub issue and project tracking during implementation

Use GitHub Issues and the GitHub Project as part of the normal development workflow.

Give every batch of code changes a title in the form `Batch nn: description`.

Tell me explicitly whenever the current work changes the appropriate GitHub tracking state.

Identify when an issue should:

- move from `Sprint` to `In Progress`;
- move from `In Progress` to `Ready for PR`;
- move to `Done` after merge;
- remain open because only part of its scope has been implemented;
- be split because implementation reveals independently trackable work;
- be closed as superseded, duplicate, or not planned;
- have its description or acceptance criteria updated because an implementation decision changed the design.

Move only issues on which implementation has actually begun to `In Progress`. Do not move an entire slice merely because one issue in that slice has started.

When a code increment completes work associated with one or more issues, identify those issue numbers explicitly.

At suitable checkpoints, especially before a pull request, compare implemented behaviour, targeted regressions, required documentation/Javadoc, and the Issue acceptance criteria. Identify any gap before calling the work ready.

Wait for me to tell you to add the completion comment and move an issue to `Ready for PR` after its implementation, targeted regressions, and required documentation/Javadoc are green.

Keep the issue open while it is `Ready for PR`.

After the Sprint PR is green and merged to `main`, close the issues it covers. Let GitHub close them automatically when the PR deliberately uses `Closes #...`.

### Java code changes

Group manual Java changes by class so that I edit each class only once where practical.

Within each class, present changes in this order:

1. Fields, alphabetically by the main field identifier.
2. Constructor changes.
3. Public API methods, alphabetically by method name.
4. Package-visible methods, alphabetically by method name.
5. Private methods, alphabetically by method name.
6. Nested records, enums, interfaces, or helper types, unless their existing location makes another order materially easier to apply.

Keep overloaded methods adjacent.

If multiple changes affect the same method, present them together rather than making me revisit that method later.

### Implementation workflow

Work in testable slices.

Number multiple proposed changes.

When producing code, add Javadoc where required for public API and add algorithmic `//` comments where they improve understanding.

Do not give me a large batch of unrelated implementation changes unless I explicitly ask for one.

After each code slice, tell me exactly which test command to run. If it is a UI test, use the headless command.

Do not test beyond what the slice requires. Avoid unnecessarily running very large test classes that take minutes.

If I report that the tests are green, continue to the next logical slice only after checking the acceptance criteria.

If a test fails, diagnose that failure before moving on. Give precise corrective edits rather than redesigning unrelated sprint work.

Do not treat a passing test as proof of behaviour that the test does not exercise.

When fixing a reported defect, prefer a regression test that reproduces the real production failure mode.

When work is associated with a GitHub Issue, keep that Issue’s lifecycle in view.

After a meaningful slice is completed and verified, record the result in the Issue when it will remain useful, and tell me any manual Project-field change that is now appropriate before continuing.

### TestFX and CI determinism

Write workflow and behaviour tests so the same logical test is reliable both on a local desktop and under the GitHub Actions virtual-display harness.

For ordinary JavaFX controls where the test verifies application behaviour rather than mouse hit-testing, activate the actual control programmatically on the JavaFX thread.

Prefer `robot.interact(control::fire)` for buttons, radio buttons, check boxes, and similar controls.

Prefer direct selection-model operations for ComboBox/ListView selection when pointer behaviour is not itself under test.

If firing a control is expected to enter a modal `showAndWait()` loop, schedule the control with `Platform.runLater(control::fire)` and then wait for the expected dialog or state.

Do not block the test thread inside `robot.interact(control::fire)` while a modal dialog still requires a later test action to close it.

Do not use `robot.clickOn(...)` merely to trigger an ordinary semantic control action in a workflow test.

Use TestFX pointer operations only when pointer interaction is itself part of the behaviour under test, including PDF-region dragging, click-to-cancel selection behaviour, focus/typing behaviour, and other genuine hit-testing or gesture cases.

For dialogs, wait for the intended `DialogPane`, resolve its button through `DialogPane.lookupButton(ButtonType...)`, and fire that button.

Do not locate dialog actions by visible button text such as `robot.lookup("OK")` or `robot.clickOn("OK")`.

After triggering asynchronous work, wait for the observable result that proves the operation occurred, such as queue advancement, persisted state, changed selection, dialog appearance, or completed UI state.

Do not rely only on a negative transient flag such as `!isSaveInProgress()` because it may already be true when the initiating action never fired.

Apply the same rules to shared TestFX fixtures and helper methods.

When intentionally using pointer-based activation for an ordinary control, make the pointer/hit-testing purpose explicit in the test comments.

### Design before implementation

Do not start implementing a substantial new feature merely because it has been mentioned.

When a feature requires design decisions, first inspect the relevant live code, identify the existing architecture and constraints, and then discuss or propose the design.

Once the design is agreed, give explicit implementation instructions in small slices.

Do not silently introduce new architectural assumptions.

Whenever you give Markdown that itself contains fenced code blocks using three backticks, wrap the entire copyable Markdown snippet in a four-backtick fence. Use that convention consistently for documentation snippets.

### Git

Tell me when a commit, push, merge, or rebase checkpoint is appropriate.

Do not give `git add`, commit, or push commands unless I explicitly ask for Git instructions.

When I ask for Git instructions, give the exact commands in the order I should run them.

Do not perform Git commits, pushes, merges, branch changes, pull-request creation, Issue creation, Issue closure, or other repository-changing actions on my behalf unless I explicitly ask.

When I explicitly designate a GitHub Issue as the current work item, you may add concise progress, design, or test comments to that Issue as described under Issue tracking without asking for separate confirmation each time.

### Codex/reviews

Treat Codex as a separate repository-review tool that I use independently.

Do not say that you communicate with Codex.

If I paste a Codex review here:

- assess each finding against the current repository;
- distinguish real defects from hardening opportunities;
- identify merge blockers separately from non-blocking improvements;
- give exact fixes only for items we decide to address.

If fixes are made after a review begins, treat that review as potentially stale and verify the current branch state before drawing conclusions.

### Issue tracking

Use GitHub Issues as the durable record for substantial development work.

Use Eclipse task markers only for short-lived implementation notes and very small local reminders.

Do not create or reintroduce `docs/issues.txt`.

When I identify a GitHub Issue as the current work item, treat that Issue as the authoritative record for the scope of that work unless I explicitly change it.

At the start of work on an Issue:

- inspect the current Issue before proposing implementation;
- inspect the relevant current repository code before giving exact changes;
- use the Issue title, body, and comments to understand the agreed scope;
- do not silently broaden the Issue.

Add comments to the current GitHub Issue without asking for separate confirmation when the comment records work we have just agreed, implemented, tested, or deliberately deferred.

Use Issue comments for durable information such as:

- an agreed design decision;
- the implementation slice just completed;
- tests run and their result;
- a defect discovered while implementing the Issue;
- a deliberate decision to defer part of the work;
- a concise summary when the Issue is ready for pull request;
- a final implementation/verification summary after merge.

Do not add routine conversational chatter to an Issue.

Make every Issue comment preserve information that will still be useful after the chat is gone.

Create a new GitHub Issue only when I explicitly ask for one or explicitly approve a proposed Issue.

Leave GitHub Project fields such as Status, Sprint, Priority, and Slice for me to manage manually.

When a Project-field change is appropriate, tell me explicitly and concisely, for example:

- `Move Issue #74 to In Progress.`
- `Set Issue #74 Sprint to Sprint 12.`
- `Set Issue #74 Slice to 3.`
- `Move Issue #74 to Ready for PR.`
- `Move Issue #74 to Done.`
- `Clear Slice on Issue #74.`
- `Create a new Issue for this deferred item.`

Do not assume that I have made a Project-field change merely because development has progressed.

Wait for me to confirm a Project-field change when that distinction matters to the next step.

When a defect belongs to the current Issue, resolve it through the normal implementation and regression-test workflow rather than creating a separate Issue merely for tracking.

Create or propose a separate Issue when work is genuinely independent, deliberately deferred, or large enough to need its own lifecycle.

Keep only deliberately deferred or unresolved architectural work that must survive outside the normal Issue workflow in `docs/design/backlog.md`.

Avoid duplicating the same actionable work in both the backlog document and a GitHub Issue unless the documentation deliberately acts as a higher-level roadmap.

### Documentation

Treat repository documentation as part of the implementation.

When code changes alter documented architecture, workflow, status, or design rules, identify the documentation that needs to be updated.

Do not rewrite project history or status documents speculatively.

Base documentation changes on implemented and verified behaviour.

### Communication style

Use concise, concrete instructions.

Do not explain basic Java, Git, Eclipse, Maven, JavaFX, or SQLite concepts unless I ask for that explanation.

When several approaches are possible, recommend one and explain the important trade-off rather than giving me an unranked menu.

Use Australian/British spelling.

If I ask “what next?”, answer in terms of the agreed current workflow and repository state rather than jumping ahead to speculative later work.
