package au.edu.eq.questionbank.ui;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.CurriculumMapping;
import au.edu.eq.questionbank.model.CurriculumMappingReviewOutcome;
import au.edu.eq.questionbank.model.CurriculumMappingSuggestion;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.MappingStatus;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.repository.CurriculumMappingRepository;
import au.edu.eq.questionbank.repository.CurriculumMappingReviewRepository;
import au.edu.eq.questionbank.repository.CurriculumRepository;
import au.edu.eq.questionbank.repository.SqliteCurriculumMappingReviewWriter;
import au.edu.eq.questionbank.service.CurriculumMappingSuggester;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.VPos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Window;

/**
 * Displays, confirms and edits descriptor mapping reviews between explicitly
 * selected syllabus versions.
 */
public final class CurriculumMappingReviewDialog extends Dialog<ButtonType> {
	private static final double SELECTOR_WIDTH = 180;
	private static final double CONTENT_WIDTH = 850;
	private static final double CONTROL_GAP = 10;
	private static final double DESCRIPTOR_TEXT_WIDTH = CONTENT_WIDTH - SELECTOR_WIDTH - CONTROL_GAP;
	private static final double DESCRIPTOR_TEXT_HEIGHT = 78;
	private final CurriculumRepository repository;
	private final CurriculumMappingSuggester suggester;
	private final CurriculumMappingReviewRepository reviewRepository;
	private final CurriculumMappingRepository mappingRepository;
	private final SqliteCurriculumMappingReviewWriter reviewWriter;
	private final ComboBox<Subject> subjectBox = new ComboBox<>();
	private final ComboBox<SyllabusVersion> sourceVersionBox = new ComboBox<>();
	private final ComboBox<SyllabusVersion> targetVersionBox = new ComboBox<>();
	private final ComboBox<CurriculumNode> sourceDescriptorBox = new ComboBox<>();
	private final Text sourceDescriptorText = new Text();
	private final TextFlow sourceDescriptorTextBox = new TextFlow(sourceDescriptorText);
	private final CheckBox showReviewedCheckBox = new CheckBox("Show reviewed descriptors only");
	private final Button editReviewButton = new Button("Edit review");
	private final ListView<CurriculumMappingSuggestion> suggestionsList = new ListView<>();
	private final ListView<CurriculumMapping> reviewedMappingsList = new ListView<>();
	private final CheckBox noMatchCheckBox = new CheckBox("No equivalent descriptor in target syllabus");
	private final Label statusLabel = new Label();
	private final Set<Long> selectedTargetIds = new HashSet<>();
	private final Set<Long> reviewedSourceIds = new HashSet<>();
	private final Set<Long> originalReviewedTargetIds = new HashSet<>();
	private final Set<Long> supplementalTargetIds = new HashSet<>();
	private Button confirmButton;
	private boolean editingReview;

