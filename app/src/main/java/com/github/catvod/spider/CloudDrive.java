package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Vod;
import com.github.catvod.bean.Result;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CloudDrive Spider — FongMi TVBox 模式
 * homeContent → 分类（夸克/阿里云）
 * categoryContent → 操作卡片
 * playerContent → 处理扫码/状态
 */
public class CloudDrive extends Spider {

    public static String quarkCookie = "";
    public static String aliyunAccessToken = "";
    public static String aliyunRefreshToken = "";
    public static long aliyunTokenExpiresAt = 0;

    public static boolean isAliyunTokenValid() {
        return !TextUtils.isEmpty(aliyunAccessToken) && System.currentTimeMillis() < aliyunTokenExpiresAt - 60000;
    }

    /** Go 代理端口（旧版兼容，始终返回 -1 表示禁用）*/
    public static int getGoPort() { return -1; }

    @Override
    public void init(Context context, String extend) {
        if (!TextUtils.isEmpty(extend)) {
            try {
                JSONObject obj = new JSONObject(extend);
                if (obj.has("quark_cookie")) quarkCookie = obj.optString("quark_cookie", "");
                if (obj.has("aliyun_token")) aliyunAccessToken = obj.optString("aliyun_token", "");
                if (obj.has("aliyun_refresh_token")) aliyunRefreshToken = obj.optString("aliyun_refresh_token", "");
                if (obj.has("aliyun_expires_at")) aliyunTokenExpiresAt = obj.optLong("aliyun_expires_at", 0);
            } catch (Exception e) {
                quarkCookie = extend;
            }
        }
    }

    @Override
    public String homeContent(boolean filter) {
        List<Vod> list = new ArrayList<>();
        if (TextUtils.isEmpty(quarkCookie))
            list.add(makeVod("quark:qrcode", "🔑 夸克扫码登录", "https://pan.quark.cn/favicon.ico", "扫码登录夸克网盘"));
        list.add(makeVod("quark:status", "📊 夸克状态", "https://pan.quark.cn/favicon.ico",
                TextUtils.isEmpty(quarkCookie) ? "未登录" : "已登录"));
        if (!isAliyunTokenValid())
            list.add(makeVod("aliyun:qrcode", "🔑 阿里云扫码登录", "https://www.aliyundrive.com/favicon.ico", "扫码登录阿里云盘"));
        list.add(makeVod("aliyun:status", "📊 阿里云状态", "https://www.aliyundrive.com/favicon.ico",
                isAliyunTokenValid() ? "已登录" : "未登录"));
        return Result.string(list);
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        List<Vod> list = new ArrayList<>();

        if ("quark".equals(tid)) {
            if (TextUtils.isEmpty(quarkCookie))
                list.add(makeVod("quark:qrcode", "🔑 扫码登录", "https://pan.quark.cn/favicon.ico", "扫码登录夸克网盘"));
            list.add(makeVod("quark:status", "📊 登录状态", "https://pan.quark.cn/favicon.ico",
                    TextUtils.isEmpty(quarkCookie) ? "未登录" : "已登录"));
        } else if ("aliyun".equals(tid)) {
            if (!isAliyunTokenValid())
                list.add(makeVod("aliyun:qrcode", "🔑 扫码登录", "https://www.aliyundrive.com/favicon.ico", "扫码登录阿里云盘"));
            list.add(makeVod("aliyun:status", "📊 登录状态", "https://www.aliyundrive.com/favicon.ico",
                    isAliyunTokenValid() ? "已登录" : "未登录"));
        }

        return Result.string(list);
    }

