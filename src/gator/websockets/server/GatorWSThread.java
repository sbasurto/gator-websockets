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
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;

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
        private boolean authenticated = false;
        private final GatorWSSecurity gatorSecurity;
        private boolean closeMe = false;
        
        public GatorWSThread(Socket _socket, CopyOnWriteArrayList<GatorWSThread> _threadList,
                GatorWSProperties _gatorProps, GatorWSKeyManager keyManager) {
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
                gatorSecurity = new GatorWSSecurity(gatorProps, keyManager.acquire());
                msgHandler = new GatorWSMessageHandler(gatorProps, gatorSecurity);
                setMyId();
                gatorProps.setId(getMyId());
        }
        private void setMyId() {
                myId = "WSS" + gappDateFactory.getDateForId();
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
                        socket.setSoTimeout(30_000);
                        InputStream input0 = socket.getInputStream();
                        OutputStream output0 = socket.getOutputStream();
	                BufferedReader input = new BufferedReader(new InputStreamReader(input0, StandardCharsets.US_ASCII));
                        PrintWriter output = new PrintWriter(output0, true);
                        int readByte;
                        gappLog.startNewLog("GatorWSThread", "run");
                	while (true) {                                                          
                                if(handshakeHandler.isValid()) {                                    
                                                                        
                                    while((readByte = input0.read()) != -1) {                                        
                                        if(packetHandler.fillPacket((byte) readByte)) {
                                            gappLog.clearMessages();
                                            
                                            byte[] messageBytes = packetHandler.getFrameData();
                                            String message = new String(messageBytes, StandardCharsets.UTF_8);
                                            if(packetHandler.getKind().equals("ping")) {
                                                sendMessage(output0, packetHandler.pong(message));
                                                continue;
                                            }
                                            if(packetHandler.getKind().equals("pong")) continue;
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
                                    procesaLinea(input.readLine(), output);                                        
                                    if(handshakeHandler.isValid()) {
                                            socket.setSoTimeout(0);
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
                handshakeBytes += linea.length() + 2;
                if(handshakeBytes > 16_384) {
                    setCloseMe(true);
                } else {
                    buffer.add(linea);
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
        private void processResponses(OutputStream output) {
            gappLog.startNewLog("GatorWSThread", "processResponses");
            if(msgHandler.hasResponse()) {
                for(GatorWSMessage msg: msgHandler.getResponseMsgs()) {
                    if(msg.getType().equals("forcedclosure")) {                        
                        sendMessage(output, msgHandler.getResponseMsgAsString(msg));
                        setCloseMe(true);
                    } else {
                        if(!msg.isForAll() && !msg.hasReceiver()) {
                            sendMessage(output, msgHandler.getResponseMsgAsString(msg));
                            if(msg.getType().equals("authsuccess")) {
                                setAuthenticated(msgHandler.successfulAuth());
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
}
