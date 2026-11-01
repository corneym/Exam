package au.edu.eq.questionbank.ui.curriculum;

import au.edu.eq.questionbank.model.CurriculumNode;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.OverrunStyle;

/** Variable-height popup row with space reserved for the vertical scrollbar. */
final class WrappingDescriptorCell extends ListCell<CurriculumNode> {
	WrappingDescriptorCell(ListView<CurriculumNode> list) {
		setWrapText(true);
		setTextOverrun(OverrunStyle.CLIP);
		setMinWidth(0);
		prefWidthProperty().bind(list.widthProperty().subtract(24));
		maxWidthProperty().bind(prefWidthProperty());
		setMinHeight(USE_PREF_SIZE);
	}

	@Override
	protected void updateItem(CurriculumNode item, boolean empty) {
		super.updateItem(item, empty);
		setText(empty || item == null ? null : item.toString());
		setGraphic(null);
	}
}
