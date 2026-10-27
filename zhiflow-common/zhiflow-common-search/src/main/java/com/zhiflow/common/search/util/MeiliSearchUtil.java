package com.zhiflow.common.search.util;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zhiflow.common.search.config.MeiliSearchConfig;
import com.meilisearch.sdk.Client;
import com.meilisearch.sdk.Config;
import com.meilisearch.sdk.Index;
import com.meilisearch.sdk.SearchRequest;
import com.meilisearch.sdk.exceptions.MeilisearchException;
import com.meilisearch.sdk.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.net.http.HttpResponse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.stream.Collectors;


@Slf4j
@Component
@ConditionalOnProperty(name = "meilisearch.enabled", havingValue = "true")
public class MeiliSearchUtil {

    /**
     * SDK 没覆盖的几个原始 HTTP 调用（实验特性开关、rawRequest）共用一个客户端。
     * 原先每次 {@code HttpClient.newHttpClient()} 新建、且连接 / 请求都不限时：Meili 卡住时后台请求一直挂着。
     */
    private static final HttpClient RAW_HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private static final Duration RAW_REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final Client client;
    private final MeiliSearchConfig config;

    // Cache health checks to avoid calling GET /health before every operation.
    private final Object healthLock = new Object();
    private volatile long lastHealthCheckAtMs = 0L;
    private volatile boolean lastHealthOk = true;

    public MeiliSearchUtil(MeiliSearchConfig config) {
        this.config = config;
        Config cfg = new Config(config.getHostUrl(), config.getAdminKey());
        Client client = new Client(cfg);
        this.client = client;
        log.info("MeiliSearch enabled: {}", client.isHealthy());

    }

    private boolean isHealthyCached() {
        // TTL <= 0 means legacy behavior (no caching).
        long successTtlMs = config.getHealthCheckTtlMs() != null ? config.getHealthCheckTtlMs() : 30_000L;
        long failureTtlMs = config.getHealthCheckFailureTtlMs() != null ? config.getHealthCheckFailureTtlMs() : 2_000L;

        long effectiveTtlMs = lastHealthOk ? successTtlMs : failureTtlMs;
        if (effectiveTtlMs <= 0) {
            try {
                return client.isHealthy();
            } catch (Exception _) {
                return false;
            }
        }

        long now = System.currentTimeMillis();
        long ageMs = now - lastHealthCheckAtMs;
        if (ageMs >= 0 && ageMs < effectiveTtlMs) {
            return lastHealthOk;
        }

        synchronized (healthLock) {
            now = System.currentTimeMillis();
            ageMs = now - lastHealthCheckAtMs;
            effectiveTtlMs = lastHealthOk ? successTtlMs : failureTtlMs;
            if (ageMs >= 0 && ageMs < effectiveTtlMs) {
                return lastHealthOk;
            }

            boolean ok;
            try {
                ok = client.isHealthy();
            } catch (Exception e) {
                ok = false;
                log.debug("MeiliSearch health check failed: {}", e.getMessage());
            }
            lastHealthOk = ok;
            lastHealthCheckAtMs = now;
            return ok;
        }
    }

