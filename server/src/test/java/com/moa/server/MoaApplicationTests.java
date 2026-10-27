package com.moa.server;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.nio.file.Path;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class MoaApplicationTests {
    private static final Path DATA = Path.of("build/test-data", UUID.randomUUID().toString()).toAbsolutePath();
    @DynamicPropertySource
    static void testData(DynamicPropertyRegistry registry) {
        registry.add("moa.data-directory", DATA::toString);
    }

    @Autowired
    private MockMvc mvc;

    @Test
    void healthIsAvailableWithoutLogin() throws Exception {
        mvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.application").value("MOA"))
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void actuatorHealthDoesNotExposeDetails() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    void familyDataCannotBeAccessedBeforeAuthenticationIsImplemented() throws Exception {
        mvc.perform(get("/api/v1/posts")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/games")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
    }

}
