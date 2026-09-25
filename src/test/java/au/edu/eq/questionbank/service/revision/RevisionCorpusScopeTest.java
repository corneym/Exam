package au.edu.eq.questionbank.service.revision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import au.edu.eq.questionbank.model.Descriptor;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;

class RevisionCorpusScopeTest {

	@Test
	void findsOnlyUnitsContainingRenderableQuestions() {
		Fixture fixture = new Fixture();
		List<Unit> units = new RevisionCorpusScope().findNonEmptyUnits(fixture.corpus);
		assertEquals(List.of(fixture.firstUnit, fixture.secondUnit), units);
	}

	@Test
	void rejectsUnitOutsideCorpus() {
		Fixture fixture = new Fixture();
		assertThrows(IllegalArgumentException.class,
				() -> new RevisionCorpusScope().selectUnits(fixture.corpus, Set.of(999L)));
	}

	@Test
	void selectedUnitScopeRestrictsRootsAndStatistics() {
		Fixture fixture = new Fixture();
		RevisionCorpus scoped = new RevisionCorpusScope().selectUnits(fixture.corpus,
				Set.of(fixture.secondUnit.getId()));
		assertEquals(1, scoped.getRootNodes().size());
		assertEquals(fixture.secondUnit, scoped.getRootNodes().getFirst().getCurriculumNode());
		RevisionCorpusStatistics statistics = scoped.getStatistics();
		assertEquals(1, statistics.getApplicablePlacements());
		assertEquals(1, statistics.getUniqueApplicableQuestions());
		assertEquals(1, statistics.getRenderableQuestions());
		assertEquals(0, statistics.getMissingQuestionRegionQuestions());
		assertEquals(0, statistics.getQuestionsWithAnswers());
		assertEquals(1, statistics.getQuestionsWithoutAnswers());
		assertEquals(0, statistics.getSharedContextReviewQuestions());
	}

	private static final class Fixture {

		private final Subject chemistry;
		private final SyllabusVersion currentVersion;
		private final Unit firstUnit;
		private final Unit secondUnit;
		private final Unit metadataOnlyUnit;
		private final RevisionCorpus corpus;

		private Fixture() {
			chemistry = new Subject(1, "Chemistry");
			currentVersion = new SyllabusVersion(1, chemistry, "2025", true);
			firstUnit = new Unit(10, currentVersion, "1", "Unit 1", 1);
			secondUnit = new Unit(20, currentVersion, "2", "Unit 2", 2);
			metadataOnlyUnit = new Unit(30, currentVersion, "3", "Unit 3", 3);
			Topic firstTopic = new Topic(11, currentVersion, firstUnit, "1.1", "Topic 1", 1);
			Topic secondTopic = new Topic(21, currentVersion, secondUnit, "2.1", "Topic 2", 1);
			Topic metadataTopic = new Topic(31, currentVersion, metadataOnlyUnit, "3.1", "Topic 3", 1);
			Descriptor firstDescriptor = new Descriptor(12, currentVersion, firstTopic, "1.1.1", "Descriptor 1", 1);
			Descriptor secondDescriptor = new Descriptor(22, currentVersion, secondTopic, "2.1.1", "Descriptor 2", 1);
			Descriptor metadataDescriptor = new Descriptor(32, currentVersion, metadataTopic, "3.1.1", "Descriptor 3",
					1);
			ExamProvider provider = new ExamProvider(1, "QCAA");
			Exam exam = new Exam(1, chemistry, provider, 2025, "Chemistry examination");
			SourceDocument source = new SourceDocument(1, "questions.pdf");
			ExamBooklet booklet = new ExamBooklet(1, exam, "Paper 1", source);
			QuestionRegion region = new QuestionRegion(booklet, 1, 0.0, 0.0, 1.0, 1.0);
			Question firstQuestion = new Question(1, booklet, "1", "", 1, List.of(region), firstDescriptor, false);
			Question secondQuestion = new Question(2, booklet, "2", "", 1, List.of(region), secondDescriptor, false);
			Question metadataOnlyQuestion = new Question(3, booklet, "3", "", 1, List.of(), metadataDescriptor, false);
			RevisionCorpusNode firstDescriptorNode = new RevisionCorpusNode(firstDescriptor, List.of(),
					List.of(new RevisionQuestionPlacement(firstQuestion, firstDescriptor, 1)));
			RevisionCorpusNode secondDescriptorNode = new RevisionCorpusNode(secondDescriptor, List.of(),
					List.of(new RevisionQuestionPlacement(secondQuestion, secondDescriptor, 2)));
			RevisionCorpusNode metadataDescriptorNode = new RevisionCorpusNode(metadataDescriptor, List.of(),
					List.of(new RevisionQuestionPlacement(metadataOnlyQuestion, metadataDescriptor, 0)));
			RevisionCorpusNode firstTopicNode = new RevisionCorpusNode(firstTopic, List.of(firstDescriptorNode),
					List.of());
			RevisionCorpusNode secondTopicNode = new RevisionCorpusNode(secondTopic, List.of(secondDescriptorNode),
					List.of());
			RevisionCorpusNode metadataTopicNode = new RevisionCorpusNode(metadataTopic,
					List.of(metadataDescriptorNode), List.of());
			RevisionCorpusNode firstUnitNode = new RevisionCorpusNode(firstUnit, List.of(firstTopicNode), List.of());
			RevisionCorpusNode secondUnitNode = new RevisionCorpusNode(secondUnit, List.of(secondTopicNode), List.of());
			RevisionCorpusNode metadataUnitNode = new RevisionCorpusNode(metadataOnlyUnit, List.of(metadataTopicNode),
					List.of());
			RevisionCorpusStatistics statistics = new RevisionCorpusStatistics(3, 3, 2, 1, 0, 3, 0);
			corpus = new RevisionCorpus(chemistry, currentVersion,
					List.of(firstUnitNode, secondUnitNode, metadataUnitNode), statistics);
		}
	}
}
