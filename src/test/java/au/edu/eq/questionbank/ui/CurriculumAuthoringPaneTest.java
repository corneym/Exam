package au.edu.eq.questionbank.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
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
import javafx.scene.control.TextArea;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.stage.Stage;

@Tag("ui")
@ExtendWith(ApplicationExtension.class)
class CurriculumAuthoringPaneTest {

	@TempDir
	Path tempDir;
	private CurriculumDraft draft;
	private CurriculumAuthoringPane pane;

	@Test
	void capturesExplicitHierarchyWithoutEnteringCodesOrParents(FxRobot robot) throws Exception {
		Path syllabus = createTextPdf("capture.pdf", "Unit one", "Chemical equilibrium", "Explain dynamic equilibrium");
		robot.interact(() -> pane.openSyllabusPdf(syllabus));
		TextArea pageText = robot.lookup("#syllabus-page-text").queryAs(TextArea.class);
		Button addUnit = robot.lookup("#add-curriculum-unit").queryAs(Button.class);
		Button addTopic = robot.lookup("#add-curriculum-topic").queryAs(Button.class);
		Button addDescriptor = robot.lookup("#add-curriculum-descriptor").queryAs(Button.class);
		robot.interact(() -> {
			pageText.selectAll();
			addUnit.fire();
		});
		CurriculumDraftNode unit = draft.nodes().get(0);
		assertEquals(CurriculumLevel.UNIT, unit.level());
		assertEquals("1", unit.code());
		assertEquals("Unit one", unit.name());
		assertEquals(1, unit.sourcePageNumber());
		robot.interact(() -> ((Button) pane.lookup("#next-pdf-page")).fire());
		robot.interact(() -> {
			pageText.selectAll();
			addTopic.fire();
		});
		CurriculumDraftNode topic = draft.nodes().get(1);
		assertEquals(CurriculumLevel.TOPIC, topic.level());
		assertEquals("1.1", topic.code());
		assertEquals(unit.draftId(), topic.parentDraftId());
		assertEquals(2, topic.sourcePageNumber());
		@SuppressWarnings("unchecked")
		TreeView<CurriculumDraftNode> tree = robot.lookup("#curriculum-draft-tree").queryAs(TreeView.class);
		robot.interact(() -> tree.getSelectionModel().select(findByCode(tree.getRoot(), "1.1")));
		robot.interact(() -> ((Button) pane.lookup("#next-pdf-page")).fire());
		robot.interact(() -> {
			pageText.selectAll();
			addDescriptor.fire();
		});
		CurriculumDraftNode descriptor = draft.nodes().get(2);
		assertEquals(CurriculumLevel.DESCRIPTOR, descriptor.level());
		assertEquals("1.1.1", descriptor.code());
		assertEquals(topic.draftId(), descriptor.parentDraftId());
		assertEquals("Explain dynamic equilibrium", descriptor.name());
		assertEquals(3, descriptor.sourcePageNumber());
	}

	@AfterEach
	void close() throws Exception {
		pane.close();
	}

	@Test
	void editsSelectedNodeTextWithoutChangingIdentityOrCode(FxRobot robot) throws Exception {
		Path syllabus = createTextPdf("edit.pdf", "Original unit");
		robot.interact(() -> pane.openSyllabusPdf(syllabus));
		TextArea pageText = robot.lookup("#syllabus-page-text").queryAs(TextArea.class);
		Button addUnit = robot.lookup("#add-curriculum-unit").queryAs(Button.class);
		TextArea editText = robot.lookup("#curriculum-edit-text").queryAs(TextArea.class);
		Button update = robot.lookup("#update-curriculum-text").queryAs(Button.class);
		robot.interact(() -> {
			pageText.selectAll();
			addUnit.fire();
			editText.setText("Corrected unit wording");
			update.fire();
		});
		CurriculumDraftNode unit = draft.findNode(1).orElseThrow();
		assertEquals(1, unit.draftId());
		assertEquals("1", unit.code());
		assertEquals("Corrected unit wording", unit.name());
	}

	@Test
	void exportsCurrentTreeBasedDraft(FxRobot robot) throws Exception {
		Path syllabus = createTextPdf("export.pdf", "Unit wording");
		robot.interact(() -> pane.openSyllabusPdf(syllabus));
		TextArea pageText = robot.lookup("#syllabus-page-text").queryAs(TextArea.class);
		Button addUnit = robot.lookup("#add-curriculum-unit").queryAs(Button.class);
		robot.interact(() -> {
			pageText.selectAll();
			addUnit.fire();
		});
		Path target = tempDir.resolve("draft-preview.txt");
		pane.exportDraft(target);
		String text = Files.readString(target);
		assertTrue(text.contains("UNIT [draftId=1]"));
		assertTrue(text.contains("code: 1"));
		assertTrue(text.contains("Unit wording"));
		assertTrue(text.contains("sourcePage: 1"));
		assertTrue(text.contains("VALIDATION: VALID"));
	}

