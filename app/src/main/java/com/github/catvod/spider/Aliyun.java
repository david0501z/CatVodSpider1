package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class Aliyun extends Spider {

    private Context context;
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private Map<String, String> getHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", UA);
        return headers;
    }

    @Override
    public void init(Context context, String extend) {
        this.context = context;
    }

    @Override
    public String homeContent(boolean filter) {
        List<Class> classes = new ArrayList<>();
        classes.add(new Class("aliyun", "阿里云盘"));
        return Result.string(classes, null);
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        return Result.string(new ArrayList<>());
    }

    @Override
    public String detailContent(List<String> ids) {
        String id = ids.get(0);
        try {
            String shareId = extractShareId(id);
            if (TextUtils.isEmpty(shareId)) return Result.string(new Vod());
            String shareToken = getShareToken(shareId);
            if (TextUtils.isEmpty(shareToken)) return Result.string(new Vod());
            Map<String, String> headers = getHeaders();
            headers.put("X-Share-Token", shareToken);
            JSONObject params = new JSONObject();
            params.put("share_id", shareId);
            params.put("parent_file_id", "root");
            params.put("limit", 100);
            String json = OkHttp.post("https://api.aliyundrive.com/v2/share_link/list_files", params.toString(), headers);
            JSONArray items = new JSONArray(json);
            Vod vod = new Vod();
            vod.setVodId(id);
            vod.setVodName("阿里云盘分享");
            vod.setVodPic("");
            List<String> playUrls = new ArrayList<>();
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                if ("folder".equals(item.optString("type"))) continue;
                String fileName = item.optString("name");
                String fileId = item.optString("file_id");
                String ext = getExt(fileName);
                if (!isVideoExt(ext)) continue;
                playUrls.add(fileName + "$" + shareId + "/" + fileId);
            }
            vod.setVodPlayFrom("阿里云盘");
            vod.setVodPlayUrl(TextUtils.join("#", playUrls));
            return Result.string(vod);
        } catch (Exception e) {
            SpiderDebug.log(e);
            return Result.string(new Vod());
        }
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try {
            String[] parts = id.split("/", 2);
            if (parts.length < 2) return Result.get().url("").string();
            String shareId = parts[0];
            String fileId = parts[1];
            String shareToken = getShareToken(shareId);
            if (TextUtils.isEmpty(shareToken)) return Result.get().url("").string();
            Map<String, String> headers = getHeaders();
            headers.put("X-Share-Token", shareToken);
            JSONObject params = new JSONObject();
            params.put("share_id", shareId);
            params.put("file_id", fileId);
            String json = OkHttp.post("https://api.aliyundrive.com/v2/file/get_share_link_download_url", params.toString(), headers);
            JSONObject obj = new JSONObject(json);
            String downloadUrl = obj.optString("download_url");
            if (TextUtils.isEmpty(downloadUrl)) downloadUrl = obj.optString("url");
            if (TextUtils.isEmpty(downloadUrl)) return Result.get().url("").string();
            Map<String, String> respHeaders = new HashMap<>();
            respHeaders.put("User-Agent", UA);
            respHeaders.put("Referer", "https://www.aliyundrive.com/");
            return Result.get().url(downloadUrl).header(respHeaders).string();
        } catch (Exception e) {
            SpiderDebug.log(e);
            return Result.get().url("").string();
        }
    }

    @Override
    public String searchContent(String key, boolean quick) {
        return "";
    }

    @Override
    public Object[] proxy(Map<String, String> params) {
        if (params == null) return null;
        String url = params.get("url");
        if (TextUtils.isEmpty(url)) return null;
        int port = CloudDrive.getGoPort();
        if (port <= 0) {
            return directProxy(params);
        }
        try {
            StringBuilder proxyUrl = new StringBuilder("http://127.0.0.1:" + port + "/proxy?url=");
            proxyUrl.append(URLEncoder.encode(url, "UTF-8"));
            String range = params.get("range");
            if (!TextUtils.isEmpty(range)) {
                proxyUrl.append("&range=").append(URLEncoder.encode(range, "UTF-8"));
            }
            JSONObject headerJson = new JSONObject();
            headerJson.put("User-Agent", UA);
            headerJson.put("Referer", "https://www.aliyundrive.com/");
            proxyUrl.append("&header=").append(URLEncoder.encode(headerJson.toString(), "UTF-8"));
            Request request = new Request.Builder().url(proxyUrl.toString()).build();
            OkHttpClient client = new OkHttpClient.Builder().build();
            Response response = client.newCall(request).execute();
            String contentType = response.header("Content-Type", "application/octet-stream");
            InputStream is = response.body() != null ? response.body().byteStream() : new ByteArrayInputStream(new byte[0]);
            return new Object[]{response.code(), contentType, is};
        } catch (Exception e) {
            SpiderDebug.log(e);
            return null;
        }
    }

    private Object[] directProxy(Map<String, String> params) {
        String url = params.get("url");
        if (TextUtils.isEmpty(url)) return null;
        try {
            OkHttpClient client = new OkHttpClient.Builder().build();
            Request.Builder builder = new Request.Builder().url(url);
            builder.addHeader("User-Agent", UA);
            builder.addHeader("Referer", "https://www.aliyundrive.com/");
            String range = params.get("range");
            if (!TextUtils.isEmpty(range)) builder.addHeader("Range", range);
            Response response = client.newCall(builder.build()).execute();
            String contentType = response.header("Content-Type", "application/octet-stream");
            InputStream is = response.body() != null ? response.body().byteStream() : new ByteArrayInputStream(new byte[0]);
            return new Object[]{response.code(), contentType, is};
        } catch (Exception e) {
            SpiderDebug.log(e);
            return null;
        }
    }

    private String getShareToken(String shareId) {
        try {
            JSONObject params = new JSONObject();
            params.put("share_id", shareId);
            params.put("share_pwd", "");
            String json = OkHttp.post("https://api.aliyundrive.com/v2/share_link/get_share_token", params.toString(), getHeaders());
            JSONObject obj = new JSONObject(json);
            return obj.optString("share_token");
        } catch (Exception e) {
            SpiderDebug.log(e);
            return null;
        }
    }

    private String extractShareId(String url) {
        if (url.startsWith("http")) {
            String[] parts = url.split("/s/");
            if (parts.length >= 2) return parts[1].split("[?#]")[0];
            parts = url.split("/share/");
            if (parts.length >= 2) return parts[1].split("[?#]")[0];
        }
        return url;
    }

    private String getExt(String name) {
        int idx = name.lastIndexOf(".");
        return idx > 0 ? name.substring(idx + 1).toLowerCase() : "";
    }

    private boolean isVideoExt(String ext) {
        return "mp4".equals(ext) || "mkv".equals(ext) || "avi".equals(ext) ||
               "mov".equals(ext) || "wmv".equals(ext) || "flv".equals(ext) ||
               "ts".equals(ext) || "webm".equals(ext) || "rmvb".equals(ext) || "m4v".equals(ext);
    }
}