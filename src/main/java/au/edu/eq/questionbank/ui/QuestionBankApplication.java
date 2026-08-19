package au.edu.eq.questionbank.ui;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.pdf.PdfSession;
import au.edu.eq.questionbank.pdf.PdfStore;
import javafx.application.Application;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;

public class QuestionBankApplication extends Application {

	private static final float DISPLAY_DPI = 120;

	public static void main(String[] args) {
		launch(args);
	}

	private PdfSession pdfSession;

	private int currentPageNumber = 1;
	private final ImageView pageView = new ImageView();

	private final Label pageLabel = new Label();

	private final Button previousButton = new Button("Previous");
	private final Button nextButton = new Button("Next");

	@Override
	public void start(Stage stage) throws Exception {
		ApplicationConfig config = ApplicationConfig.load(Path.of("questionbank.properties"));
		PdfStore pdfStore = new PdfStore(config.pdfDataRoot());
		Path pdfPath = pdfStore.resolve("chemistry/QCAA/2024/" + "snr_chemistry_24_ea_p1_mc_question.pdf");
		pdfSession = PdfSession.open(pdfPath);

		pageView.setPreserveRatio(true);
		ScrollPane scrollPane = new ScrollPane(pageView);
		scrollPane.setFitToWidth(true);
		scrollPane.setFitToHeight(true);

		previousButton.setOnAction(event -> previousPage());
		nextButton.setOnAction(event -> nextPage());

		HBox controls = new HBox(10, previousButton, pageLabel, nextButton);
		controls.setAlignment(Pos.CENTER);

		BorderPane root = new BorderPane();
		root.setCenter(scrollPane);
		root.setBottom(controls);

		Scene scene = new Scene(root, 1000, 800);
		stage.setTitle("Exam Question Bank");
		stage.setScene(scene);
		stage.show();

		showCurrentPage();
	}

	@Override
	public void stop() throws Exception {
		if (pdfSession != null) {
			pdfSession.close();
		}
	}

	private void nextPage() {
		if (currentPageNumber < pdfSession.getPageCount()) {
			currentPageNumber++;
			showCurrentPage();
		}
	}

	private void previousPage() {
		if (currentPageNumber > 1) {
			currentPageNumber--;
			showCurrentPage();
		}
	}

	private void showCurrentPage() {
		try {
			BufferedImage bufferedImage = pdfSession.renderPage(currentPageNumber, DISPLAY_DPI);
			pageView.setImage(SwingFXUtils.toFXImage(bufferedImage, null));
			pageLabel.setText(String.format("Page %d of %d", currentPageNumber, pdfSession.getPageCount()));
			previousButton.setDisable(currentPageNumber == 1);
			nextButton.setDisable(currentPageNumber == pdfSession.getPageCount());
		} catch (IOException e) {
			throw new RuntimeException("Unable to render PDF page", e);
		}
	}
}
