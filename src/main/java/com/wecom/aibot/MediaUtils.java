package com.wecom.aibot;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 媒体下载与严格企微 AES-256-CBC 解密；上传兼容入口复用客户端可靠核心。 */
public final class MediaUtils {
    private MediaUtils() {}
    public static class DownloadResult {
        private final byte[] data;
        private final String filename;
        public DownloadResult(byte[] data,String filename) {this.data=data;this.filename=filename;}
        public byte[] getData() {return data;}
        public String getFilename() {return filename;}
    }
    public static DownloadResult downloadFile(String url,String aesKey) throws IOException {
        return downloadFile(url,aesKey,20*1024*1024L+32,30000,60000);
    }
    static DownloadResult downloadFile(String url,String aesKey,long maxBytes,int connectTimeout,int readTimeout) throws IOException {
        URL source=new URL(url);
        if(!"http".equals(source.getProtocol()) && !"https".equals(source.getProtocol()))throw new IOException("只支持 HTTP/HTTPS 下载");
        HttpURLConnection conn=(HttpURLConnection)source.openConnection();
        conn.setConnectTimeout(connectTimeout);conn.setReadTimeout(readTimeout);
        try {
            if(conn.getResponseCode()!=200)throw new IOException("媒体下载 HTTP 状态异常");
            if(conn.getContentLengthLong()>maxBytes)throw new IOException("下载超过大小上限");
            byte[] data;
            try(InputStream in=conn.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                byte[] buffer=new byte[8192];int count;
                while((count=in.read(buffer))!=-1) {
                    if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("下载被中断");
                    if((long)out.size()+count>maxBytes)throw new IOException("下载超过大小上限");
                    out.write(buffer,0,count);
                }
                data=out.toByteArray();
            }
            return new DownloadResult(aesKey==null||aesKey.isEmpty()?data:decryptAES256CBC(data,aesKey),filename(conn.getHeaderField("Content-Disposition"),source.getPath()));
        } finally {conn.disconnect();}
    }
    static String filename(String disposition,String path) {
        String value=null;
        if(disposition!=null) {
            Matcher encoded=Pattern.compile("filename\\*\\s*=\\s*(?:UTF-8|utf-8)''([^;]+)").matcher(disposition);
            Matcher plain=Pattern.compile("filename\\s*=\\s*(?:\"([^\"]*)\"|([^;]*))",Pattern.CASE_INSENSITIVE).matcher(disposition);
            try {
                if(encoded.find())value=URLDecoder.decode(encoded.group(1).trim().replace("+","%2B"),"UTF-8");
                else if(plain.find())value=plain.group(1)!=null?plain.group(1):plain.group(2).trim();
            } catch(Exception ignored) { /* 无效 filename* 回退至 URL 路径。 */ }
        }
        if(value==null)value=path==null?"":path;
        value=value.replace('\\','/');value=value.substring(value.lastIndexOf('/')+1).replaceAll("[\\r\\n\\x00]","");
        return value.isEmpty()||".".equals(value)||"..".equals(value)?"download":value;
    }
    public static byte[] decryptAES256CBC(byte[] data,String aesKeyBase64) throws IOException {
        try {
            if(data==null||data.length==0||data.length%16!=0)throw new IOException("密文必须按 AES 16 字节对齐且不能为空");
            if(aesKeyBase64==null)throw new IOException("缺少 AES 密钥");
            byte[] key=Base64.getDecoder().decode(aesKeyBase64);
            if(key.length!=32)throw new IOException("AES-256 密钥必须为 32 字节");
            Cipher cipher=Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new IvParameterSpec(Arrays.copyOf(key,16)));
            return pkcs7Unpad(cipher.doFinal(data));
        } catch(IOException e){throw e;}
        catch(Exception e){throw new IOException("媒体解密失败",e);}
    }
    static byte[] pkcs7Unpad(byte[] data) throws IOException {
        if(data==null||data.length==0)throw new IOException("解密内容为空");
        int padding=data[data.length-1]&255;
        if(padding<1||padding>32||padding>data.length)throw new IOException("PKCS#7 填充无效");
        for(int i=data.length-padding;i<data.length;i++)if((data[i]&255)!=padding)throw new IOException("PKCS#7 填充不一致");
        return Arrays.copyOf(data,data.length-padding);
    }
    public static String uploadFile(WeComAiBotClient client,String type,String path) throws IOException,TimeoutException,InterruptedException {
        return SdkFutures.await(client.uploadMediaAsync(type,Paths.get(path))).getMediaId();
    }
}