	/**
	 * Creates the descriptor-review workflow. Suggestions remain unselected until
	 * the reviewer explicitly chooses one or more targets or records no match.
	 * Completed reviews are displayed read-only until edit mode is entered.
	 *
	 * @param owner            the window that owns this dialog
	 * @param repository       curriculum hierarchy lookup
	 * @param suggester        ranked descriptor suggestion service
	 * @param reviewRepository completed-review lookup
	 * @param mappingRepository directional mapping lookup
	 * @param reviewWriter     atomic review persistence boundary
	 * @throws NullPointerException if a repository, service or writer is
	 *                              {@code null}
	 */
	public CurriculumMappingReviewDialog(Window owner, CurriculumRepository repository,
			CurriculumMappingSuggester suggester, CurriculumMappingReviewRepository reviewRepository,
			CurriculumMappingRepository mappingRepository, SqliteCurriculumMappingReviewWriter reviewWriter) {
		if (repository == null) {
			throw new NullPointerException("repository");
		}
		if (suggester == null) {
			throw new NullPointerException("suggester");
		}
		if (reviewRepository == null) {
			throw new NullPointerException("reviewRepository");
		}
		if (mappingRepository == null) {
			throw new NullPointerException("mappingRepository");
		}
		if (reviewWriter == null) {
			throw new NullPointerException("reviewWriter");
		}
		this.repository = repository;
		this.suggester = suggester;
		this.reviewRepository = reviewRepository;
		this.mappingRepository = mappingRepository;
		this.reviewWriter = reviewWriter;
		setTitle("Curriculum Mapping");
		setHeaderText("Review curriculum descriptor mapping suggestions");
		initOwner(owner);
		ButtonType confirmButtonType = new ButtonType("Confirm", ButtonBar.ButtonData.APPLY);
		getDialogPane().getButtonTypes().addAll(confirmButtonType, ButtonType.CLOSE);
		setSelectorWidth(subjectBox);
		setSelectorWidth(sourceVersionBox);
		setSelectorWidth(targetVersionBox);
		setSelectorWidth(sourceDescriptorBox);
		sourceDescriptorBox.setButtonCell(new ListCell<>() {
			@Override
			protected void updateItem(CurriculumNode descriptor, boolean empty) {
				super.updateItem(descriptor, empty);
				if (empty || descriptor == null) {
					setText(null);
					return;
				}
				setText(descriptor.getCode());
			}
		});
		sourceDescriptorBox.setCellFactory(list -> new ListCell<>() {
			@Override
			protected void updateItem(CurriculumNode descriptor, boolean empty) {
				super.updateItem(descriptor, empty);
				setWrapText(true);
				setPrefWidth(800);
				if (empty || descriptor == null) {
					setText(null);
					return;
				}
				String reviewed = reviewedSourceIds.contains(descriptor.getId()) ? "    [reviewed]" : "";
				setText(descriptor.getCode() + "    " + descriptor.getName() + reviewed);
			}
		});
		sourceDescriptorTextBox.setMinWidth(DESCRIPTOR_TEXT_WIDTH);
		sourceDescriptorTextBox.setPrefWidth(DESCRIPTOR_TEXT_WIDTH);
		sourceDescriptorTextBox.setMaxWidth(DESCRIPTOR_TEXT_WIDTH);
		sourceDescriptorTextBox.setMinHeight(DESCRIPTOR_TEXT_HEIGHT);
		sourceDescriptorTextBox.setPrefHeight(DESCRIPTOR_TEXT_HEIGHT);
		sourceDescriptorTextBox.setMaxHeight(DESCRIPTOR_TEXT_HEIGHT);
		sourceDescriptorTextBox.setPadding(new Insets(6));
		sourceDescriptorTextBox
				.setStyle("-fx-border-color: #b0b0b0; -fx-border-width: 1; -fx-background-color: white;");
		HBox sourceDescriptorRow = new HBox(CONTROL_GAP, sourceDescriptorBox, sourceDescriptorTextBox);
		sourceDescriptorRow.setMinWidth(CONTENT_WIDTH);
		sourceDescriptorRow.setPrefWidth(CONTENT_WIDTH);
		sourceDescriptorRow.setMaxWidth(CONTENT_WIDTH);
		configureSuggestionsList();
		configureReviewedMappingsList();
		StackPane targetListPane = new StackPane(suggestionsList, reviewedMappingsList);
		targetListPane.setPrefWidth(CONTENT_WIDTH);
		targetListPane.setPrefHeight(320);
		showSuggestionsList();
		noMatchCheckBox.setDisable(true);
		noMatchCheckBox.setOnAction(event -> {
			if (noMatchCheckBox.isSelected()) {
				selectedTargetIds.clear();
				suggestionsList.refresh();
			}
			updateReviewStatus();
		});
		showReviewedCheckBox.setOnAction(event -> loadSourceDescriptors());
		editReviewButton.setDisable(true);
		editReviewButton.setOnAction(event -> {
			if (editingReview) {
				cancelEdit();
			} else {
				beginEditReview();
			}
		});
		HBox reviewOptions = new HBox(CONTROL_GAP, showReviewedCheckBox, editReviewButton);
		Label sourceDescriptorHeading = new Label("Source descriptor:");
		sourceDescriptorHeading.setPadding(new Insets(4, 0, 0, 0));
		GridPane.setValignment(sourceDescriptorHeading, VPos.TOP);
		GridPane grid = new GridPane();
		grid.setHgap(10);
		grid.setVgap(6);
		grid.setPadding(new Insets(10));
		grid.add(new Label("Subject:"), 0, 0);
		grid.add(subjectBox, 1, 0);
		grid.add(new Label("Source syllabus:"), 0, 1);
		grid.add(sourceVersionBox, 1, 1);
		grid.add(new Label("Target syllabus:"), 0, 2);
		grid.add(targetVersionBox, 1, 2);
		grid.add(sourceDescriptorHeading, 0, 3);
		grid.add(sourceDescriptorRow, 1, 3);
		grid.add(reviewOptions, 1, 4);
		grid.add(new Label("Suggested targets:"), 0, 5);
		grid.add(targetListPane, 1, 5);
		grid.add(noMatchCheckBox, 1, 6);
		grid.add(statusLabel, 1, 7);
		getDialogPane().setContent(grid);
		confirmButton = (Button) getDialogPane().lookupButton(confirmButtonType);
		confirmButton.setDisable(true);
		confirmButton.addEventFilter(ActionEvent.ACTION, event -> {
			event.consume();
			confirmReview();
		});
		subjectBox.valueProperty().addListener((observable, oldSubject, newSubject) -> loadVersions(newSubject));
		sourceVersionBox.valueProperty().addListener((observable, oldVersion, newVersion) -> loadSourceDescriptors());
		targetVersionBox.valueProperty().addListener((observable, oldVersion, newVersion) -> loadSourceDescriptors());
		sourceDescriptorBox.valueProperty().addListener((observable, oldDescriptor, newDescriptor) -> {
			resetEditMode();
			clearReviewSelection();
			updateSourceDescriptorText(newDescriptor);
			updateSelectedDescriptor();
		});
		subjectBox.getItems().setAll(repository.findAllSubjects());
	}

