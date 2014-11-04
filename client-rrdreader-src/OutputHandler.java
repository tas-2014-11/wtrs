package com.cittio.wtrs.rrdreader;

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;

import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URL;
import java.net.URLConnection;

import java.security.DigestOutputStream;
import java.security.MessageDigest;

import java.util.zip.GZIPOutputStream;

import org.apache.commons.httpclient.HttpURL;
import org.apache.webdav.lib.WebdavResource;

enum OutputMode {
	FilePerRRD {
		public OutputHandler createHandler() {
			return(new OutputHandler_FilePerRRD());
		}
	},
	OneBigFile {
		public OutputHandler createHandler() {
			return(new OutputHandler_OneBigFile());
		}
	},
	OneBigGzipFile {
		public OutputHandler createHandler() {
			return(new OutputHandler_OneBigGzipFile());
		}
	},
	OneBigFileOverHttpViaPut {
		public OutputHandler createHandler()
				throws MalformedURLException {

			return(new OutputHandler_OneBigFileOverHttpViaPut());
		}
	},
	GzipOverHttpViaPutWithMD5 {
		public OutputHandler createHandler()
				throws MalformedURLException {

			return(new OutputHandler_GzipOverHttpViaPutWithMD5());
		}
	};

	public abstract OutputHandler createHandler()
			throws MalformedURLException;

	// default to OneBigGzipFile
	public static OutputMode get(String s) {
		if(null == s) { return(OneBigGzipFile); }
		for(OutputMode om : values()) {
			if(0 == om.toString().compareToIgnoreCase(s)) {
				return(om);
			}
		}
		return(OneBigGzipFile);
	}
}

public abstract class OutputHandler {
	public abstract void preExecute() throws IOException;

	public abstract void postExecute() throws IOException;

	public abstract void setLeafdir(File leafdir);

	public abstract void setBasename(String basename);

	public abstract long write(RoundRobinDatabase rrd) throws IOException;

	protected String recordPrefix;
	public final void setRecordPrefix(String recordPrefix) {
		this.recordPrefix = recordPrefix;
	}

	protected String factClass;
	public final void setFactClass(String factClass) {
		this.factClass = factClass;
	}

	// TODO: pad to fixed width
	protected final String generateBatchid() {
		return(TimeFilter.minTimestamp() + "." +
			TimeFilter.maxTimestamp());
	}

	protected String wtid;
	public void setWtid(String wtid) {
		this.wtid = wtid;
	}

	public String toString() {
		StringBuffer sb = new StringBuffer(Log.trim(this));
		sb.append("(").append(factClass);
		sb.append(",").append(wtid);
		sb.append(")");
		return(sb.toString());
	}
}

abstract class LocalOutputHandler extends OutputHandler {
	protected final File writedir;

	public LocalOutputHandler() {
		writedir = new File(RRDReader.fetchWritedir());
		Log.logger.info("writedir=" + writedir.getPath());
	}
}

class OutputHandler_FilePerRRD extends LocalOutputHandler {
	public OutputHandler_FilePerRRD() {
		super();
	}

	public void preExecute() throws IOException {
		Util.mkdir_p(writedir);
	}

	protected File leafdir;
	public void setLeafdir(File leafdir) {
		this.leafdir = leafdir;

		// TODO: it's possible that we have a directory for which
		// TODO: we're going to reject all OIDs.  should do lazy
		// TODO: directory create.

		Util.mkdir_p(leafdir);
	}

	protected String basename;
	public void setBasename(String basename) {
		this.basename = basename;
	}

	public long write(RoundRobinDatabase rrd) throws IOException {
		File dir = new File(writedir,leafdir.getPath());
		// basename == OID (or service when factClass == latency)
		File writeFile = new File(dir,basename + ".txt");
		long nrowsOut = rrd.dumpToFile(writeFile,recordPrefix);
		return(nrowsOut);
	}

	public void postExecute() { }
	public void setWtid(String wtid) { }
}

