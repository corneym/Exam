module au.edu.eq.questionbank {
	requires java.desktop;

	requires javafx.controls;
	requires javafx.swing;

	requires org.apache.pdfbox;
	requires javafx.graphics;
	requires org.apache.poi.ooxml;

	exports au.edu.eq.questionbank;
	exports au.edu.eq.questionbank.ui;
}