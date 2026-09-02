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
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

public class LegacyQuestionWorkbookReader {

	private static final String YEAR = "Year";
	private static final String PAPER = "Paper";
	private static final String QUESTION = "Question";
	private static final String MARKS = "Marks";
	private static final String TOPIC = "Topic";
	private static final String ANSWER = "Answer";
	private static final String PREAMBLE = "Preamble";

	private final DataFormatter formatter = new DataFormatter();

	public List<LegacyQuestionSheet> read(Path path) throws IOException {
		if (path == null) {
			throw new NullPointerException("path");
		}
		try (InputStream input = Files.newInputStream(path); Workbook workbook = WorkbookFactory.create(input)) {
			List<LegacyQuestionSheet> sheets = new ArrayList<>();
			for (Sheet sheet : workbook) {
				sheets.add(readSheet(sheet));
			}
			return List.copyOf(sheets);
		}
	}

	private IllegalArgumentException error(Sheet sheet, int rowNumber, String message) {
		return new IllegalArgumentException("Sheet '" + sheet.getSheetName() + "', row " + rowNumber + ": " + message);
	}

	private Map<String, Integer> findColumns(Sheet sheet, Row header) {
		Map<String, Integer> columns = new HashMap<>();
		for (int columnIndex = header.getFirstCellNum(); columnIndex < header.getLastCellNum(); columnIndex++) {
			String heading = text(header, columnIndex);
			if (!heading.isBlank()) {
				columns.put(heading, columnIndex);
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

	private boolean isBlank(Row row, Map<String, Integer> columns) {
		for (int columnIndex : columns.values()) {
			if (!text(row, columnIndex).isBlank()) {
				return false;
			}
		}
		return true;
	}

	private String optionalText(Row row, int columnIndex) {
		String value = text(row, columnIndex);
		return value.isBlank() ? null : value;
	}

	private int positiveInteger(Sheet sheet, Row row, int columnIndex, String columnName) {
		String value = text(row, columnIndex);
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

	private boolean preamble(Sheet sheet, Row row, int columnIndex) {
		String value = text(row, columnIndex);
		if (value.isBlank()) {
			return false;
		}
		if ("1".equals(value)) {
			return true;
		}
		throw new IllegalArgumentException("Preamble must be blank or 1: " + value);
	}

	private LegacyQuestionRow readQuestion(Sheet sheet, Row row, Map<String, Integer> columns) {
		int excelRow = row.getRowNum() + 1;
		try {
			int year = positiveInteger(sheet, row, columns.get(YEAR), YEAR);
			String paperCode = requiredText(sheet, row, columns.get(PAPER), PAPER);
			String questionCode = requiredText(sheet, row, columns.get(QUESTION), QUESTION);
			int marks = positiveInteger(sheet, row, columns.get(MARKS), MARKS);
			String classificationCode = requiredText(sheet, row, columns.get(TOPIC), TOPIC);
			String answer = optionalText(row, columns.get(ANSWER));
			boolean preambleCaptureRequired = preamble(sheet, row, columns.get(PREAMBLE));
			return new LegacyQuestionRow(year, paperCode, questionCode, marks, classificationCode, answer,
					preambleCaptureRequired);
		} catch (IllegalArgumentException e) {
			throw error(sheet, excelRow, e.getMessage());
		}
	}

	private LegacyQuestionSheet readSheet(Sheet sheet) {
		Row header = sheet.getRow(sheet.getFirstRowNum());
		if (header == null) {
			throw error(sheet, 1, "Sheet has no header row");
		}
		Map<String, Integer> columns = findColumns(sheet, header);
		List<LegacyQuestionRow> questions = new ArrayList<>();
		for (int rowIndex = header.getRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
			Row row = sheet.getRow(rowIndex);
			if (row == null || isBlank(row, columns)) {
				continue;
			}
			questions.add(readQuestion(sheet, row, columns));
		}
		return new LegacyQuestionSheet(sheet.getSheetName().trim(), questions);
	}

	private void requireColumn(Sheet sheet, Map<String, Integer> columns, String name) {
		if (!columns.containsKey(name)) {
			throw error(sheet, 1, "Missing column: " + name);
		}
	}

	private String requiredText(Sheet sheet, Row row, int columnIndex, String columnName) {
		String value = text(row, columnIndex);
		if (value.isBlank()) {
			throw new IllegalArgumentException(columnName + " is blank");
		}
		return value;
	}

	private String text(Row row, int columnIndex) {
		if (row == null || row.getCell(columnIndex) == null) {
			return "";
		}
		return formatter.formatCellValue(row.getCell(columnIndex)).trim();
	}
}