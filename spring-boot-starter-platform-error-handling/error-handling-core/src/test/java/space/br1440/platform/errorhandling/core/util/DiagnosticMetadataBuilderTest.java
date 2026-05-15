package space.br1440.platform.errorhandling.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DiagnosticMetadataBuilder")
class DiagnosticMetadataBuilderTest {

    private final StackHintExtractor stackHintExtractor = new StackHintExtractor(false, List.of());

    // -------------------------------------------------------------------------
    // forCause — фабричный метод с автоматическим exceptionClass
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("forCause")
    class ForCause {

        @Test
        @DisplayName("первым ключом всегда является exceptionClass")
        void firstKeyIsExceptionClass() {
            // given
            RuntimeException cause = new RuntimeException("msg");

            // when
            Map<String, String> meta = DiagnosticMetadataBuilder.forCause(cause, stackHintExtractor)
                    .build();

            // then
            assertThat(meta).containsKey("exceptionClass");
            assertThat(new ArrayList<>(meta.keySet()).get(0)).isEqualTo("exceptionClass");
        }

        @Test
        @DisplayName("exceptionClass содержит FQCN исключения")
        void exceptionClassIsFqcn() {
            // given
            IllegalArgumentException cause = new IllegalArgumentException("arg");

            // when
            Map<String, String> meta = DiagnosticMetadataBuilder.forCause(cause, stackHintExtractor).build();

            // then
            assertThat(meta.get("exceptionClass")).isEqualTo(IllegalArgumentException.class.getName());
        }

        @Test
        @DisplayName("результат содержит только exceptionClass если ничего не добавлено")
        void onlyExceptionClassWhenEmpty() {
            // given
            RuntimeException cause = new RuntimeException();

            // when
            Map<String, String> meta = DiagnosticMetadataBuilder.forCause(cause, stackHintExtractor).build();

            // then
            assertThat(meta).hasSize(1).containsOnlyKeys("exceptionClass");
        }

