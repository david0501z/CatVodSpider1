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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PanSou 网盘资源搜索 spider
 * 适配 douban.html 中 pansou 搜索流程：
 * POST /api/search { kw, res:"merge", src:"all" }
 * 结果来自 merged_by_type { quark: [{url,password,note}], aliyun: [...], ... }
 * 点击后通过 cloud drive spider 解析播放
 */
public class PanSou extends Spider {

    // 默认 pansou API 地址 — 跟 douban.html 的 panApi("/api/search") 一致
    private static final String DEFAULT_API = "http://david525:8888";
    private String apiBase = DEFAULT_API;
    private Context context;

    private Map<String, String> getHeaders() {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", "Mozilla/5.0 (Linux; Android 14)");
        h.put("Content-Type", "application/json");
        return h;
    }

    @Override
    public void init(Context context, String extend) {
        this.context = context;
        // ext 可自定义 API 地址，格式: http://ip:port
        if (!TextUtils.isEmpty(extend)) {
            apiBase = extend.trim();
        }
    }

    @Override
    public String homeContent(boolean filter) {
        List<com.github.catvod.bean.Class> classes = new ArrayList<>();
        classes.add(new com.github.catvod.bean.Class("search", "🔍 盘搜", "1"));
        return Result.string(classes, new LinkedHashMap<>());
    }

    @Override
    public String homeVideoContent() {
        return "";
    }

    // ==================== 搜索 ====================

    @Override
    public String searchContent(String keyword, boolean quick) throws Exception {
        return searchContent(keyword);
    }

    @Override
    public String searchContent(String keyword) throws Exception {
        // 跟 douban.html 的 panSearchPayload() 一致
        JSONObject body = new JSONObject();
        body.put("kw", keyword);
        body.put("res", "merge");
        body.put("src", "all");

        // 可选: 指定网盘类型（不指定则搜全部）
        // body.put("cloud_types", new JSONArray());

        String apiUrl = apiBase + "/api/search";
        if (!apiBase.endsWith("/")) apiUrl = apiBase + "/api/search";
        if (apiBase.contains("/api")) apiUrl = apiBase + "/search"; // 兼容 apiBase 已是 /api 路径

        String resp;
        try {
            SpiderDebug.log("PanSou search: " + apiUrl + " kw=" + keyword);
            resp = OkHttp.post(apiUrl, body.toString(), getHeaders()).getBody();
        } catch (Exception e) {
            SpiderDebug.log("PanSou search failed: " + e.getMessage());
            // 兜底：尝试 GET 方式
            try {
                String getUrl = apiUrl + "?kw=" + URLEncoder.encode(keyword, "UTF-8") + "&res=merge";
                resp = OkHttp.string(getUrl, getHeaders());
            } catch (Exception e2) {
                return Result.string(new ArrayList<>());
            }
        }

        if (TextUtils.isEmpty(resp)) return Result.string(new ArrayList<>());

        SpiderDebug.log("PanSou response: " + resp.substring(0, Math.min(resp.length(), 500)));

        JSONObject json = new JSONObject(resp);

        // douban.html 用 body.data || body
        JSONObject data = json.optJSONObject("data");
        if (data == null) data = json;

        JSONObject merged = data.optJSONObject("merged_by_type");
        if (merged == null) {
            // 兼容老格式
            merged = json.optJSONObject("merged_by_type");
        }
        if (merged == null) return Result.string(new ArrayList<>());

        List<Vod> list = new ArrayList<>();

        // 遍历所有网盘类型（按顺序优先显示 quark/aliyun/123）
        String[] preferredTypes = {"quark", "aliyun", "123", "baidu", "115", "uc", "tianyi", "pikpak", "xunlei", "others"};
        for (String type : preferredTypes) {
            JSONArray links = merged.optJSONArray(type);
            if (links == null) continue;
            for (int i = 0; i < links.length() && list.size() < 50; i++) {
                JSONObject link = links.optJSONObject(i);
                if (link == null) continue;

                String url = link.optString("url", "");
                String note = link.optString("note", link.optString("name", link.optString("title", "")));
                String pwd = link.optString("password", link.optString("pwd", link.optString("code", "")));

                if (TextUtils.isEmpty(url)) continue;

                Vod vod = new Vod();
                vod.setVodId(url); // vod_id = 分享链接 → CloudDrive 直接解析
                vod.setVodName(!TextUtils.isEmpty(note) ? note : keyword);
                vod.setVodRemarks("[" + type + "]" + (TextUtils.isEmpty(pwd) ? "" : " 🗝️" + pwd));
                vod.setVodPic(getIconForType(type));
                list.add(vod);
            }
        }

        return Result.string(list);
    }

    // ==================== 详情（转发到 CloudDrive） ====================

    @Override
    public String detailContent(List<String> ids) throws Exception {
        if (ids == null || ids.isEmpty()) return "";
        String url = ids.get(0);
        if (TextUtils.isEmpty(url)) return "";

        SpiderDebug.log("PanSou detail: forwarding to CloudDrive: " + url);

        // 创建 CloudDrive 实例并初始化（确保 Go proxy 启动）
        CloudDrive cloud = new CloudDrive();
        try {
            cloud.init(context, "");
        } catch (Exception e) {
            SpiderDebug.log("PanSou CloudDrive init: " + e.getMessage());
        }

        return cloud.detailContent(ids);
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        // 转发到 CloudDrive
        CloudDrive cloud = new CloudDrive();
        try {
            cloud.init(context, "");
        } catch (Exception e) {
            SpiderDebug.log("PanSou CloudDrive init: " + e.getMessage());
        }
        return cloud.playerContent(flag, id, vipFlags);
    }

    // ==================== 工具 ====================

    private String getIconForType(String type) {
        switch (type) {
            case "quark":   return "https://pan.quark.cn/favicon.ico";
            case "aliyun":  return "https://www.aliyundrive.com/favicon.ico";
            case "123":     return "https://www.123pan.com/favicon.ico";
            case "baidu":   return "https://pan.baidu.com/favicon.ico";
            case "115":     return "https://115.com/favicon.ico";
            default:        return "https://pan.quark.cn/favicon.ico";
        }
    }
}