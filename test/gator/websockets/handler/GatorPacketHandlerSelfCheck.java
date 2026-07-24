package gator.websockets.handler;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class GatorPacketHandlerSelfCheck {
        private static final byte[] MASK = {1, 2, 3, 4};

        private GatorPacketHandlerSelfCheck() {}

        public static void run() {
                acceptsCloseReason();
                rejectsInvalidCloseReason();
                rejectsUnexpectedContinuation();
                rejectsBinaryMessages();
                rejectsNewMessageDuringFragmentation();
                validatesUtf8AfterReassembly();
                preservesFragmentedMessageAcrossPing();
                preservesBinaryPingPayload();
                rejectsInvalidTextUtf8();
                rejectsNonMinimalLength();
                rejectsLengthWithMostSignificantBit();
        }

        private static void acceptsCloseReason() {
                GatorPacketHandler handler = new GatorPacketHandler(null);
                feed(handler, frame(true, 8, concat(new byte[] {3, (byte) 232}, "bye".getBytes(StandardCharsets.UTF_8))));
                assert handler.getProtocolErrorCode() == 0;
                assert handler.isThisTheCloseFrame();
                assert handler.isClosureReady();
                assert handler.getStatusCode() == 1000;
        }

        private static void rejectsInvalidCloseReason() {
                GatorPacketHandler handler = new GatorPacketHandler(null);
                feed(handler, frame(true, 8, new byte[] {3, (byte) 232, (byte) 0xc3, 0x28}));
                assert handler.getProtocolErrorCode() == 1007;
        }

        private static void rejectsUnexpectedContinuation() {
                GatorPacketHandler handler = new GatorPacketHandler(null);
                feed(handler, frame(true, 0, new byte[] {1}));
                assert handler.getProtocolErrorCode() == 1002;
        }

        private static void rejectsBinaryMessages() {
                GatorPacketHandler handler = new GatorPacketHandler(null);
                feed(handler, frame(true, 2, new byte[] {1}));
                assert handler.getProtocolErrorCode() == 1003;
        }

        private static void rejectsNewMessageDuringFragmentation() {
                GatorPacketHandler handler = new GatorPacketHandler(null);
                feed(handler, frame(false, 1, new byte[] {'a'}));
                feed(handler, frame(true, 1, new byte[] {'b'}));
                assert handler.getProtocolErrorCode() == 1002;
        }

        private static void validatesUtf8AfterReassembly() {
                GatorPacketHandler handler = new GatorPacketHandler(null);
                assert !feed(handler, frame(false, 1, new byte[] {(byte) 0xc3}));
                assert feed(handler, frame(true, 0, new byte[] {(byte) 0xa9}));
                assert Arrays.equals(handler.getFrameData(), "é".getBytes(StandardCharsets.UTF_8));
        }

        private static void rejectsInvalidTextUtf8() {
                GatorPacketHandler handler = new GatorPacketHandler(null);
                feed(handler, frame(true, 1, new byte[] {(byte) 0xc3, 0x28}));
                assert handler.getProtocolErrorCode() == 1007;
        }

        private static void preservesFragmentedMessageAcrossPing() {
                GatorPacketHandler handler = new GatorPacketHandler(null);
                assert !feed(handler, frame(false, 1, new byte[] {'a'}));
                assert feed(handler, frame(true, 9, new byte[] {'x'}));
                assert Arrays.equals(handler.getFrameData(), new byte[] {'x'});
                assert feed(handler, frame(true, 0, new byte[] {'b'}));
                assert Arrays.equals(handler.getFrameData(), new byte[] {'a', 'b'});
        }

        private static void preservesBinaryPingPayload() {
                GatorPacketHandler handler = new GatorPacketHandler(null);
                byte[] payload = {(byte) 0xff, 0, (byte) 0x80};
                assert feed(handler, frame(true, 9, payload));
                byte[] pong = handler.pong(handler.getFrameData());
                assert (pong[0] & 0x0f) == 10;
                assert (pong[1] & 0x7f) == payload.length;
                assert Arrays.equals(Arrays.copyOfRange(pong, 2, pong.length), payload);
        }

        private static void rejectsNonMinimalLength() {
                GatorPacketHandler handler = new GatorPacketHandler(null);
                byte[] encoded = {(byte) 0x81, (byte) 0xfe, 0, 1};
                feed(handler, encoded);
                assert handler.getProtocolErrorCode() == 1002;
        }

        private static void rejectsLengthWithMostSignificantBit() {
                GatorPacketHandler handler = new GatorPacketHandler(null);
                byte[] encoded = {(byte) 0x81, (byte) 0xff, (byte) 0x80, 0, 0, 0, 0, 0, 0, 0};
                feed(handler, encoded);
                assert handler.getProtocolErrorCode() == 1002;
        }

        private static boolean feed(GatorPacketHandler handler, byte[] bytes) {
                boolean ready = false;
                for(byte value : bytes) {
                        ready = handler.fillPacket(value);
                        if(handler.getProtocolErrorCode() != 0) break;
                }
                return ready;
        }

        private static byte[] frame(boolean fin, int opcode, byte[] payload) {
                if(payload.length > 125) throw new IllegalArgumentException("Self-check helper only supports short frames");
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                output.write((fin ? 0x80 : 0) | opcode);
                output.write(0x80 | payload.length);
                output.writeBytes(MASK);
                for(int index = 0; index < payload.length; index++) {
                        output.write(payload[index] ^ MASK[index & 3]);
                }
                return output.toByteArray();
        }

        private static byte[] concat(byte[] first, byte[] second) {
                byte[] result = Arrays.copyOf(first, first.length + second.length);
                System.arraycopy(second, 0, result, first.length, second.length);
                return result;
        }
}
