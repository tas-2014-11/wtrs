package com.cittio.wtrs.rrdreader;

// http://java.sun.com/j2se/1.5.0/docs/api/
// http://logging.apache.org/log4j/docs/api/org/apache/log4j/Logger.html

import java.io.BufferedReader;
import java.io.File;
import java.io.FileFilter;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;

class Util {
	// TODO: sometimes we want to fail if the dir already exists.
	// TODO: sometimes we want to make sure the dir is empty.

	public static void mkdir_p(File dir) {
		Log.logger.debug("*** mkdir_p(" + dir + ")");
		if(!dir.isDirectory()) {
			if(!dir.mkdirs()) {
				Log.error("Could not create directory '"
					+ dir.getAbsolutePath() + "'");
				System.exit(1);
			}
			Log.info("Created directory '"
				+ dir.getAbsolutePath() + "'");
		}
	}

	protected static String fetchPropertyOrDie(String property) {
		String s = System.getProperty(property);
		if(null == s) {
			System.out.println("please set system property: "
				+ property);
			System.exit(1);
		}
		return(s);
	}

	public static void sleep(int secs) {
		try { Thread.sleep(secs*1000); }
		catch(java.lang.InterruptedException ie) { }
	}

	public static String stringify(byte[] ba) {
		StringBuffer sb = new StringBuffer();
		for(byte b : ba) {
			int i = 0xff & b;
			if(i < 0x10) { sb.append("0"); }
			sb.append(Integer.toHexString(i));
		}
		return(sb.toString());
	}
}

public class RRDReader {
	protected static final String PROPERTY_READDIR = "RRDReader.readdir";
	protected static final String PROPERTY_WRITEDIR = "RRDReader.writedir";
	protected static final String PROPERTY_OUTPUTMODE = "RRDReader.outputmode";
	protected static final String PROPERTY_FILTER_MINTIMESTAMP =
		"RRDReader.filter.mintimestamp";
	protected static final String PROPERTY_FILTER_MAXTIMESTAMP =
		"RRDReader.filter.maxtimestamp";
	protected static final String PROPERTY_REJECT_FACTS = "RRDReader.reject.facts";
	protected static final String PROPERTY_REJECT_FUNCTIONS = "RRDReader.reject.functions";
	protected static final String PROPERTY_ACCEPT_NODES = "RRDReader.accept.nodes";
	protected static final String PROPERTY_EXITAFTERPARSE = "RRDReader.exitAfterParse";
	protected static final String PROPERTY_PUTURL = "RRDReader.puturl";
	protected static final String PROPERTY_WTID = "RRDReader.wtid";
	protected static final String PROPERTY_BATCHIDFILE = "RRDReader.batchidfile";

	protected static final long SECS_PER_DAY = 86400;

	// global.  sorry.
	public static FunctionRejector functionRejector = new FunctionRejector();
	public static NodeAcceptor nodeAcceptor = new NodeAcceptor();
	public static FactRejector factRejector = new FactRejector();

	protected static String fetchReaddir() {
		return(Util.fetchPropertyOrDie(PROPERTY_READDIR));
	}

	protected static String fetchWritedir() {
		return(Util.fetchPropertyOrDie(PROPERTY_WRITEDIR));
	}

	protected static OutputMode fetchOutputMode() {
		OutputMode om;

		String s = System.getProperty(PROPERTY_OUTPUTMODE);
		om = OutputMode.get(s); // null arg is ok here

		String msg = "using OutputMode '" + om + "'";
		msg += " because ";
		msg += PROPERTY_OUTPUTMODE + "='" + s + "'";
		Log.logger.info(msg);

		return(om);
	}

	public static long midnightYesterdayUTC() {
		long l = System.currentTimeMillis();
		l /= 1000;	// change milliseconds to seconds
		l /= SECS_PER_DAY;	// how many days into the epoch are we
		l *= SECS_PER_DAY;	// most recent midnight in UTC
		return(l);
	}

