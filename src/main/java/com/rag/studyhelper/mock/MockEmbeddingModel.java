package com.rag.studyhelper.mock;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 确定性本地向量：按词哈希映射，便于 Mock 模式下语义检索演示。
 */
public class MockEmbeddingModel implements EmbeddingModel {

    public static final int DIMENSION = 256;

    @Override
    public Response<Embedding> embed(String text) {
        return Response.from(Embedding.from(vectorize(text)));
    }

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
        List<Embedding> list = new ArrayList<>(segments.size());
        for (TextSegment segment : segments) {
            list.add(Embedding.from(vectorize(segment.text())));
        }
        return Response.from(list);
    }

    static float[] vectorize(String text) {
        float[] vector = new float[DIMENSION];
        if (text == null || text.isEmpty()) {
            return vector;
        }
        for (String token : tokenize(text)) {
            int index = Math.floorMod(token.hashCode(), DIMENSION);
            vector[index] += 1f;
        }
        normalize(vector);
        return vector;
    }

    public static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }
        Matcher matcher = Pattern.compile("[\\p{IsHan}]+|[a-z0-9][a-z0-9_+.-]*")
                .matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String part = matcher.group();
            if (part.codePoints().allMatch(MockEmbeddingModel::isHan)) {
                int[] characters = part.codePoints().toArray();
                for (int size = 2; size <= Math.min(3, characters.length); size++) {
                    for (int offset = 0; offset + size <= characters.length; offset++) {
                        tokens.add(new String(characters, offset, size));
                    }
                }
                if (characters.length <= 6 && characters.length >= 2) {
                    tokens.add(part);
                }
            } else if (part.length() >= 2) {
                tokens.add(part);
            }
        }
        return tokens;
    }

    private static boolean isHan(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
    }

    static void normalize(float[] vector) {
        double sum = 0;
        for (float v : vector) {
            sum += v * v;
        }
        if (sum <= 0) {
            return;
        }
        float norm = (float) Math.sqrt(sum);
        for (int i = 0; i < vector.length; i += 1) {
            vector[i] /= norm;
        }
    }
}