	private void beginEditReview() {
		CurriculumNode source = sourceDescriptorBox.getValue();
		SyllabusVersion targetVersion = targetVersionBox.getValue();
		if (source == null || targetVersion == null) {
			return;
		}
		Optional<CurriculumMappingReviewOutcome> outcome = reviewRepository.findOutcome(source, targetVersion);
		if (outcome.isEmpty()) {
			return;
		}
		editingReview = true;
		editReviewButton.setText("Cancel edit");
		editReviewButton.setDisable(false);
		selectedTargetIds.clear();
		originalReviewedTargetIds.clear();
		supplementalTargetIds.clear();
		suggestionsList.getItems().clear();
		reviewedMappingsList.getItems().clear();
		showSuggestionsList();
		noMatchCheckBox.setDisable(false);
		List<CurriculumMapping> existingMappings = findReviewedMappings(source, targetVersion);
		if (outcome.get() == CurriculumMappingReviewOutcome.NO_MATCH) {
			noMatchCheckBox.setSelected(true);
		} else {
			noMatchCheckBox.setSelected(false);
			for (CurriculumMapping mapping : existingMappings) {
				long targetId = mapping.getTarget().getId();
				selectedTargetIds.add(targetId);
				originalReviewedTargetIds.add(targetId);
			}
		}
		suggestionsList.getItems().setAll(createEditableSuggestions(source, targetVersion, existingMappings));
		suggestionsList.refresh();
		updateReviewStatus();
	}

	private void cancelEdit() {
		resetEditMode();
		clearReviewSelection();
		updateSelectedDescriptor();
	}

	private void clearReviewSelection() {
		selectedTargetIds.clear();
		noMatchCheckBox.setSelected(false);
		suggestionsList.refresh();
		updateConfirmButtonState();
	}

	private void collectDescriptors(CurriculumNode node, List<CurriculumNode> descriptors) {
		if (node.getLevel() == CurriculumLevel.DESCRIPTOR) {
			descriptors.add(node);
			return;
		}
		for (CurriculumNode child : repository.findChildren(node)) {
			collectDescriptors(child, descriptors);
		}
	}

	private void configureReviewedMappingsList() {
		reviewedMappingsList.setPrefWidth(CONTENT_WIDTH);
		reviewedMappingsList.setPrefHeight(320);
		reviewedMappingsList.setCellFactory(list -> new ListCell<>() {
			@Override
			protected void updateItem(CurriculumMapping mapping, boolean empty) {
				super.updateItem(mapping, empty);
				setWrapText(true);
				if (empty || mapping == null) {
					setText(null);
					return;
				}
				CurriculumNode target = mapping.getTarget();
				setText(target.getCode() + "    " + target.getName());
			}
		});
	}

