package com.gdit.fafsa.rules;

import static java.util.Map.entry;

import com.gdit.fafsa.model.FafsaApplication;
import com.gdit.fafsa.model.FafsaApplication.DependencyStatus;
import com.gdit.fafsa.model.FafsaApplication.MaritalStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.function.Function;

/**
 * The application fields a rule file may reference, by JSON path. An explicit registry (rather than reflection)
 * keeps field access type-safe and lets the loader reject unknown fields and type mismatches up front.
 */
final class Fields {

    private record Field(Class<?> type, Function<FafsaApplication, Object> reader) {
    }

    private static final Map<String, Field> FIELDS = Map.ofEntries(
            field("studentInfo.firstName", String.class, app -> app.studentInfo().firstName()),
            field("studentInfo.lastName", String.class, app -> app.studentInfo().lastName()),
            field("studentInfo.ssn", String.class, app -> app.studentInfo().ssn()),
            field("studentInfo.dateOfBirth", LocalDate.class, app -> app.studentInfo().dateOfBirth()),
            field("dependencyStatus", DependencyStatus.class, FafsaApplication::dependencyStatus),
            field("maritalStatus", MaritalStatus.class, FafsaApplication::maritalStatus),
            field("spouseInfo.firstName", String.class, app -> app.spouseInfo().firstName()),
            field("spouseInfo.lastName", String.class, app -> app.spouseInfo().lastName()),
            field("spouseInfo.ssn", String.class, app -> app.spouseInfo().ssn()),
            field("household.numberInHousehold", Integer.class, app -> app.household().numberInHousehold()),
            field("household.numberInCollege", Integer.class, app -> app.household().numberInCollege()),
            field("income.studentIncome", BigDecimal.class, app -> app.income().studentIncome()),
            field("income.parentIncome", BigDecimal.class, app -> app.income().parentIncome()),
            field("stateOfResidence", String.class, FafsaApplication::stateOfResidence));

    private Fields() {
    }

    static boolean exists(String path) {
        return FIELDS.containsKey(path);
    }

    static Class<?> typeOf(String path) {
        return FIELDS.get(path).type();
    }

    static Object read(String path, FafsaApplication application) {
        return FIELDS.get(path).reader().apply(application);
    }

    private static Map.Entry<String, Field> field(
            String path, Class<?> type, Function<FafsaApplication, Object> reader) {
        return entry(path, new Field(type, reader));
    }
}
