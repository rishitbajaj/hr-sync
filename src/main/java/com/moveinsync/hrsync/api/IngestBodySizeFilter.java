package com.moveinsync.hrsync.api;

import com.moveinsync.hrsync.config.IngestLimits;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class IngestBodySizeFilter extends OncePerRequestFilter {

    private final IngestLimits limits;

    public IngestBodySizeFilter(IngestLimits limits) {
        this.limits = limits;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!isIngestPost(request)) {
            chain.doFilter(request, response);
            return;
        }
        if (request.getContentLengthLong() > limits.getMaxBodyBytes()) {
            writeTooLarge(response);
            return;
        }
        try {
            chain.doFilter(new LimitedRequest(request, limits.getMaxBodyBytes()), response);
        } catch (RuntimeException | ServletException | IOException exception) {
            if (!response.isCommitted() && causedByBodyLimit(exception)) {
                writeTooLarge(response);
                return;
            }
            throw exception;
        }
    }

    private void writeTooLarge(HttpServletResponse response) throws IOException {
        response.setStatus(413);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(
                "{\"error\":\"REQUEST_TOO_LARGE\",\"message\":\"Request body exceeds the maximum of "
                        + limits.getMaxBodyBytes()
                        + " bytes\"}"
        );
    }

    private static boolean isIngestPost(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String path = request.getServletPath();
        if (path == null || path.isEmpty()) {
            path = request.getRequestURI();
        }
        return "/sync/events".equals(path);
    }

    private static boolean causedByBodyLimit(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof BodyTooLargeException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {

        private final int maxBodyBytes;

        private LimitedRequest(HttpServletRequest request, int maxBodyBytes) {
            super(request);
            this.maxBodyBytes = maxBodyBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            return new LimitedStream(super.getInputStream(), maxBodyBytes);
        }
    }

    private static final class LimitedStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final int maxBodyBytes;
        private int readBytes;

        private LimitedStream(ServletInputStream delegate, int maxBodyBytes) {
            this.delegate = delegate;
            this.maxBodyBytes = maxBodyBytes;
        }

        @Override
        public int read() throws IOException {
            int next = delegate.read();
            if (next >= 0) {
                count(1);
            }
            return next;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = delegate.read(buffer, offset, length);
            if (count > 0) {
                count(count);
            }
            return count;
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }

        private void count(int justRead) {
            readBytes += justRead;
            if (readBytes > maxBodyBytes) {
                throw new BodyTooLargeException(maxBodyBytes);
            }
        }
    }
}
