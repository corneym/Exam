package au.edu.eq.questionbank.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;

import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

@Tag("ui")
@ExtendWith(ApplicationExtension.class)
class PerformanceFxStallProbeTest {

	@TempDir
	Path tempDir;

	@Test
	void recordsLongPulseGapsWithRateLimiting(FxRobot robot) throws Exception {
		Path csv = tempDir.resolve("performance.csv");
		try (PerformanceRecorder recorder = new PerformanceRecorder(true, csv)) {
			robot.interact(() -> {
				PerformanceFxStallProbe probe = new PerformanceFxStallProbe(recorder);
				probe.handle(1_000_000_000L);
				probe.handle(1_150_000_000L);
				probe.handle(1_200_000_000L);
				probe.handle(1_450_000_000L);
				probe.handle(6_500_000_000L);
			});
		}
		List<String> measurements = Files.readAllLines(csv).stream()
				.filter(line -> line.contains(",runtime.fx.pulse-gap-us,")).toList();
		assertEquals(2, measurements.size());

		// First observed gap is 150 ms, expressed in microseconds.
		assertTrue(measurements.get(0).endsWith(",150000"));

		// The second recorded gap follows the reporting cooldown.
		assertTrue(measurements.get(1).endsWith(",5050000"));
	}

	@Start
	void start(Stage stage) {
		stage.setScene(new Scene(new StackPane(), 200, 150));
		stage.show();
	}
}
