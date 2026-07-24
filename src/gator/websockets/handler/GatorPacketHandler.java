/* 
 * Copyright (C) 2021 Sergio Basurto Juárez
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package gator.websockets.handler;

import gator.lib.logs.GappLog;
import gator.websockets.frames.GatorWSFrame;
import gator.lib.logs.GappLogging;
import gator.websockets.exception.WebSocketFormatException;
import gator.websockets.exception.WebSocketMaxLengthException;
import gator.websockets.frames.GatorWSInputFrame;
import gator.websockets.frames.GatorWSOutputFrame;
import gator.websockets.helpers.GatorWSProperties;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Stack;

/**
 *
 * @author <a href="mailto:sbasurto@soft-gator.com">Sergio Basurto Juárez</a>
 * @version 1.0
 */
public class GatorPacketHandler extends GatorWSFrame {
        /**
         * A hand shake request object.
         */
        //private final GatorHandShakeHandler hsRequest = new GatorHandShakeHandler();

        /**
         * Flag telling if debug is on.
         */
        private boolean withDebug = false;
        
        /**
         * An input frame for store the last frame processed.
         */
        /**
         * An stack for frames being processed.
         */
        private final Stack<GatorWSInputFrame> framesStack = new Stack<>();
        
        /**
         * An output frame for send message to clients.
         */
        private GatorWSOutputFrame outFrame;
        
        /**
         * A counter for frames.
         */
        private int frameCount = 0;
        
        /**
         * A byte array to store the last data received in a frame.
         */
        private byte [] lastData;
        
        /**
         * A byte array that stores the hand shake bytes when ready.
         */
        private byte [] hsReady;
        
        /**
         * A stack for the data being processed.
         */
        private ByteArrayOutputStream fragmentedData;
        private boolean fragmentedMessageOpen = false;
        private boolean fragmentedMessageIsText = false;
        private boolean currentFrameIsText = false;
        
        private final GappLogging logger = new GappLogging();
        private final GappLog gappLog = new GappLog();
        
        
        private final GatorWSProperties gatorProps;
        private int protocolErrorCode = 0;
       
        /**
         * Default constructor for this class.
         * @param _gatorProps Properties to use.        
         */
        public GatorPacketHandler(GatorWSProperties _gatorProps) {    
                gatorProps = _gatorProps;
                framesStack.push(new GatorWSInputFrame(gatorProps));
                gappLog.setFileToLog("websocket");                        
	    	gappLog.setName("websockets");
        }                        
        
        /**
         * Fill packets (web socket frames).
         * @param b Byte to add to a frame.
         * @return Boolean flag telling if the frame is ready to be retrieved.
         */
        public boolean fillPacket(byte b) { 
                gappLog.startNewLog("GatorPacketHandler", "fillPacket");
                if(!framesStack.peek().isFull()) {
                    try {
                            boolean firstByteWasRead = framesStack.peek().isFirstByteRead();
                            boolean frameReady = framesStack.peek().fillFrame(b);
                            if(!firstByteWasRead && framesStack.peek().isFirstByteRead()) {
                                    validateFrameStart(framesStack.peek());
                            }
                            if(frameReady) {
                                    assembleFrameData(framesStack.peek());
                                    if(framesStack.peek().isLastFrame() && currentFrameIsText) {
                                            validateUtf8(lastData);
                                            if("part".equals(framesStack.peek().getKind())) fragmentedMessageIsText = false;
                                    }
                                    return framesStack.peek().isLastFrame();
                            } else {
                                    return false;
                            }                                
                    } catch (WebSocketFormatException ex) {
                            gappLog.addMessage("The message is malformed, so server will close this connection.", 2);
                            gappLog.addMessage(logger.getStackTraceString(ex), 2);
                            logger.logIt(gappLog, withDebug());
                            protocolErrorCode = ex.getStatusCode();
                            return false;
                    } catch (WebSocketMaxLengthException ex) {
                            gappLog.addMessage("The message has a bigger length than the accepted one.", 2);
                            gappLog.addMessage(logger.getStackTraceString(ex), 2);
                            logger.logIt(gappLog, withDebug());
                            protocolErrorCode = ex.getStatusCode();
                            return false;
                    } catch (IOException ex) {
                            gappLog.addMessage("Error reading the lenght of the ws frame.", 2);
                            gappLog.addMessage(logger.getStackTraceString(ex), 2);
                            logger.logIt(gappLog, withDebug());
                            protocolErrorCode = 1002;
                            return false;
                    }
                } else {
                        framesStack.pop();
                        framesStack.push(new GatorWSInputFrame(gatorProps));
                        return fillPacket(b);
                }
        }
        
