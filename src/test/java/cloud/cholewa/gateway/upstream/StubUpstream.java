package cloud.cholewa.gateway.upstream;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

//a target service made of a plain ServerSocket, because the failure of HAS-212 is a TCP reset on a
//connection the gateway keeps in its pool: closing a socket with SO_LINGER 0 sends an RST instead of
//a FIN, and no HTTP mock server offers that. It keeps every connection alive, answers 200 with a
//small JSON body and can be told to break the next requests in one of two ways
final class StubUpstream implements AutoCloseable {

    enum Failure {
        //the request is read and the connection is reset without a single byte of an answer
        RESET_BEFORE_RESPONSE,
        //the status line and the headers are sent and the connection is closed before the body. A
        //close and not a reset on purpose: a reset throws away what the other side has not read
        //yet, so whether the gateway ever sees the headers would be a race; after a close it does
        CLOSE_AFTER_HEADERS
    }

    record Exchange(int connection, int requestOnConnection, String method, String path, int bodyLength) {
    }

    static final String BODY = "{\"water\":41.5}";
    static final String ETAG = "\"reading-1\"";

    private final ServerSocket serverSocket;
    private final List<Exchange> exchanges = new CopyOnWriteArrayList<>();
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final AtomicInteger connections = new AtomicInteger();
    private final AtomicInteger connectionsClosedByPeer = new AtomicInteger();
    private final AtomicInteger failuresLeft = new AtomicInteger();
    private final AtomicReference<Failure> failure = new AtomicReference<>(Failure.RESET_BEFORE_RESPONSE);

    StubUpstream() {
        try {
            serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        } catch (IOException e) {
            throw new IllegalStateException("The stub upstream could not open a port", e);
        }

        Thread acceptor = new Thread(this::accept, "stub-upstream-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    int port() {
        return serverSocket.getLocalPort();
    }

    //the next requests - whichever connection they arrive on - are broken instead of answered
    void failNext(final int requests, final Failure how) {
        failure.set(how);
        failuresLeft.set(requests);
    }

    //forgets what was recorded; the connections the gateway still holds stay open and keep their numbers
    void reset() {
        failuresLeft.set(0);
        exchanges.clear();
    }

    List<Exchange> exchanges() {
        return List.copyOf(exchanges);
    }

    int connections() {
        return connections.get();
    }

    //connections the other side ended while the stub was waiting for a request on them
    int connectionsClosedByPeer() {
        return connectionsClosedByPeer.get();
    }

    @Override
    public void close() throws IOException {
        serverSocket.close();
        for (Socket socket : sockets) {
            socket.close();
        }
    }

    private void accept() {
        while (!serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                sockets.add(socket);
                int connection = connections.incrementAndGet();

                Thread handler = new Thread(() -> serve(socket, connection), "stub-upstream-" + connection);
                handler.setDaemon(true);
                handler.start();
            } catch (IOException e) {
                //the server socket was closed, the loop ends with its condition
            }
        }
    }

    private void serve(final Socket socket, final int connection) {
        try (socket) {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            int requestOnConnection = 0;

            while (true) {
                String head = readHead(in);
                if (head == null) {
                    connectionsClosedByPeer.incrementAndGet();
                    return;
                }
                int bodyLength = readBody(in, head);

                String[] requestLine = head.substring(0, head.indexOf("\r\n")).split(" ");
                requestOnConnection++;
                exchanges.add(
                    new Exchange(connection, requestOnConnection, requestLine[0], requestLine[1], bodyLength)
                );

                if (failuresLeft.getAndUpdate(left -> Math.max(0, left - 1)) > 0) {
                    if (failure.get() == Failure.CLOSE_AFTER_HEADERS) {
                        out.write(head().getBytes(StandardCharsets.US_ASCII));
                        out.flush();
                    } else {
                        //with a linger of zero close() drops the connection with an RST
                        socket.setSoLinger(true, 0);
                    }
                    return;
                }

                out.write((head() + BODY).getBytes(StandardCharsets.US_ASCII));
                out.flush();
            }
        } catch (IOException e) {
            //the gateway dropped the connection in the middle of an exchange; nothing to answer
        } finally {
            sockets.remove(socket);
        }
    }

    //with two headers a caller must never get together with an error of the gateway
    private static String head() {
        return "HTTP/1.1 200 OK\r\n"
            + "Content-Type: application/json\r\n"
            + "Cache-Control: max-age=3600\r\n"
            + "ETag: " + ETAG + "\r\n"
            + "Content-Length: " + BODY.length() + "\r\n"
            + "\r\n";
    }

    //the request line and the headers, or null when the other side closed the connection
    private static String readHead(final InputStream in) throws IOException {
        StringBuilder head = new StringBuilder();

        while (head.length() < 4 || !"\r\n\r\n".contentEquals(head.subSequence(head.length() - 4, head.length()))) {
            int next = in.read();
            if (next < 0) {
                return null;
            }
            head.append((char) next);
        }

        return head.toString();
    }

    //reads the body a Content-Length announces and answers how long it was
    private static int readBody(final InputStream in, final String head) throws IOException {
        for (String line : head.split("\r\n")) {
            if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                int length = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                if (in.readNBytes(length).length < length) {
                    throw new IOException("The request body broke off");
                }
                return length;
            }
        }

        return 0;
    }
}
