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
package gator.websockets.helpers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import gator.lib.db.GappSQLStatement;
import gator.lib.db.helpers.GappDBHelper;
import gator.lib.logs.GappLog;
import gator.lib.logs.GappLogging;
import gator.lib.sec.GappCrypt;
import gator.websockets.handler.data.GatorWSAuthResponse;
import gator.websockets.handler.data.GatorWSMessage;

/**
 *
 * @author <a href="mailto:sbasurto@soft-gator.com">Sergio Basurto Juárez</a>
 * @version 1.0
 */
public class GatorWSSecurity {
        /**
         * The original id assigned by server, this
         * value must be set once when the user is 
         * authenticated successfully.
         */
        private String userId;
        /**
         * A unique id.
         */
        private String nombre;
        private GatorWSAuthResponse authResp;
        private final GappLogging logger;
	private final GappLog gappLog;
        private final GatorWSProperties gatorProps;
        
        private String aesKey;
        private String myPublicKey;
        private String iv;
        
        public GatorWSSecurity(GatorWSProperties _gatorProps) {
                gatorProps = _gatorProps;
                logger = new GappLogging();
		gappLog = new GappLog();
                gappLog.setFileToLog("websocket");                        
	    	gappLog.setName("websockets " + gatorProps.getInetAddress());
        }
        /**
         * Allows to retrieve the private key for specific client.
         * 
         * @return The private key for this client.
         */
        private String getPrivateKey() {
                Gson gson = new Gson();
                JsonObject jsonObj = new JsonObject();
                jsonObj.addProperty("id", gatorProps.getId());
                GappDBHelper helper = new GappDBHelper(gatorProps.getConfigFile());
                GappSQLStatement gappSQLStmt = new GappSQLStatement();
                gappSQLStmt.setStoreProcedure("app_fn_get_private_key");
                gappSQLStmt.addParam(gson.toJson(jsonObj));
                String privateKey = helper.executeStore(gappSQLStmt);
                return privateKey;
        }
        /**
         * Allows to retrieve the public key for specific client.
         * @param isAuth Flag telling if it is already authenticated.
         * @return The public key for this client.
         */
        public String getPubKey(boolean isAuth) {
                Gson gson = new Gson();
                JsonObject jsonObj = new JsonObject();
                jsonObj.addProperty("id", gatorProps.getId());
                jsonObj.addProperty("isAuthenticated", isAuth);
                GappDBHelper helper = new GappDBHelper(gatorProps.getConfigFile());
                GappSQLStatement gappSQLStmt = new GappSQLStatement();
                gappSQLStmt.setStoreProcedure("app_fn_get_pub_key");
                gappSQLStmt.addParam(gson.toJson(jsonObj));
                String pubKey = helper.executeStore(gappSQLStmt);
                return pubKey;
        }
        /**
         * Allows to decrypt a encrypted string.
         * @param toDecrypt String to be decrypted.
         * @return Decrypted string.
         */
        public String decrypt(String toDecrypt) {
                GappCrypt gappCrypt = new GappCrypt("default");
                return gappCrypt.decryptStringWithPem(toDecrypt, getPrivateKey());
        }
        /**
         * Allows to decrypt a encrypted string.
         * @param toEncrypt String to be encrypted.
         * @return Encrypted string.
         */
        public String encrypt(String toEncrypt) {
                GappCrypt gappCrypt = new GappCrypt("default");                
                return gappCrypt.crytpStringWithPEM(toEncrypt, getMyPublicKey());
        }
        /**
         * Allows to set client public key for encryption.
         * @param key The public key to use with this client to encrypt data.
         */
        public void setMyPublicKey(String key) {
                this.myPublicKey = key;
        }
        /**
         * Allows to ask for public key for encryption.
         * @return The client's public key for encryption.
         */
        public String getMyPublicKey() {
                return this.myPublicKey;
        }
        /**
         * Allows to set client IV for encryption and decryption.
         * @param iv The IV for encryption and decryption.
         */
        public void setIV(String iv) {
                this.iv = iv;
        }
        /**
         * Allows to ask for public key for encryption.
         * @return The client's public key for encryption.
         */
        public String getIV() {
                return this.iv;
        }        
        /**
         * Allows to encrypt a string using AES-CBC.
         * 
         * @param toEncrypt The string to be encrypted.
         * 
         * @return The string encrypted with current key and vi.
         */
        public String encryptAES(String toEncrypt) {
                GappCrypt gappCrypt = new GappCrypt("default");
                gappCrypt.setAESKey(this.getAESKey());    
                this.setIV(gappCrypt.getIVStr());
                return gappCrypt.crytpStringAES(toEncrypt, this.getIV());
        }
        /**
         * Allows to retrieve an AES key, if there is not will create one to use.
         * @return Return a new AES key if does not exist or the current one.
         */
        public String getAESKey() {
                GappCrypt gappCrypt = new GappCrypt("default");
                if(this.aesKey == null) {
                        this.aesKey = gappCrypt.getAESKey();
                        return this.aesKey;
                } else {
                        return this.aesKey;
                }
        }
        /**
         * Allows to decrypt a string using AES-CBC.
         * 
         * @param toDecrypt The string to be encrypted.
         * @param iv The IV to use to decrypt.
         * 
         * @return The string decrypted with current key and vi.
         */
        public String decryptAES(String toDecrypt, String iv) {
                gappLog.startNewLog("GatorWSSecurity", "decryptAES");                                                     
                GappCrypt gappCrypt = new GappCrypt("default");
                gappLog.addMessage("AES Key: " + getAESKey(), 2);                                    
                logger.logIt(gappLog, gatorProps.withDebug());
                gappCrypt.setAESKey(this.getAESKey());                    
                return gappCrypt.decryptStringAES(toDecrypt, this.getAESKey(), iv);
        }
        /**
         * Allows to authenticate a user.
         * @param wsMsg The message to authenticate this user as a GappWSMessage object.
         * @return If the authenticate process was successful.
         */
        public boolean authenticate(GatorWSMessage wsMsg) {
                gappLog.startNewLog("GatorWSSecurity", "authenticate");
                Gson gson = new Gson();
                JsonObject jsonObj = new JsonObject();
                GappDBHelper helper = new GappDBHelper(gatorProps.getConfigFile());
                GappSQLStatement gappSQLStmt = new GappSQLStatement();
                jsonObj.addProperty("id", gatorProps.getId());
                jsonObj.addProperty("ipAddr", gatorProps.getInetAddress());
                jsonObj.addProperty("usuario", decrypt(wsMsg.getData().get("usuario")));
                jsonObj.addProperty("passphrase", decrypt(wsMsg.getMessage())); 
                gappLog.addMessage("Message add to this request:" + decrypt(wsMsg.getMessage()), 2);                                    
                logger.logIt(gappLog, gatorProps.withDebug());                
                gappSQLStmt.setStoreProcedure("app_fn_authenticate_ws");
                gappSQLStmt.addParam(gson.toJson(jsonObj));
                String json = helper.executeStore(gappSQLStmt);
                authResp = gson.fromJson(json, GatorWSAuthResponse.class);
                if(authResp.wasSuccessful()) {
                        setUserId(authResp.getUsuario().getId());
                        setName(getAuthResponse().getUsuario().getNombre());
                        setMyPublicKey(wsMsg.getData().get("key"));
                        getAESKey();
                        gappLog.clearMessages();
                        gappLog.addMessage("Ids: (ori) - " + gatorProps.getId() + ", (new) - " + authResp.getUsuario().getNombre(), 2);                                    
                        logger.logIt(gappLog, gatorProps.withDebug());
                }
                return authResp.wasSuccessful();
        }
        public GatorWSAuthResponse getAuthResponse() {
                return this.authResp;
        }
        /**
         * Allows to set customer id.
         * @param id The new id for customer.
         */
        public void setUserId(String id) {
                userId = id;
        }
        /**
         * Allows to ask for index.
         * @return The client's index.
         */
        public String getUserId() {
                return userId;
        }
        /**
         * Allows to set client name.
         * @param name The origin received in hand shake.
         */
        public void setName(String name) {
                nombre = name;
        }
        /**
         * Allows to ask for index.
         * @return The client's index.
         */
        public String getName() {
                return this.nombre;
        }
}
