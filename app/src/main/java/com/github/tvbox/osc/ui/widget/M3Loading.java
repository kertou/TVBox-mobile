package com.github.tvbox.osc.ui.widget;

import android.content.Context;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.util.SparseArray;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.progressindicator.CircularProgressIndicator;

/**
 * 播放器加载指示器。由 LottieAnimationView 换为 M3 CircularProgressIndicator:
 * 同一 android:id 历史上保存过 Lottie 类型的视图状态,覆盖安装后系统恢复 Activity 时
 * 会把旧 parcel 喂给新视图导致 ClassCastException,这里跳过整个状态恢复
 * (加载动画本就无需恢复;保存侧已由布局 saveEnabled=false 关闭)。
 */
public class M3Loading extends CircularProgressIndicator {

    public M3Loading(@NonNull Context context) {
        super(context);
    }

    public M3Loading(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public M3Loading(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void dispatchRestoreInstanceState(SparseArray<Parcelable> container) {
        // 故意不恢复:旧版本残留的 Lottie 状态与当前类不兼容
    }
}
