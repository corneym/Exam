package au.edu.eq.questionbank.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;

import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.service.curriculum.CurriculumDraft;
import au.edu.eq.questionbank.service.curriculum.CurriculumDraftNode;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.stage.Stage;

@Tag("ui")
@ExtendWith(ApplicationExtension.class)
class CurriculumAuthoringPaneTest {

	@TempDir
	Path tempDir;
	private CurriculumDraft draft;
	private CurriculumAuthoringPane pane;

	@Test
	void addsExplicitHierarchyNodesUsingCurrentPdfPageProvenance(FxRobot robot) throws Exception {
		Path syllabus = createTextPdf("syllabus.pdf", "Unit one: foundations", "Topic two: psychological disorders");
		robot.interact(() -> pane.openSyllabusPdf(syllabus));
		TextArea pageText = robot.lookup("#syllabus-page-text").queryAs(TextArea.class);
		TextField code = robot.lookup("#curriculum-code").queryAs(TextField.class);
		TextArea nodeText = robot.lookup("#curriculum-node-text").queryAs(TextArea.class);
		Button useText = robot.lookup("#use-selected-syllabus-text").queryAs(Button.class);
		Button addNode = robot.lookup("#add-curriculum-node").queryAs(Button.class);
		assertTrue(pageText.getText().contains("Unit one: foundations"));
		robot.interact(() -> {
			code.setText("U");
			pageText.selectRange(0, "Unit one: foundations".length());
			useText.fire();
		});
		assertEquals("Unit one: foundations", nodeText.getText());
		robot.interact(addNode::fire);
		CurriculumDraftNode unit = draft.nodes().get(0);
		assertEquals(CurriculumLevel.UNIT, unit.level());
		assertEquals("U", unit.code());
		assertEquals("Unit one: foundations", unit.name());
		assertEquals(1, unit.sourcePageNumber());
		robot.interact(() -> ((Button) pane.lookup("#next-pdf-page")).fire());
		assertTrue(pageText.getText().contains("Topic two: psychological disorders"));
		@SuppressWarnings("unchecked")
		ComboBox<CurriculumLevel> levelBox = robot.lookup("#curriculum-level").queryAs(ComboBox.class);
		@SuppressWarnings("unchecked")
		ComboBox<CurriculumDraftNode> parentBox = robot.lookup("#curriculum-parent").queryAs(ComboBox.class);
		robot.interact(() -> {
			levelBox.setValue(CurriculumLevel.TOPIC);
			parentBox.setValue(unit);
			code.setText("T");
			pageText.selectRange(0, "Topic two: psychological disorders".length());
			useText.fire();
			addNode.fire();
		});
		CurriculumDraftNode topic = draft.nodes().get(1);
		assertEquals(CurriculumLevel.TOPIC, topic.level());
		assertEquals(unit.draftId(), topic.parentDraftId());
		assertEquals("Topic two: psychological disorders", topic.name());
		assertEquals(2, topic.sourcePageNumber());
		assertEquals(2, draft.nodes().size());
	}

	@AfterEach
	void close() throws Exception {
		pane.close();
	}

	@Start
	void start(Stage stage) {
		draft = new CurriculumDraft();
		pane = new CurriculumAuthoringPane(stage, tempDir, draft);
		stage.setScene(new Scene(pane, 1100, 700));
		stage.show();
	}

	private Path createTextPdf(String name, String... pageTexts) throws Exception {
		Path path = tempDir.resolve(name);
		try (PDDocument document = new PDDocument()) {
			for (String text : pageTexts) {
				PDPage page = new PDPage(new PDRectangle(400, 300));
				document.addPage(page);
				try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
					contentStream.beginText();
					contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
					contentStream.newLineAtOffset(40, 250);
					contentStream.showText(text);
					contentStream.endText();
				}
			}
			document.save(path.toFile());
		}
		return path;
	}
}
