package au.edu.eq.questionbank.ui;

import java.util.List;

import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.ui.model.CurriculumSelectionModel;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.geometry.Insets;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * JavaFX controls for selecting a subject and syllabus, then navigating its
 * unit/topic/subtopic/descriptor hierarchy to a final classification.
 * <p>
 * Classification code entry and hierarchy selections remain synchronised.
 * Subtopic and descriptor controls are displayed only when they exist in the
 * selected syllabus path.
 */
public class CurriculumSelectorPane extends VBox {

	private static final double ROW_GAP = 4.0;
	private static final double COLUMN_GAP = 8.0;
	private static final Insets PANEL_PADDING = new Insets(8);
	private static final String BORDER_STYLE = "-fx-border-color: #b0b0b0;-fx-border-width: 1;-fx-border-radius: 3;";
	private static final String HEADING_STYLE = "-fx-font-weight: bold;";
	private final CurriculumSelectionModel model;
	private final ReadOnlyBooleanWrapper classificationSelected = new ReadOnlyBooleanWrapper();
	private final javafx.beans.property.ReadOnlyObjectWrapper<CurriculumNode> selectedClassification = new javafx.beans.property.ReadOnlyObjectWrapper<>();
	private final TextField codeField = new TextField();
	private final ComboBox<Subject> subjectBox = new ComboBox<>();
	private final ComboBox<SyllabusVersion> syllabusBox = new ComboBox<>();
	private final ComboBox<CurriculumNode> unitBox = new ComboBox<>();
	private final ComboBox<CurriculumNode> topicBox = new ComboBox<>();
	private final ComboBox<CurriculumNode> subtopicBox = new ComboBox<>();
	private final ComboBox<CurriculumNode> descriptorBox = new ComboBox<>();
	private final Label subtopicLabel = new Label("Subtopic");
	private final Label descriptorLabel = new Label("Descriptor");
	private boolean refreshingSubjects;
	private boolean refreshingCode;

	/**
	 * Creates a selector bound to the supplied selection model.
	 *
	 * @param model the curriculum selection state and lookup model
	 */
	public CurriculumSelectorPane(CurriculumSelectionModel model) {
		if (model == null) {
			throw new NullPointerException("model");
		}
		this.model = model;
		configurePane();
		configureControls();
		classificationSelected.bind(selectedClassification.isNotNull());
		configureSelectionHandlers();
		configureCodeEntry();
		buildContent();
		refreshSubjects();
	}

	private void buildContent() {
		Label classificationLabel = new Label("CLASSIFICATION");
		classificationLabel.setStyle(HEADING_STYLE);
		getChildren().addAll(classificationLabel, createGrid());
	}

	private void configurePane() {
		setSpacing(ROW_GAP);
		setPadding(PANEL_PADDING);
		setStyle(BORDER_STYLE);
	}

	/**
	 * Indicates whether a valid final classification is currently selected.
	 *
	 * @return read-only final-classification state
	 */
	public ReadOnlyBooleanProperty classificationSelectedProperty() {
		return classificationSelected.getReadOnlyProperty();
	}

	/**
	 * Clears unit, topic, subtopic, descriptor, and classification-code state while
	 * retaining the selected subject and syllabus.
	 */
	public void clearClassificationBelowSubject() {
		refreshingCode = true;
		try {
			clearHierarchySelection();
			codeField.clear();
		} finally {
			refreshingCode = false;
		}
		refreshSelectedClassificationProperty();
	}

	/**
	 * Reloads subjects, syllabuses, and hierarchy choices after a repository
	 * change. Existing selections are retained when still available.
	 */
	public void refreshSubjects() {
		SelectionSnapshot selection = captureSelection();
		refreshingSubjects = true;
		try {
			reloadSubjectAndSyllabus(selection);
			resetHierarchyChoices();
			restoreNodeSelection(selection.node());
		} finally {
			refreshingSubjects = false;
		}
		syncCodeFromSelection();
	}

	private SelectionSnapshot captureSelection() {
		return new SelectionSnapshot(subjectBox.getValue(), syllabusBox.getValue(), deepestSelectedNode());
	}

