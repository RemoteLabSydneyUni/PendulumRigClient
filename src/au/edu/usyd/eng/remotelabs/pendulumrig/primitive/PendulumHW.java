package au.edu.usyd.eng.remotelabs.pendulumrig.primitive;

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

import java.net.URL;
import java.net.URLDecoder;


public class PendulumHW extends Thread implements SerialPortEventListener {

	private boolean running = true;
	private String threadName;
    private int sleepmSec = 50;
	
	//Status variable
	private volatile int intErrCount = 999;
	private volatile int watchdog = 999;
	
	//local variables to store live data from Arduino
	
	private volatile int intProcessMode = 0;
	private volatile int intProcessState = 0;
	
	private volatile float flPeriod = 0.0f;

	private volatile int intLengthPV = 0;
	private volatile int intLengthDefault = 0;
	private volatile int intLengthSP = 0;
	
	private volatile int intSensorFault = 1;
	private volatile int intStepSP = 0;

		
	//output data buffer
	private volatile int commandBuffer = 0;
	
	private volatile int lengthSPBuffer = 0;
	private volatile int autoLengthSPBuffer = 0;
	
	// action flags
	private volatile boolean commandFlag = false;
	private volatile boolean lengthSPFlag = false;
	private volatile boolean autoLengthSPFlag = false;
	
	private volatile boolean fileFlag = false;
	private volatile boolean cleanupFlag = false;
	
	// hardware Specs
	private static final int MAX_LENGTH= 1080;
	private static final int STEP_PER_ROUND = 400;
	private static final int LENGTH_PER_ROUND = 2;
	
	// Recipe Management
	private volatile int[] recipe = new int[8];
	private volatile int currentRecipe = 0;
	
	// Remote Lock
	private volatile int remoteLock = 0;
	
    ILogger logger;

    /* Arduino interfacing */
	SerialPort serialPort;
	/** The port we're normally going to use. */
	private static final String PORT_NAMES[] = {
//		"/dev/tty.usbserial-A9007UX1", // Mac OS X
//        "/dev/ttyACM0", // Raspberry Pi
//        "/dev/ttyUSB0", // Linux
		"COM9", // Windows
	};
    private BufferedReader input;
    private static OutputStream output;

    private final IConfig config;
    String portNameFromConfig = "empty";
    String defaultRecipeFromConfig = "";
    String ctrlConfigpath = "empty";
   
    private static final int TIME_OUT = 2000;
    private static final int DATA_RATE = 115200;
    
    String inputLine;
    boolean inputLineComplete = false;

    
    //------------------------------------------------------------------
    // HW module constructor
	PendulumHW(String name){

		intProcessMode = 0;
		intProcessState = 0;
		
		intLengthPV = 0;
		intLengthSP = 0;
		
		commandBuffer = 0;
		lengthSPBuffer = 0;
		autoLengthSPBuffer = 0;
		
		fileFlag = false;
		cleanupFlag = false;
		
		currentRecipe = 0;
		for (int i = 0; i<=7; i++){
			recipe[i] = 0;
		}
		
		watchdog = 999;
		remoteLock = 0;
		
		this.config = ConfigFactory.getInstance();
		portNameFromConfig = config.getProperty("COM_Port");
		defaultRecipeFromConfig = config.getProperty("Default_Recipe");
		ctrlConfigpath = config.getProperty("Last_Config_File_Path");
		
	}
	
    //------------------------------------------------------------------
    // HW module main operations
	
