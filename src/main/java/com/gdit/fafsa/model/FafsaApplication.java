package com.gdit.fafsa.model;

import static java.util.Objects.requireNonNullElse;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A FAFSA application as submitted to the API.
 *
 * <p>Missing sections are replaced with empty ones so edit rules can read any field without null-checking
 * the section first. Missing <em>values</em> stay null and are reported by the rule that needs them.
 * Only the two statuses are mandatory, because they decide which conditional rules apply.
 */
public record FafsaApplication(
        StudentInfo studentInfo,
        @NotNull DependencyStatus dependencyStatus,
        @NotNull MaritalStatus maritalStatus,
        SpouseInfo spouseInfo,
        Household household,
        Income income,
        String stateOfResidence) {

    public FafsaApplication {
        studentInfo = requireNonNullElse(studentInfo, StudentInfo.EMPTY);
        spouseInfo = requireNonNullElse(spouseInfo, SpouseInfo.EMPTY);
        household = requireNonNullElse(household, Household.EMPTY);
        income = requireNonNullElse(income, Income.EMPTY);
    }

    public boolean isDependent() {
        return dependencyStatus == DependencyStatus.DEPENDENT;
    }

    public boolean isMarried() {
        return maritalStatus == MaritalStatus.MARRIED;
    }

    public enum DependencyStatus { DEPENDENT, INDEPENDENT }

    public enum MaritalStatus { SINGLE, MARRIED }

    public record StudentInfo(String firstName, String lastName, String ssn, LocalDate dateOfBirth) {
        static final StudentInfo EMPTY = new StudentInfo(null, null, null, null);
    }

    public record SpouseInfo(String firstName, String lastName, String ssn) {
        static final SpouseInfo EMPTY = new SpouseInfo(null, null, null);
    }

    public record Household(Integer numberInHousehold, Integer numberInCollege) {
        static final Household EMPTY = new Household(null, null);
    }

    public record Income(BigDecimal studentIncome, BigDecimal parentIncome) {
        static final Income EMPTY = new Income(null, null);
    }
}
