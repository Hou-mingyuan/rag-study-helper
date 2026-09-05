package com.rag.studyhelper.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.rag.studyhelper.mapper.DocumentsMapper;
import com.rag.studyhelper.mapper.KnowledgeSpaceMapper;
import com.rag.studyhelper.model.Documents;
import com.rag.studyhelper.model.KnowledgeSpace;
import com.rag.studyhelper.model.KnowledgeSpaceRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class KnowledgeSpaceService {

    public static final long DEFAULT_SPACE_ID = 1L;

    private final KnowledgeSpaceMapper spaceMapper;
    private final DocumentsMapper documentsMapper;

    public KnowledgeSpaceService(KnowledgeSpaceMapper spaceMapper, DocumentsMapper documentsMapper) {
        this.spaceMapper = spaceMapper;
        this.documentsMapper = documentsMapper;
    }

    public List<KnowledgeSpace> list() {
        return spaceMapper.selectList(Wrappers.<KnowledgeSpace>lambdaQuery()
                .eq(KnowledgeSpace::getStatus, "ACTIVE")
                .orderByAsc(KnowledgeSpace::getId));
    }

    public KnowledgeSpace requireActive(long id) {
        KnowledgeSpace space = spaceMapper.selectById(id);
        if (space == null || !"ACTIVE".equals(space.getStatus())) {
            throw new IllegalArgumentException("Knowledge space does not exist: " + id);
        }
        return space;
    }

    @Transactional
    public KnowledgeSpace create(KnowledgeSpaceRequest request) {
        String name = request.name().trim();
        Long duplicate = spaceMapper.selectCount(Wrappers.<KnowledgeSpace>lambdaQuery()
                .eq(KnowledgeSpace::getName, name));
        if (duplicate != null && duplicate > 0) {
            throw new IllegalStateException("Knowledge space name already exists");
        }
        KnowledgeSpace space = new KnowledgeSpace();
        space.setName(name);
        space.setDescription(request.description() == null ? "" : request.description().trim());
        space.setStatus("ACTIVE");
        spaceMapper.insert(space);
        return space;
    }

    @Transactional
    public KnowledgeSpace update(long id, KnowledgeSpaceRequest request) {
        KnowledgeSpace space = requireActive(id);
        String name = request.name().trim();
        Long duplicate = spaceMapper.selectCount(Wrappers.<KnowledgeSpace>lambdaQuery()
                .eq(KnowledgeSpace::getName, name)
                .ne(KnowledgeSpace::getId, id));
        if (duplicate != null && duplicate > 0) {
            throw new IllegalStateException("Knowledge space name already exists");
        }
        space.setName(name);
        space.setDescription(request.description() == null ? "" : request.description().trim());
        spaceMapper.updateById(space);
        return space;
    }

    @Transactional
    public void delete(long id) {
        if (id == DEFAULT_SPACE_ID) {
            throw new IllegalStateException("The default knowledge space cannot be deleted");
        }
        KnowledgeSpace space = requireActive(id);
        Long documents = documentsMapper.selectCount(Wrappers.<Documents>lambdaQuery()
                .eq(Documents::getSpaceId, id)
                .ne(Documents::getStatus, "DELETED"));
        if (documents != null && documents > 0) {
            throw new IllegalStateException("Delete the documents in this space first");
        }
        // Documents keep version/chunk tombstones for reconciliation and audit, and jobs/runs
        // also retain their foreign-key ownership. Deleting the parent row would either violate
        // those constraints or destroy the consistency trail, so a space is retired by status.
        space.setStatus("DELETED");
        spaceMapper.updateById(space);
    }
}
