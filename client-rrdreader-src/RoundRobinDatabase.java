package com.cittio.wtrs.rrdreader;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import java.util.HashSet;

class RRDException extends Exception {
	public RRDException() { super(); }
	public RRDException(String message) { super(message); }
	public RRDException(String message,Throwable cause) { super(message,cause); }
	public RRDException(Throwable cause) { super(cause); }
}

// read <basename>.rrd and dump its contents into <basename>.txt

class RoundRobinDatabaseExerciser {
	public RoundRobinDatabaseExerciser(String basename)
			throws IOException,RRDException {

		Log.logger.debug("RoundRobinDatabaseExerciser(" + basename + ")");

		File file = new File(basename + ".rrd");
		RoundRobinDatabase rrd = new RoundRobinDatabase(file);

		rrd.log();

		rrd.dumpToFile(basename + ".txt");
	}
}

public class RoundRobinDatabase {
	protected static final int SIZEOF_DOUBLE = 8;

	protected static final String RRD_MAGIC = "RRD";
	protected static final double DOUBLE_COOKIE = 8.642135E130;

	// stat_head_t
	protected final String cookie;
	protected final String version_string;
	protected final int version;
	protected final double float_cookie;
	protected final int ds_cnt;
	protected final int rra_cnt;
	protected final int pdp_step;

	//ds_def_t
	protected final RRD_DataSource[] ds;

	//rra_def_t
	protected final RRD_RoundRobinArchive[] rra;

	// live_head_t
	protected final int last_up;
	protected final int last_up_usec;

	protected static byte[] slurpFile(File file) throws IOException {
		FileInputStream fis = new FileInputStream(file);
		byte[] b = new byte[(int)file.length()];
		fis.read(b);
		fis.close();
		return(b);
	}

	// convenience constructor
	public RoundRobinDatabase(File file) throws IOException,RRDException {
		this(slurpFile(file));
	}

	// real constructor
	public RoundRobinDatabase(byte[] data) throws RRDException {
		this(new LittleEndianPrimitiveBufferReader(data));
	}

	protected RoundRobinDatabase(LittleEndianPrimitiveBufferReader lepr) throws RRDException {
			try {

		//lepr.log("stat_head_t");
		// stat_head_t

		cookie = lepr.fetchString(4);
		//Log.logger.debug("cookie.length()=" + cookie.length());

		version_string = lepr.fetchString(5);
		//Log.logger.debug("version_string.length()=" + version_string.length());
		version = Integer.parseInt(version_string);

		lepr.skip(3); // stupid address alignment

		float_cookie = lepr.fetchDouble();
		ds_cnt = lepr.fetchInt();
		rra_cnt = lepr.fetchInt();
		pdp_step = lepr.fetchInt();
		lepr.skip(80); // par

		// sanity check
		if(!RRD_MAGIC.equals(cookie)) {
			throw(new RRDException("Bad magic: '" + cookie + "'"));
		}

		// sanity check
		if(float_cookie != DOUBLE_COOKIE) {
			throw(new RRDException("Bad cookie: '" + float_cookie + "'"));
		}

		int i;

		//ds_def_t
		ds = new RRD_DataSource[ds_cnt];
		for(i=0;i<ds_cnt;i++) {
			//lepr.log("ds[" + i + "]");
			ds[i] = new RRD_DataSource(lepr);
		}

		// we never use more than one data source per rrd.
		// this reader can still handle it, but I don't know what to do with
		// more than one during output.
		// so we'll warn and continue.  just output the first data source.
		if(ds_cnt > 1) {
			String s = "Multiple data sources in rrd:" + ds_cnt + ":";
			s += Log.dump(ds);
			Log.error(s);
		}

		//rra_def_t
		rra = new RRD_RoundRobinArchive[rra_cnt];
		for(i=0;i<rra_cnt;i++) {
			//lepr.log("rra[" + i + "]");
			rra[i] = new RRD_RoundRobinArchive(lepr,ds_cnt,pdp_step);
		}

		//lepr.log("live_head_t");
		// live_head_t

		last_up = lepr.fetchInt();
		// microsecond precision not supported in version < 3
		if(version < 3) {
			last_up_usec = 0;
		}
		else {
			last_up_usec = lepr.fetchInt();
		}

		// pdp_prep_t
		for(i=0;i<ds_cnt;i++) {
			//lepr.log("pdp_prep_t");
			lepr.skip(30);	// last_ds
			lepr.skip(2);	// align
			lepr.skip(80);	// scratch
		}

		// cdp_prep_t
		for(i=0;i<rra_cnt*ds_cnt;i++) {
			//lepr.log("cdp_prep_t");
			lepr.skip(80);		// scratch
		}

		// rra_ptr_t
		for(i=0;i<rra_cnt;i++) {
			//lepr.log("rra_ptr_t");
			rra[i].cur_row = lepr.fetchInt();
		}

		fetchRowData(lepr);

			}
			catch(ArrayIndexOutOfBoundsException aioobe) {
				throw(new RRDException(aioobe));
			}
	}

