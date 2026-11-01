package com.gdit.fafsa.rules;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ClockConfiguration {

    /** "Today" for age checks comes from this clock so tests can pin it. */
    @Bean
    Clock clock() {
        return Clock.systemDefaultZone();
    }
}
