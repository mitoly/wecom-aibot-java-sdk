package com.wecom.aibot;

import org.junit.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.Assert.*;

public class MediaUtilsTest {
    byte[] key=new byte[32];
    byte[] encrypt(byte[] value) throws Exception {
        Cipher c=Cipher.getInstance("AES/CBC/NoPadding");c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new IvParameterSpec(Arrays.copyOf(key,16)));return c.doFinal(value);
    }
    @Test public void everyWecomPaddingLengthIsRemoved() throws Exception {
        for(int padding=1;padding<=32;padding++) {
            byte[] value=new byte[64];Arrays.fill(value,0,64-padding,(byte)'A');Arrays.fill(value,64-padding,64,(byte)padding);byte[] encrypted=encrypt(value);byte[] original=encrypted.clone();
            byte[] plain=MediaUtils.decryptAES256CBC(encrypted,Base64.getEncoder().withoutPadding().encodeToString(key));
            assertArrayEquals(Arrays.copyOf(value,64-padding),plain);assertArrayEquals(original,encrypted);
        }
    }
    @Test public void corruptedPaddingAndWrongKeySizeFail() throws Exception {
        byte[] invalid=new byte[32];Arrays.fill(invalid,(byte)'A');invalid[31]=2;
        try{MediaUtils.decryptAES256CBC(encrypt(invalid),Base64.getEncoder().encodeToString(key));fail();}catch(IOException expected){}
        try{MediaUtils.decryptAES256CBC(new byte[15],Base64.getEncoder().encodeToString(key));fail();}catch(IOException expected){}
        try{MediaUtils.decryptAES256CBC(new byte[32],Base64.getEncoder().encodeToString(new byte[16]));fail();}catch(IOException expected){}
    }
    @Test public void safeContentDispositionFilename() {
        assertEquals("报告+1.pdf",MediaUtils.filename("attachment; filename*=UTF-8''%E6%8A%A5%E5%91%8A%2B1.pdf","/opaque"));
        assertEquals("file.pdf",MediaUtils.filename("attachment; filename=\"../../file.pdf\"","/opaque"));
        assertEquals("opaque",MediaUtils.filename(null,"/opaque"));
    }
    @Test public void mediaSpecificLimitsAndFormats() throws Exception {
        MediaTransfer.validateUpload("image","x.gif",10*1024*1024);MediaTransfer.validateUpload("file","x.bin",20*1024*1024);
        for(String type:Arrays.asList("image","video","voice","file"))try{MediaTransfer.validateUpload(type,"x.bin",4);fail();}catch(AiBotException expected){}
        try{MediaTransfer.validateUpload("voice","x.mp3",5);fail();}catch(AiBotException expected){}
        try{MediaTransfer.validateUpload("image","x.png",10*1024*1024+1L);fail();}catch(AiBotException expected){}
    }
}
