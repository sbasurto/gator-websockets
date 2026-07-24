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
import java.util.Arrays;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

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
        private final Set<String> allowedOrigins;
        private final int maxConnections;
        private final int handshakeTimeoutMillis;
        private final int authenticationTimeoutMillis;
        private final int idleTimeoutMillis;
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
                allowedOrigins = Arrays.stream(appProps.getProperty("allowedOrigins", "").split(","))
                        .map(String::trim).filter(origin -> !origin.isEmpty()).collect(Collectors.toUnmodifiableSet());
                maxConnections = positiveInt("maxConnections", 1000);
                handshakeTimeoutMillis = positiveSecondsAsMillis("handshakeTimeoutSeconds", 30, false);
                authenticationTimeoutMillis = positiveSecondsAsMillis("authenticationTimeoutSeconds", 30, false);
                idleTimeoutMillis = positiveSecondsAsMillis("idleTimeoutSeconds", 300, true);
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
                allowedOrigins = source.allowedOrigins;
                maxConnections = source.maxConnections;
                handshakeTimeoutMillis = source.handshakeTimeoutMillis;
                authenticationTimeoutMillis = source.authenticationTimeoutMillis;
                idleTimeoutMillis = source.idleTimeoutMillis;
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
        public Set<String> getAllowedOrigins() {
                return allowedOrigins;
        }
        public int getMaxConnections() {
                return maxConnections;
        }
        public int getHandshakeTimeoutMillis() {
                return handshakeTimeoutMillis;
        }
        public int getAuthenticationTimeoutMillis() {
                return authenticationTimeoutMillis;
        }
        public int getIdleTimeoutMillis() {
                return idleTimeoutMillis;
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
        private int positiveInt(String property, int defaultValue) {
                int value = Integer.parseInt(appProps.getProperty(property, Integer.toString(defaultValue)));
                if(value < 1) throw new IllegalArgumentException(property + " must be positive");
                return value;
        }
        private int positiveSecondsAsMillis(String property, int defaultValue, boolean allowZero) {
                long seconds = Long.parseLong(appProps.getProperty(property, Integer.toString(defaultValue)));
                if(seconds < 0 || (!allowZero && seconds == 0) || seconds > Integer.MAX_VALUE / 1000L) {
                        throw new IllegalArgumentException(property + " has an invalid value");
                }
                return Math.toIntExact(seconds * 1000L);
        }
}
