package io.github.USAMA6878786.beetap;

import android.content.Context;
import android.content.Intent;

import java.util.ArrayDeque;

/**
 * 模块侧的**串行派发队列**。
 *
 * <h3>为什么需要它</h3>
 * 原来连点时模块会瞬间把 10 条派发全部甩给宿主，由宿主自己的 {@code QUEUE} 排队。
 * 但宿主那个队列有两个致命弱点：
 * <ol>
 *   <li>引擎一旦被销毁（{@code destroyEngine()}）或进程被系统回收，**队列整批清空**，
 *       排着的图直接消失，模块这边还以为"已经派出去了"；</li>
 *   <li>模块的补发窗口（20 秒）按"单张"算，队列里排到第 8 张时窗口早过了，
 *       这中间再出事没人兜。</li>
 * </ol>
 * 实测结果就是连点 10 张偶发丢 1 张（清理器 75 秒后按超时删掉，用户看到"点了没记账"）。
 *
 * <h3>做法</h3>
 * 队列放在模块进程（前台服务，不会被当后台应用冻结），**一次只把一张图交给宿主**：
 * 发派发 → 等宿主回执（{@link ShotBridge#isProcessed}）→ 再发下一张。
 * 宿主队列里永远只有 0~1 张，引擎怎么回收都不会丢东西。
 *
 * <p>串行不会变慢：宿主的无界面引擎本来就是串行识别的，这里只是把"排队"这件事
 * 从会被冻结的进程搬到不会被冻结的进程。
 */
final class DispatchQueue {

    /**
     * 队列上限。
     *
     * <p><b>12 太小（2026-11-20 实测）</b>：单张识别约 6 秒，20 次连点（1.8 秒一次）的积压
     * 到 14 张 —— 触发"队列满丢最早的"，8 张被静默丢掉，那 8 张图 75 秒后被清理器删掉，
     * 用户看到的就是"点了没记账"。宁可延后，也不能丢用户明确点过的那几张。
     * 每条只是一个路径字符串，60 条的额外内存可以忽略（约 6KB）。
     */
    private static final int MAX = 60;

    /**
     * 单张的等待上限。
     *
     * <p>识别本身 3~10 秒；给到 25 秒是为了覆盖「宿主冷启动 7 秒 + 冻结几秒」。
     * 超时就往下走 —— 一张卡死不能把后面排队的全废掉（那张由清理器兜底删掉）。
     */
    private static final long ITEM_TIMEOUT_MS = 25_000L;

    /** 补发节奏：前几次密集（抢冷启动窗口），之后放缓。 */
    private static final long FAST_MS = 700L;
    private static final long SLOW_MS = 1_200L;

    /** 隔多久检查一次"宿主进程还在不在"，不在就重新拉起。 */
    private static final long PULL_UP_EVERY_MS = 4_000L;

    private static final ArrayDeque<String> QUEUE = new ArrayDeque<String>();
    private static final Object LOCK = new Object();
    private static volatile Thread worker;

    private DispatchQueue() {
    }

