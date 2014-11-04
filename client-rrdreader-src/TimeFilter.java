package com.cittio.wtrs.rrdreader;

// making this global.
// passing it to everybody who needs it would be a pain.

public class TimeFilter {
	protected static long minTimestamp = 0;
	protected static long maxTimestamp = Integer.MAX_VALUE;

	public static void minTimestamp(long l) {
		minTimestamp = l;
	}

	public static long minTimestamp() {
		return(minTimestamp);
	}

	public static void maxTimestamp(long l) {
		maxTimestamp = l;
	}

	public static long maxTimestamp() {
		return(maxTimestamp);
	}

	public String describe() {
		StringBuffer sb = new StringBuffer("TimeFilter(");

		sb.append(minTimestamp).append(",");
		sb.append(maxTimestamp).append(")");

		return(sb.toString());
	}

	public static void checkConsistency() {
		long now = System.currentTimeMillis() / 1000L;

		String msg = null;

		if(maxTimestamp > now) {
			msg = "maxtimestamp="+maxTimestamp+" > now="+now;
		}
		if(minTimestamp > now) {
			msg = "mintimestamp="+minTimestamp+" > now="+now;
		}
		if(minTimestamp >= maxTimestamp) {
			msg = "mintimestamp="+minTimestamp+" >= maxtimestamp="+maxTimestamp;
		}
		if(minTimestamp < 0) {
			msg = "mintimestamp="+minTimestamp+" < 0";
		}
		if(maxTimestamp <= 0) {
			msg = "maxtimestamp="+maxTimestamp+" <= 0";
		}

		if(null != msg) {
			Log.logger.error("Exiting due to inconsistent filters: ("+msg+")");
			System.exit(1);
		}

		Log.logger.info("mintimestamp="+minTimestamp+" maxtimestamp="+maxTimestamp);
	}
}
