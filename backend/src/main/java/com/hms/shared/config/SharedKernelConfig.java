package com.hms.shared.config;

import com.hms.shared.id.UuidV7Generator;
import com.hms.shared.web.MoneyJacksonModule;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Shared-kernel beans. Code takes time from the injected {@link Clock}, never {@code now()} directly. */
@Configuration(proxyBeanMethods = false)
public class SharedKernelConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    UuidV7Generator uuidV7Generator(Clock clock) {
        return new UuidV7Generator(clock);
    }

    /** Registered by Spring Boot's Jackson auto-configuration. */
    @Bean
    MoneyJacksonModule moneyJacksonModule() {
        return new MoneyJacksonModule();
    }
}
