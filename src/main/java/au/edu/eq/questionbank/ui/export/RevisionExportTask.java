package au.edu.eq.questionbank.ui.export;

import au.edu.eq.questionbank.output.revision.RevisionExportRequest;
import au.edu.eq.questionbank.output.revision.RevisionExportResult;
import au.edu.eq.questionbank.output.revision.RevisionExportService;
import javafx.concurrent.Task;

public final class RevisionExportTask extends Task<RevisionExportResult> {

	private final RevisionExportService exportService;
	private final RevisionExportRequest request;

	public RevisionExportTask(RevisionExportService exportService, RevisionExportRequest request) {
		if (exportService == null) {
			throw new NullPointerException("exportService");
		}
		if (request == null) {
			throw new NullPointerException("request");
		}
		this.exportService = exportService;
		this.request = request;
	}

	@Override
	protected RevisionExportResult call() throws Exception {
		updateMessage("Starting export...");
		updateProgress(-1, 1);
		return exportService.export(request, (message, completed, total) -> {
			updateMessage(message);

			// A zero total represents an indeterminate phase rather than zero progress.
			if (total > 0) {
				updateProgress(completed, total);
			} else {
				updateProgress(-1, 1);
			}
		});
	}
}
