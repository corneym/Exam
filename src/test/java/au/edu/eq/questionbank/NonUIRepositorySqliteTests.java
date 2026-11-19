package au.edu.eq.questionbank;

import org.junit.platform.suite.api.ExcludeTags;
import org.junit.platform.suite.api.IncludeClassNamePatterns;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;

/**
 * Runs non-UI SQLite infrastructure and schema tests.
 */
@Suite
@SelectPackages("au.edu.eq.questionbank.repository.sqlite")
@IncludeClassNamePatterns(".*Test")
@ExcludeTags("ui")
public class NonUIRepositorySqliteTests {
}
