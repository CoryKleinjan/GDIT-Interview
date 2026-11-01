package com.gdit.fafsa.engine;

/** How much a failed edit matters. Configured per rule in {@code application.yml}. */
public enum Severity {
    /** The application cannot be processed until this is fixed. */
    ERROR,
    /** Suspicious but processable; the applicant should confirm or correct it. */
    WARNING,
    /** Informational only; never changes the overall status. */
    INFO
}
