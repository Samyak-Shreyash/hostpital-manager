package com.hms;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/** Module boundary and coding rules (ADR-0001, ADR-0014). */
@AnalyzeClasses(packages = "com.hms", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final DescribedPredicate<JavaClass> PUBLIC_MODULE_SURFACE = DescribedPredicate.describe(
            "another module's api package or the shared kernel",
            target -> target.getPackageName().matches("com\\.hms\\.[a-z]+\\.api(\\..*)?")
                    || target.getPackageName().startsWith("com.hms.shared"));

    /** A module may use another module only through its {@code api} package; {@code shared} is open to all. */
    @ArchTest
    static final ArchRule modules_only_access_other_modules_through_api = slices()
            .matching("com.hms.(*)..")
            .should().notDependOnEachOther()
            .ignoreDependency(DescribedPredicate.alwaysTrue(), PUBLIC_MODULE_SURFACE)
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule modules_are_free_of_cycles = slices()
            .matching("com.hms.(*)..")
            .should().beFreeOfCycles()
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule no_transactional_classes_outside_application = noClasses()
            .that().resideOutsideOfPackage("..application..")
            .should().beAnnotatedWith(Transactional.class)
            .orShould().beAnnotatedWith(jakarta.transaction.Transactional.class)
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule no_transactional_methods_outside_application = noMethods()
            .that().areDeclaredInClassesThat().resideOutsideOfPackage("..application..")
            .should().beAnnotatedWith(Transactional.class)
            .orShould().beAnnotatedWith(jakarta.transaction.Transactional.class)
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule every_endpoint_has_preauthorize = methods()
            .that().areAnnotatedWith(GetMapping.class)
            .or().areAnnotatedWith(PostMapping.class)
            .or().areAnnotatedWith(PutMapping.class)
            .or().areAnnotatedWith(PatchMapping.class)
            .or().areAnnotatedWith(DeleteMapping.class)
            .or().areAnnotatedWith(RequestMapping.class)
            .should().beAnnotatedWith(PreAuthorize.class)
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule no_standard_streams = NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

    @ArchTest
    static final ArchRule no_java_util_logging = NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;
}
