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

import java.util.ArrayList;

/**
 *
 * @author <a href="mailto:sbasurto@soft-gator.com">Sergio Basurto Juárez</a>
 * @version 1.0
 */
public class GatorWSAuthResponse {
        /**
         * Flag telling if the authentication was successful.
         */
         private boolean wasSuccessful = false;
         
         /**
          * The client that was authenticated if successful auth.
          */
         private GatorWSUsuario usuario;
         
         
         /**
          * List of connected users.
          */
         private ArrayList<GatorWSUsuario> usuarios = new ArrayList<>();
                  
         /**
          * Allows to retrieve the authentication status.
          * @return Flag telling if the authentication was successful.
          */
         public boolean wasSuccessful() {
                return wasSuccessful;
         }
         /**
          * Allows to retrieve the authenticate client.
          * @return The client that was authenticated, or null if the authentication fails.
          */
         public GatorWSUsuario getUsuario() {
                return usuario;
         }
         /**
          * Allows to retrieve the authenticate client.
          * @return The client that was authenticated, or null if the authentication fails.
          */
         public ArrayList<GatorWSUsuario> getUsuarios() {
                return usuarios;
         }
}
