package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;

import com.github.catvod.bean.Vod;
import com.github.catvod.bean.Result;
import com.github.catvod.crawler.Spider;
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

public class CloudDrive extends Spider {

    private static Process goProcess;
    private static int goPort = -1;
    private static final int PORT_BASE = 18080;
    private Context context;

    public static int getGoPort() {
        return goPort;
    }

    private synchronized void startGoProxy() {
        if (goProcess != null && goProcess.isAlive()) return;
        try {
            // 找 binary：先看扩展目录，再下到 filesDir
            String binName = "cloudproxy_arm64";
            java.io.File binFile = new java.io.File(context.getFilesDir(), binName);
            if (!binFile.exists()) {
                String url = "https://r.david525.cloudns.ch/" + binName;
                OkHttp.string(url); // just warmup
                java.io.File tmp = new java.io.File(context.getCacheDir(), binName);
                OkHttp.execute(url, tmp.getAbsolutePath());
                tmp.renameTo(binFile);
            }
            binFile.setExecutable(true);

            // 找空闲端口
            java.net.ServerSocket ss = new java.net.ServerSocket(0);
            goPort = ss.getLocalPort();
            ss.close();

            ProcessBuilder pb = new ProcessBuilder(binFile.getAbsolutePath());
            pb.environment().put("PORT", String.valueOf(goPort));
            pb.directory(context.getFilesDir());
            goProcess = pb.start();
            Thread.sleep(1000);
            android.util.Log.d("CloudDrive", "Go proxy started on port " + goPort);
        } catch (Exception e) {
            android.util.Log.e("CloudDrive", "startGoProxy failed", e);
        }
    }

    private void stopGoProxy() {
        if (goProcess != null) {
            goProcess.destroyForcibly();
            goProcess = null;
            goPort = -1;
        }
    }

    @Override
    public void init(Context context, String extend) {
        this.context = context;
        startGoProxy();
    }

    @Override
    public void destroy() {
        stopGoProxy();
    }

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
        if (TextUtils.isEmpty(url)) url = params.get("uri");
        if (TextUtils.isEmpty(url)) return null;

        Map<String, String> headers = new HashMap<>();
        for (String k : params.keySet()) {
            if (!k.equals("url") && !k.equals("uri") && !k.equals("header")) {
                headers.put(k, params.get(k));
            }
        }

        JSONObject headerJson = new JSONObject(headers);
        String target = "http://127.0.0.1:" + goPort + "/proxy?url=" + URLEncoder.encode(url, "UTF-8")
                + "&header=" + URLEncoder.encode(headerJson.toString(), "UTF-8");

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