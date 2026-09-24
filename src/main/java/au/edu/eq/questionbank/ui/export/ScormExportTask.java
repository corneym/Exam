package au.edu.eq.questionbank.ui.export;

import au.edu.eq.questionbank.output.scorm.ScormExportRequest;
import au.edu.eq.questionbank.output.scorm.ScormExportResult;
import au.edu.eq.questionbank.output.scorm.ScormExportService;
import javafx.concurrent.Task;

/**
 * JavaFX background task that exposes SCORM-export progress and messages.
 */
public final class ScormExportTask extends Task<ScormExportResult> {

	private final ScormExportService exportService;
	private final ScormExportRequest request;

	/**
	 * Creates an export task.
	 *
	 * @param exportService service that performs the export
	 * @param request       validated export configuration
	 */
	public ScormExportTask(ScormExportService exportService, ScormExportRequest request) {
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
	protected ScormExportResult call() throws Exception {
		updateMessage("Starting SCORM export...");
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
