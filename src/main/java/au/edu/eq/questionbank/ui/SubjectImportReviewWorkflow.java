package au.edu.eq.questionbank.ui;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.workpackage.CurriculumReconciler.Classification;
import au.edu.eq.questionbank.service.workpackage.SubjectImportApprovalPlan;
import au.edu.eq.questionbank.service.workpackage.SubjectImportApprovalPlan.ConflictMode;
import au.edu.eq.questionbank.service.workpackage.SubjectImportApprovalPlan.ConflictResolution;
import au.edu.eq.questionbank.service.workpackage.SubjectImportCoordinator;
import au.edu.eq.questionbank.service.workpackage.SubjectImportSnapshotFingerprint;
import au.edu.eq.questionbank.service.workpackage.SubjectReconciliationReview;
import au.edu.eq.questionbank.service.workpackage.SubjectReconciliationReview.Decision;
import au.edu.eq.questionbank.service.workpackage.SubjectReconciliationReview.Key;
import javafx.concurrent.Task;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.TextInputDialog;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;

/**
 * Explicit coordinator authorisation after read-only Subject reconciliation.
 *
 * <p>
 * Completing a review never automatically publishes changes. Every accepted
 * conflict receives a supported non-destructive resolution, a backup directory,
 * an audit note and an explicit final confirmation.
 */
public final class SubjectImportReviewWorkflow {

	private final Window owner;
	private final ApplicationConfig config;
	private final SubjectReconciliationReview review;
	private final Path masterArchive;
	private final Path incomingArchive;
	private final Runnable refreshAfterImport;
	private final Runnable cleanupAfterImport;

	/**
	 * Creates the approval workflow for an existing pair of reviewed archives.
	 *
	 * @param owner              application owner
	 * @param config             coordinator configuration
	 * @param review             completed review decisions
	 * @param masterArchive      reviewed master snapshot
	 * @param incomingArchive    reviewed incoming snapshot
	 * @param refreshAfterImport refresh after successful publication
	 */
	public SubjectImportReviewWorkflow(Window owner, ApplicationConfig config, SubjectReconciliationReview review,
			Path masterArchive, Path incomingArchive, Runnable refreshAfterImport) {
		this(owner, config, review, masterArchive, incomingArchive, refreshAfterImport, () -> {
		});
	}

	/**
	 * Creates a review workflow that owns temporary snapshot cleanup.
	 *
	 * <p>
	 * The supplied cleanup action runs if approval is abandoned, or after the
	 * asynchronous import reaches a terminal state. It must not remove the reviewed
	 * master archive while publication is in progress.
	 *
	 * @param owner              application owner
	 * @param config             coordinator configuration
	 * @param review             completed review decisions
	 * @param masterArchive      reviewed master snapshot
	 * @param incomingArchive    reviewed incoming snapshot
	 * @param refreshAfterImport refresh after successful publication
	 * @param cleanupAfterImport cleanup when the workflow finishes
	 * @throws NullPointerException if any argument is null
	 */
	public SubjectImportReviewWorkflow(Window owner, ApplicationConfig config, SubjectReconciliationReview review,
			Path masterArchive, Path incomingArchive, Runnable refreshAfterImport, Runnable cleanupAfterImport) {
		this.owner = Objects.requireNonNull(owner, "owner");
		this.config = Objects.requireNonNull(config, "config");
		this.review = Objects.requireNonNull(review, "review");
		this.masterArchive = Objects.requireNonNull(masterArchive, "masterArchive");
		this.incomingArchive = Objects.requireNonNull(incomingArchive, "incomingArchive");
		this.refreshAfterImport = Objects.requireNonNull(refreshAfterImport, "refreshAfterImport");
		this.cleanupAfterImport = Objects.requireNonNull(cleanupAfterImport, "cleanupAfterImport");
	}

	/**
	 * Dismisses a non-cancellable publication dialog after its task has reached a
	 * terminal state.
	 *
	 * <p>
	 * The dialog has no close buttons while publication is active. Once work has
	 * finished, restore a valid JavaFX close mechanism before requesting closure.
	 */
	static void dismissCompletedProgress(Alert progress) {
		Objects.requireNonNull(progress, "progress");
		progress.setOnCloseRequest(null);
		progress.getButtonTypes().setAll(ButtonType.CLOSE);
		progress.close();
	}

