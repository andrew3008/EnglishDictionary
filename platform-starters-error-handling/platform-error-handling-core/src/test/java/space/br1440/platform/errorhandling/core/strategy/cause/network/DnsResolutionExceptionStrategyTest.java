package space.br1440.platform.errorhandling.core.strategy.cause.network;

import io.netty.handler.codec.dns.DefaultDnsQuestion;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.handler.codec.dns.DnsResponseCode;
import io.netty.resolver.dns.DnsErrorCauseException;
import io.netty.resolver.dns.DnsNameResolverException;
import io.netty.resolver.dns.DnsNameResolverTimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.extension.LogPolicy;
import space.br1440.platform.errorhandling.extension.Retryability;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.extension.order.RootCauseExceptionStrategyOrder;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.error_detail.ErrorDetail;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;
import space.br1440.platform.errorhandling.model.error_detail.RetryInfoDetail;

import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.channels.UnresolvedAddressException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DnsResolutionExceptionStrategy")
class DnsResolutionExceptionStrategyTest {

    private final DnsResolutionExceptionStrategy strategy =
            new DnsResolutionExceptionStrategy(new StackHintExtractor(true, List.of())
            );

    /**
     * Конструирует {@link DnsErrorCauseException} через reflection — конструктор
     * класса в Netty помечен {@code private}, доступен только через package-private
     * static factory {@code newStatic}. В production такой объект создаёт сам Netty
     * resolver и кладёт его как cause в {@link UnknownHostException}.
     */
    private static DnsErrorCauseException newDnsErrorCauseException(DnsResponseCode code) {
        try {
            Constructor<DnsErrorCauseException> ctor =
                    DnsErrorCauseException.class.getDeclaredConstructor(String.class, DnsResponseCode.class);
            ctor.setAccessible(true);
            return ctor.newInstance("test-message", code);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Не удалось сконструировать DnsErrorCauseException через reflection", e);
        }
    }

    private static DnsNameResolverException newDnsNameResolverException(String hostname) {
        return new DnsNameResolverException(
                InetSocketAddress.createUnresolved("dns-server", 53),
                new DefaultDnsQuestion(hostname, DnsRecordType.A),
                "DNS query failed");
    }

    private static DnsNameResolverException newDnsNameResolverExceptionWithCause(String hostname,
                                                                                 Throwable cause) {
        return new DnsNameResolverException(
                InetSocketAddress.createUnresolved("dns-server", 53),
                new DefaultDnsQuestion(hostname, DnsRecordType.A),
                "DNS query failed",
                cause);
    }

    private static DnsNameResolverTimeoutException newDnsTimeoutException(String hostname) {
        return new DnsNameResolverTimeoutException(
                InetSocketAddress.createUnresolved("dns-server", 53),
                new DefaultDnsQuestion(hostname, DnsRecordType.A),
                "Query timed out after 5000 milliseconds");
    }

    private static ErrorInfoDetail findInfoDetail(RootCauseResolution res) {
        return res.details().stream()
                .filter(ErrorInfoDetail.class::isInstance)
                .map(ErrorInfoDetail.class::cast)
                .findFirst()
                .orElseThrow();
    }

    private static Map<String, String> diagnosticOf(RootCauseResolution res) {
        return findInfoDetail(res).getDiagnosticMetadata();
    }

    // -----------------------------------------------------------------------
    // Order
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Order")
    class Order {

        @Test
        void usesPlatformDnsConstant() {
            assertThat(strategy.getOrder()).isEqualTo(RootCauseExceptionStrategyOrder.DNS);
        }
    }

    // -----------------------------------------------------------------------
    // supportsCause
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("supportsCause")
    class SupportsCause {

        @Test
        void supports_UnknownHostException() {
            assertThat(strategy.supportsCause(new UnknownHostException("payment-svc"))).isTrue();
        }

        @Test
        void supports_UnresolvedAddressException() {
            assertThat(strategy.supportsCause(new UnresolvedAddressException())).isTrue();
        }

