package au.edu.eq.questionbank.ui;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import au.edu.eq.questionbank.service.workpackage.ExchangeRecord;
import au.edu.eq.questionbank.service.workpackage.MasterContributionDecisionLedger.Choice;
import au.edu.eq.questionbank.service.workpackage.MasterContributionDecisionLedger.RecordKey;
import au.edu.eq.questionbank.service.workpackage.MasterContributionReviewPlanner;
import au.edu.eq.questionbank.service.workpackage.MasterContributionReviewPlanner.RecordAssessment;
import au.edu.eq.questionbank.service.workpackage.MasterContributionReviewPlanner.ReviewStatus;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Window;

/**
 * Explicit read-only coordinator review of an already received contribution.
 *
 * Decisions are held in memory until the coordinator confirms them. This dialog
 * never writes to SQLite or publishes managed assets.
 */
final class MasterContributionReviewDialog extends Dialog<Void> {

	private final Button applyButton = new Button("Queue Apply");
	private final Button approveButton = new Button("Approve Decisions...");
	private boolean approved;
	private final Label detail = new Label();
	private final Button holdButton = new Button("Hold");
	private final Button rejectButton = new Button("Reject");
	private final ObservableList<Row> rows = FXCollections.observableArrayList();
	private final Button skipButton = new Button("Skip Identical");
	private final Label summary = new Label();
	private final Button suggestedButton = new Button("Select Supported Undecided");
	private final TableView<Row> table = new TableView<>();

	/**
	 * Constructs a non-publishing coordinator review for one whole-Exam return.
	 *
	 * @param owner          parent window
	 * @param plan           consistent preliminary master assessment
	 * @param baseline       immutable issued records
	 * @param proposals      received delta records
	 * @param examPortableId assigned master Exam identity
	 */
	MasterContributionReviewDialog(Window owner, MasterContributionReviewPlanner.ReviewPlan plan,
			List<ExchangeRecord> baseline, List<ExchangeRecord> proposals, UUID examPortableId) {
		initOwner(Objects.requireNonNull(owner, "owner"));
		initModality(Modality.WINDOW_MODAL);
		setTitle("Coordinator Contribution Review");
		setHeaderText("Review every returned record before authorising master changes.");
		Objects.requireNonNull(plan, "plan");
		Objects.requireNonNull(baseline, "baseline");
		Objects.requireNonNull(proposals, "proposals");
		Objects.requireNonNull(examPortableId, "examPortableId");
		Map<RecordKey, ExchangeRecord> issued = new HashMap<>();
		Map<RecordKey, ExchangeRecord> indexed = new HashMap<>();
		Map<RecordKey, ExchangeRecord> received = new HashMap<>();
		for (ExchangeRecord record : baseline) {
			RecordKey key = new RecordKey(record.recordType(), record.recordId());
			if (issued.putIfAbsent(key, record) != null) {
				throw new IllegalArgumentException("Duplicate issued baseline record: " + key);
			}
			indexed.put(key, record);
		}
		for (ExchangeRecord record : proposals) {
			RecordKey key = new RecordKey(record.recordType(), record.recordId());
			if (received.putIfAbsent(key, record) != null) {
				throw new IllegalArgumentException("Duplicate received proposal: " + key);
			}
			indexed.put(key, record);
		}
		for (RecordAssessment assessment : plan.assessments()) {
			RecordKey key = new RecordKey(assessment.recordType(), assessment.recordId());
			ExchangeRecord proposal = received.get(key);
			if (proposal == null) {
				throw new IllegalArgumentException("Assessment has no received proposal: " + key);
			}
			UUID booklet = findBooklet(proposal, indexed, new HashSet<>());
			String scope = booklet == null ? "Exam-wide / related" : "Booklet " + booklet.toString().substring(0, 8);
			rows.add(new Row(assessment, issued.get(key), proposal, scope));
		}
		if (rows.isEmpty()) {
			throw new IllegalArgumentException("Contribution review has no proposals.");
		}
		table.setId("contribution-review-records");
		table.setItems(rows);
		table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
		table.getColumns().setAll(textColumn("Exam / Booklet", Row::scope, 180),
				textColumn("Record", row -> row.assessment.recordType(), 180),
				textColumn("Master assessment", row -> row.assessment.status().name(), 170),
				textColumn("Decision", row -> row.choice == null ? "UNDECIDED" : row.choice.name(), 140),
				textColumn("Portable identity", row -> row.assessment.recordId().toString(), 290));
		applyButton.setId("contribution-review-apply");
		skipButton.setId("contribution-review-skip");
		rejectButton.setId("contribution-review-reject");
		holdButton.setId("contribution-review-hold");
		approveButton.setId("contribution-review-approve");
		suggestedButton.setId("contribution-review-suggested");

		applyButton.setTooltip(new Tooltip("Accept this supported new contribution record for application "
				+ "to the coordinator's Exam after final confirmation."));

		skipButton.setTooltip(new Tooltip("Record that this proposal is already identical to the authoritative "
				+ "master record. No Question content is changed."));

		rejectButton.setTooltip(new Tooltip("Decline this proposed record. The rejected contribution remains "
				+ "recorded in the reconciliation history."));

		holdButton.setTooltip(new Tooltip("Leave this proposal unresolved. The assignment cannot be closed "
				+ "until every proposal has a final decision."));

		suggestedButton.setTooltip(new Tooltip("Choose recommended decisions for supported undecided records. "
				+ "Inspect the resulting decisions before approving."));

		approveButton.setTooltip(new Tooltip("Continue only when every proposal has been decided. "
				+ "A verified full backup and final confirmation follow."));

		applyButton.setOnAction(_ -> choose(Choice.QUEUE_APPLY));
		skipButton.setOnAction(_ -> choose(Choice.SKIP_IDENTICAL));
		rejectButton.setOnAction(_ -> choose(Choice.REJECT));
		holdButton.setOnAction(_ -> choose(Choice.HOLD));
		suggestedButton.setOnAction(_ -> suggestSupported());
		approveButton.setOnAction(_ -> {
			if (rows.stream().anyMatch(row -> row.choice == null || row.choice == Choice.HOLD)) {
				return;
			}
			approved = true;
			close();
		});
		table.getSelectionModel().selectedItemProperty().addListener((_, _, _) -> refresh());
		detail.setId("contribution-review-detail");
		detail.setWrapText(true);
		detail.setText("Select a proposal to inspect issued, current-master " + "and contributor evidence.");
		ScrollPane detailScroll = new ScrollPane(detail);
		detailScroll.setFitToWidth(true);
		detailScroll.setPrefViewportHeight(180);
		summary.setId("contribution-review-summary");
		summary.setWrapText(true);
		Label notice = new Label("Queue Apply is available only for supported changes "
				+ "with a valid master assessment. " + "Unsupported or conflicting proposals cannot "
				+ "be forced into the master." + "\nHold retains unresolved evidence without applying. "
				+ "Reject explicitly declines the proposal.");
		notice.setWrapText(true);
		HBox decisions = new HBox(8, applyButton, skipButton, rejectButton, holdButton);
		decisions.setAlignment(Pos.CENTER_LEFT);
		HBox finalActions = new HBox(8, suggestedButton, approveButton);
		finalActions.setAlignment(Pos.CENTER_RIGHT);
		VBox content = new VBox(10, table, detailScroll, decisions, summary, notice, finalActions);
		content.setPadding(new Insets(12));
		VBox.setVgrow(table, Priority.ALWAYS);
		getDialogPane().setContent(content);
		getDialogPane().getButtonTypes().setAll(ButtonType.CLOSE);
		getDialogPane().setPrefSize(1100, 740);
		refresh();
	}

