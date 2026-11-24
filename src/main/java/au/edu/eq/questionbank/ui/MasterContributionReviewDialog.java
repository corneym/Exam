package au.edu.eq.questionbank.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import au.edu.eq.questionbank.service.workpackage.ExchangeRecord;
import au.edu.eq.questionbank.service.workpackage.MasterContributionDecisionLedger;
import au.edu.eq.questionbank.service.workpackage.MasterContributionDecisionLedger.RecordKey;
import au.edu.eq.questionbank.service.workpackage.MasterContributionReviewPlanner.ReviewPlan;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Window;

/**
 * Presents a read-only preview of an entire contribution.
 *
 * The coordinator may approve the complete contribution or cancel. This dialog
 * never modifies the authoritative corpus or publishes managed assets.
 */
final class MasterContributionReviewDialog extends Dialog<ButtonType> {

	static final ButtonType MERGE = new ButtonType("Merge Contribution", ButtonBar.ButtonData.OK_DONE);

	/**
	 * Constructs a human-readable whole-contribution preview.
	 *
	 * @param owner           parent window
	 * @param plan            preliminary coordinator assessment
	 * @param baseline        immutable issued records
	 * @param proposals       returned new records
	 * @param examPortableId  assigned Exam identity
	 * @param contributorName contributor's recorded name
	 */
	MasterContributionReviewDialog(Window owner, ReviewPlan plan, List<ExchangeRecord> baseline,
			List<ExchangeRecord> proposals, UUID examPortableId, String contributorName) {

		initOwner(Objects.requireNonNull(owner, "owner"));
		initModality(Modality.WINDOW_MODAL);

		setTitle("Review Contribution");

		Preview preview = buildPreview(baseline, proposals, examPortableId);

		setHeaderText(preview.examName());

		Label subject = new Label("Subject: " + preview.subjectName());

		Label contributor = new Label("Contributor: " + Objects.requireNonNull(contributorName, "contributorName"));

		Label summaryHeading = new Label("Contribution summary");
		summaryHeading.setStyle("-fx-font-weight: bold;");

		TableView<BookletSummary> summaries = new TableView<>(FXCollections.observableArrayList(preview.booklets()));

		summaries.setId("contribution-review-summary");

		summaries.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

		summaries.getColumns().setAll(textColumn("Booklet", BookletSummary::booklet, 175),
				textColumn("Questions", row -> Long.toString(row.questions()), 85),
				textColumn("Answers", row -> Long.toString(row.answers()), 75),
				textColumn("MCQ explanations", row -> Long.toString(row.mcqExplanations()), 135),
				textColumn("Shared contexts", row -> Long.toString(row.sharedContexts()), 115));

		summaries.setFixedCellSize(28);
		summaries.setPrefHeight(Math.min(200, 32 + preview.booklets().size() * 28));

		Label detailHeading = new Label("Returned work");
		detailHeading.setStyle("-fx-font-weight: bold;");

		TableView<ReturnedItem> details = new TableView<>(FXCollections.observableArrayList(preview.items()));

		details.setId("contribution-review-records");

		details.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

		details.getColumns().setAll(textColumn("Booklet", ReturnedItem::booklet, 180),
				textColumn("Question / Item", ReturnedItem::item, 180),
				textColumn("Returned work", ReturnedItem::description, 310));

		Label notice = new Label();
		notice.setId("contribution-review-notice");
		notice.setWrapText(true);

		getDialogPane().getButtonTypes().setAll(MERGE, ButtonType.CANCEL);

		Button mergeButton = (Button) getDialogPane().lookupButton(MERGE);

		mergeButton.setId("contribution-review-merge");

		try {
			// The same whole-return policy is reassessed by
			// the service when the coordinator approves merging.
			MasterContributionDecisionLedger.mergeSelections(plan);

			notice.setText("The returned work is ready for merge. " + "Existing coordinator-owned content "
					+ "will be preserved." + "\n\nA verified full backup will be "
					+ "created automatically before final " + "validation and application."
					+ "\n\nAfter a successful merge, the " + "assignment will close and the "
					+ "Exam will be unlocked.");

		} catch (IllegalArgumentException failure) {

			mergeButton.setDisable(true);

			notice.setText("The contribution cannot be merged.\n\n" + failure.getMessage()
					+ "\n\nNo returned work will be applied.");
		}

		VBox content = new VBox(10, subject, contributor, summaryHeading, summaries, detailHeading, details, notice);

		content.setPadding(new Insets(12));

		VBox.setVgrow(details, Priority.ALWAYS);

		getDialogPane().setContent(content);
		getDialogPane().setPrefSize(850, 650);

		setResultConverter(button -> button);
	}

