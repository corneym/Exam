package au.edu.eq.questionbank.ui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import au.edu.eq.questionbank.service.workpackage.EqworkCurriculumReconciler.Proposal;
import au.edu.eq.questionbank.service.workpackage.EqworkImageReconciler.Evidence;
import au.edu.eq.questionbank.service.workpackage.EqworkReconciliationReview;
import au.edu.eq.questionbank.service.workpackage.EqworkReconciliationReview.Decision;
import au.edu.eq.questionbank.service.workpackage.EqworkReconciliationReview.Key;
import au.edu.eq.questionbank.service.workpackage.EqworkReviewPlanWriter;
import au.edu.eq.questionbank.service.workpackage.EqworkTwoWaySubjectPreviewService.AssetEvidence;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.stage.FileChooser;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
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
public final class EqworkReconciliationReviewDialog extends Dialog<Void> {

    private final Path managedDataRoot;
    private final EqworkReconciliationReview review;
    private final TableView<Row> table = new TableView<>();
    private final Label detail = new Label();
    private final Label summary = new Label();

    /**
     * Creates a window for inspecting and explicitly deciding a complete preview.
     *
     * @param owner parent application window
     * @param preview fresh, validated read-only review session
     * @param managedDataRoot protected authoritative application data directory
     */
    public EqworkReconciliationReviewDialog(Window owner, EqworkReconciliationReview preview,
            Path managedDataRoot) {
        initOwner(Objects.requireNonNull(owner, "owner"));
        initModality(Modality.WINDOW_MODAL);
        setTitle("Subject Reconciliation — Review Only");
        setHeaderText("Compare independent Subject snapshots — no records will be imported");
        review = Objects.requireNonNull(preview, "preview");
        this.managedDataRoot = Objects.requireNonNull(managedDataRoot, "managedDataRoot");
        getDialogPane().getButtonTypes().setAll(ButtonType.CLOSE);
        configureTable();
        configureContent();
        refreshSummary();
    }

    /**
     * Returns decisions recorded in this dialog, without persisting them.
     *
     * @return mutable, non-publishing coordinator review state
     */
    public EqworkReconciliationReview review() {
        return review;
    }

    /** Creates the controls and concise selection details. */
    private void configureContent() {
        Button accept = new Button("Accept selected");
        accept.setId("eqwork-review-accept");
        accept.setOnAction(_ -> decideSelected(Decision.ACCEPT));
        Button reject = new Button("Reject selected");
        reject.setId("eqwork-review-reject");
        reject.setOnAction(_ -> decideSelected(Decision.REJECT));
        Button reset = new Button("Clear decision");
        reset.setId("eqwork-review-reset");
        reset.setOnAction(_ -> decideSelected(Decision.UNDECIDED));
        Button save = new Button("Save review report...");
        save.setId("eqwork-review-save");
        save.setOnAction(_ -> saveReport());

        HBox actions = new HBox(10, accept, reject, reset, save);
        detail.setWrapText(true);
        detail.setMinHeight(66);
        detail.setText("Select a record or asset to inspect its reason and proposed relationship.");
        summary.setWrapText(true);
        Label warning = new Label("Preview only: accepting a conflict records a justified review choice, "
                + "not permission to overwrite data.");
        warning.setWrapText(true);
        VBox content = new VBox(10, table, detail, actions, summary, warning);
        content.setPadding(new Insets(12));
        VBox.setVgrow(table, Priority.ALWAYS);
        content.setPrefSize(1000, 680);
        getDialogPane().setContent(content);

        table.getSelectionModel().selectedItemProperty().addListener((_, _, selected) -> {
            detail.setText(selected == null ? "Select a record or asset." : selected.details());
            boolean hasSelection = selected != null;
            accept.setDisable(!hasSelection);
            reject.setDisable(!hasSelection);
            reset.setDisable(!hasSelection);
        });
        accept.setDisable(true);
        reject.setDisable(true);
        reset.setDisable(true);
    }