	private void reloadSubjectAndSyllabus(SelectionSnapshot selection) {
		subjectBox.getItems().setAll(model.getSubjects());
		selectAvailableValue(subjectBox, selection.subject());
		model.selectSubject(subjectBox.getValue());
		syllabusBox.getItems().setAll(model.getSyllabusVersions());
		selectAvailableValue(syllabusBox, selection.syllabus());
		model.selectSyllabusVersion(syllabusBox.getValue());
		syllabusBox.setDisable(syllabusBox.getItems().isEmpty());
	}

	private void resetHierarchyChoices() {
		unitBox.getItems().setAll(model.getUnits());
		unitBox.setDisable(model.getSyllabusVersion() == null);
		topicBox.getItems().clear();
		topicBox.setDisable(true);
		hideSubtopicRow();
		hideDescriptorRow();
	}

	private void restoreNodeSelection(CurriculumNode selectedNode) {
		if (selectedNode == null || model.getSyllabusVersion() == null) {
			return;
		}
		CurriculumNode available = model.findByCode(selectedNode.getCode());
		if (available != null && available.equals(selectedNode)) {
			applyNodePath(available);
		}
	}

	/**
	 * Selects the subject, syllabus, and complete hierarchy path for an existing
	 * question classification.
	 *
	 * @param classification the subtopic or descriptor to display
	 * @throws NullPointerException     if {@code classification} is {@code null}
	 * @throws IllegalArgumentException if it does not have a valid path
	 */
	public void selectClassificationPath(CurriculumNode classification) {
		if (classification == null) {
			throw new NullPointerException("classification");
		}
		if (classification.getLevel() != CurriculumLevel.SUBTOPIC
				&& classification.getLevel() != CurriculumLevel.DESCRIPTOR) {
			throw new IllegalArgumentException("Final classification must be a subtopic or descriptor");
		}
		selectSubject(classification.getSyllabusVersion().getSubject());
		refreshingSubjects = true;
		try {
			SyllabusVersion syllabusVersion = classification.getSyllabusVersion();
			selectAvailableValue(syllabusBox, syllabusVersion);
			if (syllabusBox.getValue() == null) {
				throw new IllegalArgumentException("Classification syllabus is not available");
			}
			model.selectSyllabusVersion(syllabusBox.getValue());
			unitBox.getItems().setAll(model.getUnits());
			unitBox.setDisable(false);
			applyNodePath(classification);
		} finally {
			refreshingSubjects = false;
		}
		setCodeText(classification.getCode());
	}

	/**
	 * Returns the actual final classification selected in the pane.
	 *
	 * @return read-only selected classification property
	 */
	public ReadOnlyObjectProperty<CurriculumNode> selectedClassificationProperty() {
		return selectedClassification.getReadOnlyProperty();
	}

	/**
	 * Exposes the subject selected by this pane.
	 *
	 * @return the read-only selected-subject property
	 */
	public ReadOnlyObjectProperty<Subject> selectedSubjectProperty() {
		return subjectBox.valueProperty();
	}

	/**
	 * Selects a subject programmatically and rebuilds the dependent choices.
	 *
	 * @param subject the subject to select, or {@code null} to clear the selection
	 * @throws IllegalArgumentException if the subject is not available
	 */
	public void selectSubject(Subject subject) {
		Subject availableSubject = null;
		if (subject != null) {
			for (Subject item : subjectBox.getItems()) {
				if (item.equals(subject)) {
					availableSubject = item;
					break;
				}
			}
			if (availableSubject == null) {
				throw new IllegalArgumentException("Subject is not available: " + subject.getName());
			}
		}
		refreshingSubjects = true;
		try {
			subjectBox.setValue(availableSubject);
		} finally {
			refreshingSubjects = false;
		}
		handleSubjectSelection();
	}

	private void applyNodePath(CurriculumNode node) {
		ClassificationPath path = classificationPath(node);
		applyUnitAndTopic(path);
		applyFinalClassification(path);
	}

	private void applyUnitAndTopic(ClassificationPath path) {
		selectAvailableValue(unitBox, path.unit());
		model.selectUnit(unitBox.getValue());
		unitBox.setDisable(model.getSyllabusVersion() == null);
		topicBox.getItems().setAll(model.getTopics());
		selectAvailableValue(topicBox, path.topic());
		model.selectTopic(topicBox.getValue());
		topicBox.setDisable(model.getUnit() == null);
	}

