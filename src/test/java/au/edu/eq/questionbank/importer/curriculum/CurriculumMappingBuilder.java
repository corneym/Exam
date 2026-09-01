package au.edu.eq.questionbank.importer.curriculum;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

import au.edu.eq.questionbank.model.CurriculumMapping;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.MappingStatus;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.repository.curriculum.CurriculumRepository;

public class CurriculumMappingBuilder {

	public List<CurriculumMapping> build(CurriculumRepository curriculumRepository, SyllabusVersion sourceVersion,
			SyllabusVersion targetVersion, List<CurriculumMappingImportRow> rows, LongSupplier idSupplier) {

		if (curriculumRepository == null) {
			throw new NullPointerException("curriculumRepository");
		}
		if (sourceVersion == null) {
			throw new NullPointerException("sourceVersion");
		}
		if (targetVersion == null) {
			throw new NullPointerException("targetVersion");
		}
		if (rows == null) {
			throw new NullPointerException("rows");
		}
		if (idSupplier == null) {
			throw new NullPointerException("idSupplier");
		}

		List<CurriculumMapping> mappings = new ArrayList<>();
		for (CurriculumMappingImportRow row : rows) {
			CurriculumNode source = curriculumRepository.findByCode(sourceVersion, row.sourceCode()).orElseThrow(
					() -> new IllegalArgumentException("2019 curriculum node not found: " + row.sourceCode()));
			CurriculumNode target = curriculumRepository.findByCode(targetVersion, row.targetCode()).orElseThrow(
					() -> new IllegalArgumentException("2025 curriculum node not found: " + row.targetCode()));

			mappings.add(new CurriculumMapping(idSupplier.getAsLong(), source, target, MappingStatus.CONFIRMED));
		}
		return List.copyOf(mappings);
	}
}