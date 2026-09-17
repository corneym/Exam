package au.edu.eq.questionbank.model;

import java.math.BigInteger;
import java.util.Comparator;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Defines deterministic source order for examination questions.
 * <p>
 * Questions are ordered by provider, examination year, booklet and natural
 * question code. Persistent identifiers are used only as a final deterministic
 * tie-break when all source-order fields are otherwise identical.
 */
public final class QuestionSourceOrder {

	private static final Pattern SUPPORTED_QUESTION_CODE = Pattern.compile("^([A-Za-z]*)(\\d+)([A-Za-z]?)$");
	private static final Comparator<Question> COMPARATOR = QuestionSourceOrder::compareQuestions;

	private QuestionSourceOrder() {
	}

	/**
	 * Returns the reusable comparator for source-order presentation of Questions.
	 *
	 * @return comparator ordered by provider, year, booklet and natural Question
	 *         code
	 */
	public static Comparator<Question> comparator() {
		return COMPARATOR;
	}

	/**
	 * Compares the supported Question-code forms naturally.
	 * <p>
	 * Numeric Question numbers compare numerically, so {@code 10} follows
	 * {@code 3}. A single alphabetic part follows its base Question and parts sort
	 * alphabetically, giving {@code 3}, {@code 3a}, {@code 3b}, {@code 10}.
	 * Existing alphabetic prefixes such as {@code Q3} are also retained as part of
	 * the comparison.
	 *
	 * @param leftCode  first non-blank Question code
	 * @param rightCode second non-blank Question code
	 * @return negative, zero or positive according to natural Question-code order
	 */
	static int compareQuestionCodes(String leftCode, String rightCode) {
		String left = Objects.requireNonNull(leftCode, "leftCode").trim();
		String right = Objects.requireNonNull(rightCode, "rightCode").trim();
		Matcher leftMatcher = SUPPORTED_QUESTION_CODE.matcher(left);
		Matcher rightMatcher = SUPPORTED_QUESTION_CODE.matcher(right);
		if (leftMatcher.matches() && rightMatcher.matches()) {

			// Compare any existing alphabetic prefix before the numeric Question number.
			int prefixComparison = compareText(leftMatcher.group(1), rightMatcher.group(1));
			if (prefixComparison != 0) {
				return prefixComparison;
			}
			/*
			 * Compare the numeric portion as a number rather than as text so that Question
			 * 10 sorts after Question 3 rather than between 1 and 2.
			 */
			BigInteger leftNumber = new BigInteger(leftMatcher.group(2));
			BigInteger rightNumber = new BigInteger(rightMatcher.group(2));
			int numberComparison = leftNumber.compareTo(rightNumber);
			if (numberComparison != 0) {
				return numberComparison;
			}
			/*
			 * The empty part sorts before alphabetic parts, producing the required
			 * base/part sequence: 3, 3a, 3b.
			 */
			int partComparison = compareText(leftMatcher.group(3), rightMatcher.group(3));
			if (partComparison != 0) {
				return partComparison;
			}
			return left.compareTo(right);
		}
		/*
		 * Unsupported legacy forms are not interpreted. A deterministic textual
		 * fallback keeps them sortable without silently inventing additional syntax.
		 */
		return compareText(left, right);
	}

	private static int compareQuestions(Question left, Question right) {
		Objects.requireNonNull(left, "left");
		Objects.requireNonNull(right, "right");

		// Provider is the first source-level grouping required by the capture workflow.
		int providerComparison = compareText(left.getExam().getProvider().getName(),
				right.getExam().getProvider().getName());
		if (providerComparison != 0) {
			return providerComparison;
		}

		// Within a provider, examinations follow chronological source order.
		int yearComparison = Integer.compare(left.getExam().getYear(), right.getExam().getYear());
		if (yearComparison != 0) {
			return yearComparison;
		}

		// Booklets belonging to the same provider/year are grouped by their stored
		// name.
		int bookletComparison = compareText(left.getBooklet().getName(), right.getBooklet().getName());
		if (bookletComparison != 0) {
			return bookletComparison;
		}

		// Question codes use numeric-aware ordering with optional alphabetic parts.
		int questionComparison = compareQuestionCodes(left.getQuestionCode(), right.getQuestionCode());
		if (questionComparison != 0) {
			return questionComparison;
		}
		/*
		 * Database identity does not define ordinary source order. It is consulted only
		 * when two Questions have identical source-order keys so comparison remains
		 * deterministic.
		 */
		return Long.compare(left.getId(), right.getId());
	}

	private static int compareText(String left, String right) {

		// Compare case-insensitively first so presentation capitalisation does not
		// alter grouping.
		int comparison = left.toLowerCase(Locale.ROOT).compareTo(right.toLowerCase(Locale.ROOT));
		if (comparison != 0) {
			return comparison;
		}

		// Preserve deterministic ordering if two labels differ only by capitalisation.
		return left.compareTo(right);
	}
}
