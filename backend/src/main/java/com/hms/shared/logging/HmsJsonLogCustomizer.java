package com.hms.shared.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import org.springframework.boot.json.JsonWriter.Members;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

/**
 * JSON log customizer (ADR-0014), registered via {@code logging.structured.json.customizer}: adds the
 * owning {@code module} and redacts PHI ({@link PhiRedactor}).
 */
public class HmsJsonLogCustomizer implements StructuredLoggingJsonMembersCustomizer<ILoggingEvent> {

    @Override
    public void customize(Members<ILoggingEvent> members) {
        members.add("module", event -> ModuleName.of(event.getLoggerName())).whenNotNull();
        members.applyingValueProcessor(PhiRedactor.VALUE_PROCESSOR);
    }
}
