/*
 * execution example: pinger.jar
 * 
 * Motivation: Guys in UnityMedia.de are not masters of a stable internet connection. Before complaining to their help desk
 * I needed some hard evidence of the connection's disruptions.
 * 
 * Description: Invokes system's command line and executes endless ping command. Reads command line's output, parses it
 * and outputs it into a pipe-delimited text file for analysis in tools such as Tableau.
 */
package pinger;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import java.sql.*;

public class Pinger {
	public static final boolean DEBUG = true;
	public static int bufferSize = 60; /* How many latency responses are to be kept in memory before flushing everything to the database, setting default to 60 */
	public static int locId; /* Location identifier in cases I ever needed to monitor more than one location */
	public static boolean writeToDb = false; /* a flag whether we should be writing the results to DB */
	public static boolean writeToFile = false; /* a flag whether we should be writing the results to file */
	public static String configLocation="config.xml"; /* XML configuration file path */

	public static void main(String[] args) {
		System.out.println("Pinger for (so far) Windows by Jiri Hubacek, originally forked from https://gist.github.com/madan712/4509039");

		/* Get the values from the XML config file */
		String ip ="";
		try {
			ip = getPingedAddress(configLocation);
			bufferSize = getBufferSize(configLocation);
			locId = getLocaId(configLocation);
			writeToDb = getWriteToDb(configLocation);
			writeToFile = getWriteToFile(configLocation);
		} catch (Exception e) {
			e.printStackTrace();
			System.out.println("Couldn't get the configuration values righ. Exiting ...");
			System.exit(-1);
		}
		
		System.out.println("Entering endless ping loop");
		while (1 == 1) {
			runSystemCommand("ping " + ip + " -t");
		}
	}




