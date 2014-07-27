package com.socialproxy.tunnel;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.Selector;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.ByteBuffer;
import java.util.logging.Level;
import java.util.logging.Logger;

public class CarrierTool {
	private static final byte[] KEY_S2C = "1234567890123456".getBytes();
	private static final byte[] KEY_C2S = "client to server".getBytes();
	private final static Logger LOG = Logger.getLogger(CarrierTool.class.getName());
	private static Carrier carrier;

	public static void main (String [] args) throws Exception
	{
		if (args.length < 3 || !(args[0].equals("client") || args[0].equals("server"))) {
			System.out.println("Server Usage:");
			System.out.println("  java " + CarrierTool.class.getName() +
					" server carrier-listen-port [tunnel-spec ...]");
			System.out.println("Client Usage:");
			System.out.println("  java " + CarrierTool.class.getName() +
					" client carrier-conn-addr carrier-conn-port [tunnel-spec ...]");
			System.out.println("Tunnel Spec:");
			System.out.println("  tcp:listenport:connaddr:connport");
			System.out.println("  socks:listenport");
			System.out.println("Examples:");
			System.out.println("  java " + CarrierTool.class.getName() +
					" server 8848 socks:8849");
			System.out.println("  java " + CarrierTool.class.getName() +
					" client localhost 8848 socks:8850");
			System.out.println("  java " + CarrierTool.class.getName() +
					" server 8848 tcp:8849:localhost:22");
			System.out.println("  java " + CarrierTool.class.getName() +
					" client localhost 8848 tcp:8850:localhost:22");
			return;
		}

		for (int i = args[0].equals("server") ? 2 : 3; i < args.length; i ++) {
			String [] arr = args[i].split(":");
			if (arr[0].equals("tcp")) {
				new TCPListener(Integer.parseInt(arr[1]), arr[2], Integer.parseInt(arr[3])).start();
			} else if (arr[0].equals("socks")) {
				throw new RuntimeException("don't yet support spec " + args[i]);
			} else {
				throw new RuntimeException("don't support spec " + args[i]);
			}
		}

		if (args[0].equals("server")) {
			ServerSocketChannel serverSocket = ServerSocketChannel.open();
			serverSocket.bind(new InetSocketAddress(Integer.parseInt(args[1])));
			LOG.info("Lintening on port " + args[1]);
			while (true) {
				SocketChannel socket = serverSocket.accept();
				LOG.info("Accepted connection");
				carrier = new Carrier(socket, true, KEY_S2C, KEY_C2S);
				carrier.run();
				carrier = null;
			}
		} else {
			SocketChannel socket = SocketChannel.open(
					new InetSocketAddress(args[1], Integer.parseInt(args[2])));
			LOG.info("Carrier Connected");
			carrier = new Carrier(socket, false, KEY_C2S, KEY_S2C);
			carrier.run();
		}
	}

	static class TCPListener extends Thread {
		private final int listenPort;
		private final String remoteAddr;
		private final int remotePort;
		public TCPListener (int listenPort, String remoteAddr, int remotePort)
		{
			super("TCPListener " + listenPort);
			this.listenPort = listenPort;
			this.remoteAddr = remoteAddr;
			this.remotePort = remotePort;
		}

		@Override
		public void run ()
		{
			try {
				runInternal();
			} catch (Exception x) {
				LOG.log(Level.SEVERE, "TCPListener error", x);
			}
		}

		private void runInternal () throws Exception
		{
			ServerSocketChannel serverSocket = ServerSocketChannel.open();
			serverSocket.bind(new InetSocketAddress(listenPort));
			while (true) {
				SocketChannel socket = serverSocket.accept();
				LOG.info("Accepted local socket");
				if (carrier == null) {
					LOG.severe("local socket connected before carrier");
					socket.close();
				} else {
					boolean succeed = carrier.createChannel(socket, remoteAddr, remotePort);
					LOG.info("carrier.createChannel() " + (succeed ? "succeed" : "failed"));
					if (!succeed)
						socket.close();
				}
			}
		}
	}
}
