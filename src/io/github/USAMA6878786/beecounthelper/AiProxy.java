package io.github.USAMA6878786.beecounthelper;

import android.content.Context;
import android.os.PowerManager;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 本地 AI 代理（方案 A 的「网络侧」）。
 *
 * <h3>它解决什么</h3>
 * 蜜蜂记账的 AI 请求原本由**宿主进程**发起，识别要 6~10 秒；这段时间里宿主被系统冻结，
 * 远程 TLS 连接收不到 ACK 被重置 → {@code DioException} → 「识别失败」。
 *
 * <p>现在宿主的「视觉能力」被指向 {@code http://127.0.0.1:<port>/<上游地址>}：
 * 请求落到本类（跑在**模块进程**里，带前台服务，不会被当作后台应用冻结），
 * 由它去访问真·AI，再把响应原样交回宿主。
 *
 * <p>于是「等网络」这件事从会被冻结的进程里挪了出来；宿主即使中途被冻结，
 * 它等的只是一个本机 socket——数据在内核缓冲区里，解冻后照样读到，
 * 不存在「远程连接被重置」。
 *
 * <h3>为什么路径里带着上游地址</h3>
 * 注入给宿主的 provider 只有一个 baseUrl 字段可用，而代理必须知道该转发到哪。
 * 所以把上游地址 **hex 编码进路径**：
 * <pre>
 *   http://127.0.0.1:46321/753a2f2f.../chat/completions
 *                        └─ hex("https://open.bigmodel.cn/api/paas/v4")
 * </pre>
 * 这样代理是**无状态**的：不依赖任何共享配置，也不会因为改设置而错配。
 *
 * <h3>额外收益</h3>
 * <ul>
 *   <li>断连/429/5xx 在这里重试，宿主根本看不到这些抖动；</li>
 *   <li>同一张图（按图片 base64 的 SHA-256）重复请求直接命中缓存，几十毫秒返回。</li>
 * </ul>
 */
final class AiProxy {

    /** 缓存条目上限（一张截图一条，够用且不会吃内存）。 */
    private static final int CACHE_MAX = 6;

    /** 缓存有效期。超过就重新问 AI——图不常变，但别让旧结果挡一辈子。 */
    private static final long CACHE_TTL_MS = 10 * 60 * 1000L;

    /** 上游读超时。视觉模型慢，宿主那边给的是 120s，这里略宽一点没意义，对齐即可。 */
    private static final int READ_TIMEOUT_MS = 90_000;
    private static final int CONNECT_TIMEOUT_MS = 15_000;

    /** 重试间隔：断连/429/5xx 时退避。三次覆盖掉绝大多数瞬时故障。 */
    private static final long[] RETRY_DELAYS = new long[]{800L, 2_000L, 4_000L};

    private static volatile ServerSocket server;
    private static volatile boolean running = false;
    private static volatile int port = -1;

    /** 最近一次转发是否成功（设置页/日志用）。 */
    static volatile long lastOkAt = 0L;
    static volatile String lastError = null;
    static volatile int hitCount = 0;
    static volatile int missCount = 0;

    private static final Map<String, CacheEntry> CACHE =
            new LinkedHashMap<String, CacheEntry>(8, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                    return size() > CACHE_MAX;
                }
            };

    private AiProxy() {
    }

    static boolean isRunning() {
        return running;
    }

    static int port() {
        return port;
    }

    /** 启动（幂等）。端口固定为 {@link Const#AI_PROXY_PORT}，起不来就放弃并让宿主回退。 */
    static synchronized void start(final Context ctx) {
        if (running) {
            return;
        }
        try {
            ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),
                    Const.AI_PROXY_PORT), 8);
            server = ss;
            port = Const.AI_PROXY_PORT;
            running = true;
        } catch (Throwable t) {
            lastError = "bind failed: " + t.getMessage();
            Logx.w("[ai-proxy] 端口 " + Const.AI_PROXY_PORT + " 绑定失败，代理不启用：" + t.getMessage());
            running = false;
            return;
        }

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                Logx.i("[ai-proxy] listening on 127.0.0.1:" + port);
                while (running) {
                    try {
                        final Socket s = server.accept();
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                handle(ctx, s);
                            }
                        }, "bee-ai-conn").start();
                    } catch (Throwable e) {
                        if (!running) {
                            return;
                        }
                        Logx.w("[ai-proxy] accept 异常：" + e.getMessage());
                    }
                }
            }
        }, "bee-ai-accept");
        t.setDaemon(true);
        t.start();
        Logx.i("[ai-proxy] 已启动（端口 " + port + "）");
    }

    static synchronized void stop() {
        running = false;
        try {
            if (server != null) {
                server.close();
            }
        } catch (Throwable ignored) {
        }
        server = null;
        Logx.i("[ai-proxy] 已停止");
    }

    // ---------------------------------------------------------------- 连接处理

    private static void handle(Context ctx, Socket sock) {
        InputStream in = null;
        OutputStream out = null;
        try {
            sock.setSoTimeout(READ_TIMEOUT_MS + 30_000);
            in = new BufferedInputStream(sock.getInputStream());
            out = sock.getOutputStream();

            String requestLine = readLine(in);
            if (requestLine == null || requestLine.length() == 0) {
                return;
            }
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                writeText(out, 400, "bad request");
                return;
            }
            String method = parts[0];
            String path = parts[1];

            // 头读完（我们只用 Content-Length 和 Authorization）
            int contentLength = 0;
            String auth = null;
            String contentType = "application/json";
            for (int i = 0; i < 64; i++) {
                String line = readLine(in);
                if (line == null || line.length() == 0) {
                    break;
                }
                int c = line.indexOf(':');
                if (c <= 0) {
                    continue;
                }
                String name = line.substring(0, c).trim().toLowerCase(Locale.US);
                String value = line.substring(c + 1).trim();
                if ("content-length".equals(name)) {
                    contentLength = parseInt(value, 0);
                } else if ("authorization".equals(name)) {
                    auth = value;
                } else if ("content-type".equals(name)) {
                    contentType = value;
                }
            }

            if ("GET".equalsIgnoreCase(method)) {
                writeText(out, 200, "beeshot-proxy ok");
                return;
            }
            if (!"POST".equalsIgnoreCase(method)) {
                writeText(out, 405, "method not allowed");
                return;
            }

            byte[] body = new byte[Math.max(contentLength, 0)];
            readFully(in, body);
            Logx.i("[ai-proxy] ← " + path + " (" + body.length + "B)");

            Route route = parseRoute(path);
            if (route == null) {
                writeText(out, 404, "no upstream in path");
                return;
            }
            String upstream = route.upstream;
            String target = trimSlash(upstream) + route.tail;

            String key = imageHash(body);
            Cached cached = lookupCache(key);
            if (cached != null) {
                hitCount++;
                Logx.i("[ai-proxy] ✔ 命中缓存（" + key.substring(0, 8) + "），直接返回 "
                        + cached.body.length + "B");
                writeRaw(out, 200, cached.contentType, cached.body);
                return;
            }
            missCount++;

            PowerManager.WakeLock wl = acquire(ctx);
            try {
                Result r = forward(target, body, auth, contentType);
                if (r == null) {
                    lastError = "upstream unreachable";
                    byte[] err = ("{\"error\":{\"message\":\"BeeShot proxy 无法连接上游 AI："
                            + escape(String.valueOf(lastError)) + "\"}}").getBytes("UTF-8");
                    writeRaw(out, 502, "application/json", err);
                    return;
                }
                if (r.code == 200 && key != null) {
                    putCache(key, r);
                    lastOkAt = System.currentTimeMillis();
                    lastError = null;
                }
                Logx.i("[ai-proxy] → HTTP " + r.code + " " + r.body.length + "B（上游 "
                        + trimSlash(upstream) + "）");
                writeRaw(out, r.code, r.contentType, r.body);
            } finally {
                release(wl);
            }
        } catch (Throwable t) {
            lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
            Logx.w("[ai-proxy] 处理异常：" + t.getMessage());
            try {
                if (out != null) {
                    writeText(out, 500, "proxy error");
                }
            } catch (Throwable ignored) {
            }
        } finally {
            try {
                sock.close();
            } catch (Throwable ignored) {
            }
        }
    }

    // ---------------------------------------------------------------- 上游转发

    private static final class Result {
        int code;
        String contentType;
        byte[] body;
    }

    /**
     * 转发到真·AI，带重试。
     *
     * <p>重试条件只有三种：连不上 / 429 / 5xx。4xx（key 错、模型不存在）不重试——
     * 重试只会让用户多等，错误信息还是同一条。
     */
    private static Result forward(String target, byte[] body, String auth, String contentType) {
        for (int attempt = 0; attempt <= RETRY_DELAYS.length; attempt++) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(target).openConnection();
                c.setRequestMethod("POST");
                c.setConnectTimeout(CONNECT_TIMEOUT_MS);
                c.setReadTimeout(READ_TIMEOUT_MS);
                c.setDoOutput(true);
                c.setInstanceFollowRedirects(true);
                c.setRequestProperty("Content-Type",
                        contentType == null ? "application/json" : contentType);
                if (auth != null) {
                    c.setRequestProperty("Authorization", auth);
                }
                c.setFixedLengthStreamingMode(body.length);
                long t0 = System.currentTimeMillis();
                OutputStream os = c.getOutputStream();
                os.write(body);
                os.flush();
                os.close();

                int code = c.getResponseCode();
                InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
                byte[] resp = readAll(is);
                Logx.i("[ai-proxy] 上游 " + code + " 用时 " + (System.currentTimeMillis() - t0)
                        + "ms" + (attempt > 0 ? "（第 " + (attempt + 1) + " 次尝试）" : ""));

                if (code == 200 || (code >= 400 && code < 500 && code != 429)) {
                    Result r = new Result();
                    r.code = code;
                    r.contentType = c.getContentType() == null
                            ? "application/json" : c.getContentType();
                    r.body = resp;
                    return r;
                }
                if (code == 429 || code >= 500) {
                    if (attempt < RETRY_DELAYS.length) {
                        Logx.w("[ai-proxy] 上游 " + code + "，退避 " + RETRY_DELAYS[attempt] + "ms 重试");
                        sleep(RETRY_DELAYS[attempt]);
                        continue;
                    }
                    Result r = new Result();
                    r.code = code;
                    r.contentType = "application/json";
                    r.body = resp;
                    return r;
                }
                Result r = new Result();
                r.code = code;
                r.contentType = "application/json";
                r.body = resp;
                return r;
            } catch (Throwable e) {
                Logx.w("[ai-proxy] 转发失败（第 " + (attempt + 1) + " 次）："
                        + e.getClass().getSimpleName() + " " + e.getMessage());
                if (attempt < RETRY_DELAYS.length) {
                    sleep(RETRY_DELAYS[attempt]);
                } else {
                    lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
                    return null;
                }
            } finally {
                if (c != null) {
                    c.disconnect();
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- 缓存

    private static final class CacheEntry {
        byte[] body;
        String contentType;
        long at;
    }

    private static final class Cached {
        byte[] body;
        String contentType;
    }

    private static Cached lookupCache(String key) {
        if (key == null) {
            return null;
        }
        synchronized (CACHE) {
            CacheEntry e = CACHE.get(key);
            if (e == null) {
                return null;
            }
            if (System.currentTimeMillis() - e.at > CACHE_TTL_MS) {
                CACHE.remove(key);
                return null;
            }
            Cached c = new Cached();
            c.body = e.body;
            c.contentType = e.contentType;
            return c;
        }
    }

    private static void putCache(String key, Result r) {
        synchronized (CACHE) {
            CacheEntry e = new CacheEntry();
            e.body = r.body;
            e.contentType = r.contentType;
            e.at = System.currentTimeMillis();
            CACHE.put(key, e);
        }
    }

    /**
     * 请求体里图片 base64 的 SHA-256。
     *
     * <p>不能拿整个 body 当键：prompt 里带着「当前时间」，每次都不一样，
     * 那样缓存永远不命中。图片才是这张截图的身份。
     */
    private static String imageHash(byte[] body) {
        try {
            String s = new String(body, "UTF-8");
            int at = s.indexOf("base64,");
            if (at < 0) {
                return null;
            }
            int start = at + "base64,".length();
            int end = s.indexOf('"', start);
            if (end <= start) {
                return null;
            }
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(s.substring(start, end).getBytes("UTF-8"));
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) {
                sb.append(String.format(Locale.US, "%02x", b));
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------------- 路径编解码

    /** 把上游地址编进路径：{@code /u/<hex>}。 */
    static String encodeUpstream(String upstream) {
        if (upstream == null) {
            return "";
        }
        byte[] raw;
        try {
            raw = upstream.getBytes("UTF-8");
        } catch (Throwable t) {
            raw = upstream.getBytes();
        }
        StringBuilder sb = new StringBuilder(raw.length * 2);
        for (byte b : raw) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static final class Route {
        String upstream;
        String tail;
    }

    /**
     * 从请求路径里拆出「上游地址」和「上游路径」。
     *
     * <p>{@code /u/<hex>/chat/completions} → upstream=hex 解出来的地址，
     * tail= {@code /chat/completions}。拆不出来返回 null（不是我们的请求）。
     */
    private static Route parseRoute(String path) {
        try {
            int at = path.indexOf("/u/");
            if (at < 0) {
                return null;
            }
            int start = at + 3;
            int end = path.indexOf('/', start);
            String hex = end < 0 ? path.substring(start) : path.substring(start, end);
            if (hex.length() < 2 || hex.length() % 2 != 0) {
                return null;
            }
            byte[] raw = new byte[hex.length() / 2];
            for (int i = 0; i < raw.length; i++) {
                raw[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            }
            Route r = new Route();
            r.upstream = new String(raw, "UTF-8");
            r.tail = end < 0 ? "/chat/completions" : path.substring(end);
            return r;
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------------- 小工具

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(128);
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                buf.write(c);
            }
            if (buf.size() > 8192) {
                break;
            }
        }
        if (c < 0 && buf.size() == 0) {
            return null;
        }
        return buf.toString("ISO-8859-1");
    }

    private static void readFully(InputStream in, byte[] dst) throws IOException {
        int off = 0;
        while (off < dst.length) {
            int n = in.read(dst, off, dst.length - off);
            if (n < 0) {
                throw new IOException("eof, want " + dst.length + " got " + off);
            }
            off += n;
        }
    }

    private static byte[] readAll(InputStream is) throws IOException {
        if (is == null) {
            return new byte[0];
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static void writeRaw(OutputStream out, int code, String contentType, byte[] body)
            throws IOException {
        String head = "HTTP/1.1 " + code + " " + reason(code) + "\r\n"
                + "Content-Type: " + (contentType == null ? "application/json" : contentType) + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes("ISO-8859-1"));
        out.write(body);
        out.flush();
    }

    private static void writeText(OutputStream out, int code, String text) throws IOException {
        writeRaw(out, code, "text/plain; charset=utf-8", text.getBytes("UTF-8"));
    }

    private static String reason(int code) {
        switch (code) {
            case 200:
                return "OK";
            case 400:
                return "Bad Request";
            case 404:
                return "Not Found";
            case 405:
                return "Method Not Allowed";
            case 500:
                return "Internal Server Error";
            case 502:
                return "Bad Gateway";
            default:
                return "Status";
        }
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    private static String trimSlash(String s) {
        if (s == null) {
            return "";
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static PowerManager.WakeLock acquire(Context ctx) {
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm == null) {
                return null;
            }
            PowerManager.WakeLock wl =
                    pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BeeShot:ai");
            wl.setReferenceCounted(false);
            wl.acquire(READ_TIMEOUT_MS + 30_000L);
            return wl;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void release(PowerManager.WakeLock wl) {
        try {
            if (wl != null && wl.isHeld()) {
                wl.release();
            }
        } catch (Throwable ignored) {
        }
    }
}
