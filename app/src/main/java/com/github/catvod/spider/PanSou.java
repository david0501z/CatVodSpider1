package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;

import com.github.catvod.bean.Class;
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
 * PanSou 网盘资源搜索 — 可搜索，结果直接触发网盘 spider 的 detailContent
 */
public class PanSou extends Spider {

    private static final String API_URL = "http://david525:8888/api/search";
    // 若用户有公网/CF Worker 代理，可改为公网地址

    private Map<String, String> getHeaders() {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", "Mozilla/5.0");
        h.put("Content-Type", "application/json");
        return h;
    }

    @Override
    public void init(Context context, String extend) {
        // ext 可选：自定义 API 地址
        if (!TextUtils.isEmpty(extend)) {
            // 留空给以后扩展
        }
    }

    @Override
    public String homeContent(boolean filter) {
        List<Class> classes = new ArrayList<>();
        classes.add(new Class("search", "🔍 全网网盘搜索", "1"));
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
        JSONObject body = new JSONObject();
        body.put("kw", keyword);
        body.put("res", "merge"); // 只返回 merged_by_type

        String resp = OkHttp.post(API_URL, body.toString(), getHeaders()).getBody();
        SpiderDebug.log("PanSou search: " + resp);
        JSONObject json = new JSONObject(resp);

        // 从 merged_by_type 提取结果
        JSONObject merged = json.optJSONObject("data");
        if (merged == null) merged = json.optJSONObject("merged_by_type");
        if (merged == null) return Result.string(new ArrayList<>());

        List<Vod> list = new ArrayList<>();
        int idx = 0;

        // 遍历每种网盘类型
        String[] types = merged.getNames(merged);
        if (types == null) return Result.string(new ArrayList<>());

        for (String type : types) {
            JSONArray links = merged.optJSONArray(type);
            if (links == null) continue;
            for (int i = 0; i < links.length() && i < 20; i++) {
                JSONObject link = links.optJSONObject(i);
                if (link == null) continue;

                String url = link.optString("url", "");
                String note = link.optString("note", "");
                String pwd = link.optString("password", "");

                if (TextUtils.isEmpty(url)) continue;

                Vod vod = new Vod();
                vod.setVodId(url); // vod_id = 分享链接，CloudDrive 可直接解析
                vod.setVodName(!TextUtils.isEmpty(note) ? note : keyword + " #" + (i + 1));
                vod.setVodRemarks("[" + type + "] " + (TextUtils.isEmpty(pwd) ? "" : "🗝️" + pwd));
                vod.setVodPic(getIconForType(type));
                list.add(vod);
                idx++;
            }
        }

        return Result.string(list);
    }

    // ==================== 详情 ====================

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String url = ids.get(0);
        // 转发给 CloudDrive 解析
        return new CloudDrive().detailContent(ids);
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        // 转发给 CloudDrive
        return new CloudDrive().playerContent(flag, id, vipFlags);
    }

    // ==================== 工具 ====================

    private String getIconForType(String type) {
        switch (type) {
            case "quark": return "https://pan.quark.cn/favicon.ico";
            case "aliyun": return "https://www.aliyundrive.com/favicon.ico";
            case "123": return "https://www.123pan.com/favicon.ico";
            case "baidu": return "https://pan.baidu.com/favicon.ico";
            case "115": return "https://115.com/favicon.ico";
            default: return "https://pan.quark.cn/favicon.ico";
        }
    }
}