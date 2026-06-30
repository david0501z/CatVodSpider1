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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class Quark extends Spider {

    private String cookie;
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private Map<String, String> getHeaders() {
        Map<String, String> headers = new HashMap<>();
        if (!TextUtils.isEmpty(cookie)) headers.put("Cookie", cookie);
        headers.put("User-Agent", UA);
        return headers;
    }

    @Override
    public void init(Context context, String extend) {
        this.cookie = extend != null ? extend : "";
    }

    @Override
    public String homeContent(boolean filter) {
        List<Class> classes = new ArrayList<>();
        classes.add(new Class("quark", "夸克网盘"));
        return Result.string(classes, null);
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        List<Vod> list = new ArrayList<>();
        String shareUrl = extend != null ? extend.get("url") : "";
        if (TextUtils.isEmpty(shareUrl)) return Result.string(list);
        try {
            String shareToken = getShareToken(shareUrl);
            if (TextUtils.isEmpty(shareToken)) return Result.string(list);
            JSONObject params = new JSONObject();
            params.put("shareToken", shareToken);
            params.put("pwd", "");
            params.put("stoken", "");
            params.put("shareUrl", shareUrl);
            params.put("dirInitial", false);
            String detailUrl = "https://drive-pc.quark.cn/1/clouddrive/share/sharepage/detail?pr=ucpro&fr=pc&_page=" + pg + "&_size=50";
            String json = OkHttp.post(detailUrl, params.toString(), getHeaders());
            JSONObject obj = new JSONObject(json);
            if (obj.optInt("status") != 200) return Result.string(list);
            JSONObject data = obj.optJSONObject("data");
            if (data == null) return Result.string(list);
            JSONArray fileList = data.optJSONArray("list");
            if (fileList == null) return Result.string(list);
            for (int i = 0; i < fileList.length(); i++) {
                JSONObject file = fileList.getJSONObject(i);
                String fid = file.optString("fid");
                String fileName = file.optString("file_name");
                String size = formatSize(file.optLong("size"));
                boolean isDir = file.optInt("dir") == 1;
                String path = shareUrl + "/" + (isDir ? "folder" : "file") + "/" + fid;
                Vod vod = new Vod();
                vod.setVodId(path);
                vod.setVodName(fileName);
                vod.setVodPic("");
                if (isDir) {
                    vod.setVodTag("folder");
                    vod.setVodRemarks("文件夹");
                } else {
                    vod.setVodRemarks(size);
                }
                list.add(vod);
            }
        } catch (Exception e) {
            SpiderDebug.log(e);
        }
        return Result.string(list);
    }

    @Override
    public String detailContent(List<String> ids) {
        String id = ids.get(0);
        try {
            String shareUrl = id;
            if (id.contains("/")) {
                shareUrl = id.substring(0, id.indexOf("/"));
            }
            String shareToken = getShareToken(shareUrl);
            if (TextUtils.isEmpty(shareToken)) return Result.string(new Vod());
            JSONObject params = new JSONObject();
            params.put("shareToken", shareToken);
            params.put("pwd", "");
            params.put("stoken", "");
            params.put("shareUrl", shareUrl);
            params.put("dirInitial", false);
            String json = OkHttp.post("https://drive-pc.quark.cn/1/clouddrive/share/sharepage/detail?pr=ucpro&fr=pc&_page=1&_size=100", params.toString(), getHeaders());
            JSONObject obj = new JSONObject(json);
            if (obj.optInt("status") != 200) return Result.string(new Vod());
            JSONObject data = obj.optJSONObject("data");
            if (data == null) return Result.string(new Vod());
            JSONArray fileList = data.optJSONArray("list");
            if (fileList == null) return Result.string(new Vod());
            Vod vod = new Vod();
            vod.setVodId(id);
            vod.setVodName(data.optString("share_title", "夸克分享"));
            vod.setVodPic("");
            List<String> playUrls = new ArrayList<>();
            for (int i = 0; i < fileList.length(); i++) {
                JSONObject file = fileList.getJSONObject(i);
                if (file.optInt("dir") == 1) continue;
                String fileName = file.optString("file_name");
                String fid = file.optString("fid");
                String ext = getExt(fileName);
                if (!isVideoExt(ext)) continue;
                String fileId = shareUrl + "/file/" + fid;
                playUrls.add(fileName + "$" + fileId);
            }
            vod.setVodPlayFrom("夸克网盘");
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
            String[] parts = id.split("/file/");
            if (parts.length < 2) return Result.get().url(id).string();
            String shareUrl = parts[0];
            String fid = parts[1];
            String shareToken = getShareToken(shareUrl);
            if (TextUtils.isEmpty(shareToken)) return Result.get().url("").string();
            JSONObject params = new JSONObject();
            JSONArray fids = new JSONArray();
            fids.put(fid);
            params.put("fids", fids);
            params.put("shareToken", shareToken);
            String json = OkHttp.post("https://drive-pc.quark.cn/1/clouddrive/share/sharepage/download?pr=ucpro&fr=pc", params.toString(), getHeaders());
            JSONObject obj = new JSONObject(json);
            if (obj.optInt("status") != 200) return Result.get().url("").string();
            JSONObject data = obj.optJSONObject("data");
            if (data == null) return Result.get().url("").string();
            JSONArray list = data.optJSONArray("list");
            if (list == null || list.length() == 0) return Result.get().url("").string();
            String downloadUrl = list.getJSONObject(0).optString("url");
            if (TextUtils.isEmpty(downloadUrl)) return Result.get().url("").string();
            Map<String, String> header = new HashMap<>();
            header.put("User-Agent", UA);
            if (!TextUtils.isEmpty(cookie)) header.put("Cookie", cookie);
            return Result.get().url(downloadUrl).header(header).string();
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
        try {
            OkHttpClient client = new OkHttpClient.Builder().build();
            Request.Builder builder = new Request.Builder().url(url);
            if (!TextUtils.isEmpty(cookie)) builder.addHeader("Cookie", cookie);
            builder.addHeader("User-Agent", UA);
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

    private String getShareToken(String shareUrl) {
        try {
            JSONObject params = new JSONObject();
            params.put("pwd", "");
            params.put("stoken", "");
            params.put("shareUrl", shareUrl);
            String json = OkHttp.post("https://drive-pc.quark.cn/1/clouddrive/share/sharepage/token?pr=ucpro&fr=pc", params.toString(), getHeaders());
            JSONObject obj = new JSONObject(json);
            if (obj.optInt("status") != 200) return null;
            JSONObject data = obj.optJSONObject("data");
            if (data == null) return null;
            return data.optString("share_token");
        } catch (Exception e) {
            SpiderDebug.log(e);
            return null;
        }
    }

    private String formatSize(long size) {
        if (size < 1024) return size + "B";
        if (size < 1024 * 1024) return String.format("%.1fKB", size / 1024.0);
        if (size < 1024 * 1024 * 1024) return String.format("%.1fMB", size / (1024.0 * 1024));
        return String.format("%.1fGB", size / (1024.0 * 1024 * 1024));
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
