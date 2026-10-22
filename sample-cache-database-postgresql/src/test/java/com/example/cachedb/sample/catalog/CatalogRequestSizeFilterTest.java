package com.example.cachedb.sample.catalog;

import static org.junit.jupiter.api.Assertions.*;

import com.example.cachedb.sample.web.CatalogRequestSizeFilter;

import jakarta.servlet.http.HttpServletRequestWrapper;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

class CatalogRequestSizeFilterTest {
    @Test
    void oversizedFixedAndChunkedBodiesNeverReachTheController() throws Exception {
        for (boolean chunked : new boolean[] {false, true}) {
            var request = new MockHttpServletRequest("POST", "/api/catalog/imports");
            request.setContent(new byte[65_537]);
            var wrapped =
                    new HttpServletRequestWrapper(request) {
                        @Override
                        public long getContentLengthLong() {
                            return chunked ? -1 : super.getContentLengthLong();
                        }
                    };
            var response = new MockHttpServletResponse();
            var reached = new AtomicBoolean();
            new CatalogRequestSizeFilter().doFilter(wrapped, response, (a, b) -> reached.set(true));
            assertEquals(413, response.getStatus());
            assertFalse(reached.get());
        }
    }

    @Test
    void validJsonIsPreservedForTheMvcReader() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/catalog/refresh");
        request.setContent("{\"ids\":[]}".getBytes(StandardCharsets.UTF_8));
        var reached = new AtomicBoolean();
        new CatalogRequestSizeFilter()
                .doFilter(
                        request,
                        new MockHttpServletResponse(),
                        (a, b) -> {
                            assertEquals(
                                    "{\"ids\":[]}",
                                    new String(
                                            a.getInputStream().readAllBytes(),
                                            StandardCharsets.UTF_8));
                            reached.set(true);
                        });
        assertTrue(reached.get());
    }
}
