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
package gator.websockets;

import java.io.FileInputStream;
import java.util.Properties;
import gator.lib.io.files.GappFiles;
import gator.lib.logs.GappLog;
import gator.lib.logs.GappLogging;
import gator.websockets.server.GatorWSServer;

/**
 *
 * @author <a href="mailto:sbasurto@soft-gator.com">Sergio Basurto Juárez</a>
 * @version 1.0
 */
public class GatorWSRunner {
    
    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) {
            GappLogging logger = new GappLogging();
            GappLog gappLog = new GappLog();
            try {
                    Properties appProps = new Properties();                    
                    appProps.load(new FileInputStream(GappFiles.CONF_DIR + "/websocket.properties"));
                    
                    int port = Integer.parseInt(appProps.getProperty("port"));
                    boolean withDebug = Boolean.parseBoolean(appProps.getProperty("withDebug"));
                    boolean withSSL = Boolean.parseBoolean(appProps.getProperty("withSSL"));
                    if(withSSL && withDebug) System.setProperty("javax.net.debug", "ssl");
                    
                    gappLog.setFileToLog("websocket");
                    gappLog.setName("websockets");
                    gappLog.addIdentifier("server", "main");
                    gappLog.addMessage("Puerto(" + port + ")");
                    gappLog.addMessage("Con ssl(" + withSSL + ")");
                    gappLog.addMessage("Con debug(" + withDebug + ")");
                    
                    logger.logIt(gappLog, true);
                    
                    GatorWSServer server = new GatorWSServer(port, withDebug, false);
            }catch(Exception e) {                        
                    System.err.println("The websocket server cannot start");                        
                    e.printStackTrace(System.err);                
            }
    }
    
}
