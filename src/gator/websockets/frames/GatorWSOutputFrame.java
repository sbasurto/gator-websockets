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

import java.math.BigInteger;
import java.security.SecureRandom;

/**
 *
 * @author <a href="mailto:sbasurto@soft-gator.com">Sergio Basurto Juárez</a>
 * @version 1.0
 */
public class GatorWSOutputFrame  extends GatorWSFrame {                
        /**
         * The frame.
         */
        private byte []frame = new byte[2];  
        
        /**
         * Mask for the frame.
         */
        private byte []mask = new byte[4];
        
        /**
         * The frame is masked?
         */
        private boolean isMasked = false;
        
        /**
         * Constructor.
         * @param _type The type of frame that this will be.
         */
        public GatorWSOutputFrame(int _type) {                
                int fin = 128;
                fin += _type;
                frame[0] = getByteUnsigned(fin);
        }
        
        /**
         * Allows to create the mask.
         */
        public void createMask() {
                try {
                        SecureRandom secRandomGen = SecureRandom.getInstance("SHA1PRNG", "SUN");
                        mask = new byte[4];
                        secRandomGen.nextBytes(mask);
                        isMasked = true;
                } catch (Exception e) {
                        mask = null;
                        isMasked = false;
                }
        }
        
        /**
         * Add bytes of message length to message.
         * @param messageLength 
         */
        public void setMessageLength(int messageLength) {                
                int withMask = 0;
                if(isMasked) {
                        withMask = 128;
                } 
                if(messageLength <= 125) {                                
                        frame[1] = getByteUnsigned(withMask + messageLength);
                }
                if(messageLength > 125 && messageLength <= 65535) {                        
                        frame[1] = getByteUnsigned(withMask + 126);
                        
                        byte []realLength = BigInteger.valueOf(messageLength).toByteArray();
                        if(realLength.length < 2) {
                                byte [] missingByte = new byte[1];
                                missingByte[0] = getByteUnsigned(0);
                                realLength = concatByteArray(missingByte, realLength);
                        }
                        frame = concatByteArray(frame, realLength);
                }
                if(messageLength > 65535) {
                        frame[1] = getByteUnsigned(withMask + 127);
                        byte []realLength = BigInteger.valueOf(messageLength).toByteArray();
                        if(realLength.length < 4) {
                                byte [] missingByte = new byte[1];
                                missingByte[0] = getByteUnsigned(0);
                                realLength = concatByteArray(missingByte, realLength);
                        }
                        frame = concatByteArray(frame, realLength);
                }
                if(isMasked) {
                        for(int i = 0; i < mask.length; i++) {
                                System.out.println("\t\tmask:" + (mask[i] & 0xff));
                        }
                        frame = concatByteArray(frame, mask);
                } 
        }
        
        /**
         * Allows to add data to frame.
         * 
         * @param messageBytes Byte array representing the message.
         * 
         * @return Data as an array of bytes.
         */
        public byte[] addData(byte[] messageBytes) {
                byte []maskedBytes = new byte[messageBytes.length];
                if(isMasked) {
                        for (int i = 0; i < messageBytes.length; i++) {						
                                maskedBytes[i] = (byte) (messageBytes[i] ^ mask[i & 0x3]);					
                        }
                } else {
                        maskedBytes = messageBytes;
                }
                setMessageLength(maskedBytes.length);
                frame = concatByteArray(frame, maskedBytes);
                return frame;
        }
        
        private byte getByteUnsigned(int byteToGet) {
                return (byte) ((byte) byteToGet & 0xff);
        }
}
