package ru.tutor.codeclass.config;

import org.springframework.context.annotation.*;
import java.time.Clock;

@Configuration
public class TimeConfig {
    @Bean Clock clock() { return Clock.systemUTC(); }
}
