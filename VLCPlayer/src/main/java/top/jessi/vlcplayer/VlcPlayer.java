package top.jessi.vlcplayer;


import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.text.TextUtils;
import android.view.Surface;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.interfaces.IMedia;
import org.videolan.libvlc.interfaces.IVLCVout;

import java.util.ArrayList;
import java.util.List;

import top.jessi.vlcplayer.listener.MediaListenerEvent;
import top.jessi.vlcplayer.listener.MediaPlayerControl;
import top.jessi.vlcplayer.listener.VideoSizeChange;
import top.jessi.vlcplayer.listener.util.LogUtils;

/**
 * 这个类实现了大部分视频播放器功能
 * 只是提供参考的实例代码
 * 如果只播放音频不建议使用
 * <p>
 * 如果有bug请在github留言
 *
 * @author https://github.com/mengzhidaren
 */
public class VlcPlayer implements MediaPlayerControl, Handler.Callback, IVLCVout.OnNewVideoLayoutListener {
    private final String tag = "VlcPlayer";
    private final Handler threadHandler;// 工作线程

    private static final int INIT_START = 0x0008;
    private static final int INIT_STOP = 0x0009;
    private static final int INIT_DESTORY = 0x0010;
    private static final int STATE_PLAY = 1;
    private static final int STATE_PAUSE = 2;
    private static final int STATE_LOAD = 3;
    private static final int STATE_RESUME = 4;
    private static final int STATE_STOP = 5;
    private int currentState = STATE_LOAD;
    private float speed = 1f;
    private float position = 0f;
    private long time = 0;
    //    private final Lock lock = new ReentrantLock();
    // 单线程操作视频初始化和释放功能   防止多线程互斥
    private static final HandlerThread sThread = new HandlerThread("VlcPlayThread");

    static {
        sThread.start();
    }

    private volatile boolean isInitStart;// 初始化Video类
    private boolean isSufaceDelayerPlay;// 布局延迟加载播放  surface线程异步状态中
    private boolean canSeek;// 能否快进
    private boolean canPause;//
    private boolean canReadInfo;// 能否读取视频信息
    private boolean isPlayError;
    private volatile boolean isSurfaceAvailable;// surface是否存在
    private boolean isAttachedSurface;// surface是否关联
    private boolean isLoop = true;// 循环
    private boolean isDestory;// 回收当前activity
    private boolean isSeeking;// 跳转位置中

    // 切换view.parent时的2秒黑屏用seek恢复
    public boolean clearVideoTrackCache = false;
    // 硬件加速
    public boolean HWDecoderEnable = false;

    private MediaPlayer mMediaPlayer;
    private Surface surfaceSlave;// 字幕画布
    private Surface surfaceVideo;// 视频画布
    private int surfaceW, surfaceH;
    private String path;
    private List<String> optionList = new ArrayList<>();
    private LibVLC libVLC;

    private boolean loadOtherMedia;

    // 字幕文件
    private String addSlave;

    // libVLC 由外传入方便定制播放器
    public VlcPlayer(LibVLC libVLC) {
        this.libVLC = libVLC;
//        isAvailable
        threadHandler = new Handler(sThread.getLooper(), this);
    }

    @Override
    public boolean handleMessage(Message msg) {
        switch (msg.what) {
            case INIT_START:
                if (isInitStart)
                    openVideo();
                break;
            case INIT_STOP:
                onStopVideo();
                break;
            case INIT_DESTORY:
                if (mMediaPlayer != null) {
                    mMediaPlayer.setEventListener(null);
                    if (!mMediaPlayer.isReleased()) {
                        mMediaPlayer.release();
                        mMediaPlayer = null;
                    }
                }
                break;
        }
        return true;
    }


    /**
     * surface线程  可能有延迟
     */
    public void setSurface(Surface surfaceVideo, Surface surfaceSlave) {
        isSurfaceAvailable = true;
        this.surfaceVideo = surfaceVideo;
        this.surfaceSlave = surfaceSlave;
        LogUtils.i(tag, "setSurface");
        if (isSufaceDelayerPlay && isInitStart) {// surface未创建时延迟加载播放
            isSufaceDelayerPlay = false;
            startPlay();
        } else if (isInitStart) {
            attachSurface();
            if (currentState == STATE_RESUME || currentState == STATE_PLAY) {
                start();
            }
        }
    }

