package com.shortener.config;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling // blocklist refresh, rate-limit bucket cleanup (Phase 3)
@EnableConfigurationProperties({AppProperties.class, AnalyticsProperties.class, SecurityProperties.class,
        AbuseProperties.class})
public class AppConfig {

    /**
     * Single source of "now" for the whole app. Injecting a Clock (instead of calling
     * Instant.now() inline) makes expiry logic deterministic in tests.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