	private void configureSuggestionsList() {
		suggestionsList.setPrefWidth(CONTENT_WIDTH);
		suggestionsList.setPrefHeight(320);
		suggestionsList.setCellFactory(list -> new ListCell<>() {
			private final CheckBox checkBox = new CheckBox();
			{
				checkBox.setWrapText(true);
				checkBox.setMaxWidth(CONTENT_WIDTH - 20);
				checkBox.setOnAction(event -> updateTargetSelection(getItem(), checkBox.isSelected()));
				setGraphic(checkBox);
			}

			@Override
			protected void updateItem(CurriculumMappingSuggestion suggestion, boolean empty) {
				super.updateItem(suggestion, empty);
				if (empty || suggestion == null) {
					checkBox.setText("");
					checkBox.setSelected(false);
					setGraphic(null);
					return;
				}
				long targetId = suggestion.getTarget().getId();
				String text;
				if (editingReview && supplementalTargetIds.contains(targetId)) {
					text = "[current]    " + suggestion.getTarget().getCode() + "    "
							+ suggestion.getTarget().getName();
				} else {
					String score = String.format(Locale.ROOT, "%.3f", suggestion.getScore());
					String current = editingReview && originalReviewedTargetIds.contains(targetId) ? "    [current]"
							: "";
					text = score + "    " + suggestion.getTarget().getCode() + "    " + suggestion.getTarget().getName()
							+ current;
				}
				checkBox.setText(text);
				checkBox.setSelected(selectedTargetIds.contains(targetId));
				setGraphic(checkBox);
			}
		});
	}

	private void confirmReview() {
		CurriculumNode source = sourceDescriptorBox.getValue();
		SyllabusVersion targetVersion = targetVersionBox.getValue();
		if (source == null || targetVersion == null) {
			return;
		}
		long sourceId = source.getId();
		try {
			if (editingReview) {
				if (noMatchCheckBox.isSelected()) {
					reviewWriter.replaceWithNoMatch(source, targetVersion);
				} else {
					List<CurriculumNode> targets = selectedTargets();
					if (targets.isEmpty()) {
						updateReviewStatus();
						return;
					}
					reviewWriter.replaceMappings(source, targetVersion, targets);
				}
			} else {
				if (noMatchCheckBox.isSelected()) {
					reviewWriter.confirmNoMatch(source, targetVersion);
				} else {
					List<CurriculumNode> targets = selectedTargets();
					if (targets.isEmpty()) {
						updateReviewStatus();
						return;
					}
					reviewWriter.confirmMappings(source, targetVersion, targets);
				}
			}
			resetEditMode();
			loadSourceDescriptors(sourceId);
		} catch (SQLException e) {
			showSaveError("Could not save the curriculum mapping review.", e.getMessage());
		} catch (IllegalArgumentException | IllegalStateException e) {
			showSaveError("Could not confirm the curriculum mapping review.", e.getMessage());
		}
	}

	private List<CurriculumMappingSuggestion> createEditableSuggestions(CurriculumNode source,
			SyllabusVersion targetVersion, List<CurriculumMapping> existingMappings) {
		List<CurriculumMappingSuggestion> suggestions = new ArrayList<>(suggester.suggest(source, targetVersion));
		Set<Long> suggestionTargetIds = new HashSet<>();
		for (CurriculumMappingSuggestion suggestion : suggestions) {
			suggestionTargetIds.add(suggestion.getTarget().getId());
		}
		for (CurriculumMapping mapping : existingMappings) {
			CurriculumNode target = mapping.getTarget();
			if (!suggestionTargetIds.contains(target.getId())) {
				suggestions.add(new CurriculumMappingSuggestion(source, target, 0.0));
				supplementalTargetIds.add(target.getId());
			}
		}
		return suggestions;
	}

	private List<CurriculumNode> findDescriptors(SyllabusVersion version) {
		List<CurriculumNode> descriptors = new ArrayList<>();
		for (CurriculumNode root : repository.findRootNodes(version)) {
			collectDescriptors(root, descriptors);
		}
		return descriptors;
	}

	private List<CurriculumMapping> findReviewedMappings(CurriculumNode source, SyllabusVersion targetVersion) {
		List<CurriculumMapping> confirmedMappings = new ArrayList<>();
		for (CurriculumMapping mapping : mappingRepository.findTargets(source)) {
			if (mapping.getStatus() == MappingStatus.CONFIRMED
					&& mapping.getTarget().getSyllabusVersion().equals(targetVersion)) {
				confirmedMappings.add(mapping);
			}
		}
		return confirmedMappings;
	}

