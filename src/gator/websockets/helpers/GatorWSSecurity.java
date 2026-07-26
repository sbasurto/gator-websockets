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
import gator.websockets.handler.data.GatorWSAuthResponse;
import gator.websockets.handler.data.GatorWSMessage;
import java.util.Set;

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
        
        private final GatorWSHpke hpke;
        private final GatorJWTVerifier jwtVerifier;
        private Set<String> scopes = Set.of();
        
        public GatorWSSecurity(GatorWSProperties _gatorProps, GatorWSKeyManager.Generation generation,
                GatorJWTVerifier jwtVerifier) {
                gatorProps = _gatorProps;
                hpke = new GatorWSHpke(generation);
                this.jwtVerifier = jwtVerifier;
                logger = new GappLogging();
		gappLog = new GappLog();
                gappLog.setFileToLog("websocket");                        
	    	gappLog.setName("websockets " + gatorProps.getInetAddress());
        }
        /**
         * Allows to retrieve the public key for specific client.
         * @return The public key for this client.
         */
        public String getPubKey() {
                GatorWSMessage response = new GatorWSMessage();
                response.setType("askauth");
                response.setKeyForAuth(hpke.publicKey());
                response.setStatus("success", "Success");
                response.addData("version", Integer.toString(GatorWSHpke.VERSION));
                response.addData("keyId", hpke.keyId());
                response.addData("suite", GatorWSHpke.SUITE);
                return new Gson().toJson(response);
        }
        public boolean isEncryptedEnvelope(String message) {
                return GatorWSHpke.looksLikeEnvelope(message);
        }
        public boolean hasSession() {
                return hpke.isEstablished();
        }
        public String decryptMessage(String message) throws java.security.GeneralSecurityException {
                return hpke.open(message);
        }
        public String encryptMessage(String message) throws java.security.GeneralSecurityException {
                return hpke.seal(message);
        }
        /**
         * Allows to authenticate a user.
         * @param wsMsg The message to authenticate this user as a GappWSMessage object.
         * @return If the authenticate process was successful.
         */
        public boolean authenticate(GatorWSMessage wsMsg) {
                gappLog.startNewLog("GatorWSSecurity", "authenticate");
                if(wsMsg.getMessage() == null) {
                        return false;
                }
                if(jwtVerifier != null) {
                        try {
                                GatorJWTVerifier.Identity identity = jwtVerifier.verify(wsMsg.getMessage());
                                setUserId(identity.subject());
                                setName(identity.name());
                                scopes = identity.scopes();
                                return true;
                        } catch(Exception error) {
                                gappLog.addMessage("JWT authentication rejected: " + error.getMessage(), 2);
                                logger.logIt(gappLog, gatorProps.withDebug());
                                return false;
                        }
                }
                if(wsMsg.getData() == null || wsMsg.getData().get("usuario") == null) return false;
                Gson gson = new Gson();
                JsonObject jsonObj = new JsonObject();
                GappDBHelper helper = new GappDBHelper(gatorProps.getConfigFile());
                GappSQLStatement gappSQLStmt = new GappSQLStatement();
                jsonObj.addProperty("id", gatorProps.getId());
                jsonObj.addProperty("ipAddr", gatorProps.getInetAddress());
                jsonObj.addProperty("usuario", wsMsg.getData().get("usuario"));
                jsonObj.addProperty("passphrase", wsMsg.getMessage());
                gappSQLStmt.setStoreProcedure("app_fn_authenticate_ws");
                gappSQLStmt.addParam(gson.toJson(jsonObj));
                String json = helper.executeStore(gappSQLStmt);
                authResp = gson.fromJson(json, GatorWSAuthResponse.class);
                if(authResp != null && authResp.wasSuccessful()) {
                        setUserId(authResp.getUsuario().getId());
                        setName(getAuthResponse().getUsuario().getNombre());
                        scopes = Set.of("messages:send", "messages:receive");
                        gappLog.clearMessages();
                        gappLog.addMessage("Ids: (ori) - " + gatorProps.getId() + ", (new) - " + authResp.getUsuario().getNombre(), 2);                                    
                        logger.logIt(gappLog, gatorProps.withDebug());
                }
                return authResp != null && authResp.wasSuccessful();
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
        public Set<String> getScopes() {
                return scopes;
        }
        public boolean usesJwt() {
                return jwtVerifier != null;
        }
}
