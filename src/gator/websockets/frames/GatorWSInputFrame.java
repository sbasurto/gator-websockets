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
package gator.websockets.frames;


import gator.lib.logs.GappLog;
import gator.lib.logs.GappLogging;
import gator.websockets.exception.WebSocketFormatException;
import gator.websockets.exception.WebSocketMaxLengthException;
import gator.websockets.helpers.GatorWSProperties;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;

/**
 *
 * @author <a href="mailto:sbasurto@soft-gator.com">Sergio Basurto Juárez</a>
 * @version 1.0
 */
public class GatorWSInputFrame extends GatorWSFrame {
        /**
         * If the frame has been read entirely.
         */
        private boolean isFull = false;
        
        /**
         * If this frame is the last one.
         */
        private boolean isFIN = false;
        
        /**
         * Is first byte read, if not we just start the frame.
         */
        private boolean firstByteRead = false;
        
        /**
         * Is first byte read, if not we just start the frame.
         */
        private boolean secondByteRead = false;
        
        /**
         * Payload length.
         */
        private int payloadLength = 0;
        
        /**
         * The mask to use for unmask payload.
         */
        private final byte [] mask = new byte[4];
        
        /**
         * Index for the mask.
         */
        private int maskIndex = 0;        
                
        /**
         * The kind of data that is been transmitted.
         */
        private String dataKind = "";
        
        /**
         * When the length of payload is defined this flag must be set to true.
         */
        private boolean lengthSettled = false;
        
        /**
         * When the length of payload is defined this flag must be set to true.
         */
        private boolean maskSettled = false;
                
        /**
         * Length index, is the number of bytes that will form the length.
         */
        private int lengthIndex = 0;
        
        /**
         * The length array.
         */
        private byte [] frameLength;
        
        /**
         * The payload data.
         */
        private byte [] dataEncoded;
        
        /**
         * The payload data index.
         */
        private int dataIndex = 0;
        
        /**
         * Is data done.
         */
        private boolean isDataSettled = false;
        
        /**
         * Flag that tells if this frame is a close one.
         */
        private boolean isCloseFrame = false;
        
        /**
         * Status code byte array.
         */
        private final byte []statusCode = new byte[2];
        
        /**
         * The status code index.
         */
        private int statusCodeIndex = 0;
        
        /**
         * Flag to tell if connection closure is ready.
         */
        private boolean closureIsReady = false;
        
        /**
         * The actual status code.
         */
        private int statusCodeNumber;
        
        /**
         * Flag that tells if this frame is a ping one.
         */
        private boolean isPing = false;
        
        /**
         * Flag that tells if this frame is a ping one.
         */
        private boolean isPong = false;
        
        /**
         * The array to store bytes.
         */
        private byte [] dataReady;
        
        /**
         * The flag that tells if the frame is fragmented.
         */
        private boolean isFragmented = false;
        
        /**
         * Tells if the fragmented message has been start.
         */
        private boolean messageBegins = false;
        
        /**
         * Tells if the fragmented message has terminate.
         */
        private boolean messageEnds = false;

        private final GappLogging logger = new GappLogging(); 
        private final GappLog gappLog = new GappLog();
        private final GatorWSProperties gatorProps;
        
        public GatorWSInputFrame(GatorWSProperties _gatorProps) {
                gatorProps = _gatorProps;
                gappLog.setFileToLog("websocket");                        
	    	gappLog.setName("websockets");
        }
        /**
         * Allows to check the status of the frame.
         * @return Boolean telling us if it is close or not.
         */
        public boolean isFull() {
                return isFull;
        }
        
        /**
         * Allows to close frame, once closed the frame will no longer accept data.
         */
        public void fullFrame() {
                isFull = true;
        }
        
        /**
         * Allow us to check if the first byte has been read.
         * @return Boolean Flag telling if the first byte has been read.
         */
        public boolean isFirstByteRead() {
                return firstByteRead;
        }                
        
