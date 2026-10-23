package com.mineradio.android.playback;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSpec;
import com.mineradio.android.MediaPolicy;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.List;
import java.util.Map;

/** HTTP transport confined to official public CDNs; no cookies, auth headers or arbitrary redirects. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class SafeMediaDataSource extends BaseDataSource {
    static final class HttpFailure extends IOException {
        final int status;
        HttpFailure(int status) { super("MEDIA_HTTP_"+status); this.status=status; }
    }
    private HttpURLConnection connection;
    private InputStream input;
    private Uri reportedUri;
    private long remaining;
    private boolean opened;
    private Map<String,List<String>> headers=java.util.Collections.emptyMap();
    interface ConnectionFactory { HttpURLConnection open(String uri,String range)throws Exception; }
    private final ConnectionFactory connections;

    SafeMediaDataSource() { this(MediaPolicy::open); }
    SafeMediaDataSource(ConnectionFactory connections) { super(true);this.connections=connections; }
    @Override public long open(DataSpec spec) throws IOException {
        transferInitializing(spec);
        // Never honor arbitrary request headers, POST data, credentials or a CookieHandler.
        if(spec.httpMethod!=DataSpec.HTTP_METHOD_GET) throw new IOException("MEDIA_METHOD_REJECTED");
        if(java.net.CookieHandler.getDefault()!=null) throw new IOException("MEDIA_GLOBAL_COOKIE_HANDLER_REJECTED");
        try {
            String range = spec.position==0 && spec.length==C.LENGTH_UNSET ? null : "bytes="+spec.position+"-"
                    +(spec.length==C.LENGTH_UNSET ? "" : spec.position+spec.length-1);
            connection = connections.open(spec.uri.toString(),range);
            int status=connection.getResponseCode();
            if(status<200 || status>299) throw new HttpFailure(status);
            String contentType=connection.getContentType();
            if(contentType!=null){
                String mime=contentType.split(";",2)[0].trim().toLowerCase(java.util.Locale.ROOT);
                if(mime.startsWith("text/")||mime.contains("json")||mime.contains("xml"))
                    throw new IOException("MEDIA_RESPONSE_NOT_AUDIO");
                if(mime.matches("[a-z0-9.+-]+/[a-z0-9.+-]+"))
                    headers=java.util.Collections.singletonMap("Content-Type",java.util.Collections.singletonList(mime));
            }
            if(status==206){
                String contentRange=connection.getHeaderField("Content-Range");
                if(contentRange==null||!contentRange.startsWith("bytes "+spec.position+"-"))
                    throw new IOException("MEDIA_INVALID_RANGE");
            }
            input=connection.getInputStream();
            remaining=spec.length;
            if(remaining==C.LENGTH_UNSET) {
                long length=connection.getContentLengthLong();
                remaining=length<0?C.LENGTH_UNSET:length-(status==200?spec.position:0);
            }
            if(status==200 && spec.position>0) {
                long skip=spec.position; byte[] buffer=new byte[8192];
                while(skip>0){ if(Thread.currentThread().isInterrupted()) throw new IOException("MEDIA_CANCELLED");
                    int read=input.read(buffer,0,(int)Math.min(skip,buffer.length));
                    if(read<0)throw new EOFException("MEDIA_RANGE_UNAVAILABLE");skip-=read;
                }
            }
            // Keep signed URLs out of analytics / debug events. The original item has a stable pseudo URI.
            reportedUri=Uri.parse("mineradio://cdn"); opened=true; transferStarted(spec);
            return remaining;
        } catch(IOException e) {
            close();if(e instanceof HttpFailure)throw e;
            if(e instanceof java.net.SocketTimeoutException)throw new IOException("MEDIA_NETWORK_TIMEOUT");
            if(e instanceof java.net.UnknownHostException)throw new IOException("MEDIA_DNS_FAILED");
            if(e instanceof javax.net.ssl.SSLException)throw new IOException("MEDIA_TLS_FAILED");
            String code=e.getMessage();throw new IOException(code!=null&&code.matches("MEDIA_[A-Z_]{1,60}")?code:"MEDIA_CONNECTION_FAILED");
        }
        catch(Exception e) { close(); throw new IOException("MEDIA_CONNECTION_FAILED"); }
    }
    @Override public int read(byte[] buffer,int offset,int length)throws IOException {
        if(length==0)return 0; if(remaining==0)return C.RESULT_END_OF_INPUT;
        if(Thread.currentThread().isInterrupted())throw new IOException("MEDIA_CANCELLED");
        try {
            int read=input.read(buffer,offset,remaining==C.LENGTH_UNSET?length:(int)Math.min(length,remaining));
            if(read<0){ if(remaining>0)throw new EOFException("MEDIA_TRUNCATED");return C.RESULT_END_OF_INPUT; }
            if(remaining!=C.LENGTH_UNSET)remaining-=read;bytesTransferred(read);return read;
        } catch(IOException failure) {
            if(failure instanceof java.net.SocketTimeoutException)throw new IOException("MEDIA_NETWORK_TIMEOUT");
            if(failure instanceof EOFException)throw new IOException("MEDIA_TRUNCATED");
            throw new IOException("MEDIA_READ_FAILED");
        }
    }
    @Override public Uri getUri(){return reportedUri;}
    @Override public Map<String,List<String>> getResponseHeaders(){return headers;}
    @Override public void close() {
        if(input!=null)try{input.close();}catch(IOException ignored){} input=null;
        if(connection!=null)connection.disconnect();connection=null;reportedUri=null;
        headers=java.util.Collections.emptyMap();
        if(opened){opened=false;transferEnded();}
    }
}
