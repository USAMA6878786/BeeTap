package io.github.USAMA6878786.beetap;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「检查更新」：问一次 GitHub，比较版本号。
 *
 * <h3>为什么两条路（v4.0 起以 update.json 为准）</h3>
 * <ul>
 *   <li><b>仓库里的 {@code update.json}</b>——几十字节的小文件，走 raw.githubusercontent，
 *       没有限速。v4.0 起 APK 改为**随仓库分发**（raw 直链），它就是唯一权威：
 *       LSPosed 自带的更新检查用的也是这份，两边口径一致。</li>
 *   <li><b>GitHub Releases API</b>——信息全（tag、下载地址、说明页），但 Releases 需要人
 *       手动维护；只发仓库不发 Release 时，API 会返回旧版本。所以只做兜底。</li>
 * </ul>
 * 先走 update.json（权威），失败再走 Releases API；两条都不通才算"检查失败"。
 *
 * <h3>为什么不用 JSON 库</h3>
 * 这个模块是 javac 直接对着 android.jar 编译的，没有任何第三方依赖。
 * 这里只需要三个字段，用正则从原文里抠出来比引入 gson 划算得多。
 */
final class UpdateCheck {

    /** 权威来源：仓库里的 update.json（随版本一起提交，LSPosed 也读它）。 */
    private static final String API =
            "https://api.github.com/repos/USAMA6878786/BeeCountHelper/releases/latest";

    /**
     * update.json 的候选地址，按顺序试。
     *
     * <p><b>为什么要两条</b>（2026-11-20 实机踩的坑）：`raw.githubusercontent.com`
     * 在国内网络经常直连不通（DNS 污染/被墙），应用内的 HttpURLConnection 又不走系统代理
     * —— 结果权威源失败，兜底落回 Releases API，把旧 Release 的版本号当成"云端版本"。
     * jsDelivr 的 CDN 在国内一般可达，内容相同（同一仓库同一分支）。
     */
    private static final String[] JSON_URLS = {
            "https://raw.githubusercontent.com/USAMA6878786/BeeCountHelper/main/update.json",
            "https://cdn.jsdelivr.net/gh/USAMA6878786/BeeCountHelper@main/update.json",
    };

    /**
     * 单条请求的超时。9 秒太长：raw 被墙时是"黑洞式"超时（连接挂着不动），
     * 串行试两条就是 18 秒起步，用户感觉就是"点了半天没反应"。5 秒足够判定死活。
     */
    private static final int TIMEOUT_MS = 5_000;

    /** 两条镜像并行竞速的总等待上限。超时后放弃（后面还有 Releases 兜底）。 */
    private static final long JSON_RACE_MS = 7_000L;

    /** 检查结果。 */
    static final class Info {
        /** 是否拿到了远端版本信息。false 时只有 {@link #error} 有意义。 */
        final boolean ok;
        /** 远端版本名，形如 "2.73"（去掉了 tag 的 v 前缀）。 */
        final String latest;
        /** APK 直链（没有就是 null）。 */
        final String apkUrl;
        /** 发布页/说明页（没有就是 null）。 */
        final String pageUrl;
        /** 失败原因（给人看的）。 */
        final String error;
        /** 用的是哪条通道，写日志用。 */
        final String source;

        Info(boolean ok, String latest, String apkUrl, String pageUrl, String error, String source) {
            this.ok = ok;
            this.latest = latest;
            this.apkUrl = apkUrl;
            this.pageUrl = pageUrl;
            this.error = error;
            this.source = source;
        }
    }

    /** 当前安装的版本名（构建时注入的，见 build.sh 的 BuildInfo）。 */
    static String current() {
        return BuildInfo.VERSION_NAME;
    }