        /**
         * Allow us to set the first byte of this frame.
         * 
         * @param firstByte The first byte of this frame.
         * @throws gator.websockets.exception.WebSocketFormatException
         */
        public void setFirstByte(byte firstByte)  throws WebSocketFormatException {
                gappLog.startNewLog("GatorWSInputFrame", "setFirstByte");
                int fin = firstByte & 0x80;
                int kind = firstByte & 0x7f;                     
                isFIN = fin == 128;
                gappLog.addMessage("FIN:" + isFIN, 2);
                gappLog.addMessage("Opcode:" + kind, 2);
                logger.logIt(gappLog, gatorProps.withDebug());
                
                if(isFIN && kind != 0) isFragmented = false;
                if(!isFIN && kind != 0) {
                        messageBegins = true;
                        isFragmented = true;
                }
                if(kind == 0) isFragmented = true;
                if(isFIN && kind == 0) messageEnds = true;
                if(kind != 0 && kind != 1 && kind != 2 && kind != 8 && kind != 9 && kind != 10) {
                        throw new WebSocketFormatException("Frame opcode (" + kind + ") does not exists");
                }
                if(kind == 0) {
                        // This means is a continuation frame of a series.
                        dataKind = "part";
                }
                if(kind == 1) {
                        dataKind = "text";
                }
                if(kind == 2) {
                        dataKind = "binary";
                }
                if(kind == 8) {
                        dataKind = "close";
                        isCloseFrame = true;
                }
                if(kind == 9) {
                        dataKind = "ping";
                        isPing = true;
                }
                if(kind == 10) {
                        dataKind = "pong";
                        isPong = true;
                }
                firstByteRead = true;
        }
        
        /**
         * Allow to ask if this frame is the last frame.
         * @return  A Boolean flag that tells if this frame is the last one.
         */
        public boolean isLastFrame() {
                return isFIN;
        }
        
        /**
         * Allows to retrieve the kind of data of this frame.
         * @return String with frame's data kind accordingly to RFC6455
         */
        public String getKind() {
                return dataKind;
        }
        
        /**
         * Allow us to check if the first byte has been read.
         * @return Boolean Flag telling if the first byte has been read.
         */
        public boolean isSecondByteRead() {
                return secondByteRead;
        }
        
        /**
         * Allow us to set second byte of this frame.
         * 
         * @param secondByte The second byte of the frame.
         * @throws gator.websockets.exception.WebSocketFormatException
         */
        public void setSecondByte(byte secondByte) throws WebSocketFormatException {
                gappLog.startNewLog("GatorWSInputFrame", "setSecondByte");
                int masked = secondByte & 0x80;
                if(masked == 0) {
                        throw new WebSocketFormatException("Frame sent from a client must has a mask, this hasn't (masked bit comes as 0)");
                }
                int valor = secondByte & 0x7f;
                if (valor >= 0 && valor <= 125) {
                    payloadLength = valor;
                    lengthSettled = true;
                    dataEncoded = new byte[payloadLength];
                    if(payloadLength == 0) {
                            isDataSettled = true;
                    }
                    gappLog.addMessage("Frame length: " + payloadLength, 2);
                    logger.logIt(gappLog, gatorProps.withDebug());                     
                }
                if (valor == 126) {
                    // We will read the next 2 bytes 
                    // as unsigend 16 bits integer and this will tell us the length.
                    gappLog.addMessage("Frame length is a 16 unsigned bit integer: " + valor, 2);
                    logger.logIt(gappLog, gatorProps.withDebug());
                    lengthSettled = false;                     
                    frameLength = new byte[2];
                }
                if (valor == 127) {
                     // We will read the next 2 bytes 
                     // as unsigend 64 bits integer and this will tell us the length.
                     // Big Endian or network byte order− In this scheme, high-order byte is recibed first 
                     // and low-order byte is recived last, this means that the firs byte arriving
                     // in the connection will be the most siginificant one, little-endiand is the other way around.
                    gappLog.addMessage("Frame length is a 64 unsigned bit integer: " + valor, 2);
                    logger.logIt(gappLog, gatorProps.withDebug());
                    lengthSettled = false;                     
                    frameLength = new byte[8];
                }
                secondByteRead = true;
        }
        
