module au.edu.eq.questionbank {
	requires java.desktop;

	requires javafx.controls;
	requires javafx.swing;

	requires org.apache.pdfbox;
	requires javafx.graphics;
	requires org.apache.poi.ooxml;
	requires org.apache.logging.log4j.core;

	exports au.edu.eq.questionbank;
	exports au.edu.eq.questionbank.ui;

	opens au.edu.eq.questionbank.ui.model;
}