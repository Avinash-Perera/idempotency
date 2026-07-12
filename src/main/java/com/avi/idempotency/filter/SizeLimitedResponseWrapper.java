package com.avi.idempotency.filter;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;

/**
 * A size-limited response wrapper that caches the body up to a configured threshold.
 * If the payload exceeds the limit, it throws a PayloadTooLarge exception to protect
 * the JVM heap from OutOfMemoryErrors (OOM).
 */
public class SizeLimitedResponseWrapper extends HttpServletResponseWrapper {

    private final ByteArrayOutputStream buffer;
    private final ServletOutputStream outputStream;
    private PrintWriter writer;
    private final int maxSize;
    private int written = 0;

    public SizeLimitedResponseWrapper(HttpServletResponse response, int maxSize) {
        super(response);
        this.maxSize = maxSize;
        this.buffer = new ByteArrayOutputStream(Math.min(maxSize, 1024));
        
        this.outputStream = new ServletOutputStream() {
            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setWriteListener(WriteListener writeListener) {
            }

            @Override
            public void write(int b) throws IOException {
                checkSize(1);
                buffer.write(b);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                checkSize(len);
                buffer.write(b, off, len);
            }
        };
    }

    private void checkSize(int len) {
        written += len;
        if (written > maxSize) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(413),
                    "Idempotent response exceeded maximum cacheable size of " + maxSize + " bytes"
            );
        }
    }

    @Override
    public ServletOutputStream getOutputStream() {
        return this.outputStream;
    }

    @Override
    public PrintWriter getWriter() throws IOException {
        if (this.writer == null) {
            this.writer = new PrintWriter(new OutputStreamWriter(this.outputStream, getCharacterEncoding()));
        }
        return this.writer;
    }

    /**
     * Flushes the cached content to the actual HTTP response stream.
     */
    public void copyBodyToResponse() throws IOException {
        if (this.writer != null) {
            this.writer.flush();
        }
        byte[] content = this.buffer.toByteArray();
        if (content.length > 0) {
            getResponse().getOutputStream().write(content);
            getResponse().getOutputStream().flush();
        }
    }

    /**
     * Returns a snapshot of the buffered body.
     */
    public byte[] getContentAsByteArray() {
        if (this.writer != null) {
            this.writer.flush();
        }
        return this.buffer.toByteArray();
    }
}
