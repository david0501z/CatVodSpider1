package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CloudDrive extends Spider {

    private Quark quark;
    private Aliyun aliyun;
    private Pan123 pan123;

    @Override
    public void init(Context context, String extend) {
        quark = new Quark();
        aliyun = new Aliyun();
        pan123 = new Pan123();
        quark.init(context, extend);
        aliyun.init(context, extend);
        pan123.init(context, extend);
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
        String type = params.get("type");
        Spider spider = getSpiderByPrefix(type);
        if (spider != null) return spider.proxy(params);
        return null;
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
