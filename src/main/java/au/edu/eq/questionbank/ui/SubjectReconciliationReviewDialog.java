package au.edu.eq.questionbank.ui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import au.edu.eq.questionbank.service.workpackage.CurriculumReconciler.Proposal;
import au.edu.eq.questionbank.service.workpackage.ImageReconciler.Evidence;
import au.edu.eq.questionbank.service.workpackage.ReconciliationReviewWriter;
import au.edu.eq.questionbank.service.workpackage.SubjectReconciliationPreviewService.AssetEvidence;
import au.edu.eq.questionbank.service.workpackage.SubjectReconciliationReview;
import au.edu.eq.questionbank.service.workpackage.SubjectReconciliationReview.Decision;
import au.edu.eq.questionbank.service.workpackage.SubjectReconciliationReview.Key;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Window;

/**
 * Coordinator's read-only two-way Subject reconciliation review dialog.
 *
 * <p>
 * Every portable record and logical asset has an explicit Accept, Reject or
 * Undecided decision. The original classifications, parent relationships and
 * image evidence remain visible and never change as a side effect of review.
 * Acceptance records a proposal only; this dialog cannot import records or
 * modify the authoritative SQLite database or managed asset tree.
 */
public final class SubjectReconciliationReviewDialog extends Dialog<Void> {

	private final Path managedDataRoot;
	private final SubjectReconciliationReview review;
	private final TableView<Row> table = new TableView<>();
	private final Label detail = new Label();
	private final Label summary = new Label();
	private final Button acceptIdenticalButton = new Button("Accept All Identical");
	private final ObservableList<Row> allRows = FXCollections.observableArrayList();
	private final ComboBox<ReviewFilter> filterBox = new ComboBox<>();
	private final FilteredList<Row> visibleRows = new FilteredList<>(allRows);
	private final Button importButton = new Button("Import approved...");
	private boolean importRequested;

	/**
	 * Creates a coordinator review dialog. Its import-request button remains
	 * disabled until every record and asset is decided and dependencies are safe.
	 * Requesting import does not itself alter persistent data.
	 *
	 * @param owner           application parent window
	 * @param preview         read-only reconciliation session
	 * @param managedDataRoot protected application data directory
	 */
	public SubjectReconciliationReviewDialog(Window owner, SubjectReconciliationReview preview, Path managedDataRoot) {
		initOwner(Objects.requireNonNull(owner, "owner"));
		initModality(Modality.WINDOW_MODAL);
		setTitle("Subject Reconciliation — Review and Approval");
		setHeaderText("Review the complete Subject comparison. Import requires separate confirmation.");
		review = Objects.requireNonNull(preview, "preview");
		this.managedDataRoot = Objects.requireNonNull(managedDataRoot, "managedDataRoot");
		getDialogPane().getButtonTypes().setAll(ButtonType.CLOSE);
		configureTable();
		configureContent();
		refreshSummary();
	}

	/**
	 * Indicates that the coordinator expressly requested import after review.
	 *
	 * @return true only after the enabled Import approved action was fired
	 */
	public boolean importRequested() {
		return importRequested;
	}

	/**
	 * Returns decisions recorded in this dialog, without persisting them.
	 *
	 * @return mutable, non-publishing coordinator review state
	 */
	public SubjectReconciliationReview review() {
		return review;
	}

	private void acceptAllIdentical() {
		var plan = review.planAcceptAllIdentical();
		if (plan.keys().isEmpty()) {
			return;
		}
		Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
		confirmation.initOwner(getDialogPane().getScene().getWindow());
		confirmation.setTitle("Accept All Identical");
		confirmation.setHeaderText(
				"Accept " + plan.recordCount() + " identical records and " + plan.assetCount() + " identical assets?");
		String explanation = "Only undecided IDENTICAL records and assets will be accepted. "
				+ "Existing decisions will not be changed. " + "No data will be imported.";
		if (plan.blockedCount() > 0) {
			explanation += "\n\n" + plan.blockedCount() + " additional identical items cannot be accepted "
					+ "because required dependencies are unresolved.";
		}
		confirmation.setContentText(explanation);
		confirmation.getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
		confirmation.getDialogPane().setId("examwork-review-bulk-confirmation");
		Button confirmButton = (Button) confirmation.getDialogPane().lookupButton(ButtonType.OK);
		confirmButton.setText("Accept all");
		var response = confirmation.showAndWait();
		if (response.isEmpty() || response.get() != ButtonType.OK) {
			return;
		}
		try {
			review.acceptAllIdentical();

			// Accepted items must disappear from Undecided views.
			refreshVisibleRows();
			acceptIdenticalButton.setDisable(review.planAcceptAllIdentical().keys().isEmpty());
			table.refresh();
			refreshSummary();
		} catch (IllegalArgumentException | IllegalStateException exception) {
			showError("Cannot accept identical items", exception.getMessage());
		}
	}

