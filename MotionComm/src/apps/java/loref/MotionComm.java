/**
 * Copyright 2018 Lorenzo Failla
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 * 
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 * 
 * 3. Neither the name of the copyright holder nor the names of its contributors
 * may be used to endorse or promote products derived from this software without
 * specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package apps.java.loref;

import static apps.java.loref.GeneralUtilitiesLibrary.parseHttpRequest;
import static apps.java.loref.GeneralUtilitiesLibrary.parseShellCommand;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.URL;
import java.util.HashMap;
import java.util.Timer;
import java.util.TimerTask;

import javax.imageio.ImageIO;

import com.sun.media.vfw.BitMapInfo;

import net.sf.jipcam.axis.MjpegFrame;
import net.sf.jipcam.axis.MjpegInputStream;

import java.io.InputStream;

public class MotionComm {

    /* constants */
    private final static int DATA_SIZE_THRESHOLD = 2048;
    private final static long DATA_SIZE_THRESHOLD_WAIT_TIME_MS = 500L;

    /* subclasses */
    private class StopMotionEmulation extends TimerTask {

	private String cameraID;

	StopMotionEmulation(String cameraID) {
	    this.cameraID = cameraID;
	}

	@Override
	public void run() {

	    setParameter(cameraID, "emulate_motion", "off");
	    this.cancel();

	}

    }

    private class CameraDataStreamer {

	/*
	 * This class manage the data stream for a camera
	 */

	private final static String CLASS_NAME = "CameraDataStreamer";
	private String cameraID;
	private boolean running = false;

	public InputStream getInputStream(String userName) {

	    return in.get(userName);

	}

	/* process */
	private HashMap<String, PipedInputStream> in = new HashMap<String, PipedInputStream>();
	private HashMap<String, PipedOutputStream> out = new HashMap<String, PipedOutputStream>();
	private HashMap<String, BufferedOutputStream> bufOut = new HashMap<String, BufferedOutputStream>();
	private String port;
	private HashMap<String, String> users = new HashMap<String, String>();

	/* subclasses */

	private Thread streamingThread = new Thread() {

	    public void run() {

		if (debugMode) {
		    System.out.println("Thread della lettura dei dati in stream avviato.");
		}

		InputStream stream; // apre lo stream

		try {

		    String streamURL = String.format("http://%s:%s", host, port);

		    if (debugMode) {
			System.out.println("CameraDataStreamer - Apertura dello stream \"" + streamURL + "\" in corso...");
		    }

		    stream = new URL(streamURL).openStream();

		    if (debugMode) {
			System.out.println("CameraDataStreamer - Stream \"" + streamURL + "\" aperto");
		    }

		    if (debugMode) {
			System.out.println(running);
		    }
		    while (running) {

			// determina la dimensione dei dati disponibili
			int dataSize = stream.available();

			if (debugMode) {
			    System.out.println("CameraDataStreamer - In attesa di raggiungimento del  buffer (" + dataSize + ")");
			}

			// se la dimensione supera la soglia
			if (dataSize > DATA_SIZE_THRESHOLD) {

			    // acquisisce la porzione di stream in un nuovo
			    // array di byte
			    byte[] newData = new byte[dataSize];
			    stream.read(newData, 0, dataSize);

			    bufOut.forEach((k, v) -> {
				try {
				    v.write(newData);
				    v.flush();
				} catch (IOException e) {
				    if (debugMode)
					printDebugErrorMessage(CLASS_NAME, e);
				}

			    });

			    if (debugMode)
				printDebugMessage("CameraDataStreamer", "write ok");

			    if (debugMode) {
				System.out.println("CameraDataStreamer - Buffer raggiunto. " + dataSize + " bytes inviati allo stream.");
			    }

			} else {

			    try {
				Thread.sleep(DATA_SIZE_THRESHOLD_WAIT_TIME_MS);
			    } catch (InterruptedException e) {
			    }

			}

		    }

		    if (debugMode) {
			System.out.println("CameraDataStreamer - Chiusura stream...");
		    }

		    // chiude lo stream
		    stream.close();

		    if (debugMode) {
			System.out.println("CameraDataStreamer - Stream Closed");
		    }

		} catch (IOException e) {

		    if (debugMode) {
			System.out.println("CameraDataStreamer - IOException: " + e.getMessage());
		    }

		}

	    }

	};

	/* constructors */

	public CameraDataStreamer(String cameraID) {
	    this.cameraID = cameraID;
	    port = getStreamPort(cameraID);
	}

	private void startStreaming() {

	    streamingThread.start();
	    running = true;

	    if (debugMode) {
		System.out.println("Streaming dei dati avviato");
	    }

	}

	private void stopStreaming() {

	    running = false;

	}

	private void addUser(String userID) {

	    PipedInputStream inStream = new PipedInputStream();
	    PipedOutputStream outStream = new PipedOutputStream();
	    BufferedOutputStream bufOutStream = new BufferedOutputStream(outStream);

	    try {

		inStream.connect(outStream);
		in.put(userID, inStream);
		out.put(userID, outStream);
		bufOut.put(userID, bufOutStream);

		users.put(userID, "" + System.currentTimeMillis());

		if (debugMode)
		    printDebugMessage(CLASS_NAME, String.format("User \"%s\" added, piped stream connected", userID));

	    } catch (IOException e) {

		if (debugMode)
		    printDebugErrorMessage(CLASS_NAME, e);

	    }

	}

	private void removeUser(String userID) {

	    users.remove(userID);

	    try {
		in.get(userID).close();
		out.get(userID).close();
		bufOut.get(userID).close();

	    } catch (IOException e) {

		if (debugMode)
		    printDebugErrorMessage(CLASS_NAME, e);
	    }

	    in.remove(userID);
	    out.remove(userID);
	    bufOut.remove(userID);

	    if (debugMode) {
		System.out.println("CameraDataStreamer - Utilizzatore \"" + userID + "\" rimosso");
	    }

	    if (users.size() < 1) {

		stopStreaming();
		camerasDataStreamers.remove(cameraID);

	    }

	}

    }

    private class FrameCapturer {

	/*
	 * this class manages the frame capture from a camera, using the
	 * CameraDataStream class for input data
	 */

	/* getters and setters */
	
	private static final int DEFAULT_FRAME_WIDTH=640;
	private static final int DEFAULT_FRAME_HEIGHT=480;

	private CameraDataStreamer cameraDataStreamer;
	private String cameraID;
	private String destination;
	private int framesToBeCaptured = 0;
	private int capturedFrames = 0;
	
	private int frameWidth;
	private int frameHeight;

	private String userID;

	/* 
	 * getters and setters
	 */
	public void setFramesToBeCaptured(int value) {
	    this.framesToBeCaptured = value;
	}

	/* constructors */

	public FrameCapturer(String cameraID, CameraDataStreamer cameraDataStreamer, String destination) {

	    this.cameraID = cameraID;
	    this.cameraDataStreamer = cameraDataStreamer;
	    this.destination=destination;
	    this.frameWidth=DEFAULT_FRAME_WIDTH;
	    this.frameHeight=DEFAULT_FRAME_HEIGHT;

	    this.userID = cameraID + "_" + destination + "-" + "FrameCapturer";

	}
	
	public FrameCapturer(String cameraID, CameraDataStreamer cameraDataStreamer, String destination, int frameWidth, int frameHeight) {

	    this.cameraID = cameraID;
	    this.cameraDataStreamer = cameraDataStreamer;
	    this.destination=destination;
	    this.frameWidth=frameWidth;
	    this.frameHeight=frameHeight;

	    this.userID = cameraID + "_" + destination + "-" + "FrameCapturer";

	}

	/* methods */
	
	public void addFramesToCapture(int value) {
	    
	    this.framesToBeCaptured += value;
	    
	}

	public void startFrameCapture() {

	    cameraDataStreamer.addUser(userID);

	    new Thread() {

		public void run() {

		    boolean continueLooping = true;

		    try {

			MjpegInputStream mjpegInputStream = new MjpegInputStream(cameraDataStreamer.getInputStream(userID));

			while (capturedFrames < framesToBeCaptured && continueLooping) {

			    MjpegFrame mjpegFrame = mjpegInputStream.readMjpegFrame();
			    			    
			    if (mjpegFrame != null) {
				    
				if (motionCommListener != null){
				    
				    Image scaledImage = mjpegFrame.getImage().getScaledInstance(frameWidth, frameHeight, Image.SCALE_SMOOTH);
				    BufferedImage destinationImage = new BufferedImage(frameWidth, frameHeight, BufferedImage.TYPE_INT_RGB);
				    destinationImage.getGraphics().drawImage(scaledImage, 0, 0, null);
				    				    				    
				    ByteArrayOutputStream out = new ByteArrayOutputStream();
				    ImageIO.write(destinationImage, "gif", out);
				    out.flush();
				    
				    motionCommListener.onNewFrame(cameraID, out.toByteArray(), destination);

				    destinationImage.getGraphics().dispose();
				    out.close();
				    
				}
				    
				// incrementa il contatore dei frames catturati
				capturedFrames++;

				if (debugMode) {
				    System.out.println("FrameCapturer - Frame catturati: " + capturedFrames + "; da catturare: " + framesToBeCaptured);
				}

			    }

			}

			if (debugMode) {
			    System.out.println("FrameCapturer - Numero di fotogrammi raggiunto");
			}

			mjpegInputStream.close();

			if (debugMode) {
			    System.out.println("FrameCapturer - Stream chiuso");
			}

		    } catch (IOException | IllegalArgumentException e) {

			if (debugMode) {
			    System.out.println("FrameCapturer - Exception: " + e.getMessage());
			    continueLooping = false;
			}

		    }

		    cameraDataStreamer.removeUser(userID);

		    if (debugMode) {
			System.out.println("FrameCapturer - richiesta rimozione utilizzatore inviata");
		    }

		    camerasFrameCapturers.remove(cameraID + "_" + destination);

		}

	    }.start();

	}

    }

    /* getters and setters */

    private String host;
    private int port;
    private String owner;
    private boolean debugMode = false;

    public String getHost() {
	return host;
    }

    public void setHost(String host) {
	this.host = host;
    }

    public int getPort() {
	return port;
    }

    public void setPort(int port) {
	this.port = port;
    }

    public void setDebugMode(boolean value) {
	debugMode = value;
    }

    /* control */
    private String baseRequestURL;
    private MotionCommListener motionCommListener;

    private HashMap<String, CameraDataStreamer> camerasDataStreamers = new HashMap<String, CameraDataStreamer>();
    private HashMap<String, FrameCapturer> camerasFrameCapturers = new HashMap<String, FrameCapturer>();

    /* constructors */

    public MotionComm(String host, String owner, int port) {

	this.host = host;
	this.port = port;
	this.owner = owner;

	baseRequestURL = new StringBuilder().append("http://").append(host).append(":").append(port).toString();

    }

    /* interface */

    /* methods */

    public void setListener(MotionCommListener listener) {
	motionCommListener = listener;
    }

    public int getNOfThreads() {

	/*
	 * restituisce il numero di thread attivi corrispondenti ad una
	 * videocamera
	 * 
	 * ad esempio, questa è la risposta di motion con 2 videocamere:
	 * 
	 * Motion 4.0 Running [3] Cameras
	 * 0
	 * 1
	 * 2
	 * 
	 * la funzione restituisce 2, ovvero il numero di righe della
	 * risposta
	 * -2, in quanto la prima riga è la risposta ed il thread 0 è sempre
	 * il
	 * thread principale
	 * 
	 */

	String httpResponse = parseHttpRequest(baseRequestURL);
	String[] responseLines = httpResponse.split("\n");

	return responseLines.length - 2;

    }

    /*
     * Returns a String array with the thread ids of the current motion
     * instance.
     * 
     * For instance, if the reply to the HTTP call
     * "http://{server_address}:{control_port} is the following:
     * 
     * Motion 4.0 Running [3] Cameras
     * 0
     * 1
     * 2
     * 
     * Then this function will return {"1","2"}
     * 
     */
    public String[] getThreadsIDs() {

	String httpResponse = parseHttpRequest(baseRequestURL);
	String[] responseLines = httpResponse.split("\n");

	String[] out = new String[responseLines.length - 2];
	for (int i = 0; i < responseLines.length - 2; i++) {
	    out[i] = responseLines[i + 2];
	}
	return out;

    }

    public static boolean isHTMLOutputEnabled(String host, int port) {

	String httpResponse = parseHttpRequest(host + ":" + port);
	return httpResponse.charAt(0) == '<';

    }

    public boolean isHTMLOutputEnabled() {

	String httpResponse = parseHttpRequest(host + ":" + port);
	return httpResponse.charAt(0) == '<';

    }

    public boolean requestShot(String threadID) {
	// http://{server_name}:{control_port}/{thread_ID}/action/snapshot

	String request = new StringBuilder().append(baseRequestURL).append("/").append(threadID).append("/action/snapshot").toString();

	String[] responseLines = parseHttpRequest(request).split("\n");

	if (responseLines.length == 2) {

	    return responseLines[1].equals("Done");

	} else {

	    return false;

	}

    }

    public boolean requestVideo(String threadID) {

	String request = new StringBuilder().append(baseRequestURL).append("/").append(threadID).append("/action/makemovie").toString();

	String[] responseLines = parseHttpRequest(request).split("\n");

	if (responseLines.length == 2) {

	    return responseLines[1].equals("Done");

	} else {

	    return false;

	}

    }

    public static boolean isMotionInstalled() {

	try {
	    parseShellCommand("motion -h");
	    return true;

	} catch (IOException | InterruptedException e) {
	    System.out.println(e.getMessage());
	    return false;

	}

    }

    public String getThreadMoDetStatus(String threadID) {

	String request = new StringBuilder().append(baseRequestURL).append("/").append(threadID).append("/detection/status").toString();

	String[] responseLines = parseHttpRequest(request).split(" ");

	if (responseLines.length == 5) {

	    return responseLines[4].replaceAll("\n", "");

	} else {

	    return "";

	}

    }

    public HashMap<String, Object> getCameraInfo(String cameraID) {

	/* retrieve the information of the i-th thread */
	HashMap<String, Object> cameraInfo = new HashMap<String, Object>();
	cameraInfo.put("MoDetStatus", getThreadMoDetStatus(cameraID));
	cameraInfo.put("OwnerDevice", owner);
	cameraInfo.put("ThreadID", cameraID);
	cameraInfo.put("CameraName", getParameter(cameraID, "camera_name"));
	cameraInfo.put("StreamFPS", getCameraStreamFPS(cameraID));

	return cameraInfo;

    }

    public String getCamerasList() {
	return getCamerasList(";");
    }

    public String getCamerasList(String regex) {

	/*
	 * Restituisce una lista, separata dalla sequenza di caratteri
	 * _regex,
	 * contenente gli id dei thread corrispondenti alle videocamere
	 * 
	 * ad esempio, se questa è la risposta di motion con 2 videocamere:
	 * 
	 * Motion 4.0 Running [3] Cameras
	 * 0
	 * 1
	 * 2
	 * 
	 * la funzione restituisce "1{_regex}2"
	 * 
	 */

	String[] IDs = getThreadsIDs();

	StringBuilder out = new StringBuilder();
	for (int i = 0; i < IDs.length; i++) {
	    out.append(IDs[i]);

	    if (i < getNOfThreads() - 1)
		out.append(regex);

	}

	return out.toString();

    }

    public String getCamerasNames() {

	return getCamerasNames(";");

    }

    public String getCamerasNames(String regex) {

	String[] IDs = getThreadsIDs();

	StringBuilder out = new StringBuilder();
	for (int i = 0; i < IDs.length; i++) {
	    out.append(getParameter(IDs[i], "camera_name"));

	    if (i < getNOfThreads() - 1)
		out.append(regex);

	}

	return out.toString();

    }

    public void captureFrames(String cameraID, int framesToCapture, String destination) {

	FrameCapturer frameCapturer;
	String frameCapturerID = cameraID + "_" + destination;

	/*
	 * Definisce il FrameCapturer.
	 * Cerca un FrameCapturer attivo per il cameraID passato in argomento.
	 * Se c'è un FrameCapturer attivo per il cameraID passato in argomento,
	 * aumenta il numero di fotogrammi da catturare. Altrimenti, crea un
	 * nuovo FrameCapturer
	 * 
	 */

	if (camerasFrameCapturers.containsKey(frameCapturerID)) {

	    // esiste un FrameCapturer attivo per il cameraID passato in
	    // argomento
	    frameCapturer = camerasFrameCapturers.get(frameCapturerID);
	    frameCapturer.addFramesToCapture(framesToCapture);

	    if (debugMode)
		System.out.println("MotionComm - FrameCapturer TROVATO per cameraID: " + cameraID);

	} else {

	    // non esiste un FrameCapturer attivo per il cameraID passato in
	    // argomento

	    CameraDataStreamer cameraDataStreamer;

	    /*
	     * Definisce il CameraDataStreamer.
	     * Cerca un CameraDataStreamer attivo per il cameraID passato in
	     * argomento. Se esiste un CameraDataStreamer attivo per il cameraID
	     * passato in argomento, lo imposta come InputStream, altrimenti ne
	     * crea uno nuovo.
	     * 
	     */

	    if (camerasDataStreamers.containsKey(cameraID)) {

		// esiste un CameraDataStreamer attivo per il cameraID passato
		// in argomento
		cameraDataStreamer = camerasDataStreamers.get(cameraID);

		if (debugMode)
		    System.out.println("MotionComm - CameraDataStreamer TROVATO per cameraID: " + cameraID);

	    } else {

		// non esiste un CameraDataStreamer attivo per il cameraID
		// passato in argomento

		// crea un nuovo CameraDataStreamer per il cameraID passato in
		// argomento
		cameraDataStreamer = new CameraDataStreamer(cameraID);
		camerasDataStreamers.put(cameraID, cameraDataStreamer);
		cameraDataStreamer.startStreaming();

		if (debugMode)
		    System.out.println("MotionComm - CameraDataStreamer CREATO per cameraID: " + cameraID);

	    }

	    // crea un nuovo FrameCapturer per il cameraID passato in argomento
	    frameCapturer = new FrameCapturer(cameraID, cameraDataStreamer, destination);
	    camerasFrameCapturers.put(frameCapturerID, frameCapturer);

	    frameCapturer.setFramesToBeCaptured(framesToCapture);
	    frameCapturer.startFrameCapture();

	    if (debugMode)
		System.out.println("MotionComm - FrameCapturer CREATO per cameraID: " + cameraID);

	}

    }

    private String getParameter(String cameraID, String parameterID) {

	String request = String.format("%s/%s/config/get?query=%s", baseRequestURL, cameraID, parameterID);

	String[] responseLines = parseHttpRequest(request).split("\n");

	if (responseLines.length == 2) {

	    String reply[] = responseLines[0].replaceAll(" ", "").split("=");

	    if (reply.length == 2) {
		return reply[1];
	    } else {
		return "";
	    }

	} else {

	    return "";

	}

    }

    private String getStreamPort(String cameraID) {

	return getParameter(cameraID, "stream_port");

    }

    /*
     * Restituisce l'indirizzo completo del flusso video della camera il cui ID
     * è passato in argomento
     * Ad esempio:
     * 
     */
    public String getStreamURL(String cameraID) {

	return host + ":" + getStreamPort(cameraID);

    }

    public String getStreamFullURL(String cameraID) {

	return "http://" + getStreamURL(cameraID);

    }

    public boolean startModet(String cameraID) {

	String request = String.format("%s/%s/detection/start", baseRequestURL, cameraID);

	String[] responseLines = parseHttpRequest(request).split("\n");

	if (responseLines.length == 2 && responseLines[1].equals("Done")) {

	    if (motionCommListener != null) {
		motionCommListener.statusChanged(cameraID);
	    }

	    return true;

	} else {

	    return false;

	}

    }

    public boolean stopModet(String cameraID) {

	String request = String.format("%s/%s/detection/pause", baseRequestURL, cameraID);

	String[] responseLines = parseHttpRequest(request).split("\n");

	if (responseLines.length == 2 && responseLines[1].equals("Done")) {

	    if (motionCommListener != null) {
		motionCommListener.statusChanged(cameraID);
	    }

	    return true;

	} else {

	    return false;

	}

    }

    public boolean setParameter(String cameraID, String parameter, String value) {
	// http://10.42.0.10:8080/0/config/set?emulate_motion=off

	String request = String.format("%s/%s/config/set?%s=%s", baseRequestURL, cameraID, parameter, value);

	String[] responseLines = parseHttpRequest(request).split("\n");

	if (responseLines.length == 2) {

	    String reply = responseLines[0];

	    return reply.equals(String.format("%s = %s", parameter, value)) && responseLines[1].equals("Done");

	} else {

	    return false;

	}

    }

    public void requestMotionEvent(String cameraID, int durationSecs) {

	// imposta il parametro "emulate_motion" a "on"
	if (setParameter(cameraID, "emulate_motion", "on")) {

	    long delay = durationSecs * 1000;
	    new Timer().schedule(new StopMotionEmulation(cameraID), delay);

	}

    }

    public static String getDateFromMotionFileName(String fileName) {

	String[] split = fileName.split("-");

	if (split.length != 2)
	    return "NULL";

	if (split[1].length() != 18)
	    return "NULL";

	String year = split[1].substring(0, 4);
	String month = split[1].substring(4, 6);
	String day = split[1].substring(6, 8);

	return String.format("%s-%s-%s", year, month, day);

    }

    public static String getTimeFromMotionFileName(String fileName) {

	String[] split = fileName.split("-");

	if (split.length != 2)
	    return "NULL";

	if (split[1].length() != 18)
	    return "NULL";

	String hours = split[1].substring(8, 10);
	String minutes = split[1].substring(10, 12);
	String seconds = split[1].substring(12, 14);

	return String.format("%s.%s.%s", hours, minutes, seconds);

    }

    public String getCameraName(String cameraID) {
	return getParameter(cameraID, "camera_name");
    }

    public static String getEventJpegFileName(String aviFileName) {

	/*
	 * given the filename of the .avi file returns the filename of the
	 * relevant jpeg shot
	 * .avi file name shall be in the format xx-yyyyMMddhhmmss.avi
	 * this method will return the first occurrence of xx-yyyyMMdd*.jpg, in
	 * order to locate the jpeg shot file generated by motion daemon, which
	 * is in the form xx-yyyyMMddhhmmss-ff.jpg
	 * 
	 * null is returned is .avi file name is not in the expected format, or
	 * if no file named xx-yyyyMMdd*.jpg is found.
	 * 
	 */

	File file = new File(aviFileName);

	String[] shortName = file.getName().split("-");

	if (shortName.length < 2)
	    return null;

	String datePart = shortName[1].substring(0, 8);

	FilenameFilter filter = new FilenameFilter() {

	    @Override
	    public boolean accept(File dir, String name) {
		return name.toLowerCase().startsWith(shortName[0] + "-" + datePart) && name.toLowerCase().endsWith("jpg");
	    }

	};

	File localCmdDirectory = new File(file.getParent());
	try {
	    return localCmdDirectory.listFiles(filter)[0].getAbsolutePath();
	} catch (IndexOutOfBoundsException e) {
	    return null;
	}

    }

    public int getCameraStreamFPS(String cameraID) {

	return Integer.parseInt(getParameter(cameraID, "stream_maxrate"));

    }

    private void printDebugMessage(String tag, String message) {

	if (debugMode) {
	    System.out.println(tag + " - " + message);
	}

    }

    private void printDebugErrorMessage(String tag, Exception e) {

	if (debugMode) {
	    System.out.println(tag + " - [!] EXCEPTION: " + e.getMessage());
	}

    }

}
