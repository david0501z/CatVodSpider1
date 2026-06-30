package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.Request;
import okhttp3.Response;

public class CloudDrive extends Spider {

    private static Process goProcess;
    private static int goPort;
    private static Context appContext;

    private Quark quark;
    private Aliyun aliyun;
    private Pan123 pan123;

    @Override
    public void init(Context context, String extend) {
        appContext = context;
        quark = new Quark();
        aliyun = new Aliyun();
        pan123 = new Pan123();
        quark.init(context, extend);
        aliyun.init(context, extend);
        pan123.init(context, extend);
        startGoProxy();
    }

    @Override
    public void destroy() {
        stopGoProxy();
    }

    @Override
    public String homeContent(boolean filter) {
        List<Class> classes = new ArrayList<>();
        classes.add(new Class("quark", "夸克网盘"));
        classes.add(new Class("aliyun", "阿里云盘"));
        classes.add(new Class("123", "123云盘"));
        return Result.string(classes, null);
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        Spider spider = getSpider(tid);
        if (spider != null) return spider.categoryContent(tid, pg, filter, extend);
        return Result.string(new ArrayList<>());
    }

    @Override
    public String detailContent(List<String> ids) {
        String id = ids.get(0);
        Spider spider = detectSpider(id);
        if (spider != null) return spider.detailContent(ids);
        return Result.string(new Vod());
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        String prefix = flag.contains(".") ? flag.substring(0, flag.indexOf(".")).toLowerCase() : flag.toLowerCase();
        Spider spider = getSpiderByPrefix(prefix);
        if (spider != null) return spider.playerContent(flag, id, vipFlags);
        return Result.get().url("").string();
    }

    @Override
    public String searchContent(String key, boolean quick) {
        return "";
    }

    @Override
    public Object[] proxy(Map<String, String> params) {
        if (params == null) return null;
        if (goPort <= 0) {
            SpiderDebug.log("Go proxy not running");
            return null;
        }
        try {
            String url = params.get("url");
            String header = params.get("header");
            if (TextUtils.isEmpty(url)) {
                String type = params.get("type");
                Spider spider = getSpiderByPrefix(type);
                if (spider != null) return spider.proxy(params);
                return null;
            }
            String fullUrl = "http://127.0.0.1:" + goPort + "/proxy?url=" + URLEncoder.encode(url, "UTF-8");
            if (!TextUtils.isEmpty(header)) {
                fullUrl += "&header=" + URLEncoder.encode(header, "UTF-8");
            }
            Request request = new Request.Builder().url(fullUrl).build();
            Response response = OkHttp.newCall(request);
            String contentType = response.header("Content-Type", "application/octet-stream");
            InputStream is = response.body() != null ? response.body().byteStream() : null;
            return new Object[]{response.code(), contentType, is};
        } catch (Exception e) {
            SpiderDebug.log(e);
            return null;
        }
    }

    private void startGoProxy() {
        if (goProcess != null) return;
        try {
            Context ctx = appContext;
            if (ctx == null) return;

            String binaryName = "cloudproxy_arm64";
            File binary = new File(ctx.getFilesDir(), binaryName);
            if (!binary.exists()) {
                File extDir = ctx.getExternalFilesDir(null);
                if (extDir != null) {
                    File extBinary = new File(extDir, binaryName);
                    if (extBinary.exists()) binary = extBinary;
                }
            }
            if (!binary.exists()) {
                SpiderDebug.log("Downloading cloudproxy_arm64...");
                String downloadUrl = "https://r.david525.cloudns.ch/cloudproxy_arm64";
                InputStream in = new URL(downloadUrl).openStream();
                FileOutputStream out = new FileOutputStream(binary);
                byte[] buffer = new byte[8192];
                int len;
                while ((len = in.read(buffer)) != -1) {
                    out.write(buffer, 0, len);
                }
                in.close();
                out.close();
            }
            binary.setExecutable(true);
            int port = 18080 + (int) (Math.random() * 1000);
            ProcessBuilder pb = new ProcessBuilder(binary.getAbsolutePath(), "-port", String.valueOf(port));
            pb.directory(ctx.getFilesDir());
            goProcess = pb.start();
            Thread.sleep(1000);
            goPort = port;
            SpiderDebug.log("Go proxy started on port " + port);
        } catch (Exception e) {
            SpiderDebug.log(e);
            goProcess = null;
            goPort = 0;
        }
    }

    private void stopGoProxy() {
        if (goProcess != null) {
            goProcess.destroyForcibly();
            goProcess = null;
            goPort = 0;
            SpiderDebug.log("Go proxy stopped");
        }
    }

    public static int getGoPort() {
        return goPort;
    }

    private Spider getSpiderByPrefix(String prefix) {
        if ("quark".equals(prefix)) return quark;
        if ("aliyun".equals(prefix)) return aliyun;
        if ("123".equals(prefix)) return pan123;
        return null;
    }

    private Spider getSpider(String tid) {
        if ("quark".equals(tid)) return quark;
        if ("aliyun".equals(tid)) return aliyun;
        if ("123".equals(tid)) return pan123;
        return null;
    }

    private Spider detectSpider(String url) {
        if (TextUtils.isEmpty(url)) return null;
        if (url.contains("pan.quark.cn") || url.contains("quark")) return quark;
        if (url.contains("aliyundrive.com") || url.contains("aliyun") || url.contains("alipan.com")) return aliyun;
        if (url.contains("123pan.com") || url.contains("123pan")) return pan123;
        return null;
    }
}
