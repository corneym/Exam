package au.edu.eq.questionbank.ui.curriculum;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import au.edu.eq.questionbank.diagnostics.PerformanceOperation;
import au.edu.eq.questionbank.diagnostics.PerformanceRecorder;
import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.service.curriculum.DescriptorLookupService;
import au.edu.eq.questionbank.service.curriculum.DescriptorLookupService.DescriptorMatch;
import au.edu.eq.questionbank.ui.model.CurriculumSelectionModel;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
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

	// Keep Classification labels readable without forcing the hierarchy controls
	// wider than the available capture workspace.
	private static final int LABEL_COLUMN_WIDTH = 105;
	private static final double ROW_GAP = 4.0;
	private static final double COLUMN_GAP = 8.0;
	private static final Insets PANEL_PADDING = new Insets(8);
	private static final String BORDER_STYLE = "-fx-border-color: #b0b0b0;-fx-border-width: 1;-fx-border-radius: 3;";
	private static final String HEADING_STYLE = "-fx-font-weight: bold;";
	private static final int DESCRIPTOR_LOOKUP_LIMIT = 5;
	private final CurriculumSelectionModel model;
	private final ReadOnlyBooleanWrapper classificationSelected = new ReadOnlyBooleanWrapper();
	private final ReadOnlyObjectWrapper<CurriculumNode> selectedClassification = new ReadOnlyObjectWrapper<>();
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
	private VBox classificationContext;
	private VBox subjectContext;
	private final Label readOnlySubjectLabel = new Label();
	private PerformanceRecorder performanceRecorder = new PerformanceRecorder(false, Path.of("performance.csv"));
	private long descriptorContextGeneration;
	private SyllabusVersion descriptorLoadedSyllabus;
	private SyllabusVersion descriptorLoadingSyllabus;
	private final ListView<DescriptorMatch> descriptorLookupResults = new ListView<>();
	private final Label descriptorLookupStatus = new Label();
	private final TextField descriptorLookupText = new TextField();
	private long descriptorQueryGeneration;
	private List<CurriculumNode> descriptorSnapshot = List.of();
	private final DescriptorLookupService descriptorLookupService;

	// The desktop application owns asynchronous Working Subject transitions. A
	// standalone selector keeps its existing synchronous Subject behaviour.
	private boolean subjectRefreshManagedExternally;

	// Subject creation is an application-level action. The pane owns only its
	// compact presentation; persistence is supplied by the containing application.
	private final Button addSubjectButton = new Button("+");

	// A workflow may request restoration of a persisted Question classification
	// immediately after activating its Working Subject. When Subject loading is
	// application-managed, retain that request until the matching curriculum
	// snapshot has been published.
	private CurriculumNode deferredClassification;

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
		this.descriptorLookupService = model.createDescriptorLookupService();
		configurePane();
		configureControls();
		configureDescriptorLookup();
		classificationSelected.bind(selectedClassification.isNotNull());
		configureSelectionHandlers();
		configureCodeEntry();
		buildContent();
		refreshSubjects();
	}

	/**
	 * Publishes an asynchronously loaded curriculum snapshot into the visible
	 * Subject/classification controls.
	 *
	 * @param snapshot snapshot for the currently selected Working Subject
	 */
	public void applySubjectSnapshot(CurriculumSelectionModel.SubjectSnapshot snapshot) {
		if (snapshot == null) {
			throw new NullPointerException("snapshot");
		}
		if (!snapshot.subject().equals(subjectBox.getValue())) {

			// Stale application work must never replace choices for a later Subject.
			return;
		}
		invalidateDescriptorLookup();
		boolean previouslyRefreshing = refreshingSubjects;
		refreshingSubjects = true;
		try {
			model.applySubjectSnapshot(snapshot);
			clearSubjectDependentChoices();
			syllabusBox.getItems().setAll(snapshot.syllabusVersions());
			syllabusBox.setDisable(syllabusBox.getItems().isEmpty());
			selectAvailableValue(syllabusBox, snapshot.currentSyllabusVersion());
			unitBox.getItems().setAll(snapshot.currentUnits());
			unitBox.setDisable(snapshot.currentSyllabusVersion() == null);
			topicBox.setDisable(true);
		} finally {
			refreshingSubjects = previouslyRefreshing;
		}
		CurriculumNode classification = deferredClassification;
		if (classification != null && classification.getSyllabusVersion().getSubject().equals(snapshot.subject())) {

			// The workflow that requested this classification no longer needs to know
			// whether its Subject happened to require an asynchronous transition.
			deferredClassification = null;
			applyClassificationPath(classification);
			return;
		}

		// An ordinary Subject transition establishes curriculum choices but no Question
		// classification.
		setCodeText("");
	}

	/**
	 * Removes curriculum presentation belonging to the previous Working Subject
	 * before replacement persistence data is loaded.
	 *
	 * @param subject newly accepted Subject, or {@code null} to clear it
	 */
	public void beginSubjectRefresh(Subject subject) {
		invalidateDescriptorLookup();
		boolean previouslyRefreshing = refreshingSubjects;
		refreshingSubjects = true;
		try {

			// Any classification waiting for an older Subject is obsolete as soon as a
			// later Subject transition becomes authoritative.
			deferredClassification = null;
			model.beginSubjectRefresh(subject);
			clearSubjectDependentChoices();
			disableSyllabusHierarchy();
		} finally {

			// Preserve an enclosing programmatic selection operation when this method is
			// reached re-entrantly through the Subject value-property listener.
			refreshingSubjects = previouslyRefreshing;
		}
		setCodeText("");
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
	 * Detaches the Classification section so the application can place it inside
	 * the larger capture-workspace container while Working Subject remains
	 * application-level context.
	 *
	 * @return the existing Classification section and all of its live controls
	 * @throws IllegalStateException if the Classification section has already been
	 *                               detached
	 */
	public VBox detachClassificationContext() {
		if (classificationContext == null || !getChildren().remove(classificationContext)) {
			throw new IllegalStateException("Classification context has already been detached");
		}

		// The controls themselves are not recreated. Their listeners and selection
		// model therefore continue operating exactly as before after re-parenting.
		return classificationContext;
	}

	/**
	 * Detaches the authoritative Working Subject section so the application can
	 * place the existing live selector inside the Corpus Dashboard home surface.
	 *
	 * @return the existing Working Subject section and all of its live controls
	 * @throws IllegalStateException if the Working Subject section has already been
	 *                               detached
	 */
	public VBox detachSubjectContext() {
		if (subjectContext == null || !getChildren().remove(subjectContext)) {
			throw new IllegalStateException("Working Subject context has already been detached");
		}

		// Re-parent the actual application Subject controls rather than creating a
		// second selector with competing state.
		return subjectContext;
	}

	/**
	 * Reloads subjects, syllabuses, and hierarchy choices after a repository
	 * change. Existing selections are retained when still available.
	 */
	public void refreshSubjects() {
		invalidateDescriptorLookup();
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

	/**
	 * Selects the Subject, syllabus, and complete hierarchy path for an existing
	 * Question classification.
	 * <p>
	 * When Subject refresh is managed by the containing application, restoration
	 * may be deferred until the matching asynchronous Subject snapshot has been
	 * published.
	 *
	 * @param classification the Subtopic or Descriptor to display
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
		Subject classificationSubject = classification.getSyllabusVersion().getSubject();
		selectSubject(classificationSubject);
		boolean syllabusAvailable = syllabusBox.getItems().stream()
				.anyMatch(version -> version.equals(classification.getSyllabusVersion()));
		if (!syllabusAvailable && subjectRefreshManagedExternally) {

			// Imported Question activation can change Working Subject and immediately
			// request its stored classification. Do not turn that legitimate
			// asynchronous interval into a validation failure.
			deferredClassification = classification;
			return;
		}

		// Synchronous standalone use and already-loaded application Subjects can
		// restore
		// their complete classification immediately.
		deferredClassification = null;
		applyClassificationPath(classification);
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

	/**
	 * Supplies the application-level action used to create a Subject.
	 *
	 * @param action Subject-creation action owned by the containing application
	 * @throws NullPointerException if {@code action} is {@code null}
	 */
	public void setAddSubjectAction(Runnable action) {
		if (action == null) {
			throw new NullPointerException("action");
		}

		// The selector does not know how Subjects are persisted. It merely exposes the
		// compact action once the application supplies the authoritative workflow.
		addSubjectButton.setOnAction(_ -> action.run());
		addSubjectButton.setDisable(false);
	}

	/**
	 * Enables or disables only the Question-classification section. Working Subject
	 * remains application-level context and is not affected.
	 *
	 * @param disabled whether Classification controls are disabled
	 */
	public void setClassificationControlsDisabled(boolean disabled) {

		// Classification may be re-parented into the application capture workspace,
		// so control the retained section directly rather than disabling Working
		// Subject as well.
		classificationContext.setDisable(disabled);
	}

	/**
	 * Configures performance diagnostics for curriculum navigation.
	 *
	 * @param performanceRecorder shared application recorder
	 * @throws NullPointerException if the recorder is null
	 */
	public void setPerformanceRecorder(PerformanceRecorder performanceRecorder) {
		this.performanceRecorder = Objects.requireNonNull(performanceRecorder, "performanceRecorder");
	}

	/**
	 * Chooses whether the authoritative Subject may be changed from the currently
	 * displayed application surface.
	 *
	 * @param enabled whether Subject selection and Subject creation are available
	 */
	public void setSubjectEditingEnabled(boolean enabled) {

		// The Dashboard is the only editing surface. Specialised workspaces retain
		// Subject context but replace the selector and + action with a read-only value.
		subjectBox.setVisible(enabled);
		subjectBox.setManaged(enabled);
		addSubjectButton.setVisible(enabled);
		addSubjectButton.setManaged(enabled);
		readOnlySubjectLabel.setVisible(!enabled);
		readOnlySubjectLabel.setManaged(!enabled);
		updateReadOnlySubject(subjectBox.getValue());
	}

	/**
	 * Chooses whether Subject-dependent persistence refresh is coordinated by the
	 * containing application rather than by this pane's synchronous action handler.
	 *
	 * @param managedExternally whether the application owns Subject refresh
	 */
	public void setSubjectRefreshManagedExternally(boolean managedExternally) {

		// Only Subject transition ownership changes; ordinary classification controls
		// continue to be handled by this pane.
		subjectRefreshManagedExternally = managedExternally;
	}

	/**
	 * Locks subject and syllabus selection while allowing classification changes
	 * within that syllabus during question editing.
	 *
	 * @param locked whether the syllabus context is fixed
	 */
	public void setSyllabusContextLocked(boolean locked) {
		subjectBox.setDisable(locked);
		if (locked) {
			syllabusBox.setDisable(true);
			return;
		}
		syllabusBox.setDisable(subjectBox.getValue() == null || syllabusBox.getItems().isEmpty());
	}

	private void acceptDescriptorLookupSelection() {
		DescriptorMatch selected = descriptorLookupResults.getSelectionModel().getSelectedItem();
		if (selected == null || model.getSyllabusVersion() == null
				|| !model.getSyllabusVersion().equals(selected.descriptor().getSyllabusVersion())) {
			return;
		}

		// Use the existing authoritative hierarchy selection.
		// Normal capture and correction Save behaviour is unchanged.
		selectClassificationPath(selected.descriptor());
		invalidateDescriptorLookup();
	}

	private void applyClassificationPath(CurriculumNode classification) {
		boolean previouslyRefreshing = refreshingSubjects;
		refreshingSubjects = true;
		try {
			SyllabusVersion syllabusVersion = classification.getSyllabusVersion();
			selectAvailableValue(syllabusBox, syllabusVersion);
			if (syllabusBox.getValue() == null) {

				// Reaching this boundary after snapshot publication indicates an invalid
				// persisted classification rather than an asynchronous timing condition.
				throw new IllegalArgumentException("Classification syllabus is not available");
			}
			model.selectSyllabusVersion(syllabusBox.getValue());

			// Root Units for application-managed Subjects now come from the immutable
			// Subject snapshot, including historical syllabus versions.
			unitBox.getItems().setAll(model.getUnits());
			unitBox.setDisable(false);
			applyNodePath(classification);
		} finally {
			refreshingSubjects = previouslyRefreshing;
		}

		// Publish the final restored classification only after its complete hierarchy
		// path has been reconstructed.
		setCodeText(classification.getCode());
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

	private void buildContent() {

		// Working Subject remains exactly one compact row.
		Label subjectLabel = new Label("Subject");
		subjectLabel.setId("working-subject-label");
		subjectLabel.setStyle(HEADING_STYLE);
		StackPane subjectValue = new StackPane(subjectBox, readOnlySubjectLabel);
		subjectValue.setMaxWidth(Double.MAX_VALUE);
		HBox.setHgrow(subjectValue, Priority.ALWAYS);
		HBox subjectControls = new HBox(ROW_GAP, subjectValue, addSubjectButton);
		subjectControls.setAlignment(Pos.CENTER_LEFT);
		subjectControls.setMaxWidth(Double.MAX_VALUE);
		GridPane subjectRow = createTwoColumnGrid();
		subjectRow.addRow(0, subjectLabel, subjectControls);
		GridPane.setHgrow(subjectControls, Priority.ALWAYS);
		subjectContext = new VBox(subjectRow);
		subjectContext.setId("working-subject-context");
		subjectContext.setPadding(PANEL_PADDING);
		subjectContext.setStyle(BORDER_STYLE);
		classificationContext = new VBox(ROW_GAP, createSectionHeading("Classification"), createClassificationGrid(),
				descriptorLookupStatus, descriptorLookupResults);
		classificationContext.setId("classification-context");
		classificationContext.setPadding(PANEL_PADDING);
		classificationContext.setStyle(BORDER_STYLE);
		getChildren().addAll(subjectContext, classificationContext);
	}

	private SelectionSnapshot captureSelection() {
		return new SelectionSnapshot(subjectBox.getValue(), syllabusBox.getValue(), deepestSelectedNode());
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

	private <T> void clearComboBoxValue(ComboBox<T> box) {

		// JavaFX can retain ComboBox.value after the selection model is cleared.
		// Clear both representations so a stale displayed value cannot be read back
		// into CurriculumSelectionModel during programmatic hierarchy changes.
		box.getSelectionModel().clearSelection();
		box.setValue(null);
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
		codeField.textProperty().addListener((_, _, newValue) -> {
			handleCodeInput(newValue);
			refreshDescriptorSearchForScopeChange();
		});
		codeField.focusedProperty().addListener((_, _, focused) -> handleCodeFocusChanged(focused));
	}

	private void configureControls() {
		codeField.setId("curriculum-code");
		codeField.setPromptText("e.g. 3.1.2");
		codeField.setTooltip(new Tooltip(
				"Enter a complete curriculum code to select its Unit, Topic, Subtopic and Descriptor path."));
		codeField.setMaxWidth(Double.MAX_VALUE);
		codeField.setMinWidth(0);
		configureHierarchyBox(subjectBox, "curriculum-subject", "Select subject");
		configureHierarchyBox(syllabusBox, "curriculum-syllabus", "Select syllabus");
		configureHierarchyBox(unitBox, "curriculum-unit", "Select unit");
		configureHierarchyBox(topicBox, "curriculum-topic", "Select topic");
		configureHierarchyBox(subtopicBox, "curriculum-subtopic", "Select subtopic");
		configureHierarchyBox(descriptorBox, "curriculum-descriptor", "Select descriptor");

		// Keep Subject creation as a compact one-character action on the existing
		// Working Subject row rather than adding another row or long text button.
		addSubjectButton.setId("add-subject");
		addSubjectButton.setTooltip(new Tooltip("Add Subject"));
		addSubjectButton.setAccessibleText("Add Subject");
		addSubjectButton.setPadding(new Insets(2, 7, 2, 7));
		addSubjectButton.setMinWidth(Region.USE_PREF_SIZE);
		addSubjectButton.setMaxWidth(Region.USE_PREF_SIZE);

		// Standalone selector instances do not own Subject persistence. The containing
		// application enables this action when it supplies the persistence callback.
		addSubjectButton.setDisable(true);
		readOnlySubjectLabel.setId("working-subject-value");
		readOnlySubjectLabel.setMaxWidth(Double.MAX_VALUE);
		readOnlySubjectLabel.setStyle(HEADING_STYLE);
		readOnlySubjectLabel.setVisible(false);
		readOnlySubjectLabel.setManaged(false);
		syllabusBox.setDisable(true);
		unitBox.setDisable(true);
		topicBox.setDisable(true);
		subtopicBox.setDisable(true);
		descriptorBox.setDisable(true);
		hideSubtopicRow();
		hideDescriptorRow();
	}

	private void configureDescriptorLookup() {
		descriptorLookupText.setId("curriculum-descriptor-search");
		descriptorLookupText.setPromptText("Search by Descriptor code or keywords");
		descriptorLookupText.setMaxWidth(Double.MAX_VALUE);
		descriptorLookupStatus.setId("curriculum-descriptor-search-status");
		descriptorLookupStatus.setWrapText(true);
		descriptorLookupStatus.setVisible(false);
		descriptorLookupStatus.managedProperty().bind(descriptorLookupStatus.visibleProperty());
		descriptorLookupResults.setId("curriculum-descriptor-search-results");
		descriptorLookupResults.setPrefHeight(160);
		descriptorLookupResults.setMaxHeight(180);
		descriptorLookupResults.setVisible(false);
		descriptorLookupResults.managedProperty().bind(descriptorLookupResults.visibleProperty());

		// Full hierarchy paths distinguish similarly worded Descriptors.
		descriptorLookupResults.setCellFactory(_ -> {
			ListCell<DescriptorMatch> cell = new ListCell<>();
			Label path = new Label();
			path.setWrapText(true);
			path.prefWidthProperty().bind(descriptorLookupResults.widthProperty().subtract(30));
			cell.itemProperty().addListener((_, _, match) -> {
				if (match == null) {
					cell.setGraphic(null);
				} else {
					path.setText(match.hierarchyPath());
					cell.setGraphic(path);
				}
			});
			return cell;
		});
		descriptorLookupText.textProperty().addListener((_, _, _) -> searchDescriptors());

		// Down moves keyboard focus into the result list.
		descriptorLookupText.setOnKeyPressed(event -> {
			if (descriptorLookupResults.getItems().isEmpty()) {
				return;
			}
			if (event.getCode() == KeyCode.DOWN) {
				descriptorLookupResults.requestFocus();
				descriptorLookupResults.getSelectionModel().selectFirst();
				event.consume();
			} else if (event.getCode() == KeyCode.ENTER) {
				descriptorLookupResults.getSelectionModel().selectFirst();
				acceptDescriptorLookupSelection();
				event.consume();
			}
		});
		descriptorLookupResults.setOnKeyPressed(event -> {
			if (event.getCode() == KeyCode.ENTER) {
				acceptDescriptorLookupSelection();
				event.consume();
			}
		});
		descriptorLookupResults.setOnMouseClicked(event -> {
			if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 1) {
				acceptDescriptorLookupSelection();
			}
		});
	}

	private void configureHierarchyBox(ComboBox<?> box, String id, String promptText) {
		box.setId(id);
		box.setPromptText(promptText);
		box.setMaxWidth(Double.MAX_VALUE);
		box.setMinWidth(0);
	}

	private void configurePane() {

		// This pane now contains separate application-context and classification
		// sections rather than presenting Working Subject as Question metadata.
		setSpacing(ROW_GAP * 2);
	}

	private void configureSelectionHandlers() {
		subjectBox.setOnAction(_ -> handleSubjectSelection());
		subjectBox.valueProperty().addListener((_, _, subject) -> updateReadOnlySubject(subject));
		syllabusBox.setOnAction(_ -> handleSyllabusSelection());
		syllabusBox.valueProperty().addListener((_, _, _) -> invalidateDescriptorLookup());
		unitBox.getSelectionModel().selectedItemProperty().addListener((_, _, _) -> handleUnitSelection());
		topicBox.getSelectionModel().selectedItemProperty().addListener((_, _, _) -> handleTopicSelection());
		subtopicBox.getSelectionModel().selectedItemProperty().addListener((_, _, _) -> handleSubtopicSelection());
		descriptorBox.getSelectionModel().selectedItemProperty().addListener((_, _, _) -> handleDescriptorSelection());
	}

	private GridPane createClassificationGrid() {
		GridPane grid = createTwoColumnGrid();
		grid.addRow(0, new Label("Code"), codeField);
		grid.addRow(1, new Label("Syllabus"), syllabusBox);
		grid.addRow(2, new Label("Unit"), unitBox);
		grid.addRow(3, new Label("Topic"), topicBox);
		grid.addRow(4, subtopicLabel, subtopicBox);
		grid.addRow(5, descriptorLabel, descriptorBox);
		grid.addRow(6, new Label("Search descriptors"), descriptorLookupText);
		GridPane.setHgrow(codeField, Priority.ALWAYS);
		GridPane.setHgrow(syllabusBox, Priority.ALWAYS);
		GridPane.setHgrow(unitBox, Priority.ALWAYS);
		GridPane.setHgrow(topicBox, Priority.ALWAYS);
		GridPane.setHgrow(subtopicBox, Priority.ALWAYS);
		GridPane.setHgrow(descriptorBox, Priority.ALWAYS);
		GridPane.setHgrow(descriptorLookupText, Priority.ALWAYS);
		return grid;
	}

	private Label createSectionHeading(String text) {
		Label heading = new Label(text);
		heading.setStyle(HEADING_STYLE);
		return heading;
	}

	private GridPane createTwoColumnGrid() {
		GridPane grid = new GridPane();
		grid.setHgap(COLUMN_GAP);
		grid.setVgap(ROW_GAP);
		ColumnConstraints labelColumn = new ColumnConstraints();
		labelColumn.setMinWidth(LABEL_COLUMN_WIDTH);
		labelColumn.setPrefWidth(LABEL_COLUMN_WIDTH);
		ColumnConstraints controlColumn = new ColumnConstraints();
		controlColumn.setMinWidth(0);
		controlColumn.setHgrow(Priority.ALWAYS);
		controlColumn.setFillWidth(true);
		grid.getColumnConstraints().addAll(labelColumn, controlColumn);
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

	private void handleCodeFocusChanged(Boolean focused) {
		if (!focused.booleanValue()) {
			syncCodeFromSelection();
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

		// While a code is incomplete, navigate to its deepest existing ancestor.
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
		try (PerformanceOperation operation = performanceRecorder.start("capture.curriculum.descriptor")) {
			try {
				model.selectDescriptor(descriptorBox.getValue());
				syncCodeFromSelection();
				operation.resultCount(descriptorBox.getValue() == null ? 0 : 1);
			} catch (RuntimeException | Error failure) {
				operation.failed();
				throw failure;
			}
		}
	}

	private void handleSubjectSelection() {
		if (refreshingSubjects || refreshingCode) {
			return;
		}
		if (subjectRefreshManagedExternally) {

			// The application value-property listener has already accepted or rejected
			// this Working Subject transition and owns any required persistence work.
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

	private void handleSubtopicSelection() {
		if (refreshingSubjects || refreshingCode) {
			return;
		}
		try (PerformanceOperation operation = performanceRecorder.start("capture.curriculum.subtopic")) {
			try {
				CurriculumNode subtopic = subtopicBox.getValue();
				model.selectSubtopic(subtopic);
				descriptorBox.getSelectionModel().clearSelection();
				if (subtopic == null) {
					hideDescriptorRow();
				} else {
					refreshDescriptorRow(operation.id());
				}
				syncCodeFromSelection();
				operation.resultCount(descriptorBox.getItems().size());
			} catch (RuntimeException | Error failure) {
				operation.failed();
				throw failure;
			}
		}
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
		try (PerformanceOperation operation = performanceRecorder.start("capture.curriculum.topic")) {
			try {
				CurriculumNode topic = topicBox.getValue();
				model.selectTopic(topic);
				if (topic == null) {
					hideSubtopicRow();
					hideDescriptorRow();
					syncCodeFromSelection();
					operation.resultCount(0);
					return;
				}
				refreshFinalClassificationRows(operation.id());
				syncCodeFromSelection();
				operation.resultCount(
						subtopicBox.isVisible() ? subtopicBox.getItems().size() : descriptorBox.getItems().size());
			} catch (RuntimeException | Error failure) {
				operation.failed();
				throw failure;
			}
		}
	}

	private void handleUnitSelection() {
		if (refreshingSubjects || refreshingCode) {
			return;
		}
		try (PerformanceOperation operation = performanceRecorder.start("capture.curriculum.unit")) {
			try {
				CurriculumNode unit = unitBox.getValue();
				model.selectUnit(unit);
				topicBox.getSelectionModel().clearSelection();
				hideSubtopicRow();
				hideDescriptorRow();
				if (unit == null) {
					topicBox.getItems().clear();
					topicBox.setDisable(true);
					syncCodeFromSelection();
					operation.resultCount(0);
					return;
				}
				List<CurriculumNode> topics = measureHierarchyLookup("capture.curriculum.topics.lookup", operation.id(),
						model::getTopics);
				topicBox.getItems().setAll(topics);
				topicBox.setDisable(false);
				syncCodeFromSelection();
				operation.resultCount(topics.size());
			} catch (RuntimeException | Error failure) {
				operation.failed();
				throw failure;
			}
		}
	}

	private boolean hasCodePrefix(List<CurriculumNode> nodes, String code) {
		for (CurriculumNode node : nodes) {
			if (node.getCode().startsWith(code)) {
				return true;
			}
		}
		return false;
	}

	private void hideDescriptorRow() {

		// A hidden Descriptor must not remain as the ComboBox value after its
		// classification path has been shortened.
		clearComboBoxValue(descriptorBox);
		descriptorBox.getItems().clear();
		setDescriptorRowVisible(false);
	}

	private void hideSubtopicRow() {

		// A hidden hierarchy level must not retain a value from the previously
		// displayed path.
		clearComboBoxValue(subtopicBox);
		subtopicBox.getItems().clear();
		subtopicLabel.setVisible(false);
		subtopicLabel.setManaged(false);
		subtopicBox.setVisible(false);
		subtopicBox.setManaged(false);
		subtopicBox.setDisable(true);
	}

	private void invalidateDescriptorLookup() {
		descriptorContextGeneration++;
		descriptorQueryGeneration++;
		descriptorLoadedSyllabus = null;
		descriptorLoadingSyllabus = null;
		descriptorSnapshot = List.of();
		descriptorLookupText.clear();
		descriptorLookupResults.getItems().clear();
		descriptorLookupResults.setVisible(false);
		descriptorLookupStatus.setText("");
		descriptorLookupStatus.setVisible(false);
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

	private void loadDescriptorLookup(SyllabusVersion syllabus) {

		// Repeated keystrokes must not start repeated SQLite reads.
		if (syllabus.equals(descriptorLoadingSyllabus)) {
			return;
		}
		descriptorLoadingSyllabus = syllabus;
		long generation = descriptorContextGeneration;
		CompletableFuture.supplyAsync(() -> descriptorLookupService.loadDescriptors(syllabus))
				.whenComplete((descriptors, failure) -> Platform.runLater(() -> {

					// Ignore work from an obsolete Subject or syllabus.
					if (generation != descriptorContextGeneration || !syllabus.equals(model.getSyllabusVersion())
							|| !syllabus.equals(syllabusBox.getValue())) {
						return;
					}
					descriptorLoadingSyllabus = null;
					if (failure != null) {
						descriptorLookupStatus.setText("Could not load Descriptors.");
						return;
					}
					descriptorLoadedSyllabus = syllabus;
					descriptorSnapshot = descriptors;

					// Search with the latest text, not the text that initiated
					// the asynchronous database request.
					searchDescriptors();
				}));
	}

	private List<CurriculumNode> measureHierarchyLookup(String name, long parentOperationId,
			Supplier<List<CurriculumNode>> lookup) {
		try (PerformanceOperation operation = performanceRecorder.start(name, parentOperationId)) {
			try {

				// The selection model performs the existing repository
				// child lookup synchronously.
				List<CurriculumNode> nodes = lookup.get();
				operation.resultCount(nodes.size());
				return nodes;
			} catch (RuntimeException | Error failure) {
				operation.failed();
				throw failure;
			}
		}
	}

	private void publishDescriptorLookupResults(SyllabusVersion syllabus, long generation,
			List<DescriptorMatch> matches, Throwable failure) {
		if (generation != descriptorQueryGeneration || !syllabus.equals(model.getSyllabusVersion())
				|| !syllabus.equals(syllabusBox.getValue())) {
			return;
		}
		if (failure != null) {
			descriptorLookupStatus.setText("Descriptor search could not be completed.");
			return;
		}
		descriptorLookupResults.getItems().setAll(matches);
		descriptorLookupResults.setVisible(!matches.isEmpty());
		if (matches.isEmpty()) {
			descriptorLookupStatus.setText("No matching Descriptors.");
		} else if (matches.size() == DESCRIPTOR_LOOKUP_LIMIT) {
			descriptorLookupStatus.setText("Showing up to " + DESCRIPTOR_LOOKUP_LIMIT + " matching Descriptors.");
		} else {
			descriptorLookupStatus
					.setText(matches.size() + " matching Descriptor" + (matches.size() == 1 ? "." : "s."));
		}
	}

	private void refreshDescriptorRow() {
		refreshDescriptorRow(0);
	}

	private void refreshDescriptorRow(long parentOperationId) {
		descriptorBox.getSelectionModel().clearSelection();
		List<CurriculumNode> descriptors = measureHierarchyLookup("capture.curriculum.descriptors.lookup",
				parentOperationId, model::getDescriptors);
		descriptorBox.getItems().setAll(descriptors);
		setDescriptorRowVisible(!descriptorBox.getItems().isEmpty());
	}

	private void refreshDescriptorSearchForScopeChange() {
		if (refreshingCode || refreshingSubjects) {
			return;
		}
		String keywords = descriptorLookupText.getText();
		if (keywords != null && !keywords.isBlank()) {

			// A new hierarchy scope invalidates results from
			// every earlier asynchronous search.
			searchDescriptors();
		}
	}

	private void refreshFinalClassificationRows() {
		refreshFinalClassificationRows(0);
	}

	private void refreshFinalClassificationRows(long parentOperationId) {
		subtopicBox.getSelectionModel().clearSelection();
		descriptorBox.getSelectionModel().clearSelection();
		List<CurriculumNode> availableSubtopics = measureHierarchyLookup("capture.curriculum.subtopics.lookup",
				parentOperationId, model::getSubtopics);
		if (!availableSubtopics.isEmpty()) {

			// Retain the existing second lookup. Its cost is
			// deliberately measured before considering optimisation.
			List<CurriculumNode> displayedSubtopics = measureHierarchyLookup("capture.curriculum.subtopics.lookup",
					parentOperationId, model::getSubtopics);
			subtopicBox.getItems().setAll(displayedSubtopics);
			showSubtopicRow();
			hideDescriptorRow();
			return;
		}
		hideSubtopicRow();
		List<CurriculumNode> descriptors = measureHierarchyLookup("capture.curriculum.descriptors.lookup",
				parentOperationId, model::getDescriptors);
		descriptorBox.getItems().setAll(descriptors);
		setDescriptorRowVisible(!descriptorBox.getItems().isEmpty());
	}

	private void refreshSelectedClassificationProperty() {
		selectedClassification.set(model.getClassification());
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

	private void requireLevel(CurriculumNode node, CurriculumLevel level, String message) {
		if (node == null || node.getLevel() != level) {
			throw new IllegalArgumentException(message);
		}
	}

	private void resetHierarchyChoices() {
		unitBox.getItems().setAll(model.getUnits());
		unitBox.setDisable(model.getSyllabusVersion() == null);
		topicBox.getItems().clear();
		topicBox.setDisable(true);
		hideSubtopicRow();
		hideDescriptorRow();
	}

	private void restoreHierarchyControlsAfterLookupClear() {

		// The lookup is an aid to classification, not a mode that
		// changes which hierarchy controls may be used.
		unitBox.setDisable(model.getSyllabusVersion() == null);
		topicBox.setDisable(model.getUnit() == null);
		subtopicBox.setDisable(model.getTopic() == null || !subtopicBox.isVisible());
		descriptorBox.setDisable(model.getTopic() == null || !descriptorBox.isVisible());

		// Do not change subjectBox, syllabusBox or the enclosing
		// classificationContext. Their locks belong to the
		// authoritative Question Capture workflow.
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

	private void searchDescriptors() {
		long generation = ++descriptorQueryGeneration;
		String query = descriptorLookupText.getText();
		descriptorLookupResults.getItems().clear();
		descriptorLookupResults.setVisible(false);
		if (query == null || query.isBlank()) {

			// Clearing keywords must not remove the authoritative
			// classification or leave hierarchy controls inactive.
			descriptorLookupStatus.setText("");
			descriptorLookupStatus.setVisible(false);
			restoreHierarchyControlsAfterLookupClear();
			return;
		}
		descriptorLookupStatus.setVisible(true);
		SyllabusVersion syllabus = model.getSyllabusVersion();
		if (syllabus == null || !syllabus.equals(syllabusBox.getValue()) || model.getSubject() == null
				|| !model.getSubject().equals(syllabus.getSubject())) {
			descriptorLookupStatus.setText("Select a syllabus before searching.");
			return;
		}
		if (!syllabus.equals(descriptorLoadedSyllabus)) {
			descriptorLookupStatus.setText("Loading Descriptors...");
			loadDescriptorLookup(syllabus);
			return;
		}

		// Capture both filters before dispatching background work.
		// The completed result is accepted only if this query
		// generation is still authoritative.
		String codePrefix = searchScopeCode();
		List<CurriculumNode> snapshot = descriptorSnapshot;
		descriptorLookupStatus.setText("Searching...");
		CompletableFuture
				.supplyAsync(() -> descriptorLookupService.findMatches(syllabus, snapshot, query, codePrefix,
						DESCRIPTOR_LOOKUP_LIMIT))
				.whenComplete((matches, failure) -> Platform
						.runLater(() -> publishDescriptorLookupResults(syllabus, generation, matches, failure)));
	}

	private String searchScopeCode() {

		// The Code field is the most precise expression of the
		// user's intended curriculum scope.
		String code = codeField.getText();
		if (code != null && !code.isBlank()) {
			return code.strip();
		}

		// Fall back to the selected hierarchy when Code is empty.
		// Do not use the final Descriptor as an implicit constraint:
		// the user may be looking for an alternative classification.
		if (model.getTopic() != null) {
			return model.getTopic().getCode();
		}
		if (model.getUnit() != null) {
			return model.getUnit().getCode();
		}
		return "";
	}

	private <T> void selectAvailableValue(ComboBox<T> box, T selectedValue) {
		if (selectedValue != null) {
			for (T item : box.getItems()) {
				if (item.equals(selectedValue)) {

					// Select the actual item held by this ComboBox so its displayed value and
					// selection model refer to the same hierarchy object.
					box.getSelectionModel().select(item);
					return;
				}
			}
		}

		// A missing requested value means this hierarchy level is deliberately empty.
		// Clear both JavaFX selection state and the independently retained value.
		clearComboBoxValue(box);
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

		// Unit and Topic ComboBox selections also establish
		// the scope of an active Descriptor keyword search.
		refreshDescriptorSearchForScopeChange();
	}

	private void updateReadOnlySubject(Subject subject) {

		// Specialised application surfaces show context only. The actual Subject
		// selection remains owned by the hidden authoritative ComboBox.
		readOnlySubjectLabel.setText(subject == null ? "No Subject selected" : subject.getName());
	}

	private record ClassificationPath(CurriculumNode unit, CurriculumNode topic, CurriculumNode subtopic,
			CurriculumNode descriptor) {
	}

	private record SelectionSnapshot(Subject subject, SyllabusVersion syllabus, CurriculumNode node) {
	}
}
