package org.locationbridge.source;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public final class FlightLogStoreTest {
    private static final String HEADER =
        "{\"type\":\"session_start\",\"schema\":1,\"utc_ms\":42,\"elapsed_ns\":17}";
    private static final String SAMPLE = "{\"type\":\"sample\",\"name\":\"测试\"}";

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("flight-log-test-");
        try {
            writesCompleteSessions(root.resolve("sessions"));
            flushesBeforeStop(root.resolve("flush"));
            listsRecoverableSessions(root.resolve("recovery"));
            copiesOnlyCompleteLines(root.resolve("export.jsonl"));
            retriesFailedOpen(root.resolve("blocked"));
            accountsForBackpressure(root.resolve("backpressure"));
        } finally {
            try (Stream<Path> paths = Files.walk(root)) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) {
                    Files.delete(path);
                }
            }
        }
        System.out.println("FlightLogStore tests passed");
    }

    private static void writesCompleteSessions(Path directory) throws IOException {
        FlightLogStore store = new FlightLogStore(directory.toFile());
        store.offer(SAMPLE);
        check(!store.isRecording() && store.error() == null, "initial state is idle");
        check(store.start(42, 17), "first session starts");
        try {
            check(store.isRecording(), "recording starts with the written header");
            check(!store.start(43, 18) && store.error() == null, "duplicate start keeps session");
            store.offer(SAMPLE);
        } finally {
            store.stop(47, 23);
        }
        store.stop();
        store.offer(SAMPLE);
        check(!store.isRecording() && store.error() == null, "successful stop is idle");
        File first = onlyRecording(directory);
        List<String> firstLines = Files.readAllLines(first.toPath(), StandardCharsets.UTF_8);
        check(firstLines.size() == 3, "stop drains accepted samples and writes one footer");
        check(firstLines.get(0).equals(HEADER), "header declares time anchors and schema");
        check(firstLines.get(1).equals(SAMPLE), "sample is preserved as UTF-8 JSON");
        check(firstLines.get(2).equals(
            "{\"type\":\"session_end\",\"utc_ms\":47,\"elapsed_ns\":23,\"dropped\":0}"),
            "footer preserves the explicit stop times and drop count");

        check(store.start(42, 37), "a new session starts for the same UTC millisecond");
        store.stop();
        check(FlightLogStore.recordings(directory.toFile()).length == 2, "sessions have unique files");
        check(Files.readAllLines(first.toPath(), StandardCharsets.UTF_8).equals(firstLines),
            "the new session leaves the previous session intact");
    }

    private static void flushesBeforeStop(Path directory) throws Exception {
        FlightLogStore store = new FlightLogStore(directory.toFile());
        check(store.start(42, 17), "flush session starts");
        try {
            Path recording = onlyRecording(directory).toPath();
            check(Files.readAllLines(recording, StandardCharsets.UTF_8).get(0).equals(HEADER),
                "header is readable immediately after start");
            store.offer(SAMPLE);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            while (!Files.readAllLines(recording, StandardCharsets.UTF_8).contains(SAMPLE)) {
                check(System.nanoTime() < deadline, "writer flushes without requiring stop");
                Thread.sleep(10);
            }
        } finally {
            store.stop();
        }
    }

    private static void listsRecoverableSessions(Path directory) throws IOException {
        Files.createDirectories(directory);
        Path incomplete = directory.resolve("flight-log-42-123.jsonl");
        Files.write(incomplete, (HEADER + "\n" + SAMPLE + "\n{\"type\":").getBytes(StandardCharsets.UTF_8));
        Files.write(directory.resolve("flight-log-45-124.jsonl"), HEADER.getBytes(StandardCharsets.UTF_8));
        Files.write(directory.resolve("flight-log-44-125.jsonl"), (SAMPLE + "\n").getBytes(StandardCharsets.UTF_8));
        Files.write(directory.resolve("other.jsonl"), (HEADER + "\n").getBytes(StandardCharsets.UTF_8));
        Files.createDirectory(directory.resolve("flight-log-46-126.jsonl"));

        FlightLogStore store = new FlightLogStore(directory.toFile());
        check(store.start(101, 17), "new session starts alongside recovered file");
        store.stop();
        File[] recordings = FlightLogStore.recordings(directory.toFile());
        check(recordings.length == 2, "listing includes only usable session files");
        check(recordings[1].toPath().equals(incomplete), "interrupted file remains readable and older");
    }

    private static void retriesFailedOpen(Path directory) throws IOException {
        Files.createFile(directory);
        FlightLogStore store = new FlightLogStore(directory.toFile());
        check(!store.start(42, 17), "directory conflict prevents recording");
        check(!store.isRecording() && "open_failed".equals(store.error()), "open failure is visible");
        check(FlightLogStore.recordings(directory.toFile()).length == 0, "failed directory lists nothing");
        Files.delete(directory);
        check(store.start(43, 18), "the same store can retry after an open failure");
        store.stop();
        check(store.error() == null, "successful retry clears the error");
    }

    private static void copiesOnlyCompleteLines(Path source) throws IOException {
        byte[] complete = (HEADER + "\n" + SAMPLE + "\n").getBytes(StandardCharsets.UTF_8);
        byte[] interrupted = Arrays.copyOf(complete, complete.length + 23_000);
        Arrays.fill(interrupted, complete.length, interrupted.length, (byte) 'x');
        Files.write(source, interrupted);

        ByteArrayOutputStream destination = new ByteArrayOutputStream();
        FlightLogStore.copyCompleteLines(source.toFile(), destination);
        check(Arrays.equals(destination.toByteArray(), complete),
            "export keeps the complete UTF-8 prefix and skips an interrupted tail spanning blocks");

        destination.reset();
        Files.write(source, complete);
        FlightLogStore.copyCompleteLines(source.toFile(), destination);
        check(Arrays.equals(destination.toByteArray(), complete), "export retains an intact final line");

        destination.reset();
        Files.write(source, HEADER.getBytes(StandardCharsets.UTF_8));
        FlightLogStore.copyCompleteLines(source.toFile(), destination);
        check(destination.size() == 0, "export emits nothing when no line has ended");
    }

    private static void accountsForBackpressure(Path directory) throws IOException {
        FlightLogStore store = new FlightLogStore(directory.toFile());
        check(store.start(42, 17), "backpressure session starts");
        int offered = 100_000;
        try {
            for (int i = 0; i < offered; i++) store.offer(SAMPLE);
        } finally {
            store.stop();
        }
        List<String> lines = Files.readAllLines(onlyRecording(directory).toPath(), StandardCharsets.UTF_8);
        long accepted = lines.stream().filter(SAMPLE::equals).count();
        String footer = lines.get(lines.size() - 1);
        check(footer.endsWith(",\"dropped\":" + (offered - accepted) + "}"),
            "every offered sample is either flushed or counted as dropped");
        if (accepted < offered) {
            check(lines.stream().anyMatch(line -> line.contains("\"type\":\"samples_dropped\"")),
                "queue overflow is visible before the footer");
        }
    }

    private static File onlyRecording(Path directory) {
        File[] recordings = FlightLogStore.recordings(directory.toFile());
        check(recordings.length == 1, "a single recording should be present");
        return recordings[0];
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
