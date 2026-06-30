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

public class Aliyun extends Spider {

    private String token;
    private Context context;

    private static final Pattern SHARE_URL_PATTERN = Pattern.compile("(?:aliyundrive\\.com|alipan\\.com)/s/([a-zA-Z0-9]+)");

    @Override
    public void init(Context context, String extend) {
        this.context = context;
        // 凭证由 CloudDrive.init() 设置到 CloudDrive.aliyunToken
        this.token = CloudDrive.aliyunToken;
        if (TextUtils.isEmpty(this.token) && !TextUtils.isEmpty(extend)) {
            this.token = extend;
        }
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String shareUrl = ids.get(0);
        Matcher m = SHARE_URL_PATTERN.matcher(shareUrl);
        if (!m.find()) return Result.string(new ArrayList<>());
        String shareId = m.group(1);

        // Step 1: get share token
        JSONObject tokenBody = new JSONObject();
        tokenBody.put("share_id", shareId);
        tokenBody.put("share_pwd", "");

        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0");
        if (!TextUtils.isEmpty(token)) headers.put("Authorization", "Bearer " + token);

        String tokenResp = OkHttp.post("https://api.aliyundrive.com/v2/share_link/get_share_token", tokenBody.toString(), headers).getBody();
        SpiderDebug.log("Aliyun share token: " + tokenResp);
        JSONObject tokenJson = new JSONObject(tokenResp);
        String shareToken = tokenJson.optString("share_token", "");

        // Step 2: list files
        Map<String, String> listHeaders = new HashMap<>(headers);
        listHeaders.put("X-Share-Token", shareToken);

        JSONObject listBody = new JSONObject();
        listBody.put("share_id", shareId);
        listBody.put("parent_file_id", "root");
        listBody.put("limit", 200);

        String listResp = OkHttp.post("https://api.aliyundrive.com/v2/share_link/list_files", listBody.toString(), listHeaders).getBody();
        SpiderDebug.log("Aliyun list: " + listResp);
        JSONObject listJson = new JSONObject(listResp);
        JSONArray items = listJson.optJSONArray("items");
        if (items == null) return Result.string(new ArrayList<>());

        Vod vod = new Vod();
        vod.setVodId(shareUrl);
        vod.setVodName(shareId);

        List<String> playUrls = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            String type = item.optString("type", "");
            String name = item.optString("name", "视频" + (i + 1));
            String fileId = item.optString("file_id", "");

            if ("folder".equals(type)) {
                // skip folders for simplicity
                continue;
            }
            playUrls.add(name + "$" + fileId);
        }

        if (playUrls.isEmpty()) {
            vod.setVodPlayFrom("阿里云盘");
            vod.setVodPlayUrl(shareUrl);
        } else {
            vod.setVodPlayFrom("阿里云盘");
            vod.setVodPlayUrl(TextUtils.join("#", playUrls));
        }

        return Result.string(vod);
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        // id is file_id from detailContent
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0");

        JSONObject body = new JSONObject();
        body.put("share_id", flag.contains(":") ? flag.split(":")[1] : "");
        body.put("file_id", id);

        // Get download URL
        String resp = OkHttp.post("https://api.aliyundrive.com/v2/file/get_share_link_download_url", body.toString(), headers).getBody();
        JSONObject json = new JSONObject(resp);
        String playUrl = json.optString("download_url", "");
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
}