	//public static final long NAN_LONG_BITS = 0x7ff8000000000000L;

	// see http://en.wikipedia.org/wiki/IEEE_754
	// see /usr/include/ieee754.h
	// see http://www.cisl.ucar.edu/docs/trap.error/errortypes.html
	public static final long IEEE754_DOUBLE_EXPONENT_MASK = 0x7ff0000000000000L;

	protected void fetchRowData(LittleEndianPrimitiveBufferReader lepr) {
		int row_size = ds_cnt * SIZEOF_DOUBLE;
		int rra_next = lepr.tell();

		for(RRD_RoundRobinArchive rra___ : rra) {
			//lepr.log("reading " + rra___);

			int rra_start = rra_next;
			rra_next += (rra___.row_cnt * row_size);

			lepr.seek(rra_start + (rra___.cur_row+1) * row_size);

			int timer = -(rra___.row_cnt-1);

			int cur_row = rra___.cur_row;

			int jj = 0;
			for(int j=0;j<rra___.row_cnt;j++) {
				++cur_row;
				if(cur_row >= rra___.row_cnt) {
					lepr.seek(rra_start);
					cur_row = 0; // wrap when we hit bottom of this rra
				}

				int now = last_up
					- last_up % rra___.cdp_interval
					+ timer   * rra___.cdp_interval;

				++timer;

				// TODO: time filter goes here
				// TODO: remember to skip bytes

				for(int k=0;k<ds_cnt;k++) {
					long l = lepr.fetchLong();

	// TODO: could do math based on last_up
	// TODO: along with the time range requested
	// TODO: to allow us to jump to the desired records


	// if there are multiple data sources and we're still not interested
	// in any but the first, then we don't have to go through the trouble
	// of all these conversions from long to double.

	// if every bit in the exponent is on, then it's either INF or NAN.
	// we don't really care which.  either way it's not a number we want.

					if(	(l & IEEE754_DOUBLE_EXPONENT_MASK)
						==   IEEE754_DOUBLE_EXPONENT_MASK
					) {
						continue;
					}
					double my_cdp = Double.longBitsToDouble(l);

					jj = rra___.data[k].n_nonnan_rows;

					rra___.data[k].timestamps[jj] = now;
					rra___.data[k].values[jj] = my_cdp;

					++rra___.data[k].n_nonnan_rows;

					//dumpRow(rra___,now,my_cdp);
					//System.out.println(formatRow(rra___,now,my_cdp));
				}
			}
		}
	}

	protected void dumpRow(RRD_RoundRobinArchive rra,int time,double value) {
		Log.logger.debug(formatRow(rra,time,value));
	}

	protected String formatRow(RRD_RoundRobinArchive rra,int time,double value) {
		StringBuffer sb = new StringBuffer(rra.tag);
		sb.append("|").append(time).append("|").append(value);
		return(sb.toString());
	}

	public void log() {
		Log.logger.debug(toString());
		Log.log(ds);
		Log.log(rra);
	}

	private final String classname = Log.trim(this);

	public String toString() {
		StringBuffer sb = new StringBuffer(classname);
		sb.append("(");
		sb.append(cookie).append(",");
		sb.append(version).append(",");
		sb.append(float_cookie).append(",");
		sb.append(ds_cnt).append(",");
		sb.append(rra_cnt).append(",");
		sb.append(pdp_step).append(")");
		sb.append(":(");
		sb.append(version_string).append(",");
		sb.append(last_up).append(",");
		sb.append(last_up_usec).append(")");
		// TODO: add ds and rra data here

		return(sb.toString());
	}

	// TODO: I really hate making this global, but passing it in
	// TODO: as an argument doesn't feel right either.
	protected boolean rejectThisCf(RRD_RoundRobinArchive rra) {
		if(RRDReader.functionRejector.contains(rra.tag)) {
			return(true);
		}
		return(false);
	}

