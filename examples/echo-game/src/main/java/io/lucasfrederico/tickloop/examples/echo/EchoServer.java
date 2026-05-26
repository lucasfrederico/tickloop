package io.lucasfrederico.tickloop.examples.echo;

import io.lucasfrederico.tickloop.TickLoop;
import io.lucasfrederico.tickloop.TickQueue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Minimal demo: a TCP server that broadcasts the current tick number to
 * every connected client every second.
 *
 * <p>Demonstrates three patterns from the library:
 * <ul>
 *   <li>{@link TickLoop} runs the broadcast at a fixed rate (1 Hz).</li>
 *   <li>{@link TickQueue} carries new connections from the acceptor thread
 *       into the loop thread. The acceptor cannot mutate the clients map
 *       directly — it must go through the queue.</li>
 *   <li>{@link TickLoop#runOnMain runOnMain} is used by reader threads
 *       (one per client) to deliver incoming messages back to the loop
 *       thread, which then echoes them to every other connected client.</li>
 * </ul>
 *
 * <p>Run with: {@code mvn -pl examples/echo-game exec:java}
 * (or compile and run the jar manually).
 *
 * <p>Connect with: {@code nc localhost 8765}
 */
public final class EchoServer {

    private static final int PORT = 8765;

    public static void main(String[] args) throws Exception {
        // Single source of truth: connected clients. ONLY mutated on the loop thread.
        Map<Long, ClientHandle> clients = new LinkedHashMap<>();
        AtomicLong clientIdSeq = new AtomicLong();

        TickLoop loop = TickLoop.builder()
                .tickPeriod(Duration.ofSeconds(1))
                .threadName("echo-loop")
                .onTick(tick -> {
                    if (clients.isEmpty()) {
                        return;
                    }
                    String banner = "[server] tick=" + tick + " clients=" + clients.size() + "\n";
                    broadcast(clients, banner);
                })
                .build();

        // Queue carrying newly accepted sockets from the acceptor thread.
        TickQueue<Socket> newConnections = loop.createQueue("new-connections", socket -> {
            long id = clientIdSeq.incrementAndGet();
            try {
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                out.println("[server] welcome client " + id + ". tick rate: 1 Hz.");
                ClientHandle handle = new ClientHandle(id, socket, out);
                clients.put(id, handle);

                // Reader thread: one per client. Calls runOnMain on each line.
                Thread reader = new Thread(() -> readLoop(loop, clients, handle), "echo-reader-" + id);
                reader.setDaemon(true);
                reader.start();

                System.out.println("[server] client " + id + " connected from "
                        + socket.getRemoteSocketAddress());
            } catch (IOException e) {
                quietlyClose(socket);
                System.out.println("[server] failed to initialize client: " + e.getMessage());
            }
        });

        // Acceptor thread: not a tickloop offload because accept() is an
        // infinite blocking loop. The right primitive here is a dedicated
        // thread that bridges into the loop via TickQueue.
        ServerSocket serverSocket = new ServerSocket(PORT);
        Thread acceptor = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Socket client = serverSocket.accept();
                    newConnections.offer(client);
                } catch (IOException e) {
                    break;
                }
            }
        }, "echo-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                loop.stop();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            quietlyClose(serverSocket);
        }, "echo-shutdown"));

        System.out.println("[server] listening on port " + PORT + " (connect with: nc localhost " + PORT + ")");
        loop.start();
    }

    private static void readLoop(TickLoop loop, Map<Long, ClientHandle> clients, ClientHandle handle) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(handle.socket.getInputStream()))) {
            String line;
            while ((line = in.readLine()) != null) {
                final String message = line;
                // Crossing back into the loop thread to mutate the broadcast.
                loop.runOnMain(() -> {
                    String packet = "[client " + handle.id + "] " + message + "\n";
                    broadcast(clients, packet);
                });
            }
        } catch (IOException ignored) {
            // client disconnected
        } finally {
            loop.runOnMain(() -> {
                ClientHandle removed = clients.remove(handle.id);
                if (removed != null) {
                    quietlyClose(removed.socket);
                    System.out.println("[server] client " + handle.id + " disconnected");
                }
            });
        }
    }

    private static void broadcast(Map<Long, ClientHandle> clients, String packet) {
        clients.values().removeIf(handle -> {
            try {
                handle.out.print(packet);
                handle.out.flush();
                return handle.out.checkError();
            } catch (RuntimeException e) {
                return true;
            }
        });
    }

    private static void quietlyClose(java.io.Closeable c) {
        try {
            if (c != null) c.close();
        } catch (IOException ignored) {
            // best-effort
        }
    }

    private static final class ClientHandle {
        final long id;
        final Socket socket;
        final PrintWriter out;

        ClientHandle(long id, Socket socket, PrintWriter out) {
            this.id = id;
            this.socket = socket;
            this.out = out;
        }
    }
}