	public void run() {

		String response ="";
		
	    logger = LoggerFactory.getLoggerInstance();
        logger.info("Primitive Controller HW Interface created");
		// Construct file path of the configuration file
        String pathRaw = PendulumHW.class.getProtectionDomain().getCodeSource().getLocation().getPath();
        String pathFolder = pathRaw.substring(0,pathRaw.lastIndexOf("/"));
        String pathJar = "";
        try{
        	pathJar = URLDecoder.decode(pathFolder, "UTF-8");
        } catch (UnsupportedEncodingException e){
        	pathJar = "NAN";
        }
        String ctrl_pathFile = pathJar +"/" + ctrlConfigpath;
        logger.debug("Primitive Controller HW Interface - Ctrl Config File Path = "+ctrl_pathFile);
        
        // Load default Recipe
        if (defaultRecipeFromConfig != null && !defaultRecipeFromConfig.isEmpty()){ 
        	String[] strRecipe = defaultRecipeFromConfig.split(",");
        	for (int i = 0; i<=7; i++){
				setRecipe(i,Integer.parseInt(strRecipe[i].trim()));
			}	
        }
         // Communication port initialization
		serialInitialise();
		
		try {
			Thread.sleep(2000); // Give serial port time to start
		 } catch (Exception e) {
             System.err.println(e.toString());
		 }
		
		logger.info("Primitive Controller HW Interface - Initialized");
		
		// Main Process loop
		
		try {
			while (getRunning()) {
				
				// Read data from Arduino
				response = updateValue();
				Thread.sleep(50);
				if ((getProcMode()==0) && (getProcState()==0) && (getCleanupFlag() == false)){
					setCommandBuffer(91);
					setFileFlag();
				}
				if ((getProcMode()==9) && (getProcState()==0)){
					// push new recipe into Arduino
					int recipeIndex;
					if (getCurrentRecipe() == 7){
						recipeIndex = 0;
					}else{
						recipeIndex = getCurrentRecipe()+1;
					}
					setAutoLengthSPBuffer(getRecipe(recipeIndex));
				}
				if ((getProcMode()==9) && (getProcState()==1)){
					// confirm recipe updated, update recipe number
					setCommandBuffer(13);
					increaseRecipe();
				}
				
				// Handling command from UI
				if (getCommandFlag()){
					response = pushCommand();
					offCommandFlag();
				} 
				
				if (getLengthSPFlag()){
					response = pushLengthSP();
					offLengthSPFlag();
				}
				if (getAutoLengthSPFlag()){
					response = pushAutoLengthSP();
					offAutoLengthSPFlag();
				}
				if (getFileFlag()){
					try {
						BufferedReader ctrl_input = new BufferedReader(new InputStreamReader(new FileInputStream(ctrl_pathFile)));
						logger.debug("Primitive Controller HW Interface - Ctrl Config File Identified");
			            // Get file info - line by line
			            while ( ctrl_input.ready() )
			            {
			               String ctrl_nextline = ctrl_input.readLine();
			               if (ctrl_nextline == null) continue;
			               logger.debug(ctrl_nextline);
			               // Break the line down
			               StringTokenizer ctrl_tokens = new StringTokenizer (ctrl_nextline);
			               int ctrl_numargs = ctrl_tokens.countTokens();
			               if ( ctrl_numargs == 0 ) continue;

			               String ctrl_attribute = ctrl_tokens.nextToken();
			               if (ctrl_attribute.equals("#")) continue;

			               // Check the attribute
			               if (ctrl_attribute.equals("LastRecipeNumber")){
			            	   setCurrentRecipe(Integer.parseInt(ctrl_tokens.nextToken()));
			               }else if (ctrl_attribute.equals("LastRecipe")){
			            	   String[] strRecipe = ctrl_tokens.nextToken().split(",");
			               		for (int i = 0; i<=7; i++){
				       				setRecipe(i,Integer.parseInt(strRecipe[i].trim()));
				       			}
			               }
			            }
			            logger.info("Primitive Controller HW Interface - Ctrl Config Extracted");
			            ctrl_input.close();
			         // update flag
						offFileFlag();
					} catch (IOException e) {
						logger.error("Primitive Controller HW Interface - Cannot get Control Config file");
			        }
				}
			}
			logger.info("Primitive Controller HW Interface - Time to end=");
			try {
				BufferedReader CleanupIn = new BufferedReader(new InputStreamReader(new FileInputStream(ctrl_pathFile)));
				logger.debug("Primitive Controller HW Interface - Config File Identified");
				String cleanupOut = "LastRecipeNumber " + String.valueOf(getCurrentRecipe())+ System.getProperty("line.separator");
				cleanupOut = cleanupOut + "LastRecipe ";
				for (int i = 0; i<7;i++){
					cleanupOut = cleanupOut + String.valueOf(getRecipe(i)) + ",";
				}
				cleanupOut = cleanupOut + String.valueOf(getRecipe(7)) + System.getProperty("line.separator");
	            // Get file info - line by line
				FileOutputStream fileOut = new FileOutputStream(ctrl_pathFile);
		        fileOut.write(cleanupOut.getBytes());
		        fileOut.close();
	            logger.info("Primitive Controller HW Interface - Config file updated");

			} catch (IOException e) {
				logger.error("Primitive Controller HW Interface - Cannot get Config file");
				logger.error("Primitive Controller HW Interface - " + e.toString());
	         }
			offCleanupFlag();
			serialClose();
			logger.info("Primitive Controller HW Interface - Serial port closed=");

		} catch (InterruptedException e) {
			logger.error("Primitive Controller HW Interface - Running exception");
		}
        logger.info("Primitive Controller HW Interface - Thread " +  threadName + " exiting.");
	}
	
