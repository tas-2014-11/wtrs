package com.cittio.wtrs.rrdreader;

import java.util.HashSet;

public class FilterCollector<E> {
	protected HashSet<E> set = new HashSet<E>();

	public void addToSet(E e) {
		set.add(e);
	}

	public boolean contains(E e) {
		return(set.contains(e));
	}

	// 'rejecting the following functions: AVERAGE300'
	// 'rejecting the following fact classes: interface,node,storage'
	// 'accepting the following nodes: 1,13,17'

	public void log(String presentParticiple,String pluralNoun) {
		StringBuffer sb = new StringBuffer();
		sb.append(presentParticiple + " the following " + pluralNoun + ": '");
		String separator = "";
		for(E e : set) {
			sb.append(separator);
			sb.append(e);
			separator = ",";
		}
		sb.append("'");
		Log.logger.info(sb.toString());
	}
}

class Rejector<E> extends FilterCollector<E> {
	public void log(String pluralNoun) {
		log("rejecting",pluralNoun);
	}
}

class Acceptor<E> extends FilterCollector<E> {
	public void log(String pluralNoun) {
		log("accepting",pluralNoun);
	}
}

class FunctionRejector extends Rejector<String> {
	public void log() {
		log("functions");
	}
}

class NodeAcceptor extends Acceptor<String> {
	public void log() {
		log("nodes");
	}
}

class FactRejector extends Rejector<FactClass> {
	public void addToRejectSet(FactClass fc) {
		addToSet(fc);
	}

	public boolean loadLatency() {
		return(!contains(FactClass.LATENCY));
	}

	public boolean loadStorage() {
		return(!contains(FactClass.STORAGE));
	}

	public boolean loadNode() {
		return(!contains(FactClass.NODE));
	}

	public boolean loadInterface() {
		return(!contains(FactClass.INTERFACE));
	}

	public void log() {
		log("fact classes");
	}
}

enum FactClass {
	LATENCY,STORAGE,NODE,INTERFACE;

	public static FactClass get(String s) {
		for(FactClass fc : values()) {
			if(0 == fc.toString().compareToIgnoreCase(s)) { return(fc); }
		}
		return(null);
	}
}
