package com.imagemanager.service;

import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 音乐播放服务 — 管理幻灯片背景音乐的播放、暂停、切换。
 * <p>
 * 使用 JavaFX MediaPlayer 播放内置的 MP3 音乐文件。
 * 支持循环播放和音量控制。
 */
public class MusicService {

    private static final Logger logger = LoggerFactory.getLogger(MusicService.class);

    /** 内置音乐列表 — 资源路径映射 */
    private static final Map<String, String> MUSIC_LIBRARY = new LinkedHashMap<>() {{
        put("轻松钢琴", "/music/relax_piano.mp3");
        put("自然之声", "/music/nature_sounds.mp3");
        put("柔和吉他", "/music/gentle_guitar.mp3");
    }};

    private MediaPlayer currentPlayer;
    private String currentMusicName;
    private double volume = 0.5;

    /**
     * 获取可用的音乐列表名称。
     */
    public Map<String, String> getMusicLibrary() {
        return MUSIC_LIBRARY;
    }

    /**
     * 播放指定名称的音乐（循环播放）。
     *
     * @param musicName 音乐名称（如"轻松钢琴"）
     */
    public void play(String musicName) {
        String resourcePath = MUSIC_LIBRARY.get(musicName);
        if (resourcePath == null) {
            logger.warn("未知的音乐名称: {}", musicName);
            return;
        }

        // 如果正在播放同一首，不重复
        if (musicName.equals(currentMusicName) && currentPlayer != null
                && currentPlayer.getStatus() == MediaPlayer.Status.PLAYING) {
            return;
        }

        // 停止当前播放
        stop();

        try {
            URL musicUrl = getClass().getResource(resourcePath);
            if (musicUrl == null) {
                logger.error("音乐文件不存在: {}", resourcePath);
                return;
            }

            Media media = new Media(musicUrl.toExternalForm());
            currentPlayer = new MediaPlayer(media);
            currentPlayer.setCycleCount(MediaPlayer.INDEFINITE); // 循环播放
            currentPlayer.setVolume(volume);
            currentPlayer.play();
            currentMusicName = musicName;

            logger.info("开始播放音乐: {} (循环)", musicName);

        } catch (Exception e) {
            logger.error("播放音乐失败: {}", musicName, e);
        }
    }

    /**
     * 停止播放。
     */
    public void stop() {
        if (currentPlayer != null) {
            currentPlayer.stop();
            currentPlayer.dispose();
            currentPlayer = null;
            currentMusicName = null;
            logger.info("音乐已停止");
        }
    }

    /**
     * 暂停/恢复播放。
     */
    public void togglePause() {
        if (currentPlayer == null) return;

        if (currentPlayer.getStatus() == MediaPlayer.Status.PLAYING) {
            currentPlayer.pause();
        } else if (currentPlayer.getStatus() == MediaPlayer.Status.PAUSED) {
            currentPlayer.play();
        }
    }

    /**
     * 设置音量 (0.0 ~ 1.0)。
     */
    public void setVolume(double vol) {
        this.volume = Math.max(0.0, Math.min(1.0, vol));
        if (currentPlayer != null) {
            currentPlayer.setVolume(this.volume);
        }
    }

    /**
     * 获取当前音量。
     */
    public double getVolume() {
        return volume;
    }

    /**
     * 是否正在播放。
     */
    public boolean isPlaying() {
        return currentPlayer != null && currentPlayer.getStatus() == MediaPlayer.Status.PLAYING;
    }

    /**
     * 获取当前播放的音乐名称。
     */
    public String getCurrentMusicName() {
        return currentMusicName;
    }
}