class OutputHandler_OneBigFile extends LocalOutputHandler {
	protected static final int WRITE_BUFFER_SIZE = 0x20000;

	protected OutputStream outputStream;

	public OutputHandler_OneBigFile() {
		super();
	}

	protected String generateFilename() {
		return(factClass + ".txt");
	}

	protected File file;  // nonlocal for toString()

	public void preExecute() throws IOException {
		File batchdir = new File(writedir,generateBatchid());
		batchdir = new File(batchdir,wtid);

		// TODO: maybe make sure the batchdir is empty
		Util.mkdir_p(batchdir);

		// TODO: fail if file already exists

		file = new File(batchdir,generateFilename());

		FileOutputStream fos = new FileOutputStream(file);

		outputStream = new BufferedOutputStream(fos,WRITE_BUFFER_SIZE);

		// If I wanted to print a header for each file I might do
		// that here.  Something like
		// this bufferedWriter.write(getRRDHeader())
		// followed by this bufferedWriter.newLine()
	}

	public long write(RoundRobinDatabase rrd) throws IOException {
		long nrowsOut = rrd.dumpToFile(outputStream,recordPrefix);
		return(nrowsOut);
	}

	public void postExecute() throws IOException {
		outputStream.close();
	}

	public void setLeafdir(File leafdir) { }
	public void setBasename(String basename) { }

	public String toString() {
		StringBuffer sb = new StringBuffer(Log.trim(this));
		sb.append("(").append(file);
		sb.append(")");
		return(sb.toString());
	}
}

class OutputHandler_OneBigGzipFile extends OutputHandler_OneBigFile {
	public OutputHandler_OneBigGzipFile() {
		super();
	}

	protected String generateFilename() {
		return(factClass + ".txt.gz");
	}

	public void preExecute() throws IOException {
		super.preExecute();
		outputStream = new GZIPOutputStream(outputStream); // buffer size ???
	}

	public void setLeafdir(File leafdir) { }
}

class OutputHandler_OneBigFileOverHttpViaPut extends OutputHandler {
	protected OutputStream outputStream;
	protected InputStream inputStream;

	public static final int CHUNKLEN = 4096;
	public static final int WRITE_BUFFER_SIZE = 0x4000;

	protected HttpURLConnection huc;
	protected final URL putUrl;

	protected URL url; // non local for toString()

	public OutputHandler_OneBigFileOverHttpViaPut() throws MalformedURLException {
		// canonicalize the base url where we intend to write
		String s = RRDReader.fetchPutUrl();
		if(!s.endsWith("/")) { s = s + "/"; }
		putUrl = new URL(s);
	}

	protected String generateFilename() {
		return(factClass + ".txt");
	}

	protected void buildUrlAndOpenConnection() throws IOException {
		String urlString = putUrl.toString();
		DavHelper dh = new DavHelper(urlString);

		urlString += generateBatchid();
		dh.mkcol(urlString);

		urlString += "/" + wtid;
		dh.mkcol(urlString);

		urlString += "/" + generateFilename();

		Log.logger.info("connecting to " + urlString);

		url = new URL(urlString);
		URLConnection uc = url.openConnection();

		// don't worry about ClassCastException.
		// if the cast fails I'm screwed anyway.

		huc = (HttpURLConnection)uc;

		huc.setRequestMethod("PUT");
		huc.setChunkedStreamingMode(CHUNKLEN);

		huc.setUseCaches(false);
		huc.setDoOutput(true);
		huc.setDoInput(true);
	}

	public void preExecute() throws IOException {
		buildUrlAndOpenConnection();

		outputStream = new BufferedOutputStream(huc.getOutputStream(),
			WRITE_BUFFER_SIZE);

		huc.connect();
	}

	public long write(RoundRobinDatabase rrd) throws IOException {
		long nrowsOut = rrd.dumpToFile(outputStream,recordPrefix);
		return(nrowsOut);
	}

	public void postExecute() throws IOException {
		outputStream.close();

		logHttpHeaders();
		logHttpContent();
	}

