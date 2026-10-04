package au.edu.eq.questionbank.ui.audit;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;

import au.edu.eq.questionbank.model.Descriptor;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamAssetExpectations;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamCaptureState;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.service.audit.BookletCorpusFinding;
import au.edu.eq.questionbank.service.audit.BookletCorpusStatus;
import au.edu.eq.questionbank.service.audit.ExamCorpusFinding;
import au.edu.eq.questionbank.service.audit.ExamCorpusStatus;
import au.edu.eq.questionbank.service.audit.McqExplanationCoverage;
import au.edu.eq.questionbank.service.audit.McqExplanationSummary;
import au.edu.eq.questionbank.service.audit.QuestionCorpusSummary;
import javafx.scene.Scene;
import javafx.stage.Stage;

@Tag("ui")
@ExtendWith(ApplicationExtension.class)
class QuestionCorpusAuditDialogDashboardTest {

	private QuestionCorpusAuditDialog dialog;

	@Test
	void dialogProvidesTallOperationalWorkspace() {

		// The Dashboard needs enough initial height for Exam scope, at least several
		// booklet rows and a compact Question-work table at the same time.
		assertTrue(dialog.getDialogPane().getPrefHeight() >= 940.0);
	}

	@Test
	void dialogUsesOperationalDashboardPane() {
		CorpusDashboardPane dashboard = assertInstanceOf(CorpusDashboardPane.class,
				dialog.getDialogPane().getContent());

		// Inspect the Dialog's owned Dashboard tree directly. The Dialog need not be
		// showing for this structural regression.
		assertNull(dashboard.lookup("#corpus-dashboard-refresh"));

		// Capture and correction actions remain part of the operational Dashboard while
		// the former generic Resolve Selected action remains retired.
		assertNotNull(dashboard.lookup("#corpus-dashboard-capture-questions"));
		assertNotNull(dashboard.lookup("#corpus-dashboard-capture-answers"));
		assertNotNull(dashboard.lookup("#corpus-dashboard-complete-question"));
		assertNull(dashboard.lookup("#corpus-resolve-selected"));
	}

	@Start
	void start(Stage stage) {
		Subject chemistry = new Subject(1, "Chemistry");
		ExamProvider provider = new ExamProvider(2, "QCAA");
		Exam exam = new Exam(3, chemistry, provider, 2025, "External Assessment", ExamCaptureState.COMPLETE);
		ExamBooklet booklet = new ExamBooklet(4, exam, "Paper 1", new SourceDocument(5, "Chemistry/2025/paper1.pdf"));
		SyllabusVersion syllabus = new SyllabusVersion(6, chemistry, "2025", true);
		Unit unit = new Unit(7, syllabus, "1", "Unit 1", 1);
		Topic topic = new Topic(8, syllabus, unit, "1.1", "Topic 1", 1);
		Descriptor descriptor = new Descriptor(9, syllabus, topic, "1.1.1", "Descriptor", 1);
		Question question = new Question(10, booklet, "Q1", "", 2,
				List.of(new QuestionRegion(booklet, 1, 0.10, 0.10, 0.70, 0.20)), descriptor, false, null, null,
				QuestionResponseType.WRITTEN_RESPONSE);
		BookletCorpusStatus bookletStatus = new BookletCorpusStatus(booklet, true, null, 1, 1, 0,
				new QuestionCorpusSummary(1, 0, 1, 0, 1, 0, 0), new McqExplanationCoverage(false, 0, 0),
				EnumSet.noneOf(BookletCorpusFinding.class));
		ExamCorpusStatus examStatus = new ExamCorpusStatus(exam, new ExamAssetExpectations(1, 1, 0, 0),
				List.of(bookletStatus), new QuestionCorpusSummary(1, 0, 1, 0, 1, 0, 0),
				new McqExplanationSummary(0, 0, 0), EnumSet.noneOf(ExamCorpusFinding.class));

		// The owner window need only be showing; the dialog itself need not be opened
		// for its content hierarchy to be inspected.
		stage.setScene(new Scene(new javafx.scene.layout.StackPane(), 200, 100));
		stage.show();
		dialog = new QuestionCorpusAuditDialog(stage, chemistry, List.of(examStatus), List.of(question));
	}
}