	private void applyFinalClassification(ClassificationPath path) {
		if (model.getTopic() == null) {
			hideSubtopicRow();
			hideDescriptorRow();
			return;
		}
		refreshFinalClassificationRows();
		if (path.subtopic() != null) {
			selectAvailableValue(subtopicBox, path.subtopic());
			if (subtopicBox.getValue() == null) {
				throw new IllegalArgumentException("Subtopic is not available beneath the selected topic");
			}
			model.selectSubtopic(subtopicBox.getValue());
			refreshDescriptorRow();
		}
		if (path.descriptor() != null) {
			selectAvailableValue(descriptorBox, path.descriptor());
			if (descriptorBox.getValue() == null) {
				throw new IllegalArgumentException("Descriptor is not available beneath the selected path");
			}
			model.selectDescriptor(descriptorBox.getValue());
		}
	}

	private ClassificationPath classificationPath(CurriculumNode node) {
		if (node == null) {
			throw new NullPointerException("node");
		}
		return switch (node.getLevel()) {
		case UNIT -> new ClassificationPath(node, null, null, null);
		case TOPIC -> {
			CurriculumNode unit = node.getParent();
			requireLevel(unit, CurriculumLevel.UNIT, "Topic does not belong to a unit");
			yield new ClassificationPath(unit, node, null, null);
		}
		case SUBTOPIC -> {
			CurriculumNode topic = node.getParent();
			requireLevel(topic, CurriculumLevel.TOPIC, "Subtopic does not belong to a topic");
			CurriculumNode unit = topic.getParent();
			requireLevel(unit, CurriculumLevel.UNIT, "Subtopic topic does not belong to a unit");
			yield new ClassificationPath(unit, topic, node, null);
		}
		case DESCRIPTOR -> {
			CurriculumNode parent = node.getParent();
			if (parent != null && parent.getLevel() == CurriculumLevel.SUBTOPIC) {
				CurriculumNode topic = parent.getParent();
				requireLevel(topic, CurriculumLevel.TOPIC, "Descriptor subtopic does not belong to a topic");
				CurriculumNode unit = topic.getParent();
				requireLevel(unit, CurriculumLevel.UNIT, "Descriptor topic does not belong to a unit");
				yield new ClassificationPath(unit, topic, parent, node);
			}
			requireLevel(parent, CurriculumLevel.TOPIC, "Descriptor does not belong to a topic or subtopic");
			CurriculumNode unit = parent.getParent();
			requireLevel(unit, CurriculumLevel.UNIT, "Descriptor topic does not belong to a unit");
			yield new ClassificationPath(unit, parent, null, node);
		}
		};
	}

	private void clearHierarchySelection() {
		unitBox.getSelectionModel().clearSelection();
		topicBox.getSelectionModel().clearSelection();
		subtopicBox.getSelectionModel().clearSelection();
		descriptorBox.getSelectionModel().clearSelection();
		topicBox.getItems().clear();
		hideSubtopicRow();
		hideDescriptorRow();
		model.selectUnit(null);
		unitBox.setDisable(model.getSyllabusVersion() == null);
		topicBox.setDisable(true);
	}

	private void configureCodeEntry() {
		codeField.setTextFormatter(new TextFormatter<>(change -> {
			String candidate = change.getControlNewText();
			if (!isCodeSyntaxValid(candidate)) {
				return null;
			}
			if (!isCodeValuePossible(candidate)) {
				return null;
			}
			return change;
		}));
		codeField.textProperty().addListener((observable, oldValue, newValue) -> handleCodeInput(newValue));
		codeField.focusedProperty().addListener((observable, wasFocused, focused) -> {
			if (!focused.booleanValue()) {
				syncCodeFromSelection();
			}
		});
	}

	private void configureControls() {
		codeField.setId("curriculum-code");
		codeField.setPromptText("e.g. 3.1.2");
		codeField.setMaxWidth(Double.MAX_VALUE);
		codeField.setMinWidth(0);
		configureHierarchyBox(subjectBox, "curriculum-subject", "Select subject");
		configureHierarchyBox(syllabusBox, "curriculum-syllabus", "Select syllabus");
		configureHierarchyBox(unitBox, "curriculum-unit", "Select unit");
		configureHierarchyBox(topicBox, "curriculum-topic", "Select topic");
		configureHierarchyBox(subtopicBox, "curriculum-subtopic", "Select subtopic");
		configureHierarchyBox(descriptorBox, "curriculum-descriptor", "Select descriptor");
		syllabusBox.setDisable(true);
		unitBox.setDisable(true);
		topicBox.setDisable(true);
		subtopicBox.setDisable(true);
		descriptorBox.setDisable(true);
		hideSubtopicRow();
		hideDescriptorRow();
	}

