package au.edu.eq.questionbank;

import org.junit.platform.suite.api.ExcludeTags;
import org.junit.platform.suite.api.IncludeClassNamePatterns;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;

/**
 * Runs non-UI work-package and contribution tests.
 */
@Suite
@SelectPackages("au.edu.eq.questionbank")
@IncludeClassNamePatterns("au\\.edu\\.eq\\.questionbank\\.service\\.workpackage\\..*Test")
@ExcludeTags("ui")
public class NonUIWorkPackageTests {
}
