package au.edu.eq.questionbank.importer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

public class CurriculumMappingExcelImporter {

	private record HeaderColumns(int headerRow, int target2025Column, int source2019Column) {
	}

	private static final int HEADER_SEARCH_LIMIT = 20;

	/*
	 * Accepts either a descriptor code such as 3.1.1.4 or a subtopic code such as
	 * 3.1.1. Only the first three components are retained.
	 */
	private static final Pattern CURRICULUM_CODE = Pattern
			.compile("(?<![\\d.])(\\d+)\\.(\\d+)\\.(\\d+)(?:\\.\\d+)?(?![\\d.])");
	private static final Pattern NEW_MARKER = Pattern.compile("\\b(NEW|NO)\\b", Pattern.CASE_INSENSITIVE);
	private final DataFormatter formatter = new DataFormatter();

	public List<CurriculumMappingImportRow> read(Path path) throws IOException {

		if (path == null) {
			throw new NullPointerException("path");
		}

		Set<CurriculumMappingImportRow> mappings = new LinkedHashSet<>();
		try (InputStream inputStream = Files.newInputStream(path);
				Workbook workbook = WorkbookFactory.create(inputStream)) {
			FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
			for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
				if (workbook.isSheetHidden(sheetIndex) || workbook.isSheetVeryHidden(sheetIndex)) {
					continue;
				}
				Sheet sheet = workbook.getSheetAt(sheetIndex);
				HeaderColumns columns = findHeaderColumns(sheet, evaluator);
				if (columns == null) {
					continue;
				}
				readSheet(sheet, columns, evaluator, mappings);
			}
		}

		if (mappings.isEmpty()) {
			throw new IllegalArgumentException("No curriculum mappings found in " + path.getFileName());
		}
		return List.copyOf(mappings);
	}

	private String extractSingleSubtopicCode(String value, Sheet sheet, int rowIndex, String columnName) {
		List<String> codes = extractSubtopicCodes(value);
		if (codes.size() != 1) {
			throw new IllegalArgumentException("Expected one " + columnName + " descriptor code in sheet "
					+ sheet.getSheetName() + " at Excel row " + (rowIndex + 1) + ": " + value);
		}
		return codes.getFirst();
	}

	private List<String> extractSubtopicCodes(String value) {
		Set<String> codes = new LinkedHashSet<>();
		Matcher matcher = CURRICULUM_CODE.matcher(value);
		while (matcher.find()) {
			String code = matcher.group(1) + "." + matcher.group(2) + "." + matcher.group(3);
			codes.add(code);
		}
		return new ArrayList<>(codes);
	}

	private HeaderColumns findHeaderColumns(Sheet sheet, FormulaEvaluator evaluator) {
		int lastSearchRow = Math.min(sheet.getLastRowNum(), sheet.getFirstRowNum() + HEADER_SEARCH_LIMIT);
		for (int rowIndex = sheet.getFirstRowNum(); rowIndex <= lastSearchRow; rowIndex++) {
			Row row = sheet.getRow(rowIndex);
			if (row == null) {
				continue;
			}
			Integer column2025 = null;
			Integer column2019 = null;
			for (int columnIndex = 0; columnIndex < row.getLastCellNum(); columnIndex++) {
				String header = getText(row, columnIndex, evaluator).strip().toUpperCase(Locale.ROOT);
				if (header.equals("2025")) {
					column2025 = columnIndex;
				} else if (header.equals("2019")) {
					column2019 = columnIndex;
				}
			}
			if (column2025 != null && column2019 != null) {
				return new HeaderColumns(rowIndex, column2025, column2019);
			}
		}
		return null;
	}

	private String getText(Row row, int columnIndex, FormulaEvaluator evaluator) {
		Cell cell = row.getCell(columnIndex, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
		if (cell == null) {
			return "";
		}
		return formatter.formatCellValue(cell, evaluator).replace('\u00A0', ' ').strip();
	}

	private void readSheet(Sheet sheet, HeaderColumns columns, FormulaEvaluator evaluator,
			Set<CurriculumMappingImportRow> mappings) {
		for (int rowIndex = columns.headerRow() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
			Row row = sheet.getRow(rowIndex);
			if (row == null) {
				continue;
			}
			String target2025 = getText(row, columns.target2025Column(), evaluator);
			String source2019 = getText(row, columns.source2019Column(), evaluator);
			if (target2025.isBlank() && source2019.isBlank()) {
				continue;
			}
			String targetCode = extractSingleSubtopicCode(target2025, sheet, rowIndex, "2025");
			List<String> sourceCodes = extractSubtopicCodes(source2019);

			/*
			 * NEW/NO means the 2025 descriptor has no 2019 equivalent, so there is no
			 * legacy mapping to create.
			 */
			if (sourceCodes.isEmpty()) {
				if (NEW_MARKER.matcher(source2019).find()) {
					continue;
				}

				throw new IllegalArgumentException("No valid 2019 mapping in sheet " + sheet.getSheetName()
						+ " at Excel row " + (rowIndex + 1) + ": " + source2019);
			}
			for (String sourceCode : sourceCodes) {
				mappings.add(new CurriculumMappingImportRow(sourceCode, targetCode));
			}
		}
	}
}