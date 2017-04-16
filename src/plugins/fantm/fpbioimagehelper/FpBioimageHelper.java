package plugins.fantm.fpbioimagehelper;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.swing.JFileChooser;

import org.apache.commons.lang.SystemUtils;

import icy.file.FileUtil;
import icy.file.Saver;
import icy.gui.dialog.ConfirmDialog;
import icy.gui.dialog.MessageDialog;
import icy.image.IcyBufferedImage;
import icy.sequence.DimensionId;
import icy.sequence.Sequence;
import icy.sequence.SequenceUtil;
import plugins.adufour.ezplug.EzGroup;
import plugins.adufour.ezplug.EzPlug;
import plugins.adufour.ezplug.EzVar;
import plugins.adufour.ezplug.EzVarBoolean;
import plugins.adufour.ezplug.EzVarDimensionPicker;
import plugins.adufour.ezplug.EzVarDouble;
import plugins.adufour.ezplug.EzVarListener;
import plugins.adufour.ezplug.EzVarSequence;
import plugins.adufour.ezplug.EzVarText;

public class FpBioimageHelper extends EzPlug {

    // Variables
    EzVarSequence seqVar = new EzVarSequence("Sequence");
    
    EzVarDimensionPicker timeSlice = new EzVarDimensionPicker("Time point", DimensionId.T, seqVar);
    
    EzVarDouble voxelSizeXVar = new EzVarDouble("Voxel size x", 0, 0, 10000, 1);
    EzVarDouble voxelSizeYVar = new EzVarDouble("Voxel size y", 0, 0, 10000, 1);
    EzVarDouble voxelSizeZVar = new EzVarDouble("Voxel size z", 0, 0, 10000, 1);
    
    EzVarText uniqueNameVar = new EzVarText("Unique Name", "", 1);
    
    EzVarBoolean installedVar = new EzVarBoolean("FPBioimage already on server?", true);
    
    EzVarDouble scaleXVar = new EzVarDouble("X-scale", 1.0, 0, 100, 0.25);
    EzVarDouble scaleYVar = new EzVarDouble("Y-scale", 1.0, 0, 100, 0.25);
    EzVarDouble scaleZVar = new EzVarDouble("Z-scale", 1.0, 0, 100, 0.25);
    
    
	@Override
	protected void initialize() {
		// Add variables to plugin box
		addEzComponent(seqVar);
		addEzComponent(timeSlice);
        addEzComponent(uniqueNameVar);
        final EzGroup voxelRatioGroup = new EzGroup("Voxel Ratio (before scaling)", voxelSizeXVar, voxelSizeYVar, voxelSizeZVar);
        addEzComponent(voxelRatioGroup);
        final EzGroup scaleGroup = new EzGroup("Scale", scaleXVar, scaleYVar, scaleZVar);
        addEzComponent(scaleGroup);
        addEzComponent(installedVar);
		
        // Set pixel size and unique name from image
        Sequence seq = seqVar.getValue();
        if (seq != null){
	        voxelSizeXVar.setValue(seq.getPixelSizeX()); // sets pixel size in um
	        voxelSizeYVar.setValue(seq.getPixelSizeY());
	        voxelSizeZVar.setValue(seq.getPixelSizeZ());
	        uniqueNameVar.setValue(seq.getName());
	        if(seq.getSizeX()>500 || seq.getSizeY()>500){
	        	double scale = Math.min(400.0/(double) seq.getSizeX(), 400.0/(double) seq.getSizeY());
	        	scaleXVar.setValue(scale);
	        	scaleYVar.setValue(scale);
	        }
	        if(seq.getSizeZ()>500){
	        	double zScale = 400.0/(double)seq.getSizeZ();
	        	scaleZVar.setValue(zScale);
	        }
        }
        
        // And make sure it updates every time the sequence is changed:
        seqVar.addVarChangeListener(new EzVarListener<Sequence>(){
			@Override
			public void variableChanged(EzVar<Sequence> source, Sequence newSeq) {
				if (newSeq != null) {
			        voxelSizeXVar.setValue(newSeq.getPixelSizeX());
			        voxelSizeYVar.setValue(newSeq.getPixelSizeY());
			        voxelSizeZVar.setValue(newSeq.getPixelSizeZ());
			        uniqueNameVar.setValue(newSeq.getName());
			        if(newSeq.getSizeX()>500 || newSeq.getSizeY()>500){
			        	double scale = Math.min(400.0/(double) newSeq.getSizeX(), 400.0/(double) newSeq.getSizeY());
			        	scaleXVar.setValue(scale);
			        	scaleYVar.setValue(scale);
			        }else{
			        	scaleXVar.setValue(1.0);
			        	scaleYVar.setValue(1.0);
			        }
			        if(newSeq.getSizeZ()>500){
			        	double zScale = 400.0/(double)newSeq.getSizeZ();
			        	scaleZVar.setValue(zScale);
			        }else{
			        	scaleZVar.setValue(1.0);
			        }
				} else {
			        voxelSizeXVar.setValue(0.0);
			        voxelSizeYVar.setValue(0.0);
			        voxelSizeZVar.setValue(0.0);
			        uniqueNameVar.setValue("");
		        	scaleXVar.setValue(1.0);
		        	scaleYVar.setValue(1.0);
		        	scaleZVar.setValue(1.0);
				}
			}        	
        });
        
	}

