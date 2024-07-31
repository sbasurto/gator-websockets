/* 
 * Copyright (C) 2017 Sergio Basurto Juárez
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
import gator.lib.logs.GappLogging;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 *
 * @author <a href="mailto:sbasurto@soft-gator.com">Sergio Basurto Juárez</a>
 * @version 1.0
 */
public class GatorWSHandShakeHandler {
        private final String header = "HTTP/1.1 101 Switching Protocols";
        private String reqHeader = "";        
        private String secWebSocketAccept = "";        
        private String protocol = "";
        private String magicString = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
        private final HashMap<String, String> headerData = new HashMap<>();
        private final GappLogging logger;
	private final GappLog gappLog;
        
        public GatorWSHandShakeHandler() {
                logger = new GappLogging();
                gappLog = new GappLog();
                gappLog.setFileToLog("websocket");                        
	    	gappLog.setName("websockets");
        }
        /**
         * Set the header for this hand shake;
         * @param _header 
         */
        private void setHeader(String _header) {
                reqHeader = _header;
        }
        /**
         * Get the header for this hand shake;
         * @param _header
         */
        private String getHeader() {
                return reqHeader;
        }
        /**
         * Allows to set handshake data received from a client.
         * @param key The key for the header data.
         * @param value The value for this key.
         */
        private void addHandShakeData(String key, String value) {
                headerData.put(key, value);
        }
        public boolean procesaSaludo(ArrayList<String> buffer) {
                gappLog.startNewLog("GatorWSHandShakeHandler", "procesaSaludo");  
                
                for(String linea: buffer) {
                    gappLog.addMessage(linea, 2);                    
                    if(linea.matches("GET [/]+ HTTP/1.1")) {
                        setHeader(linea);
                    } else {
                        Matcher match = Pattern.compile("(.*): (.*)").matcher(linea);                                
                        if(match.find()) {
                            addHandShakeData(match.group(1), match.group(2));
                        }
                    }
                }
                gappLog.addMessage("el saludo es válido: " + isValid(), 2);
                logger.logIt(gappLog, true);
                return isValid();
        }
        public boolean isValid() {
            boolean isValid;               
            isValid = getHeader().equals("GET / HTTP/1.1");            
            return isValid;
        }
        public String getHandShakeResponse() {
                gappLog.startNewLog("GatorWSHandShakeHandler", "getHandShakeResponse");
                String response = header + "\r\n";                
                response += "Connection: Upgrade\r\n";                
                response += "Upgrade: websocket\r\n";                
                response += "Sec-WebSocket-Accept:" + getAccept(headerData.get("Sec-WebSocket-Key")) + "\r\n";
                gappLog.addMessage(header, 2);
                gappLog.addMessage("Connection: Upgrade", 2);
                gappLog.addMessage("Upgrade: websocket", 2);
                gappLog.addMessage("Sec-WebSocket-Accept:" + getAccept(headerData.get("Sec-WebSocket-Key")), 2);
                if(headerData.get("Sec-WebSocket-Protocol") != null) {
                        response += "Sec-WebSocket-Protocol: " + headerData.get("Sec-WebSocket-Protocol") + "\r\n";
                        gappLog.addMessage("Sec-WebSocket-Protocol: " + headerData.get("Sec-WebSocket-Protocol"), 2);
                } 
                logger.logIt(gappLog, true);
                if(isValid()) {
                    return response;
                } else {
                    return  "";
                }
                
        }
        public String getAccept(String _key) {                
                try {
                    return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((_key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes("UTF-8")));
                } catch(Exception e) {
                    return "";
                }
        }
}
