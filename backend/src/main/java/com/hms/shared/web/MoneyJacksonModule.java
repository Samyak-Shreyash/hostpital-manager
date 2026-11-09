package com.hms.shared.web;

import com.hms.shared.domain.Money;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.module.SimpleModule;

/** Money in JSON is a decimal string ({@code "1250.00"}); JSON numbers are rejected to avoid float loss. */
public class MoneyJacksonModule extends SimpleModule {

    public MoneyJacksonModule() {
        super("hms-money");
        addSerializer(Money.class, new ValueSerializer<>() {
            @Override
            public void serialize(Money value, JsonGenerator gen, SerializationContext ctxt) {
                gen.writeString(value.toString());
            }
        });
        addDeserializer(Money.class, new ValueDeserializer<>() {
            @Override
            public Money deserialize(JsonParser p, DeserializationContext ctxt) {
                if (p.currentToken() != JsonToken.VALUE_STRING) {
                    return (Money) ctxt.handleUnexpectedToken(Money.class, p);
                }
                try {
                    return Money.of(p.getString());
                } catch (IllegalArgumentException e) {
                    return (Money) ctxt.handleWeirdStringValue(Money.class, p.getString(), e.getMessage());
                }
            }
        });
    }
}
