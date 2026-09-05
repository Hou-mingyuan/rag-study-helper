package com.rag.studyhelper.vector;

import java.util.List;

public interface EmbeddingGateway {

    float[] embed(String text);

    List<float[]> embedAll(List<String> texts);

    int dimension();

    String modelName();
}
