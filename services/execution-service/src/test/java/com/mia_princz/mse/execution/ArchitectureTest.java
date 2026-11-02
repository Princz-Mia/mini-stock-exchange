package com.mia_princz.mse.execution;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class ArchitectureTest {

	private static final JavaClasses CLASSES = new ClassFileImporter()
		.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
		.importPackages("com.mia_princz.mse.execution");

	@Test
	void domainPackagesImportNoFrameworkOrInfrastructure() {
		noClasses().that()
			.resideInAPackage("..domain..")
			.should()
			.dependOnClassesThat()
			.resideInAnyPackage("org.springframework..", "jakarta..", "java.sql..", "javax.sql..", "org.apache.kafka..",
					"org.apache.avro..", "tools.jackson..", "com.fasterxml..", "com.mia_princz.mse.contracts..",
					"..adapter..", "..application..")
			.check(CLASSES);
	}

}
