package au.edu.eq.questionbank.ui.curriculum;

import au.edu.eq.questionbank.model.CurriculumNode;
import javafx.geometry.Bounds;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListView;
import javafx.scene.control.skin.ComboBoxListViewSkin;
import javafx.stage.Screen;

/** Sizes the popup before showing it, without resizing the enclosing window. */
final class DescriptorComboBoxSkin extends ComboBoxListViewSkin<CurriculumNode> {
	private final ComboBox<CurriculumNode> box;
	private final ListView<CurriculumNode> list;

	@SuppressWarnings("unchecked")
	DescriptorComboBoxSkin(ComboBox<CurriculumNode> box) {
		super(box);
		this.box = box;
		list = (ListView<CurriculumNode>) getPopupContent();
		list.setFixedCellSize(-1);
		box.setCellFactory(_ -> new WrappingDescriptorCell(list));
	}

	@Override
	public void show() {
		Bounds bounds = box.localToScreen(box.getBoundsInLocal());
		Screen screen = bounds == null ? Screen.getPrimary()
				: Screen.getScreensForRectangle(bounds.getMinX(), bounds.getMinY(), bounds.getWidth(), bounds.getHeight())
						.stream().findFirst().orElse(Screen.getPrimary());
		double windowWidth = box.getScene() == null ? 600 : box.getScene().getWidth();
		double width = Math.max(1, Math.min(600, Math.min(windowWidth - 24, screen.getVisualBounds().getWidth() - 24)));
		list.setMinWidth(width);
		list.setPrefWidth(width);
		list.setMaxWidth(width);
		super.show();
	}
}
