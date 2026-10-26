package com.deployhub.platform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@Configuration
@EnableScheduling
public class AppConfig {

    /** Injected instead of calling Instant.now() directly, so time is controllable in tests. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
