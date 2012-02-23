/*******************************************************************************
 * Copyright (c) 2011 Intalio, Inc.
 * ======================================================================
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 *
 *   The Eclipse Public License is available at
 *   http://www.eclipse.org/legal/epl-v10.html
 *
 *   The Apache License v2.0 is available at
 *   http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 *******************************************************************************/
// ========================================================================
// Copyright (c) 2010 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses.
// ========================================================================

package org.eclipse.jetty.websocket;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpException;
import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.io.ConnectedEndPoint;
import org.eclipse.jetty.server.AbstractHttpConnection;
import org.eclipse.jetty.server.BlockingHttpConnection;
import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.extensions.Extension;
import org.eclipse.jetty.websocket.extensions.ExtensionManager;
import org.eclipse.jetty.websocket.extensions.ServerExtension;

/**
 * Factory to create WebSocket connections
 */
public class WebSocketFactory extends AbstractLifeCycle
{
    private static final Logger LOG = Log.getLogger(WebSocketFactory.class);
    private final Queue<WebSocketServletConnection> connections = new ConcurrentLinkedQueue<WebSocketServletConnection>();

    public interface Acceptor
    {
        /* ------------------------------------------------------------ */
        /**
         * <p>
         * Factory method that applications needs to implement to return a {@link WebSocket} object.
         * </p>
         * 
         * @param request
         *            the incoming HTTP upgrade request
         * @param protocol
         *            the websocket sub protocol
         * @return a new {@link WebSocket} object that will handle websocket events.
         */
        WebSocket doWebSocketConnect(HttpServletRequest request, String protocol);

        /* ------------------------------------------------------------ */
        /**
         * <p>
         * Checks the origin of an incoming WebSocket handshake request.
         * </p>
         * 
         * @param request
         *            the incoming HTTP upgrade request
         * @param origin
         *            the origin URI
         * @return boolean to indicate that the origin is acceptable.
         */
        boolean checkOrigin(HttpServletRequest request, String origin);
    }

    private final Acceptor _acceptor;
    private final ExtensionManager _extensionManager = new ExtensionManager();
    private WebSocketBuffers _buffers;
    private int _maxIdleTime = 300000;
    private int _maxTextMessageSize = 16 * 1024;
    private int _maxBinaryMessageSize = -1;

    public WebSocketFactory(Acceptor acceptor)
    {
        this(acceptor,64 * 1024);
    }

    public WebSocketFactory(Acceptor acceptor, int bufferSize)
    {
        _buffers = new WebSocketBuffers(bufferSize);
        _acceptor = acceptor;
    }

    public Acceptor getAcceptor()
    {
        return _acceptor;
    }

    /**
     * @return The Registered Extensions Manager
     */
    public ExtensionManager getExtensionManager()
    {
        return _extensionManager;
    }

    /**
     * Get the maxIdleTime.
     * 
     * @return the maxIdleTime
     */
    public long getMaxIdleTime()
    {
        return _maxIdleTime;
    }

    /**
     * Set the maxIdleTime.
     * 
     * @param maxIdleTime
     *            the maxIdleTime to set
     */
    public void setMaxIdleTime(int maxIdleTime)
    {
        _maxIdleTime = maxIdleTime;
    }

    /**
     * Get the bufferSize.
     * 
     * @return the bufferSize
     */
    public int getBufferSize()
    {
        return _buffers.getBufferSize();
    }

    /**
     * Set the bufferSize.
     * 
     * @param bufferSize
     *            the bufferSize to set
     */
    public void setBufferSize(int bufferSize)
    {
        if (bufferSize != getBufferSize())
            _buffers = new WebSocketBuffers(bufferSize);
    }

    /**
     * @return The initial maximum text message size (in characters) for a connection
     */
    public int getMaxTextMessageSize()
    {
        return _maxTextMessageSize;
    }

    /**
     * Set the initial maximum text message size for a connection. This can be changed by the application calling
     * {@link WebSocket.Connection#setMaxTextMessageSize(int)}.
     * 
     * @param maxTextMessageSize
     *            The default maximum text message size (in characters) for a connection
     */
    public void setMaxTextMessageSize(int maxTextMessageSize)
    {
        _maxTextMessageSize = maxTextMessageSize;
    }

    /**
     * @return The initial maximum binary message size (in bytes) for a connection
     */
    public int getMaxBinaryMessageSize()
    {
        return _maxBinaryMessageSize;
    }