        /**
         * Allows to retrieve the final data byte array.
         * @return An array of bytes representing the data for this frame.
         */
        public byte[] getFrameData() {
                byte []data = lastData;
                gappLog.startNewLog("GatorPacketHandler", "getFrameData");
                lastData = null;
                frameCount++;
                String maskNudeBytes = "";
                for(byte b : framesStack.peek().getMask()) {
                        maskNudeBytes += (b & 0xff) + " ";                        
                }
                gappLog.addMessage("mascara:" + maskNudeBytes, 2);
                logger.logIt(gappLog, withDebug());
                return data;
        }
        private void assembleFrameData(GatorWSInputFrame frame) throws WebSocketMaxLengthException {
                byte[] frameData = frame.getData();
                String kind = frame.getKind();
                if("text".equals(kind) || "binary".equals(kind)) {
                        if(frame.isLastFrame()) {
                                lastData = frameData;
                        } else {
                                fragmentedData = new ByteArrayOutputStream(Math.min(frameData.length, 16 * 1024));
                                appendFragment(frameData);
                        }
                } else if("part".equals(kind)) {
                        appendFragment(frameData);
                        if(frame.isLastFrame()) {
                                lastData = fragmentedData.toByteArray();
                                fragmentedData = null;
                        }
                } else {
                        lastData = frameData;
                }
        }
        private void appendFragment(byte[] data) throws WebSocketMaxLengthException {
                if(fragmentedData == null || fragmentedData.size() > 16 * 1024 * 1024 - data.length) {
                        throw new WebSocketMaxLengthException("The message exceeds the 16 MiB limit.");
                }
                fragmentedData.writeBytes(data);
        }
        private void validateFrameStart(GatorWSInputFrame frame) throws WebSocketFormatException {
                String kind = frame.getKind();
                currentFrameIsText = false;
                if("part".equals(kind)) {
                        if(!fragmentedMessageOpen) {
                                throw new WebSocketFormatException("Continuation frame received without an open fragmented message");
                        }
                        currentFrameIsText = fragmentedMessageIsText;
                        if(frame.isLastFrame()) fragmentedMessageOpen = false;
                } else if("binary".equals(kind)) {
                        throw new WebSocketFormatException("Binary messages are not supported", 1003);
                } else if("text".equals(kind)) {
                        if(fragmentedMessageOpen) {
                                throw new WebSocketFormatException("A new data message cannot start before the fragmented message ends");
                        }
                        currentFrameIsText = true;
                        if(!frame.isLastFrame()) {
                                fragmentedMessageOpen = true;
                                fragmentedMessageIsText = currentFrameIsText;
                        }
                }
        }
        private void validateUtf8(byte[] data) throws WebSocketFormatException {
                try {
                        StandardCharsets.UTF_8.newDecoder()
                                .onMalformedInput(CodingErrorAction.REPORT)
                                .onUnmappableCharacter(CodingErrorAction.REPORT)
                                .decode(ByteBuffer.wrap(data));
                } catch(CharacterCodingException e) {
                        throw new WebSocketFormatException("Text messages must contain valid UTF-8", 1007);
                }
        }
        private boolean withDebug() {
                return gatorProps != null && gatorProps.withDebug();
        }
        
        /**
         * Allows to get status code number.
         * 
         * @return Integer with the status code number as RFC6455
         */
        public int getStatusCode() {
                return framesStack.peek().getStatusCode();
        }
        public int getProtocolErrorCode() {
                return protocolErrorCode;
        }
        
        /**
         * Tells if the current frame is the closure one.
         * @return Boolean telling if is the closure frame.
         */
        public boolean isThisTheCloseFrame() {
                return framesStack.peek().isThisTheCloseFrame();
        }
        
        /**
         * Tells if is closure ready.
         * @return Boolean telling if is the closure frame.
         */
        public boolean isClosureReady() {
                return framesStack.peek().isClosureReady();
        }
        
        /**
         * Allow to create a frame for a message to send.
         * 
         * @param message The message to be send.
         * @param type The type of message accordingly to RFC6455
         * 
         * @return Byte array with frame as described in RFC6455
         */
        public byte[] createMessage(String message, int type) {
                outFrame = new GatorWSOutputFrame(type);
                return outFrame.addData(message.getBytes(StandardCharsets.UTF_8));
        }
        
        /**
         * Allows to retrieve frame count.
         * 
         * @return The frame count.
         */
        public int getFrameCount() {
                return frameCount;
        }
        
        
        /**
         * Allow to create a frame for a message to send.
         * 
         * @param closureCode The close code as described in RFC6455.
         * 
         * @return Byte array with frame as described in RFC6455.
         */
        public byte[] closeWebSocket(int closureCode) {
                outFrame = new GatorWSOutputFrame(GatorWSOutputFrame.CLOSE_FRAME);
                return outFrame.addData(ByteBuffer.allocate(2).putShort((short) closureCode).array());
        }
        /**
         * Allow to create a frame for a message to send.
         *          
         * @param message A message for the ping, this could be an empty string.
         * @return Byte array with frame as described in RFC6455.
         */
        public byte[] ping(String message) {
                outFrame = new GatorWSOutputFrame(GatorWSOutputFrame.PING);
                return outFrame.addData(message.getBytes(StandardCharsets.UTF_8));
        }
        /**
         * Allow to create a frame for a message to send.
         *     
         * @param message A message for the pong, this must be the same received in the ping.
         * @return Byte array with frame as described in RFC6455.
         */
        public byte[] pong(String message) {
                return pong(message.getBytes(StandardCharsets.UTF_8));
        }
        public byte[] pong(byte[] message) {
                outFrame = new GatorWSOutputFrame(GatorWSOutputFrame.PONG);
                return outFrame.addData(message);
        }                
        
        /**
         * Allows to retrieve the current customer.
         * @return String representing this customer.
         */
        public String getKind() {
                return framesStack.peek().getKind();
        }                 
}