	public static long midnightDayBeforeYesterdayUTC() {
		return(midnightYesterdayUTC() - SECS_PER_DAY);
	}

	// TODO: think about UTC vs localtime.
	// TODO: this file just uses System.currentTimeMillis() for updates
	// TODO: monitor/src/src/services/org/opennms/netmgt/rrd/RrdUtils.java

	// if the property is unspecified get yesterday's data

	protected static long fetchMinTimestamp() {
		String s = System.getProperty(PROPERTY_FILTER_MINTIMESTAMP);
		if(null == s) {
			long l = midnightDayBeforeYesterdayUTC();

			String msg = "property " + PROPERTY_FILTER_MINTIMESTAMP;
			msg += " not found.";
			msg += "  using value " + l;
			Log.logger.info(msg);

			return(l);
		}

		try {
			return(Integer.parseInt(s));
		}
		catch(NumberFormatException nfe) {
			long l = midnightDayBeforeYesterdayUTC();

			String msg = "value '" + s + "' for property ";
			msg += PROPERTY_FILTER_MINTIMESTAMP + " is not a number";
			msg += ".  using value " + l;
			msg += ".  " + nfe.getMessage();
			Log.logger.info(msg);

			return(l);
		}
	}

	// if property is unspecified then set max = now

	protected static long fetchMaxTimestamp() {
		String s = System.getProperty(PROPERTY_FILTER_MAXTIMESTAMP);
		if(null == s) {
			long l = midnightYesterdayUTC();

			String msg = "property " + PROPERTY_FILTER_MAXTIMESTAMP;
			msg += " not found.";
			msg += "  using value " + l;
			Log.logger.info(msg);

			return(l);
		}

		try {
			return(Integer.parseInt(s));
		}
		catch(NumberFormatException nfe) {
			long l = midnightYesterdayUTC();

			String msg = "value '" + s + "' for property ";
			msg += PROPERTY_FILTER_MAXTIMESTAMP + " is not a number";
			msg += ".  using value " + l;
			msg += ".  " + nfe.getMessage();
			Log.logger.info(msg);

			return(l);
		}
	}

	// property is comma separated list of fact types to reject.
	// if no property get all fact types.

	public static void fetchFactsToReject() {
		String s = System.getProperty(PROPERTY_REJECT_FACTS);
		if(null == s) { return; }

		String[] tokens = s.split(",");
		for(String token : tokens) {
			FactClass fc = FactClass.get(token);
			if(null == fc) {
				Log.logger.error("bad token '" + token + "' for " +
					PROPERTY_REJECT_FACTS);
				System.exit(1);
			}

			factRejector.addToRejectSet(fc);
		}
	}

	// TODO: add a hook for token validation and merge with fetchFactsToReject
	// TODO: move to FactCollector

	public static void stuffCommaDelimitedPropertyValuesIntoFilterCollector(
			String property,FilterCollector<String> fc) {

		String s = System.getProperty(property);
		if(null == s) { return; }

		String[] tokens = s.split(",");
		for(String token : tokens) {
			fc.addToSet(token);
		}
	}

	// get a comma separated list of functions we don't want to load.
	// if the property is unset then load all functions.

	public static void fetchFunctionsToReject() {
		stuffCommaDelimitedPropertyValuesIntoFilterCollector(
			PROPERTY_REJECT_FUNCTIONS,functionRejector);
		functionRejector.log();
	}

	public static void fetchNodesToAccept() {
		stuffCommaDelimitedPropertyValuesIntoFilterCollector(
			PROPERTY_ACCEPT_NODES,nodeAcceptor);
		nodeAcceptor.log();
	}

	public static void checkForEarlyExit() {
		if(Boolean.getBoolean(PROPERTY_EXITAFTERPARSE)) {
			Log.logger.info("exiting early because " +
				PROPERTY_EXITAFTERPARSE + " is set");
			System.exit(0);
		}
	}

