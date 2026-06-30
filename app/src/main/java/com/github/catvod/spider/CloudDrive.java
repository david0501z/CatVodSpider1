package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;

import com.github.catvod.bean.Vod;
import com.github.catvod.bean.Result;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CloudDrive extends Spider {

    // ---- Go 代理进程管理 ----
    private static Process goProcess;
    private static int goPort = -1;
    private Context context;

    // ---- 各网盘凭证（静态，子 spider 直接读） ----
    public static String quarkCookie = "";
    public static String aliyunToken = "";

    public static int getGoPort() { return goPort; }

    private synchronized void startGoProxy() {
        if (goProcess != null && goProcess.isAlive()) return;
        try {
            String binName = "cloudproxy_arm64";
            File binFile = new File(context.getFilesDir(), binName);
            if (!binFile.exists()) {
                download(binFile, "https://r.david525.cloudns.ch/" + binName);
            }
            binFile.setExecutable(true);

            java.net.ServerSocket ss = new java.net.ServerSocket(0);
            goPort = ss.getLocalPort();
            ss.close();

            ProcessBuilder pb = new ProcessBuilder(binFile.getAbsolutePath());
            pb.environment().put("PORT", String.valueOf(goPort));
            pb.directory(context.getFilesDir());
            goProcess = pb.start();
            Thread.sleep(1000);
            SpiderDebug.log("Go proxy started on port " + goPort);
        } catch (Exception e) {
            SpiderDebug.log("startGoProxy failed: " + e.getMessage());
        }
    }

    private void download(File target, String url) throws Exception {
        okhttp3.OkHttpClient c = new okhttp3.OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                .followRedirects(true)
                .build();
        okhttp3.Request req = new okhttp3.Request.Builder().url(url).build();
        okhttp3.Response resp = c.newCall(req).execute();
        try (FileOutputStream fos = new FileOutputStream(target);
             InputStream is = resp.body().byteStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
        }
    }

    private void stopGoProxy() {
        if (goProcess != null) {
            goProcess.destroyForcibly();
            goProcess = null;
            goPort = -1;
        }
    }

    // ---- 初始化：解析 ext JSON，启动 Go 代理 ----
    @Override
    public void init(Context context, String extend) {
        this.context = context;
        // ext 格式: {"quark_cookie":"xxx","aliyun_token":"xxx"}
        if (!TextUtils.isEmpty(extend)) {
            try {
                JSONObject obj = new JSONObject(extend);
                if (obj.has("quark_cookie")) quarkCookie = obj.optString("quark_cookie", "");
                if (obj.has("aliyun_token")) aliyunToken = obj.optString("aliyun_token", "");
            } catch (Exception e) {
                // 兼容纯字符串 = quark cookie
                quarkCookie = extend;
            }
        }
        startGoProxy();
    }

    @Override
    public void destroy() { stopGoProxy(); }

    // ---- 分发 ----
    @Override
    public String detailContent(List<String> ids) throws Exception {
        String url = ids.get(0);
        if (url.contains("quark.cn")) return new Quark().detailContent(ids);
        if (url.contains("aliyundrive.com") || url.contains("alipan.com")) return new Aliyun().detailContent(ids);
        if (url.contains("123pan.com") || url.contains("123684.com")) return new Pan123().detailContent(ids);
        return "";
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        if (flag.contains("quark")) return new Quark().playerContent(flag, id, vipFlags);
        if (flag.contains("aliyun")) return new Aliyun().playerContent(flag, id, vipFlags);
        if (flag.contains("123pan")) return new Pan123().playerContent(flag, id, vipFlags);
        return "";
    }

    @Override
    public Object[] proxy(Map<String, String> params) throws Exception {
        if (goPort < 0) return null;
        String url = params.get("url");
        if (TextUtils.isEmpty(url)) return null;

        String target = "http://127.0.0.1:" + goPort + "/proxy?url=" + URLEncoder.encode(url, "UTF-8");

        Map<String, String> h = new HashMap<>();
        if (params.containsKey("Range")) h.put("Range", params.get("Range"));
        if (params.containsKey("cookie")) h.put("Cookie", params.get("cookie"));
        if (!h.isEmpty()) {
            target += "&header=" + URLEncoder.encode(new JSONObject(h).toString(), "UTF-8");
        }

        okhttp3.OkHttpClient c = new okhttp3.OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .build();
        okhttp3.Request req = new okhttp3.Request.Builder().url(target).build();
        okhttp3.Response resp = c.newCall(req).execute();
        return new Object[]{resp.code(), resp.header("Content-Type", "application/octet-stream"), resp.body().byteStream()};
    }
}