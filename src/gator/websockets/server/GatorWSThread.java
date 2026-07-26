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
package gator.websockets.server;

import gator.lib.date.GappDateFactory;
import gator.lib.logs.GappLog;
import gator.lib.logs.GappLogging;
import gator.websockets.frames.GatorWSOutputFrame;
import gator.websockets.handler.GatorPacketHandler;
import gator.websockets.handler.GatorWSHandShakeHandler;
import gator.websockets.handler.GatorWSMessageHandler;
import gator.websockets.handler.data.GatorWSMessage;
import gator.websockets.handler.data.GatorWSUsuario;
import gator.websockets.helpers.GatorWSProperties;
import gator.websockets.helpers.GatorWSSecurity;
import gator.websockets.helpers.GatorWSKeyManager;
import gator.websockets.helpers.GatorJWTVerifier;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;
import gator.websockets.realtime.GatorRealtimeCoordinator;
import java.util.UUID;

/**
 *
 * @author <a href="mailto:sbasurto@soft-gator.com">Sergio Basurto Juárez</a>
 * @version 1.0
 */
public class GatorWSThread extends Thread {
        private final Socket socket;
        private final CopyOnWriteArrayList<GatorWSThread> threadList;
        private final GappLogging logger;
	private final GappLog gappLog;
        private String myId;
        private final ArrayList<String> buffer = new ArrayList<>();
        private int handshakeBytes = 0;
        private final GatorPacketHandler packetHandler;
        private final GappDateFactory gappDateFactory;
        private final GatorWSHandShakeHandler handshakeHandler;
        private final GatorWSMessageHandler msgHandler;
        private final GatorWSProperties gatorProps;
        private volatile boolean authenticated = false;
        private final GatorWSSecurity gatorSecurity;
        private final GatorRealtimeCoordinator realtime;
        private volatile boolean closeMe = false;
        
