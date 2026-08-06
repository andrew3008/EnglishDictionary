package space.br1440.platform.tracing.autoconfigure.control.jmx.protocol;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.management.openmbean.ArrayType;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenDataException;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;
import javax.management.openmbean.TabularType;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Independent code-to-golden schema gate (Commit 1 из плана {@code wire-field-descriptors}).
 * <p>
 * Читает два нормативных committed golden JSON и независимо, без обращения к
 * {@code WireField}, к {@code CompositeType.keySet()} чужой production-копии или к любому
 * общему Java helper, восстанавливает ожидаемую Open-Type схему только из текста golden-файлов.
 * Затем сравнивает обе production-копии {@code PrivateControlWireTypes} (Spring и Agent) с этой
 * восстановленной схемой и друг с другом.
 * <p>
 * Этот тест пишется и запускается на clean checkout Commit 0, до введения дескрипторов
 * (см. план, §1.1 и §9.4). Он не должен меняться в Commit 2 и Commit 3 — иначе оракул
 * превращается в подгонку под факт, а не в независимое доказательство неизменности схемы.
 * <p>
 * Обнаруживает три независимых класса расхождения:
 * <ul>
 *   <li>Spring и Agent расходятся между собой;</li>
 *   <li>Spring и Agent совпадают друг с другом, но совместно расходятся с golden;</li>
 *   <li>golden описывает поле или тип, отсутствующий в любой production-схеме.</li>
 * </ul>
 */
class PrivateControlWireSchemaGoldenTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final File REPO_ROOT = locateRepositoryRoot();

    private static final Class<?> AGENT_WIRE_TYPES = loadAgentWireTypes();

    // ── §9.4, bullets 1-4: Spring и Agent против p0-wire-schema-contract.json ──────────────

    @Test
    void springRouteRowMatchesP0Contract() throws IOException {
        JsonNode routeRatios = p0Contract().path("routeRatios");
        CompositeType expected = expectedRouteRow(routeRatios);

        assertThat(PrivateControlWireTypes.ROUTE_ROW).isEqualTo(expected);
        assertThat(PrivateControlWireTypes.ROUTE_ROW.getTypeName())
                .isEqualTo("space.br1440.platform.tracing.control.RouteRatio.v1");
    }

    @Test
    void agentRouteRowMatchesP0Contract() throws IOException {
        JsonNode routeRatios = p0Contract().path("routeRatios");
        CompositeType expected = expectedRouteRow(routeRatios);

        assertThat(agentField("ROUTE_ROW", CompositeType.class)).isEqualTo(expected);
    }

    @Test
    void springRoutesTableMatchesP0Contract() throws IOException {
        JsonNode routeRatios = p0Contract().path("routeRatios");
        TabularType expected = expectedRoutes(routeRatios);

        assertThat(PrivateControlWireTypes.ROUTES).isEqualTo(expected);
        assertThat(PrivateControlWireTypes.ROUTES.getIndexNames()).containsExactly("routePrefix");
    }

    @Test
    void agentRoutesTableMatchesP0Contract() throws IOException {
        JsonNode routeRatios = p0Contract().path("routeRatios");
        TabularType expected = expectedRoutes(routeRatios);

        assertThat(agentField("ROUTES", TabularType.class)).isEqualTo(expected);
        assertThat(agentField("ROUTES", TabularType.class).getIndexNames()).containsExactly("routePrefix");
    }

    @Test
    void springRuntimePolicyMatchesP0Contract() throws IOException {
        JsonNode contract = p0Contract();
        TabularType routes = expectedRoutes(contract.path("routeRatios"));
        CompositeType expected = expectedComposite(
                "space.br1440.platform.tracing.control.RuntimePolicy.v1",
                contract.path("runtimePolicy").path("items"),
                Map.of("samplingRouteRatios", routes));

        assertThat(PrivateControlWireTypes.POLICY).isEqualTo(expected);
        assertThat(contract.path("runtimePolicy").path("leafCount").asInt()).isEqualTo(8);
    }

    @Test
    void agentRuntimePolicyMatchesP0Contract() throws IOException {
        JsonNode contract = p0Contract();
        TabularType routes = expectedRoutes(contract.path("routeRatios"));
        CompositeType expected = expectedComposite(
                "space.br1440.platform.tracing.control.RuntimePolicy.v1",
                contract.path("runtimePolicy").path("items"),
                Map.of("samplingRouteRatios", routes));

        assertThat(agentField("POLICY", CompositeType.class)).isEqualTo(expected);
    }

    @Test
    void springApplyRequestMatchesP0Contract() throws IOException {
        JsonNode contract = p0Contract();
        TabularType routes = expectedRoutes(contract.path("routeRatios"));
        CompositeType policy = expectedComposite(
                "space.br1440.platform.tracing.control.RuntimePolicy.v1",
                contract.path("runtimePolicy").path("items"),
                Map.of("samplingRouteRatios", routes));
        CompositeType expected = expectedComposite(
                contract.path("operation").path("requestType").asText(),
                contract.path("request").path("items"),
                Map.of("runtimePolicy", policy));

        assertThat(PrivateControlWireTypes.REQUEST).isEqualTo(expected);
        assertThat(contract.path("request").path("items").path(0).path("constant").asInt())
                .as("protocolVersion constant")
                .isEqualTo(1);
    }

    @Test
    void agentApplyRequestMatchesP0Contract() throws IOException {
        JsonNode contract = p0Contract();
        TabularType routes = expectedRoutes(contract.path("routeRatios"));
        CompositeType policy = expectedComposite(
                "space.br1440.platform.tracing.control.RuntimePolicy.v1",
                contract.path("runtimePolicy").path("items"),
                Map.of("samplingRouteRatios", routes));
        CompositeType expected = expectedComposite(
                contract.path("operation").path("requestType").asText(),
                contract.path("request").path("items"),
                Map.of("runtimePolicy", policy));

        assertThat(agentField("REQUEST", CompositeType.class)).isEqualTo(expected);
    }

    @Test
    void springApplyResponseMatchesP0Contract() throws IOException {
        JsonNode contract = p0Contract();
        CompositeType expected = expectedComposite(
                contract.path("operation").path("responseType").asText(),
                contract.path("response").path("items"),
                Map.of());

        assertThat(PrivateControlWireTypes.RESPONSE).isEqualTo(expected);
    }

    @Test
    void agentApplyResponseMatchesP0Contract() throws IOException {
        JsonNode contract = p0Contract();
        CompositeType expected = expectedComposite(
                contract.path("operation").path("responseType").asText(),
                contract.path("response").path("items"),
                Map.of());

        assertThat(agentField("RESPONSE", CompositeType.class)).isEqualTo(expected);
    }

    @Test
    void p0ContractVersionIsExplicitlyPinned() throws IOException {
        assertThat(p0Contract().path("contractVersion").asInt()).isEqualTo(1);
    }

    // ── §9.4: Spring и Agent против decision-summary.json (AcceptedCompleteSnapshot/v1) ────

    @Test
    void springAcceptedCompleteMatchesDecisionSummary() throws IOException {
        JsonNode wireSchema = decisionSummary().path("wireSchema");
        CompositeType expected = expectedAcceptedComplete(wireSchema);

        assertThat(PrivateControlWireTypes.COMPLETE_RESPONSE).isEqualTo(expected);
        assertThat(PrivateControlWireTypes.COMPLETE_RESPONSE.getTypeName())
                .isEqualTo("AcceptedCompleteSnapshot/v1");
    }

    @Test
    void agentAcceptedCompleteMatchesDecisionSummary() throws IOException {
        JsonNode wireSchema = decisionSummary().path("wireSchema");
        CompositeType expected = expectedAcceptedComplete(wireSchema);

        assertThat(agentField("ACCEPTED_COMPLETE", CompositeType.class)).isEqualTo(expected);
    }

    @Test
    void decisionSummaryFieldCountMatchesSchema() throws IOException {
        assertThat(decisionSummary().path("wireSchema").path("fieldCount").asInt()).isEqualTo(10);
    }

    // ── §9.4, failure case 1: Spring и Agent должны совпадать друг с другом напрямую ───────

    @Test
    void springAndAgentOpenTypesAreMutuallyEqual() {
        assertThat(PrivateControlWireTypes.ROUTE_ROW).isEqualTo(agentField("ROUTE_ROW", CompositeType.class));
        assertThat(PrivateControlWireTypes.ROUTES).isEqualTo(agentField("ROUTES", TabularType.class));
        assertThat(PrivateControlWireTypes.POLICY).isEqualTo(agentField("POLICY", CompositeType.class));
        assertThat(PrivateControlWireTypes.REQUEST).isEqualTo(agentField("REQUEST", CompositeType.class));
        assertThat(PrivateControlWireTypes.RESPONSE).isEqualTo(agentField("RESPONSE", CompositeType.class));
        assertThat(PrivateControlWireTypes.COMPLETE_RESPONSE)
                .isEqualTo(agentField("ACCEPTED_COMPLETE", CompositeType.class));
    }

    // ── golden JSON access ───────────────────────────────────────────────────────────────

    private static JsonNode p0Contract() throws IOException {
        return MAPPER.readTree(new File(REPO_ROOT,
                "docs/research/evidence/implementation-readiness/p0-wire-schema-contract.json"));
    }

    private static JsonNode decisionSummary() throws IOException {
        return MAPPER.readTree(new File(REPO_ROOT,
                "docs/research/evidence/priv-r07-proposal/decision-summary.json"));
    }

    private static File locateRepositoryRoot() {
        // Тот же устойчивый self-then-parent способ, которым уже пользуется
        // E8ImplementationReadinessEvidenceTest — рабочая директория gradle-теста равна
        // директории модуля, поэтому repo root находится либо там же, либо на уровень выше.
        File configured = new File(System.getProperty("wire.golden.repo.root", "."))
                .getAbsoluteFile()
                .toPath()
                .normalize()
                .toFile();
        if (new File(configured, "docs/research").isDirectory()) {
            return configured;
        }
        File parent = configured.getParentFile();
        if (parent != null && new File(parent, "docs/research").isDirectory()) {
            return parent;
        }
        return configured;
    }

    // ── expected schema, восстановленная только из golden JSON ─────────────────────────────

    private static CompositeType expectedRouteRow(JsonNode routeRatios) {
        return expectedComposite(
                routeRatios.path("rowType").asText(),
                routeRatios.path("rowItems"),
                Map.of());
    }

    private static TabularType expectedRoutes(JsonNode routeRatios) {
        CompositeType rowType = expectedRouteRow(routeRatios);
        List<String> indexNames = textList(routeRatios.path("indexNames"));
        return tabular(routeRatios.path("typeName").asText(), rowType, indexNames);
    }

    private static CompositeType expectedAcceptedComplete(JsonNode wireSchema) {
        JsonNode rowNode = wireSchema.path("routeRowCompositeType");
        CompositeType rowType = expectedComposite(
                rowNode.path("name").asText(),
                rowNode.path("fields"),
                Map.of());
        JsonNode tableNode = wireSchema.path("routeTabularType");
        TabularType routes = tabular(
                tableNode.path("name").asText(),
                rowType,
                textList(tableNode.path("indexNames")));

        return expectedComposite(
                wireSchema.path("rootCompositeTypeName").asText(),
                wireSchema.path("fields"),
                Map.of("samplingRouteRatios", routes));
    }

    private static CompositeType expectedComposite(String typeName, JsonNode items,
            Map<String, OpenType<?>> nestedOverrides) {
        List<String> names = new ArrayList<>();
        List<OpenType<?>> types = new ArrayList<>();
        for (JsonNode item : items) {
            String name = item.path("name").asText();
            names.add(name);
            OpenType<?> nested = nestedOverrides.get(name);
            types.add(nested != null ? nested : scalarOrArrayOpenType(item.path("openType").asText()));
        }
        try {
            String[] namesArray = names.toArray(new String[0]);
            return new CompositeType(typeName, typeName, namesArray, namesArray,
                    types.toArray(new OpenType<?>[0]));
        } catch (OpenDataException exception) {
            throw new IllegalStateException(
                    "Golden JSON produced an invalid expected CompositeType " + typeName, exception);
        }
    }

    private static TabularType tabular(String typeName, CompositeType rowType, List<String> indexNames) {
        try {
            return new TabularType(typeName, typeName, rowType, indexNames.toArray(new String[0]));
        } catch (OpenDataException exception) {
            throw new IllegalStateException(
                    "Golden JSON produced an invalid expected TabularType " + typeName, exception);
        }
    }

    private static OpenType<?> scalarOrArrayOpenType(String token) {
        return switch (token) {
            case "SimpleType.STRING" -> SimpleType.STRING;
            case "SimpleType.LONG" -> SimpleType.LONG;
            case "SimpleType.BOOLEAN" -> SimpleType.BOOLEAN;
            case "SimpleType.DOUBLE" -> SimpleType.DOUBLE;
            case "SimpleType.INTEGER" -> SimpleType.INTEGER;
            case "ArrayType(SimpleType.STRING)" -> arrayOfStrings();
            default -> throw new IllegalStateException(
                    "Golden JSON declares an openType token unknown to this gate: " + token);
        };
    }

    private static ArrayType<?> arrayOfStrings() {
        try {
            return new ArrayType<>(1, SimpleType.STRING);
        } catch (OpenDataException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static List<String> textList(JsonNode arrayNode) {
        List<String> values = new ArrayList<>();
        for (JsonNode element : arrayNode) {
            values.add(element.asText());
        }
        return values;
    }

    // ── reflective access to the independent Agent-classloader-plane copy ──────────────────

    private static Class<?> loadAgentWireTypes() {
        try {
            return Class.forName(
                    "space.br1440.platform.tracing.otel.javaagent.control.state.PrivateControlWireTypes");
        } catch (ClassNotFoundException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static <T> T agentField(String name, Class<T> type) {
        try {
            Field field = AGENT_WIRE_TYPES.getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(null));
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot read Agent-side field " + name, exception);
        }
    }
}
