package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.protection.ProtectionException;
import java.util.*;
import org.junit.jupiter.api.Test;

class RedisProtectionArgumentsTest {
    @Test void limitsApplyToUtf8BytesAndAggregateCommandWithoutEncodedCopies() {
        assertDoesNotThrow(()->RedisProtectionArguments.validate("return 1",List.of("namespace:key"),List.of("value")));
        assertThrows(ProtectionException.class,()->RedisProtectionArguments.validate("return 1",List.of("x".repeat(8193)),List.of()));
        assertThrows(ProtectionException.class,()->RedisProtectionArguments.validate("return 1",List.of("key"),Collections.nCopies(33,"x".repeat(262144))));
        assertThrows(ProtectionException.class,()->RedisProtectionArguments.validate("return 1",List.of("key"),List.of("\uD800")));
        assertThrows(ProtectionException.class,()->RedisProtectionArguments.validate("return 1",List.of("key"),List.of("\u011f".repeat(131073))));
        assertThrows(ProtectionException.class,()->RedisProtectionArguments.validate("return 1",Collections.nCopies(4097,"key"),List.of()));
    }
}
