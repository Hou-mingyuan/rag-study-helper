package com.rag.studyhelper.support;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;

public final class MybatisMetadata {

    private MybatisMetadata() {
    }

    public static void initialize(Class<?>... entityTypes) {
        for (Class<?> entityType : entityTypes) {
            MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                    new MybatisConfiguration(), entityType.getName());
            assistant.setCurrentNamespace(entityType.getName());
            TableInfoHelper.initTableInfo(assistant, entityType);
        }
    }
}
