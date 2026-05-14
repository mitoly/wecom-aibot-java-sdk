package com.wecom.aibot;

import com.fasterxml.jackson.databind.JsonNode;
import com.wecom.aibot.model.*;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.TimeoutException;

/**
 * 媒体文件工具类：下载、AES-256-CBC 解密、分片上传。
 * <p>
 * AES 解密安全防护：
 * <ul>
 *   <li>key 长度不足 16 字节时拒绝解密</li>
 *   <li>密文长度非 16 的倍数时拒绝解密</li>
 *   <li>PKCS7 填充一致性验证</li>
 *   <li>使用副本数组解密，不修改原始数据</li>
 * </ul>
 */
public class MediaUtils {

    private static final int AES_BLOCK_SIZE = 16;

    private MediaUtils() {
    }

    // =========================================================================
    // 文件下载
    // =========================================================================

    /**
     * 下载媒体文件结果。
     */
    public static class DownloadResult {
        private final byte[] data;
        private final String filename;

        public DownloadResult(byte[] data, String filename) {
            this.data = data;
            this.filename = filename;
        }

        public byte[] getData() {
            return data;
        }

        public String getFilename() {
            return filename;
        }
    }

    /**
     * 下载媒体文件，若提供 aesKey 则使用 AES-256-CBC 解密。
     *
     * @param url    文件下载 URL
     * @param aesKey AES 密钥（Base64 编码），为 null 或空则不解密
     * @return 下载结果（包含数据和文件名）
     * @throws IOException 下载或解密失败
     */
    public static DownloadResult downloadFile(String url, String aesKey) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(60_000);

        try {
            int status = conn.getResponseCode();
            if (status != 200) {
                throw new IOException("下载失败，HTTP 状态码: " + status);
            }

            byte[] data;
            try (InputStream is = conn.getInputStream();
                 ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) {
                    bos.write(buf, 0, n);
                }
                data = bos.toByteArray();
            }

            // 从 URL 路径提取文件名
            String path = new URL(url).getPath();
            String filename = path.substring(path.lastIndexOf('/') + 1);

            if (aesKey == null || aesKey.isEmpty()) {
                return new DownloadResult(data, filename);
            }

