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

import gator.lib.logs.GappLog;
import gator.lib.logs.GappLogging;
import gator.websockets.helpers.GatorWSProperties;
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
            GatorWSProperties gatorProps = new GatorWSProperties();
            try {                                                                                
                    gappLog.setFileToLog("websocket");
                    gappLog.setName("websockets");
                    gappLog.startNewLog("server", "main");
                    gappLog.addMessage("Puerto(" + gatorProps.getPort() + ")");
                    gappLog.addMessage("Con ssl(" + gatorProps.withSSL() + ")");
                    gappLog.addMessage("Con debug(" + gatorProps.withDebug() + ")");
                    
                    logger.logIt(gappLog, true);
                    
                    GatorWSServer server = new GatorWSServer(gatorProps);
            }catch(Exception e) {                        
                    System.err.println("The websocket server cannot start");                        
                    e.printStackTrace(System.err);                
            }
    }
    
}
