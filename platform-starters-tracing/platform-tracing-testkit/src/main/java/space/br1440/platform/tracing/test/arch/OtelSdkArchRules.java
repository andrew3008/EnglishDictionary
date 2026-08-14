package space.br1440.platform.tracing.test.arch;

import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.properties.HasName;
import com.tngtech.archunit.core.domain.properties.HasOwner;
import com.tngtech.archunit.lang.ArchRule;
import lombok.experimental.UtilityClass;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@UtilityClass
public final class OtelSdkArchRules {

    public static final ArchRule NO_BUILD_AND_REGISTER_GLOBAL = noClasses()
            .should()
            .callMethodWhere(
                    JavaCall.Predicates.target(
                            HasName.Predicates.name("buildAndRegisterGlobal")
                    )
            )
            .because("""
                              Global OpenTelemetry registration is only allowed from platform-tracing-spring-boot-autoconfigure
                              (the OTel Java Agent and starter expect the application not to perform a second registration).
                            """);

    public static final ArchRule NO_GLOBAL_OPEN_TELEMETRY = noClasses()
            .should().callMethodWhere(
                    JavaCall.Predicates.target(
                            HasOwner.Predicates.With.owner(
                                    HasName.Predicates.name("io.opentelemetry.api.GlobalOpenTelemetry")
                            )
                    )
            )
            .because("OpenTelemetry bean must be injected via Spring; GlobalOpenTelemetry.get() breaks testability");
}
