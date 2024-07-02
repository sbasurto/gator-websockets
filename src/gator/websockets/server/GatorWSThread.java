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
import gator.lib.logs.GappLog;
import gator.lib.logs.GappLogging;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.ArrayList;

/**
 *
 * @author <a href="mailto:sbasurto@soft-gator.com">Sergio Basurto Juárez</a>
 * @version 1.0
 */
public class GatorWSThread extends Thread {
        private Socket socket;
        private ArrayList<GatorWSThread> threadList;
        private PrintWriter output;
        private final GappLogging logger;
	private final GappLog gappLog;
        private String nombre;
        private String myId;
        private final GappDateFactory gappDateFactory;
        
        public GatorWSThread(Socket _socket, ArrayList<GatorWSThread> _threadList) {
                socket = _socket;
                threadList = _threadList;
                logger = new GappLogging();
		gappLog = new GappLog();
                gappLog.setFileToLog("websocket");                        
	    	gappLog.setName("websockets");
                gappDateFactory = new GappDateFactory();
                setMyId();
        }
        private void setMyId() {
                myId = "WSS" + gappDateFactory.getDateForId();
        }
        private String getMyId() {
                return myId;
        }
        @Override
        public void run () {
                gappLog.addIdentifier("GatorWSThread", "run");
		try {
                	BufferedReader input = new BufferedReader( new InputStreamReader(socket.getInputStream()));
                        output = new PrintWriter(socket.getOutputStream(),true);
                        output.println("Hola, ingrese su nombre:");
                	while (true) {
                                String outputString = input.readLine();
                                //if user types exit command
                                if(outputString.equals("exit")) {
                                        output.println("Adios");
                                        break;
                                }
                                if(nombre == null) {                                    
                                    nombre = outputString;
                                    output.println("Que tal " + nombre + ", " + getMyId());
                                    anunciaNuevoUsuario();
                                    output.println("Los usuarios conectados son: ");
                                    anunciaConectados();
                                } else {
                                    outputString = outputString.replaceFirst(":", "::@@::");
                                    String tokens[] = outputString.split("::@@::");
                                    gappLog.addMessage("Tokens: " + tokens.length);
                                    if(tokens.length > 1) {
                                        printToSpecificClient(tokens[0], tokens[1]);
                                    } else {
                                        printToAllClients(outputString);
                                    }
                                }
                                //output.println("Server says " + outputString);
                                gappLog.addMessage("Server received " + outputString);
                                logger.logIt(gappLog, true);
                	}
                        gappLog.addMessage("Terminando esta conversación con " + getNombre());                                
                        logger.logIt(gappLog, true);
                        socket.close();
		} catch(Exception e) {
			gappLog.addMessage("The following error occurs:");
                        gappLog.addMessage(logger.getStackTraceString(e), 2);
			logger.logIt(gappLog, true);
		}
        }
        private String getNombre() {
                return nombre;
        }
        private void printToAllClients(String outputString) {
                String tab = "\t";
                gappLog.addIdentifier("GatorWSThread", "printToAllClients");
                for(int i = 0; i < threadList.size(); i++) {                                                        
                    gappLog.addMessage(this.getNombre() + "(" + this.getMyId() + ") dice, ");
                    gappLog.addMessage(outputString, 2);
                    logger.logIt(gappLog);
                    if(threadList.get(i).isAlive() == true) {
                        threadList.get(i).output.println(this.getNombre() + "(" + this.getMyId() + " dice, ");
                        threadList.get(i).output.println(tab.repeat(2) + outputString);                        
                    } else {
                        threadList.remove(i);
                    }
                }
        }
        private GatorWSThread searchThread(String toSearch) {
                for(int i = 0; i < threadList.size(); i++) {
                        if(threadList.get(i).getMyId().equals(toSearch)) {
                                return threadList.get(i);
                        }
                }
                return null;
        }
        private void printToSpecificClient(String destId, String outputString) {
                String tab = "\t";
                gappLog.addIdentifier("GatorWSThread", "printToSpecificClient");
                
                GatorWSThread destThread = searchThread(destId);
                gappLog.addMessage("Orales: " + destThread);
                if(destThread != null) {
                        gappLog.addMessage(this.getNombre() + " dice, ");
                        gappLog.addMessage(outputString, 2);                        
                        logger.logIt(gappLog);                        
                        if(destThread.isAlive()) {
                                destThread.output.println(this.getNombre() +  "(" + this.getMyId() + ") dice, ");
                                destThread.output.println(tab.repeat(2) + outputString);
                        } 
                }                                
        }
        private void anunciaConectados() {
                String tab = "\t";
                String outputString;
                gappLog.addIdentifier("GatorWSThread", "printToAllClients");
                for(int i = 0; i < threadList.size(); i++) {                                                        
                    gappLog.addMessage("Usuarios conectados: ");                    
                    logger.logIt(gappLog);
                    if(threadList.get(i).isAlive() == true) {
                        outputString = threadList.get(i).getNombre() + ", " + threadList.get(i).getMyId();
                        if(threadList.get(i).getNombre() != null) {
                            gappLog.addMessage("Usuario: " + outputString, 2);
                            threadList.get(i).output.println(tab.repeat(2) + "Usuario:  " + outputString);                        
                        }
                    } else {
                        threadList.remove(i);
                    }
                }
        }
        private void anunciaNuevoUsuario() {
                String tab = "\t";
                String outputString = this.getNombre() + "(" + this.getMyId() + ") se ha conectado ";
                gappLog.addIdentifier("GatorWSThread", "printToAllClients");
                for(int i = 0; i < threadList.size(); i++) {                                                        
                    gappLog.addMessage(outputString);                    
                    logger.logIt(gappLog);
                    if(threadList.get(i).isAlive() == true) {                                                
                        threadList.get(i).output.println(tab.repeat(2) + outputString);                        
                    } else {
                        threadList.remove(i);
                    }
                }
        }
}