    /**
     * Set the initial maximum binary message size for a connection. This can be changed by the application calling
     * {@link WebSocket.Connection#setMaxBinaryMessageSize(int)}.
     * 
     * @param maxBinaryMessageSize
     *            The default maximum binary message size (in bytes) for a connection
     */
    public void setMaxBinaryMessageSize(int maxBinaryMessageSize)
    {
        _maxBinaryMessageSize = maxBinaryMessageSize;
    }

    @Override
    protected void doStop() throws Exception
    {
        closeConnections();
    }

    /**
     * Upgrade the request/response to a WebSocket Connection.
     * <p>
     * This method will not normally return, but will instead throw a UpgradeConnectionException, to exit HTTP handling
     * and initiate WebSocket handling of the connection.
     * 
     * @param request
     *            The request to upgrade
     * @param response
     *            The response to upgrade
     * @throws IOException
     *             in case of I/O errors
     */
    public void upgrade(HttpServletRequest request, HttpServletResponse response) throws IOException
    {
        // Upgrade only supports WebSocket protocol
        if (!"websocket".equalsIgnoreCase(request.getHeader("Upgrade")))
        {
            throw new IllegalStateException("!Upgrade:websocket");
        }

        // Upgrade only supported on HTTP/1.1
        if (!"HTTP/1.1".equals(request.getProtocol()))
        {
            throw new IllegalStateException("!HTTP/1.1");
        }

        // RFC 6455 - Sec 11.3.5 - Version
        int version = request.getIntHeader("Sec-WebSocket-Version");
        if (version < 0)
        {
            // Old pre-RFC version specifications (header not present in RFC-6455)
            version = request.getIntHeader("Sec-WebSocket-Draft");
        }

        AbstractHttpConnection http = AbstractHttpConnection.getCurrentConnection();
        if (http instanceof BlockingHttpConnection)
        {
            throw new IllegalStateException("Websockets not supported on blocking connectors");
        }

        ConnectedEndPoint endp = (ConnectedEndPoint)http.getEndPoint();

        // The websocket handler implementation to use
        List<Extension> extensions = new ArrayList<Extension>();

        // Declared / Requested Extensions only exist for websocket versions 7+
        if (version >= 7)
        {
            List<String> extensionsRequested = new ArrayList<String>();
            @SuppressWarnings("unchecked")
            Enumeration<String> e = request.getHeaders("Sec-WebSocket-Extensions");
            while (e.hasMoreElements())
            {
                QuotedStringTokenizer tok = new QuotedStringTokenizer(e.nextElement(),",");
                while (tok.hasMoreTokens())
                {
                    extensionsRequested.add(tok.nextToken());
                }
            }

            extensions.addAll(_extensionManager.initExtensions(extensionsRequested, Extension.Mode.SERVER));
        }
        
        LOG.debug("Extensions: " + extensions);

        // [Server Extensions] Initialize
        for (Extension extension : extensions)
        {
            if (extension instanceof ServerExtension)
            {
                ((ServerExtension)extension).onWebSocketServerFactory(this);
            }
        }

        // WebSocket impl + protocol required for Connection below
        WebSocket websocket = null;
        String protocol = null;

        // Calculate the base websocket + protocol for the physical connection.
        List<String> requestProtocols = getRequestProtocols(request);
        for (String requestProtocol : requestProtocols)
        {
            websocket = _acceptor.doWebSocketConnect(request,requestProtocol);
            if (websocket != null)
            {
                // remember protocol that was used (for later use in connection object)
                protocol = requestProtocol;
                break;
            }
        }

        if (websocket == null)
        {
            LOG.warn("No WebSocket implementation available, on all protocols");
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return;
        }
        
        // [Extensions] Bind WebSocket impl
        for (Extension extension : extensions)
        {
            websocket = extension.bindWebSocket(websocket);
            if (websocket == null)
            {
                throw new IllegalStateException("bindWebSocket()==null:" + extension.getParameterizedName());
            }
        }

        // [Server Extensions] Notify WebSocket Creation
        for (Extension extension : extensions)
        {
            if(extension instanceof ServerExtension)
            {
                ((ServerExtension)extension).onWebSocketCreation(websocket,request,protocol);
            }
        }

        // Establish Connections
        final WebSocketServletConnection connection;
        switch (version)
        {
            case -1: // unspecified draft/version
            case 0: // Old school draft/version
            {
                connection = new WebSocketServletConnectionD00(this,websocket,endp,_buffers,http.getTimeStamp(),_maxIdleTime,protocol);
                break;
            }
            case 1:
            case 2:
            case 3:
            case 4:
            case 5:
            case 6:
            {
                connection = new WebSocketServletConnectionD06(this,websocket,endp,_buffers,http.getTimeStamp(),_maxIdleTime,protocol);
                break;
            }
            case 7:
            case 8:
            {
                connection = new WebSocketServletConnectionD08(this,websocket,endp,_buffers,http.getTimeStamp(),_maxIdleTime,protocol,extensions,version);
                break;
            }
            case WebSocketConnectionRFC6455.VERSION: // RFC 6455 Version
            {
                connection = new WebSocketServletConnectionRFC6455(this,websocket,endp,_buffers,http.getTimeStamp(),_maxIdleTime,protocol,extensions,version);
                break;
            }
            default:
            {
                LOG.warn("Unsupported Websocket version: " + version);
                // Per RFC 6455 - 4.4 - Supporting Multiple Versions of WebSocket Protocol
                // Using the examples as outlined
                response.setHeader("Sec-WebSocket-Version","13, 8, 6, 0");
                throw new HttpException(400,"Unsupported websocket version specification: " + version);
            }
        }

        addConnection(connection);

        // Set the defaults
        connection.getConnection().setMaxBinaryMessageSize(_maxBinaryMessageSize);
        connection.getConnection().setMaxTextMessageSize(_maxTextMessageSize);

        // Let the connection finish processing the handshake
        connection.handshake(request,response,protocol);
        response.flushBuffer();

        // Give the connection any unused data from the HTTP connection.
        connection.fillBuffersFrom(((HttpParser)http.getParser()).getHeaderBuffer());
        connection.fillBuffersFrom(((HttpParser)http.getParser()).getBodyBuffer());

        // Tell jetty about the new connection
        LOG.debug("Websocket upgrade {} {} {} {}",request.getRequestURI(),version,protocol,connection);
        request.setAttribute("org.eclipse.jetty.io.Connection",connection);
    }