        public GatorWSThread(Socket _socket, CopyOnWriteArrayList<GatorWSThread> _threadList,
                GatorWSProperties _gatorProps, GatorWSKeyManager keyManager, GatorRealtimeCoordinator realtime,
                GatorJWTVerifier jwtVerifier) {
                socket = _socket;
                threadList = _threadList;
                logger = new GappLogging();
		gappLog = new GappLog();                
                gappDateFactory = new GappDateFactory();
                gatorProps = new GatorWSProperties(_gatorProps);
                handshakeHandler = new GatorWSHandShakeHandler(gatorProps.getAllowedOrigins());
                gatorProps.setInetAddress(socket.getInetAddress().getHostAddress());
                gappLog.setFileToLog("websocket");                        
	    	gappLog.setName("websockets " + gatorProps.getInetAddress());
                packetHandler = new GatorPacketHandler(gatorProps);
                gatorSecurity = new GatorWSSecurity(gatorProps, keyManager.acquire(), jwtVerifier);
                this.realtime = realtime;
                msgHandler = new GatorWSMessageHandler(gatorProps, gatorSecurity, realtime);
                setMyId();
                gatorProps.setId(getMyId());
        }
        private void setMyId() {
                myId = UUID.randomUUID().toString();
        }
        private String getMyId() {
                return myId;
        }
        private Socket getSocket() {
                return socket;
        }
        @Override
        public void run () {
                gappLog.startNewLog("GatorWSThread", "run");
		try {                    
                        socket.setSoTimeout(gatorProps.getHandshakeTimeoutMillis());
                        InputStream input0 = socket.getInputStream();
                        OutputStream output0 = socket.getOutputStream();
                        PrintWriter output = new PrintWriter(output0, true);
                        int readByte;
                        gappLog.startNewLog("GatorWSThread", "run");
                	while (true) {                                                          
                                if(handshakeHandler.isValid()) {                                    
                                                                        
                                    while((readByte = input0.read()) != -1) {                                        
                                        if(packetHandler.fillPacket((byte) readByte)) {
                                            gappLog.clearMessages();
                                            
                                            byte[] messageBytes = packetHandler.getFrameData();
                                            if(packetHandler.getKind().equals("ping")) {
                                                sendMessage(output0, packetHandler.pong(messageBytes));
                                                continue;
                                            }
                                            if(packetHandler.getKind().equals("pong")) continue;
                                            String message = new String(messageBytes, StandardCharsets.UTF_8);
                                            msgHandler.processMessage(message, amIAuthenticated());
                                            processResponses(output0);                                            
                                            
                                            if(closeMe()) {
                                                break;
                                            }                                            
                                            
                                        } else {
                                            if(packetHandler.getProtocolErrorCode() != 0) {
                                                sendMessage(output0, packetHandler.closeWebSocket(packetHandler.getProtocolErrorCode()));
                                                setCloseMe(true);
                                            } else if(packetHandler.isThisTheCloseFrame()) {
                                                if(packetHandler.isClosureReady()) {
                                                    if(amIAuthenticated()) msgHandler.createDisconnectMessage();
                                                    processResponses(output0);
                                                    gappLog.clearMessages();
                                                    gappLog.addMessage("Si es un frame de cierre!!!", 2);
                                                    gappLog.addMessage("enviando: " + packetHandler.getStatusCode(), 2);
                                                    logger.logIt(gappLog, gatorProps.withDebug());
                                                    sendMessage(output0, packetHandler.closeWebSocket(packetHandler.getStatusCode()));
                                                    setCloseMe(true);
                                                }
                                            }
                                        }
                                        if(closeMe()) break;
                                    }
                                    setCloseMe(true);
                                } else {
                                    procesaLinea(readHandshakeLine(input0), output);
                                    if(handshakeHandler.isValid()) {
                                            socket.setSoTimeout(gatorProps.getAuthenticationTimeoutMillis());
                                            sendMessage(output0, gatorSecurity.getPubKey());
                                    }                                                                        
                                }
                                if(closeMe()) {
                                    break;
                                }
                	}
                        gappLog.addMessage("ending socket " + getMyId());                                
                        logger.logIt(gappLog, true);
		} catch(Exception e) {
			gappLog.addMessage("The following error occurs:");
                        gappLog.addMessage(logger.getStackTraceString(e), 2);
			logger.logIt(gappLog, gatorProps.withDebug());
		} finally {
                        if(realtime != null) realtime.disconnected(connectionId(), "socket_closed");
                        threadList.remove(this);
                        try {
                                socket.close();
                        } catch(Exception ignored) {
                        }
		}
        }
        private void procesaLinea(String linea, PrintWriter output) {
            gappLog.startNewLog("GatorWSThread", "procesaLinea");
            if(linea == null) {
                setCloseMe(true);
            } else if(linea.equals("")) {
                if(!handshakeHandler.isValid()) {                    
                    if(handshakeHandler.procesaSaludo(buffer)) {
                        buffer.clear();                                               
                        output.print(handshakeHandler.getHandShakeResponse());
                        output.flush();
                    } else {
                        setCloseMe(true);
                    }
                }                 
            } else {
                buffer.add(linea);
            }
        }
        private String readHandshakeLine(InputStream input) throws IOException {
                ByteArrayOutputStream line = new ByteArrayOutputStream();
                boolean carriageReturn = false;
                while(true) {
                        int value = input.read();
                        if(value == -1) return null;
                        handshakeBytes++;
                        if(handshakeBytes > 16_384) throw new IOException("WebSocket handshake exceeds 16 KiB");
                        if(value == '\n') {
                                if(!carriageReturn) throw new IOException("WebSocket handshake lines must end with CRLF");
                                return line.toString(StandardCharsets.US_ASCII);
                        }
                        if(carriageReturn) throw new IOException("Invalid carriage return in WebSocket handshake");
                        if(value == '\r') {
                                carriageReturn = true;
                        } else {
                                if(value > 0x7f) throw new IOException("WebSocket handshake must use ASCII headers");
                                line.write(value);
                        }
                }
        }
        private boolean amIAuthenticated() {
                return authenticated;
        }
        private void setAuthenticated(boolean flag) {
                authenticated = flag;
        }
        private GatorWSThread searchThread(String needle) {
                for(int i = 0; i < threadList.size(); i++) {
                        if(threadList.get(i).getMyId().equals(needle)) {
                                return threadList.get(i);
                        }
                }
                return null;
        }
        private void sendMessage(OutputStream output, String _message) {
                gappLog.startNewLog("GatorWSThread", "sendMessage(String)");
                try {
                        synchronized(output) {
                                String payload = gatorSecurity.hasSession()
                                        ? gatorSecurity.encryptMessage(_message) : _message;
                                output.write(packetHandler.createMessage(payload, GatorWSOutputFrame.TEXT_FRAME));
                                output.flush();
                        }
                } catch(Exception e) {
                        gappLog.clearMessages();
                        gappLog.addMessage("Cannot encrypt or send websocket message", 2);
                        gappLog.addMessage(logger.getStackTraceString(e), 2);
                        logger.logIt(gappLog, gatorProps.withDebug());
                        setCloseMe(true);
                }
        }
        private void sendMessage(OutputStream output, byte []_message) {
                byte[] message = _message;
                gappLog.startNewLog("GatorWSThread", "sendMessage(byte[])");
                try {
                        synchronized(output) {
                                output.write(message);
                                output.flush();
                        }
                } catch (Exception e) {
                        gappLog.clearMessages();
                        gappLog.addMessage("An error occurs, please verify!", 2);
                        gappLog.addMessage(logger.getStackTraceString(e), 2); 
                        logger.logIt(gappLog, gatorProps.withDebug());
                }
        }
        private void processResponses(OutputStream output) throws IOException {
            gappLog.startNewLog("GatorWSThread", "processResponses");
            if(msgHandler.hasResponse()) {
                for(String response: msgHandler.getRawResponses()) sendMessage(output, response);
                for(GatorWSMessage msg: msgHandler.getResponseMsgs()) {
                    if(msg.getType().equals("forcedclosure")) {                        
                        sendMessage(output, msgHandler.getResponseMsgAsString(msg));
                        setCloseMe(true);
                    } else {
                        if(!msg.isForAll() && !msg.hasReceiver()) {
                            sendMessage(output, msgHandler.getResponseMsgAsString(msg));
                            if(msg.getType().equals("authsuccess")) {
                                setAuthenticated(msgHandler.successfulAuth());
                                if(amIAuthenticated()) {
                                        socket.setSoTimeout(gatorProps.getIdleTimeoutMillis());
                                        if(realtime != null) {
                                                try { realtime.connected(connectionId(), gatorSecurity.getUserId()); }
                                                catch(Exception error) { throw new IOException("Cannot register realtime connection", error); }
                                        }
                                }
                            }
                        }
                        if(msg.isForAll()) {
                            sendMessageToAll(msgHandler.getResponseMsgAsString(msg));
                        }
                        if(msg.hasReceiver()) {
                            for(GatorWSUsuario usuario: msg.getReceivers()) {                                
                                //gappLog.addMessage("Destinatario:" + usuario.getConexionId(), 2);
                                //logger.logIt(gappLog, gatorProps.withDebug());
                                sendMessageTo(msgHandler.getResponseMsgAsString(msg), usuario.getConexionId());
                            }
                            //
                        }
                    }
                }
            }
            msgHandler.clearResponseMsgs();
        }
        private void setCloseMe(boolean flag) {
                closeMe = flag;
        }
        private boolean closeMe() {
                return closeMe;
        }
        private void sendMessageToAll(String msg) {
            gappLog.startNewLog("GatorWSThread", "sendMessageToAll");
            try{
                for(GatorWSThread wsThread: threadList) {
                    if(!wsThread.isAlive()) {
                        threadList.remove(wsThread);
                    } else if(wsThread.amIAuthenticated()) {
                        wsThread.sendMessage(wsThread.getSocket().getOutputStream(), msg);
                    }
                }
            } catch(Exception e) {
                gappLog.clearMessages();                        
                gappLog.addMessage("An error occurs, please verify!", 2);                        
                gappLog.addMessage(logger.getStackTraceString(e), 2);                         
                logger.logIt(gappLog, gatorProps.withDebug());
            }
        }    
        private void sendMessageTo(String msg, String destinatario) {
            gappLog.startNewLog("GatorWSThread", "sendMessageTo");
            GatorWSThread destThread = searchThread(destinatario);
            try{   
                if(destThread != null) {
                    if(destThread.isAlive() && destThread.amIAuthenticated()) {
                        destThread.sendMessage(destThread.getSocket().getOutputStream(), msg);
                    }
                }
            } catch(Exception e) {
                gappLog.clearMessages();                        
                gappLog.addMessage("An error occurs, please verify!", 2);                        
                gappLog.addMessage(logger.getStackTraceString(e), 2);                         
                logger.logIt(gappLog, gatorProps.withDebug());
            }
        }
        public boolean matchesConnection(UUID connectionId) {
                return myId.equals(connectionId.toString());
        }
        public boolean deliverRealtime(String envelope) {
                if(!isAlive() || !amIAuthenticated() || closeMe()) return false;
                try {
                        sendMessage(socket.getOutputStream(), envelope);
                        return !closeMe();
                } catch(Exception error) {
                        setCloseMe(true);
                        return false;
                }
        }
        private UUID connectionId() {
                return UUID.fromString(myId);
        }
}
