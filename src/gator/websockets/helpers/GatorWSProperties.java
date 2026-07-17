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

import gator.lib.io.files.GappFiles;
import gator.lib.logs.GappLog;
import gator.lib.logs.GappLogging;
import java.io.FileInputStream;
import java.time.Duration;
import java.util.Properties;

/**
 *
 * @author <a href="mailto:sbasurto@soft-gator.com">Sergio Basurto Juárez</a>
 * @version 1.0
 */
public class GatorWSProperties {
        private final Properties appProps = new Properties();
        private final int port;
        private final boolean debug;
        private final boolean ssl;
        private final String dbConfigurationFile;
        private final int hpkeMaxConnectionsPerKey;
        private final Duration hpkeMaxKeyAge;
        private String id;
        private String inetAddress;
        private final GappLogging logger;
	private final GappLog gappLog;
        
        
        public GatorWSProperties() {
                logger = new GappLogging();
		gappLog = new GappLog();
                gappLog.startNewLog("GatorWSProperties", "constructor"); 
                try {
                        appProps.load(new FileInputStream(GappFiles.CONF_DIR + "/websocket.properties"));                        
                } catch(Exception e) {                        
                        gappLog.addMessage(logger.getStackTraceString(e), 2); 
                        logger.logIt(gappLog, true);
                }
                port = Integer.parseInt(appProps.getProperty("port"));
                debug = Boolean.parseBoolean(appProps.getProperty("withDebug"));
                ssl = Boolean.parseBoolean(appProps.getProperty("withSSL"));
                dbConfigurationFile = appProps.getProperty("gappConfigFile");
                hpkeMaxConnectionsPerKey = Integer.parseInt(appProps.getProperty("hpkeMaxConnectionsPerKey", "500"));
                hpkeMaxKeyAge = Duration.ofSeconds(Long.parseLong(appProps.getProperty("hpkeMaxKeyAgeSeconds", "86400")));
                if(ssl && debug) System.setProperty("javax.net.debug", "ssl");
        }
        public GatorWSProperties(GatorWSProperties source) {
                logger = new GappLogging();
                gappLog = new GappLog();
                port = source.port;
                debug = source.debug;
                ssl = source.ssl;
                dbConfigurationFile = source.dbConfigurationFile;
                hpkeMaxConnectionsPerKey = source.hpkeMaxConnectionsPerKey;
                hpkeMaxKeyAge = source.hpkeMaxKeyAge;
        }
        public int getPort() {
                return port;
        }
        public boolean withDebug() {
                return debug;
        }
        public boolean withSSL() {
                return ssl;
        }
        public String getConfigFile() {
                return dbConfigurationFile;
        }
        public int getHpkeMaxConnectionsPerKey() {
                return hpkeMaxConnectionsPerKey;
        }
        public Duration getHpkeMaxKeyAge() {
                return hpkeMaxKeyAge;
        }
        public String getId() {
                return id;
        }
        public void setId(String _id) {
                id = _id;
        }
        /**
         * Allows to set client origin.
         * @param _inetAddress The origin received in hand shake.
         */
        public void setInetAddress(String _inetAddress) {
                inetAddress = _inetAddress;
        }
        /**
         * Allows to ask for index.
         * @return The client's index.
         */
        public String getInetAddress() {                                   
                return inetAddress;
        }
}
