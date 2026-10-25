# JavaFX Layout Stability — Scrollbars, Image Previews and Resize Feedback

**Project:** Exam Question Bank  
**Intended repository path:** `docs/design/javafx-layout-stability.md`  
**Prepared:** 25 October 2026  
**Scope:** Reusable design guidance derived from observed JavaFX layout defects and current application code. This is not a new implementation issue.

## 1. Purpose

The Exam Question Bank displays PDF pages, captured Question and Answer regions, clipboard images, Shared Context, descriptor hierarchies and data-heavy forms inside nested JavaFX layouts. Several real-use defects have presented as persistent *shimmer*: a pane repeatedly changes size instead of settling after its contents are displayed.

This document preserves the lessons from those defects so new UI work can avoid repeating them. It distinguishes **observed behaviour and established fixes** from **possible failure mechanisms**. A scrollbar configured with `AS_NEEDED` is not inherently defective; it should be changed only where the surrounding layout makes it unstable.

GitHub Issues remain authoritative for the original requirements, implementation decisions, evidence and verification. This document describes reusable design principles rather than duplicating issue histories or creating additional work.

## 2. Why a layout can shimmer

A potential feedback loop occurs when all of these conditions interact:

1. A `ScrollPane` decides whether a vertical scrollbar is required from its content height.
2. Showing that scrollbar reduces the available viewport width.
3. A child uses the available width to calculate its height, for example an aspect-ratio-preserving `ImageView` or a wrapped hierarchy label.
4. The resulting height becomes small enough that the scrollbar is no longer necessary.
5. Hiding the scrollbar restores the width, increasing the child's height and reversing the decision on a later layout pulse.

This produces the possible cycle:

**scrollbar appears → viewport narrows → content height changes → scrollbar disappears → viewport widens → content height changes again.**

The same visible symptom can have other causes: a self-referential preferred-size binding, repeated asynchronous UI publication, rebuilding preview nodes or costly PDF rendering triggered unnecessarily. Do not infer the exact cause from appearance alone.

## 3. Established application examples

### 3.1 Question Search preview — Issue #124 (closed)

**Observed:** A large PDF-derived Question-region preview shimmered and repeatedly resized. Separately, an oversized clipboard-image preview could compress Revision Output Applicability controls until they were unusable. Those were distinct reproduction cases.

**Implemented principles:**

- Reserve vertical scrollbar width with `ScrollBarPolicy.ALWAYS` and suppress horizontal scrolling with `NEVER`.
- Set the preview's `ScrollPane.fitToWidth(false)` because the `ImageView` is sized explicitly from the **actual viewport bounds**.
- Preserve image aspect ratio while allowing the viewport to scroll vertically.
- Bound the preview's minimum/preferred height so an oversized image cannot take over the entire Search column.
- Protect the applicability list and Include/Exclude actions from being collapsed or overlapped by the preview.

**Evidence:** Five focused headless TestFX regressions and final manual checks were reported satisfactory. The issue was closed on 13 October 2026.

**Source:** `QuestionSearchPane.createQuestionPreviewPane()` and `QuestionSearchPaneTest`.

### 3.2 PDF workspace — Issue #190 (closed)

**Observed:** The PDF pane shimmered at certain content and viewport sizes.

**Implemented principles:**

- Reserve vertical scrollbar width with `ALWAYS`, and set horizontal scrolling to `NEVER`.
- **Retain** `ScrollPane.fitToWidth(true)` because this pane's PDF page container, unlike the Question Search preview, already uses ScrollPane-managed width and maintains its own proportional geometry.
- Preserve zoom, aspect ratio, overlays, scrolling and region selection.
- Check viewport stability and avoid unnecessary page-render callbacks when the page is unchanged.

**Evidence:** Focused tests passed; subsequent manual inspection could no longer reproduce the original shimmer. Because a reliable pre-fix reproduction was not retained, the exact original causal path was not proven. The issue was closed on 20 October 2026.

**Source:** `PdfWorkspacePane.createScrollPane()` and `PdfWorkspacePaneAsyncTest`.

### 3.3 Capture workspace with active Descriptor search — Issue #210 (open at preparation)

**Observed:** During Question Capture, displaying Descriptor search results caused shimmer across the results list, Content parts and right-hand Capture workspace. Selecting a Descriptor cleared the search and stopped the visible symptom.

**Local Batch 01 change:** The enclosing `captureWorkspaceScrollPane` in `QuestionBankApplication.createCaptureWorkspaceModePane()` was changed from vertical policy `AS_NEEDED` to `ALWAYS`. It retains `fitToWidth(true)` and horizontal policy `NEVER`. This stabilises the width offered to wrapped Descriptor rows and downstream capture sections when vertical overflow changes.

