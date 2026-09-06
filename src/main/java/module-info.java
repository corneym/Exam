/**
 * Exam question-bank domain, PDF extraction, curriculum import, output, and
 * JavaFX user-interface components.
 */
module au.edu.eq.questionbank {

	requires java.desktop;
	requires java.naming;
	requires java.prefs;
	requires java.sql;
	requires java.xml;
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

	opens au.edu.eq.questionbank;
	opens au.edu.eq.questionbank.admin;
	opens au.edu.eq.questionbank.importer.curriculum;
	opens au.edu.eq.questionbank.importer.legacy;
	opens au.edu.eq.questionbank.model;
	opens au.edu.eq.questionbank.output;
	opens au.edu.eq.questionbank.output.revision;
	opens au.edu.eq.questionbank.output.scorm;
	opens au.edu.eq.questionbank.pdf;
	opens au.edu.eq.questionbank.repository.assessment;
	opens au.edu.eq.questionbank.repository.curriculum;
	opens au.edu.eq.questionbank.repository.sqlite;
	opens au.edu.eq.questionbank.service.backup;
	opens au.edu.eq.questionbank.service.curriculum;
	opens au.edu.eq.questionbank.service.retrieval;
	opens au.edu.eq.questionbank.service.revision;
	opens au.edu.eq.questionbank.ui;
	opens au.edu.eq.questionbank.ui.model;
}
