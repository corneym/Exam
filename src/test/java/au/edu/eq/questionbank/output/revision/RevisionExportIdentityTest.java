package au.edu.eq.questionbank.output.revision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;

class RevisionExportIdentityTest {

	@Test
	void formatsDeterministicHumanAndTechnicalIdentities() {
		RevisionExportIdentity identity = new RevisionExportIdentity(ZonedDateTime.of(2026, 9, 24, 21, 18, 0,
				123_000_000, ZoneId.of("Australia/Brisbane")));

		assertEquals("24 Sep 2026", identity.getDisplayText());
		assertEquals("Chemistry Exam Question Corpus (24 Sep 2026)", identity.titleFor("Chemistry"));
		assertEquals("20260924T111800123Z", identity.getTechnicalToken());
		assertEquals("04 Oct 2026", new RevisionExportIdentity(ZonedDateTime.of(2026, 10, 4, 9, 0, 0, 0,
				ZoneId.of("Australia/Brisbane"))).getDisplayText());
	}

	@Test
	void preservesDistinctTechnicalIdentityForSameDayExports() {
		ZoneId zone = ZoneId.of("Australia/Brisbane");
		RevisionExportIdentity first = new RevisionExportIdentity(
				ZonedDateTime.of(2026, 9, 24, 21, 18, 0, 0, zone));
		RevisionExportIdentity second = new RevisionExportIdentity(
				ZonedDateTime.of(2026, 9, 24, 21, 18, 0, 1_000_000, zone));

		assertEquals(first.getDisplayText(), second.getDisplayText());
		assertNotEquals(first.getTechnicalToken(), second.getTechnicalToken());
	}
}