    /** Configures a searchable-by-scrolling, deterministic list of evidence. */
    private void configureTable() {
        table.setId("eqwork-reconciliation-proposals");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        TableColumn<Row, String> type = column("Record / asset", row -> row.title(), 190);
        TableColumn<Row, String> status = column("Classification", row -> row.classification(), 160);
        TableColumn<Row, String> decision = column("Decision", row ->
                review.choice(row.key()).decision().name(), 130);
        TableColumn<Row, String> reason = column("Reason and provenance", row -> row.reason(), 490);
        table.getColumns().addAll(type, status, decision, reason);
        List<Row> rows = new ArrayList<>();
        for (Proposal proposal : review.preview().proposals()) {
            String id = proposal.recordType() + ":" + proposal.incomingId();
            String parents = "Incoming parent: " + proposal.incomingParentId()
                    + "\nProposed master parent: " + proposal.masterParentId();
            String relationship = "Proposed master: " + proposal.masterId() + "\n" + parents;
            String additional = imageDetails(proposal.recordType(), proposal.incomingId());
            rows.add(new Row(EqworkReconciliationReview.recordKey(proposal.recordType(), proposal.incomingId()),
                    proposal.recordType(), proposal.classification().name(), proposal.reason(),
                    id + "\n" + relationship + "\n" + proposal.reason() + additional));
        }
        for (AssetEvidence asset : review.preview().assetEvidence()) {
            rows.add(new Row(EqworkReconciliationReview.assetKey(asset),
                    "ASSET " + asset.usage(), asset.classification().name(), asset.reason(),
                    "Incoming path: " + asset.incomingPath() + "\nMaster path: " + asset.masterPath()
                            + "\nOwner: " + asset.ownerType() + ":" + asset.incomingOwnerId()
                            + "\nIncoming SHA-256: " + asset.incomingSha256()
                            + "\nMaster SHA-256: " + asset.masterSha256()
                            + "\n" + asset.reason()));
        }
        rows.sort(Comparator.comparing(Row::title).thenComparing(row -> row.key().value()));
        table.setItems(FXCollections.observableArrayList(rows));
    }

    /** Defines one plain-text cell without pointer-dependent interaction. */
    private TableColumn<Row, String> column(String title, java.util.function.Function<Row, String> value,
            double preferredWidth) {
        TableColumn<Row, String> column = new TableColumn<>(title);
        column.setPrefWidth(preferredWidth);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        return column;
    }

    /** Applies one explicit choice, asking for rationale when a conflict is accepted. */
    private void decideSelected(Decision decision) {
        Row selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        String explanation = "";
        if (decision == Decision.ACCEPT && "CONFLICT".equals(selected.classification())) {
            TextInputDialog explanationDialog = new TextInputDialog();
            explanationDialog.initOwner(getDialogPane().getScene().getWindow());
            explanationDialog.setTitle("Conflict review justification");
            explanationDialog.setHeaderText("Accepting this conflict does not import or overwrite data");
            explanationDialog.setContentText("Reason (at least 12 characters):");
            var result = explanationDialog.showAndWait();
            if (result.isEmpty()) {
                return;
            }
            explanation = result.get();
        }
        try {
            review.decide(selected.key(), decision, explanation);
            table.refresh();
            refreshSummary();
        } catch (IllegalArgumentException | IllegalStateException exception) {
            showError("Cannot record decision", exception.getMessage());
        }
    }

    /** Shows image evidence beside its original capture proposal. */
    private String imageDetails(String type, UUID ownerId) {
        StringBuilder details = new StringBuilder();
        for (Evidence image : review.preview().imageEvidence()) {
            if (image.ownerType().equals(type) && image.incomingOwnerId().equals(ownerId)) {
                details.append("\nImage evidence: ").append(image.classification())
                        .append(" — ").append(image.reason());
            }
        }
        return details.toString();
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

    /** Saves the decisions and all original comparison evidence outside managed data. */
    private void saveReport() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save reconciliation review report");
        chooser.setInitialFileName("eqwork-" + review.preview().subjectName() + "-review.json");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON review reports", "*.json"));
        var file = chooser.showSaveDialog(getDialogPane().getScene().getWindow());
        if (file == null) {
            return;
        }
        try {
            new EqworkReviewPlanWriter(managedDataRoot).write(file.toPath(), review);
        } catch (IOException | IllegalArgumentException exception) {
            showError("Cannot save review report", exception.getMessage());
        }
    }

    /** Reports decision completeness and parent/child closure independently. */
    private void refreshSummary() {
        var state = review.snapshot();
        int total = review.reviewKeys().size();
        int reviewed = state.decisions().size();
        long conflicts = review.preview().proposals().stream()
                .filter(proposal -> proposal.classification().name().equals("CONFLICT")).count();
        summary.setText("Subject: " + review.preview().subjectName() + "   Reviewed: " + reviewed + "/" + total
                + "   Record conflicts: " + conflicts + "   Dependency closure: "
                + (state.dependencySafe() ? "satisfied" : "incomplete")
                + "   No import performed");
    }

    /** One visually inspectable record or asset and its stable decision key. */
    private record Row(Key key, String title, String classification, String reason, String details) {
    }
}
