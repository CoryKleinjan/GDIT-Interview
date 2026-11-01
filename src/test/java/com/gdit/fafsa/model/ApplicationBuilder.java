package com.gdit.fafsa.model;

import com.gdit.fafsa.model.FafsaApplication.DependencyStatus;
import com.gdit.fafsa.model.FafsaApplication.Household;
import com.gdit.fafsa.model.FafsaApplication.Income;
import com.gdit.fafsa.model.FafsaApplication.MaritalStatus;
import com.gdit.fafsa.model.FafsaApplication.SpouseInfo;
import com.gdit.fafsa.model.FafsaApplication.StudentInfo;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Test data builder: starts from the spec's valid sample so each test changes only what it is about. */
public final class ApplicationBuilder {

    private String ssn = "123456789";
    private LocalDate dateOfBirth = LocalDate.of(2003, 5, 15);
    private DependencyStatus dependencyStatus = DependencyStatus.DEPENDENT;
    private MaritalStatus maritalStatus = MaritalStatus.SINGLE;
    private SpouseInfo spouseInfo;
    private Integer numberInHousehold = 4;
    private Integer numberInCollege = 1;
    private BigDecimal studentIncome = new BigDecimal("5000");
    private BigDecimal parentIncome = new BigDecimal("65000");
    private String stateOfResidence = "CA";

    private ApplicationBuilder() {
    }

    public static ApplicationBuilder aValidApplication() {
        return new ApplicationBuilder();
    }

    public ApplicationBuilder ssn(String ssn) {
        this.ssn = ssn;
        return this;
    }

    public ApplicationBuilder dateOfBirth(LocalDate dateOfBirth) {
        this.dateOfBirth = dateOfBirth;
        return this;
    }

    public ApplicationBuilder independent() {
        this.dependencyStatus = DependencyStatus.INDEPENDENT;
        return this;
    }

    public ApplicationBuilder married(SpouseInfo spouseInfo) {
        this.maritalStatus = MaritalStatus.MARRIED;
        this.spouseInfo = spouseInfo;
        return this;
    }

    public ApplicationBuilder household(Integer numberInHousehold, Integer numberInCollege) {
        this.numberInHousehold = numberInHousehold;
        this.numberInCollege = numberInCollege;
        return this;
    }

    public ApplicationBuilder income(BigDecimal studentIncome, BigDecimal parentIncome) {
        this.studentIncome = studentIncome;
        this.parentIncome = parentIncome;
        return this;
    }

    public ApplicationBuilder stateOfResidence(String stateOfResidence) {
        this.stateOfResidence = stateOfResidence;
        return this;
    }

    public FafsaApplication build() {
        return new FafsaApplication(
                new StudentInfo("Jane", "Smith", ssn, dateOfBirth),
                dependencyStatus,
                maritalStatus,
                spouseInfo,
                new Household(numberInHousehold, numberInCollege),
                new Income(studentIncome, parentIncome),
                stateOfResidence);
    }
}