	private void configureSelectionHandlers() {
		subjectBox.setOnAction(event -> handleSubjectSelection());
		syllabusBox.setOnAction(event -> handleSyllabusSelection());
		unitBox.getSelectionModel().selectedItemProperty()
				.addListener((observable, oldValue, newValue) -> handleUnitSelection());
		topicBox.getSelectionModel().selectedItemProperty()
				.addListener((observable, oldValue, newValue) -> handleTopicSelection());
		subtopicBox.getSelectionModel().selectedItemProperty()
				.addListener((observable, oldValue, newValue) -> handleSubtopicSelection());
		descriptorBox.getSelectionModel().selectedItemProperty()
				.addListener((observable, oldValue, newValue) -> handleDescriptorSelection());
	}

	private GridPane createGrid() {
		GridPane grid = new GridPane();
		grid.setHgap(COLUMN_GAP);
		grid.setVgap(ROW_GAP);
		ColumnConstraints labelColumn = new ColumnConstraints();
		labelColumn.setMinWidth(75);
		labelColumn.setPrefWidth(75);
		ColumnConstraints controlColumn = new ColumnConstraints();
		controlColumn.setMinWidth(0);
		controlColumn.setHgrow(Priority.ALWAYS);
		controlColumn.setFillWidth(true);
		grid.getColumnConstraints().addAll(labelColumn, controlColumn);
		grid.addRow(0, new Label("Code"), codeField);
		grid.addRow(1, new Label("Subject"), subjectBox);
		grid.addRow(2, new Label("Syllabus"), syllabusBox);
		grid.addRow(3, new Label("Unit"), unitBox);
		grid.addRow(4, new Label("Topic"), topicBox);
		grid.addRow(5, subtopicLabel, subtopicBox);
		grid.addRow(6, descriptorLabel, descriptorBox);
		GridPane.setHgrow(codeField, Priority.ALWAYS);
		GridPane.setHgrow(subjectBox, Priority.ALWAYS);
		GridPane.setHgrow(syllabusBox, Priority.ALWAYS);
		GridPane.setHgrow(unitBox, Priority.ALWAYS);
		GridPane.setHgrow(topicBox, Priority.ALWAYS);
		GridPane.setHgrow(subtopicBox, Priority.ALWAYS);
		GridPane.setHgrow(descriptorBox, Priority.ALWAYS);
		return grid;
	}

	private CurriculumNode deepestSelectedNode() {
		if (model.getDescriptor() != null) {
			return model.getDescriptor();
		}
		if (model.getSubtopic() != null) {
			return model.getSubtopic();
		}
		if (model.getTopic() != null) {
			return model.getTopic();
		}
		return model.getUnit();
	}

	private CurriculumNode findCodePrefixMatch(String code) {
		String candidate = code;
		while (true) {
			int separator = candidate.lastIndexOf('.');
			if (separator < 0) {
				return null;
			}
			candidate = candidate.substring(0, separator);
			if (candidate.isBlank()) {
				return null;
			}
			CurriculumNode node = model.findByCode(candidate);
			if (node != null) {
				return node;
			}
		}
	}

	private void handleCodeInput(String text) {
		if (refreshingCode || refreshingSubjects) {
			return;
		}
		String code = text == null ? "" : text.trim();
		if (code.isEmpty()) {
			refreshingCode = true;
			try {
				clearHierarchySelection();
			} finally {
				refreshingCode = false;
			}
			refreshSelectedClassificationProperty();
			return;
		}
		if (model.getSyllabusVersion() == null) {
			return;
		}
		CurriculumNode exactMatch = model.findByCode(code);
		if (exactMatch != null) {
			refreshingCode = true;
			try {
				applyNodePath(exactMatch);
			} finally {
				refreshingCode = false;
			}
			refreshSelectedClassificationProperty();
			return;
		}
		CurriculumNode current = deepestSelectedNode();
		if (current != null && code.startsWith(current.getCode())) {
			return;
		}
		CurriculumNode prefixMatch = findCodePrefixMatch(code);
		refreshingCode = true;
		try {
			if (prefixMatch == null) {
				clearHierarchySelection();
			} else {
				applyNodePath(prefixMatch);
			}
		} finally {
			refreshingCode = false;
		}
	}