	//------------------------------------------------------------------
    // Operation support functions (private access)
	//------------------------------------------------------------------
	
	// update local variable with live data from Arduino
	// Multiple variables in Arduino are transmitted as combined string
	// This function read the entire string, tokenize into correct segments
	// and update corresponding local variables with the data value
	
	private String updateValue(){
		String msg = "";
		String feedback = "";
		String[] response = new String[8];
		try{
			msg = sendCmd("rlab://REQV");
			logger.debug("Primitive Controller HW Interface - recvmsg" + msg);
			if (msg.startsWith("Cpl")){
				response = msg.substring(4).trim().split(";",8);
				setPeriod(Float.parseFloat(response[0]));
				setSensorFault(Integer.parseInt(response[1]));
				setStepSP(Integer.parseInt(response[2]));
				setLengthPV(Integer.parseInt(response[3]));
				setLengthSP(Integer.parseInt(response[4]));
				setProcMode(Integer.parseInt(response[5]));
				setProcState(Integer.parseInt(response[6]));
				setWatchdog(Integer.parseInt(response[7]));
				
				feedback = "Cpl";
			}else{
				feedback = "Err";
			}
			Thread.sleep(sleepmSec);
		}
        catch (Exception e) {
            System.err.println(e.toString());
            logger.error("Primitive Controller HW Interface - parsing data error");
            logger.error("Primitive Controller HW Interface - " + e.toString());
        }
		return feedback;
	}
	
	private synchronized void increaseErrCount(){
		// cap the max error count logged as 500
		// to differentiate from the initialization state (999)
		if (intErrCount<500){
			intErrCount++;
		}
	}
	private synchronized void setLengthSP(int val){
		intLengthSP = val;
	}
	private synchronized void setLengthPV(int val){
		intLengthPV = val;
	}
	private synchronized void setSensorFault(int val){
		intSensorFault = val;
	}
	private synchronized void setStepSP(int val){
		intStepSP = val;
	}
	private synchronized void setAutoLengthSPBuffer(int val) {
		autoLengthSPBuffer = Math.min(MAX_LENGTH, Math.max(0,val));
		autoLengthSPFlag = true;
	}
	
	// flags handling
	private synchronized boolean getCommandFlag(){ return commandFlag;}
	private synchronized boolean getLengthSPFlag(){ return lengthSPFlag;}
	private synchronized boolean getAutoLengthSPFlag(){ return autoLengthSPFlag;}
		
	public synchronized int getCommandBuffer() { return commandBuffer;}
	public synchronized int getLengthSPBuffer() { return lengthSPBuffer;}
	public synchronized int getAutoLengthSPBuffer() { return autoLengthSPBuffer;}
	
	private synchronized void offCommandFlag(){  commandFlag = false;}
	private synchronized void offLengthSPFlag(){  lengthSPFlag = false;}
	private synchronized void offAutoLengthSPFlag(){  autoLengthSPFlag = false;}
		
	private synchronized void setCommandFlag(){	commandFlag = true;}
	private synchronized void setLengthSPFlag(){lengthSPFlag = true;}
	
	private synchronized void setWatchdog(int val){ watchdog = val;}
	private synchronized void increaseWatchdog(){
		if (watchdog<500){
			watchdog++;
		}
	}
	