	/** Defines one plain-text cell without pointer-dependent interaction. */
	private TableColumn<Row, String> column(String title, java.util.function.Function<Row, String> value,
			double preferredWidth) {
		TableColumn<Row, String> column = new TableColumn<>(title);
		column.setPrefWidth(preferredWidth);
		column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
		return column;
	}

	/** Creates controls, detail preview, and explicit import request. */
	private void configureContent() {
		Button accept = new Button("Accept selected");
		accept.setId("examwork-review-accept");
		accept.setOnAction(_ -> decideSelected(Decision.ACCEPT));
		Button reject = new Button("Reject selected");
		reject.setId("examwork-review-reject");
		reject.setOnAction(_ -> decideSelected(Decision.REJECT));
		Button reset = new Button("Clear decision");
		reset.setId("examwork-review-reset");
		reset.setOnAction(_ -> decideSelected(Decision.UNDECIDED));
		acceptIdenticalButton.setId("examwork-review-accept-identical");
		acceptIdenticalButton.setOnAction(_ -> acceptAllIdentical());
		Button save = new Button("Save review report...");
		save.setId("examwork-review-save");
		save.setOnAction(_ -> saveReport());
		importButton.setId("examwork-review-request-import");
		importButton.setOnAction(_ -> {
			var snapshot = review.snapshot();
			if (!snapshot.complete() || !snapshot.dependencySafe()) {
				return;
			}
			importRequested = true;
			close();
		});
		filterBox.setId("examwork-review-filter");
		filterBox.getItems().setAll(ReviewFilter.values());
		filterBox.setValue(ReviewFilter.NEEDS_ATTENTION);
		filterBox.setPrefWidth(190);
		filterBox.valueProperty().addListener((_, _, _) -> refreshVisibleRows());
		HBox filterControls = new HBox(10, new Label("Show:"), filterBox);
		filterControls.setAlignment(Pos.CENTER_LEFT);
		HBox actions = new HBox(10, accept, reject, reset, acceptIdenticalButton, save, importButton);
		detail.setWrapText(true);
		detail.setMinHeight(66);
		detail.setText("Select a record or asset to inspect its reason and proposed relationship.");
		summary.setWrapText(true);
		Label warning = new Label("Review decisions alone never modify the master. "
				+ "Import additionally requires explicit conflict resolutions, a full backup and confirmation.");
		warning.setWrapText(true);
		VBox content = new VBox(10, filterControls, table, detail, actions, summary, warning);
		content.setPadding(new Insets(12));
		VBox.setVgrow(table, Priority.ALWAYS);
		content.setPrefSize(1100, 680);
		getDialogPane().setContent(content);
		table.getSelectionModel().selectedItemProperty().addListener((_, _, selected) -> {
			detail.setText(selected == null ? "Select a record or asset." : selected.details());
			boolean selectedPresent = selected != null;
			accept.setDisable(!selectedPresent);
			reject.setDisable(!selectedPresent);
			reset.setDisable(!selectedPresent);
		});
		accept.setDisable(true);
		reject.setDisable(true);
		reset.setDisable(true);
		importButton.setDisable(true);
		refreshVisibleRows();
	}

