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
import gator.lib.io.files.GappFiles;
import gator.lib.logs.GappLog;
import gator.lib.logs.GappLogging;
import gator.websockets.exception.WebSocketSSLExpiredException;
import gator.websockets.helpers.GatorWSProperties;
import gator.websockets.helpers.GatorWSKeyManager;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.TrustManagerFactory;

/**
 *
 * @author <a href="mailto:sbasurto@soft-gator.com">Sergio Basurto Juárez</a>
 * @version 1.0
 */
public class GatorWSServer {
	private SSLServerSocket sslSocketServer;
	private ServerSocket socketServer;
	private final GappLogging logger;
	private final GappLog gappLog;
	private final GappDateFactory gappDateFactory;
        private final GatorWSProperties gatorProps;
        private final GatorWSKeyManager keyManager;
	
	public GatorWSServer(GatorWSProperties _gatorProps) {
		logger = new GappLogging();
		gappLog = new GappLog();
	    	gappLog.setFileToLog("websocket");                        
	    	gappLog.setName("websockets");
                gatorProps = _gatorProps;
			keyManager = new GatorWSKeyManager(gatorProps.getHpkeMaxConnectionsPerKey(), gatorProps.getHpkeMaxKeyAge());
	    	gappDateFactory = new GappDateFactory();
                runServer();
	}    
	private ServerSocket getSocket() {
	    	gappLog.startNewLog("GatorWSServer", "getSocket");
                try {
		    	if(gatorProps.withSSL()) {
		        	if(sslSocketServer == null) {
		            		sslSocketServer = getSSLSocket(gatorProps.getPort());
                                        if(sslSocketServer == null) throw new IllegalStateException("Cannot create the TLS server socket");
                                        gappLog.addMessage("Listening in port " + gatorProps.getPort() + " with SSL");
		        	}
	                        
	                        logger.logIt(gappLog, gatorProps.withDebug());
		        	return sslSocketServer;
		    	} else {
		        	if(socketServer == null) {
					socketServer = new ServerSocket(gatorProps.getPort());
                                        gappLog.addMessage("Listening in port " + gatorProps.getPort());
		        	}	                        
	                        logger.logIt(gappLog, gatorProps.withDebug());
		        	return socketServer;
		    	}
                } catch(Exception e) {
	                gappLog.addMessage("The following error occurs:");                        
			gappLog.addMessage(logger.getStackTraceString(e), 2);
			logger.logIt(gappLog, gatorProps.withDebug());
			throw new IllegalStateException("Cannot create the websocket server socket", e);
		}
	}
        /**
         * Allows to retrieve a SSLServerSocket.         
         * @param port The port in which socket will listen on.
         * @return An SSLServerSocket ready to use.
         */
        private SSLServerSocket getSSLSocket(int port) {
                try {                
			Properties appProps = new Properties();                        
                        appProps.load(new FileInputStream(GappFiles.CONF_DIR + "/websocket.properties"));                        
                        String passphrase = appProps.getProperty("passphrase");                        
                        String trustStoreFile = appProps.getProperty("truststore");
                        String alias = appProps.getProperty("alias");
                        SSLServerSocket sslSocket;
                        
                        gappLog.startNewLog("GatorWSServer", "getSSLSocket");
                        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());                                        
                        InputStream tstore = new FileInputStream(new File(GappFiles.CONF_DIR + "/" + trustStoreFile));                        
                        trustStore.load(tstore, passphrase.toCharArray());                        
                        tstore.close();                        
                        gappLog.addMessage("Trust Store Loded");
                        gappLog.addMessage("Directory: " + GappFiles.CONF_DIR + "/" + trustStoreFile);
                        
                        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                        tmf.init(trustStore);

                        
                        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());                        
                        InputStream kstore = new FileInputStream(new File(GappFiles.CONF_DIR + "/" + trustStoreFile));                        
                        keyStore.load(kstore, passphrase.toCharArray());
                        if(gappDateFactory.compareVsToday(gappDateFactory.getCalendarFromDate(((X509Certificate) keyStore.getCertificate(alias)).getNotAfter())) < 0) {                                
                                throw new WebSocketSSLExpiredException("The SSL certificate is already expired " + gappDateFactory.getDateInFormat(gappDateFactory.getCalendarFromDate(((X509Certificate) keyStore.getCertificate(alias)).getNotAfter()), "dd 'of' MMMM yyyy, HH:mm:ss.SS") + ", you cannot use it.");
                        }                        
                        kstore.close();                        
                        gappLog.addMessage("Key Store Loded");
                        
                        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                        
                        kmf.init(keyStore, passphrase.toCharArray());
                        
                        
                        SSLContext ctx = SSLContext.getInstance("TLS");
                        ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), new SecureRandom());
                        

                        SSLServerSocketFactory factory = ctx.getServerSocketFactory();
                        
                        sslSocket = (SSLServerSocket) factory.createServerSocket(port);

                        for(String proto: sslSocket.getEnabledProtocols()) {
                                gappLog.addMessage("Supported protocol: " + proto);
                        }
                        return sslSocket;
                } catch(Exception e) {
                        gappLog.addMessage("The following error occurs:");
                        gappLog.addMessage(logger.getStackTraceString(e), 2);
			logger.logIt(gappLog, gatorProps.withDebug());
                        return null;
                }
        }
        private void runServer() {
                // ponytail: copy-on-write favors modest connection churn; use a keyed concurrent registry if profiling says otherwise.
                CopyOnWriteArrayList<GatorWSThread> threadList = new CopyOnWriteArrayList<>();
		try {
			while(true) {
                                Socket socket = getSocket().accept();
                                if(threadList.size() >= gatorProps.getMaxConnections()) {
                                        socket.close();
                                        gappLog.startNewLog("GatorWSServer", "runServer");
                                        gappLog.addMessage("Connection rejected because the server reached its configured limit");
                                        logger.logIt(gappLog, true);
                                        continue;
                                }
                                GatorWSThread wsThread = new GatorWSThread(socket, threadList, gatorProps, keyManager);
                                threadList.add(wsThread);
                                wsThread.start();
			}
                } catch(Exception e) {
                        gappLog.addMessage("The following error occurs:");
                        gappLog.addMessage(logger.getStackTraceString(e), 2);
			logger.logIt(gappLog, gatorProps.withDebug());
                        throw new IllegalStateException("The websocket server stopped unexpectedly", e);
                }
        }
}
