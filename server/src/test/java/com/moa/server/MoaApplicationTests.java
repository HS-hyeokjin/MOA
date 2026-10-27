package com.moa.server;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class MoaApplicationTests {

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
        mvc.perform(get("/api/v1/photos")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/games")).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/env")).andExpect(status().isForbidden());
    }

}
