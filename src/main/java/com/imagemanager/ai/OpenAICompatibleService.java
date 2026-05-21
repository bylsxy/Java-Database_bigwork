package com.imagemanager.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.imagemanager.model.ImageAnalysisResult;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI 兼容 API 实现 — 支持 CPA 代理节点及其他 OpenAI-compatible 服务。
 * <p>
 * 通过标准的 OpenAI Chat Completions API 格式进行图像识别（图生文）
 * 和自然语言转SQL（文生文）。
 */
public class OpenAICompatibleService implements AIService {

    private static final Logger logger = LoggerFactory.getLogger(OpenAICompatibleService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final OkHttpClient httpClient;

    /**
     * 用于图像识别的系统提示词 — 要求AI返回严格JSON格式。
     */
    private static final String IMAGE_ANALYSIS_PROMPT = """
            你是一个专业的图像分析助手。请仔细分析提供的图片，返回以下严格JSON格式的结果。
            注意：只返回JSON，不要包含任何其他文字或markdown标记。
            
            {
              "description": "用一两句话描述图片的主要内容",
              "scene": ["场景标签1", "场景标签2"],
              "objects": ["物体1", "物体2"],
              "people_count": 0,
              "persons": ["如果是名人请写名字，否则写描述如：年轻女性、老年男性"],
              "actions": ["跑步", "微笑"],
              "colors": ["主色调1", "主色调2"],
              "emotions": ["欢乐", "宁静"],
              "text_content": ["图片中出现的文字"],
              "animals": ["动物名称"],
              "food": ["食物名称"],
              "location": ["推测的地点"],
              "celebrities": ["识别出的名人全名"]
            }
            
            要求：
            1. 每个数组至少给出1个标签，没有则给空数组
            2. 名人识别要尽量精确，包含全名
            3. 人数 people_count 必须是数字
            4. 场景、颜色、情绪等要尽量丰富详细
            5. 如果图片中有文字，务必识别出来
            """;

    /**
     * 用于自然语言→SQL的系统提示词。
     */
    private static final String NL_TO_SQL_PROMPT = """
            你是一个SQL生成器。给定以下PostgreSQL数据库schema，根据用户的自然语言查询生成一条SELECT SQL语句。
            
            可用的表和字段：
            - images: id, file_name, file_path, directory_id, file_size, width, height, format, file_hash, ai_processed, created_at, modified_at, is_deleted
            - tags: id, category_id, name
            - image_tags: image_id, tag_id, confidence
            - ai_analysis_results: image_id, description, raw_response, people_count, model_used
            - tag_categories: id, name, display_name (可选值: scene, object, person, celebrity, color, emotion, action, text_content, animal, food, location, count_people)
            - directories: id, dir_name, dir_path
            - v_image_search: (视图，已过滤删除图片) id, file_name, file_path, directory_id, file_size, width, height, format, thumbnail, file_hash, created_at, modified_at, dir_name, directory_path, ai_description, ai_raw_response, people_count, model_used, all_tags
            
            规则：
            1. 只生成 SELECT 语句
            2. 查询结果第一列必须是图片 id，并命名为 id，例如 SELECT id FROM v_image_search ...
            3. 优先使用 v_image_search 视图；如果直接查 images 表，必须添加 images.is_deleted = FALSE
            4. 文件名、路径、目录、格式、分辨率、大小、日期、AI描述、人数、标签都可以参与条件
            5. 文本匹配使用 ILIKE 和通配符，例如 all_tags ILIKE '%天空%'
            6. 分辨率可用 width / height 条件，文件大小用 file_size 字节条件
            7. 用户口语词需要映射到可能的标签同义词，例如“海边/海滩/沙滩”“人物/人像/合影”“汽车/车辆/车”“夜晚/夜景”
            8. 只返回SQL语句，不要包含解释文字或 markdown
            """;

    public OpenAICompatibleService() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public Optional<ImageAnalysisResult> analyzeImage(File imageFile, int imageId) {
        if (!AIConfig.isConfigured()) {
            logger.warn("AI API 未配置，跳过图像分析");
            return Optional.empty();
        }
        if (AIConfig.getModel().isBlank()) {
            logger.warn("AI 模型未选择，跳过图像分析");
            return Optional.empty();
        }

        try {
            // 1. 将图片转为 Base64
            byte[] imageBytes = Files.readAllBytes(imageFile.toPath());
            String base64Image = Base64.getEncoder().encodeToString(imageBytes);
            String mimeType = guessMimeType(imageFile.getName());

            // 2. 构建请求JSON
            String requestBody = buildVisionRequest(base64Image, mimeType);

            // 3. 发送请求（带重试）
            String responseJson = sendRequestWithRetry(requestBody);
            if (responseJson == null) {
                return Optional.empty();
            }

            // 4. 解析响应
            return parseImageAnalysisResponse(responseJson, imageId);

        } catch (IOException e) {
            logger.error("读取图片文件失败: {}", imageFile.getAbsolutePath(), e);
            return Optional.empty();
        } catch (Exception e) {
            logger.error("AI图像分析异常: {}", imageFile.getName(), e);
            return Optional.empty();
        }
    }

    @Override
    public Optional<String> naturalLanguageToSQL(String naturalLanguageQuery) {
        if (!AIConfig.isConfigured()) {
            logger.warn("AI API 未配置，无法进行NL→SQL转换");
            return Optional.empty();
        }
        if (AIConfig.getModel().isBlank()) {
            logger.warn("AI 模型未选择，无法进行NL→SQL转换");
            return Optional.empty();
        }

        try {
            String requestBody = buildTextRequest(NL_TO_SQL_PROMPT, naturalLanguageQuery);
            String responseJson = sendRequestWithRetry(requestBody);
            if (responseJson == null) {
                return Optional.empty();
            }

            // 从响应中提取SQL
            String content = extractContentFromResponse(responseJson);
            if (content != null) {
                // 清理可能的 markdown 标记
                String sql = content.replaceAll("```sql\\s*", "")
                        .replaceAll("```\\s*", "")
                        .trim();
                logger.info("NL→SQL 转换结果: {} → {}", naturalLanguageQuery, sql);
                return Optional.of(sql);
            }
        } catch (Exception e) {
            logger.error("NL→SQL转换异常: {}", naturalLanguageQuery, e);
        }
        return Optional.empty();
    }

    @Override
    public String testConnection(File testImageFile) {
        if (!AIConfig.isConfigured()) {
            return "失败：API Key 未配置";
        }
        if (AIConfig.getModel().isBlank()) {
            return "失败：请先从模型下拉列表中选择一个模型";
        }

        try {
            long startTime = System.currentTimeMillis();
            Optional<ImageAnalysisResult> result = analyzeImage(testImageFile, -1);
            long elapsed = System.currentTimeMillis() - startTime;

            if (result.isPresent()) {
                ImageAnalysisResult r = result.get();
                var tags = r.tagsByCategory();
                int totalTags = tags == null ? 0 : tags.values().stream().mapToInt(List::size).sum();

                StringBuilder sb = new StringBuilder();
                sb.append("成功：连接正常\n");
                sb.append("  模型: ").append(AIConfig.getModel()).append("\n");
                sb.append("  耗时: ").append(elapsed).append("ms\n");
                sb.append("  描述: ").append(r.description()).append("\n");
                sb.append("  人数: ").append(r.peopleCount()).append("\n");
                sb.append("  标签总数: ").append(totalTags).append("\n");

                if (tags != null) {
                    tags.forEach((category, tagList) -> {
                        if (!tagList.isEmpty()) {
                            sb.append("  [").append(category).append("]: ")
                                    .append(String.join(", ", tagList)).append("\n");
                        }
                    });
                }
                return sb.toString();
            } else {
                return "失败：模型未返回有效结果，请检查API配置";
            }
        } catch (Exception e) {
            return "失败：" + e.getMessage();
        }
    }

    // ==================== 内部方法 ====================

    /**
     * 构建图像识别请求（Vision API格式）。
     */
    private String buildVisionRequest(String base64Image, String mimeType) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", AIConfig.getModel());
        root.put("max_tokens", 2000);

        ArrayNode messages = root.putArray("messages");

        // System message
        ObjectNode sysMsg = messages.addObject();
        sysMsg.put("role", "system");
        sysMsg.put("content", IMAGE_ANALYSIS_PROMPT);

        // User message with image
        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        ArrayNode content = userMsg.putArray("content");

        ObjectNode imageContent = content.addObject();
        imageContent.put("type", "image_url");
        ObjectNode imageUrl = imageContent.putObject("image_url");
        imageUrl.put("url", "data:" + mimeType + ";base64," + base64Image);

        ObjectNode textContent = content.addObject();
        textContent.put("type", "text");
        textContent.put("text", "请分析这张图片。");

        return objectMapper.writeValueAsString(root);
    }

