package com.rag.studyhelper.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

class IngestionJobSerializationTest {

    @Test
    void internalPayloadPathIsNeverReturnedByTheApi() throws Exception {
        IngestionJob job = new IngestionJob();
        job.setId(1L);
        job.setPayloadPath("D:\\private\\inbox\\upload.payload");

        String json = new ObjectMapper().writeValueAsString(job);

        assertFalse(json.contains("payloadPath"));
        assertFalse(json.contains("private"));
    }
}