	public static String fetchPutUrl() {
		return(Util.fetchPropertyOrDie(PROPERTY_PUTURL));
	}

	public static String fetchWtid() {
		return(Util.fetchPropertyOrDie(PROPERTY_WTID));
	}

	public static String fetchBatchidfile() {
		return(Util.fetchPropertyOrDie(PROPERTY_BATCHIDFILE));
	}

	public static final String lineSeparator =
			Util.fetchPropertyOrDie("line.separator");

	public static void spoolBatchid() throws IOException {
		String batchid = TimeFilter.minTimestamp() + "." +
			TimeFilter.maxTimestamp();

		File file = new File(fetchBatchidfile());

		Log.logger.info("writing batchid '" + batchid + "' to '" + file + "'");

		FileOutputStream fos = new FileOutputStream(file);

		fos.write(batchid.getBytes());
		fos.write(lineSeparator.getBytes());
		fos.close();
	}

	// TODO: different args are required depending on output mode.
	// TODO: figure out a way to encode that and validate arg deps.

	public static void go() throws IOException {
		OutputMode om = fetchOutputMode();
		OutputHandler oh = om.createHandler();

		oh.setWtid(fetchWtid());

		File readdir = new File(fetchReaddir());
		Log.logger.info("readdir=" + readdir.getPath());

		TimeFilter.minTimestamp(fetchMinTimestamp());
		TimeFilter.maxTimestamp(fetchMaxTimestamp());
		TimeFilter.checkConsistency();

		spoolBatchid();

		fetchFactsToReject();
		factRejector.log();

		fetchFunctionsToReject();

		fetchNodesToAccept();

		checkForEarlyExit();

		if(factRejector.loadLatency()) {
			LatencyRRDReader lrd = new LatencyRRDReader(readdir,oh);
			lrd.execute();
		}

		if(factRejector.loadStorage()) {
			StorageRRDReader srd = new StorageRRDReader(readdir,oh);
			srd.execute();
		}

		if(factRejector.loadNode()) {
			NodeRRDReader nrd = new NodeRRDReader(readdir,oh);
			nrd.execute();
		}

		if(factRejector.loadInterface()) {
			InterfaceRRDReader ird = new InterfaceRRDReader(readdir,oh);
			ird.execute();
		}
	}

	public static void exercise() throws IOException,RRDException {
		new RoundRobinDatabaseExerciser("icmp");
	}

	public static void main(String[] args) throws Exception {
		RRDReader.go();
	}

	public String toString() {
		return(Log.trim(this) + "()");
	}

	protected void finalize() {
		System.out.flush();
	}
}

class Stats {
	protected long rowsIn = 0;
	protected long rowsOut = 0;
	protected final long startTime = System.currentTimeMillis() / 1000;
	protected long endTime;
	protected long fileCount = 0;

	public void addRowsIn(long l) { rowsIn += l; }

	public void addRowsOut(long l) { rowsOut += l; }

	public void setEndTimeToNow() {
		endTime = System.currentTimeMillis() / 1000;
	}

	public void incrementFileCount() { ++fileCount; }

	public String toString() {
		StringBuffer sb = new StringBuffer("Stats(");
		sb.append("rowsIn=").append(rowsIn).append(",");
		sb.append("rowsOut=").append(rowsOut).append(",");
		sb.append("startTime=").append(startTime).append(",");
		sb.append("endTime=").append(endTime).append(",");
		sb.append("fileCount=").append(fileCount).append(")");
		return(sb.toString());
	}
}

abstract class AbstractRRDReader {
	protected final String factClass;
	protected final File readdir;
	protected final OutputHandler oh;

	protected final String logString;

	protected Stats stats = new Stats();

	public static final String RRD_EXTENSION = ".rrd";
	public static final String HRSTORAGE = "hrStorage";
	public static final String UNKNOWN = "UNKNOWN";

