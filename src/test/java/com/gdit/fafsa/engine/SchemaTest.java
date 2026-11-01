package com.gdit.fafsa.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SchemaTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Schema schema = new Schema(List.of(
            new FieldDefinition("person.name", FieldType.TEXT, null),
            new FieldDefinition("person.born", FieldType.DATE, null),
            new FieldDefinition("count", FieldType.INTEGER, null),
            new FieldDefinition("amount", FieldType.NUMBER, null),
            new FieldDefinition("status", FieldType.CHOICE, List.of("single", "married"))));

    @Test
    void readsEveryFieldAsItsTypedValue() {
        Application application = schema.read(json("""
                { "person": { "name": "Jane", "born": "2003-05-15" }, "count": 4, "amount": -0.5, "status": "MARRIED" }
                """));

        assertThat(application.get("person.name")).isEqualTo("Jane");
        assertThat(application.get("person.born")).isEqualTo(LocalDate.of(2003, 5, 15));
        assertThat(application.get("count")).isEqualTo(4L);
        assertThat(application.get("amount")).isEqualTo(new BigDecimal("-0.5"));
        assertThat(application.get("status")).isEqualTo("married");
    }

    @Test
    void missingNullAndUnknownFieldsAreSimplyAbsent() {
        Application application = schema.read(json("""
                { "person": { "name": null }, "favoriteColor": "blue" }
                """));

        assertThat(application.values()).isEmpty();
    }

    @Test
    void textAcceptsNumbersSoAnSsnSentAsANumberStillReadsAsDigits() {
        assertThat(schema.read(json("{ \"person\": { \"name\": 123456789 } }")).get("person.name"))
                .isEqualTo("123456789");
    }

    @Test
    void wholeNumbersWrittenWithAZeroFractionAreAccepted() {
        assertThat(schema.read(json("{ \"count\": 4.0 }")).get("count")).isEqualTo(4L);
    }

    @Test
    void reportsEveryInvalidFieldWithoutEchoingValues() {
        assertThatThrownBy(() -> schema.read(json("""
                { "person": { "name": {}, "born": "15/05/2003" }, "count": 4.5, "amount": "lots", "status": "widowed" }
                """)))
                .isInstanceOfSatisfying(InvalidApplicationException.class, ex -> assertThat(ex.problems())
                        .containsExactly(
                                "person.name must be text",
                                "person.born must be a date (YYYY-MM-DD)",
                                "count must be a whole number",
                                "amount must be a number",
                                "status must be one of [single, married]"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"\"text\"|count must be a whole number", "true|count must be a whole number"})
    void rejectsNonNumbersForNumbers(String value, String problem) {
        assertThatThrownBy(() -> schema.read(json("{ \"count\": " + value + " }")))
                .isInstanceOfSatisfying(InvalidApplicationException.class,
                        ex -> assertThat(ex.problems()).containsExactly(problem));
    }

    @Test
    void rejectsNonTextForDatesAndChoices() {
        assertThatThrownBy(() -> schema.read(json("{ \"person\": { \"born\": 20030515 }, \"status\": 1 }")))
                .isInstanceOfSatisfying(InvalidApplicationException.class, ex -> assertThat(ex.problems())
                        .containsExactly("person.born must be a date (YYYY-MM-DD)",
                                "status must be one of [single, married]"));
    }

    @Test
    void prefixesProblemsSoBatchItemsCanBeIdentified() {
        assertThatThrownBy(() -> schema.read(json("{ \"count\": \"x\" }"), "applications[3]."))
                .isInstanceOfSatisfying(InvalidApplicationException.class,
                        ex -> assertThat(ex.problems()).containsExactly("applications[3].count must be a whole number"));
    }

    @Test
    void rejectsABodyThatIsNotAnObject() {
        assertThatThrownBy(() -> schema.read(json("[]")))
                .isInstanceOfSatisfying(InvalidApplicationException.class,
                        ex -> assertThat(ex.problems()).containsExactly("The application must be a JSON object"));
    }

    @Test
    void readsFlatFieldValuesSuchAsRuleExamples() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("person.born", "2012-11-01");
        values.put("count", 2);

        Application application = schema.read(values);

        assertThat(application.get("person.born")).isEqualTo(LocalDate.of(2012, 11, 1));
        assertThat(application.get("count")).isEqualTo(2L);
    }

    @Test
    void flatValuesMustNameKnownFields() {
        assertThatThrownBy(() -> schema.read(Map.of("person.middleName", "x")))
                .isInstanceOfSatisfying(InvalidApplicationException.class,
                        ex -> assertThat(ex.problems()).containsExactly("person.middleName is not a known field"));
    }

    @Test
    void describesItsFields() {
        assertThat(schema.has("person.name")).isTrue();
        assertThat(schema.has("person")).isFalse();
        assertThat(schema.typeOf("amount")).isEqualTo(BigDecimal.class);
        assertThat(schema.typeOf("count")).isEqualTo(Long.class);
        assertThat(schema.typeOf("status")).isEqualTo(String.class);
        assertThat(schema.fields()).extracting(FieldDefinition::path)
                .containsExactly("person.name", "person.born", "count", "amount", "status");
    }

    private static JsonNode json(String text) {
        try {
            return JSON.readTree(text);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }
}
