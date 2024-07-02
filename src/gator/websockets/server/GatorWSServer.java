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
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Properties;
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
	private final int port;
	private SSLServerSocket sslSocketServer;
	private ServerSocket socketServer;
	private final GappLogging logger;
	private final GappLog gappLog;
	private final boolean withSSL;
        private final boolean withDebug;
	private final GappDateFactory gappDateFactory;
	
	public GatorWSServer(int _port, boolean _withDebug, boolean _withSSL) {
		logger = new GappLogging();
		gappLog = new GappLog();
	    	gappLog.setFileToLog("websocket");                        
	    	gappLog.setName("websockets");
	    	withSSL = _withSSL;
                withDebug = _withDebug;
	    	gappDateFactory = new GappDateFactory();
                port = _port;
                runServer();
	}    
	private ServerSocket getSocket() {
	    	gappLog.addIdentifier("GatorWSServer", "getSocket");
                try {
		    	if(withSSL) {
		        	if(sslSocketServer == null) {
		            		sslSocketServer = getSSLSocket(port);
                                        gappLog.addMessage("Listening in port " + port + " with SSL");
		        	}
	                        
	                        logger.logIt(gappLog, withDebug);
		        	return sslSocketServer;
		    	} else {
		        	if(socketServer == null) {
					socketServer = new ServerSocket(port);
                                        gappLog.addMessage("Listening in port " + port);
		        	}	                        
	                        logger.logIt(gappLog, withDebug);
		        	return socketServer;
		    	}
                } catch(Exception e) {
	                gappLog.addMessage("The following error occurs:");                        
			gappLog.addMessage(logger.getStackTraceString(e), 2);
			logger.logIt(gappLog, withDebug);
			return null;
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
                        
                        gappLog.addIdentifier("GatorWSServer", "getSSLSocket");
                        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());                                        
                        InputStream tstore = new FileInputStream(new File(GappFiles.CONF_DIR + "/" + trustStoreFile));                        
                        trustStore.load(tstore, passphrase.toCharArray());                        
                        tstore.close();                        
                        gappLog.addMessage("Trust Store Loded");
                        
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

                        String []protos = sslSocket.getSupportedProtocols();
                        for(String proto: protos) {
                                gappLog.addMessage("Supported protocol: " + proto);
                        }
                        sslSocket.setEnabledProtocols(protos);
                        return sslSocket;
                } catch(Exception e) {
                        gappLog.addMessage("The following error occurs:");
                        gappLog.addMessage(logger.getStackTraceString(e), 2);
			logger.logIt(gappLog, withDebug);
                        return null;
                }
        }
        private void runServer() {
                ArrayList<GatorWSThread> threadList = new ArrayList<>();
		try {
 			while(true) {
                                Socket socket = getSocket().accept();
                                GatorWSThread wsThread = new GatorWSThread(socket, threadList);
                                threadList.add(wsThread);
                                wsThread.start();
			}
                } catch(Exception e) {
                        gappLog.addMessage("The following error occurs:");
                        gappLog.addMessage(logger.getStackTraceString(e), 2);
			logger.logIt(gappLog, withDebug);
                }
        }
}