	protected void logHttpHeaders() {
		int i = 1;
		while(true) {
			String hfk = huc.getHeaderFieldKey(i);
			String hf = huc.getHeaderField(i);

			if(null == hfk) { break; }
			if(null == hf) { break; }

			Log.logger.debug("header[" + i + "]=(" + hfk + "," + hf + ")");

			++i;
		}
	}

	protected void logHttpContent() throws IOException {
		InputStream inputStream = huc.getInputStream();

		//int nbytes = huc.getContentLength();
		byte b[] = new byte[1024];

		int len;
		while((len = inputStream.read(b)) > 0) {
			String s = new String(b,0,len);
			String a[] = s.split("\n");
			for(String s1 : a) {
				Log.logger.debug(s1);
			}
		}
	}

	public void setBasename(String basename) { }
	public void setWritedir(File writedir) { }
	public void setLeafdir(File leafdir) { }

	public String toString() {
		StringBuffer sb = new StringBuffer(Log.trim(this));
		sb.append("(").append(url);
		sb.append(")");
		return(sb.toString());
	}
}

class OutputHandler_GzipOverHttpViaPutWithMD5
		extends OutputHandler_OneBigFileOverHttpViaPut {

	protected DigestOutputStream dos;

	public OutputHandler_GzipOverHttpViaPutWithMD5()
			throws MalformedURLException {
		super();
	}

	protected String generateFilename() {
		return(factClass + ".txt.gz");
	}

	public void preExecute() throws IOException {
		buildUrlAndOpenConnection();

		BufferedOutputStream bos =
			new BufferedOutputStream(huc.getOutputStream(),WRITE_BUFFER_SIZE);

		try {
			dos = new DigestOutputStream(bos,MessageDigest.getInstance("MD5"));
		}
		catch(java.security.NoSuchAlgorithmException nsae) {
			Log.logger.error(nsae);
			throw(new IOException(nsae));
		}

		outputStream = new GZIPOutputStream(dos);

		huc.connect();
	} 

	protected String digestString = "no_digest";

	protected void completeDigest() {
		// finish off the digest
		MessageDigest md = dos.getMessageDigest();
		byte[] mdBytes = md.digest();

		// TODO: write the digest to a file.
		// TODO: transmit to server so he can verify receipt.

		digestString = Util.stringify(mdBytes);
		Log.logger.info("digest=" + digestString);
	}

	public void postExecute() throws IOException {
		outputStream.close();

		completeDigest();

// do I have to do a getInputStream() before I can read headers ???

		logHttpHeaders();
		logHttpContent();
	}

	public String toString() {
		StringBuffer sb = new StringBuffer(Log.trim(this));
		sb.append("(").append(url);
		sb.append(",").append(digestString);
		sb.append(")");
		return(sb.toString());
	}
}

class DavHelper {
/*
	protected static void mkcol(String s) throws IOException {
		URL url = new URL(s);
		URLConnection uc = url.openConnection();

		HttpURLConnection huc = (HttpURLConnection)uc;

		// FIXME: This will fail because HttpURLConnection validates method
		huc.setRequestMethod("MKCOL");

		huc.setUseCaches(false);
		huc.setDoOutput(false);
		huc.setDoInput(true);

		huc.connect();

		logHttpHeaders();
		logHttpContent();

		huc.disconnect();
	}
*/

	// this is the apache slide version.

	// TODO: fix exception handling

	protected final WebdavResource webdavResource;
	public DavHelper(String baseUrl) throws IOException {
		try {
			HttpURL hurl = new HttpURL(baseUrl);
			webdavResource = new WebdavResource(hurl);
		}
		catch(org.apache.commons.httpclient.HttpException he) {
			throw(new IOException(he));
		}
		catch(org.apache.commons.httpclient.URIException ue) {
			throw(new IOException(ue));
		}
	}

	public boolean mkcol(String s) throws IOException {
		Log.logger.debug("mkcol(" + s + ")");
		return(webdavResource.mkcolMethod(s));
	}
}