    public void setWindowSize(int surfaceW, int surfaceH) {
        this.surfaceW = surfaceW;
        this.surfaceH = surfaceH;
        if (mMediaPlayer != null) {
            mMediaPlayer.getVLCVout().setWindowSize(surfaceW, surfaceH);
        }
    }

    private void attachSurface() {
        if (!mMediaPlayer.getVLCVout().areViewsAttached() && isSurfaceAvailable && !isAttachedSurface) {
            LogUtils.i(tag, "attachSurface");

            setWindowSize(surfaceW, surfaceH);
//            mMediaPlayer.getVLCVout().attachSurfaceSlave(surfaceVideo, surfaceSlave, this);
            if (surfaceSlave != null && surfaceSlave.isValid()) {
                mMediaPlayer.getVLCVout().setSubtitlesSurface(surfaceSlave, null);
            }
            if (surfaceVideo != null && surfaceVideo.isValid()) {
                isAttachedSurface = true;
                mMediaPlayer.getVLCVout().setVideoSurface(surfaceVideo, null);
                mMediaPlayer.getVLCVout().attachViews();
                if (attachVideoTrack) {
                    clearVideoTrackCacheSeek();// 这里真坑 为什么没有清空cache的方法 后期让官方改
                    mMediaPlayer.setVideoTrackEnabled(true);
                    attachVideoTrack = false;
                }
            }
        }
    }

    boolean attachVideoTrack = false;

    public void onSurfaceTextureDestroyedUI() {
        isSurfaceAvailable = false;
        this.surfaceVideo = null;
        this.surfaceSlave = null;
        LogUtils.i(tag, "onSurfaceTextureDestroyedUI");
        if (isAttachedSurface) {
            isAttachedSurface = false;
            if (mMediaPlayer != null) {
                if (isInitStart) {
                    mMediaPlayer.setVideoTrackEnabled(false);
                    attachVideoTrack = true;
                }
                mMediaPlayer.getVLCVout().detachViews();
            }
        }
        // 不在这里暂停
//        if (isPlaying()) {
//            mMediaPlayer.pause();
//            currentState = STATE_RESUME;
//        }
    }


    // 回收界面时用  不重要  可以不调用 不影响稳定性
    public void onDestroy() {
        videoSizeChange = null;
        isDestory = true;
        onStop();
        threadHandler.obtainMessage(INIT_DESTORY).sendToTarget();
    }

    public void onStop() {
        LogUtils.i(tag, "onStop=" + isInitStart);
        if (!isInitStart)
            return;
        isInitStart = false;
        isSufaceDelayerPlay = false;
        currentState = STATE_STOP;
        if (mediaListenerEvent != null && !isDestory)
            mediaListenerEvent.eventPlayInit(false);
        threadHandler.obtainMessage(INIT_STOP).sendToTarget();
    }

    @Override
    public void startPlay() {
        LogUtils.i(tag, "startPlay");
        isInitStart = true;
        currentState = STATE_LOAD;
        if (mediaListenerEvent != null)
            mediaListenerEvent.eventPlayInit(true);
        if (isSurfaceAvailable) {
            isSufaceDelayerPlay = false;
            threadHandler.obtainMessage(INIT_START).sendToTarget();
        } else {
            isSufaceDelayerPlay = true;
        }
    }

    private void reStartPlay() {
        initVideoState();
        if (isAttachedSurface && isLoop && isPrepare()) {
            LogUtils.i(tag, "reStartPlay setMedia");
            mMediaPlayer.setMedia(mMediaPlayer.getMedia());
            mMediaPlayer.play();
        } else {
            if (mediaListenerEvent != null && isInitStart)
                mediaListenerEvent.eventStop(isPlayError);
        }
    }

    private void errorEvent() {
        if (mediaListenerEvent != null && isInitStart)
            mediaListenerEvent.eventError(MediaListenerEvent.EVENT_ERROR, true);
    }

