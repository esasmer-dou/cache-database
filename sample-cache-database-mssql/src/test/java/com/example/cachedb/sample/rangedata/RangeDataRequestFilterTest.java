package com.example.cachedb.sample.rangedata;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

class RangeDataRequestFilterTest {
    private final RangeDataRequestFilter filter = new RangeDataRequestFilter(
            new com.example.cachedb.sample.controls.ControlsAccess("supplier", "tenant-a", "tenant-b"));
    private MockHttpServletRequest request() {
        var request = new MockHttpServletRequest("PUT", "/api/examples/v019/range-data/r/rows/a");
        request.setContentType("application/json");
        request.addHeader("Authorization", "Bearer tenant-a"); return request;
    }

    @Test void authenticationRejectsBeforeBodyRead() throws Exception {
        var request = new MockHttpServletRequest("PUT", "/api/examples/v019/range-data/r/rows/a") {
            @Override public jakarta.servlet.ServletInputStream getInputStream() {
                throw new AssertionError("Unverified body must not be read");
            }
        };
        var response = new MockHttpServletResponse(); var chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertEquals(401, response.getStatus()); assertNull(chain.getRequest());
    }
    @Test void unknownLengthBodyIsBoundedBeforeJsonDecoding() throws Exception {
        var request = request(); request.setContent("x".repeat(2049).getBytes(StandardCharsets.UTF_8));
        var response = new MockHttpServletResponse(); var chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertEquals(413, response.getStatus()); assertNull(chain.getRequest());
    }
    @Test void acceptedBodyRemainsReadable() throws Exception {
        var request = request(); byte[] body = "{\"rank\":\"42\",\"status\":\"OPEN\"}".getBytes(StandardCharsets.UTF_8);
        request.setContent(body); var response = new MockHttpServletResponse();
        var chain = new MockFilterChain(); filter.doFilter(request, response, chain);
        assertArrayEquals(body, chain.getRequest().getInputStream().readAllBytes());
    }
    @Test void queryAndPathBudgetsApplyToReads() throws Exception {
        var request = request(); request.setMethod("GET"); request.setQueryString("cursor=" + "a".repeat(12289));
        var response = new MockHttpServletResponse(); var chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertEquals(414, response.getStatus()); assertNull(chain.getRequest());
    }
}