	private void handleDescriptorSelection() {
		if (refreshingSubjects || refreshingCode) {
			return;
		}
		model.selectDescriptor(descriptorBox.getValue());
		syncCodeFromSelection();
	}

	private void handleSubjectSelection() {
		if (refreshingSubjects || refreshingCode) {
			return;
		}
		Subject subject = subjectBox.getValue();
		clearSubjectDependentChoices();
		model.selectSubject(subject);
		if (subject == null) {
			disableSyllabusHierarchy();
			setCodeText("");
			return;
		}
		displayAvailableSyllabuses();
		setCodeText("");
	}

	private void clearSubjectDependentChoices() {
		syllabusBox.getSelectionModel().clearSelection();
		syllabusBox.getItems().clear();
		unitBox.getSelectionModel().clearSelection();
		unitBox.getItems().clear();
		clearTopicAndFinalChoices();
	}

	private void clearTopicAndFinalChoices() {
		topicBox.getSelectionModel().clearSelection();
		topicBox.getItems().clear();
		hideSubtopicRow();
		hideDescriptorRow();
	}

	private void configureHierarchyBox(ComboBox<?> box, String id, String promptText) {
		box.setId(id);
		box.setPromptText(promptText);
		box.setMaxWidth(Double.MAX_VALUE);
		box.setMinWidth(0);
	}

	private void disableSyllabusHierarchy() {
		syllabusBox.setDisable(true);
		unitBox.setDisable(true);
		topicBox.setDisable(true);
	}

	private void displayAvailableSyllabuses() {
		syllabusBox.getItems().setAll(model.getSyllabusVersions());
		syllabusBox.setDisable(syllabusBox.getItems().isEmpty());
		SyllabusVersion defaultVersion = model.getSyllabusVersion();
		if (defaultVersion == null) {
			unitBox.setDisable(true);
			topicBox.setDisable(true);
			return;
		}
		syllabusBox.setValue(defaultVersion);
		model.selectSyllabusVersion(defaultVersion);
		unitBox.getItems().setAll(model.getUnits());
		unitBox.setDisable(false);
		topicBox.setDisable(true);
	}

	private void handleSubtopicSelection() {
		if (refreshingSubjects || refreshingCode) {
			return;
		}
		CurriculumNode subtopic = subtopicBox.getValue();
		model.selectSubtopic(subtopic);
		descriptorBox.getSelectionModel().clearSelection();
		if (subtopic == null) {
			hideDescriptorRow();
		} else {
			refreshDescriptorRow();
		}
		syncCodeFromSelection();
	}

	private void handleSyllabusSelection() {
		if (refreshingSubjects || refreshingCode) {
			return;
		}
		SyllabusVersion syllabusVersion = syllabusBox.getValue();
		unitBox.getSelectionModel().clearSelection();
		clearTopicAndFinalChoices();
		model.selectSyllabusVersion(syllabusVersion);
		if (syllabusVersion == null) {
			unitBox.getItems().clear();
			unitBox.setDisable(true);
			topicBox.setDisable(true);
			setCodeText("");
			return;
		}
		unitBox.getItems().setAll(model.getUnits());
		unitBox.setDisable(false);
		topicBox.setDisable(true);
		setCodeText("");
	}

	private void handleTopicSelection() {
		if (refreshingSubjects || refreshingCode) {
			return;
		}
		CurriculumNode topic = topicBox.getValue();
		model.selectTopic(topic);
		if (topic == null) {
			hideSubtopicRow();
			hideDescriptorRow();
			syncCodeFromSelection();
			return;
		}
		refreshFinalClassificationRows();
		syncCodeFromSelection();
	}

	private void handleUnitSelection() {
		if (refreshingSubjects || refreshingCode) {
			return;
		}
		CurriculumNode unit = unitBox.getValue();
		model.selectUnit(unit);
		topicBox.getSelectionModel().clearSelection();
		hideSubtopicRow();
		hideDescriptorRow();
		if (unit == null) {
			topicBox.getItems().clear();
			topicBox.setDisable(true);
			syncCodeFromSelection();
			return;
		}
		topicBox.getItems().setAll(model.getTopics());
		topicBox.setDisable(false);
		syncCodeFromSelection();
	}

