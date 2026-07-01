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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Config — 网盘配置管理（csp_Config）
 * 
 * 独立的登录 UI spider，负责：
 * - 夸克网盘扫码登录 / 手动设置 Cookie
 * - 阿里云盘扫码登录 / 手动设置 Token
 * - 登录状态查看 / 退出
 * 
 * 凭证存为静态字段，供 Quark/Aliyun/CloudDrive 使用。
 * 
 * TVBox 交互模式（FongMi）：
 * homeContent → 显示操作卡片列表
 * detailContent → 返回详情页，play_url 指向操作标识
 * playerContent → 执行实际动作（扫码、状态查询等）
 */
public class Config extends Spider {

    // ==================== 凭证存储区 ====================
    
    /** 夸克 Cookie */
    public static String quarkCookie = "";
    /** 阿里云 Access Token */
    public static String aliyunToken = "";
    /** 阿里云 Refresh Token */
    public static String aliyunRefreshToken = "";
    /** 阿里云 Token 过期时间戳 */
    public static long aliyunTokenExpiresAt = 0;

    public static boolean isAliyunTokenValid() {
        return !TextUtils.isEmpty(aliyunToken) && System.currentTimeMillis() < aliyunTokenExpiresAt - 60000;
    }

    @Override
    public void init(Context context, String extend) {
        if (!TextUtils.isEmpty(extend)) {
            try {
                JSONObject obj = new JSONObject(extend);
                if (obj.has("quark_cookie")) quarkCookie = obj.optString("quark_cookie", "");
                if (obj.has("aliyun_token")) aliyunToken = obj.optString("aliyun_token", "");
                if (obj.has("aliyun_refresh_token")) aliyunRefreshToken = obj.optString("aliyun_refresh_token", "");
                if (obj.has("aliyun_expires_at")) aliyunTokenExpiresAt = obj.optLong("aliyun_expires_at", 0);
            } catch (Exception e) {
                quarkCookie = extend;
            }
        }
    }

    // ==================== 首页：显示操作卡片 ====================

    @Override
    public String homeContent(boolean filter) {
        List<Vod> list = new ArrayList<>();

        // ---- 夸克 ----
        boolean qOk = !TextUtils.isEmpty(quarkCookie);
        list.add(makeVod("quark:status", "📊 夸克网盘" + (qOk ? " ✅" : " ❌"), 
                "https://pan.quark.cn/favicon.ico", qOk ? "已登录" : "未登录"));
        if (!qOk) {
            list.add(makeVod("quark:qrcode", "🔑 夸克扫码登录",
                    "https://pan.quark.cn/favicon.ico", "扫码登录"));
            list.add(makeVod("quark:manual", "✏️ 夸克手动Cookie",
                    "https://pan.quark.cn/favicon.ico", "已有Cookie点此设置"));
        } else {
            list.add(makeVod("quark:logout", "🚪 夸克退出",
                    "https://pan.quark.cn/favicon.ico", "点击退出登录"));
        }

        // ---- 阿里云 ----
        boolean aOk = isAliyunTokenValid();
        list.add(makeVod("aliyun:status", "📊 阿里云盘" + (aOk ? " ✅" : " ❌"),
                "https://www.aliyundrive.com/favicon.ico", aOk ? "已登录" : "未登录"));
        if (!aOk) {
            list.add(makeVod("aliyun:qrcode", "🔑 阿里云扫码登录",
                    "https://www.aliyundrive.com/favicon.ico", "扫码登录"));
            list.add(makeVod("aliyun:manual", "🔑 阿里云手动Token",
                    "https://www.aliyundrive.com/favicon.ico", "已有Token点此设置"));
        } else {
            list.add(makeVod("aliyun:logout", "🚪 阿里云退出",
                    "https://www.aliyundrive.com/favicon.ico", "点击退出登录"));
        }

        return Result.string(list);
    }

    // ==================== 详情页 ====================

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String id = ids.get(0);
        if (TextUtils.isEmpty(id)) return "";

        String action = id.contains("qrcode") ? "扫码" : id.contains("status") ? "状态" 
                : id.contains("manual") ? "设置" : id.contains("logout") ? "退出" : "操作";
        String cloud = id.startsWith("quark:") ? "夸克" : "阿里云";