**Evidence and status as reported on 25 October 2026:** The developer reported green local tests and that the shimmer appeared to have disappeared in the running application. This is promising real-use evidence, **not** proof of a particular feedback-loop mechanism. The local change had not been verified pushed or merged when this document was prepared; Issue #210 remains open. Do not treat this entry as a completed release change until its ordinary PR process concludes.

**Source:** Issue #210 and the intended local `QuestionBankApplication.createCaptureWorkspaceModePane()` change. The GitHub branch may still show the preceding `AS_NEEDED` policy until the user's work is pushed.

### 3.4 Accepted Question Content parts — Issues #84 and #118 (closed)

**Observed:** The Content parts viewport previously needed better bounds and could fail to expand to the correct initial height until a later click.

**Existing safeguards:**

- Accepted content stays inside a bounded scrollable region instead of growing the entire Question pane without limit.
- `QuestionCapturePane.createRegionsScrollPane()` has a non-zero minimum height and a 300-pixel maximum height.
- Its preferred height is calculated from the preferred height of accepted content, with dependencies on content changes and the scroll-pane width, rather than waiting solely for a later visual layout event.
- Regression tests exercise tall content, initial expansion and reduced application height.

**Current nuance:** The inner Content parts scrollbar still uses `AS_NEEDED`. This is **not** a known recurring defect: the developer reports extensive subsequent Question capture without renewed shimmer. Do not change this working configuration just because it differs from the outer #210 workspace.

**Source:** `QuestionCapturePane.createRegionsScrollPane()`, `QuestionCaptureWorkflowTest` and `CaptureWorkspaceLayoutTest`.

### 3.5 Shared Context capture — existing safeguard, no independently reproduced shimmer

**Existing safeguards:**

- `SharedContextCapturePane` sets a non-zero minimum and bounded maximum height for its accepted-region viewport.
- The source comment explicitly states that the non-zero minimum breaks an **initial** layout dependency between scroll width, image fit width and content height.
- The transient selection preview is managed only while it is needed, so an accepted region does not leave an unnecessary extra preview occupying space.
- `SharedContextCapturePaneTest#acceptedClipboardImageHasUsableViewportWithoutClick` checks that an accepted clipboard image has a visible viewport and image height without an extra interaction.

**Current nuance:** Its accepted-region scrollbar uses `AS_NEEDED`. No additional Shared Context shimmer defect was demonstrated during this audit, and its existing safeguard should be preserved rather than replaced speculatively. The presence of a preventative fix does not establish that it originally suffered the same persistent shimmer as #124 or #210.

### 3.6 Accepted Answer regions — existing stable-width design

`AnswerCapturePane.configureAnswerRegionPreview()` already reserves vertical scrollbar width with `ALWAYS`, uses `NEVER` for horizontal scrolling, and calculates preferred height and fitted image width from stable viewport geometry. Its source comments specifically describe avoiding a preview-width / preferred-height feedback loop.

## 4. Design rules for future JavaFX work

### 4.1 Select scrollbar policies based on layout dependencies

- Use `ALWAYS` for vertical scrolling when a scrollbar's appearance would change width used to recompute image height or wrapped-content height **and** that change can reverse the overflow decision.
- Use `NEVER` for horizontal scrolling when the content is intentionally fitted to the available width; do not suppress horizontal scrolling where deliberate horizontal access is required.
- Keep `AS_NEEDED` when layout dimensions are sufficiently independent and behaviour is stable. Avoid blanket conversions throughout the application.
- Check nested ScrollPanes independently: stabilising an outer workspace does not prove that every inner viewport requires the same policy.

### 4.2 Give large previews controlled boundaries

- Supply a meaningful minimum height for an important preview or input area when initial layout may otherwise collapse it.
- Provide a bounded preferred/maximum height where accepted content could otherwise consume the full containing pane.
- Let large images scroll inside the intended viewport instead of forcing sibling controls out of view.
- Protect neighbouring controls and action rows with appropriate sizing and grow priorities.

### 4.3 Keep width/height calculations consistent

- Identify which node owns resizing: `ScrollPane` or the inner image/container. Avoid having both independently force the same width.
- Bind image fit width to `viewportBoundsProperty()` when the image must use the actual visible viewport, as in Question Search.
- Preserve an existing `fitToWidth(true)` model when a resizable container already derives its dimensions consistently from ScrollPane-managed width, as in the PDF workspace.
- Preserve aspect ratio. Do not convert a scrolling problem into distorted images or clipped region overlays.
- Avoid self-referential geometry paths, particularly when a child's height changes its parent's overflow decision, which then changes the child's width.

### 4.4 Make dynamic content transitions explicit

- When hiding a transient preview or results pane, ensure its `managed` state matches the intended layout occupancy.
- Do not rebuild entire preview subtrees or re-extract unchanged PDF regions merely because adjacent controls update. Issue #141 established preview reuse for unchanged Question content.
- Separate a deliberate one-time layout transition from persistent changes while the UI is idle.
- Consider wrapped text, asynchronously populated result lists and visible/managed transitions as geometry changes that should settle.

