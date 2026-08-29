package com.github.tvbox.osc.ui.adapter;

import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;

import java.util.ArrayList;

/**
 * 我的缓存海报网格:每部剧一张卡片,外观与主页 item_grid 一致
 */
public class CacheGridAdapter extends BaseQuickAdapter<CacheGridAdapter.Item, BaseViewHolder> {

    public static class Item {
        public String groupKey;
        public String name;
        public String pic;
        /** 角标文案:状态聚合(已缓存 n 集 / 缓存中 n/m / 已暂停 / 失败) */
        public String note;
        /** 左上角标(主页年份的位置):占用大小 */
        public String sizeText;
    }

    public CacheGridAdapter() {
        super(R.layout.item_grid, new ArrayList<>());
    }

    @Override
    protected void convert(BaseViewHolder helper, Item item) {
        helper.setText(R.id.tvName, item.name);
        TextView tvYear = helper.getView(R.id.tvYear);
        if (TextUtils.isEmpty(item.sizeText)) {
            tvYear.setVisibility(View.GONE);
        } else {
            tvYear.setVisibility(View.VISIBLE);
            tvYear.setText(item.sizeText);
        }
        TextView tvNote = helper.getView(R.id.tvNote);
        if (TextUtils.isEmpty(item.note)) {
            tvNote.setVisibility(View.GONE);
        } else {
            tvNote.setVisibility(View.VISIBLE);
            tvNote.setText(item.note);
        }
        ImageView ivThumb = helper.getView(R.id.ivThumb);
        if (!TextUtils.isEmpty(item.pic)) {
            Glide.with(ivThumb.getContext())
                    .load(item.pic.trim())
                    .placeholder(R.drawable.img_loading_placeholder)
                    .error(R.drawable.img_loading_placeholder)
                    .into(ivThumb);
        } else {
            ivThumb.setImageResource(R.drawable.img_loading_placeholder);
        }
    }

    /** 状态聚合文案(大小走左上角标 sizeText):全部完成给总数,否则给完成进度 */
    public static String buildNote(int done, int total, int waiting, int resolving, int downloading,
                                   int paused, int failed) {
        if (done >= total && total > 0) {
            return "已缓存 " + total + " 集";
        }
        String prefix = null;
        if (downloading > 0 || resolving > 0) {
            prefix = "缓存中 ";
        } else if (waiting > 0) {
            prefix = "排队中 ";
        } else if (paused > 0) {
            prefix = "已暂停 ";
        } else if (failed > 0) {
            prefix = "有失败 ";
        }
        String base = "已缓存 " + done + "/" + total + " 集";
        if (failed > 0) {
            base += " · " + failed + " 集失败";
        }
        return prefix != null ? prefix + base : base;
    }
}