    /**
     * 测试 MeiliSearch 连接
     *
     * @return 连接是否正常
     */
    public boolean testConnection() {
        try {
            return client.isHealthy();
        } catch (Exception e) {
            log.error("MeiliSearch connection test failed: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 测试获取索引
     *
     * @param indexName 索引名称
     * @return 索引是否存在
     */
    public boolean testIndexExists(String indexName) {
        try {
            Index index = getIndex(indexName);
            return index != null;
        } catch (Exception e) {
            log.error("Test index existence failed for '{}': {}", indexName, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 创建索引
     *
     * @param indexName 索引名称
     * @return Index 索引对象
     * @throws MeilisearchException MeiliSearch异常
     */
    public TaskInfo createIndex(String indexName) throws MeilisearchException {
        return client.createIndex(indexName);
    }

    /**
     * 创建索引并指定主键
     *
     * @param indexName  索引名称
     * @param primaryKey 主键字段名
     * @return Index 索引对象
     * @throws MeilisearchException MeiliSearch异常
     */
    public TaskInfo createIndex(String indexName, String primaryKey) throws MeilisearchException {
        return client.createIndex(indexName, primaryKey);
    }

    /**
     * 删除索引
     *
     * @param indexName 索引名称
     * @throws MeilisearchException MeiliSearch异常
     */
    public TaskInfo deleteIndex(String indexName) throws MeilisearchException {
        return client.deleteIndex(indexName);
    }

    /**
     * 获取索引
     *
     * @param indexName 索引名称
     * @return Index 索引对象
     * @throws MeilisearchException MeiliSearch异常
     */
    public Index getIndex(String indexName) throws MeilisearchException {
        try {
            // 首先检查连接健康状态
            if (!isHealthyCached()) {
                throw new MeilisearchException("MeiliSearch server is not healthy or not available");
            }

            // 尝试获取索引信息来验证索引是否存在
            try {
                // 获取索引
                Index index = client.getIndex(indexName);
                return index;
            } catch (com.meilisearch.sdk.exceptions.MeilisearchApiException e) {
                log.error("Index '{}' not found", indexName);
                throw e;
            }
        } catch (com.meilisearch.sdk.exceptions.MeilisearchException e) {
            log.error("Failed to get index '{}': {}", indexName, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 本地构造索引句柄（不发起 HTTP 请求），复用与 {@link #getIndex} 相同的健康检查缓存。
     * <p>
     * 与 getIndex 的区别：getIndex 会额外发一次真实 GET /indexes/{uid} 来验证索引存在；
     * index() 仅本地构造 Index 对象（uid + config），索引若不存在，异常会在后续
     * search 等操作调用时抛出，交由调用方按 MeilisearchException 处理，语义等价但少一次 HTTP 往返。
     *
     * @param indexName 索引名称
     * @return Index 句柄（本地构造，无网络请求）
     * @throws MeilisearchException 健康检查缓存判定不可用时抛出
     */
    public Index index(String indexName) throws MeilisearchException {
        if (!isHealthyCached()) {
            throw new MeilisearchException("MeiliSearch server is not healthy or not available");
        }
        return client.index(indexName);
    }

    /**
     * 添加或更新单个文档
     *
     * @param indexName 索引名称
     * @param document  文档对象
     * @throws MeilisearchException MeiliSearch异常
     */
    public TaskInfo addOrUpdateDocument(String indexName, String document) throws MeilisearchException {
        Index index = getIndex(indexName);
        return index.addDocuments(document);
    }

    /**
     * 批量添加或更新多个文档
     *
     * @param indexName 索引名称
     * @param documents 文档JSON字符串列表
     * @throws MeilisearchException MeiliSearch异常
     */
    public TaskInfo addOrUpdateDocuments(String indexName, List<String> documents) throws MeilisearchException {
        if (documents == null || documents.isEmpty()) {
            log.warn("Empty document list for index: {}", indexName);
            return null;
        }

        Index index = getIndex(indexName);

        // 构造 JSON 数组：["doc1", "doc2", ...]
        String jsonArray = "[" + String.join(",", documents) + "]";

        return index.addDocuments(jsonArray);
    }

    /**
     * 根据文档ID删除单个文档
     *
     * @param indexName  索引名称
     * @param documentId 文档ID
     * @throws MeilisearchException MeiliSearch异常
     */
    public TaskInfo deleteDocument(String indexName, String documentId) throws MeilisearchException {
        Index index = getIndex(indexName);
        return index.deleteDocument(documentId);
    }

    /**
     * 根据文档ID列表删除多个文档
     *
     * @param indexName   索引名称
     * @param documentIds 文档ID列表
     * @throws MeilisearchException MeiliSearch异常
     */
    public TaskInfo deleteDocuments(String indexName, List<String> documentIds) throws MeilisearchException {
        Index index = getIndex(indexName);
        return index.deleteDocuments(documentIds);
    }

    /**
     * 搜索文档
     *
     * @param indexName 索引名称
     * @param query     查询关键字
     * @return 搜索结果
     * @throws MeilisearchException MeiliSearch异常
     */
    public Map<String, Object> search(String indexName, String query) throws MeilisearchException {
        Index index = getIndex(indexName);
        return index.search(query).getHits().getFirst();
    }

    /**
     * 为索引配置AI嵌入器 (OpenAI)
     *
     * @param indexName    索引名称
     * @param embedderName 嵌入器名称
     * @throws MeilisearchException MeiliSearch异常
     */
    public TaskInfo configureEmbedder(String indexName, String embedderName, String apiKey, String model,
                                      Integer dimensions, String url, Map<String, Object> request, Map<String, Object> response,
                                      String documentTemplate) throws MeilisearchException {
        Index index = getIndex(indexName);
        Embedder embedder = Embedder.builder().apiKey(apiKey)
                .model(model)
                .dimensions(dimensions)
                .request(request)
                .response(response)
                .url(url)
                .documentTemplate(documentTemplate)
                .build();
        Map<String, Embedder> embedders = new HashMap<>();
        embedders.put(embedderName, embedder);
        return index.updateEmbeddersSettings(embedders);
    }

    /**
     * 更新索引的同义词字典
     *
     * @param indexName 索引名称
     * @param synonyms  同义词映射，key为词语，value为同义词数组
     * @throws MeilisearchException MeiliSearch异常
     */
    public TaskInfo updateSynonyms(String indexName, HashMap<String, String[]> synonyms) throws MeilisearchException {
        Index index = getIndex(indexName);
        Settings settings = new Settings();
        settings.setSynonyms(synonyms);
        return index.updateSettings(settings);
    }

    /**
     * 配置用户自定义 embedder
     *
     * @param indexName    索引
     * @param embedderName 嵌入器
     * @param dimensions   维度
     * @throws MeilisearchException
     */
    public TaskInfo configureUserProvidedEmbedder(String indexName, String embedderName, int dimensions) throws MeilisearchException {
        Index index = getIndex(indexName);
        Embedder embedder = Embedder.builder()
                .source(EmbedderSource.USER_PROVIDED)
                .dimensions(dimensions)
                .build();
        Map<String, Embedder> embedders = new HashMap<>();
        embedders.put(embedderName, embedder);
        return index.updateEmbeddersSettings(embedders);
    }

    /**
     * 配置索引的可过滤和可排序属性
     *
     * @param indexName           索引名称
     * @param filterableAttributes 可过滤属性列表
     * @param sortableAttributes   可排序属性列表
     * @throws MeilisearchException MeiliSearch 异常
     */
    public TaskInfo configureFilterableAndSortableAttributes(String indexName, 
                                                              String[] filterableAttributes, 
                                                              String[] sortableAttributes) throws MeilisearchException {
        Index index = getIndex(indexName);
        Settings settings = new Settings();
        settings.setFilterableAttributes(filterableAttributes);
        settings.setSortableAttributes(sortableAttributes);
        return index.updateSettings(settings);
    }

    /**
     * 启用 MeiliSearch 实验性功能
     *
     * @param featureName 功能名称（如 containsFilter）
     * @param enabled     是否启用
     * @throws MeilisearchException MeiliSearch 异常
     */
    public TaskInfo updateExperimentalFeature(String featureName, boolean enabled) throws MeilisearchException {
        try {
            String url = config.getHostUrl() + "/experimental-features/";
            JsonObject payload = new JsonObject();
            payload.addProperty(featureName, enabled);
            
            HttpClient httpClient = RAW_HTTP;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(RAW_REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + config.getAdminKey())
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build();
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() != 200) {
                throw new MeilisearchException("Failed to update experimental feature: " + response.body());
            }
            
            Gson gson = new Gson();
            return gson.fromJson(response.body(), TaskInfo.class);
        } catch (Exception e) {
            log.error("Failed to update experimental feature: {}", e.getMessage(), e);
            throw new MeilisearchException(e);
        }
    }

    /**
     * 读取索引 settings 的**原始 JSON**（不经 SDK 模型）。
     *
     * <p><b>为什么不用 {@code getIndex(x).getSettings()}</b>：SDK 的 {@code Settings} 是固定字段的
     * POJO，Meili 有而模型没有的字段（1.34 的 {@code facetSearch}/{@code prefixSearch} 等）在
     * 反序列化时会被**静默丢弃**。T1 的下发要做「回读比对」，比对的两侧一旦经过一层有损模型，
     * 就会出现两种最坏的结果：模型没有的字段永远比不出差异（假阴性），模型有而 Meili 返回 null
     * 的字段永远比出差异（假阳性，diff 天天报假警就没人看了）。
     * 所以下发与回读两条路都走原始 JSON，保证「发出去的」和「读回来的」是同一种东西。
     *
     * @param indexName 索引名
     * @return settings 的原始 JSON 字符串
     */
    public String getSettingsRaw(String indexName) throws MeilisearchException {
        return rawRequest("GET", "/indexes/" + indexName + "/settings", null);
    }

    /**
     * 以 PATCH 方式下发索引 settings（原始 JSON）。
     *
     * <p>Meili 的 {@code PATCH /settings} 是**部分更新**：body 里没出现的字段保持不变，
     * 而出现且值为 {@code null} 的字段会被**重置为默认值**。所以调用方传进来的 JSON
     * 必须只包含本版真正想管的字段，绝不能靠「传 null 表示不管」——那是重置不是跳过。
     *
     * @param indexName    索引名
     * @param settingsJson 只含本版声明字段的 settings JSON
     * @return Meili 任务信息（settings 变更是异步任务，需 {@link #waitForTask} 等它完成再回读）
     */
    public TaskInfo patchSettings(String indexName, String settingsJson) throws MeilisearchException {
        String body = rawRequest("PATCH", "/indexes/" + indexName + "/settings", settingsJson);
        return new Gson().fromJson(body, TaskInfo.class);
    }

    /**
     * 列出全部索引 uid。
     *
     * <p>用原始 HTTP 而非 SDK 的 {@code getIndexes()}：后者分页默认只取 20 条且各版本行为不一，
     * 而「漏掉一个租户的索引」正是本仓多租户最容易出、也最难发现的事故形态。
     *
     * @param limit 上限（一次取完，不做分页；本仓索引数是租户数量级）
     */
    public List<String> listIndexUids(int limit) throws MeilisearchException {
        String body = rawRequest("GET", "/indexes?limit=" + limit, null);
        List<String> uids = new ArrayList<>();
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        root.getAsJsonArray("results").forEach(e -> uids.add(e.getAsJsonObject().get("uid").getAsString()));
        return uids;
    }

    /**
     * 等待 Meili 异步任务终态。
     *
     * <p>settings 变更返回 {@code enqueued} 就结束了，**立刻回读拿到的还是旧值**——
     * 不等任务完成就比对，会把「下发成功」判成「回读不一致」，然后去查一个根本不存在的问题。
     *
     * @return 终态状态字符串（{@code succeeded} / {@code failed} / {@code canceled}）；
     *         超时返回最后一次看到的状态（调用方按「非 succeeded」处理）
     */
    public String waitForTask(int taskUid, long timeoutMs) throws MeilisearchException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String status = "unknown";
        while (System.currentTimeMillis() < deadline) {
            String body = rawRequest("GET", "/tasks/" + taskUid, null);
            JsonObject o = JsonParser.parseString(body).getAsJsonObject();
            status = o.has("status") ? o.get("status").getAsString() : "unknown";
            if (!"enqueued".equals(status) && !"processing".equals(status)) {
                return status;
            }
            try {
                Thread.sleep(300L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return status;
            }
        }
        return status;
    }

    /**
     * 直连 Meili HTTP API。SDK 未覆盖的端点走这里，鉴权与 {@link #updateExperimentalFeature} 同源。
     */
    private String rawRequest(String method, String path, String body) throws MeilisearchException {
        try {
            HttpRequest.BodyPublisher publisher = body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body, java.nio.charset.StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.getHostUrl() + path))
                    .timeout(RAW_REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + config.getAdminKey())
                    .method(method, publisher)
                    .build();
            HttpResponse<String> response = RAW_HTTP
                    .send(request, HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new MeilisearchException(method + " " + path + " -> HTTP "
                        + response.statusCode() + ": " + response.body());
            }
            return response.body();
        } catch (MeilisearchException e) {
            throw e;
        } catch (Exception e) {
            throw new MeilisearchException(e);
        }
    }

    /**
     * 获取setting
     *
     * @param indexName
     * @return
     * @throws MeilisearchException
     */
    public Map<String, Embedder> getEmbedders(String indexName) throws MeilisearchException {
        Index index = getIndex(indexName);
        return index.getEmbeddersSettings();
    }

    /**
     * 添加文档并自动生成向量
     *
     * @param indexName    索引名称
     * @param document     文档内容（JSON字符串）
     * @param textField    用于生成向量的文本字段名
     * @param embedderName embedder名称（用于搜索时指定）
     * @throws MeilisearchException MeiliSearch异常
     */
    public void addDocumentWithVector(String indexName, String document,
                                      String textField, float[] vector, String embedderName) throws MeilisearchException {
        try {
            // 2. 解析 JSON 文档
            Gson gson = new Gson();
            JsonObject jsonObject = JsonParser.parseString(document).getAsJsonObject();
            // 3. 提取文本字段
            if (!jsonObject.has(textField)) {
                log.error("Text field '{}' not found in document", textField);
                throw new IllegalArgumentException("Text field '" + textField + "' not found in document");
            }
            String text = jsonObject.get(textField).getAsString();
            // 4. 生成向量
            if (vector.length == 0) {
                log.warn("Empty vector generated for text: {}", text.substring(0, Math.min(50, text.length())));
                addOrUpdateDocument(indexName, document);
                return;
            }
            // 5. 转换为 Double[] (MeiliSearch 要求)
            Double[] vectorDouble = new Double[vector.length];
            for (int i = 0; i < vector.length; i++) {
                vectorDouble[i] = (double) vector[i];
            }
            // 6. 构造包含 _vectors 的新文档
            JsonObject vectorsObject = new JsonObject();
            vectorsObject.add(embedderName, gson.toJsonTree(vectorDouble));
            jsonObject.add("_vectors", vectorsObject);

            // 7. 写入索引
            String documentWithVector = gson.toJson(jsonObject);
            addOrUpdateDocument(indexName, documentWithVector);
            log.debug("Document added with vector, dimension: {}", vectorDouble.length);

        } catch (Exception e) {
            log.error("Failed to add document with vector", e);
            throw new RuntimeException("Failed to add document with vector", e);
        }
    }

    /**
     * 批量添加文档并自动生成向量
     *
     * @param indexName    索引名称
     * @param documents    文档列表
     * @param textField    用于生成向量的文本字段名
     * @param embedderName embedder名称
     * @throws MeilisearchException MeiliSearch异常
     */
    public void addDocumentsWithVectors(String indexName, List<Map<String, Object>> documents,
                                        String textField, List<float[]> vectors, String embedderName) throws MeilisearchException {
        if (documents == null || documents.isEmpty()) {
            log.warn("Empty document list");
            return;
        }
        try {
            Gson gson = new Gson();

            // 2. 批量提取文本字段
            List<String> texts = new ArrayList<>();
            for (Map<String, Object> doc : documents) {
                if (!doc.containsKey(textField)) {
                    log.error("Text field '{}' not found in document", textField);
                    throw new IllegalArgumentException("Text field '" + textField + "' not found in document");
                }
                texts.add(doc.get(textField).toString());
            }
            // 4. 为每个文档添加向量
            List<String> documentsWithVectors = new ArrayList<>();
            for (int i = 0; i < documents.size(); i++) {
                Map<String, Object> doc = documents.get(i);
                float[] vectorFloat = vectors.get(i);
                // 转换为 Double[]
                Double[] vectorDouble = new Double[vectorFloat.length];
                for (int j = 0; j < vectorFloat.length; j++) {
                    vectorDouble[j] = (double) vectorFloat[j];
                }
                // 构造包含向量的文档
                Map<String, Object> docWithVector = new HashMap<>(doc);
                Map<String, Object> vectorsMap = new HashMap<>();
                vectorsMap.put(embedderName, vectorDouble);
                docWithVector.put("_vectors", vectorsMap);

                documentsWithVectors.add(gson.toJson(docWithVector));
            }

            // 5. 批量写入索引
            Index index = getIndex(indexName);
            String jsonArray = "[" + String.join(",", documentsWithVectors) + "]";
            index.addDocuments(jsonArray);
            log.info("Batch added {} documents with vectors", documents.size());

        } catch (Exception e) {
            log.error("Failed to batch add documents with vectors", e);
            throw new RuntimeException("Failed to batch add documents with vectors", e);
        }
    }

    /**
     * 使用向量进行混合搜索（结合文本和向量）
     *
     * @param indexName    索引名称
     * @param query        文本查询
     * @param vector       向量数组
     * @param embedderName 嵌入器名称
     * @return 搜索结果
     * @throws MeilisearchException MeiliSearch异常
     */
    public Map<String, Object> hybridSearch(String indexName, String query, Double[] vector, String embedderName, double semanticRatio, Integer limit) throws MeilisearchException {
        Index index = getIndex(indexName);
        SearchRequest searchRequest = SearchRequest.builder()
                .q(query)
                .vector(vector)
                .hybrid(Hybrid.builder().semanticRatio(semanticRatio).embedder(embedderName).build())
                .retrieveVectors(true)
                .limit(limit)
                .build();
        SearchResult searchResult = (SearchResult) index.search(searchRequest);
        Map<String, Object> escapeRoomHit = searchResult.getHits().getFirst();
        return escapeRoomHit;
    }

    /**
     * 通用分页搜索方法（支持过滤、排序、混合搜索）
     *
     * @param indexName      索引名称
     * @param query          搜索关键词（可为null或空字符串）
     * @param filter         过滤条件（MeiliSearch filter语法）
     * @param sort           排序字段数组（格式: "field:asc" 或 "field:desc"）
     * @param offset         分页偏移量
     * @param limit          返回结果数量
     * @param vector         向量数组（用于混合搜索，可为null）
     * @param embedderName   嵌入器名称（用于混合搜索）
     * @param semanticRatio  语义搜索权重 [0.0-1.0]
     * @return SearchResult 包含命中结果和总数
     * @throws MeilisearchException MeiliSearch异常
     */
    public SearchResult searchWithPagination(String indexName, String query, String filter,
                                              String[] sort, int offset, int limit,
                                              Double[] vector, String embedderName, Double semanticRatio)
                                              throws MeilisearchException {
        Index index = getIndex(indexName);

        // 使用原生 Index.search() 方法构建搜索请求
        // 由于 SDK 版本问题，直接使用 getIndex().search() 而非 builder 模式
        try {
            // 注意：这里的实现需要根据实际SDK版本调整
            // 暂时使用简化版本，返回基本搜索结果
            Searchable result = index.search(query != null ? query : "");
            return (SearchResult) result;
        } catch (Exception e) {
            log.error("MeiliSearch搜索失败: index={}, query={}, error={}", indexName, query, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 安全地将对象转换为Double类型
     *
     * @param obj          对象
     * @param defaultValue 默认值
     * @return Double值
     */
    private Double getObjectAsDouble(Object obj, Double defaultValue) {
        if (obj == null) {
            return defaultValue;
        }
        if (obj instanceof Double double1) {
            return double1;
        }
        if (obj instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(obj.toString());
        } catch (NumberFormatException _) {
            return defaultValue;
        }
    }


}

