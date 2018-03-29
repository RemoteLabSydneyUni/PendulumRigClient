package au.edu.usyd.eng.remotelabs.pendulumrig.primitive;

import au.edu.uts.eng.remotelabs.rigclient.rig.IRigControl.PrimitiveRequest;
import au.edu.uts.eng.remotelabs.rigclient.rig.IRigControl.PrimitiveResponse;
import au.edu.uts.eng.remotelabs.rigclient.rig.primitive.IPrimitiveController;
import au.edu.uts.eng.remotelabs.rigclient.util.ConfigFactory;
import au.edu.uts.eng.remotelabs.rigclient.util.IConfig;
import au.edu.uts.eng.remotelabs.rigclient.util.ILogger;
import au.edu.uts.eng.remotelabs.rigclient.util.LoggerFactory;

import gnu.io.CommPortIdentifier;

import java.io.*;
import java.util.Enumeration;


public class PendulumRigController implements IPrimitiveController {

	   /** Logger. **/
    private ILogger logger;


       /** HW simulator or interface **/
    PendulumHW prHW;
    PendulumPortMonitor prPM;

    
	@Override
    public boolean initController()
    {
        this.logger = LoggerFactory.getLoggerInstance();
        this.logger.info("Primitive Controller created");
        
        prHW = new PendulumHW("HWThread-1");
        prHW.start();
        this.logger.info("Primitive Controller started");
        
        prPM = new PendulumPortMonitor(prHW);
        prPM.start();
        this.logger.info("Primitive Controller Port Comm started");
		return true;
    }
    
   	public PrimitiveResponse getValsAction(PrimitiveRequest request) throws IOException
    {
            PrimitiveResponse response = new PrimitiveResponse();
            response.setSuccessful(true);
            
         
            response.addResult("Period",   			String.valueOf(prHW.getPeriod()));
            response.addResult("Length",   			String.valueOf(prHW.getLengthPV()));
            response.addResult("TargetLength",   	String.valueOf(prHW.getLengthSP()));
            response.addResult("ProcMode",   		String.valueOf(prHW.getProcMode()));
            response.addResult("ProcState",   		String.valueOf(prHW.getProcState()));
            response.addResult("Watchdog",   		String.valueOf(prHW.getWatchdog()));
            response.addResult("remoteLock",   		String.valueOf(prHW.getRemoteLock()));
            response.addResult("SensorFault",   	String.valueOf(prHW.getSensorFault()));		
            response.addResult("StepSP",   			String.valueOf(prHW.getStepSP()));		//for debugging
            for (int i = 0; i<=7;i++){
            	response.addResult("Recipe"+String.valueOf(i), 		String.valueOf(prHW.getRecipe(i)));	
            }
            response.addResult("CurrentRecipe",		String.valueOf(prHW.getCurrentRecipe()));	

            return response;
    }
	
	public PrimitiveResponse setLengthAction(PrimitiveRequest request) throws IOException
    {
            PrimitiveResponse response = new PrimitiveResponse();
            response.setSuccessful(true);

            int val = Integer.parseInt(request.getParameters().get("lengthSPtarget"));
            prHW.setLengthSPBuffer(val);
            return response;
    }
	public PrimitiveResponse setRecipeAction(PrimitiveRequest request) throws IOException
    {
            PrimitiveResponse response = new PrimitiveResponse();
            response.setSuccessful(true);
            
            int index = Integer.parseInt(request.getParameters().get("recipeTargetIndex"));
            int val = Integer.parseInt(request.getParameters().get("recipeTarget"));
            prHW.setRecipe(index,val);
            return response;
    }
	
	public PrimitiveResponse setCommandAction(PrimitiveRequest request) throws IOException
    {
            PrimitiveResponse response = new PrimitiveResponse();
            response.setSuccessful(true);

            int commandIndex = Integer.parseInt(request.getParameters().get("commandIndex"));
            prHW.setCommandBuffer(commandIndex);
            return response;
    }
	
	public PrimitiveResponse setRemoteLockAction(PrimitiveRequest request) throws IOException
    {
            PrimitiveResponse response = new PrimitiveResponse();
            response.setSuccessful(true);

            int remoteLockTgt = Integer.parseInt(request.getParameters().get("remoteLockTgt"));
            prHW.setRemoteLock(remoteLockTgt);
            return response;
    }
	
    @Override
    public boolean preRoute()
    {
    	return true;
    }
    
    @Override
    public boolean postRoute()
    {
    	
        return true;
    }

    @Override
    public void cleanup()
    {
    	// Notify Arduino to enter cleanup mode
        this.logger.info("Primitive Controller cleanup");
        prHW.setCommandBuffer(99);
        prHW.setCleanupFlag();
       
        // Wait until the command is properly accepted by Arduino 
        while (prHW.getProcMode()!= 0){
        	try{
        		this.logger.debug("Primitive Controller cleanup-waiting for feedback");
            	Thread.sleep(500);
        	}catch(Exception e){
        		this.logger.error("Primitive Controller cleanup - Error " + e.toString());
        	}
        }
        
        // destroy HW
        this.logger.info("Primitive Controller cleanup-command out, terminate");
        prHW.stopRunning();
        prPM.stopRunning();
    }
}
