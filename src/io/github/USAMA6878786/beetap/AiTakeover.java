package io.github.USAMA6878786.beetap;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 接管宿主 AI 配置（跑在**蜜蜂记账进程**里，由 Xposed 钩子驱动）。
 *
 * <h3>为什么改「读取」而不是「文件」</h3>
 * 模块 App 的 root shell 跑在全局挂载命名空间，看不到宿主的数据目录（作者在那条路上踩过坑，
 * 见 {@link Const#ACTION_QUERY} 的注释）；就算能改文件，宿主进程里的
 * {@code SharedPreferencesImpl} 也早已把内容读进内存，改文件它看不见。
 *
 * <p>所以直接在宿主进程里劫持 {@code SharedPreferencesImpl.getAll()/getString()}：
 * 把 {@code flutter.ai_providers_v2} 里塞一个指向本地代理的 provider，
 * 并把 {@code flutter.ai_capability_binding_v2} 的 **vision** 指向它。
 *
 * <p>好处：不改文件、不需要重启宿主、app 自己怎么写 prefs 都盖不掉，
 * 关掉模块开关就立即恢复原状（不留任何持久化痕迹）。
 *
 * <h3>为什么只改 vision</h3>
 * 文本对话 / 账本 Agent 走的是 {@code textProviderId}，一点没动——它们仍然直连 AI。
 * 本方案只接管「截图记账」这一条视觉链路，避免把别的功能一起拖进代理。
 */
final class AiTakeover {

    /** 总开关（模块 App 可通过 {@link Const#ACTION_AI_MODE} 广播改）。 */
    private static volatile boolean enabled = true;

    /** 代理存活探测的缓存时刻与结果（避免每次读 prefs 都去连一次）。 */
    private static volatile long probeAt = 0L;
    private static volatile boolean probeOk = false;

    /** 探测结果的缓存时长。2 秒足够：一次记账流程里最多读两次 prefs。 */
    private static final long PROBE_TTL_MS = 2_000L;

    /** 注入的 provider id，和 {@link AiProxy} 的路径约定配套。 */
    private static final String LOCAL_ID = "beeshot_local";

    private AiTakeover() {
    }

    static void setEnabled(boolean v) {
        if (enabled != v) {
            enabled = v;
            probeAt = 0L;
            Logx.i("[ai-hook] 接管开关 -> " + v);
        }
    }

    static boolean enabled() {
        return enabled;
    }

    /**
     * 现在要不要改写配置。
     *
     * <p><b>必须先确认代理真的在监听</b>：如果改写之后代理没起来，宿主的视觉请求会
     * 直接连不上 127.0.0.1，反而把「偶尔识别失败」变成「永远识别失败」。
     * 所以探不通就原样返回，让宿主走它自己的老路。
     */
    static boolean shouldRewrite() {
        if (!enabled) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - probeAt < PROBE_TTL_MS) {
            return probeOk;
        }
        probeOk = probe();
        probeAt = now;
        return probeOk;
    }

    private static boolean probe() {
        Socket s = null;
        try {
            s = new Socket();
            s.connect(new InetSocketAddress("127.0.0.1", Const.AI_PROXY_PORT), 120);
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            try {
                if (s != null) {
                    s.close();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    // ---------------------------------------------------------------- 改写

    /**
     * 改写一整个 prefs 快照（{@code getAll()} 的返回值）。
     *
     * <p>这是主路径：Flutter 的 shared_preferences 在 Dart 侧启动时一次性把整张表读走，
     * 只挂 {@code getString} 是拦不到的。
     *
     * @return 改写后的新 Map（**不改原 Map**，避免污染宿主的内部状态）；无需改写时返回 null
     */
    static Map<String, Object> rewriteAll(Map<?, ?> raw) {
        if (raw == null || !shouldRewrite()) {
            return null;
        }
        Object provObj = raw.get(Const.K_AI_PROVIDERS);
        Object bindObj = raw.get(Const.K_AI_BINDING);
        if (!(provObj instanceof String) || !(bindObj instanceof String)) {
            return null;
        }
        try {
            String newProviders = rewriteProviders((String) provObj, (String) bindObj);
            if (newProviders == null) {
                return null;
            }
            String newBinding = rewriteBinding((String) bindObj);
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> e : raw.entrySet()) {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
            out.put(Const.K_AI_PROVIDERS, newProviders);
            out.put(Const.K_AI_BINDING, newBinding);
            Logx.i("[ai-hook] 已把 vision 指向本地代理（getAll）");
            return out;
        } catch (Throwable t) {
            Logx.w("[ai-hook] 改写 providers 失败：" + t.getMessage());
            return null;
        }
    }

    /** 单键读取路径（{@code getString}）。拿不到另一份配置时按「内置智谱」兜底。 */
    static String rewriteOne(String key, String value) {
        if (value == null || !shouldRewrite()) {
            return null;
        }
        try {
            if (Const.K_AI_BINDING.equals(key)) {
                return rewriteBinding(value);
            }
            if (Const.K_AI_PROVIDERS.equals(key)) {
                String rewritten = rewriteProviders(value, null);
                if (rewritten != null) {
                    Logx.i("[ai-hook] 已把 vision 指向本地代理（getString）");
                }
                return rewritten;
            }
        } catch (Throwable t) {
            Logx.w("[ai-hook] 单键改写失败：" + t.getMessage());
        }
        return null;
    }

    /**
     * 往 providers 数组里加/更新代理条目。
     *
     * @param bindingJson 能力绑定（可能为 null）。用来判断「vision 当前用的是哪个 provider」，
     *                    从而复制正确的 apiKey 与 visionModel —— 用户可能把视觉绑到了自定义服务商。
     * @return 新 JSON；无法改写（缺 key / 缺模型）时返回 null
     */
    private static String rewriteProviders(String json, String bindingJson) {
        try {
            return rewriteProvidersImpl(json, bindingJson);
        } catch (Throwable t) {
            Logx.w("[ai-hook] providers 解析失败：" + t.getMessage());
            return null;
        }
    }

    /** 真正的实现。org.json 在 Android 上抛的是**受检**异常，统一由上面那层兜。 */
    private static String rewriteProvidersImpl(String json, String bindingJson) throws Exception {
        JSONArray arr = new JSONArray(json);

        String wantedId = "zhipu_glm";
        if (bindingJson != null) {
            try {
                JSONObject b = new JSONObject(bindingJson);
                String v = b.optString("visionProviderId", "");
                if (v.length() > 0) {
                    wantedId = v;
                }
            } catch (Throwable ignored) {
            }
        }

        JSONObject source = null;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && wantedId.equals(o.optString("id"))) {
                source = o;
                break;
            }
        }
        if (source == null) {
            // 找不到指定 provider（比如被删了），退回内置那个
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null && "zhipu_glm".equals(o.optString("id"))) {
                    source = o;
                    break;
                }
            }
        }
        if (source == null) {
            return null;
        }

        String apiKey = source.optString("apiKey", "");
        String visionModel = source.optString("visionModel", "");
        String upstream = source.optString("baseUrl", "");
        if (apiKey.length() == 0 || visionModel.length() == 0 || upstream.length() == 0) {
            // 没法构造一个可用的代理条目，原样放行；宿主会按它自己的配置直连
            return null;
        }
        // 防御：如果上一条本来就是我们的代理地址，说明读到的已经是注入后的值，别二次套娃
        if (upstream.startsWith("http://127.0.0.1:")) {
            return null;
        }

        JSONObject proxy = new JSONObject();
        try {
            proxy.put("id", LOCAL_ID);
            proxy.put("name", "BeeShot Local（模块代理）");
            proxy.put("isBuiltIn", false);
            proxy.put("apiKey", apiKey);
            proxy.put("baseUrl", "http://127.0.0.1:" + Const.AI_PROXY_PORT
                    + "/u/" + AiProxy.encodeUpstream(upstream));
            // 文本/语音留空：这个 provider 只能做视觉，防止别的能力误用它
            proxy.put("textModel", "");
            proxy.put("visionModel", visionModel);
            proxy.put("audioModel", "");
            proxy.put("createdAt", "2026-01-01T00:00:00.000");
        } catch (Throwable t) {
            return null;
        }

        JSONArray out = new JSONArray();
        boolean replaced = false;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && LOCAL_ID.equals(o.optString("id"))) {
                out.put(proxy);
                replaced = true;
            } else if (o != null) {
                out.put(o);
            }
        }
        if (!replaced) {
            out.put(proxy);
        }
        Logx.i("[ai-hook] providers: 上游=" + upstream + " 模型=" + visionModel
                + "（从 " + source.optString("id") + " 复制）");
        return out.toString();
    }

    /** 只把 vision 指到本地代理，text/speech 保持原样。 */
    private static String rewriteBinding(String json) {
        try {
            JSONObject b = new JSONObject(json);
            if (LOCAL_ID.equals(b.optString("visionProviderId"))) {
                return null;
            }
            b.put("visionProviderId", LOCAL_ID);
            return b.toString();
        } catch (Throwable t) {
            return null;
        }
    }
}