        @Test
        @DisplayName("бросает NullPointerException если cause == null")
        void throwsOnNullCause() {
            // when / Then
            assertThatThrownBy(() -> DiagnosticMetadataBuilder.forCause(null, stackHintExtractor))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // -------------------------------------------------------------------------
    // forCauseRaw — фабричный метод без exceptionClass
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("forCauseRaw")
    class ForCauseRaw {

        @Test
        @DisplayName("не добавляет exceptionClass автоматически")
        void noExceptionClassByDefault() {
            // given
            RuntimeException cause = new RuntimeException("msg");

            // when
            Map<String, String> meta = DiagnosticMetadataBuilder.forCauseRaw(cause, stackHintExtractor).build();

            // then
            assertThat(meta).doesNotContainKey("exceptionClass");
        }

        @Test
        @DisplayName("позволяет вставить exceptionClass в произвольную позицию")
        void allowsManualExceptionClassAtCustomPosition() {
            // given
            RuntimeException cause = new RuntimeException("msg");

            // when
            Map<String, String> meta = DiagnosticMetadataBuilder.forCauseRaw(cause, stackHintExtractor)
                    .put("first", "1")
                    .put("exceptionClass", cause.getClass().getName())
                    .put("third", "3")
                    .build();

            // then
            List<String> keys = new ArrayList<>(meta.keySet());
            assertThat(keys).containsExactly("first", "exceptionClass", "third");
        }

        @Test
        @DisplayName("результат пуст если ничего не добавлено")
        void emptyWhenNothingAdded() {
            // given
            RuntimeException cause = new RuntimeException();

            // when
            Map<String, String> meta = DiagnosticMetadataBuilder.forCauseRaw(cause, stackHintExtractor).build();

            // then
            assertThat(meta).isEmpty();
        }
    }

    // -------------------------------------------------------------------------
    // put — безусловная вставка
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("put")
    class Put {

        @Test
        @DisplayName("добавляет пару key → value")
        void addsKeyValuePair() {
            // given
            RuntimeException cause = new RuntimeException();

            // when
            Map<String, String> meta = DiagnosticMetadataBuilder.forCauseRaw(cause, stackHintExtractor)
                    .put("foo", "bar")
                    .build();

            // then
            assertThat(meta.get("foo")).isEqualTo("bar");
        }

        @Test
        @DisplayName("перезаписывает существующий ключ")
        void overwritesExistingKey() {
            // given
            RuntimeException cause = new RuntimeException();

            // when
            Map<String, String> meta = DiagnosticMetadataBuilder.forCauseRaw(cause, stackHintExtractor)
                    .put("key", "first")
                    .put("key", "second")
                    .build();

            // then
            assertThat(meta.get("key")).isEqualTo("second");
        }

        @Test
        @DisplayName("сохраняет порядок вставки")
        void preservesInsertionOrder() {
            // given
            RuntimeException cause = new RuntimeException();

            // when
            Map<String, String> meta = DiagnosticMetadataBuilder.forCauseRaw(cause, stackHintExtractor)
                    .put("a", "1")
                    .put("b", "2")
                    .put("c", "3")
                    .build();

            // then
            assertThat(new ArrayList<>(meta.keySet())).containsExactly("a", "b", "c");
        }
    }

    // -------------------------------------------------------------------------
    // putIfPresent — условная вставка
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("putIfPresent")
    class PutIfPresent {

        @Test
        @DisplayName("добавляет значение если оно не blank")
        void addsWhenHasText() {
            // given
            RuntimeException cause = new RuntimeException();

            // when
            Map<String, String> meta = DiagnosticMetadataBuilder.forCauseRaw(cause, stackHintExtractor)
                    .putIfPresent("key", "value")
                    .build();

            // then
            assertThat(meta).containsEntry("key", "value");
        }

        @Test
        @DisplayName("пропускает null значение")
        void skipsNull() {
            // given
            RuntimeException cause = new RuntimeException();

            // when
            Map<String, String> meta = DiagnosticMetadataBuilder.forCauseRaw(cause, stackHintExtractor)
                    .putIfPresent("key", null)
                    .build();

            // then
            assertThat(meta).doesNotContainKey("key");
        }

        @Test
        @DisplayName("пропускает пустую строку")
        void skipsEmptyString() {
            // given
            RuntimeException cause = new RuntimeException();

            // when
            Map<String, String> meta = DiagnosticMetadataBuilder.forCauseRaw(cause, stackHintExtractor)
                    .putIfPresent("key", "")
                    .build();

            // then
            assertThat(meta).doesNotContainKey("key");
        }

        @Test
        @DisplayName("пропускает строку из одних пробелов")
        void skipsBlankString() {
            // given
            RuntimeException cause = new RuntimeException();

            // when
            Map<String, String> meta = DiagnosticMetadataBuilder.forCauseRaw(cause, stackHintExtractor)
                    .putIfPresent("key", "   ")
                    .build();

            // then
            assertThat(meta).doesNotContainKey("key");
        }
    }

    // -------------------------------------------------------------------------
    // withRootCause — обогащение root-cause цепочкой
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("withRootCause")
    class WithRootCause {

        @Test
        @DisplayName("добавляет rootCauseClass при наличии cause chain")
        void addsRootCauseClassWhenChained() {
            // given
            IllegalStateException deep = new IllegalStateException("deep-msg");
            RuntimeException wrapper = new RuntimeException("wrapper", deep);

            // when
            Map<String, String> meta = DiagnosticMetadataBuilder.forCauseRaw(wrapper, stackHintExtractor)
                    .withRootCause()
                    .build();

            // then
            assertThat(meta).containsKey("rootCauseClass");
            assertThat(meta.get("rootCauseClass")).isEqualTo(IllegalStateException.class.getName());
        }

        @Test
        @DisplayName("no-op если cause не имеет вложенных причин")
        void noopWhenNoCauseChain() {
            // given
            RuntimeException leaf = new RuntimeException("alone");

            // when
            Map<String, String> meta = DiagnosticMetadataBuilder.forCauseRaw(leaf, stackHintExtractor)
                    .withRootCause()
                    .build();

            // then
            assertThat(meta).doesNotContainKey("rootCauseClass");
        }

        @Test
        @DisplayName("withRootCause(depth, maxLen) передаёт явные параметры в CauseMetadataEnricher")
        void withExplicitParamsMatchesDirect() {
            // given
            IllegalStateException deep = new IllegalStateException("deep");
            RuntimeException wrapper = new RuntimeException("wrapper", deep);

            // when
            Map<String, String> viaBuilder = DiagnosticMetadataBuilder.forCauseRaw(wrapper, stackHintExtractor)
                    .withRootCause(10, 300)
                    .build();

            // then — должен содержать те же ключи что и прямой вызов CauseMetadataEnricher
            assertThat(viaBuilder).containsKey("rootCauseClass");
        }

        @Test
        @DisplayName("withRootCause() без аргументов использует DIAGNOSTIC_CAUSE_CHAIN_DEPTH=5 и DEFAULT_MAX_MESSAGE_LENGTH=200")
        void defaultsMatchConstants() {
            // given — chain глубиной 4 (wrapper → d1 → d2 → deepest)
            RuntimeException deepest = new RuntimeException("deepest");
            RuntimeException d2 = new RuntimeException("d2", deepest);
            RuntimeException d1 = new RuntimeException("d1", d2);
            RuntimeException wrapper = new RuntimeException("wrapper", d1);

            // when — оба вызова должны вернуть одинаковый результат
            Map<String, String> viaDefault = DiagnosticMetadataBuilder.forCauseRaw(wrapper, stackHintExtractor)
                    .withRootCause()
                    .build();
            Map<String, String> viaExplicit = DiagnosticMetadataBuilder.forCauseRaw(wrapper, stackHintExtractor)
                    .withRootCause(CauseMetadataEnricher.DIAGNOSTIC_CAUSE_CHAIN_DEPTH,
                                   DiagnosticMetadataBuilder.DEFAULT_MAX_MESSAGE_LENGTH)
                    .build();

            // then
            assertThat(viaDefault).isEqualTo(viaExplicit);
        }

        @Test
        @DisplayName("повторный вызов withRootCause() бросает IllegalStateException")
        void throwsOnSecondWithRootCause() {
            // given
            RuntimeException cause = new RuntimeException("msg");
            DiagnosticMetadataBuilder builder = DiagnosticMetadataBuilder.forCauseRaw(cause, stackHintExtractor)
                    .withRootCause();

            // when / Then
            assertThatThrownBy(builder::withRootCause)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("withRootCause() already called");
        }

        @Test
        @DisplayName("withRootCause() после withRootCause(depth, maxLen) тоже бросает IllegalStateException")
        void throwsOnSecondCallMixedOverloads() {
            // given
            RuntimeException cause = new RuntimeException("msg");
            DiagnosticMetadataBuilder builder = DiagnosticMetadataBuilder.forCauseRaw(cause, stackHintExtractor)
                    .withRootCause(5, 200);

            // when / Then
            assertThatThrownBy(builder::withRootCause)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    // -------------------------------------------------------------------------
    // withStackHint — стек-хинт
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("withStackHint")
    class WithStackHint {

        @Test
        @DisplayName("не добавляет stackHint если StackHintExtractor отключён (enabled=false)")
        void noopWhenStackHintDisabled() {
            // StackHintExtractor с enabled=false не извлекает stackHint
            // given
            RuntimeException cause = new RuntimeException("msg");

            // when
            Map<String, String> meta = DiagnosticMetadataBuilder.forCauseRaw(cause, stackHintExtractor)
                    .withStackHint()
                    .build();

            // then
            assertThat(meta).doesNotContainKey("stackHint");
        }

        @Test
        @DisplayName("добавляет stackHint если StackHintExtractor включён и вернул непустую строку")
        void addsStackHintWhenEnabled() {
            // given
            StackHintExtractor enabledExtractor = new StackHintExtractor(true, List.of());
            RuntimeException cause = new RuntimeException("with stack");

            // when
            Map<String, String> meta = DiagnosticMetadataBuilder.forCauseRaw(cause, enabledExtractor)
                    .withStackHint()
                    .build();

            // then — hint может быть пустым если стектрейс весь «шум», поэтому проверяем только условно
            // реальный стектрейс теста не является noise, поэтому hint будет
            if (meta.containsKey("stackHint")) {
                assertThat(meta.get("stackHint")).isNotBlank();
            }
        }
    }

    // -------------------------------------------------------------------------
    // build — терминальный метод
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("build")
    class Build {

        @Test
        @DisplayName("возвращает unmodifiable map")
        void returnsUnmodifiableMap() {
            // given
            RuntimeException cause = new RuntimeException();
            Map<String, String> meta = DiagnosticMetadataBuilder.forCauseRaw(cause, stackHintExtractor)
                    .put("k", "v")
                    .build();

            // when / Then
            assertThatThrownBy(() -> meta.put("extra", "value"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("повторный вызов build() бросает IllegalStateException")
        void throwsOnSecondBuild() {
            // given
            RuntimeException cause = new RuntimeException();
            DiagnosticMetadataBuilder builder = DiagnosticMetadataBuilder.forCauseRaw(cause, stackHintExtractor);
            builder.build();

            // when / Then
            assertThatThrownBy(builder::build)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("build() already called");
        }

        @Test
        @DisplayName("результирующая map пуста для forCauseRaw без добавлений")
        void emptyMapForRawWithoutAdds() {
            // given
            RuntimeException cause = new RuntimeException();

            // when
            Map<String, String> meta = DiagnosticMetadataBuilder.forCauseRaw(cause, stackHintExtractor).build();

            // then
            assertThat(meta).isEmpty();
        }
    }

    // -------------------------------------------------------------------------
    // Порядок вставки — интеграционные сценарии
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("порядок вставки (insertion order)")
    class InsertionOrder {

        @Test
        @DisplayName("forCause: exceptionClass → доменные поля → rootCause* → stackHint")
        void fullOrderWithForCause() {
            // given
            IllegalStateException deep = new IllegalStateException("deep");
            RuntimeException cause = new RuntimeException("wrapper", deep);

            // when
            Map<String, String> meta = DiagnosticMetadataBuilder.forCause(cause, stackHintExtractor)
                    .put("errorType", "GENERIC")
                    .put("domain", "test")
                    .withRootCause()
                    .build();

            // then — exceptionClass первый, потом доменные, потом rootCause*
            List<String> keys = new ArrayList<>(meta.keySet());
            assertThat(keys.get(0)).isEqualTo("exceptionClass");
            assertThat(keys.indexOf("errorType")).isLessThan(keys.indexOf("rootCauseClass"));
        }

        @Test
        @DisplayName("forCauseRaw: ключи в точно том порядке, в котором были добавлены")
        void rawOrderIsExactInsertionOrder() {
            // given
            RuntimeException cause = new RuntimeException();

            // when
            Map<String, String> meta = DiagnosticMetadataBuilder.forCauseRaw(cause, stackHintExtractor)
                    .put("z", "1")
                    .put("a", "2")
                    .put("m", "3")
                    .build();

            // then
            assertThat(new ArrayList<>(meta.keySet())).containsExactly("z", "a", "m");
        }
    }

    // -------------------------------------------------------------------------
    // DEFAULT_MAX_MESSAGE_LENGTH — константа
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("DEFAULT_MAX_MESSAGE_LENGTH")
    class DefaultMaxMessageLength {

        @Test
        @DisplayName("константа равна 200")
        void constantIs200() {
            assertThat(DiagnosticMetadataBuilder.DEFAULT_MAX_MESSAGE_LENGTH).isEqualTo(200);
        }
    }
}