	/**
	 * Tests whether an assessed record can be queued for supported application.
	 *
	 * @param assessment preliminary master record assessment
	 * @return true only for supported types with ready new/update evidence
	 */
	static boolean canQueue(RecordAssessment assessment) {
		Objects.requireNonNull(assessment, "assessment");
		boolean supportedType = switch (assessment.recordType()) {
		case "SOURCE_QUESTION", "SHARED_QUESTION_CONTEXT", "QUESTION", "ANSWER", "QUESTION_IMAGE" -> true;
		default -> false;
		};
		return supportedType
				&& (assessment.status() == ReviewStatus.READY_NEW || assessment.status() == ReviewStatus.READY_UPDATE);
	}

	/** Whether the coordinator explicitly approved a complete review. */
	boolean approved() {
		return approved;
	}

	/**
	 * Returns explicitly approved choices for every proposed record.
	 *
	 * @return immutable complete decision map
	 * @throws IllegalStateException if approval was not given
	 */
	Map<RecordKey, Choice> decisions() {
		if (!approved) {
			throw new IllegalStateException("Contribution decisions have not been approved.");
		}
		Map<RecordKey, Choice> result = new LinkedHashMap<>();
		for (Row row : rows) {
			result.put(new RecordKey(row.assessment.recordType(), row.assessment.recordId()), row.choice);
		}
		return Map.copyOf(result);
	}

	private void choose(Choice choice) {
		Row selected = table.getSelectionModel().getSelectedItem();
		if (selected == null) {
			return;
		}
		if (choice == Choice.QUEUE_APPLY && !canQueue(selected.assessment)) {
			return;
		}
		if (choice == Choice.SKIP_IDENTICAL && selected.assessment.status() != ReviewStatus.SKIP_IDENTICAL) {
			return;
		}
		selected.choice = choice;
		refresh();
	}

	private String describe(Row row) {
		RecordAssessment assessment = row.assessment;
		String issued = row.baseline == null ? "New record; absent from issued baseline."
				: row.baseline.values().toPrettyString();
		return "Scope: " + row.scope + "\nRecord: " + assessment.recordType() + "\nPortable ID: "
				+ assessment.recordId() + "\nAssessment: " + assessment.status() + "\n\nIssued revision: "
				+ assessment.baselineRevisionId() + "\nObserved master revision: " + assessment.masterRevisionId()
				+ "\nObserved master SHA-256: " + assessment.masterRecordSha256() + "\nProposed revision: "
				+ assessment.proposedRevisionId() + "\n\nIssued baseline values:\n" + issued
				+ "\n\nProposed contributor values:\n" + row.proposal.values().toPrettyString()
				+ "\n\nThe master revision and digest are evidence, "
				+ "not permission to overwrite divergent master content.";
	}

