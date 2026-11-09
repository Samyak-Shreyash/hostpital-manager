package com.hms.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hms.shared.domain.Money;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

class MoneyJacksonModuleTest {

    private final JsonMapper mapper = JsonMapper.builder().addModule(new MoneyJacksonModule()).build();

    record Line(Money amount) {
    }

    @Test
    void writesMoneyAsDecimalString() {
        assertThat(mapper.writeValueAsString(new Line(Money.of("1250")))).isEqualTo("{\"amount\":\"1250.00\"}");
    }

    @Test
    void readsDecimalString() {
        assertThat(mapper.readValue("{\"amount\":\"99.90\"}", Line.class).amount()).isEqualTo(Money.of("99.90"));
    }

    @Test
    void rejectsJsonNumbersAndExtraDecimals() {
        assertThatThrownBy(() -> mapper.readValue("{\"amount\":99.9}", Line.class))
                .isInstanceOf(JacksonException.class);
        assertThatThrownBy(() -> mapper.readValue("{\"amount\":\"1.005\"}", Line.class))
                .isInstanceOf(JacksonException.class);
    }
}
