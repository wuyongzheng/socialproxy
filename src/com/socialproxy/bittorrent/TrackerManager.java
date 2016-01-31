package com.socialproxy.bittorrent;

import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class TrackerManager extends Thread {
	private static TrackerManager instance;
	private final DelayQueue<Task> taskQueue = new DelayQueue<Task>();
	private final HashMap<String, ArrayList<String>> trackers = new HashMap<String, ArrayList<String>>();

	private TrackerManager (Iterable<String> trackerList)
	{
		for (String line : trackerList) {
			String id = line.substring(0, line.indexOf('\t'));
			String url = line.substring(line.indexOf('\t') + 1);
			if (trackers.containsKey(url))
				trackers.get(id).add(url);
			else {
				ArrayList<String> arr = new ArrayList<String>();
				arr.add(url);
				trackers.put(id, arr);
			}
		}
	}

	/* each one in trackers is in the form "trackerID<tab>URL" */
	public static synchronized TrackerManager getInstance (Iterable<String> trackerList)
	{
		if (instance == null) {
			instance = new TrackerManager(trackerList);
			instance.start();
		}
		return instance;
	}

	public void run ()
	{
		while (true) {
			try {
				Task task = taskQueue.take();
				task.execute();
			} catch (InterruptedException x) {}
		}
	}

	/*public void addPeer (Peer peer)
	{
	}*/

	public static abstract class Task implements Delayed {
		public final long millis;
		public Task (long t) {millis = t;}
		public long getDelay (TimeUnit unit) {
			return unit.convert(millis - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
		}
		public abstract void execute ();
	}
}
