package com.cittio.wtrs.rrdreader;

import java.io.File;
import java.util.Iterator;
import java.util.List;

import org.apache.log4j.Logger;

// http://logging.apache.org/log4j/docs/api/org/apache/log4j/Logger.html

public class Log {
	public static final Logger logger = Logger.getLogger("com.cittio.wtrs.rrdreader");

	public static void debug(Object message) { logger.debug(message); }
	public static void debug(Object message,Throwable t) { logger.debug(message,t); }
	public static void error(Object message) { logger.error(message); }
	public static void error(Object message,Throwable t) { logger.error(message,t); }
	public static void fatal(Object message) { logger.fatal(message); }
	public static void fatal(Object message,Throwable t) { logger.fatal(message,t); }
	public static void info(Object message) { logger.info(message); }
	public static void info(Object message,Throwable t) { logger.info(message,t); }
	public static void warn(Object message) { logger.warn(message); }
	public static void warn(Object message,Throwable t) { logger.warn(message,t); }

	public static String trim(Object o) {
		String s = o.getClass().getName();
		return(s.substring(1+s.lastIndexOf(".")));
	}

	public static void log(Object o) {
		logger.debug(o);
	}

	public static void log(Iterator i) {
		while(i.hasNext()) {
			logger.debug("--" + i.next());
		}
	}

	public static void log(Object[] o) {
		logger.debug("===");
		for(int i=0;i<o.length;i++) {
			logger.debug("   " + o[i]);
		}
		logger.debug("===");
	}

	public static void log(File[] fa) {
		logger.debug("START Log.log");
		for(int i=0;i<fa.length;i++) {
			logger.debug("  " + fa[i]);
		}
		logger.debug("END Log.log");
	}

	public static void log(double[] da) {
		logger.debug("START Log.log(double[])");
		for(int i=0;i<da.length;i++) {
			logger.debug("   " + i + "   " + da[i]);
		}
		logger.debug("END Log.log(double[])");
	}

	public static String dump(Object[] o) {
		StringBuffer sb = new StringBuffer("{");
		if(null == o) { return("null"); }
		for(int i=0;i<o.length;i++) {
			if(i > 0) { sb.append(","); }
			sb.append(o[i]);
		}
		sb.append("}");
		return(sb.toString());
	}

	public static String dump(String[] s) {
		StringBuffer sb = new StringBuffer("{");
		if(null == s) { return("null"); }
		for(int i=0;i<s.length;i++) {
			if(i > 0) { sb.append(","); }
			sb.append(s[i]);
		}
		sb.append("}");
		return(sb.toString());
	}

	public static String dump(Object[][] o) {
		if(null == o) { return("null"); }
		StringBuffer sb = new StringBuffer("{");

		for(int i=0;i<o.length;i++) {
			if(i > 0) { sb.append(","); }

			if(null == o[i]) {
				sb.append("null");
				continue;
			}

			sb.append("[");
			for(int j=0;j<o[i].length;j++) {
				if(j > 0) { sb.append(","); }
				sb.append(o[i][j]);
			}
			sb.append("]");
		}
		sb.append("}");
		return(sb.toString());
	}

	public static String dump(List l) {
		if(null == l) { return("null"); }
		StringBuffer sb = new StringBuffer("{");
		for(Iterator i = l.iterator();i.hasNext();) {
			sb.append(i.next().toString());
			if(i.hasNext()) { sb.append(","); }
		}
		sb.append("}");
		return(sb.toString());
	}
}
