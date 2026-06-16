package com.imagemanager.ai;

import com.imagemanager.model.ImageAnalysisResult;

import java.io.File;
import java.util.Optional;

/**
 * AI 服务接口 — 定义图像识别和文生文的能力。
 */
public interface AIService {

    /**
     * 对一张图片进行 AI 图像识别分析。
     * <p>
     * 返回结构化的分析结果，包含场景、物体、人物、名人、颜色、情绪等多维标签。
     *
     * @param imageFile 本地图片文件
     * @param imageId   数据库中的图片ID
     * @return 分析结果，如果失败返回 Optional.empty()
     */
    Optional<ImageAnalysisResult> analyzeImage(File imageFile, int imageId);

    /**
     * 将自然语言查询转换为 SQL 语句。
     * <p>
     * 调用文生文模型，将用户输入（如"穿红衣服跑步的美女"）转换为可执行的 SELECT SQL。
     *
     * @param naturalLanguageQuery 用户的自然语言查询
     * @return 生成的SQL语句，如果失败返回 Optional.empty()
     */
    Optional<String> naturalLanguageToSQL(String naturalLanguageQuery);

    /**
     * 测试 API 连接是否有效。
     * <p>
     * 用于设置页面的实时验证功能：上传一张示例图片，检查模型是否能正确返回标签。
     *
     * @param testImageFile 测试用的图片文件
     * @return 测试结果描述（成功时包含标签数量和示例，失败时包含错误信息）
     */
    String testConnection(File testImageFile);
}
