package com.reactor.cachedb.core.retention;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import org.junit.jupiter.api.Test;

class RetentionPolicyTest {
    @Test void limitsAndScoreUnitsAreExplicit() {
        var clock=Clock.fixed(Instant.parse("2026-10-27T12:00:00Z"),ZoneOffset.UTC);
        var millis=new RetentionPolicy("expires",2,RetentionPolicy.Action.DELETE,RetentionPolicy.ScoreUnit.EPOCH_MILLIS);
        var days=new RetentionPolicy("expires",2,RetentionPolicy.Action.EVICT_CACHE,RetentionPolicy.ScoreUnit.EPOCH_DAY);
        assertEquals(Instant.parse("2026-10-25T12:00:00Z").toEpochMilli(),millis.cutoff(clock));
        assertEquals(LocalDate.of(2026,10,25).toEpochDay(),days.cutoff(clock));
        assertThrows(IllegalArgumentException.class,()->new RetentionPolicy("expires",0,RetentionPolicy.Action.DELETE,RetentionPolicy.ScoreUnit.EPOCH_MILLIS));
        assertThrows(IllegalArgumentException.class,()->new RetentionPolicy("bad;sql",1,RetentionPolicy.Action.DELETE,RetentionPolicy.ScoreUnit.EPOCH_MILLIS));
        assertThrows(IllegalArgumentException.class,()->new RetentionSweepResult(1,0,1,0,1,false,0,Instant.EPOCH));
    }
}
