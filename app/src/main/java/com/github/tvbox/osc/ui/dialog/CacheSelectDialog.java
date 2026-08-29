package com.github.tvbox.osc.ui.dialog;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.text.TextUtils;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.blankj.utilcode.util.ScreenUtils;
import com.blankj.utilcode.util.ToastUtils;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.cache.DownloadEpisode;
import com.github.tvbox.osc.download.DownloadTaskManager;
import com.github.tvbox.osc.ui.activity.DetailActivity;
import com.github.tvbox.osc.ui.adapter.CacheSelectAdapter;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.Utils;
import com.lxj.xpopup.core.BottomPopupView;
import com.orhanobut.hawk.Hawk;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 应用内缓存选集弹窗:支持全选/反选/仅当前集,确认后进入下载队列。
 */
public class CacheSelectDialog extends BottomPopupView {

    private final VodInfo mVodInfo;
    private final Set<String> mCachedUrls;
    private CacheSelectAdapter mAdapter;
    private TextView tvConfirm;

    public CacheSelectDialog(@NonNull @NotNull Context context, VodInfo vodInfo, Set<String> cachedUrls) {
        super(context);
        mVodInfo = vodInfo;
        mCachedUrls = cachedUrls;
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_cache_select;
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        TextView tvSubTitle = findViewById(R.id.tvSubTitle);
        tvSubTitle.setText((mVodInfo.name == null ? "" : mVodInfo.name) + " · " + mVodInfo.playFlag);

        RecyclerView rv = findViewById(R.id.rvEpisodes);
        rv.setLayoutManager(new GridLayoutManager(getContext(), 4));
        mAdapter = new CacheSelectAdapter();
        List<CacheSelectAdapter.Item> items = new ArrayList<>();
        List<VodInfo.VodSeries> series = mVodInfo.seriesMap.get(mVodInfo.playFlag);
        for (int i = 0; i < series.size(); i++) {
            CacheSelectAdapter.Item item = new CacheSelectAdapter.Item();
            item.name = series.get(i).name;
            item.index = i;
            item.checked = false;
            item.cached = mCachedUrls != null && series.get(i).url != null && mCachedUrls.contains(series.get(i).url);
            items.add(item);
        }
        mAdapter.setNewData(items);
        rv.setAdapter(mAdapter);

        tvConfirm = findViewById(R.id.tvConfirm);
        updateConfirmText();

        mAdapter.setOnItemClickListener((adapter, view, position) -> {
            CacheSelectAdapter.Item item = mAdapter.getData().get(position);
            if (item.cached) {
                ToastUtils.showShort("该集已缓存");
                return;
            }
            item.checked = !item.checked;
            mAdapter.notifyItemChanged(position);
            updateConfirmText();
        });

        findViewById(R.id.tvSelectAll).setOnClickListener(v -> {
            boolean all = true;
            for (CacheSelectAdapter.Item item : mAdapter.getData()) {
                if (!item.cached && !item.checked) {
                    all = false;
                    break;
                }
            }
            for (CacheSelectAdapter.Item item : mAdapter.getData()) {
                if (!item.cached) item.checked = !all;
            }
            mAdapter.notifyDataSetChanged();
            updateConfirmText();
        });
        findViewById(R.id.tvSelectInvert).setOnClickListener(v -> {
            for (CacheSelectAdapter.Item item : mAdapter.getData()) {
                if (!item.cached) item.checked = !item.checked;
            }
            mAdapter.notifyDataSetChanged();
            updateConfirmText();
        });
        findViewById(R.id.tvSelectCurrent).setOnClickListener(v -> {
            for (CacheSelectAdapter.Item item : mAdapter.getData()) {
                item.checked = item.index == mVodInfo.playIndex && !item.cached;
            }
            mAdapter.notifyDataSetChanged();
            updateConfirmText();
        });

        tvConfirm.setOnClickListener(v -> {
            List<CacheSelectAdapter.Item> selected = new ArrayList<>();
            for (CacheSelectAdapter.Item item : mAdapter.getData()) {
                if (item.checked) selected.add(item);
            }
            if (selected.isEmpty()) {
                ToastUtils.showShort("请先选择要缓存的剧集");
                return;
            }
            startDownload(selected);
        });

        findViewById(R.id.tvUse1DM).setOnClickListener(v -> {
            dismiss();
            if (getContext() instanceof DetailActivity) {
                ((DetailActivity) getContext()).use1DMDownload();
            }
        });
    }

    private void updateConfirmText() {
        int count = 0;
        for (CacheSelectAdapter.Item item : mAdapter.getData()) {
            if (item.checked) count++;
        }
        tvConfirm.setText("开始缓存(" + count + "集)");
    }

    private void startDownload(List<CacheSelectAdapter.Item> selected) {
        if (!showDisclaimerIfNeeded()) {
            return;
        }
        requestNotificationPermissionIfNeeded();
        List<DownloadEpisode> tasks = new ArrayList<>();
        for (CacheSelectAdapter.Item item : selected) {
            VodInfo.VodSeries vs = mVodInfo.seriesMap.get(mVodInfo.playFlag).get(item.index);
            DownloadEpisode task = new DownloadEpisode();
            task.sourceKey = mVodInfo.sourceKey;
            task.vodId = mVodInfo.id;
            task.vodName = mVodInfo.name;
            task.vodPic = mVodInfo.pic;
            task.flag = mVodInfo.playFlag;
            task.episodeName = vs.name;
            task.episodeIndex = item.index;
            task.rawUrl = vs.url;
            tasks.add(task);
        }
        DownloadTaskManager.get().enqueue(tasks);
        ToastUtils.showShort("已加入缓存队列,可在通知栏查看进度");
        dismiss();
    }

    /**
     * 首次使用弹出免责声明,同意后不再提示
     */
    private boolean showDisclaimerIfNeeded() {
        if (Hawk.get(HawkConfig.DOWNLOAD_DISCLAIMER_AGREED, false)) {
            return true;
        }
        Activity activity = (Activity) getContext();
        new com.lxj.xpopup.XPopup.Builder(activity)
                .isDarkTheme(Utils.isDarkTheme())
                .asConfirm("免责声明",
                        "使用应用内缓存功能前,请仔细阅读并同意以下条款:\n\n" +
                                "1. 视频内容均来自第三方数据源,本应用不存储、不上传、不分发任何视频内容;\n\n" +
                                "2. 缓存的视频仅供您本人在本应用内离线观看,严禁传播、分发或用于任何商业用途;\n\n" +
                                "3. 请尊重内容版权,支持正版;因使用本功能产生的任何法律责任由您自行承担。\n\n" +
                                "点击\"同意\"即表示您已阅读并接受以上条款。",
                        "取消", "同意",
                        () -> {
                            Hawk.put(HawkConfig.DOWNLOAD_DISCLAIMER_AGREED, true);
                            // 用户同意后再真正入队
                            List<CacheSelectAdapter.Item> selected = new ArrayList<>();
                            for (CacheSelectAdapter.Item item : mAdapter.getData()) {
                                if (item.checked) selected.add(item);
                            }
                            if (!selected.isEmpty()) {
                                startDownload(selected);
                            }
                        }, null, false).show();
        return false;
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && getContext() instanceof Activity) {
            Activity activity = (Activity) getContext();
            try {
                activity.requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 10021);
            } catch (Throwable ignored) {
            }
        }
    }
}
