package com.onetapmiao.app;

import android.content.Context;
import android.widget.HorizontalScrollView;

/**
 * 带滚动回调的横向滚动容器（v1.1.0）。
 *
 * 为什么需要它：View.setOnScrollChangedListener 是隐藏 API，
 * 应用代码直接调用会编译失败。标准做法是继承 HorizontalScrollView
 * 重写 protected onScrollChanged —— 公开方法，任何版本都稳定。
 *
 * 用途：主界面三页横滑时，滚动过程中实时同步底部导航高亮。
 */
public class SnapHScrollView extends HorizontalScrollView {

    /** 滚动回调（scrollX 变化时触发） */
    private Runnable scrollListener;

    public SnapHScrollView(Context context) {
        super(context);
    }

    /** 设置滚动回调；传 null 可清除 */
    public void setOnScroll(Runnable listener) {
        this.scrollListener = listener;
    }

    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        if (scrollListener != null) {
            try {
                scrollListener.run();
            } catch (Throwable ignored) {
            }
        }
    }
}
