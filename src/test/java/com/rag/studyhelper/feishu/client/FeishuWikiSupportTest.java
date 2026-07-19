package com.rag.studyhelper.feishu.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeishuWikiSupportTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parseWikiItems_mapsFeishuFields() throws Exception {
        String json = "{\n"
                + "  \"items\": [\n"
                + "    {\n"
                + "      \"node_token\": \"wikcnA\",\n"
                + "      \"obj_token\": \"doccnB\",\n"
                + "      \"obj_type\": \"docx\",\n"
                + "      \"title\": \"合规制度\",\n"
                + "      \"has_child\": true,\n"
                + "      \"obj_edit_time\": \"1718000000\"\n"
                + "    }\n"
                + "  ]\n"
                + "}";
        List<WikiNode> nodes = FeishuWikiSupport.parseWikiItems(
                mapper.readTree(json).get("items"),
                "parent-1");

        assertEquals(1, nodes.size());
        WikiNode node = nodes.get(0);
        assertEquals("wikcnA", node.getNodeToken());
        assertEquals("doccnB", node.getObjToken());
        assertEquals("docx", node.getObjType());
        assertEquals("合规制度", node.getNodeTitle());
        assertEquals("parent-1", node.getParentNodeToken());
        assertTrue(node.isHasChild());
        assertEquals(1718000000L, node.getUpdateTime());
    }

    @Test
    void shouldSkipSync_whenUpdateTimeMatches() {
        assertTrue(FeishuWikiSupport.shouldSkipSync(1718000000L, 1718000000L));
    }

    @Test
    void shouldNotSkipSync_whenLocalMissingOrStale() {
        assertFalse(FeishuWikiSupport.shouldSkipSync(null, 1718000000L));
        assertFalse(FeishuWikiSupport.shouldSkipSync(1718000000L, 1718000001L));
    }
}
