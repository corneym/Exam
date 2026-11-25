package au.edu.eq.questionbank.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

import au.edu.eq.questionbank.service.workpackage.ExchangeRecord;
import au.edu.eq.questionbank.service.workpackage.MasterContributionIntake;
import au.edu.eq.questionbank.service.workpackage.MasterContributionReviewPlanner.RecordAssessment;
import au.edu.eq.questionbank.service.workpackage.MasterContributionReviewPlanner.ReviewPlan;
import au.edu.eq.questionbank.service.workpackage.MasterContributionReviewPlanner.ReviewStatus;
import javafx.geometry.Bounds;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

@Tag("ui")
@ExtendWith(ApplicationExtension.class)
class MasterContributionReviewDialogLayoutTest {

	private MasterContributionReviewDialog dialog;

	@AfterEach
	void closeDialog(FxRobot robot) {
		if (dialog != null) {
			robot.interact(dialog::close);
		}
	}

	@Test
	void reviewRetainsOnlyMergeAndCancelActions(FxRobot robot) {
		robot.interact(() -> {
			DialogPane pane = dialog.getDialogPane();
			assertEquals(List.of(MasterContributionReviewDialog.MERGE, ButtonType.CANCEL), pane.getButtonTypes());
			assertFalse(pane.lookupButton(MasterContributionReviewDialog.MERGE).isDisabled());
			Label notice = (Label) pane.lookup("#contribution-review-notice");
			assertTrue(notice.getText().contains("verified full backup"));
			assertTrue(notice.getText().contains("assignment will close"));
			assertTrue(notice.getText().contains("Exam will be unlocked"));
			((ButtonBase) pane.lookupButton(ButtonType.CANCEL)).fire();
			assertFalse(dialog.isShowing());
		});
	}

	@Start
	void start(Stage stage) {
		UUID subjectId = UUID.randomUUID();
		UUID providerId = UUID.randomUUID();
		UUID examId = UUID.randomUUID();
		UUID paper1Id = UUID.randomUUID();
		UUID paper2Id = UUID.randomUUID();
		UUID newQuestionId = UUID.randomUUID();
		UUID issuedQuestionId = UUID.randomUUID();
		List<ExchangeRecord> baseline = List.of(record("SUBJECT", subjectId, Map.of("subject_name", "Chemistry")),
				record("EXAM_PROVIDER", providerId, Map.of("provider_name", "QCAA")),
				record("EXAM", examId,
						Map.of("subject_id", subjectId.toString(), "provider_id", providerId.toString(), "exam_year",
								2025, "exam_name", "External Assessment")),
				record("EXAM_BOOKLET", paper1Id, Map.of("exam_id", examId.toString(), "booklet_name", "Paper 1")),
				record("EXAM_BOOKLET", paper2Id, Map.of("exam_id", examId.toString(), "booklet_name", "Paper 2")),
				record("QUESTION", issuedQuestionId, Map.of("booklet_id", paper2Id.toString(), "question_code", "21",
						"response_type", "WRITTEN_RESPONSE")));
		List<ExchangeRecord> proposals = List.of(
				record("QUESTION", newQuestionId,
						Map.of("booklet_id", paper1Id.toString(), "question_code", "12", "response_type",
								"MULTIPLE_CHOICE")),
				record("ANSWER", UUID.randomUUID(),
						Map.of("question_id", newQuestionId.toString(), "answer_text", "C", "answer_regions",
								List.of(Map.of("region_order", 1)))),
				record("ANSWER", UUID.randomUUID(),
						Map.of("question_id", issuedQuestionId.toString(), "answer_text", "A written answer",
								"answer_regions", List.of())),
				record("SHARED_QUESTION_CONTEXT", UUID.randomUUID(), Map.of("booklet_id", paper2Id.toString())));
		List<RecordAssessment> assessments = proposals.stream()
				.map(proposal -> new RecordAssessment(proposal.recordType(), proposal.recordId(), null,
						UUID.randomUUID(), null, null, ReviewStatus.READY_NEW))
				.toList();
		ReviewPlan plan = new ReviewPlan(UUID.randomUUID(), UUID.randomUUID(),
				MasterContributionIntake.IntakeStatus.READY_FOR_REVIEW, Instant.now(), assessments);
		stage.setScene(new Scene(new StackPane(), 900, 700));
		stage.show();
		dialog = new MasterContributionReviewDialog(stage, plan, baseline, proposals, examId, "Test Contributor");
		dialog.show();
	}

