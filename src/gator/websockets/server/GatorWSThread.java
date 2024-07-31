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
import gator.lib.io.bytes.GappBytes;
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
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.ArrayList;

/**
 *
 * @author <a href="mailto:sbasurto@soft-gator.com">Sergio Basurto Juárez</a>
 * @version 1.0
 */
public class GatorWSThread extends Thread {
        private final Socket socket;
        private final ArrayList<GatorWSThread> threadList;      
        private final GappLogging logger;
	private final GappLog gappLog;
        private String myId;
        private final ArrayList<String> buffer = new ArrayList<>();
        private final GatorPacketHandler packetHandler;
        private final GappDateFactory gappDateFactory;
        private final GatorWSHandShakeHandler handshakeHandler = new GatorWSHandShakeHandler();
        private final GatorWSMessageHandler msgHandler;
        private final GatorWSProperties gatorProps;
        private boolean authenticated = false;
        private final GatorWSSecurity gatorSecurity;
        private final GappBytes gappBytes;
        private boolean closeMe = false;
        
        public GatorWSThread(Socket _socket, ArrayList<GatorWSThread> _threadList, GatorWSProperties _gatorProps) {
                socket = _socket;
                threadList = _threadList;
                logger = new GappLogging();
		gappLog = new GappLog();                
                gappDateFactory = new GappDateFactory();
                gatorProps = _gatorProps;
                gatorProps.setInetAddress(socket.getInetAddress().getHostAddress());
                gappLog.setFileToLog("websocket");                        
	    	gappLog.setName("websockets " + gatorProps.getInetAddress());
                packetHandler = new GatorPacketHandler(gatorProps);
                gatorSecurity = new GatorWSSecurity(gatorProps);
                gappBytes = new GappBytes();
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
                        InputStream input0 = socket.getInputStream();
                        OutputStream output0 = socket.getOutputStream();
                	BufferedReader input = new BufferedReader( new InputStreamReader(input0));
                        PrintWriter output = new PrintWriter(output0, true);
                        int readByte;
                        gappLog.startNewLog("GatorWSThread", "run");
                	while (true) {                                                          
                                if(handshakeHandler.isValid()) {                                    
                                                                        
                                    while((readByte = input0.read()) != -1) {                                        
                                        if(packetHandler.fillPacket((byte) readByte)) {
                                            gappLog.clearMessages();
                                            
                                            byte[] messageBytes = packetHandler.getFrameData();                                                                                            
                                            msgHandler.processMessage(new String(messageBytes), amIAuthenticated());                                                
                                            processResponses(output0);                                            
                                            
                                            if(closeMe()) {
                                                break;
                                            }                                            
                                            
                                            gappLog.addMessage("Message enviado: " + new String(messageBytes), 2);
                                            logger.logIt(gappLog, gatorProps.withDebug());
                                        } else {
                                            if(packetHandler.isThisTheCloseFrame()) {
                                                if(packetHandler.isClosureReady()) {
                                                    msgHandler.createDisconnectMessage();
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
                                    }
                                } else {
                                    procesaLinea(input.readLine(), output);                                        
                                    if(handshakeHandler.isValid()) {
                                            sendMessage(output0, gatorSecurity.getPubKey(amIAuthenticated()));
                                    }                                                                        
                                }
                                if(closeMe()) {
                                    break;
                                }
                	}
                        gappLog.addMessage("ending socket " + getMyId());                                
                        logger.logIt(gappLog, true);
                        socket.close();
		} catch(Exception e) {
			gappLog.addMessage("The following error occurs:");
                        gappLog.addMessage(logger.getStackTraceString(e), 2);
			logger.logIt(gappLog, gatorProps.withDebug());
		}
        }
        private void procesaLinea(String linea, PrintWriter output) {
            gappLog.startNewLog("GatorWSThread", "procesaLinea");
            if(linea.equals("")) {                	                
                if(!handshakeHandler.isValid()) {                    
                    if(handshakeHandler.procesaSaludo(buffer)) {
                        buffer.clear();                                               
                        output.println(handshakeHandler.getHandShakeResponse());
                    }
                }                 
            } else {                
                buffer.add(linea);                
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
                byte []message = packetHandler.createMessage(_message, GatorWSOutputFrame.TEXT_FRAME);
                gappLog.addMessage(_message, 2);                
                logger.logIt(gappLog, gatorProps.withDebug());
                sendMessage(output, message);
        }
        private void sendMessage(OutputStream output, byte []_message) {
                byte[] message = _message;
                gappLog.startNewLog("GatorWSThread", "sendMessage(byte[])");
                gappLog.addMessage(gappBytes.getByteArrayAsIntString("", message), 2);
                logger.logIt(gappLog, gatorProps.withDebug());
                try {
                        output.write(message);
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
                                sendMessageTo(usuario.getConexionId(), msgHandler.getResponseMsgAsString(msg));
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
                for(int i = 0; i < threadList.size(); i++) {                                                                            
                    if(threadList.get(i).isAlive() == true) {                        
                        sendMessage(threadList.get(i).getSocket().getOutputStream(), msg);
                    } else {
                        threadList.remove(i);
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
                    if(destThread.isAlive()) {                                                
                        sendMessage(destThread.getSocket().getOutputStream(), msg);                    
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
