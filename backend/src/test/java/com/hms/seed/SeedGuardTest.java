package com.hms.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hms.seed.SeedGuard.SeedRefusedException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** [E2-4] The seed refuses to run anywhere but dev and staging. */
class SeedGuardTest {

    @Test
    void allowsDevAndStagingProfiles() {
        assertThatCode(() -> SeedGuard.check(List.of("dev"), null)).doesNotThrowAnyException();
        assertThatCode(() -> SeedGuard.check(List.of("staging"), null)).doesNotThrowAnyException();
        assertThatCode(() -> SeedGuard.check(List.of("test", "dev"), "")).doesNotThrowAnyException();
    }

    @Test
    void allowsStagingByEnvironmentVariableWhenThereIsNoStagingProfile() {
        assertThatCode(() -> SeedGuard.check(List.of(), "staging")).doesNotThrowAnyException();
        assertThatCode(() -> SeedGuard.check(List.of(), " DEV ")).doesNotThrowAnyException();
    }

    @Test
    void refusesProductionInEveryForm() {
        assertThatThrownBy(() -> SeedGuard.check(List.of("prod"), null)).isInstanceOf(SeedRefusedException.class);
        assertThatThrownBy(() -> SeedGuard.check(List.of("dev", "prod"), null))
                .isInstanceOf(SeedRefusedException.class);
        assertThatThrownBy(() -> SeedGuard.check(List.of("production"), "staging"))
                .isInstanceOf(SeedRefusedException.class);
        assertThatThrownBy(() -> SeedGuard.check(List.of("dev"), "prod")).isInstanceOf(SeedRefusedException.class);
        assertThatThrownBy(() -> SeedGuard.check(List.of(), "Production")).isInstanceOf(SeedRefusedException.class);
    }

    @Test
    void refusesWhenNeitherDevNorStagingIsActive() {
        assertThatThrownBy(() -> SeedGuard.check(List.of(), null)).isInstanceOf(SeedRefusedException.class);
        assertThatThrownBy(() -> SeedGuard.check(List.of(), "  ")).isInstanceOf(SeedRefusedException.class);
        assertThatThrownBy(() -> SeedGuard.check(List.of("test"), null)).isInstanceOf(SeedRefusedException.class);
        assertThatThrownBy(() -> SeedGuard.check(List.of("qa"), "")).isInstanceOf(SeedRefusedException.class);
    }

    @Test
    void refusesAnUnknownEnvironmentVariableValueEvenWithTheDevProfile() {
        assertThatThrownBy(() -> SeedGuard.check(List.of("dev"), "qa")).isInstanceOf(SeedRefusedException.class);
    }

    @Test
    void readsTheSpringEnvironment() {
        MockEnvironment dev = new MockEnvironment();
        dev.setActiveProfiles("dev");
        assertThatCode(() -> SeedGuard.check(dev)).doesNotThrowAnyException();

        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        assertThatThrownBy(() -> SeedGuard.check(prod)).isInstanceOf(SeedRefusedException.class);

        MockEnvironment envVar = new MockEnvironment().withProperty("HMS_ENV", "staging");
        assertThatCode(() -> SeedGuard.check(envVar)).doesNotThrowAnyException();
    }

    @Test
    void messageNeverEchoesConfiguration() {
        assertThatThrownBy(() -> SeedGuard.check(List.of("secret-profile"), "secret-env"))
                .hasMessageNotContaining("secret");
    }

    @Test
    void defaultOptionsAre2kPatientsAndLargeIs100k() {
        assertThat(SeedOptions.parse(List.of()).patients()).isEqualTo(2_000);
        assertThat(SeedOptions.parse(List.of("--large")).patients()).isEqualTo(100_000);
        assertThat(SeedOptions.parse(List.of("--patients=50")).patients()).isEqualTo(50);
        assertThatThrownBy(() -> SeedOptions.parse(List.of("--nope"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SeedOptions.parse(List.of("--patients=x")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void syntheticValuesCannotBeRealIdentifiers() {
        for (int i : new int[] {0, 1, 29, 30, 899, 1_999, 99_999}) {
            assertThat(SeedData.patientName(i)).startsWith("Test ");
            assertThat(SeedData.phone(i)).matches("\\+910000[0-9]{6}"); // Indian numbers never start with 0
            assertThat(SeedData.contactPhone(i)).matches("\\+910001[0-9]{6}");
            assertThat(SeedData.sex(i)).isIn("M", "F", "O", "U");
        }
        // Unique dates of birth below 30,000 keep the duplicate rule quiet on the service path.
        assertThat(java.util.stream.IntStream.range(0, 2_000).mapToObj(SeedData::dateOfBirth).distinct().count())
                .isEqualTo(2_000);
    }
}
