package com.github.tvbox.osc.ui.activity;

import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;

import com.blankj.utilcode.util.AppUtils;
import com.blankj.utilcode.util.ToastUtils;
import com.github.tvbox.osc.base.BaseVbActivity;
import com.github.tvbox.osc.databinding.ActivityAboutBinding;
import com.github.tvbox.osc.download.PlayUrlResolver;
import com.github.tvbox.osc.util.HeavyTaskUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONObject;

/**
 * 关于页(原底部弹窗 AboutDialog 改为独立页面)
 */
public class AboutActivity extends BaseVbActivity<ActivityAboutBinding> {

    /** GitHub 最新 release 查询接口 */
    private static final String UPDATE_API = "https://api.github.com/repos/kertou/TVBox-mobile/releases/latest";
    /** 下载页地址(releases/latest 始终重定向到最新版) */
    private static final String RELEASE_URL = "https://github.com/kertou/TVBox-mobile/releases/latest";

    @Override
    protected void init() {
        mBinding.titleBar.setNavigationOnClickListener(v -> finish());
        mBinding.tvVersion.setText("TVBox-Mobile v" + AppUtils.getAppVersionName());
        mBinding.layoutCheckUpdate.setOnClickListener(v -> checkUpdate());
    }

    private void checkUpdate() {
        ToastUtils.showShort("正在检查更新…");
        mBinding.tvCheckState.setText("正在检查…");
        HeavyTaskUtil.executeNewTask(() -> {
            String tag = null;
            try {
                String json = PlayUrlResolver.fetchText(UPDATE_API);
                if (!TextUtils.isEmpty(json)) {
                    tag = new JSONObject(json).optString("tag_name", null);
                }
            } catch (Throwable th) {
                th.printStackTrace();
            }
            final String latest = tag;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (TextUtils.isEmpty(latest)) {
                    mBinding.tvCheckState.setText("检查失败,点此重试");
                    ToastUtils.showShort("检查失败,请检查网络后重试");
                    return;
                }
                String current = AppUtils.getAppVersionName();
                if (compareVersion(latest, current) > 0) {
                    mBinding.tvCheckState.setText("发现新版本 " + latest);
                    new MaterialAlertDialogBuilder(this)
                            .setTitle("发现新版本 " + latest)
                            .setMessage("当前版本 v" + current + ",是否前往下载页面?")
                            .setPositiveButton("去下载", (d, w) ->
                                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(RELEASE_URL))))
                            .setNegativeButton("取消", null)
                            .show();
                } else {
                    mBinding.tvCheckState.setText("已是最新版本");
                    ToastUtils.showShort("已是最新版本");
                }
            });
        });
    }

    /** 版本号逐段数值比较(tag 形如 v3.1.0): a>b 返回 1, a<b 返回 -1, 相等返回 0 */
    private static int compareVersion(String a, String b) {
        String[] sa = a.trim().replaceFirst("^[vV]", "").split("\\.");
        String[] sb = b.trim().replaceFirst("^[vV]", "").split("\\.");
        int n = Math.max(sa.length, sb.length);
        for (int i = 0; i < n; i++) {
            int va = i < sa.length ? parseSegment(sa[i]) : 0;
            int vb = i < sb.length ? parseSegment(sb[i]) : 0;
            if (va != vb) {
                return va > vb ? 1 : -1;
            }
        }
        return 0;
    }

    private static int parseSegment(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
