package com.socialproxy.tunnel;

import java.net.Socket;

/* A carrier can carry mutiple tunneled channels.
 * It acts as a multiplexer bewteen multiple channels (frontend)
 * and a single transport backend, typically a TCP socket or UDT socket.
 *
 * A carrier has a local end and a remote end.
 * One of them is male, the other is female.
 * The two party has to agree on male/female by e.g. lower usr id is male.
 * Channel initiated by male end use slot in the range [1, 127], while female
 * uses [128, 254].
 * Channel 0 is for control.
 * Channel 255 is reserved.
 */
public class Carrier {
	private final Socket backsock;
	private final TChannel[] channels = new TChannel [256];
	private final boolean isMale; // maleEnd's channel ID
	private int nextSlot;

	public Carrier (Socket backend, boolean isMale)
	{
		backsock = backend;
		this.isMale = isMale;
		nextSlot = isMale ? 1 : 128;
	}

	// TODO need to make it synchronized?
	public TChannel createTChannel ()
	{
		nextSlot ++;
		if (nextSlot == (isMale ? 128 : 255))
			nextSlot -= 127;
		TChannel channel = new TChannel(this, nextSlot);
		channels[nextSlot] = channel;
		// TODO send control commands
		return channel;
	}
}
