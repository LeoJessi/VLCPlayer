package top.jessi.vlc_simple;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;


import java.util.Arrays;

import top.jessi.vlc_simple.vlc.VlcPlayerActivity;
import top.jessi.vlcplayer.VlcVideoView;
import top.jessi.vlcplayer.listener.util.LogUtils;
import top.jessi.vlcplayer.listener.util.VLCInstance;

public class MainActivity extends AppCompatActivity {
    public static final String path = "http://tv.ghostplay.in:8880/series/heredf287994061082/2879940610822545/830.mp4";

    private final String tag = "MainActivity";
    public static boolean testNetWork = false;

    private VlcVideoView mVideoView;

    public static String getUrl(Context context) {
        if (testNetWork) {
            return path;
        } else {
            return path;
            // return MyApp.getProxy().getProxyUrl(path, true);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        init();

        mVideoView = findViewById(R.id.jessiPlayer);
        mVideoView.setPath(path);
        mVideoView.startPlay();

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            mVideoView.onVideoSizeChanged(100, 60, 300, 60);
        }, 10000);
    }

    private void init() {
        // 加载库文件
        if (VLCInstance.testCompatibleCPU(this)) {
            Log.i(tag, "support   cpu");
        } else {
            Log.i(tag, "not support  cpu");
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 10);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 10) {
            if (grantResults[0] != 0) {
                Toast.makeText(this, "WRITE_EXTERNAL_STORAGE error", Toast.LENGTH_LONG).show();
            }
            LogUtils.i("Permissions", "grantResults=" + Arrays.toString(grantResults));
        }
    }

    public void myClick2(View view) {
        startActivity(new Intent(this, VlcPlayerActivity.class));
    }

    public void myClick3(View view) {
        startActivity(new Intent(this, TestActivity.class));
    }

}
