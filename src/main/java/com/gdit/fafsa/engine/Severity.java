package com.gdit.fafsa.engine;

/** How much a failed edit matters. Set per rule in the rule file. */
public enum Severity {
    /** The application cannot be processed until this is fixed. */
    ERROR,
    /** Suspicious but processable; the applicant should confirm or correct it. */
    WARNING,
    /** Informational only; never changes the overall status. */
    INFO
}
