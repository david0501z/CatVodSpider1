package com.github.catvod.spider;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Filter;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Quark extends Spider {

    private static final String API_URL = "https://drive-pc.quark.cn/1/clouddrive/";
    private static final String PR = "pr=ucpro&fr=pc";
    private static final String PREFS_NAME = "quark_login";
    private static final String KEY_COOKIE = "cookie";
    private static final Pattern SHARE_URL_PATTERN = Pattern.compile("https://pan\\.quark\\.cn/s/([a-zA-Z0-9]+)");

    private String cookie;
    private Context context;
    private SharedPreferences prefs;

    // ==================== 生命周期 ====================

    @Override
    public void init(Context context, String extend) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        // 优先从 SharedPreferences 读，没有则用 CloudDrive 静态字段或 ext
        this.cookie = prefs.getString(KEY_COOKIE, "");
        if (TextUtils.isEmpty(this.cookie)) this.cookie = CloudDrive.quarkCookie;
        if (TextUtils.isEmpty(this.cookie) && !TextUtils.isEmpty(extend)) this.cookie = extend;
        saveCookie(this.cookie);
    }

    private void saveCookie(String c) {
        this.cookie = c;
        if (prefs != null) prefs.edit().putString(KEY_COOKIE, c).apply();
        CloudDrive.quarkCookie = c;
    }

    // ==================== 登录页面 ====================

    @Override
    public String homeContent(boolean filter) {
        List<Class> classes = new ArrayList<>();
        List<Filter> filters = new ArrayList<>();

        if (TextUtils.isEmpty(cookie)) {
            classes.add(new Class("login", "🔑 登录夸克网盘", "1"));
            filters.add(new Filter("action", "", List.of(
                    new Filter.Value("扫码登录", "qrcode"),
                    new Filter.Value("手动输入Cookie", "manual")
            )));
        } else {
            classes.add(new Class("logged", "✅ 已登录 (点击刷新)", "1"));
        }
        return Result.string(classes, new LinkedHashMap<>() {{ put("login", filters); }});
    }

    // ==================== Action 处理（扫码/手动输入） ====================

    @Override
    public String action(String action) {
        try {
            if ("qrcode".equals(action)) {
                return handleQRCode();
            } else if (action != null && action.startsWith("check_")) {
                return handleCheck(action.substring(6));
            } else if ("manual".equals(action)) {
                // 手动输入：返回一个引导说明
                return new JSONObject()
                        .put("title", "手动设置Cookie")
                        .put("desc", "1. 浏览器打开 pan.quark.cn 登录\n2. F12 → Network → 复制 Cookie\n3. 在扩展配置中填入 ext 字段")
                        .toString();
            } else if ("status".equals(action)) {
                boolean loggedIn = !TextUtils.isEmpty(cookie);
                return new JSONObject().put("logged", loggedIn).put("cookie", loggedIn ? maskCookie(cookie) : "").toString();
            }
        } catch (Exception e) {
            SpiderDebug.log("Quark action error: " + e.getMessage());
        }
        return "";
    }

    private String handleQRCode() throws Exception {
        // 夸克 QR 登录：https://uop.quark.cn/cas/
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0");
        headers.put("Referer", "https://pan.quark.cn/");

        // Step 1: 获取二维码 token
        // 先尝试获取 QR code
        String qrResp = OkHttp.string("https://uop.quark.cn/cas/qrcode/getQrcode?client_id=532&v=1.2", headers);
        SpiderDebug.log("Quark getQrcode: " + qrResp);
        JSONObject qrJson = new JSONObject(qrResp);

        String qrUrl = qrJson.optString("qrUrl", "");
        String token = qrJson.optString("token", qrJson.optString("qrcodeToken", ""));

        if (TextUtils.isEmpty(qrUrl)) {
            // 兜底
            JSONObject result = new JSONObject();
            result.put("title", "夸克扫码登录");
            result.put("desc", "请打开手机夸克App扫码");
            result.put("qrcode", "https://pan.quark.cn/");
            result.put("check", "check_" + System.currentTimeMillis());
            return result.toString();
        }

        JSONObject result = new JSONObject();
        result.put("title", "夸克扫码登录");
        result.put("desc", "打开手机夸克App扫描二维码");
        result.put("qrcode", qrUrl);
        result.put("check", "check_" + token);
        return result.toString();
    }

    private String handleCheck(String token) throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0");
        headers.put("Referer", "https://pan.quark.cn/");

        // 轮询扫码状态
        String url = "https://uop.quark.cn/cas/ajax/getServiceTicketByQrcodeToken"
                + "?client_id=532&v=1.2&token=" + token
                + "&request_id=" + java.util.UUID.randomUUID().toString().replace("-", "");

        String checkResp = OkHttp.string(url, headers);
        SpiderDebug.log("Quark checkQR: " + checkResp);
        JSONObject json = new JSONObject(checkResp);

        int code = json.optInt("code", json.optInt("status", -1));
        String msg = json.optString("msg", json.optString("message", ""));

        JSONObject result = new JSONObject();
        if (code == 0 || code == 200) {
            // 扫码成功，获取 serviceTicket，后续需要用它换 cookie
            String serviceTicket = json.optString("serviceTicket", json.optString("data", ""));
            if (!TextUtils.isEmpty(serviceTicket)) {
                // 用 serviceTicket 换 cookie（后面再完善）
                saveCookie("serviceTicket=" + serviceTicket);
                result.put("success", true);
                result.put("msg", "✅ 扫码成功");
            } else {
                result.put("success", true);
                result.put("msg", "✅ 扫码成功，获取ticket中...");
            }
        } else if (code == 1 || code == 10001) {
            // 尚未扫码
            result.put("success", false);
            result.put("msg", "⏳ 等待扫码...");
            result.put("retry", true);
        } else if (code == 10002) {
            // 已扫码，等待确认
            result.put("success", false);
            result.put("msg", "📱 请在手机上确认登录");
            result.put("retry", true);
        } else {
            result.put("success", false);
            result.put("msg", "❌ " + (TextUtils.isEmpty(msg) ? "登录失败(" + code + ")" : msg));
        }
        return result.toString();
    }

    // ==================== 分享链接解析 ====================

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

        String tokenResp = OkHttp.post(API_URL + "share/sharepage/token?" + PR, tokenBody.toString(), headers).getBody();
        SpiderDebug.log("Quark token: " + tokenResp);
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

        String listResp = OkHttp.post(API_URL + "share/sharepage/detail?" + PR, listBody.toString(), headers).getBody();
        SpiderDebug.log("Quark list: " + listResp);
        JSONObject listJson = new JSONObject(listResp);
        if (listJson.optInt("status") != 200) return Result.string(new ArrayList<>());

        JSONArray list = listJson.getJSONObject("data").optJSONArray("list");
        if (list == null) return Result.string(new ArrayList<>());

        Vod vod = new Vod();
        vod.setVodId(shareUrl);
        vod.setVodName(shareUrl.substring(shareUrl.lastIndexOf("/") + 1));
        vod.setVodPic("https://pan.quark.cn/favicon.ico");

        List<String> playUrls = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.getJSONObject(i);
            if (item.optInt("file_type") == 0) continue;
            String name = item.optString("file_name", "视频" + (i + 1));
            String fid = item.optString("fid", "");
            String size = formatSize(item.optLong("size", 0));
            playUrls.add(name + " (" + size + ")$" + fid);
        }

        vod.setVodPlayFrom("夸克网盘");
        vod.setVodPlayUrl(playUrls.isEmpty() ? shareUrl : TextUtils.join("#", playUrls));
        return Result.string(vod);
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        Map<String, String> headers = new HashMap<>();
        if (!TextUtils.isEmpty(cookie)) headers.put("Cookie", cookie);
        headers.put("User-Agent", "Mozilla/5.0");

        JSONObject body = new JSONObject();
        body.put("fids", new JSONArray().put(id));

        String resp = OkHttp.post(API_URL + "share/sharepage/download?" + PR, body.toString(), headers).getBody();
        JSONObject json = new JSONObject(resp);
        if (json.optInt("status") != 200) return Result.get().url("").string();

        JSONArray data = json.getJSONObject("data").optJSONArray("list");
        if (data == null || data.length() == 0) return Result.get().url("").string();

        String playUrl = data.getJSONObject(0).optString("download_url", "");
        if (TextUtils.isEmpty(playUrl)) return Result.get().url("").string();

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

        String target = "http://127.0.0.1:" + port + "/proxy?url=" + URLEncoder.encode(url, "UTF-8");
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
        return new Object[]{resp.code(), resp.header("Content-Type", "application/octet-stream"), resp.body().byteStream()};
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1fMB", bytes / (1024.0 * 1024));
        return String.format("%.1fGB", bytes / (1024.0 * 1024 * 1024));
    }

    private String maskCookie(String c) {
        if (TextUtils.isEmpty(c) || c.length() < 10) return c;
        return c.substring(0, 6) + "****" + c.substring(c.length() - 4);
    }
}