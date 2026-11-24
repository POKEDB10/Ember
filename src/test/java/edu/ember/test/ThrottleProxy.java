package edu.ember.test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * A bad network for testing the call relay: a TCP proxy with a bandwidth limit, extra delay and a
 * router queue (64 KB per direction unless given), following a timed profile. JDK only.
 * Usage: {@code ThrottleProxy <listenPort> <targetPort> <profile> [queueKB]}, where the profile is
 * {@code seconds:kbps:delayMs,...}, e.g. {@code 0:20000:0,20:400:40,50:20000:0}. A queue of a few
 * MB behaves like a tunnel or proxy that buffers seconds of data (bufferbloat).
 * shortcut: TCP only (the WebSocket relay); direct WebRTC uses UDP and needs an OS-level tool.
 */
public final class ThrottleProxy {
    private static final int CHUNK = 4096;
    private static int queueChunks = 16;
    private static long startMs;
    private static int[][] profile;

    private ThrottleProxy() {
    }

    public static void main(String[] args) throws IOException {
        int listen = Integer.parseInt(args[0]);
        int target = Integer.parseInt(args[1]);
        profile = Arrays.stream(args[2].split(",")).map(step -> Arrays.stream(step.split(":"))
                .mapToInt(Integer::parseInt).toArray()).toArray(int[][]::new);
        if (args.length > 3) {
            queueChunks = Math.max(1, Integer.parseInt(args[3]) * 1024 / CHUNK);
        }
        startMs = System.currentTimeMillis();
        try (ServerSocket server = new ServerSocket(listen, 50, InetAddress.getLoopbackAddress())) {
            System.out.println("throttle proxy " + listen + " -> " + target + " profile " + args[2] + " start " + java.time.Instant.ofEpochMilli(startMs));
            while (true) {
                Socket client = server.accept();
                Socket upstream = new Socket(InetAddress.getLoopbackAddress(), target);
                client.setTcpNoDelay(true);
                upstream.setTcpNoDelay(true);
                pipe(client, upstream);
                pipe(upstream, client);
            }
        }
    }

    /** The profile step in force now: {kbps, delayMs}. */
    static int[] now() {
        long seconds = (System.currentTimeMillis() - startMs) / 1000;
        int[] current = profile[0];
        for (int[] step : profile) {
            if (step[0] <= seconds) {
                current = step;
            }
        }
        return new int[] {current[1], current[2]};
    }

    private record Chunk(byte[] data, long arrivedMs) { }

    private static void pipe(Socket from, Socket to) {
        BlockingQueue<Chunk> queue = new ArrayBlockingQueue<>(queueChunks); // full queue = TCP back-pressure
        Thread reader = new Thread(() -> {
            byte[] buffer = new byte[CHUNK];
            try (InputStream in = from.getInputStream()) {
                for (int read; (read = in.read(buffer)) > 0; ) {
                    queue.put(new Chunk(Arrays.copyOf(buffer, read), System.currentTimeMillis()));
                }
            } catch (IOException | InterruptedException closed) {
                // connection over
            } finally {
                queue.offer(new Chunk(new byte[0], 0));
            }
        });
        Thread writer = new Thread(() -> {
            long nextFreeNs = System.nanoTime();
            try (OutputStream out = to.getOutputStream()) {
                while (true) {
                    Chunk chunk = queue.take();
                    if (chunk.data().length == 0) {
                        break;
                    }
                    int[] link = now();
                    long wait = chunk.arrivedMs() + link[1] - System.currentTimeMillis();
                    if (wait > 0) {
                        Thread.sleep(wait);
                    }
                    nextFreeNs = Math.max(nextFreeNs, System.nanoTime())
                            + chunk.data().length * 8_000_000L / Math.max(1, link[0]);
                    long pace = nextFreeNs - System.nanoTime();
                    if (pace > 5_000_000) { // Windows sleeps in ~1-15 ms steps: let small debts build up first
                        Thread.sleep(pace / 1_000_000, (int) (pace % 1_000_000));
                    }
                    out.write(chunk.data());
                    out.flush();
                }
            } catch (IOException | InterruptedException closed) {
                // connection over
            } finally {
                try {
                    from.close();
                    to.close();
                } catch (IOException ignored) {
                    // already closed
                }
            }
        });
        reader.setDaemon(true);
        writer.setDaemon(true);
        reader.start();
        writer.start();
    }
}
