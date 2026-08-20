package au.edu.eq.questionbank.importer;

import java.nio.file.Path;
import java.util.List;

import au.edu.eq.questionbank.model.SyllabusVersion;

public record CurriculumSource(SyllabusVersion syllabusVersion, List<Path> workbooks) {

	public CurriculumSource {
		if (syllabusVersion == null) {
			throw new NullPointerException("syllabusVersion");
		}
		if (workbooks == null || workbooks.isEmpty()) {
			throw new IllegalArgumentException("workbooks must not be empty");
		}

		workbooks = List.copyOf(workbooks);
	}
}