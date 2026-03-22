package com.example.testcase;

import com.example.testcase.config.JiraProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(JiraProperties.class)
public class TestCaseApplication {

    public static void main(String[] args) {
        SpringApplication.run(TestCaseApplication.class, args);
    }
}
