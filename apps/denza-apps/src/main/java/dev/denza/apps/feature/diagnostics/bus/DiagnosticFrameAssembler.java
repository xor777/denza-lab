package dev.denza.apps.feature.diagnostics.bus;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Turns the raw buffers of the diagnostic channel's RX event into one answer per request.
 *
 * <p>Plain Java with no Android import: the shell-UID helper has it packed beside it, and the JVM
 * tests feed it the frames the research runs recorded (docs/vehicle-diagnostics-architecture.md,
 * 3.1). One instance watches the whole life of a helper, not one request, because what may arrive
 * between two requests is part of the judgement: before the first request every frame is someone
 * else's, and after an exchange closes only its own echoes are harmless.
 *
 * <p>A frame is {@code 0A}, the reply id (request + 8, big-endian), the fragment length, the frame
 * count and the frame number from 1. The first frame carries the gateway's result and its data
 * from byte 7, the others their data from byte 6. The buffer is longer than the frame - 240 bytes
 * with stale bytes in the tail - so only the declared length is read, and the same frame delivered
 * twice is the same frame.
 */
public final class DiagnosticFrameAssembler {
    public static final int OWNER = 0x0A;
    public static final int MAX_FRAMES = 16;
    public static final int MAX_BYTES = 4095;
    /** Larger than any buffer the car has delivered (240); anything bigger is not this channel. */
    public static final int MAX_BUFFER = 1024;
    /**
     * Buffers one exchange may take, duplicates and pending answers included. An ECU that keeps
     * saying "wait" is still bounded by the request's timeout; this bounds a flood.
     */
    public static final int MAX_BUFFERS_PER_EXCHANGE = 64;

    public static final int RESULT_ANSWER = 1;
    public static final int RESULT_NEGATIVE = 2;
    public static final int RESULT_NO_RESPONSE = 3;
    public static final int RESULT_TIMEOUT = 4;
    public static final int RESULT_BUSY = 5;

    /** What one buffer meant for the exchange. */
    public enum Kind {
        /** Nothing to report: part of an answer, a duplicate, a late echo or "response pending". */
        WAITING,
        /** A complete UDS answer, positive or negative. */
        ANSWER,
        /** The gateway said 3 or 4. */
        NO_REPLY,
        /** The gateway said 5. */
        BUSY,
        /** Not a frame of this channel's form; the helper stops. */
        SHAPE,
        /** A well-formed frame that belongs to no request of ours; the helper stops. */
        FOREIGN,
    }

    public static final class Outcome {
        public final Kind kind;
        /** The UDS answer for {@link Kind#ANSWER}, empty otherwise. */
        public final byte[] uds;
        /** Frames of the answer for {@link Kind#ANSWER}. */
        public final int frames;
        /** The gateway's result byte for {@link Kind#NO_REPLY} and {@link Kind#BUSY}. */
        public final int gatewayResult;
        /** Why, in a few words, for {@link Kind#SHAPE} and {@link Kind#FOREIGN}. */
        public final String detail;

        private Outcome(Kind kind, byte[] uds, int frames, int gatewayResult, String detail) {
            this.kind = kind;
            this.uds = uds;
            this.frames = frames;
            this.gatewayResult = gatewayResult;
            this.detail = detail;
        }

        static final Outcome WAIT = new Outcome(Kind.WAITING, new byte[0], 0, 0, "");

        static Outcome answer(byte[] uds, int frames) {
            return new Outcome(Kind.ANSWER, uds, frames, RESULT_ANSWER, "");
        }

        static Outcome gateway(Kind kind, int result) {
            return new Outcome(kind, new byte[0], 0, result, "");
        }

        static Outcome fault(Kind kind, String detail) {
            return new Outcome(kind, new byte[0], 0, 0, detail);
        }

        public boolean closesExchange() {
            return kind == Kind.ANSWER || kind == Kind.NO_REPLY || kind == Kind.BUSY;
        }

        @Override
        public String toString() {
            return kind + (detail.isEmpty() ? "" : " " + detail);
        }
    }

    private final List<byte[]> seen = new ArrayList<>();
    private final ByteArrayOutputStream data = new ByteArrayOutputStream();
    private boolean open;
    private int reply = -1;
    private int sid;
    private int previousReply = -1;
    private int total;
    private int nextIndex = 1;
    private int buffers;

    /** Opens the exchange for one request; the previous one, if any, must be closed. */
    public void begin(int address, byte[] uds) {
        if (open) throw new IllegalStateException("an exchange is already open");
        if (uds == null || uds.length == 0) throw new IllegalArgumentException("empty request");
        previousReply = reply;
        reply = address + 8;
        sid = uds[0] & 0xFF;
        open = true;
        seen.clear();
        restartAnswer();
        buffers = 0;
    }

