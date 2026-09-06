package com.github.tvbox.osc.ui.adapter;

import android.view.View;
import android.widget.TextView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.LiveChannelGroup;

import java.util.ArrayList;


/**
 * @author pj567
 * @date :2021/1/12
 * @description:
 */
public class LiveChannelGroupNewAdapter extends BaseQuickAdapter<LiveChannelGroup, BaseViewHolder> {
    private int selectedGroupIndex = -1;
    private int focusedGroupIndex = -1;

    public LiveChannelGroupNewAdapter() {
        super(R.layout.item_live_channel_group_new, new ArrayList<>());
    }

    @Override
    protected void convert(BaseViewHolder holder, LiveChannelGroup item) {
        View root = holder.getView(R.id.root);
        TextView tvGroupName = holder.getView(R.id.tvChannelGroupName);
        tvGroupName.setText(item.getGroupName());
        int groupIndex = item.getGroupIndex();
        // 背景(bg_m3_nav_row_selector)与文字色(selector_nav_row_text)由 XML selector 随 selected 状态自动切换
        root.setSelected(groupIndex == selectedGroupIndex && groupIndex != focusedGroupIndex);
    }

    public void setSelectedGroupIndex(int selectedGroupIndex) {
        if (selectedGroupIndex == this.selectedGroupIndex) return;
        int preSelectedGroupIndex = this.selectedGroupIndex;
        this.selectedGroupIndex = selectedGroupIndex;
        if (preSelectedGroupIndex != -1)
            notifyItemChanged(preSelectedGroupIndex);
        if (this.selectedGroupIndex != -1)
            notifyItemChanged(this.selectedGroupIndex);
    }

    public int getSelectedGroupIndex() {
        return selectedGroupIndex;
    }

    public void setFocusedGroupIndex(int focusedGroupIndex) {
        this.focusedGroupIndex = focusedGroupIndex;
        if (this.focusedGroupIndex != -1)
            notifyItemChanged(this.focusedGroupIndex);
        else if (this.selectedGroupIndex != -1)
            notifyItemChanged(this.selectedGroupIndex);
    }
}