            byte[] decrypted = decryptAES256CBC(data, aesKey);
            return new DownloadResult(decrypted, filename);
        } finally {
            conn.disconnect();
        }
    }

    // =========================================================================
    // AES-256-CBC 解密
    // =========================================================================

    /**
     * 使用 AES-256-CBC 解密数据。
     * 密钥为 Base64 编码，IV 取 decoded key 的前 16 字节。
     *
     * @param data         加密的数据
     * @param aesKeyBase64 Base64 编码的 AES 密钥
     * @return 解密后的数据
     * @throws IOException 解密失败
     */
    public static byte[] decryptAES256CBC(byte[] data, String aesKeyBase64) throws IOException {
        byte[] key;
        try {
            key = Base64.getDecoder().decode(aesKeyBase64);
        } catch (IllegalArgumentException e) {
            throw new IOException("解码 AES 密钥失败: " + e.getMessage(), e);
        }

        // AES-256 要求 key 长度至少 16 字节（用于 IV），通常为 32 字节
        if (key.length < AES_BLOCK_SIZE) {
            throw new IOException("AES 密钥长度不足: 需要至少 " + AES_BLOCK_SIZE + " 字节, 实际 " + key.length + " 字节");
        }

        // CBC 解密要求数据长度是 BlockSize 的倍数
        if (data.length == 0 || data.length % AES_BLOCK_SIZE != 0) {
            throw new IOException("密文长度无效: " + data.length + " 不是 " + AES_BLOCK_SIZE + " 的倍数");
        }

        try {
            // IV 取 key 的前 16 字节
            byte[] iv = Arrays.copyOfRange(key, 0, AES_BLOCK_SIZE);

            SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
            IvParameterSpec ivSpec = new IvParameterSpec(iv);

            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);

            // 使用副本解密，不修改原始数据
            byte[] dataCopy = Arrays.copyOf(data, data.length);
            byte[] decrypted = cipher.doFinal(dataCopy);

            // PKCS7 unpad
            return pkcs7Unpad(decrypted);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("AES 解密失败: " + e.getMessage(), e);
        }
    }

    /**
     * 移除 PKCS#7 填充。包含填充一致性验证。
     */
    static byte[] pkcs7Unpad(byte[] data) {
        if (data.length == 0) {
            return data;
        }
        int pad = data[data.length - 1] & 0xFF;
        if (pad == 0 || pad > AES_BLOCK_SIZE || pad > data.length) {
            return data; // 填充无效，返回原数据
        }
        // 验证所有填充字节是否一致
        for (int i = data.length - pad; i < data.length; i++) {
            if ((data[i] & 0xFF) != pad) {
                return data; // 填充不合法，返回原数据
            }
        }
        return Arrays.copyOfRange(data, 0, data.length - pad);
    }

    // =========================================================================
    // 分片上传
    // =========================================================================

    /**
     * 读取本地文件并通过三步分片上传 API 上传，返回 media_id。
     *
     * @param client    SDK 客户端
     * @param mediaType 媒体类型（image/voice/video/file）
     * @param filePath  本地文件路径
     * @return 上传成功后的 media_id
     * @throws IOException          文件读取或上传失败
     * @throws TimeoutException     等待响应超时
     * @throws InterruptedException 等待过程中被中断
     */
    public static String uploadFile(WeComAiBotClient client, String mediaType, String filePath)
            throws IOException, TimeoutException, InterruptedException {

        Path path = Paths.get(filePath);
        byte[] data = Files.readAllBytes(path);
        String filename = path.getFileName().toString();
        int totalChunks = (int) Math.ceil((double) data.length / Constants.UPLOAD_CHUNK_SIZE);

        // 计算 MD5
        String md5Hex;
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            md5Hex = sb.toString();
        } catch (Exception e) {
            throw new IOException("计算 MD5 失败: " + e.getMessage(), e);
        }

        // 第一步：初始化上传
        UploadInitBody initBody = new UploadInitBody(mediaType, filename, data.length, totalChunks, md5Hex);
        Frame initResp = client.send(Constants.CMD_UPLOAD_MEDIA_INIT, initBody);
        if (initResp.getErrCode() != 0) {
            throw new IOException("上传初始化失败: " + initResp.getErrCode() + " " + initResp.getErrMsg());
        }
        String uploadId = initResp.getBody().get("upload_id").asText();

        // 第二步：逐个上传分片
        for (int i = 0; i < totalChunks; i++) {
            int start = i * Constants.UPLOAD_CHUNK_SIZE;
            int end = Math.min(start + Constants.UPLOAD_CHUNK_SIZE, data.length);
            byte[] chunk = Arrays.copyOfRange(data, start, end);
            String base64Data = Base64.getEncoder().encodeToString(chunk);

            UploadChunkBody chunkBody = new UploadChunkBody(uploadId, i, base64Data);
            Frame chunkResp = client.send(Constants.CMD_UPLOAD_MEDIA_CHUNK, chunkBody);
            if (chunkResp.getErrCode() != 0) {
                throw new IOException("上传第 " + i + " 个分片失败: " + chunkResp.getErrCode() + " " + chunkResp.getErrMsg());
            }
        }

        // 第三步：完成上传
        UploadFinishBody finishBody = new UploadFinishBody(uploadId);
        Frame finishResp = client.send(Constants.CMD_UPLOAD_MEDIA_FINISH, finishBody);
        if (finishResp.getErrCode() != 0) {
            throw new IOException("完成上传失败: " + finishResp.getErrCode() + " " + finishResp.getErrMsg());
        }
        return finishResp.getBody().get("media_id").asText();
    }
}
