package com.rag.studyhelper.feishu.client;

import java.io.IOException;

public interface FeishuRemoteGateway {

    FeishuEnumeration enumerate(String remoteSpaceId) throws IOException;

    String readContent(WikiNode node) throws IOException;
}
