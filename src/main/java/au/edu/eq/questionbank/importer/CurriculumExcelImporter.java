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
 * Column A: Code Column B: Content
 */
public class CurriculumExcelImporter {

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