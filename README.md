# quarkus-http2-iae-on-stream-gone

Minimal reproducer showing Quarkus' `QuarkusErrorHandler` logging a **benign HTTP/2 client stream reset at ERROR**.

When an HTTP/2 client cancels a stream while the server is still streaming a static resource, Netty throws `java.lang.IllegalArgumentException: Stream no longer exists: N`. That throwable reaches `QuarkusErrorHandler`, which only downgrades the log to DEBUG when the *top-level* failure is an `IOException`. An `IllegalArgumentException` is not, so a routine client disconnect is logged at ERROR and pollutes error-rate SLOs.

This is the HTTP/2 variant of [quarkus#33980](https://github.com/quarkusio/quarkus/issues/33980) where a client disconnect does try to log at DEBUG.

## Issue

A static resource served from `META-INF/resources` goes through Vert.x `StaticHandler` -> `HttpServerResponse.sendFile` -> `AsyncFile` -> `Pipe` -> `Http2ServerResponse.write`. The `AsyncFile` reads the file in 8 KiB chunks and pipes each chunk to the response as a separate DATA write.

The IAE only fires when Vert.x issues an outbound DATA write **after** the client's `RST_STREAM` has been processed (Netty has already removed the stream). So three conditions must all hold:

* HTTP/2: Over HTTP/1.1 a mid-write disconnect is an `IOException` (already DEBUG). There is no per-stream reset.
* Multiple DATA frames: A body that fits in a single `writeData` cannot race. The asset must exceed the 8 KiB `AsyncFile` read buffer; this repo generates a 4 MiB file at build time.
* A cancel mid-stream: The client reads the first byte (server is now streaming) then sends `RST_STREAM(CANCEL)` while keeping the connection open.

The default HTTP/2 flow-control window (~64 KiB) helps: the server sends the first window, parks, and its in-flight `AsyncFile` read completes *after* the reset arrives, calling `writeData` on the now-removed stream.

`QuarkusErrorHandler` (Quarkus 3.37.3) classifies the failure like this, with no recursive cause inspection, so anything whose top-level type is not `IOException` is ERROR-logged:

```java
if (exception instanceof IOException) {
    log.debugf(exception, "IOError processing HTTP request to %s failed, the client likely terminated the connection. Error id: %s",
        event.request().uri(), uuid);
} else {
    log.errorf(exception, "HTTP Request to %s failed, error id: %s", event.request().uri(), uuid);
}
```

## Running the tests

```bash
./gradlew clean test
```

The tests encode the *expected* behaviour (a benign client disconnect must not be ERROR-logged), so the HTTP/2 test **fails** on the current Quarkus release.

| Test | On Quarkus 3.37.3 | What it asserts |
|------|------------------|-----------------|
| `http2StreamReset_shouldNotBeLoggedAtError` | **FAIL** | Over cleartext HTTP/2, cancelling mid-stream must not make `QuarkusErrorHandler` log at ERROR. It does: `IllegalArgumentException: Stream no longer exists` at ERROR |
| `http1StreamReset_isNotLoggedAtError` | PASS | Over HTTP/1.1 the same cancel does **not** produce that ERROR (it is a DEBUG `IOException`), which isolates the bug to HTTP/2 |


## Requirements

* Java 25
* Quarkus 3.37.3 (reproduces on the current 3.37 release; see `build.gradle.kts`)
* Gradle 9.6.1 (via the wrapper)
