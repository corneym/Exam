package au.edu.eq.questionbank.importer.legacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class LegacyExamIdentityResolverTest {

	private final LegacyExamIdentityResolver resolver = new LegacyExamIdentityResolver();

	@Test
	void doesNotInventUnknownExaminationIdentity() {
		LegacyExamIdentityProposal result = resolver.propose("Other Provider Trial", 2020);
		assertEquals("Other Provider Trial", result.worksheetName());
		assertEquals("Other Provider Trial", result.providerName());
		assertEquals("", result.examName());
		assertFalse(result.recognised());
		assertFalse(result.hasCompleteIdentity());
	}

	@Test
	void rejectsInvalidWorksheetAndYear() {
		assertThrows(IllegalArgumentException.class, () -> resolver.propose("", 2020));
		assertThrows(IllegalArgumentException.class, () -> resolver.propose("QCAA", 0));
	}

	@Test
	void resolvesActualQCAAExamination() {
		LegacyExamIdentityProposal result = resolver.propose("QCAA", 2020);
		assertEquals("QCAA", result.providerName());
		assertEquals(2020, result.year());
		assertEquals("External Assessment", result.examName());
		assertTrue(result.recognised());
		assertTrue(result.hasCompleteIdentity());
	}

	@Test
	void resolvesMockQCAAExamination() {
		LegacyExamIdentityProposal result = resolver.propose("QCAA Mock", 2020);
		assertEquals("QCAA", result.providerName());
		assertEquals("Mock Examination", result.examName());
		assertTrue(result.recognised());
	}

	@Test
	void resolvesSampleQCAAExamination() {
		LegacyExamIdentityProposal result = resolver.propose("QCAA Sample", 2020);
		assertEquals("QCAA", result.providerName());
		assertEquals("Sample Examination", result.examName());
		assertTrue(result.recognised());
	}

	@Test
	void separatesExaminationsByYear() {
		LegacyQuestionRow first = new LegacyQuestionRow(2020, "MCQ", "1", 1, "4.1.2", "C", false);
		LegacyQuestionRow second = new LegacyQuestionRow(2020, "MCQ", "2", 1, "4.1.2", "A", false);
		LegacyQuestionRow third = new LegacyQuestionRow(2021, "MCQ", "1", 1, "4.1.2", "B", false);
		LegacyQuestionSheet sheet = new LegacyQuestionSheet("QCAA Mock", List.of(first, second, third));
		List<LegacyExamIdentityProposal> proposals = resolver.propose(List.of(sheet));
		assertEquals(2, proposals.size());
		assertEquals(2020, proposals.get(0).year());
		assertEquals(2021, proposals.get(1).year());
		assertEquals("Mock Examination", proposals.get(0).examName());
		assertEquals("Mock Examination", proposals.get(1).examName());
	}

	@Test
	void worksheetNamesAreCaseAndWhitespaceTolerant() {
		LegacyExamIdentityProposal result = resolver.propose("  qcaa   mock  ", 2020);
		assertEquals("QCAA", result.providerName());
		assertEquals("Mock Examination", result.examName());
		assertEquals("qcaa   mock", result.worksheetName());
	}
}