	@Override
	protected void execute() {
		// Check user wants to go ahead with z-scaling
        if (scaleZVar.getValue() < 0.999){
        	Boolean dlg = ConfirmDialog.confirm("Warning", "Note that the z-scaling algorithm built-in to this helper is slow. I suggest you use another tool to scale in z. Continue anyway?");
        	if (!dlg){
        		return;
        	}
        }
		
		// Resolve all EZVars into variables
		Sequence seq = seqVar.getValue();
		if (seq == null) return;
		
		if (seq.getSizeX() * scaleXVar.getValue() > 500){
			MessageDialog.showDialog("Maximum X, Y or Z size after scaling is 500. Please check X dimension.", MessageDialog.ERROR_MESSAGE);
			return;
		}
		
		if (seq.getSizeY() * scaleYVar.getValue() > 500){
			MessageDialog.showDialog("Maximum X, Y or Z size after scaling is 500. Please check Y dimension.", MessageDialog.ERROR_MESSAGE);
			return;
		}
		
		if (seq.getSizeZ() * scaleZVar.getValue() > 500){
			MessageDialog.showDialog("Maximum X, Y or Z size after scaling is 500. Please check Z dimension.", MessageDialog.ERROR_MESSAGE);
			return;
		}
		
		String uniqueName = uniqueNameVar.getValue();
		
        // Choose folder for saving
		String savepath = DirectoryChooser("fpsavepath", "Choose a folder for the webpage and image data"); // maybe this should actually be an html file, not a directory. 
		if (savepath == null) return;
        
        getPreferencesRoot().put("fpsavepath", FileUtil.getDirectory(savepath));
        
        // Choose location of current FPBioimage installation
		String fppath;
        if (installedVar.getValue()){
            fppath = DirectoryChooser("fpinstallpath", "Please select your current FPBioimage installation folder");
            if (fppath == null) return;
            
        } else {
        	// Install FPBioimage
        	fppath = savepath + "/../FPBioimage/";
        	
        	try{
        		boolean success = new File(fppath).mkdir();
        	
        		if (!success){
        			throw new Exception("Could not create a directory at " + fppath);
        		}
        	}catch (Exception ex){
        		ex.printStackTrace();
        	}
        	
        	String installFromPath = "/plugins/fantm/fpbioimagehelper/FPBioimage/";
        	String[] installFromNames = new String[9];
        	installFromNames[0] = "FPBioimage.datagz";
        	installFromNames[1] = "FPBioimage.jsgz";
        	installFromNames[2] = "FPBioimage.memgz";
        	installFromNames[3] = "FPBioimageLoader.js";
        	installFromNames[4] = "fullbar.png";
        	installFromNames[5] = "loadingbar.png";
        	installFromNames[6] = "Progress.js";
        	installFromNames[7] = "progresslogo.png";
        	installFromNames[8] = "UnityLoader.js";
        	
        	for (int i=0; i<installFromNames.length; i++){
        		try {
					ExportResource(installFromPath + installFromNames[i], fppath + installFromNames[i]);
				} catch (Exception e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
        	}
        	
        }
        
        // Save image as PNG stack
        
        // Check the shape
        if (seq.getSizeZ() == 1 && seq.getSizeT() > 1){
        	Boolean dlg = ConfirmDialog.confirm("Swap Z & T", "Image appears to only have 1 z-slice. Would you like to swap Z and T dimensions?");
        	if (dlg)
        	{
        		SequenceUtil.adjustZT(seq, seq.getSizeT(), seq.getSizeZ(), true);
        	}
        	
        }
        
        // Use the right format for PNG
        seq = SequenceUtil.convertColor(seq,  BufferedImage.TYPE_INT_ARGB, seq.getFirstViewer().getLut());  
        
        Sequence saveMe = SequenceUtil.extractFrame(seq, timeSlice.getValue());
        //saveMe = SequenceUtil.convertToType(saveMe, DataType.UBYTE, true);
        
        // Check it exists
        if (saveMe==null){
        	System.out.println("Selected frame does not exist for this sequence: using frame 0.");
        	saveMe = SequenceUtil.extractFrame(seq, 0);
        }
        
        saveMe = SequenceUtil.scale(saveMe, (int) Math.round((double) saveMe.getSizeX() * scaleXVar.getValue()), (int) Math.round((double) saveMe.getSizeY() * scaleYVar.getValue()));
        
        // z-Scaling is very slow. But we already checked user really wants to go ahead with this.
        if (scaleZVar.getValue() < 0.999){
        	saveMe = scaleZ(saveMe, scaleZVar.getValue());
        }
        
        String imageFilename = savepath + "/" + uniqueName + "-images/" + uniqueName + ".png";
        
        Saver.save(saveMe, new File(imageFilename), true, true);
        
        // And now just make the webpage! 
        String pathTohtmlFile = "/templateWebpage.html";
        int numLines = 60;
        String[] webpageAsString = new String[numLines];
        
        try {
			webpageAsString = readFileToString(pathTohtmlFile, numLines);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
        
        // Get canonical filenames for relative paths
        try {
        	fppath = new File(fppath).getCanonicalPath();
        	savepath = new File(savepath).getCanonicalPath();
    	} catch (IOException e2) {
			e2.printStackTrace();
		}
        
        Path savepathPath = Paths.get(savepath);
        Path imageSavepathPath = Paths.get(savepath + "/" + uniqueName + "-images");
        Path fppathPath = Paths.get(fppath);
        
        String relativePathToFPBioimage = savepathPath.relativize(fppathPath).toString().replace('\\', '/');
        String relativePathToImages = savepathPath.relativize(imageSavepathPath).toString().replace('\\', '/');
        
        for (int i = 0; i<numLines; i++){
        	webpageAsString[i] = webpageAsString[i].replace("templateTitle", uniqueName + " - FPBioimage Viewer");
        	webpageAsString[i] = webpageAsString[i].replace("templateImagePath", relativePathToImages);
        	webpageAsString[i] = webpageAsString[i].replace("templateUniqueName", uniqueName);
        	webpageAsString[i] = webpageAsString[i].replace("templateNumberOfImages", Integer.toString(saveMe.getSizeZ()));
        	webpageAsString[i] = webpageAsString[i].replace("templateImagePrefix", uniqueName + "_z");
        	webpageAsString[i] = webpageAsString[i].replace("templateNumberingFormat", "0000");
        	webpageAsString[i] = webpageAsString[i].replace("templateVoxelX", Double.toString((voxelSizeXVar.getValue()/scaleXVar.getValue())));
        	webpageAsString[i] = webpageAsString[i].replace("templateVoxelY", Double.toString((voxelSizeYVar.getValue()/scaleYVar.getValue())));
        	webpageAsString[i] = webpageAsString[i].replace("templateVoxelZ", Double.toString((voxelSizeZVar.getValue()/scaleZVar.getValue())));
        	webpageAsString[i] = webpageAsString[i].replace("templatePathToFPBioimage", relativePathToFPBioimage);
        }
		
        String htmlSavePath =  savepath + "/" + uniqueName + ".html";
        
        // Finally, write the updated webpage to the save location
        try {
			writeStringToFile(htmlSavePath, webpageAsString);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
        
        
        // Do you want to open the webpage now?
        
    	Boolean dlg = ConfirmDialog.confirm("Complete!", "Would you like to view the webpage now?", ConfirmDialog.YES_NO_OPTION);
    	if (dlg){
    		if (SystemUtils.IS_OS_WINDOWS){
    			String runMe = "\"C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe\" --allow-file-access-from-files \"" + htmlSavePath + "\"";
    			Runtime rt = Runtime.getRuntime();
    			try {
					Process pr = rt.exec(runMe);
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
    		} else if (SystemUtils.IS_OS_MAC){
    			String runMe = "open \"/Applications/Google Chrome.app\" --allow-file-access-from-files \"" + htmlSavePath + "\"";
    			Runtime rt = Runtime.getRuntime();
    			try {
					Process pr = rt.exec(runMe); // should check return value
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
    		}
    		
    	}
        
	}
	
	@Override
	public void clean() {
		// TODO Auto-generated by Icy4Eclipse
	}
	
	
	  /**
     * Export a resource embedded into a Jar file to the local file path.
     *
     * @param resourceName ie.: "/SmartLibrary.dll"
     * @return The path to the exported resource
     * @throws Exception
     */
    static public String ExportResource(String resourceName, String outputName) throws Exception {
        InputStream stream = null;
        OutputStream resStreamOut = null;
        //String jarFolder;
        try {
            stream = FpBioimageHelper.class.getResourceAsStream(resourceName);//note that each / is a directory down in the "jar tree" been the jar the root of the tree
            if(stream == null) {
            	System.out.println("Can't find file " + resourceName);
                throw new Exception("Cannot get resource \"" + resourceName + "\" from Jar file.");
            }

            int readBytes;
            byte[] buffer = new byte[4096];
            //jarFolder = new File(FpBioimageHelper.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath()).getParentFile().getPath().replace('\\', '/');
            resStreamOut = new FileOutputStream(outputName);
            while ((readBytes = stream.read(buffer)) > 0) {
                resStreamOut.write(buffer, 0, readBytes);
            }
        } catch (Exception ex) {
            throw ex;
        } finally {
        	if (stream != null) stream.close();
            resStreamOut.close();
        }

        return outputName;
    }
	
    public String DirectoryChooser(String icyprefname, String dialogTitle){
        String defaultPath = getPreferencesRoot().get(icyprefname, null);
        JFileChooser chooser = new JFileChooser();
        if (defaultPath != null){
        	chooser.setCurrentDirectory(new java.io.File(defaultPath));
        }
		chooser.setDialogTitle(dialogTitle);
		chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
		int chooserReturn = chooser.showSaveDialog(null);
		
		if (chooserReturn == JFileChooser.APPROVE_OPTION){
			String path = chooser.getSelectedFile().toString();
			getPreferencesRoot().put(icyprefname, FileUtil.getDirectory(path));
			return path;
		} else {
			return null;
		}
    }
    
    public String DirectoryChooser(String icyprefname){
    	return DirectoryChooser(icyprefname, "Chooser folder...");
    }
    
    public String[] readFileToString(String pathToFile, int numLines) throws IOException {
    	//FileReader fr = new FileReader(pathToFile);
    	//BufferedReader textReader = new BufferedReader(fr);
    	
    	InputStream fr = getClass().getResourceAsStream(pathToFile);
    	BufferedReader textReader = new BufferedReader(new InputStreamReader(fr));
        	
    	String[] textData = new String[numLines];
    	
    	for(int i = 0; i<numLines; i++){
    		textData[i] = textReader.readLine();
    	}
    	
    	textReader.close();
    	return textData;
    }
    
    public static void writeStringToFile(String filename, String[] stringToWrite) throws IOException{
    	BufferedWriter outputWriter = null;
    	outputWriter = new BufferedWriter(new FileWriter(filename));
    	for (int i=0; i<stringToWrite.length; i++){
    		outputWriter.write(stringToWrite[i]);
    		outputWriter.newLine();
    	}
    	outputWriter.flush();
    	outputWriter.close();
    }
    
    
    public Sequence scaleZ(Sequence seq, double scaleFactor){
    	//Sequence output = null;
    	
    	// Get depths to work out interpolation factors
    	int oldDepth = seq.getSizeZ();
    	
    	// ideal new z size 
    	double idealNewDepth = (double)oldDepth * scaleFactor; 
    	
    	Sequence output = new Sequence();
    	
    	double mixA = idealNewDepth / oldDepth; // should I be using actual newDepth here?
    	double mixB = 1 - mixA;
    	  	
    	int newDepth = (int) Math.ceil(idealNewDepth);
    	
    	// Not sure if there's a nice way in Java to do all pixels at once?
    	
    	for (int newZCount=0; newZCount<newDepth; newZCount++){
    		IcyBufferedImage newSlice = new IcyBufferedImage(seq.getSizeX(), seq.getSizeY(), seq.getSizeC(), seq.getDataType_());
    		
    		int oldZSlice = (int)Math.floor(newZCount/scaleFactor);
			 
			 if (oldZSlice +1 >= seq.getSizeZ()){
				 oldZSlice = seq.getSizeZ() - 2;
			 }
    		
			 // Loop in a loop in a loop: no wonder it's so slow. 
	    	for (int x=0; x<seq.getSizeX(); x++){
	    		for (int y=0; y<seq.getSizeY(); y++){
    				 byte[] oldZa = seq.getDataCopyCAsByte(timeSlice.getValue(), oldZSlice, x, y);
    				 byte[] oldZb = seq.getDataCopyCAsByte(timeSlice.getValue(), oldZSlice + 1, x, y); // Could this overflow? Not anymore.
    				 
    				 for (int c = 0; c<oldZa.length; c++){
    					 // finally got to loop over color
    					 double newZ = mixA * oldZa[c] + mixB * oldZb[c]; 
    					 newSlice.setDataAsByte(x, y, c, (byte)newZ);
    				 }
    				     				 
    			}
    		}
	    	
	    	output.addImage(newSlice);
    	}
    	
    	return output; 
    }
    
}
