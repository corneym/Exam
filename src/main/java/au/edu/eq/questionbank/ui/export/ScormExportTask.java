package au.edu.eq.questionbank.ui.export;

import java.nio.file.Path;
import java.util.Objects;

import au.edu.eq.questionbank.diagnostics.PerformanceOperation;
import au.edu.eq.questionbank.diagnostics.PerformanceRecorder;
import au.edu.eq.questionbank.output.scorm.ScormExportRequest;
import au.edu.eq.questionbank.output.scorm.ScormExportResult;
import au.edu.eq.questionbank.output.scorm.ScormExportService;
import javafx.concurrent.Task;

/**
 * JavaFX background task that exposes SCORM-export progress and messages.
 */
public final class ScormExportTask extends Task<ScormExportResult> {

	private static final PerformanceRecorder DISABLED_RECORDER = new PerformanceRecorder(false,
			Path.of("performance.csv"));
	private final ScormExportService exportService;
	private final ScormExportRequest request;
	private final long parentOperationId;
	private final PerformanceRecorder performanceRecorder;

	/**
	 * Creates a SCORM export task without explicit diagnostics.
	 *
	 * @param exportService service that performs the export
	 * @param request       validated export configuration
	 */
	public ScormExportTask(ScormExportService exportService, ScormExportRequest request) {
		this(exportService, request, DISABLED_RECORDER, 0L);
	}

	/**
	 * Creates a SCORM export task with correlated diagnostics.
	 *
	 * @param exportService       service that performs the export
	 * @param request             validated export configuration
	 * @param performanceRecorder application diagnostics recorder
	 * @param parentOperationId   enclosing export operation ID
	 */
	public ScormExportTask(ScormExportService exportService, ScormExportRequest request,
			PerformanceRecorder performanceRecorder, long parentOperationId) {
		this.exportService = Objects.requireNonNull(exportService, "exportService");
		this.request = Objects.requireNonNull(request, "request");
		this.performanceRecorder = Objects.requireNonNull(performanceRecorder, "performanceRecorder");
		this.parentOperationId = parentOperationId;
	}

	@Override
	protected ScormExportResult call() throws Exception {
		try (PerformanceOperation operation = performanceRecorder.start("revision.scorm.export.work",
				parentOperationId)) {
			try {
				updateMessage("Starting SCORM export...");
				updateProgress(-1, 1);
				ScormExportResult result = exportService.export(request, (message, completed, total) -> {
					updateMessage(message);
					if (total > 0) {
						updateProgress(completed, total);
					} else {
						updateProgress(-1, 1);
					}
				});
				operation.resultCount(result.getStatistics().getRenderableQuestions());
				return result;
			} catch (Exception | Error failure) {
				operation.failed();
				throw failure;
			}
		}
	}
}