    private List<String> getRequestProtocols(HttpServletRequest request)
    {
        List<String> reqProtocols = new ArrayList<String>();
        
        @SuppressWarnings("unchecked")
        Enumeration<String> protocols = request.getHeaders("Sec-WebSocket-Protocol");
        String protocol = null;
        while (protocol == null && protocols != null && protocols.hasMoreElements())
        {
            String candidate = protocols.nextElement();
            for (String p : parseProtocols(candidate))
            {
                reqProtocols.add(p);
            }
        }
        
        // Always add null (no protocol) to end of list.
        reqProtocols.add(null);
        
        return reqProtocols;
    }
    
    protected String[] parseProtocols(String protocol)
    {
        if (protocol == null)
        {
            return new String[]
            { null };
        }
        protocol = protocol.trim();
        if (protocol == null || protocol.length() == 0)
        {
            return new String[]
            { null };
        }
        String[] passed = protocol.split("\\s*,\\s*");
        String[] protocols = new String[passed.length + 1];
        System.arraycopy(passed,0,protocols,0,passed.length);
        return protocols;
    }

    public boolean acceptWebSocket(HttpServletRequest request, HttpServletResponse response) throws IOException
    {
        if ("websocket".equalsIgnoreCase(request.getHeader("Upgrade")))
        {
            String origin = request.getHeader("Origin");
            if (origin == null)
            {
                // TODO: make version specific.

                // 00 - http://tools.ietf.org/html/draft-ietf-hybi-thewebsocketprotocol-00
                //   Introduced as a http response header 
                // 06 - http://tools.ietf.org/html/draft-ietf-hybi-thewebsocketprotocol-06
                //   Removed as response header.
                //   Introduced as request header to prevent unauthorized cross-origin use of websocket.
                // 11 - http://tools.ietf.org/html/draft-ietf-hybi-thewebsocketprotocol-11
                //   Removed. no longer present in spec.
                // RFC6455 - http://tools.ietf.org/html/rfc6455
                //   Not present in final standard.
                origin = request.getHeader("Sec-WebSocket-Origin");
            }

            // Allow WebSocketServlet to determine if origin is ok
            if (!_acceptor.checkOrigin(request,origin))
            {
                response.sendError(HttpServletResponse.SC_FORBIDDEN);
                return false;
            }

            // Send the upgrade
            upgrade(request,response);
            return true;
        }

        return false;
    }

    public int getConnectionCount()
    {
        return connections.size();
    }

    protected Collection<WebSocketServletConnection> getConnections()
    {
        return connections;
    }

    protected boolean addConnection(WebSocketServletConnection connection)
    {
        return isRunning() && connections.add(connection);
    }

    protected boolean removeConnection(WebSocketServletConnection connection)
    {
        return connections.remove(connection);
    }

    protected void closeConnections()
    {
        for (WebSocketServletConnection connection : connections)
            connection.shutdown();
    }
}