        /**
         * Allow us to check if the length has been read.
         * @return Boolean Flag telling if the length of the payload has been set.
         */
        public boolean isLenghtSettled() {
                return lengthSettled;
        }
        
        /**
         * Fill the byte array for the length, when the length comes as 16 bit unsigned integer or 64 bit unsigned integer in network byte order.
         * @param b 
         * @throws java.io.IOException 
         * @throws gator.websockets.exception.WebSocketMaxLengthException 
         */
        public void setLength(byte b) throws IOException, WebSocketMaxLengthException {
                gappLog.startNewLog("GatorWSInputFrame", "setLength");
                frameLength[lengthIndex] = b;
                lengthIndex++;
                if(lengthIndex == frameLength.length) {
                        lengthSettled = true;
                        int temporalLength = 0;
                        long temporalLengthLong = 0;
                        if (frameLength.length == 2) {
                                DataInputStream reader = new DataInputStream(new ByteArrayInputStream(frameLength));
                                temporalLength = reader.readShort();
                        }
                        if (frameLength.length == 8) {
                                DataInputStream reader = new DataInputStream(new ByteArrayInputStream(frameLength));
                                temporalLengthLong = reader.readLong();
                                // We will accept only 134217700 bits = 16 Mebibytes for a frame size, 
                                // you can increase this value to 2147483647 bits = 2GiB just remember to 
                                // also allow this size on heap memory.                                
                                byte []maxValue = {(byte) 0x07, (byte) 0xff, (byte) 0xff, (byte) 0xe4};
                                DataInputStream readerMax = new DataInputStream(new ByteArrayInputStream(maxValue));
                                temporalLength = readerMax.readInt();
                                gappLog.clearMessages();
                                gappLog.addMessage("Requested frame length: " + temporalLengthLong, 2);
                                gappLog.addMessage("Allowed frame max length: " + temporalLength, 2);
                                logger.logIt(gappLog, gatorProps.withDebug());
                        }
                        if(temporalLengthLong > 0 && temporalLengthLong > temporalLength) {        
                                throw new WebSocketMaxLengthException("The length of the payload data is (" + temporalLengthLong + ") this exceeds the allowed length for this server that is 16MiB in size.");
                        }
                        payloadLength = temporalLength;
                        gappLog.clearMessages();                                
                        gappLog.addMessage("Frame length: " + payloadLength, 2);
                        logger.logIt(gappLog, gatorProps.withDebug());
                        dataEncoded = new byte[payloadLength];
                }
        }
        
        /**
         * Allow us to check if the first byte has been read.
         * @return Boolean Flag telling if the masking key has been set.
         */
        public boolean isMaskSettled() {
                return maskSettled;
        }
        
        /**
         * Allows to set the mask.
         * 
         * @param b A byte for the mask.
         */
        public void setMask(byte b) {
                mask[maskIndex] = b;
                maskIndex ++;
                if(maskIndex == mask.length) {
                        maskSettled = true;
                        if(payloadLength == 0 && !isThisTheCloseFrame()) fullFrame();
                        if(payloadLength == 0 && isThisTheCloseFrame()) {
                                setClosureReady();
                                statusCodeNumber = 1000;
                        }
                }
        }
        
        /**
         * Allow us to check if the first byte has been read.
         * @return Boolean flag telling if the data for this frame has been exhausted.
         */
        public boolean isDataSettled() {
                return isDataSettled;
        }
        
        /**
         * Allows to set data for the frame.
         * 
         * @param b Byte to be added to data.
         * 
         * @return Boolean flag telling that this byte is the last in the data for this frame.
         */
        public boolean setData(byte b) {
                dataEncoded[dataIndex] = b;
                dataIndex ++;
                if(dataIndex == dataEncoded.length) {
                        isDataSettled = true;
                        fullFrame();
                        return true;
                } else {
                        return false;
                }
        }
        
