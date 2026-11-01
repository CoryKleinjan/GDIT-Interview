package com.gdit.fafsa;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class FafsaEditProcessorApplication {

    public static void main(String[] args) {
        SpringApplication.run(FafsaEditProcessorApplication.class, args);
    }
}
