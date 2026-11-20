package io.github.USAMA6878786.beecounthelper;

import android.content.Context;
import android.content.Intent;

/**
 * 用「桌面小组件 provider」的广播把宿主进程拉起来。
 *
 * <h3>为什么可行（真机验证过）</h3>
 * 它带了十几个 {@code AppWidgetProvider}，都是导出的。给其中任意一个**点名**发广播，
 * 系统就会把它的进程拉起来 —— **不需要桌面上真的摆着那个小组件**，也**不会显示任何界面**。
 * 实测（20:56，pid 从空 → 20148）：拉起后模块的 hook 全部初始化，root 也拿到了。
 *
 * <h3>它能做到什么，做不到什么</h3>
 * <b>能做到</b>：宿主进程回来了 → 桌面小组件恢复实时数字、
 * 后续的探测/判定链路能正常应答。
 *
 * <p><b>做不到</b>：让截图被识别。识别要它的 Flutter/Dart 跑起来，而 Dart 只在
 * 它的界面存在时才运行；广播拉起的是一个**没有界面**的进程。所以这个功能是
 * "把进程叫回来"，不是"让记账变可能"。
 *
 * <h3>细节</h3>
 * - 走 root 发（普通应用向"已停止"状态的包发广播会被系统拦掉；shell 不受这个限制）
 * - 60 秒防抖：避免状态刷新时反复开 su
 * - 只在调用方已经确认"宿主没应答"时才试，不做多余的 pid 检查（少一次 su）
 */
final class HostLauncher {

    /** 3 秒内只试一次（只挡住同一次点击里的重复调用）。 */
    private static final long DEBOUNCE_MS = 3_000L;

    /**
     * 派发补发的总时长。
     *
     * <p>必须覆盖「宿主进程被回收 → 点磁贴 → 进程重新起来」这段空档
     * （本机实测约 7 秒，慢的机型更久）。原来只有 2.1 秒，连点时前几张全丢。
     */
    /**
     * 派发补发的总时长。**统一 20 秒，不要按"进程在不在"分档**。
     *
     * <p>必须覆盖「宿主进程被回收 → 点磁贴 → 进程重新起来」这段空档
     * （本机实测约 7 秒，慢的机型更久）。原来只有 2.1 秒，连点时前几张全丢。
     *
     * <p>也试过「进程在 → 只补发 6 秒」省开销，<b>实测否掉</b>：那一轮 10 张里有一张
     * 从未被派发 —— 进程当时在，但随后被冻结/回收，6 秒后补发就停了，没人再叫醒它。
     * 同一路径重发对宿主是幂等的（按 DISPATCHED_HISTORY 去重），宁可多发包也别丢图。
     */
    private static final long DISPATCH_RETRY_MS = 20_000L;

    private static volatile long lastTryAt = 0L;

    private HostLauncher() {
    }