	@Test
	void summaryRowsDisplayReturnedWorkCounts(FxRobot robot) throws Exception {
		TableView<MasterContributionReviewDialog.BookletSummary> summaries = robot
				.lookup("#contribution-review-summary").queryTableView();
		TableView<MasterContributionReviewDialog.ReturnedItem> details = robot.lookup("#contribution-review-records")
				.queryTableView();
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> WaitForAsyncUtils.asyncFx(
				() -> summaries.getHeight() >= 92 && details.getHeight() >= 100 && summaries.getItems().size() == 2)
				.get());
		robot.interact(() -> {
			summaries.applyCss();
			summaries.layout();
			assertEquals(2, summaries.getItems().size());
			assertEquals(List.of("Booklet", "Questions", "Answers", "MCQ explanations", "Shared contexts"),
					summaries.getColumns().stream().map(TableColumn::getText).toList());
			assertEquals(36 + 2 * summaries.getFixedCellSize(), summaries.getMinHeight(), 0.01);
			assertTrue(summaries.getHeight() >= summaries.getMinHeight());
			assertEquals(Priority.NEVER, VBox.getVgrow(summaries));
			assertEquals(Priority.ALWAYS, VBox.getVgrow(details));
			var paper1 = summaries.getItems().getFirst();
			assertEquals("Paper 1", paper1.booklet());
			assertEquals(1L, paper1.questions());
			assertEquals(1L, paper1.answers());
			assertEquals(1L, paper1.mcqExplanations());
			assertEquals(0L, paper1.sharedContexts());
			var paper2 = summaries.getItems().get(1);
			assertEquals("Paper 2", paper2.booklet());
			assertEquals(0L, paper2.questions());
			assertEquals(1L, paper2.answers());
			assertEquals(0L, paper2.mcqExplanations());
			assertEquals(1L, paper2.sharedContexts());

			// Confirm that the count values are supplied to
			// the actual displayed table columns.
			assertEquals(List.of("Paper 1", "1", "1", "1", "0"), summaries.getColumns().stream()
					.map(column -> column.getCellObservableValue(paper1).getValue()).toList());
			assertEquals(List.of("Paper 2", "0", "1", "0", "1"), summaries.getColumns().stream()
					.map(column -> column.getCellObservableValue(paper2).getValue()).toList());

			// Both populated rows must fit within the actual
			// table viewport, not merely exist in its item list.
			for (int index = 0; index < 2; index++) {
				final int rowIndex = index;
				TableRow<?> row = summaries.lookupAll(".table-row-cell").stream().filter(TableRow.class::isInstance)
						.map(node -> (TableRow<?>) node)
						.filter(candidate -> !candidate.isEmpty() && candidate.getIndex() == rowIndex).findFirst()
						.orElseThrow();
				Bounds rowBounds = row.localToScene(row.getBoundsInLocal());
				Bounds tableBounds = summaries.localToScene(summaries.getBoundsInLocal());
				assertTrue(rowBounds.getMinY() >= tableBounds.getMinY());
				assertTrue(rowBounds.getMaxY() <= tableBounds.getMaxY() + 1.0);
			}
			assertEquals(4, details.getItems().size());
		});
	}

	private ExchangeRecord record(String type, UUID id, Map<String, ?> values) {
		return new ExchangeRecord(ExchangeRecord.CURRENT_RECORD_VERSION, type, id, UUID.randomUUID(), null,
				new ObjectMapper().valueToTree(values));
	}
}
