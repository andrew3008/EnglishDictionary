package space.br1440.platform.errorhandling.autoconfigure;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;
import org.springframework.lang.Nullable;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.RequestContext;
import space.br1440.platform.errorhandling.model.dto.ErrorEntryDTO;
import space.br1440.platform.errorhandling.model.error_detail.dto.BadRequestDetailDTO;
import space.br1440.platform.errorhandling.model.error_detail.dto.CustomErrorDetailDTO;
import space.br1440.platform.errorhandling.model.error_detail.dto.ErrorDetailDTO;
import space.br1440.platform.errorhandling.model.error_detail.dto.ErrorInfoDetailDTO;
import space.br1440.platform.errorhandling.model.error_detail.dto.HelpDetailDTO;
import space.br1440.platform.errorhandling.model.error_detail.dto.PreconditionFailureDetailDTO;
import space.br1440.platform.errorhandling.model.error_detail.dto.ResourceInfoDetailDTO;
import space.br1440.platform.errorhandling.model.error_detail.dto.RetryInfoDetailDTO;

/**
 * Регистрирует {@link RuntimeHints} для AOT/GraalVM native image: открывает полный рефлексивный
 * доступ (поля, конструкторы, методы — declared + introspect) к DTO ответа об ошибке и доменным
 * типам, которые сериализуются Jackson и не обнаруживаются статическим анализом Spring AOT.
 *
 * <p>На обычном HotSpot JVM никакого эффекта не оказывает — нужен только при
 * {@code processAot} / native build. Подключается через
 * {@link org.springframework.context.annotation.ImportRuntimeHints @ImportRuntimeHints} на
 * {@link ErrorHandlingMvcAutoConfiguration}.
 */
public final class ErrorHandlingMvcRuntimeHints implements RuntimeHintsRegistrar {

    private static final MemberCategory[] FULL_REFLECTION = {
            MemberCategory.DECLARED_FIELDS,
            MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
            MemberCategory.INVOKE_DECLARED_METHODS,
            MemberCategory.INTROSPECT_DECLARED_CONSTRUCTORS,
            MemberCategory.INTROSPECT_DECLARED_METHODS
    };

    @Override
    public void registerHints(@Nullable RuntimeHints hints, @Nullable ClassLoader classLoader) {
        if (hints == null) {
            return;
        }

        registerType(hints, ErrorEntryDTO.class);
        registerType(hints, ErrorDetailDTO.class);
        registerType(hints, ErrorInfoDetailDTO.class);
        registerType(hints, BadRequestDetailDTO.class);
        registerType(hints, RetryInfoDetailDTO.class);
        registerType(hints, HelpDetailDTO.class);
        registerType(hints, ResourceInfoDetailDTO.class);
        registerType(hints, PreconditionFailureDetailDTO.class);
        registerType(hints, CustomErrorDetailDTO.class);

        registerType(hints, ErrorEntry.class);
        registerType(hints, RequestContext.class);
    }

    private static void registerType(RuntimeHints hints, Class<?> type) {
        hints.reflection().registerType(TypeReference.of(type), FULL_REFLECTION);
    }
}