    /**
     * 尝试把宿主进程拉起来。**必须在后台线程调用**（会开 su）。
     *
     * @return true = 广播已发出（不代表一定拉起来了，调用方需要自己再探一次确认）
     */
    static boolean pullUp(Context ctx) {
        if (ctx == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - lastTryAt < DEBOUNCE_MS) {
            // 3 秒内刚拉过一次：进程已经在了，不用再广播拉起。
            // 派发这一张由截图成功后的"指定路径派发"负责（见 ShotTileService），
            // 这里不再发无路径派发——那只会让宿主空等、和带路径派发打架。
            Logx.i("[launch] 3 秒内拉过，跳过广播（截图完成后会带路径派发）");
            return false;
        }
        lastTryAt = now;

        String pkg = HostInfo.pkg(ctx);
        if (pkg == null || pkg.length() == 0) {
            Logx.w("[launch] 不知道宿主包名，放弃");
            return false;
        }
        if (!RootShell.isGranted(ctx)) {
            Logx.w("[launch] 没有 root，无法发广播拉起");
            return false;
        }
        try {
            // 一次 su 里做两件事：进程已经活着就 exit 3 直接跳过（省掉一次无用广播），
            // 否则点名发给它的小组件 provider。provider 收到后未必做什么事
            // —— 我们只关心"进程被创建"。
            // 拉起进程之后**立刻**就没窗口可言了：进程刚起来就会被系统当成空进程回收。
            // 所以不再靠"等 1.5 秒再发广播"——改成**留一个标记文件**：
            // 宿主进程的 Application.onCreate 一进来就检查它，存在就直接起引擎。
            // 那里是进程生命的起点，不存在"接收器还没注册"的窗口。
            // 关键：**先写标记，再广播**，而且不判断进程在不在。
            // 因为桌面上的小组件随时可能自己查询宿主、把进程拉起来——
            // 那时代码已经进 Application.onCreate 了，我们后面再写标记就晚了
            // （实测：21:51 和 22:07 两轮都是这么失败的）。
            // 标记里带时间戳，宿主只认 10 秒内的，避免上次残留导致下次冷启动白起一个引擎。
            String mark = "/data/data/" + pkg + "/cache/beeshot_boot";
            // **关键：命令里不能有 sleep**。
            // 之前写了 sleep 0.8 + sleep 2，等于把 su 占住 2.8 秒 ——
            // 而截图自己的 su（收面板 + screencap）要排在后面，
            // 实测 clicked→抓帧 从 200ms 涨到 1.2 秒，连点时还会更糟。
            // 所以这里只做两件瞬时的事：写标记、发拉起广播。
            String cmd = "mkdir -p /data/data/" + pkg + "/cache; "
                    + "date +%s > " + mark + "; "
                    + "chmod 644 " + mark + "; "
                    + "am broadcast -n " + pkg + "/.BeeCountWidgetProvider"
                    + " -a android.appwidget.action.APPWIDGET_UPDATE >/dev/null 2>&1";
            RootShell.Result r = RootShell.exec(cmd, 15000L);
            if (r.exit == 3) {
                Logx.i("[launch] 进程本来就在，跳过");
                return false;
            }
            Logx.i("[launch] 广播已发出 exit=" + r.exit + " out=" + trim(r.out));

            // 不再在这里"延迟派发"了：以前截图路径还没确定，只能先拉起宿主、
            // 让它自己 newestShot 取图。现在截图成功后一定会走"指定路径派发"
            // （见 ShotTileService），这里再发一条无路径派发只会和它打架——
            // 宿主收到无路径派发只能空等（"还没有明确路径"），还多占一次广播。

            return r.exit == 0;
        } catch (Throwable t) {
            Logx.w("[launch] 广播失败：" + t.getMessage());
            return false;
        }
    }

    /**
     * 告诉刚被拉起来的宿主进程："起无界面引擎，把最新那张截图派发进去"。
     *
     * <p>用普通 sendBroadcast 就够：这时宿主进程已经在跑（不是 stopped 状态），
     * 而我们注册的是**动态接收器**，不受后台隐式广播的限制。
     */
    static void requestHeadlessDispatch(android.content.Context ctx) {
        requestHeadlessDispatch(ctx, null);
    }

