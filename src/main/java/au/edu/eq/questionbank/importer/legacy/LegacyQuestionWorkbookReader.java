package au.edu.eq.questionbank.importer.legacy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

/**
 * Reads the fixed legacy question-metadata workbook format. Each worksheet name
 * identifies an examination provider and every non-blank data row becomes one
 * validated question record.
 */
public class LegacyQuestionWorkbookReader {

	/**
	 * Creates a reader for provider worksheets in legacy question workbooks.
	 */
	public LegacyQuestionWorkbookReader() {
	}

	private static final String YEAR = "Year";
	private static final String PAPER = "Paper";
	private static final String QUESTION = "Question";
	private static final String MARKS = "Marks";
	private static final String TOPIC = "Topic";
	private static final String ANSWER = "Answer";
	private static final String PREAMBLE = "Preamble";
	private final DataFormatter formatter = new DataFormatter();

	/**
	 * Reads every worksheet in workbook order.
	 *
	 * @param path the legacy {@code .xlsx} workbook
	 * @return immutable worksheet records
	 * @throws IOException              if the workbook cannot be read
	 * @throws NullPointerException     if {@code path} is {@code null}
	 * @throws IllegalArgumentException if a required heading or row value is
	 *                                  invalid
	 */
	public List<LegacyQuestionSheet> read(Path path) throws IOException {
		if (path == null) {
			throw new NullPointerException("path");
		}
		try (InputStream input = Files.newInputStream(path); Workbook workbook = WorkbookFactory.create(input)) {
			FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
			List<LegacyQuestionSheet> sheets = new ArrayList<>();

			// Every worksheet is treated as provider data, including hidden worksheets.
			for (Sheet sheet : workbook) {
				sheets.add(readSheet(sheet, evaluator));
			}
			return List.copyOf(sheets);
		}
	}

	private IllegalArgumentException error(Sheet sheet, int rowNumber, String message) {
		return new IllegalArgumentException("Sheet '" + sheet.getSheetName() + "', row " + rowNumber + ": " + message);
	}

	private Map<String, Integer> findColumns(Sheet sheet, Row header, FormulaEvaluator evaluator) {

		// Locate fields by their headings so column order can vary without changing the
		// row mapping.
		Map<String, Integer> columns = new HashMap<>();
		for (int columnIndex = header.getFirstCellNum(); columnIndex < header.getLastCellNum(); columnIndex++) {
			String heading = text(header, columnIndex, evaluator);
			if (!heading.isBlank()) {
				Integer previous = columns.putIfAbsent(heading, columnIndex);
				if (previous != null) {
					throw error(sheet, 1, "Duplicate column: " + heading);
				}
			}
		}
		requireColumn(sheet, columns, YEAR);
		requireColumn(sheet, columns, PAPER);
		requireColumn(sheet, columns, QUESTION);
		requireColumn(sheet, columns, MARKS);
		requireColumn(sheet, columns, TOPIC);
		requireColumn(sheet, columns, ANSWER);
		requireColumn(sheet, columns, PREAMBLE);
		return columns;
	}

	private boolean isBlank(Row row, Map<String, Integer> columns, FormulaEvaluator evaluator) {

		// Consider all named columns, including extras, when deciding whether a row is
		// only a spacer.
		for (int columnIndex : columns.values()) {
			if (!text(row, columnIndex, evaluator).isBlank()) {
				return false;
			}
		}
		return true;
	}

	private String optionalText(Row row, int columnIndex, FormulaEvaluator evaluator) {
		String value = text(row, columnIndex, evaluator);
		return value.isBlank() ? null : value;
	}

	private int positiveInteger(Sheet sheet, Row row, int columnIndex, String columnName, FormulaEvaluator evaluator) {
		String value = text(row, columnIndex, evaluator);
		if (value.isBlank()) {
			throw new IllegalArgumentException(columnName + " is blank");
		}
		try {
			int number = Integer.parseInt(value);
			if (number < 1) {
				throw new IllegalArgumentException(columnName + " must be positive");
			}
			return number;
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException(columnName + " must be a whole number: " + value);
		}
	}

	private boolean preamble(Sheet sheet, Row row, int columnIndex, FormulaEvaluator evaluator) {

		// The legacy format uses blank and 1 only; other values must not silently
		// become false.
		String value = text(row, columnIndex, evaluator);
		if (value.isBlank()) {
			return false;
		}
		if ("1".equals(value)) {
			return true;
		}
		throw new IllegalArgumentException("Preamble must be blank or 1: " + value);
	}

	private LegacyQuestionRow readQuestion(Sheet sheet, Row row, Map<String, Integer> columns,
			FormulaEvaluator evaluator) {
		int excelRow = row.getRowNum() + 1;
		try {
			int year = positiveInteger(sheet, row, columns.get(YEAR), YEAR, evaluator);
			String paperCode = requiredText(sheet, row, columns.get(PAPER), PAPER, evaluator);
			String questionCode = requiredText(sheet, row, columns.get(QUESTION), QUESTION, evaluator);
			int marks = positiveInteger(sheet, row, columns.get(MARKS), MARKS, evaluator);
			String classificationCode = requiredText(sheet, row, columns.get(TOPIC), TOPIC, evaluator);
			String answer = optionalText(row, columns.get(ANSWER), evaluator);
			boolean preambleCaptureRequired = preamble(sheet, row, columns.get(PREAMBLE), evaluator);
			return new LegacyQuestionRow(year, paperCode, questionCode, marks, classificationCode, answer,
					preambleCaptureRequired);
		} catch (IllegalArgumentException e) {

			// Report the worksheet and one-based Excel row for both parsing and
			// record-validation failures.
			throw error(sheet, excelRow, e.getMessage());
		}
	}

	private LegacyQuestionSheet readSheet(Sheet sheet, FormulaEvaluator evaluator) {
		Row header = sheet.getRow(sheet.getFirstRowNum());
		if (header == null) {
			throw error(sheet, 1, "Sheet has no header row");
		}
		Map<String, Integer> columns = findColumns(sheet, header, evaluator);
		List<LegacyQuestionRow> questions = new ArrayList<>();
		for (int rowIndex = header.getRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
			Row row = sheet.getRow(rowIndex);
			if (row == null || isBlank(row, columns, evaluator)) {
				continue;
			}
			questions.add(readQuestion(sheet, row, columns, evaluator));
		}
		return new LegacyQuestionSheet(sheet.getSheetName().trim(), questions);
	}

	private void requireColumn(Sheet sheet, Map<String, Integer> columns, String name) {
		if (!columns.containsKey(name)) {
			throw error(sheet, 1, "Missing column: " + name);
		}
	}

	private String requiredText(Sheet sheet, Row row, int columnIndex, String columnName, FormulaEvaluator evaluator) {
		String value = text(row, columnIndex, evaluator);
		if (value.isBlank()) {
			throw new IllegalArgumentException(columnName + " is blank");
		}
		return value;
	}

	private String text(Row row, int columnIndex, FormulaEvaluator evaluator) {
		if (row == null || row.getCell(columnIndex) == null) {
			return "";
		}

		// Use displayed values and formula results so identifiers remain text rather
		// than numeric conversions.
		return formatter.formatCellValue(row.getCell(columnIndex), evaluator).trim();
	}
}
