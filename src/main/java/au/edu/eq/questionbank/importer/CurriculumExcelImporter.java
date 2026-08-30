package au.edu.eq.questionbank.importer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

/**
 * Reads curriculum data from the standard two-column workbook format.
 *
 * Column A contains the curriculum code and column B contains its label or
 * descriptor text. Hidden sheets and sheets without the expected header row
 * are ignored.
 */
public class CurriculumExcelImporter {

	private final DataFormatter formatter;

	/**
	 * Creates an importer using Apache POI's standard display-value formatter.
	 */
	public CurriculumExcelImporter() {
		formatter = new DataFormatter();
	}

	/**
	 * Reads all valid visible sheets in workbook order and returns their rows in
	 * sheet and row order. Curriculum codes must be unique across the workbook.
	 *
	 * @param path the workbook to read
	 * @return an immutable, non-empty list of curriculum rows
	 * @throws IOException if the workbook cannot be opened or read
	 * @throws NullPointerException if {@code path} is {@code null}
	 * @throws IllegalArgumentException if no curriculum data is found or a row has
	 *                                  missing, invalid, or duplicate values
	 */
	public List<CurriculumImportRow> read(Path path) throws IOException {

		if (path == null) {
			throw new NullPointerException("path");
		}

		try (InputStream inputStream = Files.newInputStream(path);
				Workbook workbook = WorkbookFactory.create(inputStream)) {

			FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();

			List<CurriculumImportRow> importRows = new ArrayList<>();

			Set<String> codes = new HashSet<>();

			for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {

				if (workbook.isSheetHidden(sheetIndex) || workbook.isSheetVeryHidden(sheetIndex)) {
					continue;
				}

				Sheet sheet = workbook.getSheetAt(sheetIndex);

				readSheet(sheet, evaluator, importRows, codes);
			}

			if (importRows.isEmpty()) {
				throw new IllegalArgumentException("No curriculum data could be found in " + path.getFileName());
			}

			return List.copyOf(importRows);
		}
	}

	private String getText(Row row, int columnIndex, FormulaEvaluator evaluator) {

		Cell cell = row.getCell(columnIndex, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);

		if (cell == null) {
			return "";
		}

		return formatter.formatCellValue(cell, evaluator).strip();
	}

	private void readSheet(Sheet sheet, FormulaEvaluator evaluator, List<CurriculumImportRow> importRows,
			Set<String> codes) {

		Row header = sheet.getRow(sheet.getFirstRowNum());

		if (header == null) {
			return;
		}

		String codeHeader = getText(header, 0, evaluator);

		String contentHeader = getText(header, 1, evaluator);

		if (!codeHeader.toLowerCase(Locale.ROOT).equals("code")
				|| !contentHeader.toLowerCase(Locale.ROOT).equals("content")) {
			return;
		}

		for (int rowIndex = sheet.getFirstRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {

			Row row = sheet.getRow(rowIndex);

			if (row == null) {
				continue;
			}

			String code = getText(row, 0, evaluator);

			String content = getText(row, 1, evaluator);

			if (code.isBlank() && content.isBlank()) {
				continue;
			}

			if (code.isBlank()) {
				throw new IllegalArgumentException(
						"Missing curriculum code in sheet " + sheet.getSheetName() + " at Excel row " + (rowIndex + 1));
			}

			if (content.isBlank()) {
				throw new IllegalArgumentException("Missing curriculum content for " + code);
			}

			validateCode(code);

			if (!codes.add(code)) {
				throw new IllegalArgumentException("Duplicate curriculum code: " + code);
			}

			importRows.add(new CurriculumImportRow(code, content));
		}
	}

	private void validateCode(String code) {

		if (!code.matches("\\d+(\\.\\d+){0,3}")) {

			throw new IllegalArgumentException("Invalid curriculum code: " + code);
		}
	}
}
