package dev.lain.claudejb.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class StdioBridge {

    public static final String TOKEN_KEY = "dev.lain.claudejb/token";
    public static final String TOKEN_FILE = "token";

    private final Path socket;
    private final Path tokenFile;
    private final PrintStream stdout;

    StdioBridge(Path socket, PrintStream stdout) {
        this.socket = socket;
        this.tokenFile = socket.resolveSibling(TOKEN_FILE);
        this.stdout = stdout;
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("usage: StdioBridge <socket>");
            System.exit(2);
        }
        PrintStream stdout = new PrintStream(System.out, false, StandardCharsets.UTF_8);
        new StdioBridge(Path.of(args[0]), stdout).pump(System.in);
    }

    void pump(InputStream stdin) throws IOException {
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(UnixDomainSocketAddress.of(socket));
            OutputStream toPlugin = Channels.newOutputStream(channel);
            InputStream fromPlugin = Channels.newInputStream(channel);
            Thread replies = new Thread(() -> relayReplies(fromPlugin), "mcp-replies");
            replies.setDaemon(true);
            replies.start();
            relayRequests(stdin, toPlugin);
            channel.shutdownOutput();
            waitFor(replies);
        }
    }

    private void relayRequests(InputStream stdin, OutputStream toPlugin) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(stdin, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) continue;
            Object message;
            try {
                message = Json.parse(line);
            } catch (IllegalArgumentException e) {
                emit(Json.write(parseError()));
                continue;
            }
            synchronized (toPlugin) {
                Frames.write(toPlugin, Toon.encode(withToken(message)));
            }
        }
    }

    private void relayReplies(InputStream fromPlugin) {
        try {
            String frame;
            while ((frame = Frames.read(fromPlugin)) != null) {
                emit(Json.write(Toon.decode(frame)));
            }
        } catch (IOException | ToonException e) {
            System.err.println("StdioBridge: " + e.getMessage());
        }
    }

    private Object withToken(Object message) {
        if (!(message instanceof Map)) return message;
        @SuppressWarnings("unchecked")
        Map<String, Object> request = (Map<String, Object>) message;
        if (!request.containsKey("method")) return request;
        Object params = request.get("params");
        Map<String, Object> paramsMap = params instanceof Map ? cast(params) : new LinkedHashMap<>();
        Object meta = paramsMap.get("_meta");
        Map<String, Object> metaMap = meta instanceof Map ? cast(meta) : new LinkedHashMap<>();
        String token = readToken();
        if (token != null) metaMap.put(TOKEN_KEY, token);
        paramsMap.put("_meta", metaMap);
        request.put("params", paramsMap);
        return request;
    }

    private String readToken() {
        try {
            return Files.readString(tokenFile, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return null;
        }
    }

    private void emit(String line) {
        synchronized (stdout) {
            stdout.print(line);
            stdout.print('\n');
            stdout.flush();
        }
    }

    private static Map<String, Object> parseError() {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", -32700);
        error.put("message", "Parse error");
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", null);
        response.put("error", error);
        return response;
    }

    private static void waitFor(Thread thread) {
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object value) {
        return (Map<String, Object>) value;
    }
}