    /**
     * 拉一次远端版本信息。**阻塞**，调用方放子线程。
     *
     * <p>三个源（raw / jsDelivr / Releases API）**同时并发**，谁先成功用谁。
     * 实机踩坑：raw 和 jsDelivr 都被墙（Connection reset），只有 api.github.com 通
     * （Clash 路由差异）；串行等 JSON 超时再走 API 就是 7 秒起步。
     * 三路并发后，总耗时 ≈ 最快那条（实测 API ~1 秒）。
     */
    static Info fetch() {
        long t0 = System.currentTimeMillis();
        final java.util.concurrent.atomic.AtomicReference<Info> ok =
                new java.util.concurrent.atomic.AtomicReference<Info>(null);
        final java.util.concurrent.atomic.AtomicReference<String> lastErr =
                new java.util.concurrent.atomic.AtomicReference<String>("所有源都不可达");
        final java.util.concurrent.CountDownLatch firstOk =
                new java.util.concurrent.CountDownLatch(1);

        // JSON 路（内部再并发 raw + jsDelivr）
        Thread jt = new Thread(new Runnable() {
            @Override
            public void run() {
                Info info = tryJson();
                if (info.ok) {
                    ok.compareAndSet(null, info);
                    firstOk.countDown();
                } else {
                    lastErr.set(info.error);
                }
            }
        }, "bee-update-json");
        jt.setDaemon(true);

        // API 路
        Thread at = new Thread(new Runnable() {
            @Override
            public void run() {
                Info info = tryApi();
                if (info.ok) {
                    ok.compareAndSet(null, info);
                    firstOk.countDown();
                } else {
                    lastErr.set(info.error);
                }
            }
        }, "bee-update-api");
        at.setDaemon(true);

        jt.start();
        at.start();

        try {
            firstOk.await(JSON_RACE_MS + TIMEOUT_MS,
                    java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        Info found = ok.get();
        if (found != null) {
            Logx.i("[update] 完成 用时 " + (System.currentTimeMillis() - t0)
                    + "ms（" + found.source + "）");
            return found;
        }
        Logx.w("[update] 全部失败 用时 " + (System.currentTimeMillis() - t0)
                + "ms：" + lastErr.get());
        return new Info(false, null, null, null, lastErr.get(), "none");
    }

    private static Info tryApi() {
        try {
            String body = http(API);
            if (body == null) {
                return new Info(false, null, null, null, "连不上 GitHub", "api");
            }
            String tag = pick(body, "\"tag_name\"\\s*:\\s*\"([^\"]+)\"");
            if (tag == null || tag.length() == 0) {
                return new Info(false, null, null, null, "GitHub 返回里没有版本号", "api");
            }
            String apk = pick(body, "\"browser_download_url\"\\s*:\\s*\"([^\"]+\\.apk)\"");
            String page = pick(body, "\"html_url\"\\s*:\\s*\"(https://github\\.com/[^\"]+/releases/tag/[^\"]+)\"");
            String version = normalize(tag);
            Logx.i("[update] API: tag=" + tag + " apk=" + apk);
            return new Info(true, version, apk, page, null, "api");
        } catch (Throwable t) {
            return new Info(false, null, null, null, shortMsg(t), "api");
        }
    }

    /**
     * 两条镜像**并行竞速**，谁先回来用谁的。
     *
     * <p>为什么不用串行：raw 被墙时是"黑洞超时"（连接挂着不动直到超时），
     * 串行等完第一条再试第二条，用户面前的「检查更新」要干等十几秒。
     * 并行之后，总耗时 ≈ 较快那条的耗时（实测 1~3 秒）。
     */
    private static Info tryJson() {
        final java.util.concurrent.atomic.AtomicReference<Info> ok =
                new java.util.concurrent.atomic.AtomicReference<Info>(null);
        final java.util.concurrent.atomic.AtomicReference<String> lastErr =
                new java.util.concurrent.atomic.AtomicReference<String>("没有可用的 update.json 地址");
        final java.util.concurrent.CountDownLatch done =
                new java.util.concurrent.CountDownLatch(JSON_URLS.length);
        // 第一个成功就放行，不必等另一条（可能正在黑洞超时）
        final java.util.concurrent.CountDownLatch firstOk =
                new java.util.concurrent.CountDownLatch(1);

        for (int i = 0; i < JSON_URLS.length; i++) {
            final String url = JSON_URLS[i];
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        String body = http(url);
                        if (body == null) {
                            lastErr.set("连不上 " + hostOf(url));
                            Logx.w("[update] " + lastErr.get());
                            return;
                        }
                        String tag = pick(body, "\"version\"\\s*:\\s*\"([^\"]+)\"");
                        String apk = pick(body, "\"zipUrl\"\\s*:\\s*\"([^\"]+)\"");
                        if (tag == null || tag.length() == 0) {
                            lastErr.set("update.json 里没有版本号（" + hostOf(url) + "）");
                            Logx.w("[update] " + lastErr.get());
                            return;
                        }
                        Logx.i("[update] update.json（" + hostOf(url) + "）: version=" + tag
                                + " apk=" + apk);
                        ok.set(new Info(true, normalize(tag), apk, null, null,
                                "json（" + hostOf(url) + "）"));
                        firstOk.countDown();
                    } catch (Throwable t2) {
                        lastErr.set(shortMsg(t2));
                        Logx.w("[update] " + hostOf(url) + "：" + lastErr.get());
                    } finally {
                        done.countDown();
                    }
                }
            }, "bee-update-json");
            t.setDaemon(true);
            t.start();
        }

        // 等第一个成功；都没有就等到两条都结束（或超时），再判失败
        try {
            firstOk.await(JSON_RACE_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Info found = ok.get();
        if (found != null) {
            return found;
        }
        return new Info(false, null, null, null, lastErr.get(), "json");
    }

    /** 只为日志取个短名，别把整串 URL 打出来。 */
    private static String hostOf(String url) {
        try {
            return new java.net.URL(url).getHost();
        } catch (Throwable t) {
            return url;
        }
    }

    /** {@code v2.73} → {@code 2.73}。 */
    private static String normalize(String tag) {
        String s = tag.trim();
        if (s.startsWith("v") || s.startsWith("V")) {
            s = s.substring(1);
        }
        return s;
    }

    /**
     * 版本号比较：{@code remote > current} 返回正数，相等 0，更旧返回负数。
     *
     * <p>按点分段逐段比数字（2.72 vs 2.9 要判对，不能按字符串比）。
     * 段数不同时短的一方补 0，于是 "2.72" == "2.72.0"。
     */
    static int compare(String remote, String current) {
        if (remote == null || current == null) {
            return 0;
        }
        String[] a = remote.split("\\.");
        String[] b = current.split("\\.");
        int n = Math.max(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int x = i < a.length ? num(a[i]) : 0;
            int y = i < b.length ? num(b[i]) : 0;
            if (x != y) {
                return x > y ? 1 : -1;
            }
        }
        return 0;
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9].*$", ""));
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String pick(String body, String regex) {
        Matcher m = Pattern.compile(regex).matcher(body);
        return m.find() ? m.group(1) : null;
    }

    /** 极简 GET：只认 200，超时短，失败返回 null。 */
    private static String http(String url) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            // GitHub API 强制要求 UA，不给就是 403
            conn.setRequestProperty("User-Agent", "BeeTap/" + BuildInfo.VERSION_NAME);
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            if (code != 200) {
                Logx.w("[update] HTTP " + code + " for " + url);
                return null;
            }
            InputStream in = conn.getInputStream();
            try {
                BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[4096];
                int n;
                while ((n = r.read(buf)) > 0) {
                    sb.append(buf, 0, n);
                    if (sb.length() > 200 * 1024) {
                        break;   // 只需要前面这些字段，别把整页读完
                    }
                }
                return sb.toString();
            } finally {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String shortMsg(Throwable t) {
        String m = t.getMessage();
        if (m == null || m.length() == 0) {
            return t.getClass().getSimpleName();
        }
        return m.length() > 80 ? m.substring(0, 80) : m;
    }

    private UpdateCheck() {
    }
}
