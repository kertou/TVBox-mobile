package com.github.tvbox.osc.ui.adapter;

import androidx.annotation.Nullable;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.lihang.ShadowLayout;

import java.util.ArrayList;

/**
 * 缓存选集弹窗的集数网格
 */
public class CacheSelectAdapter extends BaseQuickAdapter<CacheSelectAdapter.Item, BaseViewHolder> {

    public static class Item {
        public String name;
        public int index;
        public boolean checked;
        public boolean cached;
    }

    public CacheSelectAdapter() {
        super(R.layout.item_cache_select_episode, new ArrayList<>());
    }

    @Override
    protected void convert(BaseViewHolder helper, Item item) {
        ShadowLayout sl = helper.getView(R.id.sl);
        android.widget.TextView tv = helper.getView(R.id.tvEp);
        sl.setSelected(item.checked);
        String text = item.name;
        if (item.cached) {
            text = "✓ " + text;
            tv.setAlpha(0.55f);
        } else {
            tv.setAlpha(1f);
        }
        tv.setText(text);
    }

    public void setCheckedSilently(int position, boolean checked) {
        if (position >= 0 && position < getData().size()) {
            getData().get(position).checked = checked;
        }
    }

    @Override
    public void setNewData(@Nullable java.util.List<Item> data) {
        super.setNewData(data);
    }
}
