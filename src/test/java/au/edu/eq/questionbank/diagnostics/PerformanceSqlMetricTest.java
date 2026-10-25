package au.edu.eq.questionbank.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class PerformanceSqlMetricTest {

	@Test
	void countsAndDurationsHaveExplicitUnits() {
		assertEquals("count", PerformanceSqlMetric.unit("question.save.persist.sql.connections"));
		assertEquals("count", PerformanceSqlMetric.unit("question.save.persist.sql.execute-invocations"));
		assertEquals("count", PerformanceSqlMetric.unit("question.save.persist.sql.rows-read"));
		assertEquals("count", PerformanceSqlMetric.unit("question.save.persist.sql.questions-reconstructed"));
		assertEquals("us", PerformanceSqlMetric.unit("question.save.persist.sql.connection-open-us"));
		assertEquals("us", PerformanceSqlMetric.unit("question.save.persist.sql.execution-us"));
		assertEquals("us", PerformanceSqlMetric.unit("question.save.persist.sql.row-next-us"));
	}

	@Test
	void elapsedSqlScopeIsNotMisclassifiedAsCounter() {
		assertNull(PerformanceSqlMetric.unit("question.save.persist.sql.scope"));
		assertNull(PerformanceSqlMetric.unit("dashboard.refresh"));
		assertNull(PerformanceSqlMetric.unit(null));
	}
}
