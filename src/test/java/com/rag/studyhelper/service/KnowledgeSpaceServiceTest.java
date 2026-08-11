package com.rag.studyhelper.service;

import com.rag.studyhelper.mapper.DocumentsMapper;
import com.rag.studyhelper.mapper.KnowledgeSpaceMapper;
import com.rag.studyhelper.model.Documents;
import com.rag.studyhelper.model.KnowledgeSpace;
import com.rag.studyhelper.model.KnowledgeSpaceRequest;
import com.rag.studyhelper.support.MybatisMetadata;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeSpaceServiceTest {

    @Mock
    private KnowledgeSpaceMapper spaces;
    @Mock
    private DocumentsMapper documents;

    private KnowledgeSpaceService service;

    @BeforeAll
    static void metadata() {
        MybatisMetadata.initialize(KnowledgeSpace.class, Documents.class);
    }

    @BeforeEach
    void setUp() {
        service = new KnowledgeSpaceService(spaces, documents);
    }

    @Test
    void listsAndRequiresOnlyActiveSpaces() {
        KnowledgeSpace active = space(2L, "课程资料", "ACTIVE");
        when(spaces.selectList(any())).thenReturn(List.of(active));
        when(spaces.selectById(2L)).thenReturn(active);
        when(spaces.selectById(3L)).thenReturn(space(3L, "归档", "DELETED"));

        assertEquals(List.of(active), service.list());
        assertEquals(active, service.requireActive(2L));
        assertThrows(IllegalArgumentException.class, () -> service.requireActive(3L));
        assertThrows(IllegalArgumentException.class, () -> service.requireActive(404L));
    }

    @Test
    void createsTrimmedSpaceAndRejectsDuplicateName() {
        when(spaces.selectCount(any())).thenReturn(0L);
        KnowledgeSpace created = service.create(new KnowledgeSpaceRequest("  数据库  ", "  学习资料  "));

        assertEquals("数据库", created.getName());
        assertEquals("学习资料", created.getDescription());
        assertEquals("ACTIVE", created.getStatus());
        verify(spaces).insert(created);

        when(spaces.selectCount(any())).thenReturn(1L);
        assertThrows(IllegalStateException.class,
                () -> service.create(new KnowledgeSpaceRequest("数据库", null)));
    }

    @Test
    void updatesDescriptionAndRejectsNameCollision() {
        KnowledgeSpace current = space(8L, "旧名称", "ACTIVE");
        when(spaces.selectById(8L)).thenReturn(current);
        when(spaces.selectCount(any())).thenReturn(0L);

        KnowledgeSpace updated = service.update(8L,
                new KnowledgeSpaceRequest(" 新名称 ", null));
        assertEquals("新名称", updated.getName());
        assertEquals("", updated.getDescription());
        verify(spaces).updateById(current);

        when(spaces.selectCount(any())).thenReturn(2L);
        assertThrows(IllegalStateException.class,
                () -> service.update(8L, new KnowledgeSpaceRequest("重复", "x")));
    }

    @Test
    void deletionProtectsDefaultAndNonEmptySpaces() {
        assertThrows(IllegalStateException.class, () -> service.delete(1L));
        verify(spaces, never()).updateById(any(KnowledgeSpace.class));

        KnowledgeSpace active = space(9L, "待删除", "ACTIVE");
        when(spaces.selectById(9L)).thenReturn(active);
        when(documents.selectCount(any())).thenReturn(1L);
        assertThrows(IllegalStateException.class, () -> service.delete(9L));

        when(documents.selectCount(any())).thenReturn(0L);
        service.delete(9L);
        assertEquals("DELETED", active.getStatus());
        verify(spaces).updateById(active);
    }

    private KnowledgeSpace space(long id, String name, String status) {
        KnowledgeSpace value = new KnowledgeSpace();
        value.setId(id);
        value.setName(name);
        value.setStatus(status);
        return value;
    }
}