	protected FileFilter_AcceptRrdFiles ffarf = new FileFilter_AcceptRrdFiles();
	protected FileFilter_RejectRrdFiles ffrrf = new FileFilter_RejectRrdFiles();
	protected FileFilter_AcceptHrStorage ffahs = new FileFilter_AcceptHrStorage();

	// TODO: I have to store factClass in two places: here and in the OutputHandler.
	// TODO: That's because we need to know both the OutputMode and the FactClass
	// TODO: in order to compute the output file name correctly.
	// TODO: Want to fix that.
	// TODO: The same used to go for writedir until I fixed that.

	public AbstractRRDReader(String factClass,
			File readdir,OutputHandler oh)
			throws IOException {

		this.factClass = factClass;
		this.readdir = readdir;
		this.oh = oh;

		oh.setFactClass(factClass);

		logString = Log.trim(this) +
			"(" + factClass + "," + readdir + "," + oh + ")";
	}

	public void execute() throws IOException {
		oh.preExecute();

		reallyExecute();

		oh.postExecute();

		stats.setEndTimeToNow();
		Log.logger.info(logString + ":" + stats);
	}

	public String toString() {
		return(logString + ":" + stats);
	}

	public static String calculateBasename(File f) {
		String basename = f.getName();
		int length = basename.lastIndexOf(".");
		if(length < 0) { return(basename); }
		return(basename.substring(0,length));
	}

	// read all rrd files in this directory
	protected void consumeDirectory(File dir) {
		//Log.logger.debug("=== consumeDirectory(" + dir + ")");
		Log.logger.debug("=== " + dir);

		// TODO: this does extra work.
		// TODO: we only need to generate the name of the leaf directory
		// TODO: for FilePerRRD.
		// TODO: could implement LeafDirBuilder and pass this for callback.

		oh.setLeafdir(buildLeafdir());

		File files[] = dir.listFiles(ffarf);
		Arrays.sort(files);
		for(File file : files) {
			Log.logger.debug("    " + file);

			if(!file.isFile()) { continue; }

			String basename = calculateBasename(file);
			oh.setBasename(basename);

			//Log.logger.debug("    basename=" + basename);

			// the basename of the rrd file is also the oid
			// TODO: oid filter goes here

			// TODO: check modification time of rrd file
			// TODO: skip if no mod since start time.

			RoundRobinDatabase rrd;
			try {
				rrd = new RoundRobinDatabase(file);
				//rrd.log();
			}
			catch(IOException ioe) {
				// couldn't read this rrd.  try the next one.
				Log.logger.error(file,ioe);
				continue;
			}
			catch(RRDException rrde) {
				// data glitch in this rrd.  try the next one.
				Log.logger.error(file,rrde);
				continue;
			}

			String recordPrefix = prepareRecordPrefix(basename);
			oh.setRecordPrefix(recordPrefix);

			try {
				long nrowsOut = oh.write(rrd);

				stats.addRowsOut(nrowsOut);
				stats.addRowsIn(rrd.countRows());
				stats.incrementFileCount();
			}
			catch(IOException ioe1) {
				Log.logger.error(logString + ":" + file,ioe1);
				continue;
			}
		}
	}

	protected abstract String getRRDHeader();
	protected abstract void reallyExecute() throws IOException;
	protected abstract File buildLeafdir();
	protected abstract String prepareRecordPrefix(String basename);
}

abstract class SimpleRRDReader extends AbstractRRDReader {
	protected final String topDirName;
	protected String keyDirName;

	protected static final String headerTrailingColumns =
		"rrd_function|rrd_time|rrd_value";

	public SimpleRRDReader(String factClass,File readdir,
			OutputHandler oh,String topDirName)
			throws IOException {

		super(factClass,readdir,oh);
		this.topDirName = topDirName;

		Log.logger.info(this);
	}

	public String toString() {
		return(logString + ":" + topDirName);
	}