	/** Presents explicit approval and starts import only after confirmation. */
	public void run() {
		boolean importStarted = false;
		try {
			if (!review.snapshot().complete() || !review.snapshot().dependencySafe()) {
				error("Review must be complete and dependency-safe before import");
				return;
			}
			var master = new SubjectImportSnapshotFingerprint().inspect(masterArchive);
			var incoming = new SubjectImportSnapshotFingerprint().inspect(incomingArchive);
			Map<Key, ConflictResolution> resolutions = resolveConflicts();
			if (resolutions == null) {
				return;
			}
			SubjectImportApprovalPlan plan = SubjectImportApprovalPlan.prepare(review, master, incoming, resolutions);
			long masterSubjectId = findExistingMasterSubject(master.subjectName());
			DirectoryChooser chooser = new DirectoryChooser();
			chooser.setTitle("Select full-backup destination for approved Subject import");
			var folder = chooser.showDialog(owner);
			if (folder == null) {
				return;
			}
			TextInputDialog reason = new TextInputDialog();
			reason.initOwner(owner);
			reason.setTitle("Record Subject import authorisation");
			reason.setHeaderText("Coordinator approval note for " + master.subjectName());
			reason.setContentText("Audit reason:");
			var note = reason.showAndWait();
			if (note.isEmpty() || note.get().isBlank()) {
				return;
			}
			Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
			confirmation.initOwner(owner);
			confirmation.setTitle("Confirm approved Subject import");
			confirmation.setHeaderText("Import approved records into the master database?");
			confirmation.setContentText("Subject: " + master.subjectName() + "\nAccepted actions: "
					+ plan.actions().stream().filter(action -> action.decision() == Decision.ACCEPT).count()
					+ "\nBackup destination: " + folder + "\n\nThe application will revalidate master state "
					+ "before importing." + "\nExisting master files will not be overwritten.");
			ButtonType importType = new ButtonType("Import approved changes");
			confirmation.getButtonTypes().setAll(importType, ButtonType.CANCEL);
			if (confirmation.showAndWait().orElse(ButtonType.CANCEL) != importType) {
				return;
			}
			startImport(masterSubjectId, plan, folder.toPath(), note.get());
			importStarted = true;
		} catch (IOException | SQLException | IllegalArgumentException | IllegalStateException failure) {
			error(failure.getMessage());
		} finally {

			// Before asynchronous publication starts, cancellation owns
			// immediate cleanup. After it starts, terminal task handlers own it.
			if (!importStarted) {
				cleanupAfterImport.run();
			}
		}
	}

	/** Shows a fail-closed coordinator error without publishing changes. */
	private void error(String message) {
		Alert alert = new Alert(Alert.AlertType.ERROR);
		alert.initOwner(owner);
		alert.setTitle("Subject import blocked");
		alert.setHeaderText("The approved Subject import could not be completed");
		alert.setContentText(message == null ? "Unknown import failure" : message);
		alert.showAndWait();
	}