	private synchronized void increaseRecipe(){
		if (currentRecipe == 7){
			currentRecipe = 0;
		}else{
			currentRecipe++;
		}
	}
	private synchronized void setCurrentRecipe(int val){
		if (val>=0 && val<=7){
			currentRecipe = val;
		}
	}
	
	private synchronized void setFileFlag (){ fileFlag = true;}
	private synchronized boolean getFileFlag(){return fileFlag;}
	private synchronized void offFileFlag(){ fileFlag = false;}
	
	private synchronized boolean getCleanupFlag(){return cleanupFlag;}
	private synchronized void offCleanupFlag(){cleanupFlag = false;}


	
	// Push new values to arduino
	private String pushCommand(){
		String response = "";
		try{
			String msg = Integer.toString(getCommandBuffer());
            response = sendCmd("rlab://COMD?addr=" + msg);
        }
        catch (Exception e) {
            System.err.println(e.toString());
        }
		
		return response;
	}
	
	private String pushLengthSP(){
		String response = "";
		try{
			String msg = Integer.toString(getLengthSPBuffer());
            response = sendCmd("rlab://SETV?addr=21&val=" + msg);
        }
        catch (Exception e) {
            System.err.println(e.toString());
        }
		
		return response;
	}

	
	private String pushAutoLengthSP(){
		String response = "";
		try{
			String msg = Integer.toString(getAutoLengthSPBuffer());
            response = sendCmd("rlab://SETV?addr=11&val=" + msg);
        }
        catch (Exception e) {
            System.err.println(e.toString());
        }
		
		return response;
	}
	
	//------------------------------------------------------------------
    // Serial port management functions
	//------------------------------------------------------------------
	
    public void serialInitialise() {
    	logger.info("Primitive Controller HW Interface - beginning serial initialisation");
		CommPortIdentifier portId = null;
    	logger.debug("Primitive Controller HW Interface - looking for ports");
    	try{
    		Enumeration portEnum = CommPortIdentifier.getPortIdentifiers();
    		logger.debug("Primitive Controller HW Interface - finished looking for ports");
    		//First, Find an instance of serial port as set in PORT_NAMES.
			while (portEnum.hasMoreElements()) {
				CommPortIdentifier currPortId = (CommPortIdentifier) portEnum.nextElement();
				if (currPortId.getName().equals(portNameFromConfig)){
					portId = currPortId;
				}
//				for (String portName : PORT_NAMES) {
//					if (currPortId.getName().equals(portName)) {
//						portId = currPortId;
//						break;
//					}
//				}
			}
		}catch (Exception e) {
			logger.error("Primitive Controller HW Interface - Exception - Could not find port.");
		}
    	
    	logger.debug("Primitive Controller HW Interface - finished finding port instance");
		if (portId == null) {
			logger.error("Primitive Controller HW Interface - Could not find COM port.");
			return;
		}
		logger.info("Primitive Controller HW Interface - Serial port ID found");

		try {
			// open serial port, and use class name for the appName.
			serialPort = (SerialPort) portId.open(this.getClass().getName(), TIME_OUT);

			// set port parameters
			serialPort.setSerialPortParams(DATA_RATE,
					SerialPort.DATABITS_8,
					SerialPort.STOPBITS_1,
					SerialPort.PARITY_NONE);

			// open the streams
			input = new BufferedReader(new InputStreamReader(serialPort.getInputStream()));
			output = serialPort.getOutputStream();

            serialPort.addEventListener(this);
            serialPort.notifyOnDataAvailable(true);

            logger.info("Primitive Controller HW Interface - Serial ports opened ");
		} catch (Exception e) {
//			System.err.println(e.toString());
			logger.error("Primitive Controller HW Interface - Serial ports open failed: " + e.toString() );
		}
	}

    public synchronized void serialEvent(SerialPortEvent oEvent) {
        if (oEvent.getEventType() == SerialPortEvent.DATA_AVAILABLE) {
            try {
            	/*
                inputLine=input.readLine();
                setDataReady(true);
                logger.info("Primitive Controller HW Interface - data received ");*/
            } catch (Exception e) {
               // System.err.println(e.toString());
                logger.error("Primitive Controller HW Interface - data exception: " + e.toString());
            }
        }
    }

