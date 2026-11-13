package com.example.cachedb.sample.cacheapi;

import static org.junit.jupiter.api.Assertions.*;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CacheApiRequestSizeFilterTest {
    @Test void oversizedFixedAndChunkedBodiesNeverReachJsonDecoding() throws Exception {
        for (boolean chunked : new boolean[] {false, true}) {
            var request = new MockHttpServletRequest("PUT", "/api/examples/v019/async/offers/1");
            request.setContent(new byte[4097]);
            var wrapped = new HttpServletRequestWrapper(request) {
                @Override public long getContentLengthLong() { return chunked ? -1 : super.getContentLengthLong(); }
            };
            var response = new MockHttpServletResponse();
            var reached = new AtomicBoolean();
            new CacheApiRequestSizeFilter().doFilter(wrapped, response, (a, b) -> reached.set(true));
            assertEquals(413, response.getStatus()); assertFalse(reached.get());
        }
    }

    @Test void exactLimitIsPreservedAndQueryStringsAreBounded() throws Exception {
        var request = new MockHttpServletRequest("PUT", "/api/examples/v019/async/offers/1");
        byte[] bytes = ("{\"value\":\"x\"}" + " ".repeat(4083)).getBytes(StandardCharsets.UTF_8);
        assertEquals(4096, bytes.length);
        request.setContent(bytes);
        var reached = new AtomicBoolean();
        new CacheApiRequestSizeFilter().doFilter(request, new MockHttpServletResponse(), (a, b) -> {
            assertArrayEquals(bytes, a.getInputStream().readNBytes(4097)); reached.set(true);
        });
        assertTrue(reached.get());
        request = new MockHttpServletRequest("GET", "/api/examples/v019/reactive/stock");
        request.setQueryString("x".repeat(4097));
        var response = new MockHttpServletResponse(); reached.set(false);
        new CacheApiRequestSizeFilter().doFilter(request, response, (a, b) -> reached.set(true));
        assertEquals(414, response.getStatus()); assertFalse(reached.get());
    }

    @Test void unrelatedRoutesRemainUntouched() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/unrelated");
        request.setContent(new byte[4097]);
        var reached = new AtomicBoolean();
        new CacheApiRequestSizeFilter().doFilter(request, new MockHttpServletResponse(), (a, b) -> reached.set(true));
        assertTrue(reached.get());
    }
}