    private void openVideo() {
        canSeek = false;
        isPlayError = false;
        initVideoState();
        if (mMediaPlayer == null) {
            mMediaPlayer = new MediaPlayer(libVLC);
            //    mMediaPlayer.setAudioOutput(VLCOptions.getAout(PreferenceManager.getDefaultSharedPreferences
            //    (mContext)));
            //   mMediaPlayer.setEqualizer(VLCOptions.getEqualizer(mContext));
        }
        if (!isAttachedSurface && mMediaPlayer.getVLCVout().areViewsAttached()) {// 异常判断
            mMediaPlayer.getVLCVout().detachViews();
            LogUtils.i("异常  isAttachedSurface");
        }
        mMediaPlayer.setEventListener(new MediaPlayer.EventListener() {
            @Override
            public void onEvent(MediaPlayer.Event event) {
                onEventNative(event);
            }
        });
        boolean isLoadMedia = loadMedia(libVLC);
        attachSurface();
        if (isSurfaceAvailable && isInitStart && isAttachedSurface && isLoadMedia) {
            mMediaPlayer.play();
            LogUtils.i("加载 " + isLoadMedia);
        } else {
            LogUtils.i("异常  没有视频可加载");
        }
    }

    /**
     * //http://www.baidu
     * //rtmp://58.61.150.198/live/Livestream
     * // ftp://www.baidu
     * // sdcard/mp4.mp4
     */
    private boolean loadMedia(LibVLC libVLC) {
        if (loadOtherMedia) {
            loadOtherMedia = false;
            return true;
        }
        if (TextUtils.isEmpty(path)) {
            return false;
        }
        Media media;
        if (path.contains("://")) {
            media = new Media(libVLC, Uri.parse(path));
        } else {
            media = new Media(libVLC, path);
        }
        if (!optionList.isEmpty()) {
            for (int i = 0, len = optionList.size(); i < len; i++) {
                media.addOption(optionList.get(i));
            }
        }
        media.setHWDecoderEnabled(HWDecoderEnable, false);
        media.setEventListener(mMediaListener);
        mMediaPlayer.setMedia(media);
        media.release();
        return true;
    }

    private final Media.EventListener mMediaListener = new Media.EventListener() {
        @Override
        public void onEvent(Media.Event event) {
            switch (event.type) {
                case Media.Event.MetaChanged:
                    LogUtils.i(tag, "Media.Event.MetaChanged:  =" + event.getMetaId());
                    break;
                case Media.Event.ParsedChanged:
                    LogUtils.i(tag, "Media.Event.ParsedChanged  =" + event.getMetaId());

                    Media.VideoTrack track = mMediaPlayer.getCurrentVideoTrack();
                    LogUtils.i(tag, "Media.Event.MetaChanged:  =" + event.getMetaId() + " ~~ " + track);
                    if (track != null) {
                        if (track.width > 0 && track.height > 0) {
                            LogUtils.i(tag, "Media.Event.MetaChanged:~~~~  =" + track.width + " ~~ " + track.height);
                        }
                    }
                    break;
                case Media.Event.StateChanged:
                    LogUtils.i(tag, "StateChanged   =" + event.getMetaId());
                    break;
                default:
                    LogUtils.i(tag,
                            "Media.Event.type=" + event.type + "   eventgetParsedStatus=" + event.getParsedStatus());
                    break;
            }
        }
    };

    @Override
    public void setPath(String path) {
        this.path = path;
    }

    private void initVideoState() {
        canSeek = false;
        canReadInfo = false;
        canPause = false;
        time = 0;
        position = 0f;
    }

    private void onStopVideo() {
        LogUtils.i(tag, "release");
        initVideoState();
        if (mMediaPlayer != null) {
            final IMedia media = mMediaPlayer.getMedia();
            if (media != null) {
                media.setEventListener(null);
                mMediaPlayer.stop();
                mMediaPlayer.setMedia(null);
                media.release();
            }

        }
    }