	public static void runSystemCommand(String command) {
		List<String> buffer = new ArrayList<String>(bufferSize);
		
		/* genericBuffer contains records represented by genericBufferRecord */
		List<HashMap> genericBuffer = new ArrayList<HashMap>(bufferSize);

		/* TODO: Linux PING command parsing */
		/* Skeleton:
				try {
				//p = r.exec("uname -a");
				p = r.exec("ping 8.8.8.8");
				BufferedReader b = new BufferedReader(new InputStreamReader(p.getInputStream()));
				String line = "";
				while ((line = b.readLine()) != null) {
				  System.out.println(line);
				}
				b.close();
				}
				catch (Exception e) {
				e.printStackTrace();
				}
				//}
		 */
		
		/* Windows OS specific part: */
		try {
			/* executes the PING command on the system level */
			Process p = Runtime.getRuntime().exec(command);
			BufferedReader inputStream = new BufferedReader(new InputStreamReader(p.getInputStream()));

			String s = "";
			/* If waiting for response times out, this counter gets increased, when a reply is received, the counter gets reset to 0
			 * If more than 5 succeeding timeouts occur, lagFlag is set to 1 */
			int timeoutCounter = 0;
			SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
			
			/* reading the command's output stream */
			while ((s = inputStream.readLine()) != null) {
				HashMap<String,String> genericBufferRecord = new HashMap<String,String>();
				if (s.contains("Reply")) {
					timeoutCounter=0;
					if (DEBUG) System.out.println(sdf.format(new Date()) + "|" + s.substring(s.indexOf("time=") + 4 + 1, s.indexOf("ms")) + "|0" );
					genericBufferRecord.put("ts",sdf.format(new Date()));
					genericBufferRecord.put("latency",s.substring(s.indexOf("time=") + 4 + 1, s.indexOf("ms")) );
					genericBufferRecord.put("lagFlag", "0");
					genericBuffer.add(genericBufferRecord);

				} else if (s.contains("Request timed")) {
					timeoutCounter++;
					if (timeoutCounter >= 5) {
						/* set flag to 1 as there's longer than 5 second timeout */
						if (DEBUG) System.out.println( sdf.format(new Date()) + "|1000|1");
						genericBufferRecord.put("ts",sdf.format(new Date()));
						genericBufferRecord.put("latency","null");
						genericBufferRecord.put("lagFlag", "1");
						genericBuffer.add(genericBufferRecord);
					} else {
						if (DEBUG) System.out.println( sdf.format(new Date()) + "|1000|0");
						genericBufferRecord.put("ts",sdf.format(new Date()));
						genericBufferRecord.put("latency","null");
						genericBufferRecord.put("lagFlag", "0");
						genericBuffer.add(genericBufferRecord);
					}
				/* general failure is triggered in situations such as when the network cable is unplugged or the NIC restarts, this might also happen
				 * when connected to WLAN and router restarts - coz some 'guys' from UnityMedia are updating the router in the middle of the day.
				 * Therefore conservatively setitng the lagFlag to 1 */	
				} else if (s.contains("General failure.")) {
					if (DEBUG) System.out.println( sdf.format(new Date()) + "|1100|1");
					genericBufferRecord.put("ts",sdf.format(new Date()));
					genericBufferRecord.put("latency","null");
					genericBufferRecord.put("lagFlag", "1");
					genericBuffer.add(genericBufferRecord);
				}
				
				/* Once we hit the buffer size, flush the buffer to DB or/and file */ 
				if (genericBuffer.size() == bufferSize) {
					try {
						System.out.println("Buffer limit of " + bufferSize + " hit, flushing buffer to file");
						if (writeToFile) writeFile(genericBuffer);
						if (writeToDb) insertToDb(genericBuffer);
					} catch (SQLException e) {
						if (e.getErrorCode() == 1062)
							/* Known issue: seldom happens when more than two replies are receved within the same second. As my MySql version on my RapsberyPi
							 * doesn't support higher Timestamp precision than second, it's easier to ignore this case */
							System.out.println("ERROR: Primary key violation, nothing special, might happen, we can happily continue :) ...");
						else {
							System.out.println("vendor error code: " + e.getErrorCode() );
							e.printStackTrace();
							System.out.println("A problem occured during insertion to the database. Continuing ...");
						}
					} catch (Exception e) {
						e.printStackTrace();
					} finally {
						genericBuffer.clear();
					}
					
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		
	}

	
	/* when buffer limit is reached and db insertion is enabled, flush it into the DB */
	private static void insertToDb(List<HashMap> buffer) throws SQLException,Exception {
		/* building connection string based on the values stores in the XML configuration file */
		File fXmlFile = new File(configLocation);
		DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
		DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
		Document doc = dBuilder.parse(fXmlFile);
		doc.getDocumentElement().normalize();
			
		NodeList dbNodeList = doc.getDocumentElement().getElementsByTagName("db");		
		NamedNodeMap nnm = dbNodeList.item(0).getAttributes();
			
		String dbUser = nnm.getNamedItem("user").getTextContent();
		String dbPasswd = nnm.getNamedItem("passwd").getTextContent();
		String dbConnString = nnm.getNamedItem("connString").getTextContent();
		
		Connection con=DriverManager.getConnection(dbConnString, dbUser, dbPasswd);
		Statement stmt=con.createStatement();  
		
		/* Iterate over buffer and build up INSERT commands which are being added to a batched INSERT */
		for (HashMap currentMap : buffer) {
				String currentLine = "INSERT INTO latency (loc_id, ts, latency, lag_flag) VALUES (";
				currentLine += locId;
				currentLine += ",'";
				currentLine += currentMap.get("ts");
				currentLine += "',";
				currentLine += currentMap.get("latency");
				currentLine += ",";
				currentLine += currentMap.get("lagFlag");
				currentLine += ");";
				System.out.println(currentLine);	
				stmt.addBatch(currentLine);
		}
		stmt.executeBatch();		
		con.close();
	}
	
	
	public static void writeFile(List<HashMap> in) throws IOException {
		String filePath="";
		try {
			filePath = getFilePath(configLocation);
		} catch (Exception e) {
			e.printStackTrace();
			System.out.println("Couldn't get the file path from the XML file. Exiting ...");
			System.exit(-1);
		}
		
		File fout = new File(filePath);
		FileOutputStream fos;
		BufferedWriter bw;
		
		if (fout.exists()) {
			fos = new FileOutputStream(fout, true);
			bw = new BufferedWriter(new OutputStreamWriter(fos));
		}	
		else {
			fos = new FileOutputStream(fout, false);
			bw = new BufferedWriter(new OutputStreamWriter(fos));
			bw.write("locid|ts|latency|lag_flag");
			bw.newLine();
		}
			
		/* File write */
		for (HashMap<String, String> currentMap : in) {
			if (currentMap.containsKey("ts") && currentMap.containsKey("lagFlag") && currentMap.containsKey("latency"))
			{
				if (DEBUG)
					System.out.println("Writing to file: " + locId + "|" + currentMap.get("ts") + "|" + currentMap.get("latency") + "|" + currentMap.get("lagFlag") );
				bw.write(locId + "|" + currentMap.get("ts") + "|" + currentMap.get("latency") + "|" + currentMap.get("lagFlag") );
				bw.newLine();
			} else
				/* we should never get here :) */
				System.out.println("ERROR: you messed up your hashmap, man");
		}
		bw.close();
	}
	
	private static String getPingedAddress(String configLocation) throws Exception {
		File fXmlFile = new File(configLocation);
		DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
		DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
		Document doc = dBuilder.parse(fXmlFile);
		doc.getDocumentElement().normalize();
		
		String ip = doc.getDocumentElement().getElementsByTagName("pingedAddress").item(0).getTextContent();

		String ip_pattern = 
		        "(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)";

		Pattern pattern = Pattern.compile(ip_pattern);
		Matcher matcher = pattern.matcher(ip);
		if (matcher.find()) {
		    return matcher.group();
		} else{
		    throw new Exception("Wrong ip address format");
		}
		
	}
	
	private static String getFilePath(String configLocation) throws Exception {
		File fXmlFile = new File(configLocation);
		DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
		DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
		Document doc = dBuilder.parse(fXmlFile);
		doc.getDocumentElement().normalize();
		
		String filePath = doc.getDocumentElement().getElementsByTagName("filePath").item(0).getTextContent();
		return filePath;
	}
	
	private static int getBufferSize(String configLocation) throws Exception {
		File fXmlFile = new File(configLocation);
		DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
		DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
		Document doc = dBuilder.parse(fXmlFile);
		doc.getDocumentElement().normalize();
		
		int bufferSize = Integer.parseInt(doc.getDocumentElement().getElementsByTagName("bufferSize").item(0).getTextContent());
		return bufferSize;
	}
	
	private static int getLocaId(String configLocation) throws Exception {
		File fXmlFile = new File(configLocation);
		DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
		DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
		Document doc = dBuilder.parse(fXmlFile);
		doc.getDocumentElement().normalize();
		
		int bufferSize = Integer.parseInt(doc.getDocumentElement().getElementsByTagName("locId").item(0).getTextContent());
		return bufferSize;
	}
	
	private static boolean getWriteToFile(String configLocation) throws Exception {
		File fXmlFile = new File(configLocation);
		DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
		DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
		Document doc = dBuilder.parse(fXmlFile);
		doc.getDocumentElement().normalize();
		
		boolean bufferSize = Boolean.parseBoolean(doc.getDocumentElement().getElementsByTagName("writeToFile").item(0).getTextContent());
		return bufferSize;
	}

	private static boolean getWriteToDb(String configLocation) throws Exception {
		File fXmlFile = new File(configLocation);
		DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
		DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
		Document doc = dBuilder.parse(fXmlFile);
		doc.getDocumentElement().normalize();
		
		boolean bufferSize = Boolean.parseBoolean(doc.getDocumentElement().getElementsByTagName("writeToDb").item(0).getTextContent());
		return bufferSize;
	}
	

}