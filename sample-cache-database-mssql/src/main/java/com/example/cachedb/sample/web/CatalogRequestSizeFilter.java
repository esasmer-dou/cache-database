package com.example.cachedb.sample.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Bound command bodies before Jackson materializes their arrays, including chunked requests. */
@Component
public final class CatalogRequestSizeFilter extends OncePerRequestFilter {
    private static final int MAX_BYTES = 65_536;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !"POST".equals(request.getMethod())
                || !(path.equals("/api/catalog/imports") || path.equals("/api/catalog/refresh")
                        || path.equals("/api/catalog/dirty"));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request.getContentLengthLong() > MAX_BYTES) {
            response.sendError(413, "Catalog commands are limited to 64 KiB");
            return;
        }
        byte[] bytes = request.getInputStream().readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) {
            response.sendError(413, "Catalog commands are limited to 64 KiB");
            return;
        }
        var input = new ByteArrayInputStream(bytes);
        chain.doFilter(
                new HttpServletRequestWrapper(request) {
                    @Override
                    public ServletInputStream getInputStream() {
                        return new ServletInputStream() {
                            @Override
                            public int read() {
                                return input.read();
                            }

                            @Override
                            public int read(byte[] buffer, int offset, int length) {
                                return input.read(buffer, offset, length);
                            }

                            @Override
                            public boolean isFinished() {
                                return input.available() == 0;
                            }

                            @Override
                            public boolean isReady() {
                                return true;
                            }

                            @Override
                            public void setReadListener(ReadListener listener) {
                                throw new UnsupportedOperationException(
                                        "Synchronous MVC request body");
                            }
                        };
                    }
                },
                response);
    }
}
