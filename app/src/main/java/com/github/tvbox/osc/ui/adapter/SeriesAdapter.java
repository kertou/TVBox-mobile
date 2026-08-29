package com.github.tvbox.osc.ui.adapter;

import android.graphics.Color;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.blankj.utilcode.util.ConvertUtils;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.VodInfo;
import com.lihang.ShadowLayout;

import java.util.ArrayList;
import java.util.HashSet;

/**
 * @author pj567
 * @date :2020/12/22
 * @description:
 */
public class SeriesAdapter extends BaseQuickAdapter<VodInfo.VodSeries, BaseViewHolder> {
    private boolean isGird;
    /** 已离线缓存的集数地址(原始url),用于显示角标 */
    private HashSet<String> cachedUrls = new HashSet<>();

    public SeriesAdapter(boolean isGird) {
        super(R.layout.item_series, new ArrayList<>());
        this.isGird = isGird;
    }

    @Override
    protected void convert(BaseViewHolder helper, VodInfo.VodSeries item) {
        ShadowLayout sl = helper.getView(R.id.sl);
        TextView tvSeries = helper.getView(R.id.tvSeries);
        sl.setSelected(item.selected);
        boolean cached = item.url != null && cachedUrls.contains(item.url);
        tvSeries.setText(cached ? "✓ " + item.name : item.name);

        if (!isGird){// 详情页横向展示时固定宽度
            ViewGroup.LayoutParams layoutParams = sl.getLayoutParams();
            layoutParams.width = ConvertUtils.dp2px(120);
            sl.setLayoutParams(layoutParams);
        }
    }

    public void setCachedUrls(HashSet<String> urls) {
        this.cachedUrls = urls == null ? new HashSet<>() : urls;
    }

    public void setGird(boolean gird) {
        isGird = gird;
    }

}