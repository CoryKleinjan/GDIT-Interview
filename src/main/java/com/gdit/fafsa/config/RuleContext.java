package com.gdit.fafsa.config;

import com.gdit.fafsa.engine.Application;
import java.time.LocalDate;

/** What a check may need besides the value itself: the rest of the application and today's date. */
public record RuleContext(Application application, LocalDate today) {
}