        /**
         * Get the data length array.
         * @return An integer representing the data length in bytes;
         */
        public int getDataLength() {
                return dataEncoded.length;
        }
        
        /**
         * Allow to retrieve the data.
         * 
         * @return An array of bytes representing data decoded.
         */
        public byte[] getData() {
                byte []decoded = new byte[dataEncoded.length];                                
                for (int i = 0; i < dataEncoded.length; i++) {					
                        decoded[i] = (byte) (dataEncoded[i] ^ mask[i & 0x3]);                                
                }
                return decoded;
        }
        
        /**
         * Allows to ask if this frame is a frame for close connection.
         * 
         * @return Boolean flag telling if this frame is a frame for close connection.
         */
        public boolean isThisTheCloseFrame() {
                return isCloseFrame;
        }
        
        /**
         * Allows to set the close the two bytes of status code.
         * 
         * @param b The byte to add for status code.
         */
        public void setStatusCode(byte b) {
                statusCode[statusCodeIndex] = b;
                statusCodeIndex++;
                if(statusCodeIndex == 1) {
                        secondByteRead = true;
                }
                if(statusCodeIndex == 2) {
                        setClosureReady();
                        byte []decoded = new byte[statusCode.length];
                        for (int i = 0; i < statusCode.length; i++) {					
                                decoded[i] = (byte) (statusCode[i] ^ mask[i & 0x3]);                                
                        }
                        statusCodeNumber = ((decoded[0] & 0xff) << 8) | decoded[1] & 0xff;
                }
        }
        /**
         * Allows to set closure ready when the frame is a close connection one.
         */
        public void setClosureReady() {
                closureIsReady = true;
        }
        /**
         * Allows to ask if closure is ready.
         * 
         * @return Boolean flag telling if this frame is a frame for close connection.
         */
        public boolean isClosureReady() {
                return closureIsReady;
        }
        
        /**
         * Allows to retrieve the status code number
         * @return Integer with the status code number as RFC6455.
         */
        public int getStatusCode() {
                return statusCodeNumber;
        }
        /**
         * Fill a frame accordingly to RFC6455.
         * @param readByte  The last byte read in socket.
         * @return  Flag telling if the frame is ready or not, the fact that the frame is ready does not mean is the last 
         * one in the communication, depends on the FIN bit.
         * @throws WebSocketFormatException
         * @throws WebSocketMaxLengthException
         * @throws IOException
         */
        public boolean fillFrame(byte readByte) throws WebSocketFormatException, WebSocketMaxLengthException, IOException {
                if(!isFirstByteRead()) {
                        setFirstByte(readByte);
                        return false;
                }
                if(!isSecondByteRead()) {
                        setSecondByte(readByte);
                        return false;
                }
                if(!isLenghtSettled()) {
                        setLength(readByte);
                        return false;
                }
                if(!isMaskSettled()) {
                        setMask(readByte);
                        return false;
                }
                if(isThisTheCloseFrame()) {
                        if(isClosureReady()) {
                                fullFrame();
                        } else {
                                setStatusCode(readByte);
                        }
                        return false;
                } else if (!isDataSettled()) {
                        return setData(readByte);
                } else {
                        return isDataSettled();
                }
        }
        /**
         * Allows to check if the frame is fragmented or not.
         * @return Boolean telling us if it is close or not.
         */
        public boolean isFragmented() {
                return isFragmented;
        }
        /**
         * Allows to check if the frame is fragmented or not.
         * @return Boolean telling us if it is close or not.
         */
        public byte[] getMask() {
                return mask;
        }
        /**
         * Allows to check if the frame is the begin of a fragmented message.
         * @return Boolean telling us if this is the first frame of a message.
         */
        public boolean isBeginOfMessage() {
                return messageBegins;
        }
        /**
         * Allows to check if the frame is the end of a fragmented message.
         * @return Boolean telling us if this is the first frame of a message.
         */
        public boolean isEndOfMessage() {
                return messageEnds;
        }        
}
