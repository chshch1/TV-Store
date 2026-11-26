package com.example.tvwebdavbox;

import android.app.Activity;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

/**
 * 内置播放器：用系统 MediaPlayer 解码，避免部分电视盒子没有第三方播放器。
 * 支持遥控器方向键调节进度（由 MediaController 提供）。
 */
public class PlayerActivity extends Activity {

    private VideoView videoView;
    private TextView errorText;
    private MediaController controller;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);

        videoView = findViewById(R.id.video_view);
        errorText = findViewById(R.id.player_error);

        Uri uri = getIntent().getData();
        if (uri == null) {
            finish();
            return;
        }

        controller = new MediaController(this);
        controller.setAnchorView(videoView);
        videoView.setMediaController(controller);

        videoView.setOnPreparedListener(mp -> {
            errorText.setVisibility(View.GONE);
            mp.setLooping(false);
        });
        videoView.setOnErrorListener((mp, what, extra) -> {
            errorText.setVisibility(View.VISIBLE);
            errorText.setText(R.string.play_failed);
            Toast.makeText(this, R.string.play_failed, Toast.LENGTH_LONG).show();
            return true;
        });
        videoView.setOnCompletionListener(MediaPlayer::stop);

        videoView.setVideoURI(uri);
        videoView.requestFocus();
        videoView.start();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // 播放界面把按键优先交给 MediaController，遥控器即可暂停/快进
        if (controller != null && controller.dispatchKeyEvent(event)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (videoView != null && videoView.isPlaying()) {
            videoView.pause();
        }
    }

    @Override
    protected void onDestroy() {
        if (videoView != null) {
            videoView.stopPlayback();
        }
        super.onDestroy();
    }
}
