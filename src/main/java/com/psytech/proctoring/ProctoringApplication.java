package com.psytech.proctoring;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ProctoringApplication {
    public static void main(String[] args) {
        SpringApplication.run(ProctoringApplication.class, args);
    }
}