    public synchronized void serialClose() {
		if (serialPort != null) {
			serialPort.removeEventListener();
			serialPort.close();
		}
	}

    private void send(int b){
        try{
            output.write(b);
        }
        catch (Exception e) {
            System.err.println(e.toString());
        }
    }
    
    private int read(){
        int b = 0;

        try{
            b = (int)input.read();
        }
        catch (Exception e) {
            System.err.println(e.toString());
        }
        return b;
    }

    private synchronized boolean getDataReady() {
    	return inputLineComplete;
    }
    
    private synchronized void setDataReady(boolean val) {
    	inputLineComplete = val;
    }
    
    private String sendCmd(String cmd) {
		String response="";
	
		try {
			// ignore any input that has already been read in
			setDataReady(false); 
			
			// then send the current command
			while (input.ready()) input.read();  // clear input buffer

			logger.debug("Primitive Controller HW Interface - Arduino Cmd=" + cmd);
			output.write(cmd.getBytes());
			output.write('\n');
			output.flush();

			Thread.sleep(50);
			
			int currentChar;
			int attempts=0;
			while (true) {
				currentChar = read();

				if (currentChar==-1 ){
					Thread.sleep(50);
					attempts++;
				}
				else if (currentChar == '\n') break;
				else if (currentChar != 0)response += (char) currentChar;
				if (attempts>60) {
					response = "Err:Timeout";
					break;
				}
			}
			
						
			logger.debug("Primitive Controller HW Interface - Arduino Rsp=" + response);
			if (response.startsWith("Err")){
				increaseErrCount();
			}else if (response.startsWith("Cpl")){
				resetErrCount();
			}
			
		 } catch (Exception e) {
			 logger.error("Primitive Controller HW Interface - could not write to port");
             System.err.println(e.toString());
		 }
		return response;
	}
	
	//------------------------------------------------------------------
    // Public access functions
	//------------------------------------------------------------------
	// Status Check
	public synchronized boolean getRunning(){ return this.running; }
	public synchronized int getErrorCount(){ return intErrCount;}
	
	// Command to hardware - status
	public synchronized void stopRunning() { this.running = false ;	}
	public synchronized void resetErrCount(){intErrCount=0;}
	
	// Present Value Retrieve (from local buffer)

	public synchronized float getPeriod(){return flPeriod;}
	public synchronized void setPeriod(float val){
		flPeriod = val;
	}
	public synchronized int getLengthPV(){return intLengthPV;}

	public synchronized int getLengthSP(){return intLengthSP;}

	public synchronized int getProcMode(){return intProcessMode;}
	public synchronized void setProcMode(int val){
		intProcessMode = val;
	}
	public synchronized int getProcState(){return intProcessState;}
	public synchronized void setProcState(int val){
		intProcessState = val;
	}
			
	// Command to hardware - data
	public synchronized void setLengthSPBuffer(int val) {
		lengthSPBuffer = Math.min(MAX_LENGTH, Math.max(0,val));
		lengthSPFlag = true;
	}
	public synchronized void setCommandBuffer(int val) {
		commandBuffer = val;
		commandFlag = true;
	}
	public synchronized int getWatchdog(){return watchdog;}
	// for debugging
	public synchronized int getSensorFault(){ return intSensorFault;}
	public synchronized int getStepSP(){ return intStepSP;}
	
	public synchronized void setRecipe(int index, int val){
		if (index>=0 && index<=7){
			recipe[index] = val;
		}
	}
	public synchronized int getRecipe(int index){
		if (index>=0 && index<=7){
			return recipe[index];
		}else{
			return 0;
		}
	}
	public synchronized int getCurrentRecipe(){ return currentRecipe;}
	
	public synchronized void setCleanupFlag(){cleanupFlag = true;}
	
	public synchronized int getRemoteLock(){ return remoteLock;}
	public synchronized void setRemoteLock(int val){
		if (val == 1 || val == 0){
			remoteLock = val;
		}
	}
	
}