    private Vod makeVod(String id, String name, String pic, String remark) {
        Vod v = new Vod();
        v.setVodId(id);
        v.setVodName(name);
        v.setVodPic(pic);
        v.setVodRemarks(remark);
        return v;
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String id = ids.get(0);
        if (TextUtils.isEmpty(id)) return "";

        if (id.startsWith("quark:") || id.startsWith("aliyun:")) {
            String display = id.startsWith("quark:") ? "夸克网盘" : "阿里云盘";
            String action = id.contains("qrcode") ? "扫码登录" : "登录状态";

            JSONObject obj = new JSONObject();
            obj.put("vod_id", id);
            obj.put("vod_name", display + " - " + action);
            obj.put("vod_pic", "https://pan.quark.cn/favicon.ico");
            obj.put("vod_year", "");
            obj.put("vod_area", "");
            obj.put("vod_remarks", "点击播放");
            obj.put("vod_play_from", "云操作");
            obj.put("vod_play_url", action + "$" + id);
            JSONObject wrap = new JSONObject();
            wrap.put("list", new org.json.JSONArray().put(obj));
            return wrap.toString();
        }

        return "";
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        SpiderDebug.log("CloudDrive playerContent: flag=" + flag + " id=" + id);

        if (TextUtils.isEmpty(id)) return Result.get().url("").string();

        if (id.startsWith("quark:")) {
            String action = id.substring(6); // "qrcode" or "status"
            return handleCloudAction("quark", action);
        }
        if (id.startsWith("aliyun:")) {
            String action = id.substring(7);
            return handleCloudAction("aliyun", action);
        }

        return Result.get().url("").string();
    }

    private String handleCloudAction(String cloud, String action) throws Exception {
        SpiderDebug.log("CloudDrive handle: " + cloud + "/" + action);

        if ("qrcode".equals(action)) {
            return handleQRCode(cloud);
        } else if ("status".equals(action)) {
            return handleStatus(cloud);
        }
        return Result.get().url("").string();
    }

    private String handleQRCode(String cloud) throws Exception {
        String name = "quark".equals(cloud) ? "夸克网盘" : "阿里云盘";
        String qrData;

        if ("quark".equals(cloud)) {
            Map<String, String> h = new HashMap<>();
            h.put("User-Agent", "Mozilla/5.0");
            h.put("Referer", "https://pan.quark.cn/");

            String resp = OkHttp.string("https://uop.quark.cn/cas/qrcode/getQrcode?client_id=532&v=1.2", h);
            JSONObject json = new JSONObject(resp);
            qrData = json.optString("qrUrl", "");
            SpiderDebug.log("Quark QR: " + qrData);
        } else {
            Map<String, String> h = new HashMap<>();
            h.put("User-Agent", "Mozilla/5.0");
            h.put("Content-Type", "application/json");

            JSONObject body = new JSONObject();
            String resp = OkHttp.post("https://api.aliyundrive.com/v3/qrcode/generate", body.toString(), h).getBody();
            JSONObject json = new JSONObject(resp);
            qrData = json.optString("codeUrl", "");
            JSONObject data = json.optJSONObject("data");
            if (TextUtils.isEmpty(qrData) && data != null) qrData = data.optString("codeUrl", "");
            SpiderDebug.log("Aliyun QR: " + qrData);
        }

        if (TextUtils.isEmpty(qrData)) {
            return Result.get().url("").msg("获取二维码失败").string();
        }

        // 用 api.qrserver.com 生成二维码图片 URL 作为播放地址
        String playUrl = "https://api.qrserver.com/v1/create-qr-code/?size=300x300&data="
                + java.net.URLEncoder.encode(qrData, "UTF-8");

        return Result.get().url(playUrl).string();
    }

    private String handleStatus(String cloud) throws Exception {
        String name = "quark".equals(cloud) ? "夸克网盘" : "阿里云盘";
        String cookie = "quark".equals(cloud) ? quarkCookie : aliyunAccessToken;
        boolean loggedIn = !TextUtils.isEmpty(cookie);

        // 返回一个状态页面（用 data: URI 作为播放地址？）
        String html = "<html><body style='background:#1a1a2e;color:#eee;padding:20px;text-align:center;font-family:sans-serif;'>"
                + "<h2 style='color:" + (loggedIn ? "#2ecc71" : "#e74c3c") + "'>" + name + " "
                + (loggedIn ? "✅ 已登录" : "❌ 未登录") + "</h2>"
                + "<p>" + (loggedIn ? "凭证: " + maskStr(cookie, 40) : "请先扫码登录") + "</p>"
                + "</body></html>";

        String dataUri = "data:text/html;charset=utf-8," + java.net.URLEncoder.encode(html, "UTF-8");
        return Result.get().url(dataUri).string();
    }

    private String maskStr(String s, int maxLen) {
        if (TextUtils.isEmpty(s)) return "";
        if (s.length() <= 8) return s.substring(0, 2) + "****";
        return s.substring(0, 4) + "****" + s.substring(s.length() - 4);
    }
}