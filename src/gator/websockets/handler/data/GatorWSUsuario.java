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
 *
 * @author <a href="mailto:sbasurto@soft-gator.com">Sergio Basurto Juárez</a>
 * @version 1.0
 */
public class GatorWSUsuario {
        private String rowid;       
        private String usuario_id;
        private String usuario_password;
        private String usuario_nombre;
        private String usuario_estado;
        private String usuario_cargo;
        private String usuario_rfc;
        private String usuario_idioma;
        private String usuario_debug_level;
        private String usuario_sesion_timeout;
        private String conexion_id;
        private ArrayList<String> conexiones = new ArrayList<>();
        public void setRowId(String rowid) {
                this.rowid = rowid;
        }
        public String getRowId() {
                return this.rowid;
        }
        public void setId(String usuarioId) {
                this.usuario_id = usuarioId;
        }
        public String getId() {
                return this.usuario_id;
        }        
        public String getPassword() {
                return this.usuario_password;
        }
        public void setNombre(String nombre) {
                this.usuario_nombre = nombre;
        }
        public String getNombre() {
                return this.usuario_nombre;
        }
        public void setEstado(String estado) {
                this.usuario_estado = estado;
        }
        public String getEstado() {
                return this.usuario_estado;
        }
        public void setCargo(String cargo) {
                this.usuario_cargo = cargo;
        }
        public String getCargo() {
                return this.usuario_cargo;
        }
        public void setRFC(String rfc) {
                this.usuario_rfc = rfc;
        }
        public String getRFC() {
                return this.usuario_rfc;
        }
        public void setIdioma(String language) {
                this.usuario_idioma = language;
        }
        public String getIdioma() {
                return this.usuario_idioma;
        }
        public void setDebugLevel(String debugLevel) {
                this.usuario_debug_level = debugLevel;
        }
        public String getDebugLevel() {
                return this.usuario_debug_level;
        }
        public void setSessionTimeout(String sessionTimeout) {
                this.usuario_sesion_timeout = sessionTimeout;
        }
        public String getSessionTimeout() {
                return this.usuario_sesion_timeout;
        }        
        public void addConexion(String conexion) {
                this.conexiones.add(conexion);
        }
        public ArrayList<String> getConexiones() {
                return this.conexiones;
        }
        public void setConexionId(String conexionId) {
                this.conexion_id = conexionId;
        }
        public String getConexionId() {
                return this.conexion_id;
        }
}
