package dev.lain.claudejb.mcp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public final class Frames {

    public static final int MAX_FRAME_BYTES = 64 * 1024 * 1024;

    private Frames() {
    }

    public static void write(OutputStream out, String text) throws IOException {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        out.write((payload.length + "\n").getBytes(StandardCharsets.US_ASCII));
        out.write(payload);
        out.flush();
    }

    public static String read(InputStream in) throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        while (true) {
            int b = in.read();
            if (b < 0) return header.size() == 0 ? null : fail("stream ended inside a frame header");
            if (b == '\n') break;
            if (b < '0' || b > '9' || header.size() > 9) return fail("malformed frame header");
            header.write(b);
        }
        int length = Integer.parseInt(header.toString(StandardCharsets.US_ASCII));
        if (length > MAX_FRAME_BYTES) return fail("frame of " + length + " bytes exceeds the ceiling");
        byte[] payload = in.readNBytes(length);
        if (payload.length != length) return fail("stream ended inside a frame body");
        return new String(payload, StandardCharsets.UTF_8);
    }

    private static String fail(String message) throws IOException {
        throw new IOException(message);
    }
}