	// node and latency share common directory structures.  this walks them.
	protected void reallyExecute() {
		File top = new File(readdir,topDirName);

		// TODO: maybe use list() instead of listFiles()

		// expect a list of either nodeids or ip addresses

		File fa[] = top.listFiles();

		sortDir(fa);
		for(File dir : fa) {
			//Log.logger.debug("+++ " + dir);

			if(!dir.isDirectory()) {
				Log.logger.info("expected directory but found: "
					+ dir);
				continue;
			}

			// when reading latency data dir.getName() is an ip address.
			// when reading node,interface,storage dir.getName() is the nodeid.
			// save it and use later to build the write directory for FilePerRRD

			keyDirName = dir.getName();

			processDir(dir);
		}
	}

	// this works for node and latency.  interface and storage need more processing.
	protected void processDir(File dir) {
		consumeDirectory(dir);
	}

	protected File buildLeafdir() {
		File writeSubdir = new File(factClass);
		return(new File(writeSubdir,keyDirName));
	}

	protected String prepareRecordPrefix(String oid) {
		return(keyDirName + "|" + oid);
	}

	protected void sortDir(File[] fa) {
		Arrays.sort(fa,new fileAsInt_Comparator());
	}
}

class fileAsInt_Comparator implements Comparator<File> {
	public int compare(File a,File b) {
		try {
			int ai = Integer.parseInt(a.getName());
			int bi = Integer.parseInt(b.getName());
			return(ai - bi);
		}
		catch(NumberFormatException nfe) {
			Log.logger.debug(nfe.toString() + ":" + a + ":" + b);
			return(0);
		}
	}
}

class fileAsStringBasename_Comparator implements Comparator<File> {
	public int compare(File a,File b) {
		return(a.getName().compareTo(b.getName()));
	}
}

class NodeRRDReader extends SimpleRRDReader {
	public NodeRRDReader(File readdir,OutputHandler oh)
			throws IOException {

		super("node",readdir,oh,"snmp");
	}

	protected String getRRDHeader() {
		return("rrd_nodeid|rrd_oid|" + headerTrailingColumns);
	}
}

class LatencyRRDReader extends SimpleRRDReader {
	public LatencyRRDReader(File readdir,OutputHandler oh)
			throws IOException {

		super("latency",readdir,oh,"response");
	}

	protected void sortDir(File[] fa) {
		Arrays.sort(fa,new fileAsStringBasename_Comparator());
	}

	protected String getRRDHeader() {
		return("rrd_ipaddress|rrd_service|" + headerTrailingColumns);
	}
}

class InterfaceRRDReader extends SimpleRRDReader {
	public InterfaceRRDReader(File readdir,OutputHandler oh)
			throws IOException {

		super("interface",readdir,oh,"snmp");
	}

	protected String interfaceName;

	protected void processDir(File d) {
		Log.logger.debug("    processDir(" + d + ")");

		// expect to find:
		// a) one directory per interface
		// b) many rrd files containing node data
		// c) hrStorage directory

		File[] dirlist = d.listFiles(ffrrf);
		Arrays.sort(dirlist,new fileAsStringBasename_Comparator());

		for(File dir : dirlist) {
			if(dir.isFile()) { continue; }

			if(dir.isDirectory()) {
				if(0 == HRSTORAGE.compareTo(dir.getName())) { continue; }

				//interfaceName = extractInterfaceName(dir.getName());
				//Log.logger.debug("interfaceName=" + interfaceName);

				interfaceName = dir.getName();

				consumeDirectory(dir);
			}
		}
	}

	// directory names are built from the interface followed by the
	// mac address with a dash stuck between them.
	// if a mac address isn't available then it's just the interface name.
	// interface names are scrubbed to make them filesystem-safe.  see source here:
	// WT_3-1_patches_branch/WatchTower/monitor/src/src/services/org/opennms/netmgt/utils/IfLabel.java
	// WT_3-1_patches_branch/WatchTower/monitor/src/src/services/org/opennms/netmgt/utils/AlphaNumeric.java