    private void onEventNative(final MediaPlayer.Event event) {
        switch (event.type) {
            case MediaPlayer.Event.Stopped:
                LogUtils.i(tag, "Stopped  isLoop=" + isLoop);
                reStartPlay();
                break;
            case MediaPlayer.Event.EndReached:
                LogUtils.i(tag, "EndReached");
                break;
            case MediaPlayer.Event.EncounteredError:
                isPlayError = true;
                canReadInfo = false;
                LogUtils.i(tag, "EncounteredError");
                errorEvent();
                break;
            case MediaPlayer.Event.Opening:
                LogUtils.i(tag, "Opening");
                canReadInfo = true;
                if (getPlaybackSpeed() != 1f) {
                    mMediaPlayer.setRate(1f);
                }
                speed = 1f;
                loadSlave();
                break;
            case MediaPlayer.Event.Playing:
                LogUtils.i(tag, "Playing");
                if (mediaListenerEvent != null && isInitStart)
                    mediaListenerEvent.eventPlay(true);
                if (currentState == STATE_PAUSE || !isAttachedSurface) {
                    pause();
                }
                break;
            case MediaPlayer.Event.Paused:
                LogUtils.i(tag, "Paused");
                if (mediaListenerEvent != null && isInitStart)
                    mediaListenerEvent.eventPlay(false);
                break;
            case MediaPlayer.Event.TimeChanged:// TimeChanged   15501
                // LogUtils.i(tag, "TimeChanged" + event.getTimeChanged());
//                if (isABLoop && isAttached && canSeek && abTimeEnd > 0) {
//                    if (event.getTimeChanged() > abTimeEnd) {
//                               seekTo(abTimeStart);
//                    }
//                }
                time = event.getTimeChanged();
                break;
            case MediaPlayer.Event.PositionChanged:// PositionChanged   0.061593015
                // LogUtils.i(tag, "PositionChanged" + event.getPositionChanged());
                position = event.getPositionChanged();
                break;
            case MediaPlayer.Event.Vout:
                LogUtils.i(tag, "Vout" + event.getVoutCount());
                break;
            case MediaPlayer.Event.ESAdded:
                LogUtils.i(tag, "ESAdded");
                break;
            case MediaPlayer.Event.ESDeleted:
                LogUtils.i(tag, "ESDeleted");
                break;
            case MediaPlayer.Event.SeekableChanged:
                canSeek = event.getSeekable();
                LogUtils.i(tag, "SeekableChanged=" + canSeek);
                break;
            case MediaPlayer.Event.PausableChanged:
                canPause = event.getPausable();
                LogUtils.i(tag, "PausableChanged=" + event.getPausable());
                break;
            case MediaPlayer.Event.Buffering:
                LogUtils.i(tag, "MediaPlayer.Event.Buffering" + event.getBuffering());
                if (mediaListenerEvent != null && isInitStart)
                    mediaListenerEvent.eventBuffing(MediaListenerEvent.EVENT_BUFFING, event.getBuffering());
                if (currentState == STATE_PAUSE || !isAttachedSurface) {// 关屏有音 bug
                    if (event.getBuffering() == 100f && isPrepare()) {
                        mMediaPlayer.pause();
                    }
                }
                break;
            case MediaPlayer.Event.MediaChanged:
                LogUtils.i(tag, "MediaChanged=" + event.getEsChangedType());
                break;
            default:
                LogUtils.i(tag, "event.type=" + event.type);
                break;
        }
    }

    @Override
    public boolean isPrepare() {
        return isInitStart && !isPlayError && mMediaPlayer != null;
    }

    @Override
    public boolean canControl() {// 直播流不要用这个
        return canReadInfo && canSeek && canPause;
    }

    @Override
    public void start() {
        LogUtils.i(tag, "start");
        currentState = STATE_PLAY;
        if (isPrepare() && isAttachedSurface)
            mMediaPlayer.play();
    }

    @Override
    public void pause() {
        currentState = STATE_PAUSE;
        if (isPrepare() && canPause) {
            mMediaPlayer.pause();
        }
    }


    @Override
    public int getDuration() {
        if (isPrepare() && canReadInfo) {
            return (int) mMediaPlayer.getLength();
        }
        return 0;
    }

    @Override
    public int getCurrentPosition() {
        if (isPrepare() && canReadInfo)
            return (int) (mMediaPlayer.getLength() * mMediaPlayer.getPosition());
        return 0;
    }


    @Override
    public void seekTo(int pos) {
        if (isPrepare() && canSeek && !isSeeking) {
            isSeeking = true;
            mMediaPlayer.setTime(pos);
            isSeeking = false;
        }
    }

