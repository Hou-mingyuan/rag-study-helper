package com.rag.studyhelper;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RagStudyHelperApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagStudyHelperApplication.class, args);
    }

}