    /** Closes the exchange without an answer: the helper's own wait ran out. */
    public void end() {
        open = false;
    }

    public boolean isOpen() {
        return open;
    }

    /** Judges one raw RX buffer; an outcome that {@link Outcome#closesExchange() closes} ends the exchange. */
    public Outcome accept(byte[] buffer) {
        if (buffer == null || buffer.length < 6 || buffer.length > MAX_BUFFER) {
            return Outcome.fault(Kind.SHAPE, "buffer of " + (buffer == null ? "null" : buffer.length + " bytes"));
        }
        if (open && ++buffers > MAX_BUFFERS_PER_EXCHANGE) {
            return Outcome.fault(Kind.SHAPE, "more than " + MAX_BUFFERS_PER_EXCHANGE + " buffers for one request");
        }
        int owner = buffer[0] & 0xFF;
        int id = ((buffer[1] & 0xFF) << 8) | (buffer[2] & 0xFF);
        int length = buffer[3] & 0xFF;
        int count = buffer[4] & 0xFF;
        int index = buffer[5] & 0xFF;
        if (owner != OWNER) return Outcome.fault(Kind.FOREIGN, String.format("owner %02X", owner));
        int offset = index == 1 ? 7 : 6;
        if (count < 1 || count > MAX_FRAMES || index < 1 || index > count || buffer.length < offset + length) {
            return Outcome.fault(Kind.SHAPE, String.format("frame %d/%d of %d bytes in %d", index, count, length, buffer.length));
        }
        byte[] frame = Arrays.copyOf(buffer, offset + length);
        if (duplicate(frame)) return Outcome.WAIT;
        if (!open) {
            // Between requests only an echo of the exchange that just closed is harmless.
            return id == reply ? Outcome.WAIT : foreign(id);
        }
        if (id != reply) return id == previousReply ? Outcome.WAIT : foreign(id);
        seen.add(frame);

        if (index == 1) {
            if (nextIndex != 1) return Outcome.fault(Kind.SHAPE, "a first frame inside an answer");
            int result = buffer[6] & 0xFF;
            switch (result) {
                case RESULT_ANSWER:
                case RESULT_NEGATIVE:
                    break;
                case RESULT_NO_RESPONSE:
                case RESULT_TIMEOUT:
                    open = false;
                    return Outcome.gateway(Kind.NO_REPLY, result);
                case RESULT_BUSY:
                    open = false;
                    return Outcome.gateway(Kind.BUSY, result);
                default:
                    return Outcome.fault(Kind.SHAPE, "gateway result " + result);
            }
            if (length == 0) return Outcome.fault(Kind.SHAPE, "an answer without data");
            if (!forThisService(buffer[offset] & 0xFF, length > 1 ? buffer[offset + 1] & 0xFF : -1)) {
                // The same ECU answering an earlier service late - consecutive requests to one ECU
                // are the rule - is an echo; anything else from it is not an answer to anything.
                return previousReply == reply ? Outcome.WAIT
                        : Outcome.fault(Kind.SHAPE, String.format("answer %02X to service %02X", buffer[offset] & 0xFF, sid));
            }
            total = count;
        } else if (index != nextIndex || count != total) {
            return Outcome.fault(Kind.SHAPE, String.format("frame %d/%d after %d/%d", index, count, nextIndex - 1, total));
        }
        if (data.size() + length > MAX_BYTES) {
            return Outcome.fault(Kind.SHAPE, "an answer longer than " + MAX_BYTES + " bytes");
        }
        data.write(buffer, offset, length);
        nextIndex++;
        if (index < total) return Outcome.WAIT;

        byte[] uds = data.toByteArray();
        int frames = total;
        restartAnswer();
        if (uds.length >= 3 && (uds[0] & 0xFF) == 0x7F && (uds[2] & 0xFF) == 0x78) {
            // "Response pending": the ECU answers later in the same exchange; only the timeout ends it.
            return Outcome.WAIT;
        }
        open = false;
        return Outcome.answer(uds, frames);
    }

    private boolean forThisService(int first, int second) {
        return first == sid + 0x40 || (first == 0x7F && second == sid);
    }

    private Outcome foreign(int id) {
        return Outcome.fault(Kind.FOREIGN, String.format("frame from %03X", id));
    }

    private boolean duplicate(byte[] frame) {
        for (byte[] known : seen) {
            if (Arrays.equals(known, frame)) return true;
        }
        return false;
    }

    private void restartAnswer() {
        data.reset();
        total = 0;
        nextIndex = 1;
    }
}