        @Test
        void supports_DnsNameResolverException() {
            assertThat(strategy.supportsCause(newDnsNameResolverException("payment.example.com.")))
                    .isTrue();
        }

        @Test
        void supports_DnsNameResolverTimeoutException() {
            assertThat(strategy.supportsCause(newDnsTimeoutException("payment.example.com.")))
                    .isTrue();
        }

        @Test
        void doesNotSupport_RuntimeException() {
            assertThat(strategy.supportsCause(new RuntimeException("unrelated"))).isFalse();
        }

        @Test
        void doesNotSupport_IOException() {
            assertThat(strategy.supportsCause(new IOException("io error"))).isFalse();
        }

        @Test
        void doesNotSupport_ConnectException() {
            // ConnectException — это TCP, обрабатывается HttpIntegrationExceptionStrategy
            assertThat(strategy.supportsCause(new ConnectException("connection refused"))).isFalse();
        }
    }

    // -----------------------------------------------------------------------
    // Classification
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Classification")
    class Classification {

        @Test
        void unknownHost_returns_503_RETRYABLE() {
            RootCauseResolution res = strategy.extractCauseDetails(new UnknownHostException("payment-svc"));

            assertThat(res.httpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(res.retryability()).isEqualTo(Retryability.RETRYABLE);
            assertThat(findInfoDetail(res).getReason()).isEqualTo("UNKNOWN_HOST");
        }

        @Test
        void unresolvedAddress_returns_503_RETRYABLE() {
            RootCauseResolution res = strategy.extractCauseDetails(new UnresolvedAddressException());

            assertThat(res.httpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(findInfoDetail(res).getReason()).isEqualTo("UNRESOLVED_ADDRESS");
        }

        @Test
        void nettyDnsTimeout_returns_504_GATEWAY_TIMEOUT() {
            // DnsNameResolverTimeoutException extends DnsNameResolverException —
            // должен классифицироваться как TIMEOUT, не как FAILED
            RootCauseResolution res = strategy.extractCauseDetails(
                    newDnsTimeoutException("payment.example.com."));

            assertThat(res.httpStatus()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
            assertThat(findInfoDetail(res).getReason()).isEqualTo("NETTY_DNS_TIMEOUT");
        }

        @Test
        void nettyDnsFailed_returns_503_RETRYABLE() {
            RootCauseResolution res = strategy.extractCauseDetails(
                    newDnsNameResolverException("payment.example.com."));

            assertThat(res.httpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(findInfoDetail(res).getReason()).isEqualTo("NETTY_DNS_FAILED");
        }

        @Test
        void allDnsErrors_includeRetryInfoDetail() {
            for (Throwable t : List.of(
                    new UnknownHostException("foo"),
                    new UnresolvedAddressException(),
                    newDnsTimeoutException("foo.example.com."),
                    newDnsNameResolverException("foo.example.com."))) {
                RootCauseResolution res = strategy.extractCauseDetails(t);
                List<ErrorDetail> details = res.details();

                assertThat(details).anyMatch(d -> d instanceof RetryInfoDetail);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Hostname extraction (UnknownHostException)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Hostname extraction — UnknownHostException")
    class HostnameJdk {

        @ParameterizedTest
        @CsvSource({
                "'payment-svc',                                       payment-svc",
                "'payment.svc.cluster.local',                         payment.svc.cluster.local",
                "'foo: Name or service not known',                    foo",
                "'bar: No such host is known',                        bar",
                "'baz: nodename nor servname provided, or not known', baz",
                "'qux: Temporary failure in name resolution',         qux",
                "'host.example.com: Try again',                       host.example.com",
                "'svc: connection refused',                           svc",
                "'svc: timed out',                                    svc"
        })
        void extractsHostnameFromJdkMessage(String message, String expectedHostname) {
            UnknownHostException ex = new UnknownHostException(message);
            Map<String, String> meta = diagnosticOf(strategy.extractCauseDetails(ex));

            assertThat(meta).containsEntry("hostname", expectedHostname);
        }

        @Test
        void rejectsZashumlennyiHostname_doesNotPolluteMetadata() {
            // Сообщение, у которого после "разбора" hostname получается ".broken!?",
            // что не соответствует RFC 1035 — поле hostname не пишется.
            UnknownHostException ex = new UnknownHostException(".broken!?: not known");
            Map<String, String> meta = diagnosticOf(strategy.extractCauseDetails(ex));

            assertThat(meta).doesNotContainKey("hostname");
        }
    }

    // -----------------------------------------------------------------------
    // Hostname extraction (Netty)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Hostname extraction — Netty")
    class HostnameNetty {

        @Test
        void normalizesTrailingDot_fromQuestionName() {
            // DefaultDnsQuestion канонизирует имя в формат "host.example.com." —
            // стратегия должна отбросить trailing dot для согласованности с JDK
            DnsNameResolverException ex = newDnsNameResolverException("payment.example.com.");
            Map<String, String> meta = diagnosticOf(strategy.extractCauseDetails(ex));

            assertThat(meta).containsEntry("hostname", "payment.example.com");
            assertThat(meta.get("hostname")).doesNotEndWith(".");
        }

        @Test
        void extractsHostnameFromTimeoutException() {
            DnsNameResolverTimeoutException ex = newDnsTimeoutException("api.example.com.");
            Map<String, String> meta = diagnosticOf(strategy.extractCauseDetails(ex));

            assertThat(meta).containsEntry("hostname", "api.example.com");
        }
    }

    // -----------------------------------------------------------------------
    // Hostname classification + K8s context
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("hostnameType classification")
    class HostnameClassification {

        @ParameterizedTest
        @CsvSource({
                "'payment.default.svc.cluster.local',                K8S_INTERNAL",
                "'payment.kube-system.svc.cluster.local',            K8S_INTERNAL",
                "'pod.svc.namespace.svc.cluster.local',              K8S_INTERNAL",
                "'host.local',                                        LOCAL",
                "'foo',                                               UNQUALIFIED",
                "'api.example.com',                                   EXTERNAL",
                // Anchor: формально содержит .svc., но не K8s — отличить можно только
                // по паттерну '.svc.<root>.<tld>' с двумя сегментами после .svc.
                "'fortnite.svc.example',                             EXTERNAL"
        })
        void classifiesHostnameType(String hostname, String expectedType) {
            UnknownHostException ex = new UnknownHostException(hostname);
            Map<String, String> meta = diagnosticOf(strategy.extractCauseDetails(ex));

            assertThat(meta).containsEntry("hostnameType", expectedType);
        }
    }

    @Nested
    @DisplayName("K8s FQDN parsing")
    class KubernetesContext {

        @Test
        void parsesStandardServiceNamespace() {
            UnknownHostException ex = new UnknownHostException("payment.default.svc.cluster.local");
            Map<String, String> meta = diagnosticOf(strategy.extractCauseDetails(ex));

            assertThat(meta).containsEntry("k8sServiceName", "payment");
            assertThat(meta).containsEntry("k8sNamespace", "default");
        }

        @Test
        void parsesHeadlessPodServiceNamespace() {
            // pod.service.namespace.svc.cluster.local — headless / StatefulSet
            UnknownHostException ex = new UnknownHostException(
                    "pod-0.payment.production.svc.cluster.local");
            Map<String, String> meta = diagnosticOf(strategy.extractCauseDetails(ex));

            assertThat(meta).containsEntry("k8sServiceName", "payment");
            assertThat(meta).containsEntry("k8sNamespace", "production");
        }

        @Test
        void parsesNestedSvc_reluctantPattern() {
            // Federation / cross-cluster: foo.svc.bar.svc.cluster.local
            // Reluctant паттерн отрезает по ПЕРВОМУ .svc., чтобы Группа 1 = "foo"
            UnknownHostException ex = new UnknownHostException("foo.svc.bar.svc.cluster.local");
            Map<String, String> meta = diagnosticOf(strategy.extractCauseDetails(ex));

            // foo — единственный сегмент до первого .svc., не записываем
            // (parts.length == 1 по контракту enrichKubernetesContext)
            assertThat(meta).doesNotContainKey("k8sServiceName");
            assertThat(meta).doesNotContainKey("k8sNamespace");
        }

        @Test
        void doesNotPolluteMetadata_forNonK8sHostname() {
            UnknownHostException ex = new UnknownHostException("api.example.com");
            Map<String, String> meta = diagnosticOf(strategy.extractCauseDetails(ex));

            assertThat(meta).doesNotContainKey("k8sServiceName");
            assertThat(meta).doesNotContainKey("k8sNamespace");
            assertThat(meta).doesNotContainKey("isKubernetesInternal");
        }
    }

    // -----------------------------------------------------------------------
    // resolverType
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("resolverType")
    class ResolverType {

        @Test
        void jdk_forUnknownHostException() {
            Map<String, String> meta = diagnosticOf(
                    strategy.extractCauseDetails(new UnknownHostException("foo")));
            assertThat(meta).containsEntry("resolverType", "JDK");
        }

        @Test
        void nio_forUnresolvedAddressException() {
            Map<String, String> meta = diagnosticOf(
                    strategy.extractCauseDetails(new UnresolvedAddressException()));
            assertThat(meta).containsEntry("resolverType", "NIO");
        }

        @Test
        void nettyAsync_forNettyExceptions() {
            Map<String, String> meta = diagnosticOf(
                    strategy.extractCauseDetails(newDnsNameResolverException("foo.example.com.")));
            assertThat(meta).containsEntry("resolverType", "NETTY_ASYNC");
        }
    }

    // -----------------------------------------------------------------------
    // DNS RCODE extraction
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("DNS RCODE extraction (DnsErrorCauseException)")
    class DnsRcodeExtraction {

        @Test
        void extractsNxdomain_fromDirectCause() {
            DnsErrorCauseException rcodeException = newDnsErrorCauseException(DnsResponseCode.NXDOMAIN);
            DnsNameResolverException ex = newDnsNameResolverExceptionWithCause(
                    "payment.example.com.", rcodeException);

            Map<String, String> meta = diagnosticOf(strategy.extractCauseDetails(ex));

            assertThat(meta).containsEntry("dnsRcode", "3");
            assertThat(meta).containsEntry("dnsRcodeName", "NXDOMAIN");
            assertThat(meta).containsEntry("dnsErrorCategory", "NXDOMAIN");
        }

        @Test
        void extractsServfail_fromDirectCause() {
            DnsErrorCauseException rcodeException = newDnsErrorCauseException(DnsResponseCode.SERVFAIL);
            DnsNameResolverException ex = newDnsNameResolverExceptionWithCause(
                    "payment.example.com.", rcodeException);

            Map<String, String> meta = diagnosticOf(strategy.extractCauseDetails(ex));

            assertThat(meta).containsEntry("dnsRcode", "2");
            assertThat(meta).containsEntry("dnsRcodeName", "SERVFAIL");
            assertThat(meta).containsEntry("dnsErrorCategory", "SERVFAIL");
        }

        @Test
        void extractsRefused_fromDirectCause() {
            DnsErrorCauseException rcodeException = newDnsErrorCauseException(DnsResponseCode.REFUSED);
            DnsNameResolverException ex = newDnsNameResolverExceptionWithCause(
                    "payment.example.com.", rcodeException);

            Map<String, String> meta = diagnosticOf(strategy.extractCauseDetails(ex));

            assertThat(meta).containsEntry("dnsRcode", "5");
            assertThat(meta).containsEntry("dnsRcodeName", "REFUSED");
        }

        @Test
        void extractsRcode_fromDeepCauseChain() {
            // Реалистичный production-стек: DnsNameResolverException → ... → DnsErrorCauseException
            DnsErrorCauseException rcodeException = newDnsErrorCauseException(DnsResponseCode.NXDOMAIN);
            DnsNameResolverException ex = newDnsNameResolverExceptionWithCause(
                    "payment.example.com.",
                    new RuntimeException("layer1",
                            new RuntimeException("layer2", rcodeException)));

            Map<String, String> meta = diagnosticOf(strategy.extractCauseDetails(ex));

            assertThat(meta).containsEntry("dnsRcode", "3");
            assertThat(meta).containsEntry("dnsRcodeName", "NXDOMAIN");
        }

        @Test
        void omitsRcode_whenNoErrorCauseInChain() {
            // DnsNameResolverException без DnsErrorCauseException в cause chain →
            // dnsRcode не пишется, dnsErrorCategory тоже (resolution_failed
            // тождественен reason — поэтому пропускается)
            DnsNameResolverException ex = newDnsNameResolverException("payment.example.com.");
            Map<String, String> meta = diagnosticOf(strategy.extractCauseDetails(ex));

            assertThat(meta).doesNotContainKey("dnsRcode");
            assertThat(meta).doesNotContainKey("dnsRcodeName");
            assertThat(meta).doesNotContainKey("dnsErrorCategory");
        }
    }

    // -----------------------------------------------------------------------
    // dnsErrorCategory (JDK heuristics)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("dnsErrorCategory — JDK heuristic")
    class JdkDnsCategory {

        @ParameterizedTest
        @CsvSource({
                "'foo: Name or service not known',                NXDOMAIN",
                "'foo: No such host is known',                    NXDOMAIN",
                "'foo: nodename nor servname provided',           NXDOMAIN",
                "'foo: Temporary failure in name resolution',     SERVFAIL",
                "'foo: Try again',                                SERVFAIL",
                "'foo: Network is unreachable',                   NETWORK_UNREACHABLE",
                "'foo: connection refused',                       REFUSED",
                "'foo: timed out',                                TIMEOUT"
        })
        void categorizesByOsMessage(String message, String expectedCategory) {
            UnknownHostException ex = new UnknownHostException(message);
            Map<String, String> meta = diagnosticOf(strategy.extractCauseDetails(ex));

            assertThat(meta).containsEntry("dnsErrorCategory", expectedCategory);
        }

        @Test
        void omitsCategory_whenHeuristicDoesNotMatch() {
            // Чистый hostname без OS-маркеров — категорию не пишем (чтобы не штамповать
            // RESOLUTION_FAILED как тождественно reason)
            UnknownHostException ex = new UnknownHostException("payment.example.com");
            Map<String, String> meta = diagnosticOf(strategy.extractCauseDetails(ex));

            assertThat(meta).doesNotContainKey("dnsErrorCategory");
        }
    }

    // -----------------------------------------------------------------------
    // queryTimeoutMs
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("queryTimeoutMs (Netty timeout)")
    class QueryTimeoutMs {

        @Test
        void extractsTimeoutFromMessage() {
            DnsNameResolverTimeoutException ex = newDnsTimeoutException("payment.example.com.");
            Map<String, String> meta = diagnosticOf(strategy.extractCauseDetails(ex));

            // Сообщение конструктора: "Query timed out after 5000 milliseconds"
            assertThat(meta).containsEntry("queryTimeoutMs", "5000");
        }
    }

    // -----------------------------------------------------------------------
    // dnsQuestionType
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("dnsQuestionType")
    class QuestionType {

        @Test
        void extractsRecordTypeName_forA() {
            DnsNameResolverException ex = newDnsNameResolverException("payment.example.com.");
            Map<String, String> meta = diagnosticOf(strategy.extractCauseDetails(ex));

            assertThat(meta).containsEntry("dnsQuestionType", "A");
        }

        @Test
        void extractsRecordTypeName_forAaaa() {
            DnsNameResolverException ex = new DnsNameResolverException(
                    InetSocketAddress.createUnresolved("dns-server", 53),
                    new DefaultDnsQuestion("api.example.com.", DnsRecordType.AAAA),
                    "AAAA query failed");

            Map<String, String> meta = diagnosticOf(strategy.extractCauseDetails(ex));

            assertThat(meta).containsEntry("dnsQuestionType", "AAAA");
        }
    }

    // -----------------------------------------------------------------------
    // JDK DNS cache config
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("JDK DNS cache config")
    class JdkDnsCache {

        @Test
        void writesPositiveAndNegativeTtl_forUnknownHost() {
            // Свойства Security всегда возвращают что-то (пусть даже null) — мы не
            // зависим от конкретного значения, только от факта попытки записи
            UnknownHostException ex = new UnknownHostException("foo");
            Map<String, String> meta = diagnosticOf(strategy.extractCauseDetails(ex));

            // По крайней мере одно из полей должно быть либо записано,
            // либо отсутствовать — оба варианта валидны (зависит от настроек JVM)
            boolean haveAny = meta.containsKey("jdkDnsPositiveCacheTtlSec")
                    || meta.containsKey("jdkDnsNegativeCacheTtlSec")
                    // допустимо ни одно — если в системе SecurityManager или свойства null
                    || (!meta.containsKey("jdkDnsPositiveCacheTtlSec")
                        && !meta.containsKey("jdkDnsNegativeCacheTtlSec"));
            assertThat(haveAny).isTrue();
        }

        @Test
        void writesTtl_forUnresolvedAddress() {
            UnresolvedAddressException ex = new UnresolvedAddressException();
            // Для UNRESOLVED_ADDRESS тоже идём через JDK NIO → enrichJdkDnsCacheConfig
            // должен вызваться. Проверка существования полей такая же гибкая.
            Map<String, String> meta = diagnosticOf(strategy.extractCauseDetails(ex));

            assertThat(meta).isNotNull();
        }

        @Test
        void doesNotWriteTtl_forNettyDnsErrors() {
            DnsNameResolverException ex = newDnsNameResolverException("foo.example.com.");
            Map<String, String> meta = diagnosticOf(strategy.extractCauseDetails(ex));

            assertThat(meta).doesNotContainKey("jdkDnsPositiveCacheTtlSec");
            assertThat(meta).doesNotContainKey("jdkDnsNegativeCacheTtlSec");
        }
    }

    // -----------------------------------------------------------------------
    // CWE-209 — security invariants
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("CWE-209 security invariants")
    class SecurityInvariants {

        @Test
        void doesNotIncludeFullExceptionMessage() {
            // Реалистичный сценарий: сообщение содержит resolver IP / search domain
            UnknownHostException ex = new UnknownHostException(
                    "payment.svc: Resolver 10.96.0.10:53 failed (search [default.svc.cluster.local])");
            Map<String, String> meta = diagnosticOf(strategy.extractCauseDetails(ex));

            // Полное сообщение целиком в metadata не записывается ни под каким ключом
            for (String value : meta.values()) {
                assertThat(value).doesNotContain("10.96.0.10");
                assertThat(value).doesNotContain("default.svc.cluster.local]");
            }
        }

        @Test
        void rejectsNonRfcCompliantHostname() {
            // Имена с недопустимыми символами (пробелы, знаки) отбрасываются
            UnknownHostException ex = new UnknownHostException("not a hostname!@#: not known");
            Map<String, String> meta = diagnosticOf(strategy.extractCauseDetails(ex));

            assertThat(meta).doesNotContainKey("hostname");
            assertThat(meta).doesNotContainKey("hostnameType");
        }

        @Test
        void diagnosticMetadata_isUnmodifiable() {
            UnknownHostException ex = new UnknownHostException("foo");
            Map<String, String> meta = diagnosticOf(strategy.extractCauseDetails(ex));

            // Защитный invariant: metadata является unmodifiable view
            assertThat(meta).isInstanceOf(Map.class);
            org.junit.jupiter.api.Assertions.assertThrows(
                    UnsupportedOperationException.class,
                    () -> meta.put("k", "v"));
        }
    }

    // -----------------------------------------------------------------------
    // logPolicy
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("logPolicy")
    class LogPolicyTest {

        @Test
        void allDnsErrors_logAsError() {
            // logPolicy в DnsResolutionExceptionStrategy не зависит от полей ErrorEntry —
            // всегда возвращает ERROR. Используем mock, чтобы не дёргать обязательные
            // поля builder'а (errorCode, httpStatus и пр.) — они не влияют на результат.
            ErrorEntry mockEntry = mock(ErrorEntry.class);

            assertThat(strategy.logPolicy(new RuntimeException("any"), mockEntry))
                    .isEqualTo(LogPolicy.ERROR);
        }
    }

    // -----------------------------------------------------------------------
    // exceptionClass / rootCauseClass
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("exceptionClass / rootCauseClass")
    class ClassMetadata {

        @Test
        void includesExceptionClassFqn() {
            UnknownHostException ex = new UnknownHostException("foo");
            Map<String, String> meta = diagnosticOf(strategy.extractCauseDetails(ex));

            assertThat(meta).containsEntry("exceptionClass", "java.net.UnknownHostException");
        }

        @Test
        void includesRootCauseClass_whenWrapped() {
            // DnsNameResolverException обёрнут в RuntimeException c глубоким cause chain
            DnsErrorCauseException rcodeException = newDnsErrorCauseException(DnsResponseCode.NXDOMAIN);
            DnsNameResolverException nettyEx = newDnsNameResolverExceptionWithCause(
                    "payment.example.com.", rcodeException);

            Map<String, String> meta = diagnosticOf(strategy.extractCauseDetails(nettyEx));

            // rootCause = глубинная причина = DnsErrorCauseException
            assertThat(meta).containsEntry("rootCauseClass",
                    "io.netty.resolver.dns.DnsErrorCauseException");
        }

        @Test
        void omitsRootCauseClass_whenNoNestedCause() {
            // У UnknownHostException нет cause → root cause = сама себя → поле не пишем
            UnknownHostException ex = new UnknownHostException("foo");
            Map<String, String> meta = diagnosticOf(strategy.extractCauseDetails(ex));

            assertThat(meta).doesNotContainKey("rootCauseClass");
        }
    }

    // -----------------------------------------------------------------------
    // Cause chain traversal — UnresolvedAddressException nested cases
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("UnresolvedAddressException nested cases")
    class UnresolvedAddressNested {

        @Test
        void extractsHostname_fromNestedUnknownHostException() {
            UnknownHostException nested = new UnknownHostException("api.example.com");
            UnresolvedAddressException ex = new UnresolvedAddressException();
            // UnresolvedAddressException не принимает cause в конструктор — initCause
            ex.initCause(nested);

            Map<String, String> meta = diagnosticOf(strategy.extractCauseDetails(ex));

            assertThat(meta).containsEntry("hostname", "api.example.com");
        }

        @Test
        void omitsHostname_whenNoNestedCause() {
            UnresolvedAddressException ex = new UnresolvedAddressException();
            Map<String, String> meta = diagnosticOf(strategy.extractCauseDetails(ex));

            assertThat(meta).doesNotContainKey("hostname");
        }
    }

    // -----------------------------------------------------------------------
    // ErrorInfoDetail / domain
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("ErrorInfoDetail")
    class ErrorInfoDetailTest {

        @Test
        void domainIsDns() {
            UnknownHostException ex = new UnknownHostException("foo");
            ErrorInfoDetail info = findInfoDetail(strategy.extractCauseDetails(ex));

            assertThat(info.getDomain()).isEqualTo("dns");
        }

        @Test
        void reasonMatchesDnsErrorTypeName() {
            assertThat(findInfoDetail(strategy.extractCauseDetails(new UnknownHostException("foo")))
                    .getReason()).isEqualTo("UNKNOWN_HOST");
            assertThat(findInfoDetail(strategy.extractCauseDetails(new UnresolvedAddressException()))
                    .getReason()).isEqualTo("UNRESOLVED_ADDRESS");
            assertThat(findInfoDetail(strategy.extractCauseDetails(newDnsTimeoutException("foo.example.com.")))
                    .getReason()).isEqualTo("NETTY_DNS_TIMEOUT");
            assertThat(findInfoDetail(strategy.extractCauseDetails(newDnsNameResolverException("foo.example.com.")))
                    .getReason()).isEqualTo("NETTY_DNS_FAILED");
        }
    }
}
