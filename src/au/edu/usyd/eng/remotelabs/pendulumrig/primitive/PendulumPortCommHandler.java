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

public class PendulumPortCommHandler extends Thread{
	PendulumHW      prHW;         // connection to the sim engine
	DataInputStream    ip;
	DataOutputStream   op;
	Socket             connection;  // Network connection
	int                conID;
	byte buf[] = new byte[1000];
	int numread;
	boolean            running = true;
	
	ILogger logger;
	
	public PendulumPortCommHandler(Socket con, int id, PendulumHW hw){
		this.prHW = hw;
		this.connection = con;
		this.conID = id;
	}
	
	public void run (){
		boolean quit=false;
	    String  request, response;
	    
	    logger = LoggerFactory.getLoggerInstance();

	    try {
	       // we have a connection - create the streams
	       ip = new DataInputStream(connection.getInputStream());
	       op = new DataOutputStream(connection.getOutputStream());
	       do {
	          // read in the command
	          numread = ip.read(buf);
	          request = new String(buf, 0, numread);
	          // process request
	          response = processRequest(request);
	          logger.debug("Primitive Controller HW Comm(" + conID + "): request <" + request + "> - response <" + response + ">");
	          op.write(response.getBytes());
	          op.flush();
	       } while (!request.equals("Quit") && this.getRunning());
       
	       logger.info("Primitive Controller HW Comm(" + conID + "): closing connection");
	       connection.close();
	       
	       while (this.getRunning()) { }
	    }
	    catch (IOException e) {
	       e.printStackTrace();
	    }
	    logger.info("Primitive Controller HW Comm(" + conID + "): connection closed");
	}
	
	public String processRequest (String request){
		int     i;
		String  response = "";
		String  command, args[];
		
		// First, break the command down
		StringTokenizer tokens = new StringTokenizer (request);
		int numargs = tokens.countTokens();
		
		args = new String[10];
		for (i=0; i<numargs; i++) args[i] = new String(tokens.nextToken());
		for (i=numargs; i<10; i++) args[i] = new String("0");
		command = args[0];
		
		// Protocol Check
		
		// COMMAND  - GetPeriod
	    // RESPONSE - Done/Fail
	    if (command.equals("GetPeriod")) {
	    	response = String.valueOf(prHW.getPeriod());
	    }
	    else if (command.equals("GetLength")) {
	    	response = String.valueOf(prHW.getLengthPV());
	    }
	    else if (command.equals("GetMode")) {
	    	response = String.valueOf(prHW.getProcMode());
	    }
	    else if (command.equals("GetState")) {
	    	response = String.valueOf(prHW.getProcState());
		}
	    else if (command.equals("GetCurrentRecipe")) {
	    	response = String.valueOf(prHW.getCurrentRecipe());
		}
	    else if (command.equals("GetRemoteLock")) {
	    	response = String.valueOf(prHW.getRemoteLock());
		}
	    else if (command.equals("GetRecipe")) {
	    	int recNum = Integer.parseInt(args[1]);
	    	if (recNum>7 || recNum < 0){
	    		response = "invalid";
	    	}else{
	    		response = String.valueOf(prHW.getRecipe(recNum));
	    	}
	    }
	    else if (command.equals("SetRecipe")) {
	    	int recNum = Integer.parseInt(args[1]);
	    	int recVal = Integer.parseInt(args[2]);
	    	if ((recNum>7) || (recNum < 0) || (prHW.getRemoteLock() == 1) || (recVal<220) || (recVal >980)){
	    		response = "invalid";
	    	}else{
	    		prHW.setRecipe(recNum, recVal);
	    		response = "Done";
	    	}
	    }
	    else{
	    	response = "unknown";
	    	
	    }
		return response;
	}
	
	public synchronized boolean getRunning(){ return this.running; }
	public synchronized void stopRunning() {
		this.running = false ;
        this.logger.info("Primitive Controller cleanup- handler " + conID + " set to terminate");
	}

}