### 4.5 Diagnose before changing a working pane

For a suspected shimmer, record the following at the point where it occurs:

1. The displayed content type: PDF page, extracted region, clipboard image, wrapped hierarchy text or mixed content.
2. The specific active state: selection, search results, loading, saved content, no results or dismissal.
3. Window, SplitPane and internal ScrollPane dimensions, especially around the threshold where a scrollbar first appears.
4. Whether the vertical scrollbar is alternating between visible and hidden.
5. Repeated measurements of `viewportBounds`, child width/height and relevant preferred sizes over successive JavaFX pulses.
6. Whether PDF extraction/rendering or asynchronous result publication keeps happening when the UI is otherwise idle.

**Change only the demonstrated dependency.** If the defect cannot be reproduced, document the possible mechanism and preserve the working implementation rather than applying unverified preventative changes.

## 5. Regression testing and visual acceptance

Use the project's headless TestFX Maven profile and deterministic semantic control actions. Tests should verify the user-visible acceptance criteria as well as any internal scrollbar policy required by the chosen fix.

Useful cases include:

- Images just below and above the scroll-overflow threshold; large/tall PDF regions; large clipboard images; multiple accepted parts.
- Short, long/wrapped, few, many and no-result Descriptor searches, including result-selection/dismissal transitions.
- Initial display without an extra click; narrow and wider content viewports; transitions between short and tall content.
- Stable viewport, preview and parent-section dimensions across repeated JavaFX pulses after initial settling.
- Preserved aspect ratio, scrolling, sibling-control visibility, classification selection, saved content and region overlays.
- No repeated expensive PDF rendering or uncontrolled asynchronous UI refresh for an unchanged display.

**Headless test caution:** Do not enlarge the native JavaFX Stage beyond its initial Monocle framebuffer dimensions merely to test resizing; that caused rendering-thread buffer errors during #190 testing even while JUnit reported success. Constrain and resize the **internal ScrollPane** instead when testing viewport thresholds.

**Manual acceptance is mandatory for actual shimmer defects.** Leave the problematic content or search results displayed long enough to establish that the layout remains still. A test in which shimmer ceases only because the results were selected or dismissed is insufficient. Repeat at the sizes and with the content originally associated with the defect.

## 6. Maintenance and issue policy

- This document is a **design reference**, not a backlog of proposed scrollbar modifications.
- Existing stabilisation should remain untouched unless a reproducible regression or failing acceptance test justifies a change.
- Record any confirmed new defect, its reproduction, implementation and verification in a GitHub Issue. Do not create issues solely because a source file contains `AS_NEEDED`.
- Keep `docs/design/backlog.md` for deliberately deferred or unresolved architectural work rather than duplicating ordinary issue tracking.
- Update this document when a new, verified layout pattern materially changes the guidance; link to the authoritative GitHub issue instead of copying its entire history.

## 7. References

**GitHub defect and implementation records**

- [#84 — Prevent Question capture preview from overflowing or collapsing the Question pane](https://github.com/corneym/Exam/issues/84)
- [#118 — Fix accepted Question region container delayed vertical expansion](https://github.com/corneym/Exam/issues/118)
- [#124 — Fix Question Search preview image resize and shimmer loop](https://github.com/corneym/Exam/issues/124)
- [#141 — Retain unchanged Question content previews instead of repeatedly rendering PDF regions](https://github.com/corneym/Exam/issues/141)
- [#190 — Fix PDF pane shimmer and repeated layout resizing as content size changes](https://github.com/corneym/Exam/issues/190)
- [#210 — Fix Question Capture layout shimmer while Descriptor search results are displayed](https://github.com/corneym/Exam/issues/210)

**Implementation and regression entry points**

- `src/main/java/au/edu/eq/questionbank/ui/search/QuestionSearchPane.java`
- `src/main/java/au/edu/eq/questionbank/ui/pdf/PdfWorkspacePane.java`
- `src/main/java/au/edu/eq/questionbank/ui/QuestionBankApplication.java`
- `src/main/java/au/edu/eq/questionbank/ui/capture/QuestionCapturePane.java`
- `src/main/java/au/edu/eq/questionbank/ui/capture/SharedContextCapturePane.java`
- `src/main/java/au/edu/eq/questionbank/ui/capture/AnswerCapturePane.java`
- `src/test/java/au/edu/eq/questionbank/ui/search/QuestionSearchPaneTest.java`
- `src/test/java/au/edu/eq/questionbank/ui/pdf/PdfWorkspacePaneAsyncTest.java`
- `src/test/java/au/edu/eq/questionbank/ui/CaptureWorkspaceLayoutTest.java`
- `src/test/java/au/edu/eq/questionbank/ui/capture/SharedContextCapturePaneTest.java`
