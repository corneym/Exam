package au.edu.eq.questionbank;

import org.junit.platform.suite.api.ExcludeTags;
import org.junit.platform.suite.api.IncludeClassNamePatterns;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;

/**
 * Runs non-UI application, administration, diagnostics, model and UI-helper
 * tests.
 */
@Suite
@SelectPackages("au.edu.eq.questionbank")
@IncludeClassNamePatterns("au\\.edu\\.eq\\.questionbank\\.(?:[^.]+Test|(?:admin|diagnostics|model|ui)\\..*Test)")
@ExcludeTags("ui")
public class NonUICoreTests {
}
