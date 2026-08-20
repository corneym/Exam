package au.edu.eq.questionbank.importer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.repository.CurriculumRepository;
import au.edu.eq.questionbank.repository.InMemoryCurriculumRepository;

public class CurriculumRepositoryLoader {

	private final CurriculumExcelImporter importer;
	private final CurriculumNodeBuilder nodeBuilder;

	public CurriculumRepositoryLoader() {
		this(new CurriculumExcelImporter(), new CurriculumNodeBuilder());
	}

	CurriculumRepositoryLoader(CurriculumExcelImporter importer, CurriculumNodeBuilder nodeBuilder) {

		this.importer = importer;
		this.nodeBuilder = nodeBuilder;
	}

	public CurriculumRepository load(List<CurriculumSource> sources) throws IOException {

		if (sources == null || sources.isEmpty()) {
			throw new IllegalArgumentException("sources must not be empty");
		}

		Set<Subject> subjects = new LinkedHashSet<>();
		List<SyllabusVersion> versions = new ArrayList<>();
		List<CurriculumNode> nodes = new ArrayList<>();

		AtomicLong ids = new AtomicLong(1);

		for (CurriculumSource source : sources) {
			SyllabusVersion version = source.syllabusVersion();

			subjects.add(version.getSubject());
			versions.add(version);

			List<CurriculumImportRow> rows = new ArrayList<>();

			for (var workbook : source.workbooks()) {
				rows.addAll(importer.read(workbook));
			}

			nodes.addAll(nodeBuilder.build(version, rows, ids::getAndIncrement));
		}

		return new InMemoryCurriculumRepository(List.copyOf(subjects), List.copyOf(versions), List.copyOf(nodes));
	}
}