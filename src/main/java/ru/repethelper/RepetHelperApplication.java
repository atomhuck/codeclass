package ru.repethelper;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RepetHelperApplication {
    public static void main(String[] args) {
        SpringApplication.run(RepetHelperApplication.class, args);
    }
}
