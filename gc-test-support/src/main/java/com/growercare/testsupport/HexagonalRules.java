package com.growercare.testsupport;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.GeneralCodingRules;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.junit.jupiter.params.provider.Arguments;

/**
 * SPEC-PLAT-01 R4–R9: the architecture rules every GrowerCare service obeys, as parameterized test rows.
 * <pre>{@code
 * static Stream<Arguments> rules() { return HexagonalRules.rows(); }
 *
 * @ParameterizedTest @MethodSource("rules")
 * void architecture(ArchRule rule) { rule.check(HexagonalRules.mainClasses("com.growercare.casesvc")); }
 * }</pre>
 */
public final class HexagonalRules {

    private static final Map<String, JavaClasses> IMPORTED = new ConcurrentHashMap<>();

    private HexagonalRules() { }

    /** Production classes only (tests may use anything). Cached per package. */
    public static JavaClasses mainClasses(String basePackage) {
        return IMPORTED.computeIfAbsent(basePackage, p -> new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
                .importPackages(p));
    }

    public static Stream<Arguments> rows() {
        return Stream.of(
                argumentSet("PLAT-R4 domain is free of frameworks and adapters", noClasses()
                        .that().resideInAPackage("..domain..")
                        .should().dependOnClassesThat().resideInAnyPackage(
                                "org.springframework..", "java.sql..", "javax.sql..", "jakarta.servlet..",
                                "tools.jackson..", "com.fasterxml.jackson..", "..adapters..", "..config..")
                        .allowEmptyShould(true)),
                argumentSet("PLAT-R5 application does not depend on adapters", noClasses()
                        .that().resideInAPackage("..application..")
                        .should().dependOnClassesThat().resideInAPackage("..adapters..")
                        .allowEmptyShould(true)),
                argumentSet("PLAT-R6 inbound adapters do not use outbound adapters", noClasses()
                        .that().resideInAPackage("..adapters.in..")
                        .should().dependOnClassesThat().resideInAPackage("..adapters.out..")
                        .allowEmptyShould(true)),
                argumentSet("PLAT-R64 no ORM (ADR-013)", noClasses()
                        .should().dependOnClassesThat().resideInAnyPackage("org.hibernate..", "jakarta.persistence..")),
                argumentSet("PLAT-R65 Kafka only in inbound messaging adapters and config (publishing = outbox)", noClasses()
                        .that().resideOutsideOfPackages("..adapters.in.messaging..", "..config..")
                        .should().dependOnClassesThat().resideInAnyPackage("org.springframework.kafka..", "org.apache.kafka..")),
                argumentSet("PLAT-R8a no field injection", GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION),
                argumentSet("PLAT-R8b no System.out / System.err", GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS),
                argumentSet("PLAT-R8c no java.util.logging", GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING),
                argumentSet("PLAT-R9a controllers live in adapters.in.web", classes()
                        .that().areAnnotatedWith("org.springframework.web.bind.annotation.RestController")
                        .should().resideInAPackage("..adapters.in.web..")
                        .allowEmptyShould(true)),
                argumentSet("PLAT-R9b controllers never run SQL", noClasses()
                        .that().areAnnotatedWith("org.springframework.web.bind.annotation.RestController")
                        .should().dependOnClassesThat().haveFullyQualifiedName("org.springframework.jdbc.core.simple.JdbcClient")
                        .allowEmptyShould(true)));
    }

    /** Convenience for one-line service tests. */
    public static void check(ArchRule rule, String basePackage) {
        rule.check(mainClasses(basePackage));
    }
}
