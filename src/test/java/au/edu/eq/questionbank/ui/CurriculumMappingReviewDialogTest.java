package au.edu.eq.questionbank.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;

import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.CurriculumMappingReviewOutcome;
import au.edu.eq.questionbank.model.CurriculumMappingSuggestion;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Descriptor;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.Subtopic;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.repository.curriculum.CurriculumMappingRepository;
import au.edu.eq.questionbank.repository.curriculum.CurriculumMappingReviewRepository;
import au.edu.eq.questionbank.repository.curriculum.CurriculumRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumMappingRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumMappingReviewRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumMappingReviewWriter;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.curriculum.CurriculumMappingSuggester;
import au.edu.eq.questionbank.service.curriculum.SubtopicMappingEvidenceService;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

@Tag("ui")
@ExtendWith(ApplicationExtension.class)
class CurriculumMappingReviewDialogTest {

	private CurriculumMappingReviewDialog dialog;
	private CurriculumMappingReviewRepository reviewRepository;
	private Descriptor secondSourceDescriptor;
	private Descriptor thirdSourceDescriptor;
	private Subject subject;
	private SyllabusVersion sourceVersion;
	private SyllabusVersion targetVersion;

	@Test
	@SuppressWarnings("unchecked")
	void confirmingReviewAdvancesToNextUnresolvedNode(FxRobot robot) throws Exception {
		ComboBox<Subject> subjectBox = field("subjectBox", ComboBox.class);
		ComboBox<SyllabusVersion> sourceVersionBox = field("sourceVersionBox", ComboBox.class);
		ComboBox<CurriculumNode> sourceDescriptorBox = field("sourceDescriptorBox", ComboBox.class);
		ListView<CurriculumMappingSuggestion> suggestionsList = field("suggestionsList", ListView.class);
		robot.interact(() -> {
			subjectBox.setValue(subject);
			sourceVersionBox.setValue(sourceVersion);
			sourceDescriptorBox.setValue(secondSourceDescriptor);
			try {
				CurriculumMappingSuggestion suggestion = suggestionsList.getItems().getFirst();
				invoke("updateTargetSelection", new Class<?>[] { CurriculumMappingSuggestion.class, boolean.class },
						suggestion, true);
				invoke("confirmReview", new Class<?>[0]);
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException(e);
			}
		});
		assertEquals(thirdSourceDescriptor, sourceDescriptorBox.getValue());
		assertEquals(CurriculumMappingReviewOutcome.MATCHED,
				reviewRepository.findOutcome(secondSourceDescriptor, targetVersion).orElseThrow());
	}

	@Start
	void start(Stage stage) throws Exception {
		stage.setScene(new Scene(new StackPane()));
		SqliteDatabase database = new SqliteDatabase(
				Files.createTempDirectory("mapping-dialog-test-").resolve("questionbank.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		subject = curriculumWriter.insertSubject("Chemistry");
		sourceVersion = curriculumWriter.insertSyllabusVersion(subject, "2019", false);
		Unit sourceUnit = curriculumWriter.insertUnit(sourceVersion, "1", "Source unit", 0);
		Topic sourceTopic = curriculumWriter.insertTopic(sourceUnit, "1.1", "Source topic", 0);
		curriculumWriter.insertDescriptor(sourceTopic, "1.1.1", "First source descriptor", 0);
		secondSourceDescriptor = curriculumWriter.insertDescriptor(sourceTopic, "1.1.2", "Second source descriptor", 1);
		thirdSourceDescriptor = curriculumWriter.insertDescriptor(sourceTopic, "1.1.3", "Third source descriptor", 2);
		Subtopic sourceSubtopic = curriculumWriter.insertSubtopic(sourceTopic, "1.1.4", "Source subtopic", 3);
		Descriptor firstEvidenceDescriptor = curriculumWriter.insertDescriptor(sourceSubtopic, "1.1.4.1",
				"First evidence descriptor", 0);
		Descriptor secondEvidenceDescriptor = curriculumWriter.insertDescriptor(sourceSubtopic, "1.1.4.2",
				"Second evidence descriptor", 1);
		curriculumWriter.insertDescriptor(sourceSubtopic, "1.1.4.3", "Third evidence descriptor", 2);
		targetVersion = curriculumWriter.insertSyllabusVersion(subject, "2025", true);
		Unit targetUnit = curriculumWriter.insertUnit(targetVersion, "2", "Target unit", 0);
		Topic targetTopic = curriculumWriter.insertTopic(targetUnit, "2.1", "Target topic", 0);
		Descriptor targetDescriptor = curriculumWriter.insertDescriptor(targetTopic, "2.1.1", "Target descriptor", 0);
		SqliteCurriculumMappingReviewWriter reviewWriter = new SqliteCurriculumMappingReviewWriter(database);
		reviewWriter.confirmMappings(firstEvidenceDescriptor, targetVersion, List.of(targetDescriptor));
		reviewWriter.confirmNoMatch(secondEvidenceDescriptor, targetVersion);
		CurriculumRepository repository = new SqliteCurriculumRepository(database);
		reviewRepository = new SqliteCurriculumMappingReviewRepository(database);
		CurriculumMappingRepository mappingRepository = new SqliteCurriculumMappingRepository(database);
		CurriculumMappingSuggester descriptorSuggester = (source, _) -> List
				.of(new CurriculumMappingSuggestion(source, targetDescriptor, 1.0));
		CurriculumMappingSuggester emptySubtopicSuggester = (_, _) -> List.of();
		SubtopicMappingEvidenceService evidenceService = new SubtopicMappingEvidenceService(repository,
				reviewRepository);
		dialog = new CurriculumMappingReviewDialog(stage, repository, descriptorSuggester, emptySubtopicSuggester,
				evidenceService, reviewRepository, mappingRepository, reviewWriter);
	}

	@Test
	@SuppressWarnings("unchecked")
	void subtopicModeDisplaysDescriptorReviewCoverageAndNoMatchCount(FxRobot robot) throws Exception {
		ComboBox<Subject> subjectBox = field("subjectBox", ComboBox.class);
		ComboBox<SyllabusVersion> sourceVersionBox = field("sourceVersionBox", ComboBox.class);
		ComboBox<CurriculumLevel> reviewLevelBox = field("reviewLevelBox", ComboBox.class);
		Label evidenceLabel = field("subtopicEvidenceLabel", Label.class);
		robot.interact(() -> {
			subjectBox.setValue(subject);
			sourceVersionBox.setValue(sourceVersion);
			reviewLevelBox.setValue(CurriculumLevel.SUBTOPIC);
		});
		assertTrue(evidenceLabel.isVisible());
		assertTrue(evidenceLabel.isManaged());
		assertEquals("Descriptor review coverage: 2 / 3    No-match descriptors: 1", evidenceLabel.getText());
		robot.interact(() -> reviewLevelBox.setValue(CurriculumLevel.DESCRIPTOR));
		assertFalse(evidenceLabel.isVisible());
		assertFalse(evidenceLabel.isManaged());
		assertEquals("", evidenceLabel.getText());
	}

	private <T> T field(String name, Class<T> type) throws ReflectiveOperationException {
		Field field = CurriculumMappingReviewDialog.class.getDeclaredField(name);
		field.setAccessible(true);
		return type.cast(field.get(dialog));
	}

	private Object invoke(String methodName, Class<?>[] parameterTypes, Object... arguments)
			throws ReflectiveOperationException {
		Method method = CurriculumMappingReviewDialog.class.getDeclaredMethod(methodName, parameterTypes);
		method.setAccessible(true);
		return method.invoke(dialog, arguments);
	}
}
