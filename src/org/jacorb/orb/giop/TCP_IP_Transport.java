/*
 *        JacORB - a free Java ORB
 *
 *   Copyright (C) 1997-2003  Gerald Brose.
 *
 *   This library is free software; you can redistribute it and/or
 *   modify it under the terms of the GNU Library General Public
 *   License as published by the Free Software Foundation; either
 *   version 2 of the License, or (at your option) any later version.
 *
 *   This library is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *   Library General Public License for more details.
 *
 *   You should have received a copy of the GNU Library General Public
 *   License along with this library; if not, write to the Free
 *   Software Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
 */

package org.jacorb.orb.connection;

import java.net.*;
import java.io.*;

import org.jacorb.orb.BufferManager;
import org.jacorb.util.*;

/**
 * TCP_IP_Transport.java
 *
 *
 * Created: Sun Aug 12 20:18:47 2002
 *
 * @author Nicolas Noffke
 * @version $Id$
 */

public abstract class TCP_IP_Transport
    implements Transport
{
    protected InputStream in_stream = null;
    protected OutputStream out_stream = null;

    private byte[] msg_header = null;
    private BufferManager buff_mg = null;

    private ByteArrayOutputStream b_out = null;
    private boolean dump_incoming = false;

    String connection_info;
    Socket socket;

    //the statistics provider, may stay null
    private StatisticsProvider statistics_provider = null;

    //used to unregister this transport
    protected TransportManager transport_manager = null;

    private TransportListener transport_listener = null;

    private int finalTimeout = 20000;

    public TCP_IP_Transport( StatisticsProvider statistics_provider,
                             TransportManager transport_manager )
    {
        this.statistics_provider = statistics_provider;
        this.transport_manager = transport_manager;

        msg_header = new byte[ Messages.MSG_HEADER_SIZE ];

        buff_mg = BufferManager.getInstance();

        String dump_outgoing =
            Environment.getProperty( "jacorb.debug.dump_outgoing_messages",
                                     "off" );

        if( "on".equals( dump_outgoing ))
        {
            b_out = new ByteArrayOutputStream();
        }

        String dump_incoming_str =
            Environment.getProperty( "jacorb.debug.dump_incoming_messages",
                                     "off" );

        dump_incoming = "on".equals( dump_incoming_str );

        finalTimeout = 
            Environment.getIntPropertyWithDefault( "jacorb.connection.timeout_after_closeconnection", 
                                                   20000 );
    }

    /**
     * Open up a new connection (if not already done). This is always
     * called prior to sending a message.
     */
    protected abstract void connect();

    /**
     * Wait until the connection is established. This is called from
     * getMessage() so the connection may be opened up not until the
     * first message is sent (instead of opening it up when the
     * transport is created).
     *
     * @return true if connection ready, false if connection closed.
     */
    protected abstract boolean waitUntilConnected();

    /**
     * This method tries to read in <tt>length</tt> bytes from
     * <tt>in_stream</tt> and places them into <tt>buffer</tt>
     * beginning at <tt>start_pos</tt>. It doesn't care about the
     * contents of the bytes.
     *
     * @return the actual number of bytes that were read.
     */

    private final int readToBuffer( byte[] buffer,
                                    int start_pos,
                                    int length )
        throws IOException
    {
        int read = 0;

        while( read < length )
        {
            int n = 0;

            try
            {
                n = in_stream.read( buffer,
                                    start_pos + read,
                                    length - read );
            }
            catch( InterruptedIOException e )
            {
                if (socket.getSoTimeout () != 0)
                {
                    Debug.output
                        (
                         2,
                         "Socket timed out with timeout period of " +
                         socket.getSoTimeout ()
                         );

                    transport_listener.readTimedOut();
                    return -1;
                }
                else
                {
                    throw e;
                }
            }
            catch( SocketException se )
            {
                Debug.output( 2, "Transport to " + connection_info +
                              ": stream closed" );

                transport_listener.streamClosed();
                return -1;
            }

            if( n < 0 )
            {
                Debug.output( 2, "Transport to " + connection_info +
                              ": stream closed" );

                transport_listener.streamClosed();
                return -1;
            }

            read += n;
        }

        return read;
    }

    // implementation of org.jacorb.orb.connection.Transport interface

    /**
     * Read a GIOP message from the stream. This will first try to
     * read in the fixed-length GIOP message header to determine the
     * message size, and the read the rest. It also checks the leading
     * four magic bytes of the message header. This method <b>is not
     * thread safe<b> and only expected to be called by a single
     * thread.
     *
     * @return a GIOP message or null.
     * @exception IOException passed through from the underlying IO layer.
     */
    public byte[] getMessage()
        throws IOException
    {
        //Wait until the actual socket connection is established. This
        //is necessary for the client side, so opening up a new
        //connection can be delayed until the first message is to be
        //sent.
        if( ! waitUntilConnected() )
        {
            return null;
        }

        int read = readToBuffer( msg_header, 0, Messages.MSG_HEADER_SIZE );

        if( read == -1 )
        {
            return null;
        }

        if( read != Messages.MSG_HEADER_SIZE )
        {
            //TODO: resynching?

            // Debug.output( 1, "ERROR: Failed to read GIOP message header" );
            // Debug.output( 1, (Messages.MSG_HEADER_SIZE - read) +
            //                   " Bytes less than the expected " +
            //                   Messages.MSG_HEADER_SIZE + " Bytes" );
            // Debug.output( 3, "TCP_IP_GIOPTransport.getMessage()",
            //                  msg_header, 0, read );

            return null;
        }
        
        //(minimally) decode GIOP message header. Main checks should
        //be done one layer above.

        if( (char) msg_header[0] == 'G' && (char) msg_header[1] == 'I' &&
            (char) msg_header[2] == 'O' && (char) msg_header[3] == 'P')
        {
            //determine message size
            int msg_size = Messages.getMsgSize( msg_header );

            if( msg_size < 0 )
            {
                Debug.output( 1, "ERROR: Negative GIOP message size: " + 
                              msg_size );
                Debug.output( 3, "TCP_IP_GIOPTransport.getMessage()",
                              msg_header, 0, read );

                return null;
            }

            //get a large enough buffer from the pool
            byte[] inbuf = buff_mg.getBuffer( msg_size +
                                              Messages.MSG_HEADER_SIZE );

            //copy header
            System.arraycopy( msg_header, 0, inbuf, 0, Messages.MSG_HEADER_SIZE );

            //read "body"
            read = readToBuffer( inbuf, Messages.MSG_HEADER_SIZE, msg_size );

            if( read == -1 )
            {
                //stream ended too early
                return null;
            }

            if( read != msg_size )
            {
                Debug.output( 1, "ERROR: Failed to read GIOP message" );
                Debug.output( 1, (msg_size - read) +
                              " Bytes less than the expected " +
                              msg_size + " Bytes" );
                Debug.output( 3, "TCP_IP_GIOPTransport.getMessage()",
                              inbuf, 0, read );

                return null;
            }

            if( dump_incoming )
            {
                Debug.output( 1, "getMessage()", inbuf, 0, read + Messages.MSG_HEADER_SIZE );
            }

            if( statistics_provider != null )
            {
                statistics_provider.messageReceived( msg_size +
                                                     Messages.MSG_HEADER_SIZE );
            }

            //this is the "good" exit point. 
            return inbuf;
        }
        else
        {
            Debug.output( 1, "ERROR: Failed to read GIOP message" );
            Debug.output( 1, "Magic start doesn't match" );
            Debug.output( 3, "TCP_IP_GIOPTransport.getMessage()",
                          msg_header );

            return null;
        }
    }

    public void write( byte[] message,
                       int start,
                       int size )
        throws IOException
    {
        connect();
        
        out_stream.write( message, start, size );

        if( b_out != null )
        {
            b_out.write( message, start, size );
        }

        if( statistics_provider != null )
        {
            statistics_provider.messageChunkSent( size );
        }
    }


    public void flush()
        throws IOException
    {
        if( b_out != null )
        {
            byte[] b = b_out.toByteArray();

            Debug.output( 1, "sendMessages()", b );

            b_out.reset();
        }

        out_stream.flush();

        if( statistics_provider != null )
        {
            statistics_provider.flushed();
        }
    }

    /**
     * Get the statistics provider for transport usage statistics.
     */
    public StatisticsProvider getStatisticsProvider()
    {
        return statistics_provider;
    }

    public void setTransportListener( TransportListener transport_listener )
    {
       this.transport_listener = transport_listener;
    }

    /**
     * This is used to tell the transport that a CloseConnection has
     * been sent, and that it should set a timeout in case the client
     * doesn't close its side of the connection right away.
     *
     * This should only be called on the thread that listens on the
     * socket because timeouts are not applied until read() is called
     * the next time.  
     */
    public void turnOnFinalTimeout()
    {
        if( socket != null )
        {
            try
            {
                socket.setSoTimeout( finalTimeout );
            }
            catch( SocketException se )
            {
                Debug.output( 2, se );
            }
        }
    }

}
// TCP_IP_Transport

