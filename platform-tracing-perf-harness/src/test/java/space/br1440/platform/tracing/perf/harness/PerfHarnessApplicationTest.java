package space.br1440.platform.tracing.perf.harness;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PerfHarnessApplicationTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void perfHealth() throws Exception {
        mockMvc.perform(get("/perf/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void perfFast() throws Exception {
        mockMvc.perform(get("/perf/fast"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value("fast"));
    }

    @Test
    void perfWork() throws Exception {
        mockMvc.perform(get("/perf/work"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value("work"));
    }

    @Test
    void validationValid() throws Exception {
        mockMvc.perform(get("/perf/validation/valid"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value("validation-valid"));
    }

    @Test
    void validationMissing() throws Exception {
        mockMvc.perform(get("/perf/validation/missing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value("validation-missing"));
    }
}