	private UUID findBooklet(ExchangeRecord record, Map<RecordKey, ExchangeRecord> indexed, Set<RecordKey> visited) {
		RecordKey key = new RecordKey(record.recordType(), record.recordId());
		if (!visited.add(key)) {
			return null;
		}
		var values = record.values();
		var direct = values.path("booklet_id");
		if (direct.isTextual()) {
			return UUID.fromString(direct.asText());
		}
		Map<String, String> parents = Map.of("source_question_id", "SOURCE_QUESTION", "question_id", "QUESTION",
				"shared_context_id", "SHARED_QUESTION_CONTEXT", "answer_id", "ANSWER");
		for (Map.Entry<String, String> parent : parents.entrySet()) {
			var reference = values.path(parent.getKey());
			if (!reference.isTextual()) {
				continue;
			}
			UUID parentId = UUID.fromString(reference.asText());
			ExchangeRecord ancestor = indexed.get(new RecordKey(parent.getValue(), parentId));
			if (ancestor != null) {
				UUID booklet = findBooklet(ancestor, indexed, visited);
				if (booklet != null) {
					return booklet;
				}
			}
		}
		return null;
	}

	private void refresh() {
		Row selected = table.getSelectionModel().getSelectedItem();
		applyButton.setDisable(selected == null || !canQueue(selected.assessment));
		skipButton.setDisable(selected == null || selected.assessment.status() != ReviewStatus.SKIP_IDENTICAL);
		rejectButton.setDisable(selected == null);
		holdButton.setDisable(selected == null);
		if (selected != null) {
			detail.setText(describe(selected));
		}
		long undecided = rows.stream().filter(row -> row.choice == null).count();
		long applying = rows.stream().filter(row -> row.choice == Choice.QUEUE_APPLY).count();
		long skipped = rows.stream().filter(row -> row.choice == Choice.SKIP_IDENTICAL).count();
		long rejected = rows.stream().filter(row -> row.choice == Choice.REJECT).count();
		long held = rows.stream().filter(row -> row.choice == Choice.HOLD).count();
		Map<String, Long> byScope = rows.stream()
				.collect(Collectors.groupingBy(Row::scope, TreeMap::new, Collectors.counting()));
		String scopes = byScope.entrySet().stream().map(entry -> entry.getKey() + ": " + entry.getValue())
				.collect(Collectors.joining(" | "));
		summary.setText("Total: " + rows.size() + " | Queue Apply: " + applying + " | Skip: " + skipped + " | Reject: "
				+ rejected + " | Hold: " + held + " | Undecided: " + undecided + "\nExam/Booklet record counts: "
				+ scopes);
		approveButton.setDisable(undecided > 0 || held > 0);
		table.refresh();
	}

	private void suggestSupported() {
		long eligible = rows.stream().filter(row -> row.choice == null)
				.filter(row -> canQueue(row.assessment) || row.assessment.status() == ReviewStatus.SKIP_IDENTICAL)
				.count();
		if (eligible == 0) {
			return;
		}
		Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
		confirmation.initOwner(getDialogPane().getScene().getWindow());
		confirmation.setTitle("Select Supported Decisions");
		confirmation.setHeaderText("Select recommended decisions for " + eligible + " records?");
		confirmation.setContentText("Supported ready records will be queued for application."
				+ "\nIdentical records will be marked Skip." + "\nHeld or unsupported records remain undecided."
				+ "\n\nThis only changes the review selections; " + "it does not modify the master database.");
		if (confirmation.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
			return;
		}
		for (Row row : rows) {
			if (row.choice != null) {
				continue;
			}
			if (canQueue(row.assessment)) {
				row.choice = Choice.QUEUE_APPLY;
			} else if (row.assessment.status() == ReviewStatus.SKIP_IDENTICAL) {
				row.choice = Choice.SKIP_IDENTICAL;
			}
		}
		refresh();
	}

	private TableColumn<Row, String> textColumn(String title, Function<Row, String> value, int width) {
		TableColumn<Row, String> column = new TableColumn<>(title);
		column.setPrefWidth(width);
		column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
		return column;
	}

	private static final class Row {

		private final RecordAssessment assessment;
		private final ExchangeRecord baseline;
		private Choice choice;
		private final ExchangeRecord proposal;
		private final String scope;

		private Row(RecordAssessment assessment, ExchangeRecord baseline, ExchangeRecord proposal, String scope) {
			this.assessment = Objects.requireNonNull(assessment);
			this.baseline = baseline;
			this.proposal = Objects.requireNonNull(proposal);
			this.scope = Objects.requireNonNull(scope);
		}

		private String scope() {
			return scope;
		}
	}
}