    /**
     * 构建纯文本请求（文生文）。
     */
    private String buildTextRequest(String systemPrompt, String userMessage) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", AIConfig.getModel());
        root.put("max_tokens", 1000);

        ArrayNode messages = root.putArray("messages");

        ObjectNode sysMsg = messages.addObject();
        sysMsg.put("role", "system");
        sysMsg.put("content", systemPrompt);

        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", userMessage);

        return objectMapper.writeValueAsString(root);
    }

    /**
     * 发送HTTP请求并带指数退避重试。
     */
    private String sendRequestWithRetry(String requestBody) {
        int maxRetries = AIConfig.getMaxRetries();
        String baseUrl = AIConfig.getBaseUrl();
        String apiKey = AIConfig.getApiKey();

        // 确保 URL 以 /chat/completions 结尾
        String url = baseUrl.endsWith("/")
                ? baseUrl + "chat/completions"
                : baseUrl + "/chat/completions";

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                Request request = new Request.Builder()
                        .url(url)
                        .addHeader("Authorization", "Bearer " + apiKey)
                        .addHeader("Content-Type", "application/json")
                        .post(RequestBody.create(requestBody, MediaType.parse("application/json")))
                        .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        return response.body().string();
                    } else {
                        String errorBody = response.body() != null ? response.body().string() : "无响应体";
                        logger.warn("AI API 响应异常 (尝试 {}/{}): HTTP {}, body={}",
                                attempt, maxRetries, response.code(), errorBody);

                        // 429 限流：增加等待时间
                        if (response.code() == 429) {
                            long waitMs = AIConfig.getRequestDelay() * attempt * 2;
                            logger.info("遭遇限流，等待 {}ms 后重试...", waitMs);
                            Thread.sleep(waitMs);
                            continue;
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (Exception e) {
                logger.error("AI API 请求失败 (尝试 {}/{}): {}", attempt, maxRetries, e.getMessage());
            }

            // 指数退避
            if (attempt < maxRetries) {
                try {
                    long waitMs = AIConfig.getRequestDelay() * attempt;
                    Thread.sleep(waitMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }

        logger.error("AI API 请求在 {} 次重试后仍然失败", maxRetries);
        return null;
    }

    /**
     * 解析图像分析响应JSON，提取结构化标签。
     */
    private Optional<ImageAnalysisResult> parseImageAnalysisResponse(String responseJson, int imageId) {
        try {
            String content = extractContentFromResponse(responseJson);
            if (content == null) {
                logger.warn("AI响应中未找到content字段");
                return Optional.empty();
            }

            // 清理可能的 markdown 标记
            content = content.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();

            // 解析AI返回的结构化JSON
            JsonNode analysisNode = objectMapper.readTree(content);

            String description = getTextOrDefault(analysisNode, "description", "无描述");
            int peopleCount = analysisNode.has("people_count") ? analysisNode.get("people_count").asInt(0) : 0;

            // 按分类提取标签
            Map<String, List<String>> tagsByCategory = new LinkedHashMap<>();
            tagsByCategory.put("scene", extractStringArray(analysisNode, "scene"));
            tagsByCategory.put("object", extractStringArray(analysisNode, "objects"));
            tagsByCategory.put("person", extractStringArray(analysisNode, "persons"));
            tagsByCategory.put("celebrity", extractStringArray(analysisNode, "celebrities"));
            tagsByCategory.put("action", extractStringArray(analysisNode, "actions"));
            tagsByCategory.put("color", extractStringArray(analysisNode, "colors"));
            tagsByCategory.put("emotion", extractStringArray(analysisNode, "emotions"));
            tagsByCategory.put("text_content", extractStringArray(analysisNode, "text_content"));
            tagsByCategory.put("animal", extractStringArray(analysisNode, "animals"));
            tagsByCategory.put("food", extractStringArray(analysisNode, "food"));
            tagsByCategory.put("location", extractStringArray(analysisNode, "location"));

            // 人数作为特殊标签
            if (peopleCount > 0) {
                tagsByCategory.put("count_people", List.of(String.valueOf(peopleCount)));
            }

            return Optional.of(new ImageAnalysisResult(
                    imageId, content, description, peopleCount,
                    AIConfig.getModel(), tagsByCategory
            ));

        } catch (Exception e) {
            logger.error("解析AI图像分析响应失败", e);
            return Optional.empty();
        }
    }

    /**
     * 从 OpenAI 格式的响应中提取 content 文本。
     */
    private String extractContentFromResponse(String responseJson) {
        try {
            JsonNode root = objectMapper.readTree(responseJson);
            JsonNode choices = root.get("choices");
            if (choices != null && choices.isArray() && !choices.isEmpty()) {
                JsonNode message = choices.get(0).get("message");
                if (message != null && message.has("content")) {
                    return message.get("content").asText();
                }
            }
        } catch (Exception e) {
            logger.error("解析API响应JSON失败", e);
        }
        return null;
    }

    private String getTextOrDefault(JsonNode node, String field, String defaultVal) {
        return node.has(field) ? node.get(field).asText(defaultVal) : defaultVal;
    }

    private List<String> extractStringArray(JsonNode node, String field) {
        List<String> result = new ArrayList<>();
        if (node.has(field) && node.get(field).isArray()) {
            for (JsonNode item : node.get(field)) {
                String text = item.asText("").trim();
                if (!text.isEmpty()) {
                    result.add(text);
                }
            }
        }
        return result;
    }

    private String guessMimeType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".bmp")) return "image/bmp";
        if (lower.endsWith(".webp")) return "image/webp";
        return "image/jpeg"; // 默认JPEG
    }
}
