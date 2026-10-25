package au.edu.eq.questionbank.diagnostics;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/** Ensures the packaged launcher records its installed runtime identity. */
class PerformanceInstalledLauncherTest {

	@Test
	void appImageSetsInstalledRuntimeWithoutEnablingDiagnostics() throws Exception {
		String script = Files.readString(Path.of("scripts", "package-windows-app-image.ps1"));
		assertTrue(script.contains("--java-options \"--enable-native-access=ALL-UNNAMED\""));
		assertTrue(script.contains("--java-options \"-Deqb.performance.runtimeKind=installed\""));
		assertTrue(!script.contains("-Deqb.performance.enabled=true"));
		assertTrue(!script.contains("-Deqb.performance.rawRetention.enabled=true"));
	}

	@Test
	void installerPackagesSamePreconfiguredAppImage() throws Exception {
		String script = Files.readString(Path.of("scripts", "package-windows-installer.ps1"));
		assertTrue(script.contains("--app-image $appImageDirectory"));
		assertTrue(script.contains("& $appImageScript"));
	}
}
