package io.github.USAMA6878786.beecounthelper;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Material 3 的视觉规范，用**纯原生 View** 手工实现。
 *
 * <h3>为什么不用 Material Components</h3>
 * 这个模块没有任何 AndroidX 依赖——`build.sh` 用 javac 对着 {@code android.jar} 直接编译，
 * 拉不进 {@code com.google.android.material}。所以这里做的不是"用 M3 组件"，
 * 而是**照 M3 的规范画**：色彩令牌、形状（圆角）、字号层级、触控高度、留白节奏。
 *
 * <h3>色彩</h3>
 * 以"蜜蜂/蜂蜜"为种子色的暖色调（M3 的 tonal palette 思路）：
 * 主色 {@link #PRIMARY}、容器 {@link #PRIMARY_CONTAINER}、表面 {@link #SURFACE_CONTAINER}。
 * 文本一律用 {@link #ON_SURFACE} / {@link #ON_SURFACE_VARIANT}，保证对比度。
 */
final class Ui {

    // ---------------- 色彩令牌（M3 语义命名） ----------------

    /** 页面底色：暖白纸感，比纯白柔和（避免大面积 #FFF 的刺眼）。 */
    static final int BACKGROUND = Color.parseColor("#FBFAF7");
    /** 卡片底色：纯白，让卡片自己"浮"在暖白页面上。 */
    static final int SURFACE_CONTAINER = Color.parseColor("#FFFFFF");
    /** 发丝描边：白卡在浅底上单靠投影会糊，加 1px 描边更利落。 */
    static final int OUTLINE = Color.parseColor("#EDE6DC");
    /** 主色（primary）：蜂蜜琥珀，压深一点以提高白底上的对比度。 */
    static final int PRIMARY = Color.parseColor("#8A5100");
    static final int ON_PRIMARY = Color.WHITE;
    /** 主色容器（primaryContainer），用于 tonal 按钮与高亮块。 */
    static final int PRIMARY_CONTAINER = Color.parseColor("#FBEBD6");
    static final int ON_PRIMARY_CONTAINER = Color.parseColor("#4A2A00");
    /** 主要文字。 */
    static final int ON_SURFACE = Color.parseColor("#1B1917");
    /** 次要文字。 */
    static final int ON_SURFACE_VARIANT = Color.parseColor("#6B6055");
    /** 未选中态的描边/轨道。 */
    static final int OUTLINE_VARIANT = Color.parseColor("#D8CEC1");
    // ---------------- 尺寸令牌（M3 常用档位） ----------------

    /** 页面左右留白。 */
    static final int PAGE_PAD = 18;
    /** 卡片内留白。 */
    static final int CARD_PAD = 18;
    /** 卡片之间。 */
    static final int CARD_GAP = 14;
    /** 卡片圆角（M3 medium 形状）。 */
    static final int CARD_RADIUS = 20;
    /** 按钮圆角（Apple 风格，比卡片略小才有"可点"的暗示）。 */
    static final int BUTTON_RADIUS = 14;
    /** 按钮最小高度（Apple HIG 44 + 2 呼吸）。 */
    static final int BUTTON_HEIGHT = 46;

    // ---------------- 基础换算 ----------------

    static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    // ---------------- 形状 ----------------

    /** 圆角纯色背景。 */
    static GradientDrawable rounded(int color, int radiusDp, Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    /** 圆角 + 发丝描边（白卡靠它和背景分开）。 */
    static GradientDrawable roundedStroke(int color, int radiusDp, int stroke, Context c) {
        GradientDrawable d = rounded(color, radiusDp, c);
        d.setStroke(Math.max(1, dp(c, 0.7f)), stroke);
        return d;
    }

    // ---------------- 字体 ----------------

    /** 中等字重（系统自带，不引入字体文件）。 */
    static android.graphics.Typeface medium() {
        return android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL);
    }

    // ---------------- 容器 ----------------

    /**
     * 一张卡片：白底 + 发丝描边 + 1dp 投影，返回**内容容器**（往里 addView）。
     *
     * <p>为什么白卡而不是填充色块：暖白页面上再铺浅驼色卡片，明度差太小、
     * 整页会糊成一片；换成白卡 + 1px 描边 + 极轻投影，层级一眼就出来了，
     * 也更接近现在主流 App 的观感（M3 filled 卡在深色底上才好看）。
     *
     * @param title 卡片标题，null 表示不要标题
     */
    static LinearLayout card(Context c, LinearLayout parent, String title) {
        LinearLayout outer = new LinearLayout(c);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackground(roundedStroke(SURFACE_CONTAINER, CARD_RADIUS, OUTLINE, c));
        int p = dp(c, CARD_PAD);
        outer.setPadding(p, p, p, p);
        // 极轻投影（1.5dp）：给白卡一点"离开纸面"的感觉，不做重阴影。
        outer.setElevation(dp(c, 1.5f));
        outer.setClipToPadding(false);

        if (title != null && title.length() > 0) {
            TextView t = new TextView(c);
            t.setText(title);
            t.setTextSize(12f);
            t.setTypeface(medium());
            t.setTextColor(ON_SURFACE_VARIANT);
            t.setLetterSpacing(0.08f);
            t.setAllCaps(false);
            outer.addView(t, lp(-2, -2, 0, 0, 0, 12));
        }

        parent.addView(outer, lp(-1, -2, 0, CARD_GAP, 0, 0));
        return outer;
    }

    /** 一行：左右两栏（标题在左、控件在右），M3 的 list item 样式。 */
    static LinearLayout row(Context c) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(c, 52));
        return r;
    }

    // ---------------- 文本 ----------------

    /** 页面大标题（26sp，负字距让大字更紧凑有质感）。 */
    static TextView title(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(26f);
        t.setTypeface(medium());
        t.setTextColor(ON_SURFACE);
        t.setLetterSpacing(-0.02f);
        return t;
    }

    /** 次要说明（一行以内为宜——这是本次"少字"的落点）。 */
    static TextView caption(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(12.5f);
        t.setTextColor(ON_SURFACE_VARIANT);
        t.setLineSpacing(dp(c, 3), 1f);
        return t;
    }

    /** 行标题（15sp + 中等字重，比正文略"半拍"重，层级靠字重区分）。 */
    static TextView rowTitle(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(15f);
        t.setTypeface(medium());
        t.setTextColor(ON_SURFACE);
        return t;
    }

    // ---------------- 控件 ----------------

    /** M3 filled 按钮：主色底、白字、全圆角、无阴影。 */
    static TextView filledButton(Context c, String text, View.OnClickListener l) {
        TextView b = pillText(c, text, ON_PRIMARY, PRIMARY);
        b.setOnClickListener(l);
        return b;
    }

    /** M3 tonal 按钮：主色容器底、深色字。次级操作用它。 */
    static TextView tonalButton(Context c, String text, View.OnClickListener l) {
        TextView b = pillText(c, text, ON_PRIMARY_CONTAINER, PRIMARY_CONTAINER);
        b.setOnClickListener(l);
        return b;
    }

    private static TextView pillText(Context c, String text, int textColor, int bg) {
        TextView b = new TextView(c);
        b.setText(text);
        b.setTextSize(14.5f);
        b.setTypeface(medium());
        b.setTextColor(textColor);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(c, BUTTON_HEIGHT));
        b.setPadding(dp(c, 20), dp(c, 12), dp(c, 20), dp(c, 12));
        b.setClickable(true);
        b.setFocusable(true);
        if (bg == Color.TRANSPARENT) {
            GradientDrawable g = rounded(Color.TRANSPARENT, BUTTON_RADIUS, c);
            b.setBackground(new RippleDrawable(
                    ColorStateList.valueOf(0x1F8A5100), g, null));
        } else {
            GradientDrawable g = rounded(bg, BUTTON_RADIUS, c);
            b.setBackground(new RippleDrawable(
                    ColorStateList.valueOf(0x33FFFFFF), g, null));
        }
        // 去掉原生 TextView 的默认行距，让高度完全由 minHeight 决定
        b.setIncludeFontPadding(false);
        return b;
    }

    /**
     * 一行开关：左侧标题（+可选一行副标题），右侧 Switch。
     *
     * <p>M3 里这类行**不加卡片内边距以外的东西**——靠留白分组，不画分隔线。
     */
    static void switchRow(Context c, LinearLayout parent, String title, String subtitle,
                          boolean checked, OnToggle l) {
        LinearLayout row = row(c);
        // 上下各留 12dp：多行开关之间靠留白分组，不画分隔线（M3 的做法）。
        int v = dp(c, 12);
        row.setPadding(0, v, 0, v);

        LinearLayout left = new LinearLayout(c);
        left.setOrientation(LinearLayout.VERTICAL);
        left.addView(rowTitle(c, title));
        if (subtitle != null && subtitle.length() > 0) {
            TextView sub = caption(c, subtitle);
            left.addView(sub, lp(-1, -2, 0, dp(c, 3), 0, 0));
        }
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        llp.rightMargin = dp(c, 16);
        row.addView(left, llp);

        Toggle sw = new Toggle(c);
        sw.checked = checked;
        sw.listener = l;
        row.addView(sw, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        parent.addView(row);
    }

    /**
     * M3 列表行：左对齐文字 + 右侧箭头，整行可点。
     *
     * <p>「帮助」那种成组入口用它——比一串居中文字按钮更像 App，也不会显得零散。
     */
    static void listItem(Context c, LinearLayout parent, String title, View.OnClickListener l) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(c, 52));
        int v = dp(c, 8);
        row.setPadding(0, v, 0, v);
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(0x148A5100), null, null));

        TextView t = new TextView(c);
        t.setText(title);
        t.setTextSize(15f);
        t.setTypeface(medium());
        t.setTextColor(ON_SURFACE);
        row.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView chev = new TextView(c);
        chev.setText("›");
        chev.setTextSize(17f);
        chev.setTextColor(ON_SURFACE_VARIANT);
        chev.setIncludeFontPadding(false);
        row.addView(chev);

        row.setOnClickListener(l);
        parent.addView(row);
    }

    /** SeekBar 按 M3 语义色着色。 */
    static void tintSeekBar(Context c, android.widget.SeekBar sb) {
        sb.setProgressTintList(ColorStateList.valueOf(PRIMARY));
        sb.setThumbTintList(ColorStateList.valueOf(PRIMARY));
        sb.setProgressBackgroundTintList(ColorStateList.valueOf(PRIMARY_CONTAINER));
        // 让滑轨与上方的文字**左对齐**：系统 SeekBar 默认左右留了半个滑块宽的内边距，
        // 看起来会比文字缩进一截。去掉这个内边距、并把滑块偏移归零，轨道就顶到卡片内容边缘。
        int top = sb.getPaddingTop();
        int bottom = sb.getPaddingBottom();
        sb.setPadding(0, top, 0, bottom);
        // 关键：偏移要留**半个滑块宽**，让滑块中心正好落在轨道两端。
        // 之前把偏移写成 0，滑块就整体偏左半个宽度，滑到 100% 时最右边会露出一小段空轨道。
        android.graphics.drawable.Drawable th = sb.getThumb();
        sb.setThumbOffset(th == null ? 0 : th.getIntrinsicWidth() / 2);
    }

    /** 通用 LayoutParams 构造（width/height 用 -1 = MATCH、-2 = WRAP）。 */
    static LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }


    /** 开关回调（不用 CompoundButton，因为下面是自己画的）。 */
    interface OnToggle {
        void onToggle(boolean on);
    }

    /**
     * 自绘的 M3 开关。
     *
     * <h3>为什么不用系统 Switch</h3>
     * 系统 {@code Switch} 在 Android 12+ 的轨道颜色由主题的 {@code switchTrackTint} 决定，
     * 代码里 {@code setTrackTintList} 实测**不生效**（真机：只有圆点变色，轨道始终浅色）。
     * 那样"开/关"只靠圆点位置区分，不是 M3 的样子。
     *
     * <p>自己画就完全可控：开 = 主色轨道 + 白点靠右；关 = 描边色轨道 + 深色点靠左。
     */
    static final class Toggle extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int trackW;
        private final int trackH;
        private final int knobD;
        boolean checked;
        OnToggle listener;
        /** 0 = 关，1 = 开。用动画驱动，别让圆点"瞬移"。 */
        private float anim = 0f;
        private boolean animInit = false;
        private android.animation.ValueAnimator va;
        private final int colorOff = OUTLINE_VARIANT;
        private final int colorOn = PRIMARY;
        private final int knobOff = Color.parseColor("#FFFFFF");
        private final int knobOn = ON_PRIMARY;

        Toggle(Context c) {
            super(c);
            trackW = dp(c, 52);
            trackH = dp(c, 32);
            knobD = dp(c, 24);
            setClickable(true);
            setFocusable(true);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            setMeasuredDimension(trackW, trackH);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (!animInit) {
                animInit = true;
                anim = checked ? 1f : 0f;
            }
            float r = trackH / 2f;
            // 轨道颜色在关/开之间插值，和圆点一起动，观感是"整体切换"而不是两块分别跳。
            int track = blend(colorOff, colorOn, anim);
            paint.setColor(track);
            canvas.drawRoundRect(0, 0, trackW, trackH, r, r, paint);

            float cx = r + (trackW - 2 * r) * anim;
            paint.setColor(blend(knobOff, knobOn, anim));
            // 圆点带一点柔和阴影，看起来有厚度（M3 的 state layer 观感）。
            paint.setShadowLayer(dp(getContext(), 1.2f), 0, dp(getContext(), 0.8f), 0x33000000);
            setLayerType(LAYER_TYPE_SOFTWARE, paint);
            canvas.drawCircle(cx, trackH / 2f, knobD / 2f, paint);
            paint.clearShadowLayer();
        }

        /** 线性插值两个 ARGB 颜色。 */
        private static int blend(int from, int to, float t) {
            float u = Math.max(0f, Math.min(1f, t));
            int a = (int) (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * u);
            int r = (int) (Color.red(from) + (Color.red(to) - Color.red(from)) * u);
            int g = (int) (Color.green(from) + (Color.green(to) - Color.green(from)) * u);
            int b = (int) (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * u);
            return Color.argb(a, r, g, b);
        }

        /** 从当前值动画到目标状态（150ms，减速曲线）。 */
        private void animateTo(float target) {
            if (va != null) {
                va.cancel();
            }
            va = android.animation.ValueAnimator.ofFloat(anim, target);
            va.setDuration(150L);
            va.setInterpolator(new android.view.animation.DecelerateInterpolator());
            va.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(android.animation.ValueAnimator a) {
                    anim = (Float) a.getAnimatedValue();
                    invalidate();
                }
            });
            va.start();
        }

        @Override
        public boolean performClick() {
            checked = !checked;
            animInit = true;
            animateTo(checked ? 1f : 0f);
            if (listener != null) {
                listener.onToggle(checked);
            }
            return true;
        }
    }

    private Ui() {
    }
}
