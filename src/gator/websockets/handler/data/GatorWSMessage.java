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
package gator.websockets.handler.data;

import gator.lib.sec.ids.GappUUIDFactory;
import java.util.ArrayList;
import java.util.HashMap;

/**
 *
 * @author <a href="mailto:sbasurto@soft-gator.com">Sergio Basurto Juárez</a>
 * @version 1.0
 */
public class GatorWSMessage {
        /**
         * A universal unique identifier for this message.
         */
         private String msgId;
        /**
         * The type of the message, could be one of the following:
         * <lo>
         * <li>askauth</li>
         * <li>askkey</li>
         * <li>event</li>
         * <li>message</li>
         * </lo>
         * to know how to use them please see documentation.
         */
        private String type;
        
        /**
         * If type is askkey this field must be filled or when the 
         * type is askauth it must be filled also in both cases with
         * public key for this communication.
         */
        private String keyForAuth;
        
        /**
         * If type is askkey this field must be filled or when the 
         * type is askauth but is already authenticated then
         * this key must be used to encrypt message during session.
         */
        private String keyToUse;
        
        /**
         * The status that is been reported in this message, could
         * be error or success.
         */
        private String estatus;
        
        /**
         * A detail description of the status.
         */
        private String estatusDesc;
        
        /**
         * The message.
         */
        private String message;
        
        /**
         * Flag to tell if message is to all connected users.
         */
         private boolean isToAll = false;
         
        /**
         * Flag to tell if message is for specific user(s).
         */
         private boolean hasReceiver = false;
                
        
        /**
         * A key value pairs for additional data that must be included
         * with message.
         */
        private HashMap<String, String> data = new HashMap<>();
        
        
        /**
         * A list of objects.
         */
        private final ArrayList<HashMap<String, String>> list = new ArrayList<>();
        
        /**
         * A list of users.
         */
        private ArrayList<GatorWSUsuario> usuarios = new ArrayList<>();
        
        /**
         * A receivers list for send message.
         */
        private ArrayList<GatorWSUsuario> destinatarios = new ArrayList<>();
        private final GappUUIDFactory uuidFactory = new GappUUIDFactory();
        /**
         * Default constructor.
         */
        public GatorWSMessage() {
            this.msgId = uuidFactory.getUUID();
        }
        /**
         * Allows to set the type for this message.
         * @param _type 
         */
        public void setType(String _type) {
                type = _type;
        }
        
        /**
         * Allows to retrieve the type of this message.
         * 
         * @return The type for message.
         */
        public String getType() {
                return type;
        }
        
        /**
         * Allows to set the public authorization key for this message.
         * @param key The public key to be settled. 
         */
        public void setKeyForAuth(String key) {
                keyForAuth = key;
        }
        
        /**
         * Allows to retrieve the type of this message.
         * 
         * @return The type for message.
         */
        public String getKeyForAuth() {
                return keyForAuth;
        }
        
        /**
         * Allows to set the public authorization key for this message.
         * @param _status The status for this message.
         * @param _statusDesc The status description.
         */
        public void setStatus(String _status, String _statusDesc) {
                estatus = _status;
                estatusDesc = _statusDesc;
        }
        
        /**
         * Allows to retrieve the type of this message.
         * 
         * @return The type for message.
         */
        public String getStatus() {
                return estatus;
        }
        
        /**
         * Allows to retrieve the type of this message.
         * 
         * @return The type for message.
         */
        public String getStatusDesc() {
                return estatusDesc;
        }
        /**
         * Allows to retrieve the message's data.
         * 
         * @return The message's additional data.
         */
        public HashMap<String, String> getData() {
                return data;
        }
        /**
         * Allows to add data to the message.
         * @param key The key for store the data in a map.
         * @param value The value that will be stored.
         */
        public void addData(String key, String value) {
                data.put(key, value);
        }
        /**
         * Allows to set data at once.
         * @param data The data to be set.         
         */
        public void setData(HashMap<String, String> data) {
                this.data = data;
        }
        /**
         * Allows to set the message.
         * @param _message The message string. 
         */
        public void setMessage(String _message) {
                message = _message;
        }
        
        /**
         * Allows to retrieve the type of this message.
         * 
         * @return The message string.
         */
        public String getMessage() {
                return message;
        }
        /**
         * Allows to retrieve the message's list.
         * 
         * @return The message's list of objects.
         */
        public ArrayList<HashMap<String, String>> getList() {
                return list;
        }
        /**
         * Allows to add data to the message.
         * @param el A hash map to add.
         */
        public void addElementToList(HashMap<String, String> el) {
                list.add(el);
        }
        /**
         * Allows to set the public key to use.
         * @param key The public key to be settled. 
         */
        public void setKeyToUse(String key) {
                this.keyToUse = key;
        }
        
        /**
         * Allows to retrieve the key to use.
         * 
         * @return The key to use.
         */
        public String getKeyToUse() {
                return keyToUse;
        }        
        /**
         * Allows to retrieve the message's users list.
         * 
         * @return The message's user list.
         */
        public ArrayList<GatorWSUsuario> getUsuarios() {
                return usuarios;
        }
        /**
         * Allows to set the message's user list.
         * @param users The list of users to set.
         */
        public void setUsuarios(ArrayList<GatorWSUsuario> users) {
                this.usuarios = users;
        }
        /**
         * Allows to add a user.
         * @param user The add in the list.
         */
        public void addUsuario(GatorWSUsuario user) {
                this.usuarios.add(user);
        }
        /**
         * Allows to set if a messages is for all connected users, default is false.       
         */
        public void toAll() {
                this.isToAll = true;
        }
        /**
         * Allows to set if a messages is not for all connected users, default is false.       
         */
        public void notToAll() {
                this.isToAll = false;
        }
        /**
         * Allows to set if a messages is for specific users, default is false.       
         */
        public void itHasReceiver() {
                this.hasReceiver = true;
        }
        /**
         * Allows to set if a messages is not for specific users, default is false.       
         */
        public void itHasNotReceiver() {
                this.hasReceiver = false;
        }
        /**
         * Allows to retrieve if is for all connected users.       
         * @return Flag telling if the message is for all users.
         */
        public boolean isForAll() {
                return this.isToAll;
        }
        /**
         * Allows to retrieve if is for all connected users.       
         * @return Flag telling if the message is for all users.
         */
        public boolean hasReceiver() {
                return this.hasReceiver;
        }
        /**
         * Allows to retrieve the message's receivers list.
         * 
         * @return The message's user list.
         */
        public ArrayList<GatorWSUsuario> getReceivers() {
                return destinatarios;
        }
        /**
         * Allows to set the message's receivers list.
         * @param receivers The list of receivers to set.
         */
        public void setReceivers(ArrayList<GatorWSUsuario> receivers) {
                this.destinatarios = receivers;
        }
        /**
         * Allows to add a receiver.
         * @param receiver The receiver to add in the list.
         */
        public void addReceiver(GatorWSUsuario receiver) {
                this.destinatarios.add(receiver);
        }
}
