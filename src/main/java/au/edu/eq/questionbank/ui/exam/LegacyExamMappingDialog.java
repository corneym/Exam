package au.edu.eq.questionbank.ui.exam;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import au.edu.eq.questionbank.importer.legacy.LegacyExamIdentity;
import au.edu.eq.questionbank.importer.legacy.LegacyExamIdentityProposal;
import au.edu.eq.questionbank.importer.legacy.LegacyExamIdentityResolver;
import au.edu.eq.questionbank.importer.legacy.LegacyExamKey;
import au.edu.eq.questionbank.importer.legacy.LegacyQuestionSheet;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/**
 * Confirms the authoritative examination identity of each worksheet/year
 * combination in a legacy workbook.
 *
 * <p>
 * Worksheet-name conventions provide initial suggestions. No mapping is
 * authoritative until the user confirms it.
 */
public final class LegacyExamMappingDialog extends Dialog<Map<LegacyExamKey, LegacyExamIdentity>> {

	private static final double SPACING = 8;
	private final List<MappingEditor> editors = new ArrayList<>();

	/**
	 * Creates an examination mapping dialog.
	 *
	 * @param owner  owning application window
	 * @param sheets validated source worksheets
	 */
	public LegacyExamMappingDialog(Window owner, List<LegacyQuestionSheet> sheets) {
		if (sheets == null) {
			throw new NullPointerException("sheets");
		}
		initOwner(owner);
		setTitle("Resolve Legacy Examinations");
		setHeaderText("Confirm the examination associated with each worksheet and year.");
		List<LegacyExamIdentityProposal> proposals = new LegacyExamIdentityResolver().propose(sheets);
		ButtonType confirmType = new ButtonType("Confirm Examinations", ButtonBar.ButtonData.OK_DONE);
		getDialogPane().getButtonTypes().addAll(confirmType, ButtonType.CANCEL);
		configureContent(proposals);
		configureValidation(confirmType);
		setResultConverter(button -> {
			if (button != confirmType) {
				return null;
			}
			return collectMappings();
		});
	}

	private Map<LegacyExamKey, LegacyExamIdentity> collectMappings() {
		Map<LegacyExamKey, LegacyExamIdentity> result = new LinkedHashMap<>();
		for (MappingEditor editor : editors) {
			result.put(editor.key(), new LegacyExamIdentity(editor.provider().getText(), editor.examName().getText()));
		}
		return Map.copyOf(result);
	}

	private void configureContent(List<LegacyExamIdentityProposal> proposals) {
		VBox content = new VBox(SPACING);
		content.setPadding(new Insets(10));
		Label explanation = new Label("The worksheet name and Year column identify the source "
				+ "examination. Check the proposed Provider and " + "Examination Name before proceeding.");
		explanation.setWrapText(true);
		GridPane grid = new GridPane();
		grid.setHgap(10);
		grid.setVgap(8);
		grid.add(new Label("Worksheet"), 0, 0);
		grid.add(new Label("Year"), 1, 0);
		grid.add(new Label("Provider"), 2, 0);
		grid.add(new Label("Examination Name"), 3, 0);
		ColumnConstraints worksheetColumn = new ColumnConstraints(140);
		ColumnConstraints yearColumn = new ColumnConstraints(65);
		ColumnConstraints providerColumn = new ColumnConstraints(140);
		ColumnConstraints examColumn = new ColumnConstraints(220);
		examColumn.setHgrow(Priority.ALWAYS);
		grid.getColumnConstraints().addAll(worksheetColumn, yearColumn, providerColumn, examColumn);
		int row = 1;
		for (LegacyExamIdentityProposal proposal : proposals) {
			LegacyExamKey key = new LegacyExamKey(proposal.worksheetName(), proposal.year());
			TextField provider = new TextField(proposal.providerName());
			TextField examName = new TextField(proposal.examName());
			provider.setId("legacy-mapping-provider-" + row);
			examName.setId("legacy-mapping-exam-" + row);
			provider.setPromptText("Provider");
			examName.setPromptText("Examination name");
			grid.add(new Label(proposal.worksheetName()), 0, row);
			grid.add(new Label(Integer.toString(proposal.year())), 1, row);
			grid.add(provider, 2, row);
			grid.add(examName, 3, row);
			editors.add(new MappingEditor(key, provider, examName));
			row++;
		}
		ScrollPane scroll = new ScrollPane(grid);
		scroll.setFitToWidth(true);
		scroll.setPrefViewportHeight(Math.min(350, Math.max(90, row * 42)));
		Label note = new Label("Confirming these identities does not create examinations "
				+ "or import questions. Missing examination assets " + "will be resolved in Exam/Assets.");
		note.setWrapText(true);
		content.getChildren().addAll(explanation, scroll, note);
		getDialogPane().setContent(content);
		getDialogPane().setPrefWidth(720);
	}

	private void configureValidation(ButtonType confirmType) {
		Button confirm = (Button) getDialogPane().lookupButton(confirmType);
		confirm.setId("legacy-mapping-confirm");
		Runnable update = () -> confirm.setDisable(editors.isEmpty() || editors.stream()
				.anyMatch(editor -> editor.provider().getText().isBlank() || editor.examName().getText().isBlank()));
		for (MappingEditor editor : editors) {
			editor.provider().textProperty().addListener((_, _, _) -> update.run());
			editor.examName().textProperty().addListener((_, _, _) -> update.run());
		}
		update.run();
	}

	private record MappingEditor(LegacyExamKey key, TextField provider, TextField examName) {
	}
}
