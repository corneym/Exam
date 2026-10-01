package au.edu.eq.questionbank.ui.audit;

import java.util.function.Function;

import au.edu.eq.questionbank.service.audit.QuestionCorpusWorkItem;
import javafx.scene.control.ListCell;

/**
 * Displays one Question Corpus Audit work item.
 */
final class QuestionCorpusAuditWorkItemCell extends ListCell<QuestionCorpusWorkItem> {

	private final Function<QuestionCorpusWorkItem, String> formatter;

	QuestionCorpusAuditWorkItemCell(Function<QuestionCorpusWorkItem, String> formatter) {
		if (formatter == null) {
			throw new NullPointerException("formatter");
		}

		// Keep the cell responsible for JavaFX lifecycle while the pane retains the
		// Dashboard-specific wording used to describe one work item.
		this.formatter = formatter;
	}

	@Override
	protected void updateItem(QuestionCorpusWorkItem item, boolean empty) {
		super.updateItem(item, empty);

		if (empty || item == null) {

			// Recycled empty cells must not retain text from their previous item.
			setText(null);
			return;
		}

		// Delegate only the work-item wording; cell reuse remains encapsulated here.
		setText(formatter.apply(item));
	}
}
