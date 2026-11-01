package com.gdit.fafsa.api;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** End to end through the real Spring context, rules and application.yml, using the spec's sample payloads. */
@SpringBootTest
@AutoConfigureMockMvc
class ValidationApiTest {

    private static final String VALIDATE = "/api/v1/applications/validate";
    private static final String VALIDATE_BATCH = "/api/v1/applications/validate/batch";

    @Autowired
    private MockMvc mvc;

    @Test
    void validSampleIsValidAndEveryRuleIsReported() throws Exception {
        post(VALIDATE, sample("valid-application.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALID"))
                .andExpect(jsonPath("$.results", hasSize(11)))
                .andExpect(jsonPath("$.results[?(@.outcome == 'FAILED')]").isEmpty())
                .andExpect(jsonPath("$.results[?(@.ruleId == 'married-spouse-info')].outcome")
                        .value(contains("NOT_APPLICABLE")));
    }

    @Test
    void invalidSampleFailsExactlyTheSevenRulesTheSpecLists() throws Exception {
        post(VALIDATE, sample("invalid-application.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INVALID"))
                .andExpect(jsonPath("$.results[?(@.outcome == 'FAILED')].ruleId").value(containsInAnyOrder(
                        "student-age", "ssn-format", "dependent-parent-income", "non-negative-income",
                        "household-college-count", "state-code", "married-spouse-info")))
                .andExpect(jsonPath("$.results[2].ruleId").value("student-age"))
                .andExpect(jsonPath("$.results[2].severity").value("ERROR"))
                .andExpect(jsonPath("$.results[2].message").isNotEmpty())
                .andExpect(jsonPath("$.results[2].fields").value(contains("studentInfo.dateOfBirth")));
    }

    @Test
    void batchReturnsOneResultPerApplicationInRequestOrder() throws Exception {
        post(VALIDATE_BATCH, sample("batch.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[*].status").value(contains("VALID", "INVALID")));
    }

    @Test
    void emptyBatchIsRejected() throws Exception {
        post(VALIDATE_BATCH, "{\"applications\": []}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors").value(contains("applications: size must be between 1 and 1000")));
    }

    @Test
    void oversizedBatchIsRejected() throws Exception {
        String application = sample("valid-application.json");
        String applications = String.join(",", Collections.nCopies(1001, application));

        post(VALIDATE_BATCH, "{\"applications\": [" + applications + "]}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingStatusesAreEditFailuresAndEveryOtherRuleStillRuns() throws Exception {
        post(VALIDATE, "{\"stateOfResidence\": \"CA\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INVALID"))
                .andExpect(jsonPath("$.results", hasSize(11)))
                .andExpect(jsonPath("$.results[0].ruleId").value("marital-status"))
                .andExpect(jsonPath("$.results[0].outcome").value("FAILED"))
                .andExpect(jsonPath("$.results[?(@.ruleId == 'married-spouse-info')].outcome")
                        .value(contains("NOT_APPLICABLE")));
    }

    @Test
    void listsTheActiveRulesInPriorityOrder() throws Exception {
        mvc.perform(get("/api/v1/rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(11)))
                .andExpect(jsonPath("$[0].id").value("marital-status"))
                .andExpect(jsonPath("$[0].severity").value("ERROR"))
                .andExpect(jsonPath("$[0].fields").value(contains("maritalStatus")))
                .andExpect(jsonPath("$[0].check").doesNotExist());
    }

    @Test
    void malformedJsonIsRejected() throws Exception {
        post(VALIDATE, "{ not json")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Request body is malformed or has the wrong shape."));
    }

    @Test
    void wrongValueTypeNamesTheField() throws Exception {
        post(VALIDATE, "{\"household\": {\"numberInHousehold\": \"four\"}}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Invalid value for field 'household.numberInHousehold'."));
    }

    @Test
    void unknownEnumValueNamesTheField() throws Exception {
        post(VALIDATE, "{\"dependencyStatus\": \"sometimes\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Invalid value for field 'dependencyStatus'."));
    }

    @Test
    void invalidValueInsideABatchNamesTheIndexedField() throws Exception {
        post(VALIDATE_BATCH, "{\"applications\": [{\"maritalStatus\": 42.5}]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Invalid value for field 'applications[0].maritalStatus'."));
    }

    @Test
    void wrongTopLevelShapeIsRejected() throws Exception {
        post(VALIDATE, "[]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Request body is malformed or has the wrong shape."));
    }

    @Test
    void validationCountsAreExposedAsMetrics() throws Exception {
        post(VALIDATE, sample("invalid-application.json"));

        mvc.perform(get("/actuator/metrics/fafsa.edits.failed").param("tag", "rule:ssn-format"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableTags[*].tag", hasItem("severity")));
    }

    private ResultActions post(String path, String body) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String sample(String name) throws IOException {
        return new ClassPathResource("samples/" + name).getContentAsString(StandardCharsets.UTF_8);
    }
}