	/** Configures a searchable-by-scrolling, deterministic list of evidence. */
	private void configureTable() {
		table.setId("examwork-reconciliation-proposals");
		table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
		TableColumn<Row, String> type = column("Record / asset", Row::title, 190);
		TableColumn<Row, String> status = column("Classification", Row::classification, 160);
		TableColumn<Row, String> decision = column("Decision", row -> review.choice(row.key()).decision().name(), 130);
		TableColumn<Row, String> reason = column("Reason and provenance", Row::reason, 490);
		table.getColumns().addAll(List.of(type, status, decision, reason));
		List<Row> rows = new ArrayList<>();
		for (Proposal proposal : review.preview().proposals()) {
			String id = proposal.recordType() + ":" + proposal.incomingId();
			String parents = "Incoming parent: " + proposal.incomingParentId() + "\nProposed master parent: "
					+ proposal.masterParentId();
			String relationship = "Proposed master: " + proposal.masterId() + "\n" + parents;
			String additional = imageDetails(proposal.recordType(), proposal.incomingId());
			rows.add(new Row(SubjectReconciliationReview.recordKey(proposal.recordType(), proposal.incomingId()),
					proposal.recordType(), proposal.classification().name(), proposal.reason(),
					id + "\n" + relationship + "\n" + proposal.reason() + additional));
		}
		for (AssetEvidence asset : review.preview().assetEvidence()) {
			rows.add(new Row(SubjectReconciliationReview.assetKey(asset), "ASSET " + asset.usage(),
					asset.classification().name(), asset.reason(),
					"Incoming path: " + asset.incomingPath() + "\nMaster path: " + asset.masterPath() + "\nOwner: "
							+ asset.ownerType() + ":" + asset.incomingOwnerId() + "\nIncoming SHA-256: "
							+ asset.incomingSha256() + "\nMaster SHA-256: " + asset.masterSha256() + "\n"
							+ asset.reason()));
		}
		rows.sort(Comparator.comparing(Row::title).thenComparing(row -> row.key().value()));
		allRows.setAll(rows);

		// Keep filtering and column sorting independent.
		SortedList<Row> sortedRows = new SortedList<>(visibleRows);
		sortedRows.comparatorProperty().bind(table.comparatorProperty());
		table.setItems(sortedRows);
	}

	/**
	 * Applies one explicit choice, asking for rationale when a conflict is
	 * accepted.
	 */
	private void decideSelected(Decision decision) {
		Row selected = table.getSelectionModel().getSelectedItem();
		if (selected == null) {
			return;
		}

		// Capture the displayed order before filtering can remove
		// the selected row after its decision changes.
		List<Row> previousRows = List.copyOf(table.getItems());
		int previousIndex = table.getSelectionModel().getSelectedIndex();
		String explanation = "";
		if (decision == Decision.ACCEPT && "CONFLICT".equals(selected.classification())) {
			TextInputDialog explanationDialog = new TextInputDialog();
			explanationDialog.initOwner(getDialogPane().getScene().getWindow());
			explanationDialog.setTitle("Conflict review justification");
			explanationDialog.setHeaderText("Accepting this conflict does not import " + "or overwrite data");
			explanationDialog.setContentText("Reason (at least 12 characters):");
			var result = explanationDialog.showAndWait();
			if (result.isEmpty()) {
				return;
			}
			explanation = result.get();
		}
		try {
			review.decide(selected.key(), decision, explanation);
			refreshVisibleRows();
			table.refresh();
			refreshSummary();
			if (decision == Decision.ACCEPT || decision == Decision.REJECT) {
				selectNextUndecided(previousRows, previousIndex);
			}
		} catch (IllegalArgumentException | IllegalStateException exception) {
			showError("Cannot record decision", exception.getMessage());
		}
	}

	/** Shows image evidence beside its original capture proposal. */
	private String imageDetails(String type, UUID ownerId) {
		StringBuilder details = new StringBuilder();
		for (Evidence image : review.preview().imageEvidence()) {
			if (image.ownerType().equals(type) && image.incomingOwnerId().equals(ownerId)) {
				details.append("\nImage evidence: ").append(image.classification()).append(" — ")
						.append(image.reason());
			}
		}
		return details.toString();
	}

