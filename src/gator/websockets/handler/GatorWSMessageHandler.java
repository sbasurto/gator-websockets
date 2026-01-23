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

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import gator.lib.db.GappSQLStatement;
import gator.lib.db.helpers.GappDBHelper;
import gator.lib.logs.GappLog;
import gator.lib.logs.GappLogging;
import gator.websockets.handler.data.GatorWSMessage;
import gator.websockets.handler.data.GatorWSUsuario;
import gator.websockets.helpers.GatorWSProperties;
import gator.websockets.helpers.GatorWSSecurity;
import java.util.ArrayList;

/**
 *
 * @author <a href="mailto:sbasurto@soft-gator.com">Sergio Basurto Juárez</a>
 * @version 1.0
 */
public class GatorWSMessageHandler {
        private final GappLogging logger;
	private final GappLog gappLog;
        private final GatorWSProperties gatorProps;
        private final GatorWSSecurity gatorSecurity;
        private boolean authenticated = false;
        /**
          * The response for last message processed.
          */
        private final ArrayList<GatorWSMessage> responseMsgs = new ArrayList<>();
        /**
         * Flag to tell if last message processed has a response.
         */
        private boolean hasResponse = false;
        private ArrayList<GatorWSUsuario> usuarios = new ArrayList<>();
        
