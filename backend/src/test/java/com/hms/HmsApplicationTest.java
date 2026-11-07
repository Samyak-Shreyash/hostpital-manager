package com.hms;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;

class HmsApplicationTest {

    @Test
    void applicationClassIsSpringBootEntryPoint() {
        assertThat(HmsApplication.class).hasAnnotation(SpringBootApplication.class);
    }
}