    @Override
    public void seekTo(long pos) {
        if (isPrepare() && canSeek && !isSeeking) {
            isSeeking = true;
            mMediaPlayer.setTime(pos);
            isSeeking = false;
        }
    }

    @Override
    public boolean isPlaying() {
        if (isPrepare()) {
            return mMediaPlayer.isPlaying();
        }
        return false;
    }


    @Override
    public boolean setPlaybackSpeedMedia(float speed) {
        if (isPrepare() && canSeek) {
            this.speed = speed;
            mMediaPlayer.setRate(speed);
            // seekTo(getCurrentPosition());
        }
        return true;
    }

    @Override
    public float getPlaybackSpeed() {
        if (isPrepare() && canSeek)
            return mMediaPlayer.getRate();
        return speed;
    }


    private MediaListenerEvent mediaListenerEvent;

    public void setMediaListenerEvent(MediaListenerEvent mediaListenerEvent) {
        this.mediaListenerEvent = mediaListenerEvent;
    }

    private VideoSizeChange videoSizeChange;

    public void setVideoSizeChange(VideoSizeChange videoSizeChange) {
        this.videoSizeChange = videoSizeChange;
    }

    public Media.VideoTrack getVideoTrack() {
        if (isPrepare())
            return mMediaPlayer.getCurrentVideoTrack();
        return null;
    }

    @Override
    public void onNewVideoLayout(IVLCVout vlcVout, int width, int height, int visibleWidth, int visibleHeight,
                                 int sarNum, int sarDen) {
        if (videoSizeChange != null) {
            videoSizeChange.onVideoSizeChanged(width, height, visibleWidth, visibleHeight);
        }
    }

    public int getOrientation() {
        Media.VideoTrack videoTrack = getVideoTrack();
        if (videoTrack != null) {
            LogUtils.i(tag, "videoTrack=" + videoTrack.toString());
            return videoTrack.orientation;
        }
        return 0;
    }

    @Override
    public void setMirror(boolean mirror) {

    }

    @Override
    public boolean getMirror() {
        return false;
    }

    @Override
    public int getBufferPercentage() {
        return 0;
    }

    @Override
    public boolean canPause() {
        return canPause;
    }

    @Override
    public boolean canSeekBackward() {
        return false;
    }

    @Override
    public boolean canSeekForward() {
        return false;
    }

    @Override
    public void setLoop(boolean isLoop) {
        this.isLoop = isLoop;
    }

    @Override
    public boolean isLoop() {
        return isLoop;
    }

    @Override
    public int getAudioSessionId() {
        if (isPrepare()) {
            return mMediaPlayer.getAudioTrack();
        }
        return 0;
    }

    public MediaPlayer getMediaPlayer() {
        return mMediaPlayer;
    }

    // 切换parent时的2秒黑屏用seek恢复
    public void clearVideoTrackCacheSeek() {
        if (clearVideoTrackCache && isPrepare() && canSeek && time > 0) {
            mMediaPlayer.setTime(time);
        }
    }

    public void setMediaPlayer(MediaPlayer mediaPlayer) {
        this.mMediaPlayer = mediaPlayer;
    }

    public void setMedia(IMedia media) {
        if (mMediaPlayer == null) {
            mMediaPlayer = new MediaPlayer(libVLC);
        }
        mMediaPlayer.setMedia(media);
        loadOtherMedia = true;
    }

    public void setAddSlave(String addSlave) {
        this.addSlave = addSlave;
    }

    // 加载字幕
    private void loadSlave() {
        if (!TextUtils.isEmpty(addSlave)) {
            if (addSlave.contains("://")) {
                mMediaPlayer.addSlave(Media.Slave.Type.Subtitle, Uri.parse(addSlave), true);
            } else {
                mMediaPlayer.addSlave(Media.Slave.Type.Subtitle, addSlave, true);
            }
        }
    }

    public void setVolume(float leftVolume, float rightVolume) {
        mMediaPlayer.setVolume((int) ((leftVolume + rightVolume) * 100 / 2));
    }

    public void setMute() {
        mMediaPlayer.setVolume(0);
    }


    public void addOption(List<String> list) {
        this.optionList = list;
    }
}
