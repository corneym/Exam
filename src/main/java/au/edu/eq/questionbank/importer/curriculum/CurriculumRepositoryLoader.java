package au.edu.eq.questionbank.importer.curriculum;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.repository.curriculum.CurriculumRepository;
import au.edu.eq.questionbank.repository.curriculum.InMemoryCurriculumRepository;

/**
 * Orchestrates workbook import and hierarchy construction into an in-memory
 * curriculum repository.
 */
public class CurriculumRepositoryLoader {

	private final CurriculumExcelImporter importer;
	private final CurriculumNodeBuilder nodeBuilder;

	/**
	 * Creates a loader using the standard workbook importer and node builder.
	 */
	public CurriculumRepositoryLoader() {
		this(new CurriculumExcelImporter(), new CurriculumNodeBuilder());
	}

	CurriculumRepositoryLoader(CurriculumExcelImporter importer, CurriculumNodeBuilder nodeBuilder) {

		this.importer = importer;
		this.nodeBuilder = nodeBuilder;
	}

	/**
	 * Loads one or more syllabus sources into a single repository. Node identifiers
	 * are assigned sequentially across all sources in input order.
	 *
	 * @param sources non-empty syllabus sources to import
	 * @return a repository containing the distinct subjects, versions, and built
	 *         curriculum nodes
	 * @throws IOException              if a source workbook cannot be read
	 * @throws IllegalArgumentException if {@code sources} is {@code null} or empty
	 */
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

			for (Path workbook : source.workbooks()) {
				rows.addAll(importer.read(workbook));
			}

			nodes.addAll(nodeBuilder.build(version, rows, ids::getAndIncrement));
		}

		return new InMemoryCurriculumRepository(List.copyOf(subjects), List.copyOf(versions), List.copyOf(nodes));
	}
}