	private void hideDescriptorRow() {
		descriptorBox.getSelectionModel().clearSelection();
		descriptorBox.getItems().clear();
		setDescriptorRowVisible(false);
	}

	private void hideSubtopicRow() {
		subtopicBox.getSelectionModel().clearSelection();
		subtopicBox.getItems().clear();
		subtopicLabel.setVisible(false);
		subtopicLabel.setManaged(false);
		subtopicBox.setVisible(false);
		subtopicBox.setManaged(false);
		subtopicBox.setDisable(true);
	}

	private boolean isCodeSyntaxValid(String code) {
		if (code == null || code.isEmpty()) {
			return true;
		}
		return code.matches("\\d+(?:\\.\\d+)*\\.?");
	}

	private boolean isCodeValuePossible(String code) {
		if (code == null || code.isEmpty()) {
			return true;
		}
		if (model.getSyllabusVersion() == null) {
			return false;
		}
		if (model.findByCode(code) != null) {
			return true;
		}
		if (hasCodePrefix(model.getUnits(), code)) {
			return true;
		}
		if (model.getUnit() != null && hasCodePrefix(model.getTopics(), code)) {
			return true;
		}
		if (model.getTopic() != null) {
			if (hasCodePrefix(model.getSubtopics(), code)) {
				return true;
			}
			if (hasCodePrefix(model.getDescriptors(), code)) {
				return true;
			}
		}
		if (model.getSubtopic() != null && hasCodePrefix(model.getDescriptors(), code)) {
			return true;
		}
		return false;
	}

	private boolean hasCodePrefix(List<CurriculumNode> nodes, String code) {
		for (CurriculumNode node : nodes) {
			if (node.getCode().startsWith(code)) {
				return true;
			}
		}
		return false;
	}

	private void refreshDescriptorRow() {
		descriptorBox.getSelectionModel().clearSelection();
		descriptorBox.getItems().setAll(model.getDescriptors());
		setDescriptorRowVisible(!descriptorBox.getItems().isEmpty());
	}

	private void refreshFinalClassificationRows() {
		subtopicBox.getSelectionModel().clearSelection();
		descriptorBox.getSelectionModel().clearSelection();
		if (!model.getSubtopics().isEmpty()) {
			subtopicBox.getItems().setAll(model.getSubtopics());
			showSubtopicRow();
			hideDescriptorRow();
			return;
		}
		hideSubtopicRow();
		descriptorBox.getItems().setAll(model.getDescriptors());
		setDescriptorRowVisible(!descriptorBox.getItems().isEmpty());
	}

	private void refreshSelectedClassificationProperty() {
		selectedClassification.set(model.getClassification());
	}

	private void requireLevel(CurriculumNode node, CurriculumLevel level, String message) {
		if (node == null || node.getLevel() != level) {
			throw new IllegalArgumentException(message);
		}
	}

	private <T> void selectAvailableValue(ComboBox<T> box, T selectedValue) {
		if (selectedValue != null) {
			for (T item : box.getItems()) {
				if (item.equals(selectedValue)) {
					box.getSelectionModel().select(item);
					return;
				}
			}
		}
		box.getSelectionModel().clearSelection();
	}

	private void setCodeText(String text) {
		refreshingCode = true;
		try {
			codeField.setText(text == null ? "" : text);
		} finally {
			refreshingCode = false;
		}
		refreshSelectedClassificationProperty();
	}

	private void setDescriptorRowVisible(boolean visible) {
		descriptorLabel.setVisible(visible);
		descriptorLabel.setManaged(visible);
		descriptorBox.setVisible(visible);
		descriptorBox.setManaged(visible);
		descriptorBox.setDisable(!visible);
	}

	private void showSubtopicRow() {
		subtopicLabel.setVisible(true);
		subtopicLabel.setManaged(true);
		subtopicBox.setVisible(true);
		subtopicBox.setManaged(true);
		subtopicBox.setDisable(false);
	}

	private void syncCodeFromSelection() {
		if (refreshingCode) {
			return;
		}
		CurriculumNode selected = deepestSelectedNode();
		setCodeText(selected == null ? "" : selected.getCode());
	}

	private record ClassificationPath(CurriculumNode unit, CurriculumNode topic, CurriculumNode subtopic,
			CurriculumNode descriptor) {
	}

	private record SelectionSnapshot(Subject subject, SyllabusVersion syllabus, CurriculumNode node) {
	}
}
