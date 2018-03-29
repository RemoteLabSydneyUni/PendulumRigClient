package au.edu.usyd.eng.remotelabs.pendulumrig.primitive;

import java.awt.*;
import java.io.*;

import gnu.io.CommPortIdentifier; 
import gnu.io.SerialPort;
import gnu.io.SerialPortEvent; 
import gnu.io.SerialPortEventListener; 
import java.util.Enumeration;
import java.util.StringTokenizer;

import au.edu.usyd.eng.remotelabs.pendulumrig.primitive.PendulumHW;
import au.edu.uts.eng.remotelabs.rigclient.util.ConfigFactory;
import au.edu.uts.eng.remotelabs.rigclient.util.IConfig;
import au.edu.uts.eng.remotelabs.rigclient.util.ILogger;
import au.edu.uts.eng.remotelabs.rigclient.util.LoggerFactory;
import java.util.*;

import java.net.*;

public class PendulumPortMonitor extends Thread{
	
	private int port;
	private final IConfig config;
	private boolean running = true;
    String portNameFromConfig = "empty";
	
	PendulumHW prHW;
	
	ILogger logger;
	
	// Constructor
	PendulumPortMonitor (PendulumHW prHW){
		this.prHW = prHW;

		this.config = ConfigFactory.getInstance();

		portNameFromConfig = config.getProperty("Broadcast_Port");
		port = Integer.parseInt(portNameFromConfig);
		
	}
	
	public void run(){
		ServerSocket      server;
	    Socket            connection;
	    int               datain;
	    int               id = 0;
	    ArrayList<PendulumPortCommHandler> handlers = new ArrayList<PendulumPortCommHandler>();
	    
		logger = LoggerFactory.getLoggerInstance();
        logger.info("Primitive Controller HW: Comm Interface created");
        logger.debug("Primitive Controller HW: Comm Interface port = " + portNameFromConfig);
        
	    // try to establish the connection
	    try {
	    	server = new ServerSocket( port, 100 );
	    	logger.info("Primitive Controller HW Comm: Server Socket Setup on Port "+ port);
	    	server.setSoTimeout(500);
	    	while(getRunning()){
	    		try {
	    			connection = server.accept();
		            id++;
		            logger.info("Primitive Controller HW Comm ("+ id +"): Connection established on Port "+ port);
		            // new thread handling the comm
		            PendulumPortCommHandler commHandler = new PendulumPortCommHandler(connection, id, prHW);
		            handlers.add(commHandler);
		            commHandler.start();	    			
	    		}
	    		catch (SocketTimeoutException e) { }
	    	}
	    	for (PendulumPortCommHandler handler:handlers) {
	    		handler.stopRunning();
	    	}
    		server.close();
	    	logger.info("Primitive Controller HW Comm: Connection socket closed");
	    }
	    catch (IOException e) {
	    	logger.error("Primitive Controller HW Comm: Connection error"+ e.toString());
	    }
	}
	public synchronized boolean getRunning(){ return this.running; }
	public synchronized void stopRunning() {
		this.running = false ;
        this.logger.info("Primitive Controller cleanup- port monitor set to terminate");
	}
	
}
