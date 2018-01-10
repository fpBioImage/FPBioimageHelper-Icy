package plugins.fantm.fpbioimagehelper;

// Java system imports
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.regex.Pattern;

import javax.swing.JFileChooser;
import javax.swing.JOptionPane;

import org.jets3t.service.S3Service;
import org.jets3t.service.S3ServiceException;
import org.jets3t.service.ServiceException;
import org.jets3t.service.acl.AccessControlList;
import org.jets3t.service.acl.GroupGrantee;
import org.jets3t.service.acl.Permission;
import org.jets3t.service.model.S3Object;

// Java awt imaging imports
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import icy.file.FileUtil;
import icy.file.Saver;
import icy.gui.dialog.ConfirmDialog;
import icy.gui.dialog.MessageDialog;
import icy.gui.frame.progress.ProgressFrame;
import icy.gui.viewer.Viewer;
import icy.image.IcyBufferedImage;
import icy.image.IcyBufferedImageUtil;
import icy.image.lut.LUT;
import icy.math.Scaler;
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
	public static String bucketName = "fpbhost";
    EzVarSequence seqVar = new EzVarSequence("Sequence");
    
    EzVarDimensionPicker timeSlice = new EzVarDimensionPicker("Time point", DimensionId.T, seqVar);
    
    EzVarDouble voxelSizeXVar = new EzVarDouble("Voxel size x", 0, 0, 10000, 1);
    EzVarDouble voxelSizeYVar = new EzVarDouble("Voxel size y", 0, 0, 10000, 1);
    EzVarDouble voxelSizeZVar = new EzVarDouble("Voxel size z", 0, 0, 10000, 1);
    
    EzVarText uniqueNameVar = new EzVarText("Unique Name", "", 1);
        
    EzVarDouble scaleXVar = new EzVarDouble("X-scale", 1.0, 0, 100, 0.25);
    EzVarDouble scaleYVar = new EzVarDouble("Y-scale", 1.0, 0, 100, 0.25);
    EzVarDouble scaleZVar = new EzVarDouble("Z-scale", 1.0, 0, 100, 0.25);
    
    EzVarBoolean uploadToAWSVar = new EzVarBoolean("Upload to FPB Host?", false);
    
    
	@Override
	protected void initialize() {
		// Add variables to plugin box
		addEzComponent(seqVar);
		addEzComponent(timeSlice);
        addEzComponent(uniqueNameVar);
        final EzGroup voxelRatioGroup = new EzGroup("Voxel Ratio (before scaling)", voxelSizeXVar, voxelSizeYVar, voxelSizeZVar);
        addEzComponent(voxelRatioGroup);
        final EzGroup scaleGroup = new EzGroup("Scaling (<1 to reduce file size)", scaleXVar, scaleYVar, scaleZVar);
        addEzComponent(scaleGroup);
        addEzComponent(uploadToAWSVar);
		
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
		uniqueName = validateName(uniqueName);
		
        // Choose folder for saving
		String savepath = DirectoryChooser("fpsavepath", "Choose a folder to save webpage and image data"); // maybe this should actually be an html file, not a directory. 
		if (savepath == null) return;
        savepath = savepath + "/" + uniqueName;
        getPreferencesRoot().put("fpsavepath", FileUtil.getDirectory(savepath));
        
        // Save image as PNG stack
        Viewer view = seq.getFirstViewer();
        view.setCanvas("plugins.kernel.canvas.Canvas2DPlugin");
        
        // Check the shape
        if (seq.getSizeZ() == 1 && seq.getSizeT() > 1){
        	Boolean dlg = ConfirmDialog.confirm("Swap Z & T", "Image appears to only have 1 z-slice. Would you like to swap Z and T dimensions?");
        	if (dlg)
        	{
        		SequenceUtil.adjustZT(seq, seq.getSizeT(), seq.getSizeZ(), true);
        	}
        	
        }
        
        // Get time slice
        Sequence sliceArray = SequenceUtil.extractFrame(seq, timeSlice.getValue());
                
        // Check it exists
        if (sliceArray==null){
        	System.out.println("Selected frame does not exist for this sequence: using frame 0.");
        	sliceArray = SequenceUtil.extractFrame(seq, 0);
        }
        
        // Start progress bar here!
        ProgressFrame prog = new ProgressFrame("Converting colorspace...");
        prog.setLength(1.0);
        prog.setPosition(0.1);
        
        // Convert to the right color
        sliceArray = SequenceUtil.convertColor(sliceArray, BufferedImage.TYPE_INT_ARGB, seq.getFirstViewer().getLut());
        
        prog.setPosition(0.175);
        prog.setMessage("FP Helper: Scaling Image");
        
        // Scale image
        sliceArray = SequenceUtil.scale(sliceArray, (int) Math.round((double) sliceArray.getSizeX() * scaleXVar.getValue()), (int) Math.round((double) sliceArray.getSizeY() * scaleYVar.getValue()));
        	
        // z-Scaling is very slow. But we already checked user really wants to go ahead with this.
        if (scaleZVar.getValue() < 0.999){
        	sliceArray = scaleZ(sliceArray, scaleZVar.getValue());
        }
        
        prog.setPosition(0.25);
        prog.setMessage("FP Helper: Composing texture atlases...");
        
        // Now we have our stack of images (sliceArray), we need to order it into 8 texture atlases
        int sliceWidth = sliceArray.getSizeX(); int sliceHeight = sliceArray.getSizeY();
        int numberOfImages = sliceArray.getSizeZ();
        
        int atlasWidth; int atlasHeight;
        int numberOfAtlases = 8;
        
        int zPadding = 4;
        int paddedSliceDepth = numberOfImages + zPadding;
        
        int paddedSliceWidth = ceil2(sliceWidth);
        int paddedSliceHeight = ceil2(sliceHeight);
        
        int xOffset = (int)Math.floor((paddedSliceWidth - sliceWidth)/2);
        int yOffset = (int)Math.floor((paddedSliceHeight - sliceHeight)/2);
        
        int slicesPerAtlas = (int)Math.ceil((float)paddedSliceDepth/(float)numberOfAtlases);
        atlasWidth = ceil2(paddedSliceWidth);
        atlasHeight = ceil2(paddedSliceHeight * slicesPerAtlas);
        while ((atlasHeight > 2*atlasWidth) && (atlasHeight > sliceHeight)){
        	  atlasHeight /= 2;
        	  atlasWidth *= 2;
        }
        
        BufferedImage[] atlasArray = new BufferedImage[8];

        for (int i=0; i<numberOfAtlases; i++){
        	atlasArray[i] = new BufferedImage(atlasWidth, atlasHeight, BufferedImage.TYPE_INT_ARGB);
        }
        
        // Make LUT and set alpha properly
        LUT argbLUT = sliceArray.createCompatibleLUT();
        argbLUT.setAlphaToLinear(); 
        
        Scaler[] scalers = argbLUT.getScalers();
        
        double maxRightIn = 0;
        for (int i = 0; i<3; i++){
        	double thisRightIn = scalers[i].getRightIn();
        	if (thisRightIn > maxRightIn)
        		maxRightIn = thisRightIn;
        }
        
        scalers[3].setRightIn(maxRightIn);
        argbLUT.getLutChannel(3).setScaler(scalers[3]);
        
        // Put each slice into its correct place in the atlas
        int slicesPerRow = (int)Math.floor((float)atlasWidth/(float)paddedSliceWidth);
        for (int i=0; i<numberOfImages; i++){
        	int j = i + (int)Math.floor((float)zPadding/2.0);
        	int atlasNumber = (int)((float)j % (float)numberOfAtlases);
        	int locationIndex = (int)Math.floor((float)j/(float)numberOfAtlases);
        	
        	BufferedImage sliceTexture = IcyBufferedImageUtil.getARGBImage(sliceArray.getImage(0, i), argbLUT);
        	
        	int xStartPixel = (int)((float)locationIndex % (float)slicesPerRow) * paddedSliceWidth + xOffset;
        	int yStartPixel = (int)Math.floor((float)locationIndex / (float)slicesPerRow) * paddedSliceHeight;
        	yStartPixel = atlasHeight - yStartPixel - paddedSliceHeight + yOffset;
        	
        	copySubImage(sliceTexture, atlasArray[atlasNumber], xStartPixel, yStartPixel);
        }
                
        Sequence atlasSequence = new Sequence("Atlas Array");
        for (int i=0; i<numberOfAtlases; i++){
        	atlasSequence.addImage(atlasArray[i]);
        }
        
        // Save the atlases
        prog.setMessage("FP Helper: Saving images...");
        prog.setPosition(1.0/2.0);
        String imageFilename = savepath + "/" + uniqueName + ".png";
        // Save the images! 
        Saver.save(atlasSequence, new File(imageFilename), true, true);
        
        
        // And now just make the webpage! 
        String pathTohtmlFile = "/templateWebpage.html";
        int numLines = 56; // TODO: Not great practice to hard-code this! 
        String[] webpageAsString = new String[numLines];
        
        try {
			webpageAsString = readFileToString(pathTohtmlFile, numLines);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
        
        // Get canonical filenames for relative paths
        try {
        	savepath = new File(savepath).getCanonicalPath();
    	} catch (IOException e2) {
			e2.printStackTrace();
		}
              
        String relativePathToImages = "."; // Since they're in the same folder now.
        
        for (int i = 0; i<numLines; i++){
        	webpageAsString[i] = webpageAsString[i].replace("templateTitle", uniqueName + " - FPBioimage Viewer");
        	webpageAsString[i] = webpageAsString[i].replace("templateImagePath", relativePathToImages);
        	webpageAsString[i] = webpageAsString[i].replace("templateUniqueName", uniqueName);
        	webpageAsString[i] = webpageAsString[i].replace("templateNumberOfImages", Integer.toString(numberOfImages));
        	webpageAsString[i] = webpageAsString[i].replace("templateImagePrefix", uniqueName + "_z");
        	webpageAsString[i] = webpageAsString[i].replace("templateNumberingFormat", "0000");
        	webpageAsString[i] = webpageAsString[i].replace("templateVoxelX", Double.toString((voxelSizeXVar.getValue()/scaleXVar.getValue())));
        	webpageAsString[i] = webpageAsString[i].replace("templateVoxelY", Double.toString((voxelSizeYVar.getValue()/scaleYVar.getValue())));
        	webpageAsString[i] = webpageAsString[i].replace("templateVoxelZ", Double.toString((voxelSizeZVar.getValue()/scaleZVar.getValue())));
        	webpageAsString[i] = webpageAsString[i].replace("templateSliceWidth", Integer.toString(sliceWidth));
        	webpageAsString[i] = webpageAsString[i].replace("templateSliceHeight", Integer.toString(sliceHeight));
        }
		
        String htmlSavePath =  savepath + "/index.html";
        
        // Finally, write the updated webpage to the save location
        try {
			writeStringToFile(htmlSavePath, webpageAsString);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
        
        if (uploadToAWSVar.getValue()){
        	prog.setPosition(3.0/4.0);
            prog.setMessage("FP Helper: Uploading to FP Host...");
            
        	// Start up S3Service
        	S3Service s3Service = Bucket.getS3Service();
        	
            // Check that files don't already exist
            String keyPrefix = uniqueName;
            boolean confirmUpload = true;
            boolean fileAlreadyExists = true;
            
            try {
				fileAlreadyExists = s3Service.isObjectInBucket(bucketName, keyPrefix + "/index.html");
			} catch (ServiceException e2) {
				e2.printStackTrace();
			}
            
            while (fileAlreadyExists){
            	// Check when file was uploaded
            	S3Object existingObject = null;
				try {
					existingObject = s3Service.getObject(bucketName, keyPrefix + "/index.html");
				} catch (S3ServiceException e) {
					e.printStackTrace();
				}
            	Date lastModified = existingObject.getLastModifiedDate();
            	
            	Instant then = lastModified.toInstant();
            	Instant now = Instant.now();
            	Instant twentyFourHoursAgo = now.minus(24, ChronoUnit.HOURS);
            	Boolean within24Hours = ( ! then.isBefore( twentyFourHoursAgo ) ) &&  then.isBefore( now ) ;

            	if (within24Hours){
            		// Ask if we want to overwrite, otherwise rename
            		String msgStr = "File already exists, but is less than 24 hours old. Do you want to overwrite? (Press No to rename then upload.)";
            		//int overwrite = ConfirmDialog.confirmEx("File exists!", msgStr, ConfirmDialog.YES_NO_CANCEL_OPTION);
            		int overwrite = JOptionPane.showConfirmDialog(null, msgStr, "File exists!", JOptionPane.YES_NO_CANCEL_OPTION);
            		if (overwrite == 2){
            			confirmUpload = false; fileAlreadyExists = false; // To get out the loop
            		} else if (overwrite == 0){
            			// User wants to overwrite 
            			fileAlreadyExists = false; // Just to get out the loop
            		} else if (overwrite == 1){
            			String newPrefix = JOptionPane.showInputDialog("New unique name:");
            			if (newPrefix != null){
            				// Check if this new name exists
            				keyPrefix = validateName(newPrefix);
            				try {
            					fileAlreadyExists = s3Service.isObjectInBucket(bucketName, keyPrefix + "/index.html");
            				} catch (ServiceException e2) {
            					e2.printStackTrace();
            				}
            			} else {
            				// User cancelled
            				confirmUpload = false; // Won't upload anything
            				fileAlreadyExists = false; // To get out the loop
            			}
            		}
            		
            	} else {
            		// Can't overwrite, sorry. You can rename?
            		String newPrefix = JOptionPane.showInputDialog("File already exists, and is over 24 hours old so can't be overwritten. Either rename, or cancel:");
            		if (newPrefix != null){
        				keyPrefix = validateName(newPrefix);
        	            try {
        					fileAlreadyExists = s3Service.isObjectInBucket(bucketName, keyPrefix + "/index.html");
        				} catch (ServiceException e2) {
        					e2.printStackTrace();
        				}
        			} else {
        				// User cancelled
        				confirmUpload = false; // Don't upload anything
        				fileAlreadyExists = false; // To get out the loop
        			}
            	}

            }

            if (confirmUpload){
            	// Set up list of all files to upload
	            String[] filelist = new String[9];
	            String[] keylist = new String[9];         
	            
	            filelist[8] = htmlSavePath;
	            keylist[8] = keyPrefix + "/index.html";
	            
	            for (int i=0; i<8; i++){
	            	filelist[i] = savepath + "/" + uniqueName + "_z" + String.format("%04d", i) + ".png";
	            	keylist[i] = keyPrefix + "/" + uniqueName + "_z" + String.format("%04d", i) + ".png";
	            }
	            
	            // Upload files
	            for (int i=0; i<filelist.length; i++){
	            	File file = new File(filelist[i]);
					try {
						S3Object uploadThis = new S3Object(file);
	            		uploadThis.setKey(keylist[i]);
	            		uploadThis.addMetadata("Content-Type", "text/html");
	            		uploadThis.setAcl(AccessControlList.REST_CANNED_PUBLIC_READ);
	            		s3Service.putObject(bucketName, uploadThis);
					} catch (NoSuchAlgorithmException | IOException e) {
						e.printStackTrace();
					} catch (S3ServiceException e) {
						e.printStackTrace();
					}
            		
	            }
	            
	            int showWebDlg = JOptionPane.showConfirmDialog(null, "Would you like to view the webpage now?", "Upload complete!", JOptionPane.YES_NO_OPTION);
	            Boolean showWeb = showWebDlg == 1 ? true : false;
	            // Show webpage in default browser
	            if (showWeb){ 
	            	try {
						java.awt.Desktop.getDesktop().browse(new URI("http://s3.amazonaws.com/fpbhost/" + keyPrefix + "/index.html"));
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					} catch (URISyntaxException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
	            }
            } else {
            	JOptionPane.showConfirmDialog(null,	"Data saved locally to " + htmlSavePath, "Complete!", JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE);
            } // End of confirmUpload if
        } else {
        	JOptionPane.showConfirmDialog(null,"Data saved locally to " + htmlSavePath, "Complete!", JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE);
        } // End of doUpload if
        
        prog.close();
	} // End of FpBioimageHelper class
	
	@Override
	public void clean() {
		// TODO Auto-generated by Icy4Eclipse
	}
	
	private String validateName(String inputName){
		Pattern special = Pattern.compile ("[!@#£$%&*()+=|<>?{}\\[\\]~.,\\s]");
		boolean hasSpecial = special.matcher(inputName).find();
		if (inputName.length() < 4) 
			{hasSpecial = true;}
		
		if (!hasSpecial){
			return inputName;
		} else {
			String newName = null;
			while (hasSpecial){
        		newName = JOptionPane.showInputDialog("Unique name can't contain spaces or special characters, with minimum length 3. Please choose a valid unique name:");
				if (newName==null || newName.length()<4 || newName == ""){
					hasSpecial = true;
				} else {
					hasSpecial = special.matcher(newName).find();
				}
			}
			return newName;
		}
		
	}
	
    static public String ExportResource(String resourceName, String outputName) throws Exception {
        // Copy a file from inside the jar to outside
    	InputStream stream = null;
        OutputStream resStreamOut = null;

        try {
            stream = FpBioimageHelper.class.getResourceAsStream(resourceName);//note that each / is a directory down in the "jar tree" been the jar the root of the tree
            if(stream == null) {
            	System.out.println("Can't find file " + resourceName);
                throw new Exception("Cannot get resource \"" + resourceName + "\" from Jar file.");
            }

            int readBytes;
            byte[] buffer = new byte[4096];
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
    
    private static void copySubImage(final BufferedImage src,
            final BufferedImage dst, final int dx, final int dy) {
        int[] srcbuf = ((DataBufferInt) src.getRaster().getDataBuffer()).getData();
        int[] dstbuf = ((DataBufferInt) dst.getRaster().getDataBuffer()).getData();
        int width = src.getWidth();
        int height = src.getHeight();
        int dstoffs = dx + dy * dst.getWidth();
        int srcoffs = 0;
        for (int y = 0 ; y < height ; y++ , dstoffs+= dst.getWidth(), srcoffs += width ) {
            System.arraycopy(srcbuf, srcoffs , dstbuf, dstoffs, width);
        }
    }
	
    public String DirectoryChooser(String icyprefname, String dialogTitle){
        // Icy seems to have no built-in directory chooser.
    	// Use this to choose a directory to save image data to
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
    
    public int ceil2(int x){
    	// Round an int up to the next power of 2
    	double log = Math.log(x) / Math.log(2);
    	double roundLog = Math.ceil(log);
    	int powerOfTwo = (int)Math.pow(2, roundLog);
    	return powerOfTwo;
    }
    
    public String[] readFileToString(String pathToFile, int numLines) throws IOException {
    	//Reads an input file to a string array   	
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
    	// Simply writes string to specified filename
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
    	//This isn't the best thing to use, it's very slow...
    	
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