    /**
     * 告诉刚被拉起来的宿主进程："起无界面引擎，把这张截图派发进去"。
     *
     * <p>用普通 sendBroadcast 就够：这时宿主进程已经在跑（不是 stopped 状态），
     * 而我们注册的是**动态接收器**，不受后台隐式广播的限制。
     *
     * @param path 本次截图的精确路径；null 表示让宿主自己取最新（冷启动兜底用）
     */
    static void requestHeadlessDispatch(android.content.Context ctx, String path) {
        String pkg = HostInfo.pkg(ctx);
        if (pkg == null || pkg.length() == 0) {
            return;
        }
        try {
            // 先起解冻续命线程（异步，不阻塞）：它内部第一下会立即戳，
            // 尽早把可能已被冻结的进程解冻，让紧接着的广播更容易送达。
            // 自适应校准（异步、只跑一次）：按本机实测的冻结周期算出该 sleep 多久，
            // 不同 ROM/机型的冻结周期可能不是 5 秒，硬编码会失效。
            //
            // 2026-11-20 起默认关闭：AI 网络等待已搬到模块进程，宿主不再有长连接；
            // 「让广播更容易送达」这件事由下面的 20 秒补发 + 每 4 秒 pullUp 兜住，
            // 而这条循环每次都要挂一个 26 秒的 root shell。见 Const.ENABLE_THAW_LOOP。
            ensureThaw(ctx, pkg);

            // 用 root 的 am broadcast 发派发请求：普通 sendBroadcast 在被冻结的
            //    宿主上投不到（动态接收器虽在，但进程冻结着收不到），root 的 shell
            //    广播带 FLAG_RECEIVER_INCLUDE_BACKGROUND，能直达后台/冻结进程。
            StringBuilder extras = new StringBuilder();
            extras.append("--ez ").append(Const.EXTRA_AUTO_DELETE).append(' ')
                    .append(Prefs.autoDelete(ctx) ? "true" : "false");
            if (path != null && path.length() > 0) {
                extras.append(" --es ").append(Const.EXTRA_PATH).append(' ').append(path);
            }
            RootShell.broadcast(Const.ACTION_HEADLESS, pkg, extras.toString(),
                    Intent.FLAG_RECEIVER_FOREGROUND | Const.FLAG_RECEIVER_INCLUDE_BACKGROUND);
            Logx.i("[launch] 已请求无界面派发" + (path != null ? "（指定路径）" : ""));

            // 补发窗口 **20 秒**（原来 3 次 ×700ms ≈ 2.1 秒）。
            //
            // 为什么必须这么长（2026-11-20 实测）：宿主被系统回收后再点磁贴，
            // 从广播到「进程真的起来、接收器注册好」要 **约 7 秒**。原来那 2 秒
            // 窗口全部落在进程还没起来的空档里，广播直接丢掉 —— 连点 10 次丢了 2 张，
            // 表现就是「点了没记账」，而且要等 75 秒清理器超时才删图。
            //
            // 同一张图重发是安全的：宿主按路径去重（DISPATCHED_HISTORY / 队列 contains），
            // 重复不会重复记账。收到宿主回执说这张 processed 就提前收工。
            final String fpkg = pkg;
            final String fextras = extras.toString();
            final String fpath = path;
            final android.content.Context appCtx =
                    ctx.getApplicationContext() == null ? ctx : ctx.getApplicationContext();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final long window = DISPATCH_RETRY_MS;
                    final long deadline = System.currentTimeMillis() + window;
                    long lastPullUpAt = System.currentTimeMillis();
                    int n = 0;
                    while (System.currentTimeMillis() < deadline) {
                        try {
                            Thread.sleep(n < 6 ? 700L : 1_300L);
                        } catch (InterruptedException e) {
                            return;
                        }
                        if (ShotBridge.isProcessed(fpath)) {
                            Logx.i("[launch] 宿主已确认处理这张，停止补发（共 " + n + " 次）");
                            return;
                        }
                        // 每 4 秒重新拉一次：进程若已被回收，只有"拉起"才能让它重新存在，
                        // 光发广播唤不醒一个不存在的进程。
                        if (System.currentTimeMillis() - lastPullUpAt >= 4_000L) {
                            lastPullUpAt = System.currentTimeMillis();
                            try {
                                pullUp(appCtx);
                            } catch (Throwable ignored) {
                            }
                        }
                        n++;
                        // 每 5 次走一次 root 广播（能穿冻结/后台限制，但每次都要起一个 su 进程，
                        // 连点时开销不能忽视）；其余用普通广播，进程活着时一样能到。
                        if (n % 5 == 1) {
                            RootShell.broadcast(Const.ACTION_HEADLESS, fpkg, fextras,
                                    Intent.FLAG_RECEIVER_FOREGROUND
                                            | Const.FLAG_RECEIVER_INCLUDE_BACKGROUND);
                        } else if (appCtx != null) {
                            try {
                                Intent it = new Intent(Const.ACTION_HEADLESS);
                                it.setPackage(fpkg);
                                it.putExtra(Const.EXTRA_AUTO_DELETE, Prefs.autoDelete(appCtx));
                                if (fpath != null && fpath.length() > 0) {
                                    it.putExtra(Const.EXTRA_PATH, fpath);
                                }
                                it.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                                appCtx.sendBroadcast(it);
                            } catch (Throwable ignored) {
                            }
                        }
                        Logx.i("[launch] 补发派发请求 #" + n);
                    }
                    Logx.w("[launch] 派发补发已满 " + (window / 1000) + " 秒，停止"
                            + (fpath == null ? "" : "：" + fpath.substring(fpath.lastIndexOf('/') + 1)));
                }
            }, "bee-dispatch-retry").start();
        } catch (Throwable t) {
            Logx.w("[launch] 请求派发失败：" + t.getMessage());
        }
    }

    // ---------------- MIUI 冻结解冻（GreezeManager） ----------------
    //
    // 背景（实测）：
    //   · 蜜蜂记账被无界面拉起后，MIUI 的 GreezeManager 周期性 freezeUid 冻结它
    //     （logcat `FZ uid ... reason=from system`，解冻后约 5 秒必冻结一次）。
    //   · 冻结会掐断它到智谱 AI 的 TCP 连接 → 解冻后死连接报 DioException
    //     → 弹「识别失败」（单点约 40~50%，连点更糟）；严重时卡在「正在识别截图」。
    //
    // 无效方案（都已实测排除）：
    //   · cpuset 保前台：阻止不了 freezeUid
    //   · oom_score_adj=0：同样阻止不了 freezeUid
    //   · `am unfreeze --sticky`：命令报成功，但 cgroup.freeze 仍是 1——只对
    //     `am freeze` 冻的进程有效，对 GreezeManager 的私有冻结无效
    //   · 前台化 Activity：有效但会闪屏
    //
    // 唯一有效：binder 调用（content query）戳 provider —— 送达即触发 THAW 解冻。
    //
    // ★ 实测结论（2026-10-25，logcat 铁证）：GreezeManager 的冻结是**固定定时器**——
    //   每次真正 THAW 之后，硬等 **约 5.2 秒**就再冻结一次，与进程有没有 binder 流量无关。
    //
    //   重要澄清（纠正早期错误结论）：content query 戳 provider **只能"冻结后立刻解冻"**，
    //   把"冻结态持续时长"从几秒压到约 0.33 秒（THAW→FZ 稳定 0.33s）。但它**阻止不了
    //   冻结本身**——冻结那一瞬间，蜜蜂记账到智谱 AI 的 TCP 连接已经断了，解冻再快也
    //   救不回，AI 请求照样报 Network error。
    //
    //   ★ 识别失败（Network error）的根因是冻结断连，与"窗口多长"无关：
    //   实测 Network error 的相邻间隔 86% 集中在 5.2 秒，和冻结周期精确吻合。
    //   真正的解法是「省电策略设无限制」（系统不冻结）；本段"对齐解冻"只能
    //   *略微*缩短冻结态、让广播/派发更容易送达，**无法替代无限制**。
    //
    //   所以本段保留的意义有限：它让"冻结后尽快解冻"，减少广播被冻结窗口拒掉的概率
    //   （连点时约 1/3 派发会被拒），但对"识别失败"本身没有实质改善。

    /** 循环里两次戳之间的 sleep：4.15s + 戳本身约 1.0s ≈ 5.15s 周期。 */
    private static final long ALIGN_SLEEP_MS = 4_150L;

    /** 实际使用的 sleep（自适应校准后覆盖）。 */
    private static volatile long alignSleepMs = ALIGN_SLEEP_MS;

    /** 是否已校准（只跑一次）。 */
    private static volatile boolean alignCalibrated = false;

    /** 校准结果缓存键（避免每次启动都重新测）。 */
    private static final String PREF_ALIGN_INTERVAL = "align_interval_ms";

    /** 本机实测的「解冻→再冻结」间隔；默认 5010 是这台 HyperOS 机器的值。 */
    private static volatile long freezeIntervalMs = 5_010L;

    /**
     * 自适应校准：实测**本机**的「解冻 → 再冻结」间隔，反推对齐 sleep。
     *
     * <p>为什么必须自适应：5.01 秒只是这台机器的值，别的 ROM 可能是 10 秒、30 秒。
     * 硬编码 4.15 秒换台机器就完全对不齐（要么每次戳空、要么窗口极长）。
     *
     * <p>做法：
     * <ol>
     *   <li>读 {@code logcat -v epoch -s GreezeManager}，取本 uid 的 THAW/FZ 时间戳，
     *       算相邻 THAW→FZ 的**中位数**（避开偶发异常样本）；</li>
     *   <li>实测一次 content query 的耗时（= 周期里的固定开销）；</li>
     *   <li>sleep = 间隔 − 开销 + 目标窗口(150ms)，并夹在合理区间。</li>
     * </ol>
     * 结果写进 SharedPreferences，下次启动直接用。
     */
    static void calibrateAsync(final Context ctx, final String pkg) {
        if (alignCalibrated) {
            return;
        }
        alignCalibrated = true;
        final Context app = ctx.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    long cached = app.getSharedPreferences("beeshot", 0)
                            .getLong(PREF_ALIGN_INTERVAL, 0L);
                    if (cached > 1_000L) {
                        freezeIntervalMs = cached;
                        Logx.i("[launch] 用缓存的冻结间隔=" + cached + "ms");
                        return;
                    }
                    int uid;
                    try {
                        uid = app.getPackageManager().getApplicationInfo(pkg, 0).uid;
                    } catch (Throwable t) {
                        Logx.w("[launch] 拿不到宿主 uid，跳过校准");
                        alignCalibrated = false;   // 允许下次重试
                        return;
                    }
                    RootShell.Result r = RootShell.exec(
                            "logcat -d -v epoch -t 3000 2>/dev/null | grep -E 'FZ uid = " + uid
                                    + " |THAW uid = " + uid + " ' | tail -60", 15_000L);
                    long interval = medianInterval(r.out == null ? "" : r.out);
                    if (interval <= 0) {
                        Logx.w("[launch] 校准失败：logcat 没有本机冻结记录，沿用默认 "
                                + alignSleepMs + "ms");
                        alignCalibrated = false;   // 允许下次点击再试
                        return;
                    }
                    // 只记"本机冻结间隔"；每次戳的耗时抖动由循环内**闭环自校正**吸收
                    // （每轮用 目标周期 − 本次耗时 当 sleep，周期恒定）。
                    freezeIntervalMs = interval;
                    app.getSharedPreferences("beeshot", 0).edit()
                            .putLong(PREF_ALIGN_INTERVAL, interval).apply();
                    Logx.i("[launch] 自适应校准完成：本机冻结间隔=" + interval
                            + "ms → 目标周期=" + (interval + 150L) + "ms");
                } catch (Throwable t) {
                    Logx.w("[launch] 校准异常：" + t.getMessage());
                    alignCalibrated = false;   // 允许下次重试
                }
            }
        }, "bee-align-calib").start();
    }

    /**
     * 从 logcat 文本里算 THAW→FZ 间隔的中位数（毫秒）。
     *
     * @return 中位数；没有有效样本返回 -1
     */
    private static long medianInterval(String out) {
        java.util.ArrayList<Long> v = new java.util.ArrayList<Long>();
        long lastThaw = -1;
        for (String line : out.split("\n")) {
            String s = line.trim();
            int sp = s.indexOf(' ');
            if (sp <= 0) {
                continue;
            }
            long ts;
            try {
                ts = (long) (Double.parseDouble(s.substring(0, sp)) * 1000);
            } catch (Throwable t) {
                continue;
            }
            if (s.contains("THAW uid = ")) {
                lastThaw = ts;
            } else if (s.contains("FZ uid = ") && lastThaw > 0) {
                long d = ts - lastThaw;
                if (d > 500 && d < 120_000) {
                    v.add(d);
                }
                lastThaw = -1;
            }
        }
        if (v.isEmpty()) {
            return -1;
        }
        java.util.Collections.sort(v);
        return v.get(v.size() / 2);
    }

    /**
     * 一次循环戳几下。
     *
     * <p><b>不能砍（2026-11-20 踩过）</b>：从 10 降到 6（≈16 秒）后，连点 10 张的排队要跑
     * 约 50 秒，循环一结束宿主就重新冻死、没人再戳它 —— 那一轮只完成了 3 张，其余被清理器
     * 按超时删掉。所以这个值要按"最长排队时长"给足余量，10 下（≈26 秒）× 每次派发重启一条，
     * 才能把整段队列罩住。
     */
    private static final int ALIGN_TICKS = 10;

    /** 只有一条对齐循环（连点时复用，避免两条相位互踩）。 */
    private static volatile boolean thawLoopRunning = false;

    /**
     * 起一条解冻循环：一次 su 里循环戳 provider，让蜜蜂记账"冻结后尽快被解冻"。
     *
     * <p><b>注意</b>：这只能缩短"冻结态持续时长"（约 0.33 秒），**阻止不了冻结本身**，
     * 因此对「识别失败」（冻结瞬间断连导致的 Network error）没有实质改善——那是
     * 「省电策略设无限制」才能解决的。本循环的意义仅在于：让广播/派发更容易送达，
     * 减少连点时广播被冻结窗口拒掉的概率。
     *
     * <p>必须跑在**模块自己的进程**里（本类就是）：宿主被冻结时它内部所有线程一起被冻，
     * 在宿主里起的解冻线程自己也发不出 binder（实测只戳 1 次就停）。
     */
    /**
     * 确保解冻保活循环在跑（幂等）。
     *
     * <p><b>派发期间必须有人一直在戳宿主</b>：MIUI 冻结后台进程后只有外部 binder 触碰
     * 才会解冻，光发广播只能让它短暂醒一下，Flutter 引擎会卡在"排队等派发"上跑不完。
     * 实测关掉这条循环时，10 张连点只有 0 张能记账。
     */
    static void ensureThaw(Context ctx, String pkg) {
        if (!Const.ENABLE_THAW_LOOP) {
            return;
        }
        try {
            calibrateAsync(ctx, pkg);
            startThawLoop(ctx, pkg);
        } catch (Throwable t) {
            Logx.w("[launch] 起解冻循环失败：" + t.getMessage());
        }
    }

    private static void startThawLoop(final Context ctx, final String pkg) {
        if (thawLoopRunning) {
            return;
        }
        thawLoopRunning = true;
        String uri = "content://" + pkg + ".androidx-startup/";
        // timeout 兜底：即使某次 content 卡住，也不会留下永久循环。
        // 目标周期 = 本机冻结间隔 + 150ms（略大于冻结间隔，让每轮的戳落在"上一次冻结之后"，
        // 尽快解冻；周期越接近冻结间隔，戳越可能命中冻结态）。
        // 循环内每轮实测本次戳耗时 d，用 T−d 当 sleep，让周期恒定、抖动被吸收。
        final long target = freezeIntervalMs + 150L;
        String cmd = "timeout " + (ALIGN_TICKS * 5) + " sh -c 'for i in $(seq 1 "
                + ALIGN_TICKS + "); do "
                + "a=$(date +%s%3N); "
                + "content query --uri " + uri + " >/dev/null 2>&1; "
                + "b=$(date +%s%3N); d=$((b-a)); sl=$(( " + target + " - d )); "
                + "[ $sl -lt 500 ] && sl=500; "
                + "sleep $((sl/1000)).$(printf %03d $((sl%1000))); "
                + "done'";
        final Process p = RootShell.startAsync(cmd);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (p != null) {
                        p.waitFor();
                    }
                } catch (Throwable ignored) {
                }
                thawLoopRunning = false;
                Logx.i("[launch] 对齐解冻循环结束");
            }
        }, "bee-thaw-watch").start();
    }

    private static String trim(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\n', ' ').trim();
        return t.length() > 80 ? t.substring(0, 80) : t;
    }
}
