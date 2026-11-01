package com.gdit.fafsa.rules;

import static com.gdit.fafsa.model.ApplicationBuilder.aValidApplication;
import static org.assertj.core.api.Assertions.assertThat;

import com.gdit.fafsa.model.FafsaApplication.DependencyStatus;
import com.gdit.fafsa.model.FafsaApplication.MaritalStatus;
import com.gdit.fafsa.model.FafsaApplication.SpouseInfo;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class FieldsTest {

    private final com.gdit.fafsa.model.FafsaApplication application =
            aValidApplication().married(new SpouseInfo("John", "Smith", "987654321")).build();

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "studentInfo.firstName, Jane",
            "studentInfo.lastName, Smith",
            "studentInfo.ssn, 123456789",
            "studentInfo.dateOfBirth, 2003-05-15",
            "dependencyStatus, DEPENDENT",
            "maritalStatus, MARRIED",
            "spouseInfo.firstName, John",
            "spouseInfo.lastName, Smith",
            "spouseInfo.ssn, 987654321",
            "household.numberInHousehold, 4",
            "household.numberInCollege, 1",
            "income.studentIncome, 5000",
            "income.parentIncome, 65000",
            "stateOfResidence, CA"})
    void readsEveryFieldByItsJsonPath(String path, String expected) {
        assertThat(Fields.exists(path)).isTrue();
        assertThat(String.valueOf(Fields.read(path, application))).isEqualTo(expected);
    }

    @Test
    void knowsEachFieldsType() {
        assertThat(Fields.typeOf("studentInfo.dateOfBirth")).isEqualTo(LocalDate.class);
        assertThat(Fields.typeOf("income.parentIncome")).isEqualTo(BigDecimal.class);
        assertThat(Fields.typeOf("household.numberInCollege")).isEqualTo(Integer.class);
        assertThat(Fields.typeOf("maritalStatus")).isEqualTo(MaritalStatus.class);
        assertThat(Fields.typeOf("dependencyStatus")).isEqualTo(DependencyStatus.class);
    }

    @Test
    void rejectsUnknownPaths() {
        assertThat(Fields.exists("studentInfo.middleName")).isFalse();
    }
}
