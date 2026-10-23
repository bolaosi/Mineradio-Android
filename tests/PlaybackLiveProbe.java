import com.mineradio.android.MediaPolicy;
import com.mineradio.android.music.*;
import org.json.*;
import java.net.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;

/** Explicit account-free real API/CDN probe. Never serialize URLs, credentials or raw exceptions. */
public final class PlaybackLiveProbe {
    public static void main(String[] args) throws Exception {
        Path dir=Path.of(args[0]);Files.createDirectories(dir);
        HttpTransport http=new HttpTransport();
        MusicApi api=new MusicApi(new MusicApi.Store(){
            public String get(String key){return null;}
            public void put(String key,String value){throw new IllegalStateException("NO_CREDENTIAL_WRITES");}
        },new NeteaseClient(http),http);
        JSONArray records=new JSONArray();
        for(String provider:new String[]{"netease","qq"}) for(String keyword:new String[]{"Synergy Infraction","晴天","小星星"}) {
            String prefix=provider.equals("qq")?"/api/qq":"/api";
            MusicApi.ApiResponse search=api.route(prefix+"/search?limit=2&keywords="+URLEncoder.encode(keyword,"UTF-8"),"GET","");
            JSONArray songs=search.body.optJSONArray("songs");
            if(songs==null||songs.length()==0){records.put(new JSONObject().put("provider",provider).put("query",keyword).put("searchStatus",search.status).put("songs",0));continue;}
            for(int i=0;i<Math.min(2,songs.length());i++) {
                JSONObject song=songs.getJSONObject(i);
                JSONObject record=new JSONObject().put("provider",provider).put("query",keyword).put("name",song.optString("name"))
                    .put("artist",song.optString("artist")).put("id",song.optString("id"));records.put(record);
                String path=prefix+"/song/url?quality=standard&"+(provider.equals("qq")?"mid="+song.optString("mid")+"&mediaMid="+song.optString("mediaMid"):"id="+song.optString("id"));
                MusicApi.ApiResponse play=api.route(path,"GET","");
                record.put("apiStatus",play.status).put("playable",play.body.optBoolean("playable")).put("trial",play.body.optBoolean("trial"));
                String url=play.body.optString("url");
                if(url.isEmpty()){record.put("errorCode",play.body.optString("error","URL_NOT_RETURNED"));continue;}
                if(args.length>1&&args[1].equals("--https"))url=url.replaceFirst("^http:","https:");
                String filename="sample-"+records.length()+".audio";
                HttpURLConnection connection=null;
                try{
                    URI parsed=URI.create(url);record.put("scheme",parsed.getScheme()).put("host",parsed.getHost());
                    MediaPolicy.validate(url);
                    connection=MediaPolicy.open(url,"bytes=0-4095");
                    int status=connection.getResponseCode();record.put("rangeStatus",status).put("contentType",connection.getContentType());
                    record.put("rangeHeaderValid",Optional.ofNullable(connection.getHeaderField("Content-Range")).orElse("").startsWith("bytes 0-"));
                    if(status!=200&&status!=206)continue;
                    byte[] head;try(InputStream input=connection.getInputStream()){head=input.readNBytes(4096);}
                    record.put("headBytes",head.length).put("looksLikeErrorText",head.length>0&&(head[0]=='{'||head[0]=='<'));
                    connection.disconnect();connection=MediaPolicy.open(url,null);
                    if(connection.getResponseCode()!=200){record.put("fullStatus",connection.getResponseCode());continue;}
                    long size=0;try(InputStream input=connection.getInputStream();OutputStream out=Files.newOutputStream(dir.resolve(filename))){
                        byte[] buffer=new byte[32768];int count;
                        while((count=input.read(buffer))!=-1){size+=count;if(size>40*1024*1024)throw new IOException("SIZE_LIMIT");out.write(buffer,0,count);}
                    }
                    record.put("file",filename).put("bytes",size);
                    connection.disconnect();connection=MediaPolicy.open(url,"bytes=65536-69631");
                    record.put("seekRangeStatus",connection.getResponseCode());
                    if(connection.getResponseCode()==206){
                        byte[] seek;try(InputStream input=connection.getInputStream()){seek=input.readNBytes(4096);}
                        byte[] original=Files.readAllBytes(dir.resolve(filename));
                        record.put("seekBytesMatchFullFile",Arrays.equals(seek,Arrays.copyOfRange(original,65536,65536+seek.length)));
                    }
                }catch(Exception failure){record.put("transportErrorClass",failure.getClass().getSimpleName());}
                finally{if(connection!=null)connection.disconnect();}
                Files.writeString(dir.resolve("results.json"),new JSONObject().put("realAccount",false).put("records",records).toString(2));
            }
        }
        Files.writeString(dir.resolve("results.json"),new JSONObject().put("realAccount",false).put("records",records).toString(2));
        System.out.println("Account-free API/CDN probe complete; sanitized results saved. records="+records.length());
    }
}
