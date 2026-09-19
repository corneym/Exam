package au.edu.eq.questionbank.ui.curriculum;

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
import au.edu.eq.questionbank.repository.curriculum.CurriculumMappingRepository;
import au.edu.eq.questionbank.repository.curriculum.CurriculumMappingReviewRepository;
import au.edu.eq.questionbank.repository.curriculum.CurriculumRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumMappingReviewWriter;
import au.edu.eq.questionbank.service.curriculum.CurriculumMappingCoverage;
import au.edu.eq.questionbank.service.curriculum.CurriculumMappingCoverageService;
import au.edu.eq.questionbank.service.curriculum.CurriculumMappingCoverageStatus;
import au.edu.eq.questionbank.service.curriculum.CurriculumMappingLevelCoverage;
import au.edu.eq.questionbank.service.curriculum.CurriculumMappingSuggester;
import au.edu.eq.questionbank.service.curriculum.SubtopicMappingEvidence;
import au.edu.eq.questionbank.service.curriculum.SubtopicMappingEvidenceService;
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
 * Displays, confirms and edits descriptor and subtopic mapping reviews from a
 * historical syllabus to the current syllabus for the selected subject.
 */
public final class CurriculumMappingReviewDialog extends Dialog<ButtonType> {

	private static final int SOURCE_CELL_WIDTH = 800;
	private static final int SOURCE_TEXT_PADDING = 6;
	private static final int SUGGESTION_HORIZONTAL_INSET = 20;
	private static final int TARGET_LIST_HEIGHT = 320;
	private static final int FORM_COLUMN_GAP = 10;
	private static final int FORM_ROW_GAP = 6;
	private static final int FORM_PADDING = 10;
	private static final int HEADING_TOP_PADDING = 4;
	private static final double CONTENT_WIDTH = 850;
	private static final double CONTROL_GAP = 10;
	private static final double DESCRIPTOR_TEXT_HEIGHT = 78;
	private static final double SELECTOR_WIDTH = 180;
	private static final double DESCRIPTOR_TEXT_WIDTH = CONTENT_WIDTH - SELECTOR_WIDTH - CONTROL_GAP;
	private Button confirmButton;
	private final CurriculumMappingSuggester descriptorSuggester;
	private boolean editingReview;
	private final Button editReviewButton = new Button("Edit review");
	private final CurriculumMappingRepository mappingRepository;
	private final CheckBox noMatchCheckBox = new CheckBox("No equivalent descriptor in target syllabus");
	private final Set<Long> originalReviewedTargetIds = new HashSet<>();
	private final CurriculumRepository repository;
	private final ListView<CurriculumMapping> reviewedMappingsList = new ListView<>();
	private final Set<Long> reviewedSourceIds = new HashSet<>();
	private final ComboBox<CurriculumLevel> reviewLevelBox = new ComboBox<>();
	private final CurriculumMappingReviewRepository reviewRepository;
	private final SqliteCurriculumMappingReviewWriter reviewWriter;
	private final Set<Long> selectedTargetIds = new HashSet<>();
	private final CheckBox showReviewedCheckBox = new CheckBox("Show reviewed descriptors only");
	private final ComboBox<CurriculumNode> sourceDescriptorBox = new ComboBox<>();
	private final Text sourceDescriptorText = new Text();
	private final TextFlow sourceDescriptorTextBox = new TextFlow(sourceDescriptorText);
	private final Label sourceNodeHeading = new Label("Source descriptor:");
	private final ComboBox<SyllabusVersion> sourceVersionBox = new ComboBox<>();
	private final Label statusLabel = new Label();
	private final ComboBox<Subject> subjectBox = new ComboBox<>();
	private final CurriculumMappingSuggester subtopicSuggester;
	private final SubtopicMappingEvidenceService subtopicEvidenceService;
	private final Label subtopicEvidenceLabel = new Label();
	private final ListView<CurriculumMappingSuggestion> suggestionsList = new ListView<>();
	private final Set<Long> supplementalTargetIds = new HashSet<>();
	private final ComboBox<SyllabusVersion> targetVersionBox = new ComboBox<>();
	private final CurriculumMappingCoverageService coverageService;
	private final Label coverageLabel = new Label();