	/** Summarises review closure and enables import only when complete. */
	private void refreshSummary() {
		var state = review.snapshot();
		int total = review.reviewKeys().size();
		int reviewed = state.decisions().size();
		int remaining = total - reviewed;
		long conflicts = review.preview().proposals().stream()
				.filter(proposal -> proposal.classification().name().equals("CONFLICT")).count();
		String completion = state.complete() ? "All decisions recorded" : "Review in progress";
		summary.setText("Subject: " + review.preview().subjectName() + "   Reviewed: " + reviewed + "/" + total
				+ "   Remaining: " + remaining + "   Record conflicts: " + conflicts + "   Dependency closure: "
				+ (state.dependencySafe() ? "satisfied" : "incomplete") + "   " + completion
				+ "   No import performed");
		acceptIdenticalButton.setDisable(review.planAcceptAllIdentical().keys().isEmpty());
		importButton.setDisable(!state.complete() || !state.dependencySafe());
	}

	/**
	 * Applies the selected filter while retaining all review evidence.
	 */
	private void refreshVisibleRows() {
		Row selected = table.getSelectionModel().getSelectedItem();
		ReviewFilter filter = filterBox.getValue();
		if (filter == null) {
			filter = ReviewFilter.NEEDS_ATTENTION;
		}
		final ReviewFilter activeFilter = filter;
		visibleRows.setPredicate(row -> {
			Decision decision = review.choice(row.key()).decision();
			return switch (activeFilter) {
			case ALL -> true;
			case UNDECIDED -> decision == Decision.UNDECIDED;
			case NEEDS_ATTENTION -> decision == Decision.UNDECIDED && !"IDENTICAL".equals(row.classification());
			};
		});

		// Preserve selection only if the item is still visible.
		if (selected == null || !table.getItems().contains(selected)) {
			table.getSelectionModel().clearSelection();
		} else {
			table.getSelectionModel().select(selected);
		}
	}

	/**
	 * Saves the decisions and all original comparison evidence outside managed
	 * data.
	 */
	private void saveReport() {
		FileChooser chooser = new FileChooser();
		chooser.setTitle("Save reconciliation review report");
		chooser.setInitialFileName("examwork-" + review.preview().subjectName() + "-review.json");
		chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON review reports", "*.json"));
		var file = chooser.showSaveDialog(getDialogPane().getScene().getWindow());
		if (file == null) {
			return;
		}
		try {
			new ReconciliationReviewWriter(managedDataRoot).write(file.toPath(), review);
		} catch (IOException | IllegalArgumentException exception) {
			showError("Cannot save review report", exception.getMessage());
		}
	}

	/**
	 * Selects the next visible undecided row in the previous display order.
	 *
	 * <p>
	 * Search wraps to the beginning. When no undecided row remains visible,
	 * selection is cleared and the review summary remains.
	 */
	private void selectNextUndecided(List<Row> previousRows, int previousIndex) {
		if (previousRows.isEmpty() || previousIndex < 0) {
			table.getSelectionModel().clearSelection();
			return;
		}
		int count = previousRows.size();
		for (int offset = 1; offset <= count; offset++) {
			int index = (previousIndex + offset) % count;
			Row candidate = previousRows.get(index);
			if (!table.getItems().contains(candidate)) {
				continue;
			}
			if (review.choice(candidate.key()).decision() != Decision.UNDECIDED) {
				continue;
			}
			table.getSelectionModel().select(candidate);
			table.scrollTo(candidate);
			return;
		}
		table.getSelectionModel().clearSelection();
	}

	/** Presents a concise warning or I/O failure without masking data status. */
	private void showError(String title, String explanation) {
		Alert alert = new Alert(Alert.AlertType.ERROR);
		alert.initOwner(getDialogPane().getScene().getWindow());
		alert.setTitle(title);
		alert.setHeaderText(null);
		alert.setContentText(explanation);
		alert.showAndWait();
	}

	/**
	 * Available coordinator review display filters.
	 */
	private enum ReviewFilter {

		NEEDS_ATTENTION("Needs attention"), UNDECIDED("Undecided"), ALL("All");

		private final String label;

		ReviewFilter(String label) {
			this.label = label;
		}

		@Override
		public String toString() {
			return label;
		}
	}

	/** One visually inspectable record or asset and its stable decision key. */
	private record Row(Key key, String title, String classification, String reason, String details) {
	}
}
