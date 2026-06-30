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

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Pan123 extends Spider {

    private Context context;

    private static final Pattern SHARE_KEY_PATTERN = Pattern.compile("(?:123pan\\.com|123684\\.com)/s/([^/?\"']+)");

    @Override
    public void init(Context context, String extend) {
        this.context = context;
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String shareUrl = ids.get(0);
        Matcher m = SHARE_KEY_PATTERN.matcher(shareUrl);
        if (!m.find()) return Result.string(new ArrayList<>());
        String shareKey = m.group(1);
        if (shareKey.startsWith("s/")) shareKey = shareKey.substring(2);

        // 123云盘不需要 cookie，直接请求
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0");
        headers.put("Referer", "https://www.123pan.com/");

        // 获取分享信息
        String infoResp = OkHttp.string("https://www.123pan.com/api/share/info?shareKey=" + shareKey, headers);
        SpiderDebug.log("123Pan info: " + infoResp);
        JSONObject infoJson = new JSONObject(infoResp);
        if (infoJson.optInt("code") != 0) return Result.string(new ArrayList<>());

        JSONObject data = infoJson.optJSONObject("data");
        if (data == null) return Result.string(new ArrayList<>());

        long shareId = data.optLong("shareId", 0);
        String sharePwd = data.optString("sharePwd", "");

        // 获取文件列表
        JSONObject listBody = new JSONObject();
        listBody.put("shareKey", shareKey);
        listBody.put("shareId", shareId);
        listBody.put("sharePwd", sharePwd);
        listBody.put("parentFileId", 0);
        listBody.put("limit", 200);
        listBody.put("Page", 1);
        listBody.put("orderBy", "name");
        listBody.put("orderDirection", "asc");

        String listResp = OkHttp.post("https://www.123pan.com/api/share/list", listBody.toString(), headers);
        SpiderDebug.log("123Pan list: " + listResp);
        JSONObject listJson = new JSONObject(listResp);
        if (listJson.optInt("code") != 0) return Result.string(new ArrayList<>());

        JSONArray list = listJson.getJSONObject("data").optJSONArray("fileList");
        if (list == null) return Result.string(new ArrayList<>());

        Vod vod = new Vod();
        vod.setVodId(shareUrl);
        vod.setVodName(shareKey);

        List<String> playUrls = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.getJSONObject(i);
            String name = item.optString("FileName", "视频" + (i + 1));
            long fid = item.optLong("FileId", 0);
            int type = item.optInt("Type", 0);
            if (type == 1) continue; // folder
            long size = item.optLong("FileSize", 0);
            String sizeStr = formatSize(size);
            playUrls.add(name + " (" + sizeStr + ")$" + fid);
        }

        if (playUrls.isEmpty()) {
            vod.setVodPlayFrom("123云盘");
            vod.setVodPlayUrl(shareUrl);
        } else {
            vod.setVodPlayFrom("123云盘");
            vod.setVodPlayUrl(TextUtils.join("#", playUrls));
        }

        return Result.string(vod);
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        // Get download URL
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0");
        headers.put("Referer", "https://www.123pan.com/");

        JSONObject body = new JSONObject();
        body.put("fileId", Long.parseLong(id));

        String resp = OkHttp.post("https://www.123pan.com/api/share/download/info", body.toString(), headers);
        JSONObject json = new JSONObject(resp);
        if (json.optInt("code") != 0) return Result.get().url("").string();

        JSONObject data = json.optJSONObject("data");
        if (data == null) return Result.get().url("").string();

        // 可能有多个 download URL
        String playUrl = data.optString("DownloadUrl", "");
        if (TextUtils.isEmpty(playUrl)) {
            // key = direct link
            playUrl = "https://" + data.optString("Key", "");
        }
        if (TextUtils.isEmpty(playUrl)) return Result.get().url("").string();

        // Use Go proxy
        int port = CloudDrive.getGoPort();
        if (port > 0) {
            String proxyUrl = "http://127.0.0.1:" + port + "/proxy?url="
                    + URLEncoder.encode(playUrl, "UTF-8");
            return Result.get().url(proxyUrl).string();
        }

        return Result.get().url(playUrl).string();
    }

    @Override
    public Object[] proxy(Map<String, String> params) throws Exception {
        int port = CloudDrive.getGoPort();
        if (port < 0) return null;

        String url = params.get("url");
        if (TextUtils.isEmpty(url)) return null;

        String target = "http://127.0.0.1:" + port + "/proxy?url="
                + URLEncoder.encode(url, "UTF-8");

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