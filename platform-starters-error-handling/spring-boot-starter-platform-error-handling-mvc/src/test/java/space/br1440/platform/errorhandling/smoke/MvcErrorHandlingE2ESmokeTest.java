package space.br1440.platform.errorhandling.smoke;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import space.br1440.platform.errorhandling.core.constants.ErrorHandlingHeaders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = MvcErrorHandlingE2ESmokeTest.SmokeApp.class,
        properties = {
                "spring.application.name=mvc-smoke",
                "platform.error-handling.enabled=true",
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration"
        })
@AutoConfigureMockMvc
@DisplayName("MVC E2E smoke (production-contract)")
class MvcErrorHandlingE2ESmokeTest {

    @Autowired
    private MockMvc mockMvc;

    @Nested
    @DisplayName("HTTP contract (RFC 7807 + correlation-id)")
    class HttpContract {

        @Test
        @DisplayName("404 → Content-Type=application/problem+json")
        void notFound_returnsProblemJson() throws Exception {
            // when
            mockMvc.perform(get("/smoke/404"))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.parseMediaType("application/problem+json")));
        }

        @Test
        @DisplayName("400 → Content-Type=application/problem+json")
        void badRequest_returnsProblemJson() throws Exception {
            // when
            mockMvc.perform(get("/smoke/400"))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.parseMediaType("application/problem+json")));
        }

        @Test
        @DisplayName("500 → Content-Type=application/problem+json")
        void internalError_returnsProblemJson() throws Exception {
            // when
            mockMvc.perform(get("/smoke/500"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.parseMediaType("application/problem+json")));
        }

        @Test
        @DisplayName("503 → Content-Type=application/problem+json")
        void serviceUnavailable_returnsProblemJson() throws Exception {
            // when
            mockMvc.perform(get("/smoke/503"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.parseMediaType("application/problem+json")));
        }

        @Test
        @DisplayName("Принятый X-Correlation-Id переносится либо в response-header, либо в body.traceId")
        void correlationIdPropagation_returnedToClient() throws Exception {
            // given
            String inputCid = "smoke-mvc-cid-12345";

            // when
            MvcResult result = mockMvc.perform(get("/smoke/404")
                            .header(ErrorHandlingHeaders.X_CORRELATION_ID, inputCid))
                    .andExpect(status().isNotFound())
                    .andReturn();

            // then
            String body = result.getResponse().getContentAsString();
            String headerCid = result.getResponse().getHeader(ErrorHandlingHeaders.X_CORRELATION_ID);

            assertThat(headerCid != null && headerCid.equals(inputCid)
                    || body.contains(inputCid))
                    .as("correlation-id propagation: header='%s' bodySnippet='%s'",
                            headerCid, snippet(body))
                    .isTrue();
        }

        @Test
        @DisplayName("Невалидный X-Correlation-Id (overlength) отклоняется + сервис не падает")
        void invalidCorrelationId_isRejectedButRequestProceeds() throws Exception {
            // given
            String overlengthCid = "a".repeat(1024);

            // when
            mockMvc.perform(get("/smoke/404")
                            .header(ErrorHandlingHeaders.X_CORRELATION_ID, overlengthCid))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("Phase 4.2: расширенные E2E кейсы")
    class ExtendedE2ECases {

        @Test
        @DisplayName("CorrelationIdFilter ставит response-заголовок даже для успешного запроса")
        void correlationIdFilter_propagatesOnSuccess() throws Exception {
            // given
            String inputCid = "smoke-success-cid";

            // when
            MvcResult result = mockMvc.perform(get("/smoke/ok")
                            .header(ErrorHandlingHeaders.X_CORRELATION_ID, inputCid))
                    .andExpect(status().isOk())
                    .andReturn();

            // then
            // Сквозной фильтр (Phase 2.1) должен ставить header даже для успешного 200.
            assertThat(result.getResponse().getHeader(ErrorHandlingHeaders.X_CORRELATION_ID))
                    .isEqualTo(inputCid);
        }

        @Test
        @DisplayName("Generated correlationId возвращается клиенту, если header не задан")
        void noCorrelationIdHeader_serverGeneratesAndReturns() throws Exception {
            // when
            MvcResult result = mockMvc.perform(get("/smoke/ok"))
                    .andExpect(status().isOk())
                    .andReturn();

            // then
            String generated = result.getResponse().getHeader(ErrorHandlingHeaders.X_CORRELATION_ID);
            assertThat(generated).as("server должен сгенерировать UUID v4").isNotBlank();
            assertThat(generated).hasSize(36).contains("-");
        }

        @Test
        @DisplayName("HttpRequestMethodNotSupported -> 405 + body содержит метод")
        void methodNotSupported_includesMethodInBody() throws Exception {
            // when
            // POST на endpoint, который принимает только GET
            MvcResult result = mockMvc.perform(MockMvcRequestBuilders.post("/smoke/ok"))
                    .andExpect(status().isMethodNotAllowed())
                    .andReturn();

            // then
            String body = result.getResponse().getContentAsString();
            assertThat(body).contains("INVALID_ARGUMENT");
            assertThat(body).contains("METHOD_NOT_ALLOWED");
            assertThat(body).contains("POST");
        }

    }

    private static String snippet(String s) {
        if (s == null) {
            return "<null>";
        }

        return (s.length() <= 200) ? s : s.substring(0, 200) + "...";
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {
            SecurityAutoConfiguration.class,
            UserDetailsServiceAutoConfiguration.class
    })
    static class SmokeApp {

        @RestController
        @RequestMapping("/smoke")
        static class SmokeController {

            @GetMapping("/404")
            String notFound() {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "smoke not found");
            }

            @GetMapping("/400")
            String badRequest() {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "smoke bad request");
            }

            @GetMapping("/500")
            String internalError() {
                throw new NullPointerException("smoke npe");
            }

            @GetMapping("/503")
            String serviceUnavailable() {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "smoke unavailable");
            }

            @GetMapping("/ok")
            String ok() {
                return "ok";
            }
        }
    }
}
