package com.gdit.fafsa;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class FafsaEditProcessorApplication {

    public static void main(String[] args) {
        SpringApplication.run(FafsaEditProcessorApplication.class, args);
    }
}