	// remove the trailing mac address from the directory name

/*
	// WARNING: some agents (Win2k for example) do not produce unique names.
	// WARNING: we must keep the mac address or risk key collisions.

	protected String extractInterfaceName(String s) {
		throw(new Error("Don't use this function"));
		if(s.length() < 14) { return(s); } // too short.

		byte[] b = s.getBytes();

		if(b[b.length-13] != '-') { return(s); } // no dash.

		for(int i=b.length-12;i<b.length;i++) {
			if(b[i]>=0x30 && b[i]<=0x39) { continue; }	// 0-9
			if(b[i]>=0x61 && b[i]<=0x66) { continue; }	// a-f
			if(b[i]>=0x41 && b[i]<=0x46) { continue; }	// A-F

			// some non hex characters.  can't be a mac.
			return(s);
		}

		return(s.substring(0,b.length-13));	// trim mac and '-'
	}
*/

	// construct a directory tree that looks like this:
	// interface/9/eth0
	// or like this interface/9/eth0-00504500a7cd if we want to avoid key collisions
	protected File buildLeafdir() {
		File nodeSubdir = new File(factClass,keyDirName);
		File interfaceSubdir = new File(nodeSubdir,interfaceName);
		return(interfaceSubdir);
	}

	protected String prepareRecordPrefix(String oid) {
		return(keyDirName + "|" + oid + "|" + interfaceName);
	}

	protected String getRRDHeader() {
		return("rrd_nodeid|rrd_oid|rrd_interface|" + headerTrailingColumns);
	}
}

class FileFilter_AcceptRrdFiles implements FileFilter {
	public boolean accept(File f) {
		return(f.getName().endsWith(AbstractRRDReader.RRD_EXTENSION));
	}
}

class FileFilter_RejectRrdFiles implements FileFilter {
	public boolean accept(File f) {
		return(!f.getName().endsWith(AbstractRRDReader.RRD_EXTENSION));
	}
}

class FileFilter_AcceptHrStorage implements FileFilter {
	public boolean accept(File f) {
		return(0 == f.getName().compareTo(AbstractRRDReader.HRSTORAGE));
	}
}

class StorageRRDReader extends SimpleRRDReader {
	public StorageRRDReader(File readdir,OutputHandler oh)
			throws IOException {

		super("storage",readdir,oh,"snmp");
	}

	protected String storageName;
	protected String storageid;

	protected void processDir(File d) {
		Log.logger.debug("    processDir(" + d + ")");

		// expect to find:
		// a) one directory per interface
		// b) many rrd files containing node data
		// c) hrStorage directory

		for(File d1 : d.listFiles(ffahs)) {
			if(!d1.isDirectory()) { continue; }

			// expect one directory per filesystem.  dir name is storageid
			for(File d2 : d1.listFiles()) {

				storageid = d2.getName();
				storageName = fetchStorageName(d2);

				consumeDirectory(d2);
			}
		}
	}

	// TODO: maybe scrub the filesystem name to make it sql safe ???
	// TODO: watch out for pipe characters.

	protected String fetchStorageName(File dir) {
		try {
			File file = new File(dir,"GraphTitle.txt");
			FileReader fr = new FileReader(file);
			BufferedReader br = new BufferedReader(fr);
			String s = br.readLine();
			if(null == s) { return(UNKNOWN); }
			return(s);
		}
		catch(IOException ioe) {
			return(UNKNOWN);
		}
	}

	protected File buildLeafdir() {
		File nodeSubdir = new File(factClass,keyDirName);
		File storageSubdir = new File(nodeSubdir,storageid);
		return(storageSubdir);
	}

	protected String prepareRecordPrefix(String oid) {
		return(keyDirName + "|" + oid + "|" + storageName);
	}

	protected String getRRDHeader() {
		return("rrd_nodeid|rrd_oid|rrd_storagename|" + headerTrailingColumns);
	}
}
