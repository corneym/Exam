package au.edu.eq.questionbank;

import org.junit.platform.suite.api.ExcludeTags;
import org.junit.platform.suite.api.IncludeClassNamePatterns;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;

/**
 * Runs non-UI curriculum repository tests.
 */
@Suite
@SelectPackages("au.edu.eq.questionbank.repository.curriculum")
@IncludeClassNamePatterns(".*Test")
@ExcludeTags("ui")
public class NonUIRepositoryCurriculumTests {
}
