package au.edu.eq.questionbank.importer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

public class CurriculumExcelImporter {

	private static final int HEADER_SEARCH_LIMIT = 25;

	private final DataFormatter formatter;

	public CurriculumExcelImporter() {
		formatter = new DataFormatter();
	}

	public List<CurriculumImportRow> read(Path path) throws IOException {
		if (path == null) {
			throw new NullPointerException("path");
		}

		try (InputStream inputStream = Files.newInputStream(path);
				Workbook workbook = WorkbookFactory.create(inputStream)) {

			FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
			List<CurriculumImportRow> importRows = new ArrayList<>();

			for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
				if (workbook.isSheetHidden(sheetIndex) || workbook.isSheetVeryHidden(sheetIndex)) {
					continue;
				}

				Sheet sheet = workbook.getSheetAt(sheetIndex);
				HeaderColumns columns = findHeaderColumns(sheet, evaluator);

				if (columns != null) {
					importRows.addAll(readSheet(sheet, columns, evaluator));
				}
			}

			if (importRows.isEmpty()) {
				throw new IllegalArgumentException("No curriculum data could be found in " + path.getFileName());
			}

			return List.copyOf(importRows);
		}
	}

	private List<CurriculumImportRow> readSheet(Sheet sheet, HeaderColumns columns, FormulaEvaluator evaluator) {

		List<CurriculumImportRow> rows = new ArrayList<>();

		String currentUnit = "";
		String currentTopic = "";
		String currentSubtopic = "";
		String currentClassification = "";

		for (int rowIndex = columns.headerRow() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {

			Row row = sheet.getRow(rowIndex);

			if (row == null) {
				continue;
			}

			String unit = getText(row, columns.unitColumn(), evaluator);
			String topic = getText(row, columns.topicColumn(), evaluator);
			String subtopic = getText(row, columns.subtopicColumn(), evaluator);
			String classification = getText(row, columns.classificationColumn(), evaluator);
			String descriptor = getText(row, columns.descriptorColumn(), evaluator);

			if (!unit.isBlank()) {
				currentUnit = unit;
			}
			if (!topic.isBlank()) {
				currentTopic = topic;
			}
			if (!subtopic.isBlank()) {
				currentSubtopic = subtopic;
			}
			if (!classification.isBlank()) {
				currentClassification = classification;
			}

			if (descriptor.isBlank()) {
				continue;
			}

			if (currentUnit.isBlank() || currentTopic.isBlank() || currentSubtopic.isBlank()
					|| currentClassification.isBlank()) {

				throw new IllegalArgumentException("Incomplete curriculum hierarchy in sheet " + sheet.getSheetName()
						+ " at Excel row " + (rowIndex + 1));
			}

			rows.add(new CurriculumImportRow(currentUnit, currentTopic, currentSubtopic, currentClassification,
					descriptor));
		}

		return rows;
	}

	private HeaderColumns findHeaderColumns(Sheet sheet, FormulaEvaluator evaluator) {

		int lastSearchRow = Math.min(sheet.getLastRowNum(), sheet.getFirstRowNum() + HEADER_SEARCH_LIMIT);

		for (int rowIndex = sheet.getFirstRowNum(); rowIndex <= lastSearchRow; rowIndex++) {

			Row row = sheet.getRow(rowIndex);

			if (row == null) {
				continue;
			}

			Map<String, Integer> headers = new HashMap<>();

			for (int columnIndex = 0; columnIndex < row.getLastCellNum(); columnIndex++) {

				String value = normalizeHeader(getText(row, columnIndex, evaluator));

				if (!value.isBlank()) {
					headers.put(value, columnIndex);
				}
			}

			Integer unitColumn = headers.get("unit");
			Integer topicColumn = headers.get("topic");
			Integer subtopicColumn = headers.get("subtopic");
			Integer descriptorColumn = headers.get("descriptor");

			if (unitColumn == null || topicColumn == null || subtopicColumn == null || descriptorColumn == null) {
				continue;
			}

			Integer classificationColumn = headers.get("classification");

			/*
			 * The full Chemistry study checklists have a blank header immediately before
			 * Descriptor. That column contains the classification code.
			 */
			if (classificationColumn == null) {
				classificationColumn = descriptorColumn - 1;
			}

			if (classificationColumn < 0 || classificationColumn.equals(subtopicColumn)) {
				throw new IllegalArgumentException(
						"Could not determine classification column in sheet " + sheet.getSheetName());
			}

			return new HeaderColumns(rowIndex, unitColumn, topicColumn, subtopicColumn, classificationColumn,
					descriptorColumn);
		}

		return null;
	}

	private String getText(Row row, int columnIndex, FormulaEvaluator evaluator) {

		Cell cell = row.getCell(columnIndex, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);

		if (cell == null) {
			return "";
		}

		return cleanText(formatter.formatCellValue(cell, evaluator));
	}

	private String cleanText(String value) {
		if (value == null) {
			return "";
		}

		/*
		 * Some 2019 descriptors contain non-breaking spaces copied from source
		 * documents.
		 */
		return value.replace('\u00A0', ' ').strip();
	}

	private String normalizeHeader(String value) {
		return value.strip().toLowerCase(Locale.ROOT);
	}

	private record HeaderColumns(int headerRow, int unitColumn, int topicColumn, int subtopicColumn,
			int classificationColumn, int descriptorColumn) {
	}
}