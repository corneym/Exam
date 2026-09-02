/**
 * Exam question-bank domain, PDF extraction, curriculum import, output, and
 * JavaFX user-interface components.
 */
module au.edu.eq.questionbank {
	requires java.desktop;
	requires java.naming;
	requires java.prefs;
	requires java.sql;

	requires org.apache.pdfbox;
	requires org.apache.poi.ooxml;
	requires org.apache.logging.log4j.core;

	requires javafx.graphics;
	requires javafx.base;
	requires javafx.controls;
	requires javafx.swing;
	requires org.apache.poi.poi;

	exports au.edu.eq.questionbank;
	exports au.edu.eq.questionbank.ui;

	opens au.edu.eq.questionbank.ui.model;
	opens au.edu.eq.questionbank.service.curriculum;
	opens au.edu.eq.questionbank.importer.legacy;
}
