package au.edu.eq.questionbank.ui.curriculum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Descriptor;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.VBox;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;

@Tag("ui")
@ExtendWith(ApplicationExtension.class)
class DescriptorComboBoxSupportTest {
	private ComboBox<CurriculumNode> box;
	private Stage stage;

	@Start
	void start(Stage stage) {
		this.stage = stage;
		box = new ComboBox<>();
		DescriptorComboBoxSupport.configure(box);
		Subject subject = new Subject(1, "Chemistry");
		SyllabusVersion version = new SyllabusVersion(1, subject, "2025", true);
		Unit unit = new Unit(1, version, "1", "Unit", 1);
		Topic topic = new Topic(2, version, unit, "1.1", "Topic", 1);
		for (int i = 0; i < 35; i++) {
			box.getItems().add(new Descriptor(i + 3, version, topic, "1.1." + i,
					("Explain and compare the experimental evidence for this scientific model. ").repeat(8), i));
		}
		stage.setScene(new Scene(new VBox(box), 720, 500));
		stage.show();
	}

	@Test
	@SuppressWarnings("unchecked")
	void popupWrapsLongRowsAtNormalAndReducedWidthsWithoutResizingParent(FxRobot robot) {
		for (double width : new double[] {720, 360}) {
			robot.interact(() -> stage.setWidth(width));
			WaitForAsyncUtils.waitForFxEvents();
			robot.interact(box::show);
			WaitForAsyncUtils.waitForFxEvents();
			robot.interact(() -> {
				ListView<CurriculumNode> list = (ListView<CurriculumNode>)
						((DescriptorComboBoxSkin) box.getSkin()).getPopupContent();
				assertTrue(list.getWidth() <= Math.min(600, stage.getScene().getWidth() - 24) + 1,
						"Popup width " + list.getWidth() + " for scene " + stage.getScene().getWidth());
				ListCell<?> cell = list.lookupAll(".list-cell").stream().filter(ListCell.class::isInstance)
						.map(ListCell.class::cast).filter(candidate -> candidate.getItem() != null && candidate.isVisible())
						.findFirst().orElseThrow();
				assertTrue(cell.isWrapText());
				assertTrue(cell.getHeight() > 60, "Long text must produce a multi-line row");
				assertTrue(cell.getHeight() >= cell.prefHeight(cell.getWidth()) - 1);
				assertEquals(cell.getItem().toString(), cell.getText());
				box.getSelectionModel().select(30);
				list.scrollTo(30);
				box.hide();
				assertEquals(box.getItems().get(30), box.getValue());
				assertTrue(box.getTooltip().getText().contains(box.getValue().toString()));
				assertEquals(width, stage.getWidth(), 1);
				assertTrue(box.getHeight() < 50);
			});
		}
	}

	@Test
	void arrowNavigationAndReselectionKeepNativeComboBoxBehaviour(FxRobot robot) {
		robot.interact(() -> {
			box.getSelectionModel().selectFirst();
			box.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.DOWN, false, false, false, false));
			assertEquals(box.getItems().get(1), box.getValue());
			box.getSelectionModel().selectFirst();
			assertEquals(box.getItems().getFirst(), box.getValue());
		});
	}
}
