package com.mineradio.android;

public class BackendPolicyTest {
    private static void reject(String base,String path)throws Exception{
        try{if(path==null)BackendPolicy.normalizeBase(base);else BackendPolicy.apiUrl(base,path);}
        catch(Exception expected){return;}throw new AssertionError("Unsafe URL accepted");
    }
    public static void main(String[] args)throws Exception{
        if(!BackendPolicy.normalizeBase("https://example.org/").equals("https://example.org"))throw new AssertionError();
        if(!BackendPolicy.normalizeBase("http://192.168.1.4:3000").startsWith("http:"))throw new AssertionError();
        for(String base:new String[]{"http://example.org","https://u:p@example.org","https://example.org/api","https://example.org?token=x","javascript:alert(1)"})reject(base,null);
        for(String path:new String[]{"//evil.org/api/x","https://evil.org/api/x","/api/../x","/api/%2e%2e/x","/api/x#fragment"})reject("https://example.org",path);
        if(!BackendPolicy.apiUrl("https://example.org","/api/audio?url=https%3A%2F%2Fmusic.example%2Fx").startsWith("https://example.org/api/audio?"))throw new AssertionError();
        System.out.println("BackendPolicy: URL and credential routing checks passed");
    }
}
