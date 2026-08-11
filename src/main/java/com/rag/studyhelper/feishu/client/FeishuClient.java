package com.rag.studyhelper.feishu.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rag.studyhelper.feishu.config.FeishuProperties;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public class FeishuClient implements FeishuRemoteGateway {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final String appId;
    private final String appSecret;
    private final HttpUrl baseUrl;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final int maxRetries;
    private final long initialBackoffMillis;
    private final long minRequestIntervalMillis;
    private final int pageSize;
    private final int maxPages;
    private final AtomicInteger retryCounter = new AtomicInteger();

    private volatile String cachedToken;
    private volatile long tokenExpireAt;
    private long lastRequestAt;

    public FeishuClient(String appId, String appSecret, String baseUrl, OkHttpClient httpClient) {
        this(appId, appSecret, baseUrl, httpClient, defaults());
    }

    public FeishuClient(String appId, String appSecret, String baseUrl,
                        OkHttpClient httpClient, FeishuProperties properties) {
        if (appId == null || appId.isBlank() || appSecret == null || appSecret.isBlank()) {
            throw new IllegalArgumentException("Feishu app-id and app-secret are required when sync is enabled");
        }
        this.appId = appId;
        this.appSecret = appSecret;
        this.baseUrl = HttpUrl.get(baseUrl);
        this.httpClient = httpClient;
        this.maxRetries = Math.max(0, properties.getMaxRetries());
        this.initialBackoffMillis = Math.max(0L, properties.getInitialBackoffMillis());
        this.minRequestIntervalMillis = Math.max(0L, properties.getMinRequestIntervalMillis());
        this.pageSize = Math.min(Math.max(properties.getPageSize(), 1), 50);
        this.maxPages = Math.max(properties.getMaxPages(), 1);
    }

    @Override
    public FeishuEnumeration enumerate(String remoteSpaceId) throws IOException {
        if (remoteSpaceId == null || remoteSpaceId.isBlank()) {
            throw new IllegalArgumentException("Feishu remote space-id is required");
        }
        int retriesBefore = retryCounter.get();
        EnumerationState state = new EnumerationState();
        collectNodes(remoteSpaceId, null, state);
        long maxUpdateTime = state.nodes.stream().mapToLong(WikiNode::getUpdateTime).max().orElse(0L);
        return new FeishuEnumeration(List.copyOf(state.nodes), true, state.pages,
                retryCounter.get() - retriesBefore, maxUpdateTime);
    }

    public List<WikiNode> getWikiNodeTree(String remoteSpaceId) throws IOException {
        return enumerate(remoteSpaceId).nodes();
    }

    @Override
    public String readContent(WikiNode node) throws IOException {
        if (node == null) {
            throw new IllegalArgumentException("Feishu node is required");
        }
        return switch (node.getObjType()) {
            case "doc", "docx" -> getDocumentContent(node.getObjToken());
            case "sheet" -> getSheetContent(node.getObjToken());
            case "bitable" -> getBitableContent(node.getObjToken());
            default -> throw new IllegalArgumentException(
                    "Unsupported Feishu node type: " + node.getObjType());
        };
    }

    public synchronized String getAccessToken() throws IOException {
        if (cachedToken != null && System.currentTimeMillis() < tokenExpireAt) {
            return cachedToken;
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("app_id", appId);
        payload.put("app_secret", appSecret);
        Request request = new Request.Builder()
                .url(url("open-apis", "auth", "v3", "tenant_access_token", "internal"))
                .post(RequestBody.create(JSON, objectMapper.writeValueAsBytes(payload)))
                .build();
        JsonNode body = executeJson(request, "get access token");
        String token = body.path("tenant_access_token").asText();
        if (token.isBlank()) {
            throw new IOException("Feishu access-token response did not contain a token");
        }
        int expires = Math.max(body.path("expire").asInt(7200), 120);
        cachedToken = token;
        tokenExpireAt = System.currentTimeMillis() + (expires - 60L) * 1000L;
        return token;
    }

    public List<JsonNode> listSpaces() throws IOException {
        List<JsonNode> spaces = new ArrayList<>();
        String pageToken = null;
        Set<String> seenTokens = new HashSet<>();
        int pages = 0;
        do {
            HttpUrl.Builder url = urlBuilder("open-apis", "wiki", "v2", "spaces")
                    .addQueryParameter("page_size", String.valueOf(pageSize));
            if (pageToken != null) {
                url.addQueryParameter("page_token", pageToken);
            }
            JsonNode data = executeAuthorizedGet(url.build(), "list spaces").path("data");
            data.path("items").forEach(spaces::add);
            pageToken = nextPageToken(data, seenTokens, ++pages, "list spaces");
        } while (pageToken != null);
        return spaces;
    }

    public String getDocumentContent(String documentToken) throws IOException {
        JsonNode body = executeAuthorizedGet(
                url("open-apis", "docx", "v1", "documents", requiredToken(documentToken), "raw_content"),
                "read document content");
        return body.path("data").path("content").asText();
    }

    public String getSheetContent(String spreadsheetToken) throws IOException {
        String token = requiredToken(spreadsheetToken);
        JsonNode meta = executeAuthorizedGet(
                url("open-apis", "sheets", "v2", "spreadsheets", token, "metainfo"),
                "read sheet metadata");
        List<String> ranges = new ArrayList<>();
        for (JsonNode sheet : meta.path("data").path("sheets")) {
            String title = sheet.path("title").asText();
            int rows = Math.max(sheet.path("row_count").asInt(1), 1);
            int columns = Math.max(sheet.path("column_count").asInt(1), 1);
            ranges.add(title + "!A1:" + toExcelColumn(columns) + rows);
        }

        StringBuilder result = new StringBuilder();
        for (String range : ranges) {
            result.append("=== Sheet: ").append(range, 0, range.indexOf('!')).append(" ===\n");
            JsonNode values = executeAuthorizedGet(
                    url("open-apis", "sheets", "v2", "spreadsheets", token, "values", range),
                    "read sheet values").path("data").path("valueRange").path("values");
            for (JsonNode row : values) {
                for (int index = 0; index < row.size(); index++) {
                    result.append(row.get(index).asText());
                    if (index < row.size() - 1) {
                        result.append(" | ");
                    }
                }
                result.append('\n');
            }
            result.append('\n');
        }
        return result.toString();
    }

    public String getBitableContent(String appToken) throws IOException {
        String token = requiredToken(appToken);
        List<JsonNode> tables = listBitableTables(token);
        StringBuilder result = new StringBuilder();
        for (JsonNode table : tables) {
            String tableId = table.path("table_id").asText();
            result.append("=== Bitable: ").append(table.path("name").asText()).append(" ===\n");
            String pageToken = null;
            Set<String> seenTokens = new HashSet<>();
            int pages = 0;
            do {
                HttpUrl.Builder url = urlBuilder("open-apis", "bitable", "v1", "apps", token,
                        "tables", tableId, "records")
                        .addQueryParameter("page_size", String.valueOf(pageSize));
                if (pageToken != null) {
                    url.addQueryParameter("page_token", pageToken);
                }
                JsonNode data = executeAuthorizedGet(url.build(), "read bitable records").path("data");
                for (JsonNode item : data.path("items")) {
                    JsonNode fields = item.path("fields");
                    fields.fieldNames().forEachRemaining(field -> result.append(field)
                            .append(": ").append(fields.get(field)).append('\n'));
                    result.append("---\n");
                }
                pageToken = nextPageToken(data, seenTokens, ++pages, "read bitable records");
            } while (pageToken != null);
            result.append('\n');
        }
        return result.toString();
    }

    private void collectNodes(String remoteSpaceId, String parentNodeToken,
                              EnumerationState state) throws IOException {
        String branchKey = parentNodeToken == null ? "<root>" : parentNodeToken;
        if (!state.visitedBranches.add(branchKey)) {
            throw new IOException("Feishu wiki tree contains a recursive branch");
        }

        List<WikiNode> currentLevel = new ArrayList<>();
        String pageToken = null;
        Set<String> pageTokens = new HashSet<>();
        do {
            HttpUrl.Builder url = urlBuilder("open-apis", "wiki", "v2", "spaces", remoteSpaceId, "nodes");
            if (parentNodeToken != null) {
                url.addPathSegment(parentNodeToken).addPathSegment("children");
            }
            url.addQueryParameter("page_size", String.valueOf(pageSize));
            if (pageToken != null) {
                url.addQueryParameter("page_token", pageToken);
            }
            JsonNode data = executeAuthorizedGet(url.build(), "enumerate wiki nodes").path("data");
            state.pages++;
            if (state.pages > maxPages) {
                throw new IOException("Feishu wiki enumeration exceeded the configured page limit");
            }
            for (WikiNode node : FeishuWikiSupport.parseWikiItems(data.path("items"), parentNodeToken)) {
                if (node.getNodeToken() == null || node.getNodeToken().isBlank()) {
                    throw new IOException("Feishu wiki returned a node without node_token");
                }
                if (state.seenNodes.add(node.getNodeToken())) {
                    state.nodes.add(node);
                    currentLevel.add(node);
                }
            }
            pageToken = nextPageToken(data, pageTokens, state.pages, "enumerate wiki nodes");
        } while (pageToken != null);

        for (WikiNode node : currentLevel) {
            if (node.isHasChild()) {
                collectNodes(remoteSpaceId, node.getNodeToken(), state);
            }
        }
    }

    private List<JsonNode> listBitableTables(String appToken) throws IOException {
        List<JsonNode> tables = new ArrayList<>();
        String pageToken = null;
        Set<String> seenTokens = new HashSet<>();
        int pages = 0;
        do {
            HttpUrl.Builder url = urlBuilder("open-apis", "bitable", "v1", "apps", appToken, "tables")
                    .addQueryParameter("page_size", String.valueOf(pageSize));
            if (pageToken != null) {
                url.addQueryParameter("page_token", pageToken);
            }
            JsonNode data = executeAuthorizedGet(url.build(), "list bitable tables").path("data");
            data.path("items").forEach(tables::add);
            pageToken = nextPageToken(data, seenTokens, ++pages, "list bitable tables");
        } while (pageToken != null);
        return tables;
    }

    private JsonNode executeAuthorizedGet(HttpUrl url, String operation) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + getAccessToken())
                .get()
                .build();
        return executeJson(request, operation);
    }

    private JsonNode executeJson(Request request, String operation) throws IOException {
        IOException lastError = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            throttle();
            try (Response response = httpClient.newCall(request).execute()) {
                int status = response.code();
                if (status == 429 || status >= 500) {
                    lastError = new IOException(operation + " returned HTTP " + status);
                    if (attempt < maxRetries) {
                        retryCounter.incrementAndGet();
                        waitBeforeRetry(attempt, response.header("Retry-After"));
                        continue;
                    }
                    break;
                }
                if (!response.isSuccessful()) {
                    throw new PermanentFeishuException(operation + " returned HTTP " + status);
                }
                if (response.body() == null) {
                    throw new IOException(operation + " returned an empty response");
                }
                JsonNode body = objectMapper.readTree(response.body().byteStream());
                int code = body.path("code").asInt(0);
                if (code != 0) {
                    if (isRetryableBusinessCode(code) && attempt < maxRetries) {
                        lastError = new IOException(operation + " returned retryable code " + code);
                        retryCounter.incrementAndGet();
                        waitBeforeRetry(attempt, null);
                        continue;
                    }
                    throw new PermanentFeishuException(operation + " returned Feishu code " + code);
                }
                return body;
            } catch (PermanentFeishuException permanentError) {
                throw permanentError;
            } catch (IOException networkError) {
                lastError = new IOException(operation + " failed: "
                        + networkError.getClass().getSimpleName(), networkError);
                if (attempt >= maxRetries) {
                    break;
                }
                retryCounter.incrementAndGet();
                waitBeforeRetry(attempt, null);
            }
        }
        throw new IOException(operation + " failed after " + (maxRetries + 1) + " attempts", lastError);
    }

    private synchronized void throttle() throws IOException {
        long wait = lastRequestAt + minRequestIntervalMillis - System.currentTimeMillis();
        if (wait > 0) {
            sleep(wait);
        }
        lastRequestAt = System.currentTimeMillis();
    }

    private void waitBeforeRetry(int attempt, String retryAfter) throws IOException {
        long wait = initialBackoffMillis * (1L << Math.min(attempt, 10));
        if (retryAfter != null) {
            try {
                wait = Math.max(wait, Long.parseLong(retryAfter.trim()) * 1000L);
            } catch (NumberFormatException ignored) {
                // Fall back to exponential backoff for invalid Retry-After values.
            }
        }
        sleep(Math.min(wait, 30_000L));
    }

    private void sleep(long millis) throws IOException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Feishu request interrupted", interrupted);
        }
    }

    private String nextPageToken(JsonNode data, Set<String> seenTokens,
                                 int pages, String operation) throws IOException {
        if (pages > maxPages) {
            throw new IOException(operation + " exceeded the configured page limit");
        }
        boolean hasMore = data.path("has_more").asBoolean(false);
        String token = data.path("page_token").asText("").trim();
        if (!hasMore) {
            return null;
        }
        if (token.isEmpty()) {
            throw new IOException(operation + " reported has_more without page_token");
        }
        if (!seenTokens.add(token)) {
            throw new IOException(operation + " returned a repeated page_token");
        }
        return token;
    }

    private HttpUrl url(String... segments) {
        return urlBuilder(segments).build();
    }

    private HttpUrl.Builder urlBuilder(String... segments) {
        HttpUrl.Builder builder = baseUrl.newBuilder();
        for (String segment : segments) {
            builder.addPathSegment(segment);
        }
        return builder;
    }

    private String requiredToken(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Feishu object token is required");
        }
        return token;
    }

    private boolean isRetryableBusinessCode(int code) {
        return code == 99991400 || code == 99991663 || code == 99991664;
    }

    private String toExcelColumn(int column) {
        StringBuilder result = new StringBuilder();
        int current = column;
        while (current > 0) {
            current--;
            result.insert(0, (char) ('A' + current % 26));
            current /= 26;
        }
        return result.toString();
    }

    private static FeishuProperties defaults() {
        return new FeishuProperties();
    }

    private static final class EnumerationState {
        private final List<WikiNode> nodes = new ArrayList<>();
        private final Set<String> seenNodes = new HashSet<>();
        private final Set<String> visitedBranches = new HashSet<>();
        private int pages;
    }

    private static final class PermanentFeishuException extends IOException {
        private PermanentFeishuException(String message) {
            super(message);
        }
    }
}