	/** Finds the unique reviewed Subject in the active master database. */
	private long findExistingMasterSubject(String subject) throws SQLException {
		try (Connection connection = new SqliteDatabase(config.databasePath()).openConnection();
				PreparedStatement statement = connection
						.prepareStatement("SELECT id FROM subjects WHERE subject_name = ?")) {
			statement.setString(1, subject);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new SQLException("The active master has no reviewed Subject: " + subject);
				}
				long id = result.getLong(1);
				if (result.next()) {
					throw new SQLException("Master Subject identity is ambiguous");
				}
				return id;
			}
		}
	}

	/** Explicitly resolves accepted conflicts with non-destructive operations. */
	private Map<Key, ConflictResolution> resolveConflicts() {
		Map<Key, ConflictResolution> result = new HashMap<>();
		for (var proposal : review.preview().proposals()) {
			Key key = SubjectReconciliationReview.recordKey(proposal.recordType(), proposal.incomingId());
			if (proposal.classification() != Classification.CONFLICT
					|| review.choice(key).decision() != Decision.ACCEPT) {
				continue;
			}
			boolean mayLink = List.of("SUBJECT", "SYLLABUS_VERSION", "CURRICULUM_NODE", "EXAM_PROVIDER")
					.contains(proposal.recordType());
			List<ConflictMode> options = mayLink ? List.of(ConflictMode.PRESERVE_MASTER, ConflictMode.LINK_TO_MASTER)
					: List.of(ConflictMode.PRESERVE_MASTER);
			ChoiceDialog<ConflictMode> dialog = new ChoiceDialog<>(null, options);
			dialog.initOwner(owner);
			dialog.setTitle("Resolve accepted record conflict");
			dialog.setHeaderText(proposal.recordType() + ": " + proposal.incomingId());
			dialog.setContentText(proposal.reason() + "\nSelect a non-destructive resolution:");
			var choice = dialog.showAndWait();
			if (choice.isEmpty() || choice.get() == null) {
				return null;
			}
			result.put(key, new ConflictResolution(choice.get(), proposal.masterId(), null,
					"Coordinator approved: " + review.choice(key).justification()));
		}
		for (var asset : review.preview().assetEvidence()) {
			Key key = SubjectReconciliationReview.assetKey(asset);
			if (asset.classification() != Classification.CONFLICT || review.choice(key).decision() != Decision.ACCEPT) {
				continue;
			}
			Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
			confirmation.initOwner(owner);
			confirmation.setTitle("Resolve accepted asset conflict");
			confirmation.setHeaderText("Preserve existing master asset unchanged?");
			confirmation.setContentText(asset.incomingPath() + "\nMaster SHA-256: " + asset.masterSha256()
					+ "\nIncoming conflicting bytes will not be published.");
			if (confirmation.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
				return null;
			}
			result.put(key, new ConflictResolution(ConflictMode.PRESERVE_MASTER, null, asset.masterSha256(),
					"Coordinator explicitly retained original master asset"));
		}
		return result;
	}

	/** Runs validated import off the JavaFX application thread. */
	private void startImport(long masterSubjectId, SubjectImportApprovalPlan plan, Path backupDirectory, String note) {
		Task<SubjectImportCoordinator.Result> task = new Task<>() {

			@Override
			protected SubjectImportCoordinator.Result call() throws Exception {
				return new SubjectImportCoordinator(config).execute(masterSubjectId, plan, incomingArchive,
						backupDirectory, note);
			}
		};
		Alert progress = new Alert(Alert.AlertType.INFORMATION);
		progress.initOwner(owner);
		progress.setTitle("Publishing approved Subject import");
		progress.setHeaderText("Verifying backup and committing approved Subject data");
		progress.setContentText("Publication cannot be cancelled once started.");
		progress.getButtonTypes().clear();
		progress.setOnCloseRequest(event -> event.consume());
		task.setOnSucceeded(_ -> {
			dismissCompletedProgress(progress);
			var result = task.getValue();
			try {
				if (result.applied()) {
					refreshAfterImport.run();
				}
			} finally {
				cleanupAfterImport.run();
			}
			Alert success = new Alert(Alert.AlertType.INFORMATION);
			success.initOwner(owner);
			success.setTitle("Subject import result");
			success.setHeaderText(result.applied() ? "Subject import committed" : "Package already rejected");
			success.setContentText((result.replay() ? "Idempotent replay. " : "New import completed. ") + "\nBackup: "
					+ result.backupArchivePath());
			success.showAndWait();
		});
		task.setOnFailed(_ -> {
			dismissCompletedProgress(progress);
			cleanupAfterImport.run();
			Throwable failure = task.getException();
			error(failure == null ? "Unknown import failure" : failure.getMessage());
		});
		task.setOnCancelled(_ -> {
			dismissCompletedProgress(progress);
			cleanupAfterImport.run();
		});
		Thread worker = new Thread(task, "examwork-subject-import");
		worker.setDaemon(true);
		progress.show();
		worker.start();
	}
}
