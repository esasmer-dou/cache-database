package com.reactor.cachedb.starter;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.jdbc.JdbcSourceOwnerOnboarding;
import java.util.*;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class CacheSourceOwnerOnboardingTest {
    @Test void administrationPrecedesConfigurationValidationOrIo() {
        assertEquals(ProtectionException.Code.ADMIN_REQUIRED,assertThrows(ProtectionException.class,
                ()->CacheSourceOwnerOnboarding.prepare(null,null,null,null,null,null,null,null)).code());
    }

    @Test void worklistHasStableBoundedPagesAndCanonicalOpaqueCursors() {
        var ids=JdbcSourceOwnerOnboarding.longIds();
        var candidates=LongStream.range(0,4096).boxed().toList();
        var pages=CacheSourceOwnerOnboarding.pages(candidates,ids,131072,4096);
        assertEquals(32,pages.size()); assertEquals(pages,CacheSourceOwnerOnboarding.pages(candidates,ids,131072,4096));
        var cursors=new HashSet<String>(); var identities=new ArrayList<String>();
        for (int i=0;i<pages.size();i++) {
            var page=pages.get(i); assertEquals(128,page.ids().size());
            assertEquals(i==31,page.last()); assertTrue(cursors.add(page.nextCursor()));
            if (!page.last()) assertEquals(64,ProtectionKey.token(page.nextCursor()).length());
            identities.addAll(page.ids());
        }
        assertEquals(candidates.stream().map(Object::toString).toList(),identities);
        assertEquals("-",pages.getLast().nextCursor());
    }

    @Test void byteBudgetSplitsBeforeTransportAndCountsUtf8RatherThanCharacters() {
        var ids=JdbcSourceOwnerOnboarding.stringIds();
        var candidates=List.of("a".repeat(600),"b".repeat(600),"c".repeat(600));
        var pages=CacheSourceOwnerOnboarding.pages(candidates,ids,1024,3);
        assertEquals(3,pages.size()); assertEquals(3,pages.stream().mapToInt(p->p.ids().size()).sum());
        assertThrows(ProtectionException.class,()->CacheSourceOwnerOnboarding.pages(List.of("\u20ac".repeat(342)),ids,2048,1));
    }

    @Test void emptyDuplicateOversizedAndOverBudgetWorklistsFailClosed() {
        var ids=JdbcSourceOwnerOnboarding.longIds();
        assertThrows(ProtectionException.class,()->CacheSourceOwnerOnboarding.pages(List.<Long>of(),ids,1024,1));
        assertThrows(ProtectionException.class,()->CacheSourceOwnerOnboarding.pages(List.of(1L,1L),ids,1024,2));
        assertThrows(ProtectionException.class,()->CacheSourceOwnerOnboarding.pages(List.of(1L,2L),ids,1024,1));
        assertThrows(ProtectionException.class,()->CacheSourceOwnerOnboarding.pages(LongStream.range(0,4097).boxed().toList(),ids,131072,5000));
    }

    @Test void worklistCannotBecomeUnboundedThroughTinyPages() {
        var ids=JdbcSourceOwnerOnboarding.stringIds();
        var candidates=LongStream.range(0,129).mapToObj(i->"x".repeat(1000)+i).toList();
        assertThrows(ProtectionException.class,()->CacheSourceOwnerOnboarding.pages(candidates,ids,1024,129));
    }

    @Test void exactWorklistOrderAndContentsArePartOfPageIdentity() {
        var ids=JdbcSourceOwnerOnboarding.longIds();
        var original=CacheSourceOwnerOnboarding.pages(List.of(1L,2L),ids,1024,2);
        assertNotEquals(original,CacheSourceOwnerOnboarding.pages(List.of(2L,1L),ids,1024,2));
        assertNotEquals(original,CacheSourceOwnerOnboarding.pages(List.of(1L,3L),ids,1024,2));
        assertEquals(List.of("-9223372036854775808","9223372036854775807"),
                CacheSourceOwnerOnboarding.pages(List.of(Long.MIN_VALUE,Long.MAX_VALUE),ids,1024,2).getFirst().ids());
    }

    @Test void rootAdapterUsesOnlyNativeIdentityCodecsNotConfiguredJdbcCallbacks() {
        var strings=CacheSourceOwnerOnboarding.<String>nativeIds(JdbcSourceOwnerOnboarding.stringIds().formatToken());
        assertEquals("unchanged",strings.decode("unchanged"));
        var longs=CacheSourceOwnerOnboarding.<Long>nativeIds(JdbcSourceOwnerOnboarding.longIds().formatToken());
        assertEquals(17L,longs.decode("17"));
        assertThrows(ProtectionException.class,()->longs.decode("017"));
        assertEquals(ProtectionException.Code.UNWIRED_PROTOCOL,assertThrows(ProtectionException.class,
                ()->CacheSourceOwnerOnboarding.nativeIds(ProtectionKey.hash("custom-identity-callback"))).code());
    }
}
