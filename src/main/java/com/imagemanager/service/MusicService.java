package com.imagemanager.service;

import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 音乐播放服务 — 管理幻灯片背景音乐的播放、暂停、切换。
 * <p>
 * 支持内置固定目录下的三首音乐（轻松钢琴、自然之声、柔和吉他），
 * 以及用户自定义选择的本地音乐文件。
 * 所有音乐均为单曲循环模式。
 */
public class MusicService {

    private static final Logger logger = LoggerFactory.getLogger(MusicService.class);

    /** 固定音乐文件夹路径（用户主目录下的 DIMS_Music） */
    public static final String MUSIC_DIR = System.getProperty("user.home") + "/DIMS_Music";

    /** 内置音乐名称与文件名的映射 */
    private static final Map<String, String> BUILTIN_MUSIC_MAP = Map.of(
            "轻松钢琴", "轻松钢琴.mp3",
            "自然之声", "自然之声.mp3",
            "柔和吉他", "柔和吉他.mp3"
    );

    static {
        // 确保音乐目录存在
        File musicDir = new File(MUSIC_DIR);
        if (!musicDir.exists()) {
            boolean created = musicDir.mkdirs();
            if (created) {
                logger.info("创建音乐目录成功: {}", MUSIC_DIR);
                logger.info("请将以下三个音乐文件放入该目录以便使用内置音乐：");
                BUILTIN_MUSIC_MAP.forEach((name, fileName) ->
                        logger.info("  - {} -> {}", name, fileName));
            } else {
                logger.warn("无法创建音乐目录: {}", MUSIC_DIR);
            }
        } else {
            logger.debug("音乐目录已存在: {}", MUSIC_DIR);
        }
    }

    private MediaPlayer currentPlayer;
    private String currentMusicIdentifier; // 内置名称或自定义文件路径
    private double volume = 0.5;
    private boolean playRequested = false;
    private boolean paused = false;

    /**
     * 获取内置音乐名称列表（不包含自定义选项）
     */
    public List<String> getBuiltinMusicNames() {
        return new ArrayList<>(BUILTIN_MUSIC_MAP.keySet());
    }

    /**
     * 播放音乐（支持内置名称或自定义文件路径）
     *
     * @param musicNameOrPath 内置音乐名称 或 自定义音乐文件的绝对路径
     */
    public boolean play(String musicNameOrPath) {
        // 停止当前播放，确保不会多个声音重叠
        stop();

        String filePath;
        if (BUILTIN_MUSIC_MAP.containsKey(musicNameOrPath)) {
            String fileName = BUILTIN_MUSIC_MAP.get(musicNameOrPath);
            filePath = MUSIC_DIR + File.separator + fileName;
        } else {
            // 直接作为自定义文件路径处理
            filePath = musicNameOrPath;
        }

        File musicFile = new File(filePath);
        if (!musicFile.exists()) {
            logger.warn("音乐文件不存在: {}", filePath);
            if (BUILTIN_MUSIC_MAP.containsKey(musicNameOrPath)) {
                logger.warn("请将 {} 放入目录: {}", BUILTIN_MUSIC_MAP.get(musicNameOrPath), MUSIC_DIR);
            }
            return false;
        }

        try {
            Media media = new Media(musicFile.toURI().toString());
            MediaPlayer player = new MediaPlayer(media);
            currentPlayer = player;
            player.setCycleCount(MediaPlayer.INDEFINITE); // 单曲循环
            player.setVolume(volume);
            player.setOnReady(() -> {
                if (playRequested && !paused && currentPlayer == player) {
                    player.play();
                }
            });
            player.setOnPlaying(() -> {
                playRequested = true;
                paused = false;
                logger.debug("音乐进入播放状态: {}", currentMusicIdentifier);
            });
            player.setOnPaused(() -> {
                paused = true;
                logger.debug("音乐进入暂停状态: {}", currentMusicIdentifier);
            });
            player.setOnStopped(() -> {
                playRequested = false;
                paused = false;
                logger.debug("音乐进入停止状态: {}", currentMusicIdentifier);
            });
            player.setOnError(() -> logger.error("音乐播放器错误: {}",
                    player.getError() != null ? player.getError().getMessage() : "未知错误"));
            playRequested = true;
            paused = false;
            player.play();
            currentMusicIdentifier = musicNameOrPath;
            logger.info("开始播放音乐: {}", musicFile.getAbsolutePath());
            return true;
        } catch (Exception e) {
            logger.error("播放音乐失败: {}", musicNameOrPath, e);
            currentPlayer = null;
            currentMusicIdentifier = null;
            playRequested = false;
            paused = false;
            return false;
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
            currentMusicIdentifier = null;
            playRequested = false;
            paused = false;
            logger.debug("音乐已停止");
        }
    }

    /**
     * 暂停/恢复播放。
     */
    public void pause() {
        if (currentPlayer == null) {
            return;
        }
        paused = true;
        playRequested = false;
        currentPlayer.pause();
        logger.info("暂停音乐: {}", currentMusicIdentifier);
    }

    /**
     * 恢复播放。
     */
    public void resume() {
        if (currentPlayer == null) {
            return;
        }
        paused = false;
        playRequested = true;
        currentPlayer.play();
        logger.info("继续播放音乐: {}", currentMusicIdentifier);
    }

    /**
     * 暂停/恢复播放。
     */
    public boolean togglePause() {
        if (isPlaying()) {
            pause();
        } else {
            resume();
        }
        return isPlaying();
    }

    /**
     * 是否已经加载了音乐文件。
     */
    public boolean hasLoadedMusic() {
        return currentPlayer != null;
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
        return currentPlayer != null && playRequested && !paused;
    }

    /**
     * 是否已暂停。
     */
    public boolean isPaused() {
        return currentPlayer != null && paused;
    }

    /**
     * 当前播放器状态，便于界面同步按钮。
     */
    public MediaPlayer.Status getStatus() {
        return currentPlayer != null ? currentPlayer.getStatus() : null;
    }

    /**
     * 获取当前播放的音乐标识（内置名称或自定义文件路径）。
     */
    public String getCurrentMusicIdentifier() {
        return currentMusicIdentifier;
    }
}
