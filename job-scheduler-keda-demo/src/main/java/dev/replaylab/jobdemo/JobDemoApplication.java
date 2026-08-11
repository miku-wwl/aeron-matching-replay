package dev.replaylab.jobdemo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class JobDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(JobDemoApplication.class, args);
    }
}
