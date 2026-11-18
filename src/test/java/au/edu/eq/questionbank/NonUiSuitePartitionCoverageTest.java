package au.edu.eq.questionbank;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

class NonUiSuitePartitionCoverageTest {

	@Test
	void everyNonUiTestIsAssignedExactlyOnce() {

		// Discover the actual JUnit class and method inventory
		// without executing the underlying tests.
		Function<Class<?>, Set<String>> discover = suite -> {
			TestPlan plan = LauncherFactory.create().discover(
					LauncherDiscoveryRequestBuilder.request().selectors(DiscoverySelectors.selectClass(suite)).build());
			Set<String> identifiers = new TreeSet<>();
			for (TestIdentifier root : plan.getRoots()) {
				for (TestIdentifier identifier : plan.getDescendants(root)) {
					TestSource source = identifier.getSource().orElse(null);
					if (source instanceof ClassSource classSource) {
						if (!classSource.getClassName().equals(suite.getName())) {
							identifiers.add("class:" + classSource.getClassName());
						}
					} else if (source instanceof MethodSource methodSource) {
						identifiers.add("method:" + methodSource.getClassName() + "#" + methodSource.getMethodName()
								+ "(" + methodSource.getMethodParameterTypes() + ")");
					}
				}
			}
			return identifiers;
		};
		Set<String> expected = discover.apply(NonUITests.class);
		Class<?>[] batches = { NonUIRepositoryTests.class, NonUIWorkPackageTests.class, NonUIServiceTests.class,
				NonUICoreTests.class, NonUIImportOutputTests.class };
		Map<String, List<String>> assignments = new TreeMap<>();
		for (Class<?> batch : batches) {
			Set<String> discovered = discover.apply(batch);
			assertFalse(discovered.isEmpty(), () -> "No tests discovered for " + batch.getSimpleName());
			System.out.printf("%s: %d discovered identifiers%n", batch.getSimpleName(), discovered.size());
			for (String identifier : discovered) {
				assignments.computeIfAbsent(identifier, ignored -> new ArrayList<>()).add(batch.getSimpleName());
			}
		}
		Set<String> missing = new TreeSet<>(expected);
		missing.removeAll(assignments.keySet());
		Set<String> unexpected = new TreeSet<>(assignments.keySet());
		unexpected.removeAll(expected);
		Set<String> duplicated = new TreeSet<>();
		for (Map.Entry<String, List<String>> entry : assignments.entrySet()) {
			if (entry.getValue().size() > 1) {
				duplicated.add(entry.getKey() + " -> " + entry.getValue());
			}
		}
		assertAll(() -> assertFalse(expected.isEmpty(), "The original NonUITests suite discovered no tests"),
				() -> assertTrue(missing.isEmpty(), () -> "Unassigned tests: " + missing),
				() -> assertTrue(unexpected.isEmpty(), () -> "Unexpected tests: " + unexpected),
				() -> assertTrue(duplicated.isEmpty(), () -> "Multiply assigned tests: " + duplicated));
	}
}
