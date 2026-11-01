package com.gdit.fafsa.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.gdit.fafsa.model.FafsaApplication.DependencyStatus;
import com.gdit.fafsa.model.FafsaApplication.MaritalStatus;
import org.junit.jupiter.api.Test;

class FafsaApplicationTest {

    @Test
    void missingSectionsBecomeEmptySoRulesNeverSeeNullSections() {
        var application = new FafsaApplication(null, DependencyStatus.INDEPENDENT, MaritalStatus.SINGLE,
                null, null, null, null);

        assertThat(application.studentInfo()).isEqualTo(FafsaApplication.StudentInfo.EMPTY);
        assertThat(application.spouseInfo()).isEqualTo(FafsaApplication.SpouseInfo.EMPTY);
        assertThat(application.household()).isEqualTo(FafsaApplication.Household.EMPTY);
        assertThat(application.income()).isEqualTo(FafsaApplication.Income.EMPTY);
    }

    @Test
    void reportsDependencyAndMaritalStatus() {
        var dependentMarried = new FafsaApplication(null, DependencyStatus.DEPENDENT, MaritalStatus.MARRIED,
                null, null, null, null);
        var independentSingle = new FafsaApplication(null, DependencyStatus.INDEPENDENT, MaritalStatus.SINGLE,
                null, null, null, null);

        assertThat(dependentMarried.isDependent()).isTrue();
        assertThat(dependentMarried.isMarried()).isTrue();
        assertThat(independentSingle.isDependent()).isFalse();
        assertThat(independentSingle.isMarried()).isFalse();
    }
}
