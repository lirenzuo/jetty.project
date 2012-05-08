// ========================================================================
// Copyright (c) 2004-2011 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.util.List;

import javax.servlet.DispatcherType;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.continuation.ContinuationThrowable;
import org.eclipse.jetty.http.HttpContent;
import org.eclipse.jetty.http.HttpException;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.io.UncheckedPrintWriter;
import org.eclipse.jetty.util.ArrayQueue;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.thread.Timeout.Task;

/**
 *
 */
public abstract class HttpChannel
{
    private static final Logger LOG = Log.getLogger(HttpChannel.class);

    private static final ThreadLocal<HttpChannel> __currentChannel = new ThreadLocal<HttpChannel>();

    /* ------------------------------------------------------------ */
    public static HttpChannel getCurrentHttpChannel()
    {
        return __currentChannel.get();
    }

    /* ------------------------------------------------------------ */
    protected static void setCurrentHttpChannel(HttpChannel channel)
    {
        __currentChannel.set(channel);
    }
    
    private int _requests;

    private final Server _server;
    private final EndPoint _endp;
    private final HttpURI _uri;

    private final HttpFields _requestFields;
    private final Request _request;
    private final AsyncContinuation _async;

    private final HttpFields _responseFields;
    private final Response _response;
    
    private final ArrayQueue<ByteBuffer> _inputQ=new ArrayQueue<>();
    private boolean _inputEOF;

    private volatile ServletInputStream _in;
    private volatile Output _out;
    private volatile HttpWriter _writer;
    private volatile PrintWriter _printWriter;

    int _include;

    private HttpVersion _version = HttpVersion.HTTP_1_1;

    private boolean _expect = false;
    private boolean _expect100Continue = false;
    private boolean _expect102Processing = false;
    private boolean _host = false;

    
    private final RequestHandler _handler = new RequestHandler();
    private final HttpGenerator.ResponseInfo _info = new Info();
    

    /* ------------------------------------------------------------ */
    /** Constructor
     *
     */
    public HttpChannel(Server server,EndPoint endp)
    {
        _server = server;
        _endp = endp;
        _uri = new HttpURI(URIUtil.__CHARSET);
        _requestFields = new HttpFields();
        _responseFields = new HttpFields(server.getMaxCookieVersion());
        _request = new Request(this);
        _response = new Response(this);
        _async = _request.getAsyncContinuation();
    }
   
    /* ------------------------------------------------------------ */
    public HttpParser.RequestHandler getRequestHandler()
    {
        return _handler;
    }

