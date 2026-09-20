package au.edu.eq.questionbank.model;

/**
 * Derives an original source-question code from a part-question code.
 * <p>
 * A code is treated as multipart only when its final character is a letter and
 * the immediately preceding character is a digit.
 *
 * Examples:
 *
 * <pre>
 * 21a  -> 21
 * Q24a -> Q24
 * 21   -> null
 * ABC  -> null
 * 21ab -> null
 * </pre>
 */
public final class SourceQuestionCodeParser {

	private SourceQuestionCodeParser() {
	}

	/**
	 * Derives the source-question code represented by a multipart question code.
	 *
	 * @param questionCode the question code, which may be {@code null}
	 * @return the derived source-question code, or {@code null} when the code does
	 *         not satisfy the conservative multipart rule
	 */
	public static String derive(String questionCode) {
		String code = questionCode == null ? "" : questionCode.trim();
		if (code.length() < 2) {
			return null;
		}

		// Remove at most one trailing letter; multi-letter suffixes are not inferred as
		// multipart codes.
		char part = code.charAt(code.length() - 1);
		if (!Character.isLetter(part)) {
			return null;
		}
		String sourceCode = code.substring(0, code.length() - 1);
		if (sourceCode.isBlank()) {
			return null;
		}

		// Require a digit immediately before the part, preserving any earlier prefix
		// such as Q24.
		char sourceLastCharacter = sourceCode.charAt(sourceCode.length() - 1);
		if (!Character.isDigit(sourceLastCharacter)) {
			return null;
		}
		return sourceCode;
	}
}
