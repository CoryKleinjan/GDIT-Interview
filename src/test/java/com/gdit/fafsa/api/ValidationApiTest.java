package com.gdit.fafsa.api;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
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
                .andExpect(jsonPath("$.results[0].severity").doesNotExist())
                .andExpect(jsonPath("$.results[0].message").doesNotExist())
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
    void largeBatchKeepsRequestOrderAcrossThreads() throws Exception {
        String valid = sample("valid-application.json");
        String invalid = sample("invalid-application.json");
        var applications = new java.util.ArrayList<String>();
        for (int i = 0; i < 200; i++) {
            applications.add(i % 3 == 0 ? invalid : valid);
        }

        var response = post(VALIDATE_BATCH, "{\"applications\": [" + String.join(",", applications) + "]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results", hasSize(200)));
        for (int i = 0; i < 200; i += 37) {
            response.andExpect(jsonPath("$.results[" + i + "].status").value(i % 3 == 0 ? "INVALID" : "VALID"));
        }
    }

    @Test
    void emptyBatchIsRejected() throws Exception {
        post(VALIDATE_BATCH, "{\"applications\": []}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors").value(contains("applications size must be between 1 and 1000")));
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
    void valuesThatDoNotFitTheirTypeAreAllNamedAtOnce() throws Exception {
        post(VALIDATE, "{\"household\": {\"numberInHousehold\": \"four\"}, \"dependencyStatus\": \"sometimes\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Some fields have values that do not fit their type."))
                .andExpect(jsonPath("$.errors").value(containsInAnyOrder(
                        "household.numberInHousehold must be a whole number",
                        "dependencyStatus must be one of [dependent, independent]")));
    }

    @Test
    void invalidValueInsideABatchNamesTheIndexedField() throws Exception {
        post(VALIDATE_BATCH, "{\"applications\": [{\"maritalStatus\": 42.5}]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors").value(contains(
                        "applications[0].maritalStatus must be one of [single, married]")));
    }

    @Test
    void wrongTopLevelShapeIsRejected() throws Exception {
        post(VALIDATE, "[]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors").value(contains("The application must be a JSON object")));
    }

    @Test
    void batchThatIsNotAnObjectIsRejected() throws Exception {
        post(VALIDATE_BATCH, "[]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Request body is malformed or has the wrong shape."));
    }

    @Test
    void wrongBatchShapeNamesTheField() throws Exception {
        post(VALIDATE_BATCH, "{\"applications\": \"x\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Invalid value for field 'applications'."));
    }

    @Test
    void listsTheApplicationFieldsSoClientsCanBuildForms() throws Exception {
        mvc.perform(get("/api/v1/fields"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.path == 'studentInfo.dateOfBirth')].type").value(contains("DATE")))
                .andExpect(jsonPath("$[?(@.path == 'maritalStatus')].values[0]").value(contains("single")));
    }

    @Test
    void servesTheManualTestingPageAtTheRoot() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk()).andExpect(forwardedUrl("index.html"));
        mvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<title>FAFSA Edit Tester</title>")));
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
