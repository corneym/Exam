# Sprint 07 cleanup review

Reviewed on 9 September 2026. Changes are on `feature/preamble-capture`; no commit was created. At the start of the review, that branch and `refactor/sprint07-cleanup` both pointed to `7b75eec` (`Answer capture mirrors question capture`). The working tree was initially clean.

## Implemented changes

Documentation focuses on contracts that affect callers: lifecycle, nullable state, editing transitions, region order, page numbering, source paths, transaction scope and export behavior. Trivial private helpers have descriptive names rather than repetitive Javadoc.

Paths below are relative to `src/main/java/au/edu/eq/questionbank/`.

| Files | Changes |
| --- | --- |
| `importer/curriculum/CurriculumImportRow.java`, `CurriculumSource.java` | Moved invalid record-level `@throws` tags onto the validating compact constructors. |
| `model/AnswerRegion.java`, `QuestionRegion.java` | Moved exception documentation onto compact constructors; retained the proportional coordinate and one-based page contracts. |
| `model/Question.java` | Documented classification, identity, optional relationships, immutable region ordering and metadata-only questions. Corrected answer documentation: assigning an answer updates memory, not persistence. Clarified that the unresolved-context predicate uses the historical legacy hint. |
| `pdf/PdfSession.java` | Documented session ownership, thread confinement and close behavior; placed owned resources before factory methods. |
| `pdf/PdfStore.java` | Corrected the containment contract to describe lexical normalization and the lack of symbolic-link/junction resolution. |
| `pdf/QuestionExtractor.java` | Clarified that question extraction includes the question's own regions, without automatically including linked shared context. |
| `repository/assessment/QuestionRepository.java` | Clarified that editing requires nonempty replacement regions and classification within the existing syllabus. |
| `repository/assessment/SqliteQuestionWriter.java` | Added contracts for shared-context propagation, region attachment overloads, relationship updates and question edits, including transaction boundaries and rollback behavior. |
| `service/retrieval/QuestionPreviewService.java` | Corrected “stored image” wording: previews are reconstructed from authoritative regions and omit linked context. |
| `service/revision/RevisionCorpusStatistics.java`, `RevisionQuestionPlacement.java` | Documented that preamble reporting reflects historical legacy evidence, independently of later context resolution. |
| `output/revision/RevisionAnswerAsset.java`, `RevisionQuestionAsset.java` | Documented owning questions, source regions, one-based answer region numbers and export-relative image paths. |
| `output/revision/RevisionAnswerAssetRenderer.java`, `RevisionQuestionAssetRenderer.java` | Documented renderer dependencies and synchronous progress callbacks. |
| `output/revision/RevisionExportRequest.java`, `RevisionExportResult.java` | Documented subject, destination and result statistics contracts. |
| `output/revision/RevisionExportService.java`, `RevisionExportProgressListener.java`, `RevisionHtmlRenderer.java` | Documented staging, validation, destination requirements, publication, failure cleanup, rendering results and progress callback threading. |
| `ui/QuestionCapturePane.java` | Grouped constants by purpose and fields into dependencies, capture modes, metadata/preamble controls, region controls, save controls and transient state. Extracted question-code change handling and guarded edit-field loading. Added edit and mode-transition contracts. |
| `ui/AnswerCapturePane.java` | Grouped dependencies, source selection, content/region controls, save controls and transient edit state. Extracted deferred selection restoration. Documented answer editing and state queries. |
| `ui/SharedContextCapturePane.java` | Grouped constants and dependencies, context selection, region controls, save controls and transient state. Extracted context-status changes and accepted-region removal. Documented pending versus accepted versus persisted state. |
| `ui/QuestionSearchPane.java` | Extracted named success/failure handlers for search, hierarchy loads and previews, preserving generation and task-identity checks. Extracted hierarchy-result application helpers. Documented disposal, selection and edit refresh. |
| `ui/QuestionSearchDialog.java` | Documented disposal and refresh after editing. |
| `ui/QuestionBankApplication.java` | Extracted close-request, subject-change and export completion/failure handlers. Shared progress-dialog construction between HTML and SCORM export. Extracted refresh/reopen behavior after editing a search result. |
| `ui/CurriculumMappingReviewDialog.java` | Extracted no-match selection, editing toggle, confirmation, source descriptor and review-level handlers. |
| `ui/CurriculumSelectorPane.java` | Extracted code-field focus handling; documented locking subject/syllabus during editing. |
| `ui/CurriculumImportDialog.java`, `LegacyBookletImportDialog.java`, `LegacyQuestionImportDialog.java`, `ExamImportDialog.java` | Moved inline event-filter validation into named helpers, retaining event-consumption behavior. |
| `ui/PdfWorkspacePane.java` | Documented borrowed PDF sessions, viewer restoration, active page/document queries, cursor control and proportional selection records. |

