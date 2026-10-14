package au.edu.eq.questionbank.ui.export;

import java.nio.file.Path;
import java.util.Objects;

import au.edu.eq.questionbank.diagnostics.PerformanceOperation;
import au.edu.eq.questionbank.diagnostics.PerformanceRecorder;
import au.edu.eq.questionbank.output.revision.RevisionExportRequest;
import au.edu.eq.questionbank.output.revision.RevisionExportResult;
import au.edu.eq.questionbank.output.revision.RevisionExportService;
import javafx.concurrent.Task;

/**
 * JavaFX background task that exposes revision-export progress and messages.
 */
public final class RevisionExportTask extends Task<RevisionExportResult> {

	private static final PerformanceRecorder DISABLED_RECORDER = new PerformanceRecorder(false,
			Path.of("performance.csv"));

	// Export request and execution collaborators.
	private final RevisionExportService exportService;
	private final long parentOperationId;
	private final PerformanceRecorder performanceRecorder;
	private final RevisionExportRequest request;

	/**
	 * Creates an export task without explicit performance diagnostics.
	 *
	 * @param exportService service that performs the export
	 * @param request       validated export configuration
	 */
	public RevisionExportTask(RevisionExportService exportService, RevisionExportRequest request) {
		this(exportService, request, DISABLED_RECORDER, 0L);
	}

	/**
	 * Creates an export task with a correlated performance operation.
	 *
	 * @param exportService       service that performs the export
	 * @param request             validated export configuration
	 * @param performanceRecorder application diagnostics recorder
	 * @param parentOperationId   enclosing export operation ID
	 */
	public RevisionExportTask(RevisionExportService exportService, RevisionExportRequest request,
			PerformanceRecorder performanceRecorder, long parentOperationId) {
		this.exportService = Objects.requireNonNull(exportService, "exportService");
		this.request = Objects.requireNonNull(request, "request");
		this.performanceRecorder = Objects.requireNonNull(performanceRecorder, "performanceRecorder");
		this.parentOperationId = parentOperationId;
	}

	@Override
	protected RevisionExportResult call() throws Exception {
		try (PerformanceOperation operation = performanceRecorder.start("revision.html.export.work",
				parentOperationId)) {
			try {
				updateMessage("Starting export...");
				updateProgress(-1, 1);
				RevisionExportResult result = exportService.export(request, (message, completed, total) -> {
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
