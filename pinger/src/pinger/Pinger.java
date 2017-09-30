/*
 * execution example: pinger.jar "8.8.8.8" "C:\\tmp\\out.txt"
 * 
 * Motivation: Guys in UnityMedia.de are not masters of stable internet connection. Before complaining to their help desk
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

import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.sql.*;

public class Pinger {
	public static final boolean DEBUG = true;
	public static final int BUFFERSIZE = 2;
	public static final int LOCID = 1;
	public static String configLocation;
	public static String filePath;

	public static void main(String[] args) {
		System.out.println("Pinger for Windows by Jiri Hubacek, forked from https://gist.github.com/madan712/4509039");

		
		if (args.length != 2) {
			System.out.println("Argument error. Expected arguments: <ipaddress> <filepath> <configLocation>");
			System.exit(-1);
		}
		String ip = args[0];
		filePath = args[1];
		configLocation="src//pinger//config.xml";

		System.out.println("Entering endless ping loop");
		while (1 == 1) {
			runSystemCommand("ping " + ip + " -t");
		}
	}

	public static void runSystemCommand(String command) {
		List<String> buffer = new ArrayList<String>(BUFFERSIZE);
		
		// genericBuffer contains records represented by genericBufferRecord
		List<HashMap> genericBuffer = new ArrayList<HashMap>(BUFFERSIZE);

		
		// if OS = windows
		try {
			Process p = Runtime.getRuntime().exec(command);
			BufferedReader inputStream = new BufferedReader(new InputStreamReader(p.getInputStream()));

			String s = "";
			int timeoutCounter = 0;
			SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
			
			// reading the command's output stream
			while ((s = inputStream.readLine()) != null) {
				HashMap<String,String> genericBufferRecord = new HashMap<String,String>();
				if (s.contains("Reply")) {
					timeoutCounter=0;
					if (DEBUG) System.out.println(sdf.format(new Date()) + "|" + s.substring(s.indexOf("time=") + 4 + 1, s.indexOf("ms")) + "|0" );
					genericBufferRecord.put("ts",sdf.format(new Date()));
					genericBufferRecord.put("latency",s.substring(s.indexOf("time=") + 4 + 1, s.indexOf("ms")) );
					genericBufferRecord.put("lagFlag", "0");
					genericBuffer.add(genericBufferRecord);
					//buffer.add(sdf.format(new Date()) + "|" + s.substring(s.indexOf("time=") + 4 + 1, s.indexOf("ms")) + "|0" );

				} else if (s.contains("Request timed")) {
					timeoutCounter++;
					if (timeoutCounter >= 5) {
						// set flag to 1 as there's longer than 5 second timeout
						if (DEBUG) System.out.println( sdf.format(new Date()) + "|1000|1");
						genericBufferRecord.put("ts",sdf.format(new Date()));
						genericBufferRecord.put("latency","null");
						genericBufferRecord.put("lagFlag", "1");
						//buffer.add(sdf.format(new Date()) + "|1000|1");
						genericBuffer.add(genericBufferRecord);
					} else {
						if (DEBUG) System.out.println( sdf.format(new Date()) + "|1000|0");
						//buffer.add(sdf.format(new Date()) + "|1000|0");
						genericBufferRecord.put("ts",sdf.format(new Date()));
						genericBufferRecord.put("latency","null");
						genericBufferRecord.put("lagFlag", "0");
						genericBuffer.add(genericBufferRecord);
					}
					
				} else if (s.contains("General failure.")) {
					// general failure is triggered in situations such as when the network cable is unplugged or the NIC restarts
					// therefore lag flag is not being set
					if (DEBUG) System.out.println( sdf.format(new Date()) + "|1100|0");
					//buffer.add(sdf.format(new Date()) + "|1100|0");
					genericBufferRecord.put("ts",sdf.format(new Date()));
					genericBufferRecord.put("latency","null");
					genericBufferRecord.put("lagFlag", "1");
					genericBuffer.add(genericBufferRecord);
				}
				
				if (genericBuffer.size() == BUFFERSIZE) {
					try {
						System.out.println("Buffer limit of " + BUFFERSIZE + " hit, flushing buffer to file");
						writeFile(genericBuffer);
						insertToDb(genericBuffer);
					} catch (SQLException e) {
						if (e.getErrorCode() == 1062)
							System.out.println("ERROR: Primary key violation, nothing special might happen, we can happily continue :) ...");
						else {
							System.out.println("vendor error code: " + e.getErrorCode() );
							e.printStackTrace();
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

	

	private static void insertToDb(List<HashMap> buffer) throws SQLException,Exception {
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
		
		for (HashMap currentMap : buffer) {
				String currentLine = "INSERT INTO latency (loc_id, ts, latency, lag_flag) VALUES (";
				currentLine += LOCID;
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
			
		// flushing buffer
		for (HashMap<String, String> currentMap : in) {
			if (currentMap.containsKey("ts") && currentMap.containsKey("lagFlag") && currentMap.containsKey("latency"))
			{
				if (DEBUG)
					System.out.println("Writing to file: " + LOCID + "|" + currentMap.get("ts") + "|" + currentMap.get("latency") + "|" + currentMap.get("lagFlag") );
				bw.write(LOCID + "|" + currentMap.get("ts") + "|" + currentMap.get("latency") + "|" + currentMap.get("lagFlag") );
				bw.newLine();
			} else
				System.out.println("ERROR: you fucked up your hashmap, man");
		}
		bw.close();
	}

}