All action/listener lambdas that contained inline workflow blocks now delegate to named helpers. No-op callbacks, value conversion, bindings and task implementations are not event listeners and remain where appropriate. Handler extraction preserves operation order, transition guards, event consumption and asynchronous stale-result checks.

There are no changes to coordinate calculations, page-number conversion, stored source paths, schema or file formats. No source PDFs, generated images or machine-specific configuration were added.

## Added tests

Eight additional tests, under `src/test/java/au/edu/eq/questionbank/`:

| Test class | New coverage |
| --- | --- |
| `pdf/PdfSessionTest.java` | Crop-box dimensions plus 90-degree rotation at two DPIs; different first/last page sizes to detect incorrect one-based indexing. |
| `repository/assessment/SqliteQuestionRepositoryTest.java` | A trigger rejects a later replacement region after metadata changes and deletion of old regions; reopening verifies original metadata and coordinates were restored. |
| `repository/assessment/SqliteSharedQuestionContextRepositoryTest.java` | A later region insertion failure rolls back the context and an earlier successful region insert. |
| `output/revision/RevisionExportServiceTest.java` | Failure in a progress consumer immediately before publication removes staging and leaves no destination. |
| `ui/SharedContextCapturePaneTest.java` | Cancelling an accepted automatic preamble discards it; cross-booklet transfer is rejected without state changes; saving before acceptance is rejected without losing the pending selection. |

The database tests exercise real SQLite transactions. PDF tests generate their own temporary documents. UI tests use the existing TestFX/JUnit integration.

## Glaring gaps: reported, not behaviorally changed

1. **High priority: captured preambles are omitted from generated question images.** `pdf/QuestionExtractor.java`, `extractQuestion(PdfSession, Question)`, only passes `question.getRegions()` to assembly. `QuestionPreviewService` and `RevisionQuestionAssetRenderer` call that method; SCORM uses revision assets too. A question that depends on a captured shared graph or stem can therefore be incomplete in preview/export. Add explicit shared-context assembly with defined ordering and duplicate handling, then exercise a linked multi-page preamble through HTML and SCORM integration tests.

2. **High priority: saving a question is not one transaction across its related changes.** `ui/QuestionCapturePane.java`, `saveQuestion()`, resolves/persists source identity and automatic context, applies context to sibling questions, and updates preamble status before the final question save/update. Those repository calls commit separately. A failure in the final write can leave a saved context, changed sibling links or changed source status even though the question save failed. The added rollback tests prove individual writer guarantees, not atomicity of this complete workflow. A service-level transaction boundary and a failure-injection integration test are needed.

3. **Path containment is lexical rather than physical.** `pdf/PdfStore.java`, `resolve()` and `importExamPdf()`, normalize paths and test `startsWith(pdfRoot)` but subsequently follow filesystem links. A directory link inside the configured root can redirect reads or imports outside it. Other backup filesystem checks do not automatically protect this PDF path. Define the policy for links/junctions and enforce it on source resolution and destination creation, with platform-aware filesystem tests.

4. **Preamble review counts do not represent outstanding work.** `service/revision/RevisionCorpusBuilder.java` counts `isPreambleCaptureRequired()`, and `RevisionQuestionPlacement` exposes the same historical flag. Linking context does not clear that historical evidence, so resolved questions remain in the preamble-review count. Decide whether the statistic should mean historical evidence or unresolved work; use a separate unresolved-state predicate if the latter, with tests covering resolved imported questions and source-level `UNKNOWN`/`NONE`/`PRESENT` status.

5. **The central document-assembly workflow is still incomplete.** The implemented output layer provides question HTML and subject revision HTML/SCORM. There is no dedicated selected-question exam PDF exporter in the current source. Treat selected-question ordering, page layout and PDF publication as follow-up product work, with integration tests for multi-region questions and shared context.

## Validation

- Standard suite: 679 tests, zero failures or errors, three skipped. The skipped tests are the existing external-workbook `ChemistryCurriculumIntegrationTest` cases.
- Full headless UI suite: 65 tests, zero failures or errors, including the final repeat after the last helper extraction.
- Javadoc: `./mvnw.cmd javadoc:javadoc -Ddoclint=all,-missing -Dshow=private` passes after correcting seven pre-existing invalid `@throws` usages across four records. This checks internal APIs as well as exported APIs; it does not demand comments on every private implementation member.
- Diff whitespace check passes with CRLF-aware handling.

Initial sandboxed Maven attempts could not access cached dependencies or clean JUnit temporary directories. Verification succeeded using approved Maven execution with normal filesystem access; no toolchain or repository configuration was changed.

Detailed build logs and generated documentation are derived artifacts under `target/` and are not part of the change set.
