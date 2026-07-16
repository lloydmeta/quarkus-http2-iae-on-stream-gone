package com.beachape;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URL;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class Http2StreamResetErrorLoggingTest {

    private static final String QUARKUS_ERROR_HANDLER_LOGGER = "io.quarkus.vertx.http.runtime.QuarkusErrorHandler";
    private static final String RESET_MESSAGE_PREFIX = "Stream no longer exists";

    @TestHTTPResource("/large-asset.bin")
    URL assetUrl;

    private final List<LogRecord> records = Collections.synchronizedList(new ArrayList<>());
    private final Logger quarkusErrorHandlerLogger = Logger.getLogger(QUARKUS_ERROR_HANDLER_LOGGER);
    private Handler captureHandler;

    @BeforeEach
    void attachLogCapture() {
        records.clear();
        captureHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        captureHandler.setLevel(Level.ALL);
        quarkusErrorHandlerLogger.addHandler(captureHandler);
    }

    @AfterEach
    void detachLogCapture() {
        if (captureHandler != null) {
            quarkusErrorHandlerLogger.removeHandler(captureHandler);
        }
    }

    @Test
    void http2StreamReset_shouldNotBeLoggedAtError() {
        assertStreamResetNotLoggedAtError(Protocol.H2_PRIOR_KNOWLEDGE);
    }

    @Test
    void http1StreamReset_isNotLoggedAtError() {
        assertStreamResetNotLoggedAtError(Protocol.HTTP_1_1);
    }

    private void assertStreamResetNotLoggedAtError(Protocol requestProtocol) {
        var outcome = runCancelLoop(requestProtocol);

        // Sanity tests
        var usedIntendedProtocol = requestProtocol == Protocol.H2_PRIOR_KNOWLEDGE
                ? isHttp2(outcome.observedProtocol())
                : outcome.observedProtocol() == requestProtocol;
        assertTrue(
                usedIntendedProtocol,
                """
                did not exercise %s (observed protocol: %s); the scenario was never tested. For \
                HTTP/2, OkHttp H2_PRIOR_KNOWLEDGE may not be talking to Quarkus' plaintext port; fall \
                back to a raw-socket h2c client."""
                        .formatted(requestProtocol, outcome.observedProtocol()));

        // The bug: a benign client disconnect is logged at ERROR instead of DEBUG.
        var resetError = outcome.resetError();
        if (resetError != null) {
            fail("""
                    QuarkusErrorHandler logged a benign client-initiated stream reset at %s (attempt \
                    %d) over %s: %s. A client disconnect during response writing should be treated as \
                    benign (DEBUG), not ERROR."""
                    .formatted(
                            resetError.getLevel(),
                            outcome.attempts(),
                            outcome.observedProtocol(),
                            resetError.getThrown()));
        }
    }
    private static int maxAttempts = 50;
    private static final Duration perAttemptWait = Duration.ofMillis(400);
    private CancelLoopOutcome runCancelLoop(Protocol protocol) {
        var client = new OkHttpClient.Builder()
                .protocols(List.of(protocol))
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(5))
                .build();

        Protocol observedProtocol = null;
        LogRecord resetError = null;
        var attempt = 0;
        try {
            for (; attempt < maxAttempts && resetError == null; attempt++) {
                try {
                    var call = client.newCall(new Request.Builder().url(assetUrl).build());
                    try (var response = call.execute()) {
                        observedProtocol = response.protocol();
                        response.body().byteStream().read(); // read the first byte: server is mid-stream
                        call.cancel(); // reset the stream (HTTP/2) or close the connection (HTTP/1.1)
                    }
                } catch (Exception e) {
                    // Cancelling mid-read routinely throws on the client side; irrelevant here.
                    // The only signal that matters is the server-side log.
                }
                resetError = awaitResetError(perAttemptWait);
            }
        } finally {
            client.dispatcher().executorService().shutdown();
            client.connectionPool().evictAll();
        }
        return new CancelLoopOutcome(observedProtocol, resetError, attempt);
    }

    private LogRecord awaitResetError(Duration timeout) {
        var deadlineNanos = System.nanoTime() + timeout.toNanos();
        do {
            var found = findResetError();
            if (found != null) {
                return found;
            }
            try {
                Thread.sleep(Duration.ofMillis(10));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return findResetError();
            }
        } while (System.nanoTime() < deadlineNanos);
        return findResetError();
    }

    private LogRecord findResetError() {
        synchronized (records) {
            for (var record : records) {
                if (record.getLevel().intValue() < Level.SEVERE.intValue()) {
                    continue;
                }
                if (record.getThrown() instanceof IllegalArgumentException iae
                        && iae.getMessage() != null
                        && iae.getMessage().startsWith(RESET_MESSAGE_PREFIX)) {
                    return record;
                }
            }
        }
        return null;
    }

    private static boolean isHttp2(Protocol protocol) {
        return protocol == Protocol.HTTP_2 || protocol == Protocol.H2_PRIOR_KNOWLEDGE;
    }

    private record CancelLoopOutcome(Protocol observedProtocol, LogRecord resetError, int attempts) {}
}
