package com.gdit.fafsa.rules;

import com.gdit.fafsa.model.FafsaApplication;
import java.time.LocalDate;

/** What a check may need besides the value itself: the rest of the application and today's date. */
record RuleContext(FafsaApplication application, LocalDate today) {
}
