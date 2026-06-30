package main

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"flag"
	"strconv"
	"strings"
)

var version = "v1.0.0"

func main() {
	portFlag := flag.String("port", "", "port to listen on")
	flag.Parse()
	if *portFlag != "" {
		os.Setenv("PORT", *portFlag)
	}
	port := os.Getenv("PORT")
	if port == "" {
		port = "5758"
	}

	mux := http.NewServeMux()
	mux.HandleFunc("/proxy", proxyHandler)          // 通用视频代理
	mux.HandleFunc("/quark/proxy", quarkProxyHandler) // 夸克视频代理
	mux.HandleFunc("/health", healthHandler)

	addr := ":" + port
	log.Printf("CloudDrive Proxy %s listening on %s", version, addr)
	if err := http.ListenAndServe(addr, mux); err != nil {
		log.Fatalf("server error: %v", err)
	}
}

func healthHandler(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]string{"status": "ok", "version": version})
}

// === 通用代理 ===
// Java spider 调用: proxy?url=<base64(url)>&header=<base64(json_header)>
func proxyHandler(w http.ResponseWriter, r *http.Request) {
	targetURL := r.URL.Query().Get("url")
	if targetURL == "" {
		http.Error(w, "missing url", http.StatusBadRequest)
		return
	}
	// Decode base64 url if encoded
	if strings.HasPrefix(targetURL, "http") == false {
		if d, err := base64.URLEncoding.DecodeString(targetURL); err == nil {
			targetURL = string(d)
		}
	}

	headerStr := r.URL.Query().Get("header")
	headers := make(map[string]string)
	if headerStr != "" {
		if d, err := base64.URLEncoding.DecodeString(headerStr); err == nil {
			json.Unmarshal(d, &headers)
		}
	}

	proxyRequest(w, r, targetURL, headers)
}

// === 夸克代理 ===
// 夸克下载链接需要带 cookie 和特定的 User-Agent
func quarkProxyHandler(w http.ResponseWriter, r *http.Request) {
	url := r.URL.Query().Get("url")
	cookie := r.URL.Query().Get("cookie")
	if url == "" {
		http.Error(w, "missing url", http.StatusBadRequest)
		return
	}

	headers := map[string]string{
		"User-Agent": "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0.6099.230",
		"Referer":    "https://pan.quark.cn/",
	}
	if cookie != "" {
		headers["Cookie"] = cookie
	}

	proxyRequest(w, r, url, headers)
}

// 通用转发请求，支持 Range
func proxyRequest(w http.ResponseWriter, r *http.Request, targetURL string, extraHeaders map[string]string) {
	client := &http.Client{}

	req, err := http.NewRequest(r.Method, targetURL, nil)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	// 透传 Range 头（视频拖动关键）
	if rangeHeader := r.Header.Get("Range"); rangeHeader != "" {
		req.Header.Set("Range", rangeHeader)
	}
	// 透传其他有用头
	for _, h := range []string{"If-Modified-Since", "If-None-Match", "Accept-Encoding"} {
		if v := r.Header.Get(h); v != "" {
			req.Header.Set(h, v)
		}
	}
	// 设置自定义头
	for k, v := range extraHeaders {
		req.Header.Set(k, v)
	}

	resp, err := client.Do(req)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}
	defer resp.Body.Close()

	// 透传响应头
	for k := range resp.Header {
		vals := resp.Header.Values(k)
		for _, v := range vals {
			w.Header().Add(k, v)
		}
	}
	w.WriteHeader(resp.StatusCode)

	// 流式转发
	if resp.ContentLength > 0 {
		w.Header().Set("Content-Length", strconv.FormatInt(resp.ContentLength, 10))
	}
	io.Copy(w, resp.Body)
}

// 静态文件服务（支持直接访问目录下的文件）
func init() {
	log.SetFlags(log.LstdFlags | log.Lshortfile)
	_ = version
	_ = fmt.Sprintf
}
