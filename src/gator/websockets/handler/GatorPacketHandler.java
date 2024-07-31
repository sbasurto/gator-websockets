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
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
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
        private GatorWSInputFrame lastFrame;
        
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
        private final Stack<byte[]> dataStack = new Stack<>();
        
        /**
         * The mask that comes in the frame.
         */
        private final ArrayList<Integer> mask = new ArrayList<>();
                   
        /**
         * Flag that tell if this frame is the beginning of a set of fragmented frames.
         */
        private boolean messageHasBegin = false;
        
        private final GappLogging logger = new GappLogging();
        private final GappLog gappLog = new GappLog();
        
        
        /**
         * A stack for the data being processed.
         */
        private final Stack<Integer> dataLengths = new Stack<>();
        private final GatorWSProperties gatorProps;
       
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
                            if(framesStack.peek().isBeginOfMessage()) messageHasBegin = true;
                            if(framesStack.peek().fillFrame(b)) {                                    
                                    if(messageHasBegin && framesStack.peek().isFragmented() || !messageHasBegin) {
                                            if(dataStack.size() > 1) lastData = dataStack.pop();
                                            if(dataStack.empty()) {
                                                    dataStack.push(framesStack.peek().getData());
                                                    dataLengths.push(framesStack.peek().getDataLength());
                                            } else {
                                                    byte []temporal = dataStack.pop();
                                                    int longitudTmp = dataLengths.pop();
                                                    dataStack.push(framesStack.peek().concatByteArray(temporal, framesStack.peek().getData()));
                                                    longitudTmp += framesStack.peek().getDataLength();
                                                    if(longitudTmp > 2147483647) {
                                                            throw new WebSocketMaxLengthException("The length for this message is (" + longitudTmp + ") this exceeds the allowed length for this server that is 2GiB in size.");
                                                    }
                                                    dataLengths.push(longitudTmp);
                                            }
                                            if(framesStack.peek().isEndOfMessage()) messageHasBegin = false;
                                    } else {
                                            dataStack.push(framesStack.peek().getData());                                            
                                    }
                                    return framesStack.peek().isLastFrame();
                            } else {
                                    return false;
                            }                                
                    } catch (WebSocketFormatException ex) {
                            gappLog.addMessage("The message is malformed, so server will close this connection.", 2);
                            gappLog.addMessage(logger.getStackTraceString(ex), 2);
                            logger.logIt(gappLog, gatorProps.withDebug());
                            closeWebSocket(ex.getStatusCode());                                
                            return true;
                    } catch (WebSocketMaxLengthException ex) {
                            gappLog.addMessage("The message has a bigger length than the accepted one.", 2);
                            gappLog.addMessage(logger.getStackTraceString(ex), 2);
                            logger.logIt(gappLog, gatorProps.withDebug());
                            closeWebSocket(ex.getStatusCode());                                
                            return true;
                    } catch (IOException ex) {
                            gappLog.addMessage("Error reading the lenght of the ws frame.", 2);
                            gappLog.addMessage(logger.getStackTraceString(ex), 2);
                            logger.logIt(gappLog, gatorProps.withDebug());
                            closeWebSocket(1002);    
                            return true;
                    }
                } else {
                        lastFrame = framesStack.pop();
                        framesStack.push(new GatorWSInputFrame(gatorProps));
                        return fillPacket(b);
                }
        }
        
        /**
         * Allows to retrieve the final data byte array.
         * @return An array of bytes representing the data for this frame.
         */
        public byte[] getFrameData() {
                byte []data = dataStack.peek();
                gappLog.startNewLog("GatorPacketHandler", "getFrameData");
                if(framesStack.peek().isLastFrame()) {
                        dataStack.pop();
                } else {
                        gappLog.addMessage("I am still receiving frames, please wait, til the end.", 2);
                        logger.logIt(gappLog, gatorProps.withDebug());
                }
                frameCount++;
                String maskNudeBytes = "";
                for(byte b : framesStack.peek().getMask()) {
                        maskNudeBytes += (b & 0xff) + " ";                        
                        mask.add(b & 0xff);
                }
                gappLog.addMessage("mascara:" + maskNudeBytes, 2);
                logger.logIt(gappLog, gatorProps.withDebug());
                return data;
        }
        
        /**
         * Allows to get status code number.
         * 
         * @return Integer with the status code number as RFC6455
         */
        public int getStatusCode() {
                return framesStack.peek().getStatusCode();
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
                return outFrame.addData(message.getBytes());
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
                return outFrame.addData(BigInteger.valueOf(closureCode).toByteArray());
        }
        /**
         * Allow to create a frame for a message to send.
         *          
         * @param message A message for the ping, this could be an empty string.
         * @return Byte array with frame as described in RFC6455.
         */
        public byte[] ping(String message) {
                outFrame = new GatorWSOutputFrame(GatorWSOutputFrame.PING);
                return outFrame.addData(message.getBytes());
        }
        /**
         * Allow to create a frame for a message to send.
         *     
         * @param message A message for the pong, this must be the same received in the ping.
         * @return Byte array with frame as described in RFC6455.
         */
        public byte[] pong(String message) {
                outFrame = new GatorWSOutputFrame(GatorWSOutputFrame.PONG);
                return outFrame.addData(message.getBytes());
        }                
        
        /**
         * Allows to retrieve the current customer.
         * @return String representing this customer.
         */
        public String getKind() {
                return framesStack.peek().getKind();
        }                 
}
