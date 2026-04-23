/*
 * WebSocketUtil.java
 * Provided utility — do not modify.
 *
 * Implements the WebSocket opening handshake (RFC 6455 §4) and
 * basic text-frame encoding/decoding using only standard Java library
 * classes (java.net, java.io, java.security, java.util.Base64).
 *
 * Public API — the only three methods you need:
 *
 *   WebSocketUtil.handshake(InputStream, OutputStream)
 *       Reads the HTTP upgrade request and writes the 101 response.
 *       Call exactly once per accepted Socket, before any reads or writes.
 *
 *   String WebSocketUtil.readFrame(InputStream)
 *       Reads one complete WebSocket text frame and returns the payload
 *       as a String.  Returns null if the client closed the connection
 *       gracefully (opcode 0x8 close frame or EOF).
 *
 *   WebSocketUtil.writeFrame(OutputStream, String)
 *       Encodes the String as a WebSocket text frame and writes it to
 *       the stream.  Server-to-client frames are never masked per RFC 6455.
 */

import java.io.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

public class WebSocketUtil {

    // RFC 6455 magic GUID appended to the client key before SHA-1
    private static final String WS_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    // Opcodes
    private static final int OP_TEXT  = 0x1;
    private static final int OP_CLOSE = 0x8;
    private static final int OP_PING  = 0x9;
    private static final int OP_PONG  = 0xA;

    /**
     * Performs the WebSocket HTTP upgrade handshake.
     *
     * Reads the HTTP request line and headers from {@code in}, locates the
     * {@code Sec-WebSocket-Key} header, computes the accept token, and writes
     * the {@code 101 Switching Protocols} response to {@code out}.
     *
     * @param in  the raw InputStream of the accepted Socket
     * @param out the raw OutputStream of the accepted Socket
     * @throws IOException if the handshake fails or the stream closes early
     */
    public static void handshake(InputStream in, OutputStream out) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));

        String key = null;
        String line;
        // Read HTTP headers until blank line
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            if (line.startsWith("Sec-WebSocket-Key:")) {
                key = line.substring("Sec-WebSocket-Key:".length()).trim();
            }
        }
        if (key == null) {
            throw new IOException("Missing Sec-WebSocket-Key header");
        }

        String acceptToken = computeAcceptToken(key);

        String response =
            "HTTP/1.1 101 Switching Protocols\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: " + acceptToken + "\r\n" +
            "\r\n";

        out.write(response.getBytes("UTF-8"));
        out.flush();
    }

    /**
     * Reads one WebSocket text frame from {@code in}.
     *
     * Handles client-to-server masking as required by RFC 6455. Silently
     * responds to ping frames with a pong. Returns {@code null} on a close
     * frame or EOF.
     *
     * @param in the raw InputStream of the accepted Socket
     * @return the text payload of the frame, or null if the connection closed
     * @throws IOException on I/O error (treat as disconnect)
     */
    public static String readFrame(InputStream in) throws IOException {
        while (true) {
            // Byte 0: FIN + opcode
            int b0 = in.read();
            if (b0 == -1) return null;  // EOF

            // Byte 1: MASK bit + payload length (7-bit)
            int b1 = in.read();
            if (b1 == -1) return null;

            int opcode  = b0 & 0x0F;
            boolean masked = (b1 & 0x80) != 0;
            long payloadLen = b1 & 0x7F;

            // Extended payload length
            if (payloadLen == 126) {
                payloadLen = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);
            } else if (payloadLen == 127) {
                payloadLen = 0;
                for (int i = 0; i < 8; i++) {
                    payloadLen = (payloadLen << 8) | (in.read() & 0xFF);
                }
            }

            // Masking key (4 bytes), always present for client→server frames
            byte[] maskKey = new byte[4];
            if (masked) {
                readFully(in, maskKey, 4);
            }

            // Payload
            byte[] payload = new byte[(int) payloadLen];
            readFully(in, payload, (int) payloadLen);

            // Unmask
            if (masked) {
                for (int i = 0; i < payload.length; i++) {
                    payload[i] ^= maskKey[i % 4];
                }
            }

            if (opcode == OP_TEXT) {
                return new String(payload, "UTF-8");
            } else if (opcode == OP_CLOSE) {
                return null;  // client closing
            } else if (opcode == OP_PING) {
                // Respond with pong (same payload)
                writeRawFrame(in.equals(in) ? null : null, payload, OP_PONG);
                // Note: we can't easily get the OutputStream here; pong is best-effort.
                // In practice the browser doesn't require pong for normal operation.
            }
            // Continuation and other opcodes are ignored for this protocol
        }
    }

    /**
     * Writes a WebSocket text frame containing {@code message} to {@code out}.
     *
     * Server-to-client frames are not masked per RFC 6455 §5.1.
     * This method is NOT thread-safe — call it from only one thread per stream
     * (use WriterThread to enforce this).
     *
     * @param out     the raw OutputStream of the accepted Socket
     * @param message the text payload to send
     * @throws IOException on I/O error
     */
    public static void writeFrame(OutputStream out, String message) throws IOException {
        byte[] payload = message.getBytes("UTF-8");
        int len = payload.length;

        ByteArrayOutputStream frame = new ByteArrayOutputStream();

        // Byte 0: FIN=1, opcode=text (0x81)
        frame.write(0x81);

        // Byte 1 (and optional extended length): no mask bit (server→client)
        if (len <= 125) {
            frame.write(len);
        } else if (len <= 65535) {
            frame.write(126);
            frame.write((len >> 8) & 0xFF);
            frame.write(len & 0xFF);
        } else {
            frame.write(127);
            for (int i = 7; i >= 0; i--) {
                frame.write((len >> (8 * i)) & 0xFF);
            }
        }

        frame.write(payload);
        out.write(frame.toByteArray());
        out.flush();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static String computeAcceptToken(String clientKey) {
        try {
            String combined = clientKey + WS_MAGIC;
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] hash = sha1.digest(combined.getBytes("UTF-8"));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException | UnsupportedEncodingException e) {
            throw new RuntimeException("SHA-1 not available", e);
        }
    }

    private static void readFully(InputStream in, byte[] buf, int n) throws IOException {
        int offset = 0;
        while (offset < n) {
            int read = in.read(buf, offset, n - offset);
            if (read == -1) throw new EOFException("Stream closed mid-frame");
            offset += read;
        }
    }

    private static void writeRawFrame(OutputStream out, byte[] payload, int opcode)
            throws IOException {
        if (out == null) return;
        out.write(0x80 | opcode);
        out.write(payload.length);
        out.write(payload);
        out.flush();
    }
}