	/**
	 * Creates the mapping-review workflow. Suggestions remain unselected until the
	 * reviewer explicitly chooses one or more targets or records no match.
	 * Completed reviews are displayed read-only until edit mode is entered.
	 *
	 * @param owner                   the window that owns this dialog
	 * @param repository              curriculum hierarchy lookup
	 * @param descriptorSuggester     ranked descriptor suggestion service
	 * @param subtopicSuggester       ranked subtopic suggestion service
	 * @param subtopicEvidenceService descriptor-review evidence for subtopic
	 *                                reviews
	 * @param reviewRepository        completed-review lookup
	 * @param mappingRepository       directional mapping lookup
	 * @param coverageService         mapping-review coverage reporting service
	 * @param reviewWriter            atomic review persistence boundary
	 * @throws NullPointerException if a repository, service or writer is
	 *                              {@code null}
	 */
	public CurriculumMappingReviewDialog(Window owner, CurriculumRepository repository,
			CurriculumMappingSuggester descriptorSuggester, CurriculumMappingSuggester subtopicSuggester,
			SubtopicMappingEvidenceService subtopicEvidenceService, CurriculumMappingReviewRepository reviewRepository,
			CurriculumMappingRepository mappingRepository, CurriculumMappingCoverageService coverageService,
			SqliteCurriculumMappingReviewWriter reviewWriter) {
		if (repository == null) {
			throw new NullPointerException("repository");
		}
		if (descriptorSuggester == null) {
			throw new NullPointerException("descriptorSuggester");
		}
		if (subtopicSuggester == null) {
			throw new NullPointerException("subtopicSuggester");
		}
		if (subtopicEvidenceService == null) {
			throw new NullPointerException("subtopicEvidenceService");
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
		if (coverageService == null) {
			throw new NullPointerException("coverageService");
		}
		this.repository = repository;
		this.descriptorSuggester = descriptorSuggester;
		this.subtopicSuggester = subtopicSuggester;
		this.subtopicEvidenceService = subtopicEvidenceService;
		this.reviewRepository = reviewRepository;
		this.mappingRepository = mappingRepository;
		this.reviewWriter = reviewWriter;
		this.coverageService = coverageService;
		ButtonType confirmButtonType = configureDialog(owner);
		configureSelectors();
		configureSourceNodeControls();
		configureSuggestionLists();
		configureReviewControls();
		buildContent();
		wireListeners(confirmButtonType);
		initialiseData();
	}

	private CurriculumMappingSuggester activeSuggester() {
		if (reviewLevelBox.getValue() == CurriculumLevel.SUBTOPIC) {
			return subtopicSuggester;
		}
		return descriptorSuggester;
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

	private void buildContent() {
		HBox sourceDescriptorRow = new HBox(CONTROL_GAP, sourceDescriptorBox, sourceDescriptorTextBox);
		sourceDescriptorRow.setMinWidth(CONTENT_WIDTH);
		sourceDescriptorRow.setPrefWidth(CONTENT_WIDTH);
		sourceDescriptorRow.setMaxWidth(CONTENT_WIDTH);
		StackPane targetListPane = new StackPane(suggestionsList, reviewedMappingsList);
		targetListPane.setPrefWidth(CONTENT_WIDTH);
		targetListPane.setPrefHeight(TARGET_LIST_HEIGHT);
		HBox reviewOptions = new HBox(CONTROL_GAP, showReviewedCheckBox, editReviewButton);
		GridPane grid = new GridPane();
		grid.setHgap(FORM_COLUMN_GAP);
		grid.setVgap(FORM_ROW_GAP);
		grid.setPadding(new Insets(FORM_PADDING));
		grid.add(new Label("Subject:"), 0, 0);
		grid.add(subjectBox, 1, 0);
		grid.add(new Label("Source syllabus:"), 0, 1);
		grid.add(sourceVersionBox, 1, 1);
		grid.add(new Label("Current target syllabus:"), 0, 2);
		grid.add(targetVersionBox, 1, 2);
		grid.add(new Label("Mapping level:"), 0, 3);
		grid.add(reviewLevelBox, 1, 3);
		grid.add(new Label("Coverage:"), 0, 4);
		grid.add(coverageLabel, 1, 4);
		grid.add(sourceNodeHeading, 0, 5);
		grid.add(sourceDescriptorRow, 1, 5);
		grid.add(reviewOptions, 1, 6);
		grid.add(new Label("Suggested targets:"), 0, 7);
		grid.add(targetListPane, 1, 7);
		grid.add(noMatchCheckBox, 1, 8);
		grid.add(statusLabel, 1, 9);
		grid.add(subtopicEvidenceLabel, 1, 10);
		getDialogPane().setContent(grid);
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

	private void collectReviewNodes(CurriculumNode node, CurriculumLevel level, List<CurriculumNode> nodes) {
		if (node.getLevel() == level) {
			nodes.add(node);
			return;
		}
		for (CurriculumNode child : repository.findChildren(node)) {
			collectReviewNodes(child, level, nodes);
		}
	}

	private ButtonType configureDialog(Window owner) {
		setTitle("Curriculum Mapping");
		setHeaderText("Review curriculum mapping suggestions");
		initOwner(owner);
		ButtonType confirmButtonType = new ButtonType("Confirm", ButtonBar.ButtonData.APPLY);
		getDialogPane().getButtonTypes().addAll(confirmButtonType, ButtonType.CLOSE);
		return confirmButtonType;
	}

	private void configureReviewControls() {
		noMatchCheckBox.setDisable(true);
		noMatchCheckBox.setOnAction(_ -> handleNoMatchSelection());
		showReviewedCheckBox.setOnAction(_ -> loadSourceDescriptors());
		editReviewButton.setDisable(true);
		editReviewButton.setOnAction(_ -> toggleReviewEditing());
		sourceNodeHeading.setPadding(new Insets(HEADING_TOP_PADDING, 0, 0, 0));
		GridPane.setValignment(sourceNodeHeading, VPos.TOP);
		subtopicEvidenceLabel.setId("curriculum-mapping-subtopic-evidence");
		subtopicEvidenceLabel.setWrapText(true);
		subtopicEvidenceLabel.setMaxWidth(CONTENT_WIDTH);
		coverageLabel.setId("curriculum-mapping-coverage");
		coverageLabel.setWrapText(true);
		coverageLabel.setMaxWidth(CONTENT_WIDTH);
	}

	private void configureReviewedMappingsList() {
		reviewedMappingsList.setPrefWidth(CONTENT_WIDTH);
		reviewedMappingsList.setPrefHeight(TARGET_LIST_HEIGHT);
		reviewedMappingsList.setCellFactory(_ -> new ListCell<>() {

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

	private void configureSelectors() {
		setSelectorWidth(subjectBox);
		setSelectorWidth(sourceVersionBox);
		setSelectorWidth(targetVersionBox);
		setSelectorWidth(reviewLevelBox);
		reviewLevelBox.getItems().setAll(CurriculumLevel.DESCRIPTOR, CurriculumLevel.SUBTOPIC);
		reviewLevelBox.setValue(CurriculumLevel.DESCRIPTOR);
		updateLevelLabels();
		targetVersionBox.setDisable(true);
		setSelectorWidth(sourceDescriptorBox);
	}

	private void configureSourceNodeControls() {
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
		sourceDescriptorBox.setCellFactory(_ -> new ListCell<>() {

			@Override
			protected void updateItem(CurriculumNode descriptor, boolean empty) {
				super.updateItem(descriptor, empty);
				setWrapText(true);
				setPrefWidth(SOURCE_CELL_WIDTH);
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
		sourceDescriptorTextBox.setPadding(new Insets(SOURCE_TEXT_PADDING));
		sourceDescriptorTextBox
				.setStyle("-fx-border-color: #b0b0b0; -fx-border-width: 1; -fx-background-color: white;");
	}

	private void configureSuggestionList() {
		suggestionsList.setPrefWidth(CONTENT_WIDTH);
		suggestionsList.setPrefHeight(TARGET_LIST_HEIGHT);
		suggestionsList.setCellFactory(_ -> new ListCell<>() {

			private final CheckBox checkBox = new CheckBox();
			{
				checkBox.setWrapText(true);
				checkBox.setMaxWidth(CONTENT_WIDTH - SUGGESTION_HORIZONTAL_INSET);
				checkBox.setOnAction(_ -> updateTargetSelection(getItem(), checkBox.isSelected()));
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

	private void configureSuggestionLists() {
		configureSuggestionList();
		configureReviewedMappingsList();
		showSuggestionsList();
	}

	private void confirmReview() {
		CurriculumNode source = sourceDescriptorBox.getValue();
		SyllabusVersion targetVersion = targetVersionBox.getValue();
		int sourceIndex = sourceDescriptorBox.getSelectionModel().getSelectedIndex();
		boolean wasEditingReview = editingReview;
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
			if (wasEditingReview) {
				loadSourceDescriptors(sourceId);
			} else {
				loadSourceDescriptors(0, sourceIndex);
			}
		} catch (SQLException e) {
			showSaveError("Could not save the curriculum mapping review.", e.getMessage());
		} catch (IllegalArgumentException | IllegalStateException e) {
			showSaveError("Could not confirm the curriculum mapping review.", e.getMessage());
		}
	}

	private List<CurriculumMappingSuggestion> createEditableSuggestions(CurriculumNode source,
			SyllabusVersion targetVersion, List<CurriculumMapping> existingMappings) {
		List<CurriculumMappingSuggestion> suggestions = new ArrayList<>(
				activeSuggester().suggest(source, targetVersion));
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

	private List<CurriculumNode> findAvailableSourceDescriptors(SyllabusVersion sourceVersion,
			SyllabusVersion targetVersion) {
		reviewedSourceIds.addAll(reviewRepository.findReviewedSourceIds(sourceVersion, targetVersion));
		List<CurriculumNode> availableDescriptors = new ArrayList<>();
		for (CurriculumNode node : findReviewNodes(sourceVersion)) {
			boolean reviewed = reviewedSourceIds.contains(node.getId());
			if (showReviewedCheckBox.isSelected()) {
				if (reviewed) {
					availableDescriptors.add(node);
				}
			} else if (!reviewed) {
				availableDescriptors.add(node);
			}
		}
		return availableDescriptors;
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

	private List<CurriculumNode> findReviewNodes(SyllabusVersion version) {
		CurriculumLevel level = reviewLevelBox.getValue();
		if (level == null) {
			return List.of();
		}
		List<CurriculumNode> nodes = new ArrayList<>();
		for (CurriculumNode root : repository.findRootNodes(version)) {
			collectReviewNodes(root, level, nodes);
		}
		return nodes;
	}

	private String formatCoverageLevel(String name, CurriculumMappingLevelCoverage coverage) {
		if (coverage.status() == CurriculumMappingCoverageStatus.NOT_APPLICABLE) {
			return name + ": N/A" + " — " + coverage.uncoveredTargetCount()
					+ " target nodes without confirmed predecessor";
		}
		String percentage = String.format(Locale.ROOT, "%.1f", coverage.reviewedPercentage());
		return name + ": " + coverage.deliberatelyReviewed() + "/" + coverage.total() + " resolved (" + percentage
				+ "%)" + " — " + coverage.unreviewed() + " unreviewed, " + coverage.inconsistent() + " inconsistent"
				+ " — " + coverage.uncoveredTargetCount() + " target nodes without confirmed predecessor";
	}

	private void handleConfirmReview(ActionEvent event) {
		event.consume();
		confirmReview();
	}

	private void handleNoMatchSelection() {
		if (noMatchCheckBox.isSelected()) {
			selectedTargetIds.clear();
			suggestionsList.refresh();
		}
		updateReviewStatus();
	}

	private void handleReviewLevelChanged() {
		resetEditMode();
		clearReviewSelection();
		updateLevelLabels();
		loadSourceDescriptors();
	}

	private void handleSourceDescriptorChanged(CurriculumNode newDescriptor) {
		resetEditMode();
		clearReviewSelection();
		updateSourceDescriptorText(newDescriptor);
		updateSelectedDescriptor();
	}

	private void initialiseData() {
		subjectBox.getItems().setAll(repository.findAllSubjects());
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
			statusLabel.setText("Reviewed: no equivalent " + reviewNodeName());
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
		loadSourceDescriptors(0, -1);
	}

	private void loadSourceDescriptors(long preferredSourceId) {
		loadSourceDescriptors(preferredSourceId, -1);
	}

	private void loadSourceDescriptors(long preferredSourceId, int preferredSourceIndex) {
		resetSourceDescriptorState();
		SyllabusVersion sourceVersion = sourceVersionBox.getValue();
		SyllabusVersion targetVersion = targetVersionBox.getValue();
		updateCoverageSummary();
		if (sourceVersion == null || targetVersion == null) {
			statusLabel.setText("");
			return;
		}
		if (sourceVersion.equals(targetVersion)) {
			statusLabel.setText("Source and target syllabus versions must be different.");
			return;
		}
		List<CurriculumNode> availableDescriptors = findAvailableSourceDescriptors(sourceVersion, targetVersion);
		sourceDescriptorBox.getItems().setAll(availableDescriptors);
		if (availableDescriptors.isEmpty()) {
			showNoAvailableSourceDescriptors();
			return;
		}
		selectPreferredSourceDescriptor(availableDescriptors, preferredSourceId, preferredSourceIndex);
	}

	private void loadVersions(Subject subject) {
		resetEditMode();
		sourceVersionBox.getItems().clear();
		targetVersionBox.getItems().clear();
		sourceDescriptorBox.getItems().clear();
		sourceDescriptorText.setText("");
		suggestionsList.getItems().clear();
		reviewedMappingsList.getItems().clear();
		coverageLabel.setText("");
		subtopicEvidenceLabel.setText("");
		reviewedSourceIds.clear();
		clearReviewSelection();
		noMatchCheckBox.setDisable(true);
		showSuggestionsList();
		statusLabel.setText("");
		if (subject == null) {
			return;
		}
		List<SyllabusVersion> versions = repository.findVersionsForSubject(subject);
		SyllabusVersion currentVersion = null;
		for (SyllabusVersion version : versions) {
			if (version.isCurrent()) {
				if (currentVersion != null) {
					statusLabel.setText("More than one current syllabus is configured for this subject.");
					return;
				}
				currentVersion = version;
			} else {
				sourceVersionBox.getItems().add(version);
			}
		}
		if (currentVersion == null) {
			statusLabel.setText("No current syllabus is configured for this subject.");
			return;
		}
		targetVersionBox.getItems().setAll(currentVersion);
		targetVersionBox.setValue(currentVersion);
		if (sourceVersionBox.getItems().isEmpty()) {
			statusLabel.setText("No historical syllabus is available for mapping.");
			return;
		}
	}

	private void resetEditMode() {
		editingReview = false;
		originalReviewedTargetIds.clear();
		supplementalTargetIds.clear();
		editReviewButton.setText("Edit review");
		editReviewButton.setDisable(true);
	}

	private void resetSourceDescriptorState() {
		resetEditMode();
		sourceDescriptorBox.getItems().clear();
		sourceDescriptorText.setText("");
		suggestionsList.getItems().clear();
		reviewedMappingsList.getItems().clear();
		subtopicEvidenceLabel.setText("");
		clearReviewSelection();
		noMatchCheckBox.setDisable(true);
		showSuggestionsList();
		reviewedSourceIds.clear();
	}

	private String reviewNodeName() {
		if (reviewLevelBox.getValue() == CurriculumLevel.SUBTOPIC) {
			return "subtopic";
		}
		return "descriptor";
	}

	private String reviewNodeNamePlural() {
		if (reviewLevelBox.getValue() == CurriculumLevel.SUBTOPIC) {
			return "subtopics";
		}
		return "descriptors";
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

	private void selectPreferredSourceDescriptor(List<CurriculumNode> availableDescriptors, long preferredSourceId,
			int preferredSourceIndex) {
		if (preferredSourceId > 0) {
			for (CurriculumNode descriptor : availableDescriptors) {
				if (descriptor.getId() == preferredSourceId) {
					sourceDescriptorBox.setValue(descriptor);
					return;
				}
			}
		}
		if (preferredSourceIndex >= 0) {
			int index = Math.min(preferredSourceIndex, availableDescriptors.size() - 1);
			sourceDescriptorBox.getSelectionModel().select(index);
			return;
		}
		sourceDescriptorBox.getSelectionModel().selectFirst();
	}

	private void setSelectorWidth(ComboBox<?> comboBox) {
		comboBox.setMinWidth(SELECTOR_WIDTH);
		comboBox.setPrefWidth(SELECTOR_WIDTH);
		comboBox.setMaxWidth(SELECTOR_WIDTH);
	}

	private void showNoAvailableSourceDescriptors() {
		if (showReviewedCheckBox.isSelected()) {
			statusLabel.setText("No reviewed " + reviewNodeNamePlural() + " for these syllabus versions.");
		} else {
			statusLabel.setText("All " + reviewNodeNamePlural() + " have been reviewed for these syllabus versions.");
		}
		updateConfirmButtonState();
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

	private void toggleReviewEditing() {
		if (editingReview) {
			cancelEdit();
		} else {
			beginEditReview();
		}
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

	private void updateCoverageSummary() {
		SyllabusVersion sourceVersion = sourceVersionBox.getValue();
		SyllabusVersion targetVersion = targetVersionBox.getValue();
		if (sourceVersion == null || targetVersion == null || sourceVersion.equals(targetVersion)) {
			coverageLabel.setText("");
			return;
		}
		CurriculumMappingCoverage coverage = coverageService.calculateCoverage(sourceVersion, targetVersion);
		coverageLabel.setText(formatCoverageLevel("Descriptors", coverage.descriptorCoverage()) + "    "
				+ formatCoverageLevel("Subtopics", coverage.subtopicCoverage()) + "    Overall: " + coverage.status());
	}

	private void updateLevelLabels() {
		String nodeName = reviewNodeName();
		String nodeNames = reviewNodeNamePlural();
		sourceNodeHeading.setText("Source " + nodeName + ":");
		showReviewedCheckBox.setText("Show reviewed " + nodeNames + " only");
		noMatchCheckBox.setText("No equivalent " + nodeName + " in target syllabus");
		boolean showEvidence = reviewLevelBox.getValue() == CurriculumLevel.SUBTOPIC;
		subtopicEvidenceLabel.setVisible(showEvidence);
		subtopicEvidenceLabel.setManaged(showEvidence);
		if (!showEvidence) {
			subtopicEvidenceLabel.setText("");
		}
	}

	private void updateReviewStatus() {
		String prefix = editingReview ? "Editing review: " : "";
		String nodeName = reviewNodeName();
		String nodeNames = reviewNodeNamePlural();
		if (noMatchCheckBox.isSelected()) {
			statusLabel.setText(prefix + "no equivalent " + nodeName);
			updateConfirmButtonState();
			return;
		}
		if (selectedTargetIds.isEmpty()) {
			if (editingReview) {
				statusLabel.setText("Editing review: no target " + nodeName + " selected");
			} else if (reviewLevelBox.getValue() == CurriculumLevel.SUBTOPIC && suggestionsList.getItems().isEmpty()) {
				statusLabel.setText("No subtopic candidates from confirmed descriptor mappings.");
			} else {
				statusLabel.setText(suggestionsList.getItems().size() + " candidate mappings");
			}
			updateConfirmButtonState();
			return;
		}
		if (selectedTargetIds.size() == 1) {
			statusLabel.setText(prefix + "1 target " + nodeName + " selected");
		} else {
			statusLabel.setText(prefix + selectedTargetIds.size() + " target " + nodeNames + " selected");
		}
		updateConfirmButtonState();
	}

	private void updateSelectedDescriptor() {
		suggestionsList.getItems().clear();
		reviewedMappingsList.getItems().clear();
		CurriculumNode source = sourceDescriptorBox.getValue();
		SyllabusVersion targetVersion = targetVersionBox.getValue();
		if (source == null || targetVersion == null) {
			subtopicEvidenceLabel.setText("");
			noMatchCheckBox.setDisable(true);
			editReviewButton.setDisable(true);
			updateConfirmButtonState();
			return;
		}
		if (source.getSyllabusVersion().equals(targetVersion)) {
			subtopicEvidenceLabel.setText("");
			statusLabel.setText("Source and target syllabus versions must be different.");
			noMatchCheckBox.setDisable(true);
			editReviewButton.setDisable(true);
			updateConfirmButtonState();
			return;
		}
		updateSubtopicEvidence(source, targetVersion);
		Optional<CurriculumMappingReviewOutcome> outcome = reviewRepository.findOutcome(source, targetVersion);
		if (outcome.isPresent()) {
			loadReviewedDescriptor(source, targetVersion, outcome.get());
			return;
		}
		showSuggestionsList();
		noMatchCheckBox.setDisable(false);
		editReviewButton.setDisable(true);
		List<CurriculumMappingSuggestion> suggestions = activeSuggester().suggest(source, targetVersion);
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

	private void updateSubtopicEvidence(CurriculumNode source, SyllabusVersion targetVersion) {
		if (reviewLevelBox.getValue() != CurriculumLevel.SUBTOPIC) {
			subtopicEvidenceLabel.setText("");
			return;
		}
		SubtopicMappingEvidence evidence = subtopicEvidenceService.summarise(source, targetVersion);
		subtopicEvidenceLabel.setText("Descriptor review coverage: " + evidence.reviewedDescriptorCount() + " / "
				+ evidence.totalDescriptorCount() + "    No-match descriptors: " + evidence.noMatchDescriptorCount());
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

	private void wireListeners(ButtonType confirmButtonType) {
		confirmButton = (Button) getDialogPane().lookupButton(confirmButtonType);
		confirmButton.setDisable(true);
		confirmButton.addEventFilter(ActionEvent.ACTION, this::handleConfirmReview);
		subjectBox.valueProperty().addListener((_, _, newSubject) -> loadVersions(newSubject));
		sourceVersionBox.valueProperty().addListener((_, _, _) -> loadSourceDescriptors());
		targetVersionBox.valueProperty().addListener((_, _, _) -> loadSourceDescriptors());
		sourceDescriptorBox.valueProperty()
				.addListener((_, _, newDescriptor) -> handleSourceDescriptorChanged(newDescriptor));
		reviewLevelBox.valueProperty().addListener((_, _, _) -> handleReviewLevelChanged());
	}
}
