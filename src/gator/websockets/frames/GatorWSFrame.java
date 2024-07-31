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

/**
 *
 * @author <a href="mailto:sbasurto@soft-gator.com">Sergio Basurto Juárez</a>
 * @version 1.0
 */
public class GatorWSFrame {
        public static int CONTINUATION_FRAME = 0;
        public static int TEXT_FRAME = 1;
        public static int BINARY_FRAME = 2;
        public static int CLOSE_FRAME = 8;
        public static int PING = 9;
        public static int PONG = 10;
        
        
        private final GappLogging logger  = new GappLogging();
	private final GappLog gappLog = new GappLog();
        /**
         * Allows to concatenate to byte arrays.
         * @param arr1  First bytes array.
         * @param arr2  Second bytes array.
         * @return The final array with the two arrays concatenated.
         */
        public byte[] concatByteArray(byte [] arr1, byte[] arr2) {
                byte []finalArray = new byte[arr1.length + arr2.length];
                int i, globalIndex = 0;
                for(i = 0; i < arr1.length; i++) {
                        finalArray[globalIndex] = arr1[i];
                        globalIndex++;
                }
                for(i = 0; i < arr2.length; i++) {
                        finalArray[globalIndex] = arr2[i];
                        globalIndex++;
                }
                return finalArray;
        }
        /**
         * Allows to encode/decode as stated in RFC6455
         * 
         * @param toEncodeDecode The bytes buffer to encode/decode.
         * @param theMask The mask to use for encode/decode.
         * 
         * @return A byte array encoded or decoded.
         */
        public byte[] encodeDecode(byte []toEncodeDecode, byte []theMask) {
                byte []encodeDecode = new byte[toEncodeDecode.length];
                for(int i = 0; i < toEncodeDecode.length; i++) {
                        encodeDecode[i] = (byte) (toEncodeDecode[i] ^ theMask[i & 0x3]);
                }
                return encodeDecode;
        }
}
