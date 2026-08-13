package com.reactor.cachedb.core.projection;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProjectionSchemaTypesTest {

    @Test
    void shouldRoundTripAllDeclarativeProjectionTypes() {
        ProjectionSchema<AllTypes> schema = ProjectionSchema.<AllTypes>builder()
                .stringColumn("text_value", AllTypes::textValue)
                .longColumn("long_value", AllTypes::longValue)
                .integerColumn("integer_value", AllTypes::integerValue)
                .shortColumn("short_value", AllTypes::shortValue)
                .doubleColumn("double_value", AllTypes::doubleValue)
                .floatColumn("float_value", AllTypes::floatValue)
                .booleanColumn("boolean_value", AllTypes::booleanValue)
                .decimalColumn("decimal_value", AllTypes::decimalValue)
                .uuidColumn("uuid_value", AllTypes::uuidValue)
                .instantColumn("instant_value", AllTypes::instantValue)
                .localDateColumn("date_value", AllTypes::dateValue)
                .localDateTimeColumn("date_time_value", AllTypes::dateTimeValue)
                .localTimeColumn("time_value", AllTypes::timeValue)
                .decodeWith(row -> new AllTypes(
                        row.string("text_value"),
                        row.longValue("long_value"),
                        row.integer("integer_value"),
                        row.shortValue("short_value"),
                        row.doubleValue("double_value"),
                        row.floatValue("float_value"),
                        row.booleanValue("boolean_value"),
                        row.decimal("decimal_value"),
                        row.uuid("uuid_value"),
                        row.instant("instant_value"),
                        row.localDate("date_value"),
                        row.localDateTime("date_time_value"),
                        row.localTime("time_value")
                ))
                .build();
        AllTypes expected = new AllTypes(
                "value\u001Fwith separator",
                9L,
                8,
                (short) 7,
                6.5d,
                5.25f,
                true,
                new BigDecimal("1234.5600"),
                UUID.fromString("98f482ac-4b22-4dce-b68a-44e882aee197"),
                Instant.parse("2026-08-13T09:15:30.123Z"),
                LocalDate.parse("2026-08-13"),
                LocalDateTime.parse("2026-08-13T09:15:30.123"),
                LocalTime.parse("09:15:30.123")
        );

        assertEquals(expected, schema.fromRedisValue(schema.toRedisValue(expected)));
        assertEquals(expected.instantValue(), schema.columnValues(expected).get("instant_value"));
    }

    private record AllTypes(
            String textValue,
            Long longValue,
            Integer integerValue,
            Short shortValue,
            Double doubleValue,
            Float floatValue,
            Boolean booleanValue,
            BigDecimal decimalValue,
            UUID uuidValue,
            Instant instantValue,
            LocalDate dateValue,
            LocalDateTime dateTimeValue,
            LocalTime timeValue
    ) {
    }
}