    /** 提交一张要派发的截图（去重）。 */
    static void submit(final Context ctx, final String path) {
        if (ctx == null || path == null || path.length() == 0) {
            return;
        }
        final Context app = ctx.getApplicationContext() == null
                ? ctx : ctx.getApplicationContext();
        synchronized (LOCK) {
            if (ShotBridge.isProcessed(path) || QUEUE.contains(path)) {
                Logx.i("[dispatch-q] 已在队列或已处理，跳过："
                        + RootShell.baseName(path));
                return;
            }
            while (QUEUE.size() >= MAX) {
                String dropped = QUEUE.pollFirst();
                Logx.w("[dispatch-q] 队列满，丢掉最早的一张：" + RootShell.baseName(dropped));
            }
            QUEUE.addLast(path);
            Logx.i("[dispatch-q] 入队（队列 " + QUEUE.size() + "）："
                    + RootShell.baseName(path));
            if (worker == null) {
                worker = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        drain(app);
                    }
                }, "bee-dispatch-q");
                worker.setDaemon(true);
                worker.start();
            }
        }
    }

    /** 串行消化队列。队列空了就退出线程（下次 submit 会重建）。 */
    private static void drain(Context app) {
        while (true) {
            String path;
            synchronized (LOCK) {
                path = QUEUE.pollFirst();
                if (path == null) {
                    worker = null;
                    Logx.i("[dispatch-q] 队列已空，工人线程退出");
                    return;
                }
            }
            try {
                deliver(app, path);
            } catch (Throwable t) {
                Logx.w("[dispatch-q] 派发异常：" + t.getMessage());
            }
        }
    }

    /**
     * 把一张图交给宿主，直到它被确认处理（或超时）。
     *
     * <p>判定"已处理"用的是宿主回执 {@link ShotBridge#isProcessed} ——
     * 宿主对每张图都会回一条 {@code processed=true}（含"识别完了但不是账单"）。
     */
    private static void deliver(Context app, String path) {
        final String pkg = HostInfo.pkg(app);
        if (pkg == null || pkg.length() == 0) {
            Logx.w("[dispatch-q] 不知道宿主包名，放弃这张");
            return;
        }
        final long deadline = System.currentTimeMillis() + ITEM_TIMEOUT_MS;
        long lastPullUp = System.currentTimeMillis();
        int n = 0;
        Logx.i("[dispatch-q] 开始派发：" + RootShell.baseName(path));

        // 派发期间必须有人一直戳宿主，否则它被冻结后引擎跑不完（见 ensureThaw 注释）
        HostLauncher.ensureThaw(app, pkg);

        while (System.currentTimeMillis() < deadline) {
            if (ShotBridge.isProcessed(path)) {
                Logx.i("[dispatch-q] 宿主已处理，用时 " + (n * 1L) + " 次补发："
                        + RootShell.baseName(path));
                return;
            }
            // 文件没了就说明这张已经处理完被删掉了（回执可能在广播里丢了）。
            // 继续补发只会让宿主拿着旧路径空等 3 秒 → 弹一条「识别失败」。
            // 至少发过 2 次再判，避免"刚截图文件还没落盘"被误判。
            if (n >= 2 && !new java.io.File(path).exists()) {
                Logx.i("[dispatch-q] 文件已消失，判定处理完成，停止补发："
                        + RootShell.baseName(path));
                return;
            }
            n++;
            // 每 5 次走一次 root 广播（能穿冻结），其余走普通广播（便宜）
            if (n % 5 == 1) {
                StringBuilder extras = new StringBuilder();
                extras.append("--ez ").append(Const.EXTRA_AUTO_DELETE).append(' ')
                        .append(Prefs.autoDelete(app) ? "true" : "false");
                extras.append(" --es ").append(Const.EXTRA_PATH).append(' ').append(path);
                RootShell.broadcast(Const.ACTION_HEADLESS, pkg, extras.toString(),
                        Intent.FLAG_RECEIVER_FOREGROUND
                                | Const.FLAG_RECEIVER_INCLUDE_BACKGROUND);
            } else {
                try {
                    Intent it = new Intent(Const.ACTION_HEADLESS);
                    it.setPackage(pkg);
                    it.putExtra(Const.EXTRA_AUTO_DELETE, Prefs.autoDelete(app));
                    it.putExtra(Const.EXTRA_PATH, path);
                    it.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                    app.sendBroadcast(it);
                } catch (Throwable ignored) {
                }
            }

            // 隔几次主动问一次宿主"这张处理了吗"。
            // 不能只等它推：宿主只在**记账成功**时才主动推（decisive），
            // "识别完了但不是账单"那种不会推，光等会把队列卡满 25 秒。
            if (n % 3 == 0) {
                ShotBridge.Answer a = ShotBridge.query(app, path, 1_200L);
                if (a.received && (a.processed || a.success)) {
                    Logx.i("[dispatch-q] 查询确认已处理，交给下一张："
                            + RootShell.baseName(path));
                    return;
                }
            }

            // 宿主进程不在了就只能靠拉起（广播唤不醒不存在的进程）
            long now = System.currentTimeMillis();
            if (now - lastPullUp >= PULL_UP_EVERY_MS) {
                lastPullUp = now;
                try {
                    if (!HostInfo.isProcessAlive(app)) {
                        Logx.i("[dispatch-q] 宿主进程不在，重新拉起");
                        HostLauncher.pullUp(app);
                    }
                } catch (Throwable ignored) {
                }
            }

            try {
                Thread.sleep(n <= 5 ? FAST_MS : SLOW_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
        Logx.w("[dispatch-q] 等满 " + (ITEM_TIMEOUT_MS / 1000) + " 秒仍无回执，放弃这张："
                + RootShell.baseName(path) + "（清理器会兜底删掉）");
    }
}
