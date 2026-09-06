package com.github.tvbox.osc.ui.widget;

import android.content.Context;
import android.util.AttributeSet;

import com.google.android.material.chip.Chip;

/**
 * M3 Filter Chip（样式见 styles.xml 的 AppChip）。
 * 把 View 的 selected 状态映射进 drawable 状态的 checked，让既有 setSelected(...)
 * 驱动选中态的业务代码（SeriesAdapter/CacheSelectAdapter/GridFilterDialog/
 * PlayingControlDialog/直播行）不改一行就能吃到官方 Chip 的背景/描边/文字色。
 */
public class FilterChip extends Chip {
    private static final int[] STATE_CHECKED = {android.R.attr.state_checked};

    public FilterChip(Context context) {
        super(context);
        init();
    }

    public FilterChip(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public FilterChip(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    /** 尾部图标(如播放控制的切换值图标)默认会吞掉点击,这里委托给 chip 自身的点击;
         关闭 checkable,避免点按触发 CompoundButton 自身的 checked 切换产生幽灵选中(选中态只由业务 setSelected 驱动) */
    private void init() {
        setCheckable(false);
        setOnCloseIconClickListener(v -> performClick());
    }

    @Override
    public int[] onCreateDrawableState(int extraSpace) {
        int[] state = super.onCreateDrawableState(extraSpace + 1);
        if (isSelected()) {
            mergeDrawableStates(state, STATE_CHECKED);
        }
        return state;
    }
}
