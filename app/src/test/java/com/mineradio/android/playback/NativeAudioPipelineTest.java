package com.mineradio.android.playback;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor.AudioFormat;
import androidx.media3.datasource.DataSpec;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.*;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.extractor.DefaultExtractorInput;
import androidx.media3.extractor.mp3.Mp3Extractor;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Android framework shadows and actual Media3 classes; not a physical AudioTrack/device test. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, manifest=Config.NONE)
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
public class NativeAudioPipelineTest {
    @Test public void emptyDrainBuffersAreSafeBeforeFirstOutputAndAfterReset()throws Exception{
        SpectrumProcessor processor=new SpectrumProcessor();
        for(boolean visible:new boolean[]{false,true}){
            processor.configure(new AudioFormat(44100,2,C.ENCODING_PCM_16BIT));processor.flush();processor.visible=visible;
            for(int i=0;i<4;i++)processor.queueInput(androidx.media3.common.audio.AudioProcessor.EMPTY_BUFFER);
            processor.queueInput(ByteBuffer.allocateDirect(0));assertFalse(processor.getOutput().hasRemaining());
            processor.queueEndOfStream();assertTrue(processor.isEnded());processor.reset();
        }
    }
    @Test public void emptyInputDoesNotEraseUndrainedPcm()throws Exception{
        SpectrumProcessor processor=new SpectrumProcessor();processor.configure(new AudioFormat(44100,2,C.ENCODING_PCM_16BIT));processor.flush();
        byte[] expected={1,2,3,4};ByteBuffer input=ByteBuffer.allocateDirect(4);input.put(expected).flip();processor.queueInput(input);
        processor.queueInput(androidx.media3.common.audio.AudioProcessor.EMPTY_BUFFER);
        ByteBuffer output=processor.getOutput();byte[] actual=new byte[output.remaining()];output.get(actual);assertArrayEquals(expected,actual);
        processor.queueInput(androidx.media3.common.audio.AudioProcessor.EMPTY_BUFFER);processor.queueEndOfStream();assertTrue(processor.isEnded());
    }
    public static class AudioDocuments extends android.content.ContentProvider {
        File file;
        public boolean onCreate(){return true;}
        public android.content.res.AssetFileDescriptor openAssetFile(Uri uri,String mode)throws FileNotFoundException {
            return new android.content.res.AssetFileDescriptor(android.os.ParcelFileDescriptor.open(file,android.os.ParcelFileDescriptor.MODE_READ_ONLY),0,file.length());
        }
        public String getType(Uri uri){return "audio/mpeg";}
        public android.database.Cursor query(Uri u,String[] p,String s,String[] a,String order){return null;}
        public Uri insert(Uri u,android.content.ContentValues v){throw new UnsupportedOperationException();}
        public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
        public int update(Uri u,android.content.ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
    }
    private static final String CDN="https://m802.music.126.net/test.mp3?vkey=TEST_ONLY";
    private static class Reply extends HttpURLConnection {
        final byte[] bytes;String type="audio/mpeg",range;int status=200;boolean closed;
        Reply(byte[] bytes)throws Exception{super(new URL(CDN));this.bytes=bytes;}
        public int getResponseCode(){return status;}
        public String getContentType(){return type;}
        public String getHeaderField(String name){return "Content-Range".equals(name)?range:null;}
        public long getContentLengthLong(){return bytes.length;}
        public InputStream getInputStream(){return new ByteArrayInputStream(bytes);}
        public void disconnect(){closed=true;}public boolean usingProxy(){return false;}public void connect(){}
    }
    @Test public void realMp3SniffsThroughTransportWithPrivateReportedUri() throws Exception {
        byte[] bytes;try(InputStream in=getClass().getResourceAsStream("/tone.mp3")){bytes=in.readAllBytes();}
        Reply reply=new Reply(bytes);SafeMediaDataSource transport=new SafeMediaDataSource((url,range)->reply);
        try{
            long length=transport.open(new DataSpec(Uri.parse(CDN)));
            assertTrue(new Mp3Extractor().sniff(new DefaultExtractorInput(transport,0,length)));
            assertEquals("mineradio://cdn",transport.getUri().toString());
            assertEquals(1,transport.getResponseHeaders().size());
            assertEquals("audio/mpeg",transport.getResponseHeaders().get("Content-Type").get(0));
        }finally{transport.close();}assertTrue(reply.closed);
    }
    @Test public void rangeAndIgnoredRangeBothReturnCorrectBytes() throws Exception {
        byte[] bytes=new byte[200];for(int i=0;i<bytes.length;i++)bytes[i]=(byte)i;
        Reply full=new Reply(bytes);
        SafeMediaDataSource transport=new SafeMediaDataSource((url,range)->{assertEquals("bytes=100-109",range);return full;});
        try{
            assertEquals(10,transport.open(new DataSpec.Builder().setUri(CDN).setPosition(100).setLength(10).build()));
            byte[] actual=new byte[10];assertEquals(10,transport.read(actual,0,10));assertArrayEquals(java.util.Arrays.copyOfRange(bytes,100,110),actual);
        }finally{transport.close();}
        Reply partial=new Reply(java.util.Arrays.copyOfRange(bytes,100,110));partial.status=206;partial.range="bytes 100-109/200";
        transport=new SafeMediaDataSource((url,range)->partial);
        try{transport.open(new DataSpec.Builder().setUri(CDN).setPosition(100).setLength(10).build());byte[] actual=new byte[10];transport.read(actual,0,10);assertEquals(100,actual[0]);}
        finally{transport.close();}
    }
    @Test public void rejectsErrorPageAndBadRangeAndClassifiesTimeout()throws Exception {
        for(String type:new String[]{"text/html","application/json"}){
            Reply reply=new Reply("not audio".getBytes());reply.type=type;
            SafeMediaDataSource source=new SafeMediaDataSource((url,range)->reply);
            try{source.open(new DataSpec(Uri.parse(CDN)));fail();}catch(IOException error){assertEquals("MEDIA_RESPONSE_NOT_AUDIO",error.getMessage());}
            assertTrue(reply.closed);
        }
        Reply bad=new Reply(new byte[10]);bad.status=206;bad.range="bytes 0-9/200";
        SafeMediaDataSource source=new SafeMediaDataSource((url,range)->bad);
        try{source.open(new DataSpec.Builder().setUri(CDN).setPosition(100).build());fail();}
        catch(IOException error){assertEquals("MEDIA_INVALID_RANGE",error.getMessage());}
        source=new SafeMediaDataSource((url,range)->{throw new java.net.SocketTimeoutException("https://private.invalid/?token=secret");});
        try{source.open(new DataSpec(Uri.parse(CDN)));fail();}catch(IOException error){assertEquals("MEDIA_NETWORK_TIMEOUT",error.getMessage());assertNull(error.getCause());}
    }
    @Test public void contentUriUsesContentResolverAndSniffsActualLocalMp3()throws Exception{
        Uri uri=Uri.parse("content://test.documents/audio/fixture");
        AudioDocuments provider=new AudioDocuments();
        provider.file=File.createTempFile("local-audio-",".mp3",RuntimeEnvironment.getApplication().getCacheDir());
        try(InputStream input=getClass().getResourceAsStream("/tone.mp3");OutputStream output=new FileOutputStream(provider.file)){input.transferTo(output);}
        org.robolectric.android.controller.ContentProviderController<AudioDocuments> documents=
                org.robolectric.android.controller.ContentProviderController.of(provider).create("test.documents");
        DefaultDataSource source=new DefaultDataSource.Factory(RuntimeEnvironment.getApplication(),
                ()->new SafeMediaDataSource((url,range)->{throw new AssertionError("Local audio must never use HTTP");})).createDataSource();
        try{long length=source.open(new DataSpec(uri));assertTrue(new Mp3Extractor().sniff(new DefaultExtractorInput(source,0,length)));}
        finally{source.close();documents.shutdown();assertTrue(provider.file.delete());}
    }
    @Test public void pcmProcessorPassesSamplesWithoutChangingThem() throws Exception {
        SpectrumProcessor processor=new SpectrumProcessor();
        processor.configure(new AudioFormat(44100,2,C.ENCODING_PCM_16BIT));processor.flush();
        byte[] bytes=new byte[8192];for(int i=0;i<bytes.length;i++)bytes[i]=(byte)i;
        processor.visible=true;
        ByteBuffer source=ByteBuffer.allocateDirect(bytes.length).order(ByteOrder.nativeOrder());source.put(bytes).flip();
        processor.queueInput(source);ByteBuffer output=processor.getOutput();byte[] actual=new byte[output.remaining()];output.get(actual);
        assertArrayEquals(bytes,actual);assertFalse(source.hasRemaining());
        processor.queueEndOfStream();assertTrue(processor.isEnded());
    }
}
