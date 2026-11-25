package org.locationbridge.source;

import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Owns one local diagnostic session at a time. Start, stop and listing run off the UI thread. */
final class FlightLogStore {
    private static final String FILE_PREFIX = "flight-log-";
    private static final String FILE_EXTENSION = ".jsonl";
    private static final Pattern RECORDING_NAME =
        Pattern.compile("flight-log--?\\d{1,19}-[a-zA-Z0-9_-]+\\.jsonl");
    private static final Pattern SESSION_HEADER =
        Pattern.compile("\\{\"type\":\"session_start\",\"schema\":\\d+,\"utc_ms\":-?\\d+,\"elapsed_ns\":-?\\d+\\}");
    private static final int MAX_PENDING_LINES = 1024;
    private static final int MAX_LINE_CHARS = 16_384;
    private static final int MAX_HEADER_BYTES = 160;
    private static final long FLUSH_INTERVAL_NS = TimeUnit.SECONDS.toNanos(1);

    private final File directory;
    private volatile Session session;
    private volatile String failure;

    FlightLogStore(File directory) {
        this.directory = directory;
    }

    synchronized boolean start(long utcMillis, long elapsedNanos) {
        Session previous = session;
        if (previous != null) {
            if (previous.accepting) return false;
            previous.awaitWriter();
            session = null;
        }

        long nanoTimeAtStart = System.nanoTime();
        FileOutputStream stream = null;
        try {
            if (!directory.mkdirs() && !directory.isDirectory()) throw new IOException();
            File file = Files.createTempFile(directory.toPath(), FILE_PREFIX + utcMillis + "-", FILE_EXTENSION)
                .toFile();
            stream = new FileOutputStream(file);
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(stream, StandardCharsets.UTF_8));
            writeLine(writer, "{\"type\":\"session_start\",\"schema\":1,\"utc_ms\":" + utcMillis
                + ",\"elapsed_ns\":" + elapsedNanos + "}");
            writer.flush();
            Session next = new Session(stream, writer, elapsedNanos, nanoTimeAtStart);
            failure = null;
            next.thread.start();
            session = next;
            return true;
        } catch (IOException | SecurityException error) {
            failure = "open_failed";
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException ignored) {}
            }
            return false;
        }
    }

    void offer(String jsonLine) {
        Session current = session;
        if (current != null && current.accepting) current.enqueue(jsonLine);
    }

    boolean isRecording() {
        Session current = session;
        return current != null && current.accepting;
    }

    String error() {
        return failure;
    }

    synchronized void stop() {
        Session current = session;
        if (current != null) {
            stop(System.currentTimeMillis(), current.elapsedNanosAtStart
                + (System.nanoTime() - current.nanoTimeAtStart));
        }
    }

    synchronized void stop(long utcMillis, long elapsedNanos) {
        Session current = session;
        if (current != null) {
            current.finish(utcMillis, elapsedNanos);
            current.awaitWriter();
            session = null;
        }
    }

    static File[] recordings(File directory) {
        File[] files = directory.listFiles(file -> RECORDING_NAME.matcher(file.getName()).matches()
            && file.isFile() && hasSessionHeader(file));
        if (files == null) return new File[0];
        Arrays.sort(files, Comparator.comparingLong(FlightLogStore::recordingStart)
            .thenComparing(FlightLogStore::recordingModified).thenComparing(File::getName).reversed());
        return files;
    }

    /** Exports an append-only snapshot through its last LF, leaving the destination open. */
    static void copyCompleteLines(File source, OutputStream destination) throws IOException {
        byte[] buffer = new byte[8192];
        try (RandomAccessFile input = new RandomAccessFile(source, "r")) {
            long remaining = completeLength(input, buffer);
            input.seek(0);
            while (remaining > 0) {
                int count = (int) Math.min(remaining, buffer.length);
                input.readFully(buffer, 0, count);
                destination.write(buffer, 0, count);
                remaining -= count;
            }
        }
    }

    private static long completeLength(RandomAccessFile input, byte[] buffer) throws IOException {
        long end = input.length();
        while (end > 0) {
            int count = (int) Math.min(end, buffer.length);
            long start = end - count;
            input.seek(start);
            input.readFully(buffer, 0, count);
            for (int i = count - 1; i >= 0; i--) {
                if (buffer[i] == '\n') return start + i + 1;
            }
            end = start;
        }
        return 0;
    }

    private static long recordingStart(File file) {
        String name = file.getName();
        int end = name.indexOf('-', FILE_PREFIX.length() + 1);
        try {
            return Long.parseLong(name.substring(FILE_PREFIX.length(), end));
        } catch (NumberFormatException error) {
            return Long.MIN_VALUE;
        }
    }

    private static FileTime recordingModified(File file) {
        try {
            return Files.getLastModifiedTime(file.toPath());
        } catch (IOException | SecurityException error) {
            return FileTime.fromMillis(0);
        }
    }

    private static boolean hasSessionHeader(File file) {
        try (InputStream input = new BufferedInputStream(Files.newInputStream(file.toPath()), MAX_HEADER_BYTES)) {
            StringBuilder header = new StringBuilder();
            for (int i = 0; i < MAX_HEADER_BYTES; i++) {
                int character = input.read();
                if (character == '\n') return SESSION_HEADER.matcher(header).matches();
                if (character == -1) return false;
                header.append((char) character);
            }
        } catch (IOException | SecurityException ignored) {}
        return false;
    }

    private static void writeLine(BufferedWriter writer, String line) throws IOException {
        writer.write(line);
        writer.write('\n');
    }

    private final class Session {
        final Thread thread;
        final long elapsedNanosAtStart;
        final long nanoTimeAtStart;
        volatile boolean accepting = true;
        private final ArrayDeque<String> pending = new ArrayDeque<>();
        private long stopUtcMillis;
        private long stopElapsedNanos;
        private long dropped;

        Session(FileOutputStream stream, BufferedWriter writer, long elapsedNanos, long nanoTime) {
            elapsedNanosAtStart = elapsedNanos;
            nanoTimeAtStart = nanoTime;
            thread = new Thread(() -> writeSession(stream, writer), "flight-log-writer");
            thread.setDaemon(true);
        }

        synchronized void enqueue(String jsonLine) {
            if (!accepting) return;
            if (pending.size() >= MAX_PENDING_LINES || jsonLine == null || jsonLine.isEmpty()
                || jsonLine.length() > MAX_LINE_CHARS || jsonLine.indexOf('\n') >= 0 || jsonLine.indexOf('\r') >= 0) {
                dropped++;
                return;
            }
            pending.addLast(jsonLine);
            notify();
        }

        synchronized String poll(long nanosToWait) throws InterruptedException {
            if (pending.isEmpty() && accepting && nanosToWait > 0) {
                TimeUnit.NANOSECONDS.timedWait(this, nanosToWait);
            }
            return pending.pollFirst();
        }

        synchronized boolean isDrained() {
            return !accepting && pending.isEmpty();
        }

        synchronized long droppedCount() {
            return dropped;
        }

        synchronized void finish(long utcMillis, long elapsedNanos) {
            stopUtcMillis = utcMillis;
            stopElapsedNanos = elapsedNanos;
            accepting = false;
            notify();
        }

        void awaitWriter() {
            boolean interrupted = false;
            for (;;) {
                try {
                    thread.join();
                    break;
                } catch (InterruptedException error) {
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }

        @SuppressWarnings("try")
        private void writeSession(FileOutputStream stream, BufferedWriter writer) {
            // Close the stream directly so a failed write does not retry buffered bytes on close.
            try (FileOutputStream output = stream) {
                long nextFlush = System.nanoTime() + FLUSH_INTERVAL_NS;
                long reportedDropped = 0;
                while (true) {
                    String line = poll(nextFlush - System.nanoTime());
                    if (line != null) writeLine(writer, line);
                    if (line == null && isDrained()) break;
                    if (System.nanoTime() - nextFlush >= 0) {
                        reportedDropped = writeDropped(writer, reportedDropped);
                        writer.flush();
                        nextFlush = System.nanoTime() + FLUSH_INTERVAL_NS;
                    }
                }
                long totalDropped = writeDropped(writer, reportedDropped);
                writeLine(writer, "{\"type\":\"session_end\",\"utc_ms\":" + stopUtcMillis
                    + ",\"elapsed_ns\":" + stopElapsedNanos + ",\"dropped\":" + totalDropped + "}");
                writer.flush();
            } catch (IOException | InterruptedException | SecurityException error) {
                failure = "write_failed";
                synchronized (this) {
                    accepting = false;
                    pending.clear();
                }
            }
        }

        private long writeDropped(BufferedWriter writer, long reportedDropped) throws IOException {
            long totalDropped = droppedCount();
            if (totalDropped != reportedDropped) {
                writeLine(writer, "{\"type\":\"samples_dropped\",\"dropped\":" + totalDropped + "}");
            }
            return totalDropped;
        }
    }
}