	@Test
	void keepsTopicSelectedWhileCapturingRepeatedDescriptors(FxRobot robot) throws Exception {
		String page = "Unit text Topic text First descriptor Second descriptor";
		Path syllabus = createTextPdf("sticky-parent.pdf", page);
		robot.interact(() -> pane.openSyllabusPdf(syllabus));
		TextArea pageText = robot.lookup("#syllabus-page-text").queryAs(TextArea.class);
		Button addUnit = robot.lookup("#add-curriculum-unit").queryAs(Button.class);
		Button addTopic = robot.lookup("#add-curriculum-topic").queryAs(Button.class);
		Button addDescriptor = robot.lookup("#add-curriculum-descriptor").queryAs(Button.class);
		selectText(robot, pageText, page, "Unit text");
		robot.interact(addUnit::fire);
		selectText(robot, pageText, page, "Topic text");
		robot.interact(addTopic::fire);
		@SuppressWarnings("unchecked")
		TreeView<CurriculumDraftNode> tree = robot.lookup("#curriculum-draft-tree").queryAs(TreeView.class);
		robot.interact(() -> tree.getSelectionModel().select(findByCode(tree.getRoot(), "1.1")));
		selectText(robot, pageText, page, "First descriptor");
		robot.interact(addDescriptor::fire);
		selectText(robot, pageText, page, "Second descriptor");
		robot.interact(addDescriptor::fire);
		CurriculumDraftNode topic = draft.findNode(2).orElseThrow();
		assertEquals(2, draft.childrenOf(topic.draftId()).size());
		assertEquals("1.1.1", draft.childrenOf(topic.draftId()).get(0).code());
		assertEquals("1.1.2", draft.childrenOf(topic.draftId()).get(1).code());
		assertEquals("First descriptor", draft.childrenOf(topic.draftId()).get(0).name());
		assertEquals("Second descriptor", draft.childrenOf(topic.draftId()).get(1).name());
	}

	@Test
	void preservesCollapsedTreeStateWhenDraftIsRebuilt(FxRobot robot) throws Exception {
		String page = "Unit text First topic Second topic";
		Path syllabus = createTextPdf("collapsed-tree.pdf", page);
		robot.interact(() -> pane.openSyllabusPdf(syllabus));
		TextArea pageText = robot.lookup("#syllabus-page-text").queryAs(TextArea.class);
		Button addUnit = robot.lookup("#add-curriculum-unit").queryAs(Button.class);
		Button addTopic = robot.lookup("#add-curriculum-topic").queryAs(Button.class);
		@SuppressWarnings("unchecked")
		TreeView<CurriculumDraftNode> tree = robot.lookup("#curriculum-draft-tree").queryAs(TreeView.class);
		selectText(robot, pageText, page, "Unit text");
		robot.interact(addUnit::fire);
		selectText(robot, pageText, page, "First topic");
		robot.interact(addTopic::fire);
		TreeItem<CurriculumDraftNode> unitItem = findByCode(tree.getRoot(), "1");
		robot.interact(() -> {
			unitItem.setExpanded(false);
			tree.getSelectionModel().select(unitItem);
		});
		selectText(robot, pageText, page, "Second topic");
		robot.interact(addTopic::fire);
		TreeItem<CurriculumDraftNode> rebuiltUnit = findByCode(tree.getRoot(), "1");
		assertEquals(false, rebuiltUnit.isExpanded());
		assertEquals(2, rebuiltUnit.getChildren().size());
	}

	@Start
	void start(Stage stage) {
		draft = new CurriculumDraft();
		pane = new CurriculumAuthoringPane(stage, tempDir, draft);
		stage.setScene(new Scene(pane, 1200, 760));
		stage.show();
	}

	@Test
	void supportsOptionalSubtopicCapture(FxRobot robot) throws Exception {
		String page = "Unit Topic Subtopic Descriptor";
		Path syllabus = createTextPdf("subtopic.pdf", page);
		robot.interact(() -> pane.openSyllabusPdf(syllabus));
		TextArea pageText = robot.lookup("#syllabus-page-text").queryAs(TextArea.class);
		Button addUnit = robot.lookup("#add-curriculum-unit").queryAs(Button.class);
		Button addTopic = robot.lookup("#add-curriculum-topic").queryAs(Button.class);
		Button addSubtopic = robot.lookup("#add-curriculum-subtopic").queryAs(Button.class);
		Button addDescriptor = robot.lookup("#add-curriculum-descriptor").queryAs(Button.class);
		selectText(robot, pageText, page, "Unit");
		robot.interact(addUnit::fire);
		selectText(robot, pageText, page, "Topic");
		robot.interact(addTopic::fire);
		@SuppressWarnings("unchecked")
		TreeView<CurriculumDraftNode> tree = robot.lookup("#curriculum-draft-tree").queryAs(TreeView.class);
		robot.interact(() -> tree.getSelectionModel().select(findByCode(tree.getRoot(), "1.1")));
		selectText(robot, pageText, page, "Subtopic");
		robot.interact(addSubtopic::fire);
		robot.interact(() -> tree.getSelectionModel().select(findByCode(tree.getRoot(), "1.1.1")));
		selectText(robot, pageText, page, "Descriptor");
		robot.interact(addDescriptor::fire);
		assertEquals("1.1.1.1", draft.nodes().get(3).code());
		assertEquals(CurriculumLevel.DESCRIPTOR, draft.nodes().get(3).level());
	}

	private Path createTextPdf(String name, String... pageTexts) throws Exception {
		Path path = tempDir.resolve(name);
		try (PDDocument document = new PDDocument()) {
			for (String text : pageTexts) {
				PDPage page = new PDPage(new PDRectangle(500, 300));
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

	private TreeItem<CurriculumDraftNode> findByCode(TreeItem<CurriculumDraftNode> item, String code) {
		CurriculumDraftNode value = item.getValue();
		if (value != null && value.code().equals(code)) {
			return item;
		}
		for (TreeItem<CurriculumDraftNode> child : item.getChildren()) {
			TreeItem<CurriculumDraftNode> found = findByCode(child, code);
			if (found != null) {
				return found;
			}
		}
		throw new AssertionError("Tree node not found: " + code);
	}

	private void selectText(FxRobot robot, TextArea textArea, String completeText, String selectedText) {
		int start = completeText.indexOf(selectedText);
		assertTrue(start >= 0, "Test text not found: " + selectedText);
		robot.interact(() -> textArea.selectRange(start, start + selectedText.length()));
	}
}