        public GatorWSMessageHandler(GatorWSProperties _gatorProps, GatorWSSecurity _gatorSecurity) {
                gatorProps = _gatorProps;
                logger = new GappLogging();
		gappLog = new GappLog();
                gatorSecurity = _gatorSecurity;
                gappLog.setFileToLog("websocket");                        
	    	gappLog.setName("websockets " + gatorProps.getInetAddress());
        }
        /**
         * Process any message and generate a response if it applies.
         *          
         * @param message The message to be processed as a string.
         * @param isAuth Flag telling if thread is authenticated.
         */
        public void processMessage(String message, boolean isAuth) {                
                gappLog.startNewLog("GatorWSMessageHandler", "processMessage");                                     
                gappLog.addMessage("Processing message: " + message, 2);                                    
                logger.logIt(gappLog, gatorProps.withDebug());
                hasResponse = false;
                Gson gson = new Gson();
                if(!this.isJson(message)) {
                    String []els = message.split("::@@::");
                    /*gappLog.clearMessages();
                    gappLog.addMessage("crypted message: " + els[0], 2);*/
                    if(els.length >= 2) {
                        gappLog.addMessage("IV: " + gatorSecurity.decrypt(els[1]), 2);
                        gatorSecurity.setIV(gatorSecurity.decrypt(els[1]));
                    }
                    message = gatorSecurity.decryptAES(els[0], gatorSecurity.decrypt(els[1]));
                    //logger.logIt(gappLog, gatorProps.withDebug());
                }     
                gappLog.startNewLog("GatorWSMessageHandler", "processMessage"); 
                gappLog.addMessage("Mensaje descifrado: " + message, 2);                                    
                logger.logIt(gappLog, gatorProps.withDebug());
                GatorWSMessage wsMsg = gson.fromJson(message, GatorWSMessage.class);
                if(!isAuth) {
                    switch (wsMsg.getType()) {
                        case "askkey" -> setAskAuth(isAuth);
                        case "authenticateme" -> authenticate(wsMsg);
                        default -> setForceClosure(wsMsg);
                    }
                } else {                        
                        if(wsMsg.getType().equals("askkey")) {
                                setAskAuth(isAuth);
                        }
                        if(wsMsg.getType().equals("askkeytouse")) {
                                setKeyToUse();
                        }
                        if(wsMsg.getType().equals("getuserlist")) {
                                setUserList();
                        }                        
                        if(wsMsg.getType().equals("message")) {                                
                                if(!wsMsg.getReceivers().isEmpty()) {
                                    createEnvelopeTo(wsMsg);
                                } else {                                    
                                    createEnvelopeToAll(wsMsg);
                                }
                        }
                        if(wsMsg.getType().equals("event")) {                                                                
                                createEventToAll(wsMsg);
                        }
                }
        }
        /**
         * Allows to test a string for JSON.
         * @param json The string to be tested.
         * @return True if the string is a valid JSON, false otherwise.
         */
        public boolean isJson(String json) {
                gappLog.startNewLog("GatorWSMessageHandler", "processMessage");
                try {
                        JsonParser.parseString(json);
                } catch(Exception e) {                        
                        gappLog.addMessage("Not a json" + json, 2);                        
                        //gappLog.addMessage(logger.getStackTraceString(e), 2); 
                        logger.logIt(gappLog, gatorProps.withDebug());                                           
                        return false;
                }
                return true;
        }
        /**
         * Set the ask authorization message.
         * @param isAuth Flag to tell if thread is already authenticated.
         */
        public void setAskAuth(boolean isAuth) {
                Gson gson = new Gson();
                responseMsgs.add(gson.fromJson(gatorSecurity.getPubKey(isAuth), GatorWSMessage.class));
                hasResponse = true;
        }
        /**
         * Set the ask authorization message.
         * @param wsMsg The original message as GappWSMessage.
         */
        public void authenticate(GatorWSMessage wsMsg) {
                if((authenticated = gatorSecurity.authenticate(wsMsg))) {                                                
                        GatorWSMessage responseMsg = new GatorWSMessage();                                
                        responseMsg.setType("authsuccess");                                
                        responseMsg.setStatus("success", "Authentication successful");                        
                        responseMsg.setKeyToUse(gatorSecurity.encrypt(gatorSecurity.getAESKey()));
                        responseMsgs.add(responseMsg);
                        responseMsg = new GatorWSMessage();
                        responseMsg.setType("event");                                                
                        responseMsg.setMessage("gatorwsuserconn");
                        responseMsg.addUsuario(gatorSecurity.getAuthResponse().getUsuario());
                        responseMsg.notToAll();
                        responseMsg.itHasNotReceiver();
                        responseMsgs.add(responseMsg);
                        hasResponse = true;
                } else {                        
                        setForceClosure(wsMsg, "Authentication failure");
                }                
        }
        /**
         * Set the force closure message.
         * @param wsMsg The original message to force the closure.
         */
        public void setForceClosure(GatorWSMessage wsMsg) {
                setForceClosure(wsMsg, "Must authenticate before could send any message");                
        }
        /**
         * Set the force closure message.
         * @param wsMsg The original message to force the closure.
         * @param messageStatus A string to send on closure forced.
         */
        public void setForceClosure(GatorWSMessage wsMsg, String messageStatus) {
                GatorWSMessage responseMsg = new GatorWSMessage();                                
                responseMsg.setType("forcedclosure");                                
                responseMsg.setStatus("error", messageStatus);                                
                responseMsg.addData("msgOri", wsMsg.getMessage());
                responseMsgs.add(responseMsg);
                hasResponse = true;
        }
        public boolean successfulAuth() {
                return authenticated;
        }
        /**
         * Tell if the processed message has a response associated.
         * @return Boolean flag telling if it has a response or not.
         */
        public boolean hasResponse() {
                return hasResponse;
        }
        /**
         * Allow to retrieve last response.
         * @return The response GappWSMessage as string.
         */
        public ArrayList<GatorWSMessage> getResponseMsgs() {                
                return responseMsgs;
        }
        /**
         * Allow to retrieve last response.
         * @param gatorWSMessage GatorWSMessage to convert to string.
         * @return The response selected GatorWSMessage as string.
         */
        public String getResponseMsgAsString(GatorWSMessage gatorWSMessage) {
                Gson gson = new Gson();
                return gson.toJson(gatorWSMessage);
        }
        /**
         * Set the ask authorization message.
         */
        public void setKeyToUse() {
                gappLog.startNewLog("GatorWSMessageHandler", "setKeyToUse");                                                     
                GatorWSMessage responseMsg = new GatorWSMessage();
                responseMsg.setType("keytouse");
                responseMsg.setStatus("success", "");                        
                gappLog.addMessage("Current AES key: " + gatorSecurity.getAESKey(), 2);                                    
                logger.logIt(gappLog, gatorProps.withDebug());
                responseMsg.setKeyToUse(gatorSecurity.encrypt(gatorSecurity.getAESKey()));
                responseMsgs.add(responseMsg);
                hasResponse = true;                               
        }
        /**
         * Set the ask authorization message.
         */
        public void setUserList() {                                        
                GatorWSMessage responseMsg = getUsuariosAsMsg();
                GatorWSUsuario receiver = new GatorWSUsuario();
                receiver.setId(gatorSecurity.getUserId());
                receiver.setConexionId(gatorProps.getId());
                responseMsg.addReceiver(receiver);                
                responseMsg.itHasNotReceiver();
                responseMsg.notToAll();
                responseMsgs.add(responseMsg);
                hasResponse = true;                               
        }
        /**
         * Allows to retrieve the authenticate client.
         * @return The client that was authenticated, or null if the authentication fails.
         */
        public GatorWSMessage getUsuariosAsMsg() {
                Gson gson = new Gson();
                JsonObject jsonObj = new JsonObject();
                jsonObj.addProperty("id", gatorProps.getId());
                jsonObj.addProperty("ipAddr", gatorProps.getInetAddress());
                jsonObj.addProperty("usuario", gatorSecurity.getAuthResponse().getUsuario().getId());
                GappDBHelper helper = new GappDBHelper(gatorProps.getConfigFile());
                GappSQLStatement gappSQLStmt = new GappSQLStatement();
                gappSQLStmt.setStoreProcedure("app_fn_get_usuarios_ws");
                gappSQLStmt.addParam(gson.toJson(jsonObj));
                String json = helper.executeStore(gappSQLStmt);
                GatorWSMessage usersListMsg = gson.fromJson(json, GatorWSMessage.class);
                setUsuarios(usersListMsg.getUsuarios());
                return usersListMsg;
        }
        /**
         * Allows to set the message's user list.
         * @param users The list of users to set.
         */
        private void setUsuarios(ArrayList<GatorWSUsuario> users) {
                usuarios = users;
        }
        /**
         * Allows to prepare the package with the event of 
         * user close connection.
         * 
         * @param wsMsg The original message received.
         */
        public void createEnvelopeTo(GatorWSMessage wsMsg) {
                GatorWSUsuario usuario = new GatorWSUsuario();
                usuario.setId(gatorSecurity.getAuthResponse().getUsuario().getId());
                usuario.setNombre(gatorSecurity.getAuthResponse().getUsuario().getNombre());
                GatorWSMessage responseMsg = sendMessageDataBase(wsMsg);                                
                responseMsg.setReceivers(wsMsg.getReceivers());
                responseMsg.addUsuario(usuario);
                responseMsg.itHasReceiver();
                responseMsgs.add(responseMsg);
                hasResponse = true;                
        }
        /**
         * Allows to send message to one or more users.
         * @param wsMsg Original message received from client.
         * @return The message with status of delivery.
         */
        public GatorWSMessage sendMessageDataBase(GatorWSMessage wsMsg) {
                Gson gson = new Gson();
                JsonObject jsonObj = new JsonObject();
                jsonObj.addProperty("id", gatorProps.getId());
                jsonObj.addProperty("ipAddr", gatorProps.getInetAddress());
                jsonObj.addProperty("usuario", gatorSecurity.getAuthResponse().getUsuario().getId());
                jsonObj.addProperty("message", gson.toJson(wsMsg));
                GappDBHelper helper = new GappDBHelper(gatorProps.getConfigFile());
                GappSQLStatement gappSQLStmt = new GappSQLStatement();
                gappSQLStmt.setStoreProcedure("app_fn_send_message_ws");
                gappSQLStmt.addParam(gson.toJson(jsonObj));
                String json = helper.executeStore(gappSQLStmt);
                GatorWSMessage messageSended = gson.fromJson(json, GatorWSMessage.class);                
                return messageSended;
        }
        /**
         * Allows to prepare the package with the event of 
         * user close connection.
         * 
         * @param wsMsg The original message received.
         */
        public void createEnvelopeToAll(GatorWSMessage wsMsg) {
                gappLog.startNewLog("GatorWSMessageHandler", "createEnvelopeToAll");                 
                
                GatorWSUsuario usuario = new GatorWSUsuario();
                usuario.setId(gatorSecurity.getAuthResponse().getUsuario().getId());
                usuario.setNombre(gatorSecurity.getAuthResponse().getUsuario().getNombre());
                GatorWSMessage responseMsg = sendMessageDataBase(wsMsg);
                responseMsg.addUsuario(usuario);
                responseMsg.toAll();
                gappLog.addMessage(getResponseMsgAsString(responseMsg), 2);
                logger.logIt(gappLog, gatorProps.withDebug());
                responseMsgs.add(responseMsg);                
                hasResponse = true;
        }
        /**
         * Clear response messages.
         */
        public void clearResponseMsgs() {
                responseMsgs.clear();
        }
        /**
         * Allows to prepare the package with the event of 
         * user close connection.
         * 
         */
        public void createDisconnectMessage() {                
                GatorWSMessage responseMsg = new GatorWSMessage();
                GatorWSUsuario usuario = new GatorWSUsuario();
                usuario.setId(gatorSecurity.getAuthResponse().getUsuario().getId());
                usuario.setNombre(gatorSecurity.getAuthResponse().getUsuario().getNombre());
                responseMsg.setType("event");
                responseMsg.setMessage("gatorwsuserdisconn");
                responseMsg.addUsuario(usuario);
                responseMsg.toAll();
                responseMsgs.add(responseMsg);
                responseMsg = new GatorWSMessage();                        
                responseMsg.setType("event");                        
                responseMsg.setMessage("gatorwsconnclose");
                responseMsg.notToAll();                        
                responseMsg.itHasNotReceiver();                        
                responseMsgs.add(responseMsg);
                hasResponse = true;
        }
        /**
         * Allows to send event to all users connected.
         * 
         * @param wsMsg
         */
        public void createEventToAll(GatorWSMessage wsMsg) {                
                GatorWSMessage responseMsg = new GatorWSMessage();
                GatorWSUsuario usuario = new GatorWSUsuario();
                usuario.setId(gatorSecurity.getAuthResponse().getUsuario().getId());
                usuario.setNombre(gatorSecurity.getAuthResponse().getUsuario().getNombre());
                responseMsg.setType("event");
                responseMsg.setMessage(wsMsg.getMessage());
                responseMsg.setData(wsMsg.getData());
                responseMsg.addUsuario(usuario);
                responseMsg.toAll();
                responseMsgs.add(responseMsg);                
                hasResponse = true;
        }
}