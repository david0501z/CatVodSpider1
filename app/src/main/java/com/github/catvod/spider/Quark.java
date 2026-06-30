package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;

import com.github.catvod.bean.Vod;
import com.github.catvod.bean.Result;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Quark extends Spider {

    private static final String API_URL = "https://drive-pc.quark.cn/1/clouddrive/";
    private static final String PR = "pr=ucpro&fr=pc";
    private String cookie;
    private Context context;

    private static final Pattern SHARE_URL_PATTERN = Pattern.compile("https://pan\\.quark\\.cn/s/([a-zA-Z0-9]+)");

    @Override
    public void init(Context context, String extend) {
        this.context = context;
        this.cookie = extend != null ? extend : "";
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String shareUrl = ids.get(0);
        Matcher m = SHARE_URL_PATTERN.matcher(shareUrl);
        if (!m.find()) return Result.string(new ArrayList<>());

        // Step 1: get share token
        JSONObject tokenBody = new JSONObject();
        tokenBody.put("pwd", "");
        tokenBody.put("stoken", "");
        tokenBody.put("shareUrl", shareUrl);

        Map<String, String> headers = new HashMap<>();
        if (!TextUtils.isEmpty(cookie)) headers.put("Cookie", cookie);
        headers.put("User-Agent", "Mozilla/5.0");

        String tokenResp = OkHttp.post(API_URL + "share/sharepage/token?" + PR, tokenBody.toString(), headers);
        SpiderDebug.log("Quark token response: " + tokenResp);
        JSONObject tokenJson = new JSONObject(tokenResp);
        if (tokenJson.optInt("status") != 200) return Result.string(new ArrayList<>());
        String shareToken = tokenJson.getJSONObject("data").optString("share_token", "");

        // Step 2: list files
        JSONObject listBody = new JSONObject();
        listBody.put("shareToken", shareToken);
        listBody.put("pwd", "");
        listBody.put("stoken", "");
        listBody.put("shareUrl", shareUrl);
        listBody.put("dirInitial", false);
        listBody.put("_page", 1);
        listBody.put("_size", 100);

        String listResp = OkHttp.post(API_URL + "share/sharepage/detail?" + PR, listBody.toString(), headers);
        SpiderDebug.log("Quark list response: " + listResp);
        JSONObject listJson = new JSONObject(listResp);
        if (listJson.optInt("status") != 200) return Result.string(new ArrayList<>());

        JSONArray list = listJson.getJSONObject("data").optJSONArray("list");
        if (list == null) return Result.string(new ArrayList<>());

        // Build vod info
        String fileName = shareUrl.substring(shareUrl.lastIndexOf("/") + 1);
        Vod vod = new Vod();
        vod.setVodId(shareUrl);
        vod.setVodName(fileName);

        List<String> playUrls = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.getJSONObject(i);
            if (item.optInt("file_type") == 0) continue; // skip folders for now
            String name = item.optString("file_name", "视频" + (i + 1));
            String fid = item.optString("fid", "");
            String size = formatSize(item.optLong("size", 0));
            playUrls.add(name + " (" + size + ")$" + fid);
        }

        if (playUrls.isEmpty()) {
            // maybe it's a folder with sub-files
            // just return the folder itself for navigation
            vod.setVodPlayFrom("夸克网盘");
            vod.setVodPlayUrl(shareUrl);
        } else {
            vod.setVodPlayFrom("夸克网盘");
            vod.setVodPlayUrl(TextUtils.join("#", playUrls));
        }

        return Result.string(vod);
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        // id is the file fid from detailContent
        // Need to get download URL from quark API
        Map<String, String> headers = new HashMap<>();
        if (!TextUtils.isEmpty(cookie)) headers.put("Cookie", cookie);
        headers.put("User-Agent", "Mozilla/5.0");

        JSONObject body = new JSONObject();
        body.put("fids", new JSONArray().put(id));

        String resp = OkHttp.post(API_URL + "share/sharepage/download?" + PR, body.toString(), headers);
        JSONObject json = new JSONObject(resp);
        if (json.optInt("status") != 200) return Result.get().url("").string();

        JSONArray data = json.getJSONObject("data").optJSONArray("list");
        if (data == null || data.length() == 0) return Result.get().url("").string();

        String playUrl = data.getJSONObject(0).optString("download_url", "");
        if (TextUtils.isEmpty(playUrl)) return Result.get().url("").string();

        // Use Go proxy for streaming
        int port = CloudDrive.getGoPort();
        if (port > 0) {
            String proxyUrl = "http://127.0.0.1:" + port + "/quark/proxy?url="
                    + URLEncoder.encode(playUrl, "UTF-8")
                    + "&cookie=" + URLEncoder.encode(cookie != null ? cookie : "", "UTF-8");
            return Result.get().url(proxyUrl).string();
        }

        return Result.get().url(playUrl).header(headers).string();
    }

    @Override
    public Object[] proxy(Map<String, String> params) throws Exception {
        int port = CloudDrive.getGoPort();
        if (port < 0) return null;

        String url = params.get("url");
        if (TextUtils.isEmpty(url)) return null;

        String target = "http://127.0.0.1:" + port + "/proxy?url="
                + URLEncoder.encode(url, "UTF-8");
        if (params.containsKey("cookie")) {
            target += "&cookie=" + URLEncoder.encode(params.get("cookie"), "UTF-8");
        }

        okhttp3.OkHttpClient c = new okhttp3.OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build();
        okhttp3.Request req = new okhttp3.Request.Builder().url(target)
                .header("Range", params.getOrDefault("Range", ""))
                .build();
        okhttp3.Response resp = c.newCall(req).execute();
        String contentType = resp.header("Content-Type", "application/octet-stream");
        return new Object[]{resp.code(), contentType, resp.body().byteStream()};
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1fMB", bytes / (1024.0 * 1024));
        return String.format("%.1fGB", bytes / (1024.0 * 1024 * 1024));
    }
}