        JSONObject obj = new JSONObject();
        obj.put("vod_id", id);
        obj.put("vod_name", cloud + " - " + action);
        obj.put("vod_pic", id.startsWith("quark:") ? 
                "https://pan.quark.cn/favicon.ico" : "https://www.aliyundrive.com/favicon.ico");
        obj.put("vod_play_from", "网盘操作");
        obj.put("vod_play_url", "确认$" + id);
        JSONObject wrap = new JSONObject();
        wrap.put("list", new JSONArray().put(obj));
        return wrap.toString();
    }

    // ==================== 动作执行 ====================

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        SpiderDebug.log("Config playerContent: " + id);

        if (TextUtils.isEmpty(id)) return Result.get().url("").string();

        if (id.startsWith("quark:")) return handleQuark(id.substring(6));
        if (id.startsWith("aliyun:")) return handleAliyun(id.substring(7));

        return Result.get().url("").string();
    }

    // ==================== 夸克动作 ====================

    private String handleQuark(String action) throws Exception {
        switch (action) {
            case "qrcode": return quarkQRCode();
            case "status": return quarkStatus();
            case "manual": return quarkManual();
            case "logout": return quarkLogout();
        }
        return Result.get().url("").string();
    }

    private String quarkQRCode() throws Exception {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", "Mozilla/5.0");
        h.put("Referer", "https://pan.quark.cn/");

        // 获取二维码
        String resp = OkHttp.string("https://uop.quark.cn/cas/qrcode/getQrcode?client_id=532&v=1.2", h);
        SpiderDebug.log("Quark QR: " + resp);
        JSONObject json = new JSONObject(resp);
        String qrUrl = json.optString("qrUrl", "");

        if (TextUtils.isEmpty(qrUrl)) {
            return Result.get().url("").msg("获取二维码失败").string();
        }

        // 返回二维码图片 URL 作为播放地址
        String playUrl = "https://api.qrserver.com/v1/create-qr-code/?size=300x300&data="
                + java.net.URLEncoder.encode(qrUrl, "UTF-8");
        return Result.get().url(playUrl).string();
    }

    private String quarkStatus() throws Exception {
        boolean ok = !TextUtils.isEmpty(quarkCookie);
        String text = ok ? "夸克网盘 ✅ 已登录\nCookie: " + maskStr(quarkCookie, 40) 
                : "夸克网盘 ❌ 未登录\n请扫码或手动设置Cookie";
        String html = makeStatusHtml("夸克网盘", ok, text);
        String dataUri = "data:text/html;charset=utf-8," + java.net.URLEncoder.encode(html, "UTF-8");
        return Result.get().url(dataUri).string();
    }

    private String quarkManual() throws Exception {
        // 手动设置 - 返回引导说明页面
        String html = "<html><body style='background:#1a1a2e;color:#eee;padding:20px;font-family:sans-serif;'>"
                + "<h2 style='color:#f39c12'>✏️ 手动设置夸克Cookie</h2>"
                + "<ol style='line-height:2'>"
                + "<li>浏览器打开 <b>pan.quark.cn</b> 并登录</li>"
                + "<li>按 F12 → Network → 刷新页面</li>"
                + "<li>找到任意请求 → 复制 Cookie 头</li>"
                + "<li>在 TVBox 站点设置的 ext 字段填入: <br/>"
                + "<code style='background:#333;padding:4px 8px;border-radius:4px;display:inline-block;margin-top:4px;'>"
                + "{\"quark_cookie\":\"你的cookie\"}</code></li>"
                + "</ol>"
                + "<p style='color:#888'>或重新扫码登录</p>"
                + "</body></html>";
        String dataUri = "data:text/html;charset=utf-8," + java.net.URLEncoder.encode(html, "UTF-8");
        return Result.get().url(dataUri).string();
    }

    private String quarkLogout() throws Exception {
        quarkCookie = "";
        String html = "<html><body style='background:#1a1a2e;color:#eee;padding:20px;text-align:center;font-family:sans-serif;'>"
                + "<h2 style='color:#2ecc71'>✅ 已退出夸克网盘</h2>"
                + "<p>Cookie 已清除</p>"
                + "</body></html>";
        String dataUri = "data:text/html;charset=utf-8," + java.net.URLEncoder.encode(html, "UTF-8");
        return Result.get().url(dataUri).string();
    }

    // ==================== 阿里云动作 ====================

    private String handleAliyun(String action) throws Exception {
        switch (action) {
            case "qrcode": return aliyunQRCode();
            case "status": return aliyunStatus();
            case "manual": return aliyunManual();
            case "logout": return aliyunLogout();
        }
        return Result.get().url("").string();
    }

    private String aliyunQRCode() throws Exception {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", "Mozilla/5.0");
        h.put("Content-Type", "application/json");

        JSONObject body = new JSONObject();
        body.put("scopes", new JSONArray().put("user:base"));
        body.put("width", 300);

        String resp = OkHttp.post("https://open.aliyundrive.com/oauth/authorize/qrcode", 
                body.toString(), h).getBody();
        SpiderDebug.log("Aliyun QR: " + resp);
        JSONObject json = new JSONObject(resp);
        String qrUrl = json.optString("qrCodeUrl", "");

        if (TextUtils.isEmpty(qrUrl)) {
            return Result.get().url("").msg("获取二维码失败").string();
        }

        String playUrl = "https://api.qrserver.com/v1/create-qr-code/?size=300x300&data="
                + java.net.URLEncoder.encode(qrUrl, "UTF-8");
        return Result.get().url(playUrl).string();
    }

    private String aliyunStatus() throws Exception {
        boolean ok = isAliyunTokenValid();
        String text = ok ? "阿里云盘 ✅ 已登录\nToken: " + maskStr(aliyunToken, 40)
                : "阿里云盘 ❌ 未登录\n请扫码或手动设置Token";
        String html = makeStatusHtml("阿里云盘", ok, text);
        String dataUri = "data:text/html;charset=utf-8," + java.net.URLEncoder.encode(html, "UTF-8");
        return Result.get().url(dataUri).string();
    }

    private String aliyunManual() throws Exception {
        String html = "<html><body style='background:#1a1a2e;color:#eee;padding:20px;font-family:sans-serif;'>"
                + "<h2 style='color:#f39c12'>🔑 手动设置阿里云Token</h2>"
                + "<ol style='line-height:2'>"
                + "<li>浏览器打开 <b>aliyundrive.com</b> 并登录</li>"
                + "<li>F12 → Application → Local Storage → token</li>"
                + "<li>复制 access_token 的值</li>"
                + "<li>在 TVBox 站点设置的 ext 字段填入: <br/>"
                + "<code style='background:#333;padding:4px 8px;border-radius:4px;display:inline-block;margin-top:4px;'>"
                + "{\"aliyun_token\":\"你的token\"}</code></li>"
                + "</ol></body></html>";
        String dataUri = "data:text/html;charset=utf-8," + java.net.URLEncoder.encode(html, "UTF-8");
        return Result.get().url(dataUri).string();
    }

    private String aliyunLogout() throws Exception {
        aliyunToken = "";
        aliyunRefreshToken = "";
        aliyunTokenExpiresAt = 0;
        String html = "<html><body style='background:#1a1a2e;color:#eee;padding:20px;text-align:center;font-family:sans-serif;'>"
                + "<h2 style='color:#2ecc71'>✅ 已退出阿里云盘</h2>"
                + "<p>Token 已清除</p>"
                + "</body></html>";
        String dataUri = "data:text/html;charset=utf-8," + java.net.URLEncoder.encode(html, "UTF-8");
        return Result.get().url(dataUri).string();
    }

    // ==================== 工具方法 ====================

    private Vod makeVod(String id, String name, String pic, String remark) {
        Vod v = new Vod();
        v.setVodId(id);
        v.setVodName(name);
        v.setVodPic(pic);
        v.setVodRemarks(remark);
        return v;
    }

    private String makeStatusHtml(String name, boolean ok, String text) {
        return "<html><body style='background:#1a1a2e;color:#eee;padding:20px;font-family:sans-serif;'>"
                + "<h2 style='color:" + (ok ? "#2ecc71" : "#e74c3c") + ";text-align:center;'>"
                + (ok ? "✅ " : "❌ ") + name + "</h2>"
                + "<pre style='background:#16213e;padding:15px;border-radius:8px;white-space:pre-wrap;word-break:break-all;'>"
                + text + "</pre>"
                + "</body></html>";
    }

    private String maskStr(String s, int maxLen) {
        if (TextUtils.isEmpty(s)) return "";
        if (s.length() <= 8) return s.substring(0, 2) + "****";
        return s.substring(0, 4) + "****" + s.substring(s.length() - 4);
    }
}
