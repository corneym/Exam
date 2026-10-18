package au.edu.eq.questionbank.importer.legacy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Generates proposed examination identities from legacy worksheet names and the
 * years occurring in their rows.
 *
 * <p>
 * These proposals require confirmation before import.
 */
public final class LegacyExamIdentityResolver {

	/**
	 * Creates a resolver for proposing legacy examination identities.
	 */
	public LegacyExamIdentityResolver() {
	}

	/**
	 * Produces one proposal per worksheet and examination year.
	 *
	 * <p>
	 * Multiple question rows from the same year do not produce duplicate
	 * examination proposals.
	 *
	 * @param sheets validated legacy worksheets
	 * @return proposed examination identities
	 */
	public List<LegacyExamIdentityProposal> propose(List<LegacyQuestionSheet> sheets) {
		if (sheets == null) {
			throw new NullPointerException("sheets");
		}
		List<LegacyExamIdentityProposal> proposals = new ArrayList<>();
		for (LegacyQuestionSheet sheet : sheets) {
			Set<Integer> years = new LinkedHashSet<>();
			for (LegacyQuestionRow question : sheet.questions()) {
				years.add(question.year());
			}
			for (int year : years) {
				proposals.add(propose(sheet.providerName(), year));
			}
		}
		proposals.sort(Comparator.comparing(LegacyExamIdentityProposal::worksheetName)
				.thenComparingInt(LegacyExamIdentityProposal::year));
		return List.copyOf(proposals);
	}

	/**
	 * Proposes an examination identity from a worksheet name.
	 *
	 * @param worksheetName legacy worksheet name
	 * @param year          examination year
	 * @return examination identity proposal
	 */
	public LegacyExamIdentityProposal propose(String worksheetName, int year) {
		if (worksheetName == null || worksheetName.isBlank()) {
			throw new IllegalArgumentException("Worksheet name must not be blank");
		}
		String name = worksheetName.trim();
		String normalised = name.toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
		return switch (normalised) {
		case "QCAA" -> new LegacyExamIdentityProposal(name, year, "QCAA", "External Assessment", true);
		case "QCAA MOCK" -> new LegacyExamIdentityProposal(name, year, "QCAA", "Mock Examination", true);
		case "QCAA SAMPLE" -> new LegacyExamIdentityProposal(name, year, "QCAA", "Sample Examination", true);
		default -> new LegacyExamIdentityProposal(name, year, name, "", false);
		};
	}
}