	private void loadReviewedDescriptor(CurriculumNode source, SyllabusVersion targetVersion,
			CurriculumMappingReviewOutcome outcome) {
		showReviewedMappingsList();
		noMatchCheckBox.setSelected(false);
		noMatchCheckBox.setDisable(true);
		confirmButton.setDisable(true);
		editReviewButton.setText("Edit review");
		editReviewButton.setDisable(false);
		reviewedMappingsList.getItems().clear();
		if (outcome == CurriculumMappingReviewOutcome.NO_MATCH) {
			statusLabel.setText("Reviewed: no equivalent descriptor");
			return;
		}
		List<CurriculumMapping> mappings = findReviewedMappings(source, targetVersion);
		reviewedMappingsList.getItems().setAll(mappings);
		if (mappings.isEmpty()) {
			statusLabel.setText("Reviewed: matched, but no confirmed targets were found");
		} else if (mappings.size() == 1) {
			statusLabel.setText("Reviewed: 1 confirmed target");
		} else {
			statusLabel.setText("Reviewed: " + mappings.size() + " confirmed targets");
		}
	}

	private void loadSourceDescriptors() {
		loadSourceDescriptors(0);
	}

	private void loadSourceDescriptors(long preferredSourceId) {
		resetEditMode();
		sourceDescriptorBox.getItems().clear();
		sourceDescriptorText.setText("");
		suggestionsList.getItems().clear();
		reviewedMappingsList.getItems().clear();
		clearReviewSelection();
		noMatchCheckBox.setDisable(true);
		showSuggestionsList();
		reviewedSourceIds.clear();
		SyllabusVersion sourceVersion = sourceVersionBox.getValue();
		SyllabusVersion targetVersion = targetVersionBox.getValue();
		if (sourceVersion == null || targetVersion == null) {
			statusLabel.setText("");
			return;
		}
		if (sourceVersion.equals(targetVersion)) {
			statusLabel.setText("Source and target syllabus versions must be different.");
			return;
		}
		reviewedSourceIds.addAll(reviewRepository.findReviewedSourceIds(sourceVersion, targetVersion));
		List<CurriculumNode> availableDescriptors = new ArrayList<>();
		for (CurriculumNode descriptor : findDescriptors(sourceVersion)) {
			boolean reviewed = reviewedSourceIds.contains(descriptor.getId());
			if (showReviewedCheckBox.isSelected()) {
				if (reviewed) {
					availableDescriptors.add(descriptor);
				}
			} else {
				if (!reviewed) {
					availableDescriptors.add(descriptor);
				}
			}
		}
		sourceDescriptorBox.getItems().setAll(availableDescriptors);
		if (availableDescriptors.isEmpty()) {
			if (showReviewedCheckBox.isSelected()) {
				statusLabel.setText("No reviewed descriptors for these syllabus versions.");
			} else {
				statusLabel.setText("All descriptors have been reviewed for these syllabus versions.");
			}
			updateConfirmButtonState();
			return;
		}
		CurriculumNode preferredDescriptor = null;
		if (preferredSourceId > 0) {
			for (CurriculumNode descriptor : availableDescriptors) {
				if (descriptor.getId() == preferredSourceId) {
					preferredDescriptor = descriptor;
					break;
				}
			}
		}
		if (preferredDescriptor != null) {
			sourceDescriptorBox.setValue(preferredDescriptor);
		} else {
			sourceDescriptorBox.getSelectionModel().selectFirst();
		}
	}

	private void loadVersions(Subject subject) {
		resetEditMode();
		sourceVersionBox.getItems().clear();
		targetVersionBox.getItems().clear();
		sourceDescriptorBox.getItems().clear();
		sourceDescriptorText.setText("");
		suggestionsList.getItems().clear();
		reviewedMappingsList.getItems().clear();
		reviewedSourceIds.clear();
		clearReviewSelection();
		noMatchCheckBox.setDisable(true);
		showSuggestionsList();
		statusLabel.setText("");
		if (subject == null) {
			return;
		}
		List<SyllabusVersion> versions = repository.findVersionsForSubject(subject);
		sourceVersionBox.getItems().setAll(versions);
		targetVersionBox.getItems().setAll(versions);
		for (SyllabusVersion version : versions) {
			if (version.isCurrent()) {
				targetVersionBox.setValue(version);
				break;
			}
		}
	}

	private void resetEditMode() {
		editingReview = false;
		originalReviewedTargetIds.clear();
		supplementalTargetIds.clear();
		editReviewButton.setText("Edit review");
		editReviewButton.setDisable(true);
	}

	private List<CurriculumNode> selectedTargets() {
		List<CurriculumNode> targets = new ArrayList<>();
		for (CurriculumMappingSuggestion suggestion : suggestionsList.getItems()) {
			if (selectedTargetIds.contains(suggestion.getTarget().getId())) {
				targets.add(suggestion.getTarget());
			}
		}
		return targets;
	}

