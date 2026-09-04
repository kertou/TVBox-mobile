package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.lxj.xpopup.core.BottomPopupView;

import org.jetbrains.annotations.NotNull;

public class AboutDialog extends BottomPopupView {

    public AboutDialog(@NonNull @NotNull Context context) {
        super(context);
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_about;
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        try {
            String versionName = getContext().getPackageManager()
                    .getPackageInfo(getContext().getPackageName(), 0).versionName;
            ((TextView) findViewById(R.id.tv_version)).setText("TVBox-Mobile v" + versionName);
        } catch (Exception ignore) {
        }
        // 内容较高：限制滚动区高度随屏幕自适应，避免底部弹窗整体超出屏幕；
        // XPopup 2.10 不避让导航栏 inset，这里手动扣除导航栏高度
        android.content.res.Resources res = getContext().getResources();
        int navResId = res.getIdentifier("navigation_bar_height", "dimen", "android");
        int navH = navResId > 0 ? res.getDimensionPixelSize(navResId) : 0;
        android.widget.ScrollView sv = findViewById(R.id.sv_content);
        View root = findViewById(R.id.ll_root);
        if (sv != null && root != null) {
            int screenH = res.getDisplayMetrics().heightPixels;
            sv.getLayoutParams().height = Math.max(dp2px(30), (int) (screenH * 0.55f) - navH);
            root.setPadding(root.getPaddingLeft(), root.getPaddingTop(),
                    root.getPaddingRight(), root.getPaddingBottom() + navH);
        }
        findViewById(R.id.iv_close).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                dismiss();
            }
        });
    }

    private int dp2px(int dp) {
        return (int) (dp * getContext().getResources().getDisplayMetrics().density + 0.5f);
    }
}