    /* ------------------------------------------------------------ */
    public HttpGenerator.ResponseInfo getResponseInfo()
    {
        return _info;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @return the number of requests handled by this connection
     */
    public int getRequests()
    {
        return _requests;
    }

    /* ------------------------------------------------------------ */
    public Server getServer()
    {
        return _server;
    }
    
    /* ------------------------------------------------------------ */
    public AsyncContinuation getAsyncContinuation()
    {
        return _async;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the requestFields.
     */
    public HttpFields getRequestFields()
    {
        return _requestFields;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the responseFields.
     */
    public HttpFields getResponseFields()
    {
        return _responseFields;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the request.
     */
    public Request getRequest()
    {
        return _request;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the response.
     */
    public Response getResponse()
    {
        return _response;
    }

    /* ------------------------------------------------------------ */
    public InetSocketAddress getLocalAddress()
    {
        return _endp.getLocalAddress();
    }

    /* ------------------------------------------------------------ */
    public InetSocketAddress getRemoteAddress()
    {
        return _endp.getRemoteAddress();
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Get the inputStream from the connection.
     * <p>
     * If the associated response has the Expect header set to 100 Continue,
     * then accessing the input stream indicates that the handler/servlet
     * is ready for the request body and thus a 100 Continue response is sent.
     *
     * @return The input stream for this connection.
     * The stream will be created if it does not already exist.
     */
    public ServletInputStream getInputStream() throws IOException
    {
        // If the client is expecting 100 CONTINUE, then send it now.
        if (_expect100Continue)
        {
            // is content missing?
            if (available()==0)
            {
                if (isResponseCommitted())
                    throw new IllegalStateException("Committed before 100 Continues");

                send1xx(HttpStatus.CONTINUE_100);
            }
            _expect100Continue=false;
        }

        if (_in == null)
            _in = new HttpInput(HttpChannel.this);
        return _in;
    }


    /* ------------------------------------------------------------ */
    /**
     * @return The output stream for this connection. The stream will be created if it does not already exist.
     */
    public HttpOutput getOutputStream()
    {
        if (_out == null)
            _out = new Output();
        return _out;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return A {@link PrintWriter} wrapping the {@link #getOutputStream output stream}. The writer is created if it
     *    does not already exist.
     */
    public PrintWriter getPrintWriter(String charset)
    {
        getOutputStream();
        if (_writer==null)
        {
            _writer=new HttpWriter(_out);
            if (_server.isUncheckedPrintWriter())
                _printWriter=new UncheckedPrintWriter(_writer);
            else
                _printWriter = new PrintWriter(_writer)
                {
                    public void close()
                    {
                        synchronized (lock)
                        {
                            try
                            {
                                out.close();
                            }
                            catch (IOException e)
                            {
                                setError();
                            }
                        }
                    }
                };

        }
        _writer.setCharacterEncoding(charset);
        return _printWriter;
    }

    /* ------------------------------------------------------------ */
    public void reset()
    {
        _requestFields.clear();
        _request.recycle();
        _responseFields.clear();
        _response.recycle();
        _uri.clear();
        synchronized (_inputQ)
        {
            _inputEOF=false;
            _inputQ.clear();
        }
    }

    /* ------------------------------------------------------------ */
    protected void handleRequest() throws IOException
    {
        boolean error = false;

        String threadName=null;
        Throwable async_exception=null;
        try
        {
            if (LOG.isDebugEnabled())
            {
                threadName=Thread.currentThread().getName();
                Thread.currentThread().setName(threadName+" - "+_uri);
            }


            // Loop here to handle async request redispatches.
            // The loop is controlled by the call to async.unhandle in the
            // finally block below.  If call is from a non-blocking connector,
            // then the unhandle will return false only if an async dispatch has
            // already happened when unhandle is called.   For a blocking connector,
            // the wait for the asynchronous dispatch or timeout actually happens
            // within the call to unhandle().

            final Server server=_server;
            boolean handling=_async.handling() && server!=null && server.isRunning();
            while (handling)
            {
                _request.setHandled(false);

                String info=null;
                try
                {
                    _uri.getPort();
                    info=URIUtil.canonicalPath(_uri.getDecodedPath());
                    if (info==null && !_request.getMethod().equals(HttpMethod.CONNECT))
                        throw new HttpException(400);
                    _request.setPathInfo(info);

                    if (_out!=null)
                        _out.reopen();

                    if (_async.isInitial())
                    {
                        _request.setDispatcherType(DispatcherType.REQUEST);
                        getConnector().customize(_request);
                        server.handle(this);
                    }
                    else
                    {
                        _request.setDispatcherType(DispatcherType.ASYNC);
                        server.handleAsync(this);
                    }
                }
                catch (ContinuationThrowable e)
                {
                    LOG.ignore(e);
                }
                catch (EofException e)
                {
                    async_exception=e;
                    LOG.debug(e);
                    error=true;
                    _request.setHandled(true);
                }
                catch (RuntimeIOException e)
                {
                    async_exception=e;
                    LOG.debug(e);
                    error=true;
                    _request.setHandled(true);
                }
                catch (HttpException e)
                {
                    LOG.debug(e);
                    error=true;
                    _request.setHandled(true);
                    _response.sendError(e.getStatus(), e.getReason());
                }
                catch (Throwable e)
                {
                    async_exception=e;
                    LOG.warn(String.valueOf(_uri),e);
                    error=true;
                    _request.setHandled(true);
                    sendError(info==null?400:500, null, null, true);
                }
                finally
                {
                    handling = !_async.unhandle() && server.isRunning() && _server!=null;
                }
            }
        }
        finally
        {
            if (threadName!=null)
                Thread.currentThread().setName(threadName);

            if (_async.isUncompleted())
            {
                _async.doComplete(async_exception);

                if (_expect100Continue)
                {
                    LOG.debug("100 continues not sent");
                    // We didn't send 100 continues, but the latest interpretation
                    // of the spec (see httpbis) is that the client will either
                    // send the body anyway, or close.  So we no longer need to
                    // do anything special here other than make the connection not persistent
                    _expect100Continue = false;
                    if (!_response.isCommitted())
                        _response.addHeader(HttpHeader.CONNECTION,HttpHeaderValue.CLOSE.toString());
                }

                if (!error && !_response.isCommitted() && !_request.isHandled())
                    _response.sendError(HttpServletResponse.SC_NOT_FOUND);

                _response.complete();
                _request.setHandled(true);
            }
        }
    }


    /* ------------------------------------------------------------ */
    public boolean isIncluding()
    {
        return _include>0;
    }

    /* ------------------------------------------------------------ */
    public void include()
    {
        _include++;
    }

    /* ------------------------------------------------------------ */
    public void included()
    {
        _include--;
        if (_out!=null)
            _out.reopen();
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.io.AsyncConnection#isSuspended()
     */
    public boolean isSuspended()
    {
        return _request.getAsyncContinuation().isSuspended();
    }

    /* ------------------------------------------------------------ */
    public void onClose()
    {
        LOG.debug("closed {}",this);
    }

    /* ------------------------------------------------------------ */
    public boolean isExpecting100Continues()
    {
        return _expect100Continue;
    }

    /* ------------------------------------------------------------ */
    public boolean isExpecting102Processing()
    {
        return _expect102Processing;
    }

    /* ------------------------------------------------------------ */
    /* 
     * @see java.io.InputStream#read(byte[], int, int)
     */
    public int read(byte[] b, int off, int len) throws IOException
    {
        synchronized (_inputQ.lock())
        {
            ByteBuffer content=null;            
            while(content==null)
            {
                content=_inputQ.peekUnsafe();
                while (content!=null && !content.hasRemaining())
                {
                    _inputQ.pollUnsafe();
                    content=_inputQ.peekUnsafe();
                }

                if (content==null)
                {
                    // check for EOF
                    if (_inputEOF)
                        return -1;
                    
                    blockForContent();
                }
            }

            int l=Math.min(len,content.remaining());
            content.get(b,off,l);
            return l;
        }
        
    }

    /* ------------------------------------------------------------ */
    public int available() throws IOException
    {
        synchronized (_inputQ.lock())
        {
            ByteBuffer content=_inputQ.peekUnsafe();
            if (content==null)
                return 0;

            return content.remaining();
        }
        
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public String toString()
    {
        return String.format("%s r=%d",
                super.toString(),
                _requests);
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    private final class Info implements HttpGenerator.ResponseInfo
    {
        @Override
        public HttpVersion getHttpVersion()
        {
            return getRequest().getHttpVersion();
        }

        @Override
        public HttpFields getHttpFields()
        {
            return _responseFields;
        }

        @Override
        public long getContentLength()
        {
            return _response.getLongContentLength();
        }

        @Override
        public boolean isHead()
        {
            return getRequest().isHead();
        }

        @Override
        public int getStatus()
        {
            return _response.getStatus();
        }

        @Override
        public String getReason()
        {
            return _response.getReason();
        }
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    private class RequestHandler implements HttpParser.RequestHandler
    {
        @Override
        public boolean startRequest(String method, String uri, String version) throws IOException
        {
            _host = false;
            _expect = false;
            _expect100Continue=false;
            _expect102Processing=false;

            if(_request.getTimeStamp()==0)
                _request.setTimeStamp(System.currentTimeMillis());
            HttpMethod m = HttpMethod.CACHE.get(method);
            _request.setMethod(m,method);

            try
            {
                if (m==HttpMethod.CONNECT)
                    _uri.parseConnect(uri);
                else
                    _uri.parse(uri);

                _request.setUri(_uri);

                if (version==null)
                {
                    _request.setHttpVersion(HttpVersion.HTTP_0_9);
                    _version=HttpVersion.HTTP_0_9;
                }
                else
                {
                    _version= HttpVersion.CACHE.get(version);
                    if (_version==null)
                        throw new HttpException(HttpStatus.BAD_REQUEST_400,null);
                    _request.setHttpVersion(_version);
                }
            }
            catch (Exception e)
            {
                LOG.debug(e);
                if (e instanceof HttpException)
                    throw (HttpException)e;
                throw new HttpException(HttpStatus.BAD_REQUEST_400,null,e);
            }
            
            return false;
        }

        @Override
        public boolean parsedHeader(HttpHeader header, String name, String value) throws IOException
        {
            switch (header)
            {
                case HOST:
                    // TODO check if host matched a host in the URI.
                    _host = true;
                    break;

                case EXPECT:
                    HttpHeaderValue expect = HttpHeaderValue.CACHE.get(value);
                    switch(expect==null?HttpHeaderValue.UNKNOWN:expect)
                    {
                        case CONTINUE:
                            _expect100Continue=true;
                            break;

                        case PROCESSING:
                            _expect102Processing=true;
                            break;

                        default:
                            String[] values = value.toString().split(",");
                            for  (int i=0;values!=null && i<values.length;i++)
                            {
                                expect=HttpHeaderValue.CACHE.get(values[i].trim());
                                if (expect==null)
                                    _expect=true;
                                else
                                {
                                    switch(expect)
                                    {
                                        case CONTINUE:
                                            _expect100Continue=true;
                                            break;
                                        case PROCESSING:
                                            _expect102Processing=true;
                                            break;
                                        default:
                                            _expect=true;
                                    }
                                }
                            }
                    }
                    break;

                case CONTENT_TYPE:
                    MimeTypes.Type mime=MimeTypes.CACHE.get(value);
                    String charset=(mime==null)?MimeTypes.getCharsetFromContentType(value):mime.getCharset().toString();
                    if (charset!=null)
                        _request.setCharacterEncodingUnchecked(charset);
                    break;
            }

            _requestFields.add(name, value);
            return false;
        }

        @Override
        public boolean headerComplete(boolean hasBody,boolean persistent) throws IOException
        {
            _requests++;
            switch (_version)
            {
                case HTTP_0_9:
                    break;
                case HTTP_1_0:
                    if (persistent)
                        _responseFields.add(HttpHeader.CONNECTION,HttpHeaderValue.KEEP_ALIVE);

                    if (_server.getSendDateHeader())
                        _responseFields.putDateField(HttpHeader.DATE.toString(),_request.getTimeStamp());
                    break;

                case HTTP_1_1:

                    if (!persistent)
                        _responseFields.add(HttpHeader.CONNECTION,HttpHeaderValue.CLOSE);
                    
                    if (_server.getSendDateHeader())
                        _responseFields.putDateField(HttpHeader.DATE.toString(),_request.getTimeStamp());

                    if (!_host)
                    {
                        LOG.debug("!host {}",this);
                        _responseFields.put(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE);
                        sendError(HttpStatus.BAD_REQUEST_400,null,null,true);
                        return true;
                    }

                    if (_expect)
                    {
                        LOG.debug("!expectation {}",this);
                        _responseFields.put(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE);
                        sendError(HttpStatus.EXPECTATION_FAILED_417,null,null,true);
                        return true;
                    }

                    break;
                default:
            }

            // Either handle now or wait for first content
            if (_expect100Continue)
                return true;
            
            return false;
        }
        
        @Override
        public boolean content(ByteBuffer ref) throws IOException
        {
            _inputQ.add(ref);
            return true;
        }

        @Override
        public boolean messageComplete(long contentLength) throws IOException
        {
            synchronized (_inputQ.lock())
            {
                _inputEOF=true;
            }
            return true;
        }

        @Override
        public boolean earlyEOF()
        {
            return true;
        }

    }



    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    public class Output extends HttpOutput
    {
        Output()
        {
            super(HttpChannel.this);
        }

        /* ------------------------------------------------------------ */
        /*
         * @see javax.servlet.ServletOutputStream#print(java.lang.String)
         */
        @Override
        public void print(String s) throws IOException
        {
            if (isClosed())
                throw new IOException("Closed");
            PrintWriter writer=getPrintWriter(null);
            writer.print(s);
        }


        /* ------------------------------------------------------------ */
        public void sendContent(Object content) throws IOException
        {
            Resource resource=null;

            if (isClosed())
                throw new IOException("Closed");

            // Convert HTTP content to contentl
            if (content instanceof HttpContent)
            {
                HttpContent httpContent = (HttpContent) content;
                String contentType = httpContent.getContentType();
                if (contentType != null)
                    _responseFields.put(HttpHeader.CONTENT_TYPE, contentType);
                    
                if (httpContent.getContentLength() > 0)
                    _responseFields.putLongField(HttpHeader.CONTENT_LENGTH, httpContent.getContentLength());
                
                String lm = httpContent.getLastModified();
                if (lm != null)
                    _responseFields.put(HttpHeader.LAST_MODIFIED, lm);
                else if (httpContent.getResource()!=null)
                {
                    long lml=httpContent.getResource().lastModified();
                    if (lml!=-1)
                        _responseFields.putDateField(HttpHeader.LAST_MODIFIED, lml);
                }

                content = httpContent.getDirectBuffer();
                if (content==null)
                    content=httpContent.getIndirectBuffer();
                if (content==null)
                    content=httpContent.getInputStream();
            }
            else if (content instanceof Resource)
            {
                resource=(Resource)content;
                _responseFields.putDateField(HttpHeader.LAST_MODIFIED, resource.lastModified());
                content=resource.getInputStream();
            }

            // Process content.
            if (content instanceof ByteBuffer)
            {
                send((ByteBuffer)content);
            }
            else if (content instanceof InputStream)
            {
                throw new IllegalArgumentException("not implemented!");
            }
            else
                throw new IllegalArgumentException("unknown content type?");


        }
    }
    
    

    public abstract Connector getConnector();

    public abstract long getMaxIdleTime();
    
    public abstract void asyncDispatch();
    
    public abstract void scheduleTimeout(Task timeout, long timeoutMs);

    public abstract void cancelTimeout(Task timeout);

    protected abstract void blockForContent() throws IOException;
    
    protected abstract int write(ByteBuffer content) throws IOException;
    
    protected abstract void send(ByteBuffer content) throws IOException;
        
    protected abstract void sendError(int status, String reason, String content, boolean close)  throws IOException;
    
    protected abstract void send1xx(int processing102);
    
    protected abstract int getContentBufferSize();

    protected abstract void increaseContentBufferSize(int size);

    protected abstract void resetBuffer();

    protected abstract boolean isResponseCommitted();
    
    protected abstract void flushResponse() throws IOException;

    protected abstract void completeResponse() throws IOException;
    

}