	/**
	 * Builds a presentation using actual domain names and relationships.
	 *
	 * @param baseline       issued baseline records
	 * @param proposals      returned records
	 * @param examPortableId assigned Exam identity
	 * @return read-only preview data
	 */
	static Preview buildPreview(List<ExchangeRecord> baseline, List<ExchangeRecord> proposals, UUID examPortableId) {

		Objects.requireNonNull(baseline, "baseline");
		Objects.requireNonNull(proposals, "proposals");
		Objects.requireNonNull(examPortableId, "examPortableId");

		Map<RecordKey, ExchangeRecord> indexed = new HashMap<>();

		for (ExchangeRecord record : baseline) {
			indexRecord(indexed, record);
		}

		for (ExchangeRecord record : proposals) {
			indexRecord(indexed, record);
		}

		ExchangeRecord exam = indexed.get(new RecordKey("EXAM", examPortableId));

		if (exam == null) {
			throw new IllegalArgumentException("The assigned Exam details are missing.");
		}

		ExchangeRecord subject = reference(exam, "subject_id", "SUBJECT", indexed);

		ExchangeRecord provider = reference(exam, "provider_id", "EXAM_PROVIDER", indexed);

		String examName = provider.values().path("provider_name").asText() + " "
				+ exam.values().path("exam_year").asText() + " " + exam.values().path("exam_name").asText();

		List<ReturnedItem> items = new ArrayList<>();

		for (ExchangeRecord proposal : proposals) {
			items.add(describe(proposal, indexed));
		}

		items.sort(Comparator.comparing(ReturnedItem::booklet).thenComparing(ReturnedItem::item)
				.thenComparing(ReturnedItem::description));

		// Group by portable Booklet identity internally, but display
		// only the actual human-readable Booklet name.
		var grouped = items.stream().collect(Collectors.groupingBy(ReturnedItem::bookletId));

		List<BookletSummary> summaries = new ArrayList<>();

		for (List<ReturnedItem> group : grouped.values()) {

			summaries.add(new BookletSummary(group.getFirst().booklet(), count(group, "QUESTION"),
					count(group, "ANSWER"), group.stream().filter(ReturnedItem::mcqExplanation).count(),
					count(group, "SHARED_QUESTION_CONTEXT")));
		}

		summaries.sort(Comparator.comparing(BookletSummary::booklet));

		return new Preview(subject.values().path("subject_name").asText(), examName, List.copyOf(summaries),
				List.copyOf(items));
	}

	private static long count(List<ReturnedItem> items, String type) {

		return items.stream().filter(item -> item.type().equals(type)).count();
	}

	private static ReturnedItem describe(ExchangeRecord proposal, Map<RecordKey, ExchangeRecord> indexed) {

		String type = proposal.recordType();

		ExchangeRecord question = switch (type) {
		case "QUESTION" -> proposal;
		case "ANSWER", "QUESTION_IMAGE" -> reference(proposal, "question_id", "QUESTION", indexed);
		default -> null;
		};

		ExchangeRecord bookletOwner = question == null ? proposal : question;

		if (!bookletOwner.values().hasNonNull("booklet_id")) {

			return new ReturnedItem(new UUID(0, 0), "Exam-wide", "Other item", "Unsupported returned work", type,
					false);
		}

		ExchangeRecord booklet = reference(bookletOwner, "booklet_id", "EXAM_BOOKLET", indexed);

		String bookletName = booklet.values().path("booklet_name").asText();

		String item = question == null ? switch (type) {
		case "SOURCE_QUESTION" -> "Question " + proposal.values().path("source_question_code").asText();
		case "SHARED_QUESTION_CONTEXT" -> "Shared context";
		default -> "Other item";
		} : "Question " + question.values().path("question_code").asText();

		boolean mcqExplanation = "ANSWER".equals(type) && question != null
				&& "MULTIPLE_CHOICE".equals(question.values().path("response_type").asText())
				&& !proposal.values().path("answer_regions").isEmpty();

		String description = switch (type) {
		case "QUESTION" -> "New Question";
		case "ANSWER" -> mcqExplanation ? "Answer and MCQ Explanation" : "New Answer";
		case "SOURCE_QUESTION" -> "Source Question";
		case "QUESTION_IMAGE" -> "Question image";
		case "SHARED_QUESTION_CONTEXT" -> "Shared question context";
		default -> "Unsupported returned work";
		};

		return new ReturnedItem(booklet.recordId(), bookletName, item, description, type, mcqExplanation);
	}

	private static void indexRecord(Map<RecordKey, ExchangeRecord> indexed, ExchangeRecord record) {

		RecordKey key = new RecordKey(record.recordType(), record.recordId());

		// The current contribution contract permits additions,
		// rather than replacements of issued baseline records.
		if (indexed.putIfAbsent(key, record) != null) {
			throw new IllegalArgumentException(
					"The contribution duplicates issued " + "or previously returned content.");
		}
	}

	private static ExchangeRecord reference(ExchangeRecord owner, String field, String type,
			Map<RecordKey, ExchangeRecord> indexed) {

		var value = owner.values().path(field);

		if (!value.isTextual()) {
			throw new IllegalArgumentException("Required contribution details are missing.");
		}

		ExchangeRecord record = indexed.get(new RecordKey(type, UUID.fromString(value.asText())));

		if (record == null) {
			throw new IllegalArgumentException("Required contribution details could not " + "be located.");
		}

		return record;
	}

	private static <T> TableColumn<T, String> textColumn(String title, Function<T, String> value, int width) {

		TableColumn<T, String> column = new TableColumn<>(title);

		column.setPrefWidth(width);

		column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));

		return column;
	}

	record BookletSummary(String booklet, long questions, long answers, long mcqExplanations, long sharedContexts) {
	}

	record Preview(String subjectName, String examName, List<BookletSummary> booklets, List<ReturnedItem> items) {
	}

	record ReturnedItem(UUID bookletId, String booklet, String item, String description, String type,
			boolean mcqExplanation) {
	}
}