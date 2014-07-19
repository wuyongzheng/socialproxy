package com.socialproxy.tunnel;

public class CarrierProtocolException extends Exception
{
	public CarrierProtocolException ()
	{
	}

	public CarrierProtocolException (String message)
	{
		super(message);
	}

	public CarrierProtocolException (String message, Throwable cause)
	{
		super(message, cause);
	}

	public CarrierProtocolException (Throwable cause)
	{
		super(cause);
	}
}
