package com.example.testcase;

import com.example.testcase.config.JiraProperties;
import com.example.testcase.config.StoryDigestUiProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({JiraProperties.class, StoryDigestUiProperties.class})
public class TestCaseApplication {

    public static void main(String[] args) {
        SpringApplication.run(TestCaseApplication.class, args);
    }
}