	// this is for OneBigFile
	// it's also a helper for FilePerRRD
	public long dumpToFile(OutputStream os,String prefix)
			throws IOException {

		long nrowsOut = 0;
		for(RRD_RoundRobinArchive rra___ : rra) {
			if(rejectThisCf(rra___)) { continue; }

			nrowsOut += rra___.dumpToFile(os,prefix);
		}
		return(nrowsOut);
	}

	protected static final int WRITE_BUFFER_SIZE = 0x20000;

	// use this for FilePerRRD
	public long dumpToFile(File file,String prefix) throws IOException {
		FileOutputStream fos = new FileOutputStream(file);
		BufferedOutputStream bos = new BufferedOutputStream(fos,WRITE_BUFFER_SIZE);

		long nrowsOut = dumpToFile(bos,prefix);

		bos.flush();
		fos.close();

		return(nrowsOut);
	}

	// this one is only useful for testing
	public long dumpToFile(String filename) throws IOException {
		return dumpToFile(new File(filename),"");
	}

	public int countRows() {
		int n = 0;
		for(RRD_RoundRobinArchive rra___ : rra) {
			n += rra___.countRows();
		}
		return(n);
	}
}

class RRD_DataSource {
	protected final String ds_nam;
	protected final String dst;

	public RRD_DataSource(LittleEndianPrimitiveBufferReader lepr) {
		ds_nam = lepr.fetchString(20);
		dst = lepr.fetchString(20);
		lepr.skip(80); // par
	}

	private final String classname = Log.trim(this);

	public String toString() {
		StringBuffer sb = new StringBuffer(classname);
		sb.append("(");
		sb.append(ds_nam).append(",");
		sb.append(dst).append(")");
		return(sb.toString());
	}
}

// sorry about all the public variables
class RRD_RoundRobinArchive {
	protected final String cf_nam;
	public final int row_cnt;
	public final int pdp_cnt;
	protected final RRA_DS_RowData[] data;

	public int cur_row; // rrdtool keeps this field in its own struct.  it's convenient to stick it here.

	public final int cdp_interval; // calculated
	public final String tag; // calculated

	public RRD_RoundRobinArchive(LittleEndianPrimitiveBufferReader lepr,
			int ds_cnt,int pdp_step) {

		cf_nam = lepr.fetchString(20);
		row_cnt = lepr.fetchInt();
		pdp_cnt = lepr.fetchInt();
		lepr.skip(80); // par

		data = new RRA_DS_RowData[ds_cnt];
		for(int i=0;i<ds_cnt;i++) {
			data[i] = new RRA_DS_RowData(row_cnt);
		}

		// we need this value a lot.  let's pre-calculate it.
		cdp_interval = pdp_cnt * pdp_step;

		// yields something like MAX3600 or AVERAGE300
		tag = cf_nam + cdp_interval;
	}

	private final String classname = Log.trim(this);

	public String toString() {
		StringBuffer sb = new StringBuffer(classname);
		sb.append("(");
		sb.append(cf_nam).append(",");
		sb.append(row_cnt).append(",");
		sb.append(pdp_cnt).append(",");
		sb.append(cur_row).append(")");
		sb.append(":(");
		sb.append(cdp_interval).append(",");
		sb.append(tag).append(")");
		sb.append(":(");
		sb.append(Log.dump(data));
		sb.append(")");
		return(sb.toString());
	}

	public long dumpToFile(OutputStream os,String prefix)
			throws IOException {

		long nrowsOut = 0;
		for(RRA_DS_RowData d : data) {
			nrowsOut += d.dumpToFile(os,tag,prefix);

// we only want the first data source (there *should* only be one)

			return(nrowsOut);
		}

		// not reached (usually)
		return(nrowsOut);
	}

	// return the count of non-NAN rows in the first data source
	public int countRows() {
		int n = 0;
		for(RRA_DS_RowData d : data) {
			n += d.n_nonnan_rows;
			return(n);
		}
		// not reached (usually)
		return(n);
	}
}

// this class holds the row level data for one data source inside one rra

class RRA_DS_RowData {
	public final int[] timestamps;
	public final double[] values;
	public final String logString;

	public int n_nonnan_rows = 0; // measured

	// TODO: i don't really like allocating these huge arrays when i know that
	// TODO: many rows will go unfilled due to NAN.
	// TODO: maybe should manage linked list of smaller arrays which extend as needed.

	// TODO: rather than allocating arrays of size nrows, we could look at
	// TODO: last_up and calculate an upper limit on the number of rows
	// TODO: that might be in this rra.

	// FIXME: should I be storing timestamps as long ???