	private void setSelectorWidth(ComboBox<?> comboBox) {
		comboBox.setMinWidth(SELECTOR_WIDTH);
		comboBox.setPrefWidth(SELECTOR_WIDTH);
		comboBox.setMaxWidth(SELECTOR_WIDTH);
	}

	private void showReviewedMappingsList() {
		suggestionsList.setVisible(false);
		suggestionsList.setManaged(false);
		reviewedMappingsList.setVisible(true);
		reviewedMappingsList.setManaged(true);
	}

	private void showSaveError(String header, String message) {
		Alert alert = new Alert(Alert.AlertType.ERROR);
		alert.setTitle("Curriculum Mapping");
		alert.setHeaderText(header);
		alert.setContentText(message);
		alert.initOwner(getOwner());
		alert.showAndWait();
	}

	private void showSuggestionsList() {
		reviewedMappingsList.setVisible(false);
		reviewedMappingsList.setManaged(false);
		suggestionsList.setVisible(true);
		suggestionsList.setManaged(true);
	}

	private void updateConfirmButtonState() {
		if (confirmButton == null) {
			return;
		}
		CurriculumNode source = sourceDescriptorBox.getValue();
		SyllabusVersion targetVersion = targetVersionBox.getValue();
		if (source != null && reviewedSourceIds.contains(source.getId()) && !editingReview) {
			confirmButton.setDisable(true);
			return;
		}
		boolean reviewSelected = noMatchCheckBox.isSelected() || !selectedTargetIds.isEmpty();
		boolean validVersions = source != null && targetVersion != null
				&& !source.getSyllabusVersion().equals(targetVersion);
		confirmButton.setDisable(!validVersions || !reviewSelected);
	}

	private void updateReviewStatus() {
		String prefix = editingReview ? "Editing review: " : "";
		if (noMatchCheckBox.isSelected()) {
			statusLabel.setText(prefix + "no equivalent descriptor");
			updateConfirmButtonState();
			return;
		}
		if (selectedTargetIds.isEmpty()) {
			if (editingReview) {
				statusLabel.setText("Editing review: no target descriptor selected");
			} else {
				statusLabel.setText(suggestionsList.getItems().size() + " candidate mappings");
			}
			updateConfirmButtonState();
			return;
		}
		if (selectedTargetIds.size() == 1) {
			statusLabel.setText(prefix + "1 target descriptor selected");
		} else {
			statusLabel.setText(prefix + selectedTargetIds.size() + " target descriptors selected");
		}
		updateConfirmButtonState();
	}

	private void updateSelectedDescriptor() {
		suggestionsList.getItems().clear();
		reviewedMappingsList.getItems().clear();
		CurriculumNode source = sourceDescriptorBox.getValue();
		SyllabusVersion targetVersion = targetVersionBox.getValue();
		if (source == null || targetVersion == null) {
			noMatchCheckBox.setDisable(true);
			editReviewButton.setDisable(true);
			updateConfirmButtonState();
			return;
		}
		if (source.getSyllabusVersion().equals(targetVersion)) {
			statusLabel.setText("Source and target syllabus versions must be different.");
			noMatchCheckBox.setDisable(true);
			editReviewButton.setDisable(true);
			updateConfirmButtonState();
			return;
		}
		Optional<CurriculumMappingReviewOutcome> outcome = reviewRepository.findOutcome(source, targetVersion);
		if (outcome.isPresent()) {
			loadReviewedDescriptor(source, targetVersion, outcome.get());
			return;
		}
		showSuggestionsList();
		noMatchCheckBox.setDisable(false);
		editReviewButton.setDisable(true);
		List<CurriculumMappingSuggestion> suggestions = suggester.suggest(source, targetVersion);
		suggestionsList.getItems().setAll(suggestions);
		updateReviewStatus();
	}

	private void updateSourceDescriptorText(CurriculumNode descriptor) {
		if (descriptor == null) {
			sourceDescriptorText.setText("");
			return;
		}
		sourceDescriptorText.setText(descriptor.getName());
	}

	private void updateTargetSelection(CurriculumMappingSuggestion suggestion, boolean selected) {
		if (suggestion == null) {
			return;
		}
		long targetId = suggestion.getTarget().getId();
		if (selected) {
			selectedTargetIds.add(targetId);
			noMatchCheckBox.setSelected(false);
		} else {
			selectedTargetIds.remove(targetId);
		}
		updateReviewStatus();
	}
}