	public RRA_DS_RowData(int nrows) {
		timestamps = new int[nrows];
		values = new double[nrows];
		logString = "RRA_DS_RowData(" + nrows + ")";
	}

	public String toString() {
		StringBuffer sb = new StringBuffer(logString);
		sb.append(":").append(n_nonnan_rows);
		return(sb.toString());
	}

	public int dumpToFile(OutputStream os,String tag,String prefix) throws IOException {
		//Log.logger.debug("dump:" + prefix + ":" + tag + ":" + this);

		String s = prefix + "|" + tag + "|";

		int nrows = 0;
		StringBuffer sb;
		for(int i=0;i<n_nonnan_rows;i++) {
			// reject rows which aren't in the time range we want.
			// this could happen when we read records instead.

			if(timestamps[i] < TimeFilter.minTimestamp()) {
				continue;
			}
			if(timestamps[i] >= TimeFilter.maxTimestamp()) {
				continue;
			}

			// TODO: think about how to init this StringBuffer

			// 10 == strlen(`date +"%s"`)
			// 1 == strlen("|")
			// 18 == strlen(sprintf("%e",double)) more or less
			sb = new StringBuffer(s.length() + 10 + 1 + 18);

			sb.append(s);
			sb.append(timestamps[i]);
			sb.append("|");
			sb.append(values[i]);

			os.write(sb.toString().getBytes());
			os.write(10); // newline

			++nrows;
		}
		return(nrows);
	}
}

// reads little endian data from a byte array

class LittleEndianPrimitiveBufferReader {
	protected final byte[] data;
	protected int offset = 0;

	public LittleEndianPrimitiveBufferReader(byte[] data) {
		this.data = data;
		classname = Log.trim(this);
	}

	public void log() {
		Log.logger.debug(toString());
	}

	public void log(String s) {
		Log.logger.debug(classname + ":" + offset + ":" + s);
	}

	// make this static
	private final String classname;

	public String toString() {
		return(classname + ":" + data + ":" + offset);
	}

	/*
	  I'd like to just do this
		return(new String(data,offset,length));
	  But it includes the terminating NULL (which shows up if you do
	  String.length() but does not print).
	*/

	public String fetchString(int length) {
		try {
			int realLength;
			for(realLength=0;realLength<length;realLength++) {
				if(0 == data[offset+realLength]) {
					break;
				}
			}

			return(new String(data,offset,realLength));
		}
		finally {
			offset += length;
		}
	}

	public void skip(int nbytes) {
		offset += nbytes;
	}

	public void printBytes(boolean b) {
		printBytes = b;
	}

	public void doPrintBytes(int nbytes) {
		for(int i=0;i<nbytes;i++) {
			Log.logger.debug("   * data[" + (offset+i) + "]=0x" + 
				Integer.toHexString(data[offset+i]&0xff));
		}
	}

	protected boolean printBytes = false;

	public int fetchInt() {
		if(printBytes) { doPrintBytes(4); }

		try {
			return(
				(data[offset]&0xff)		|
				(data[offset+1]&0xff)	<<8	|
				(data[offset+2]&0xff)	<<16	|
				(data[offset+3]&0xff)	<<24
			);
		}
		finally {
			offset += 4; // sizeof(int)
		}
	}

	public long fetchLong() {
		if(printBytes) { doPrintBytes(8); }

		try {
			// it seems to auto cast from byte to int during the shift.
			// you'll lose data on bytes 3-7 if you don't cast before the shift.
			return(
				((long)(data[offset]&0xff))		|
				((long)(data[offset+1]&0xff)) <<8	|
				((long)(data[offset+2]&0xff)) <<16	|
				((long)(data[offset+3]&0xff)) <<24	|
				((long)(data[offset+4]&0xff)) <<32	|
				((long)(data[offset+5]&0xff)) <<40	|
				((long)(data[offset+6]&0xff)) <<48	|
				((long)(data[offset+7]&0xff)) <<56
			);
			//System.out.println(Long.toHexString(l) + " " + l);
		}
		finally {
			offset += 8; // sizeof(long)
		}
	}

	public double fetchDouble() {
		return(Double.longBitsToDouble(fetchLong()));
	}

	public float fetchFloat() {
		return(Float.intBitsToFloat(fetchInt()));
	}

	// TODO: it sure would be nice to encapsulate all the index math.
	// TODO: maybe define a byte range and a cursor which wraps within that range
	public int tell() {
		return(offset);
	}

	public int seek(int new_offset) {
		int old_offset = offset;
		offset = new_offset;
		return(old_offset);
	}
}
