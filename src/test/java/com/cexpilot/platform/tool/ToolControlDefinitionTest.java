package com.cexpilot.platform.tool;

import com.cexpilot.platform.tool.control.ToolControl;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.*;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class ToolControlDefinitionTest {
    private static final String YAML="""
        name: document.publish
        enabled: true
        description: 发布文件
        transport: http
        service_id: documents
        method: POST
        path: /publish
        side_effect: true
        max_attempts: 1
        control:
          kind: OPERATION
          confirmation_required: true
          summary: 发布指定版本文件
          resource_versions: [/version]
          idempotent: true
          expires_after_seconds: 3600
        input_schema:
          type: object
          additionalProperties: false
          required: [version]
          properties:
            version: {type: integer}
        output_schema:
          type: object
          additionalProperties: false
          properties:
            done: {type: boolean}
        """;
    private ToolDefinition load(String source) {
        var resources=new PathMatchingResourcePatternResolver(){
            @Override public Resource[] getResources(String pattern){return new Resource[]{new ByteArrayResource(source.getBytes(StandardCharsets.UTF_8))};}
        };
        return ToolDefinitionLoader.load(resources).get(0);
    }
    @Test void operationDeclarationIncludesConfirmationVersionsAndContractFingerprint() {
        var definition=load(YAML);assertEquals(ToolControl.Kind.OPERATION,definition.control().kind());
        assertTrue(definition.control().confirmationRequired());assertEquals(java.util.List.of("/version"),definition.control().resourceVersions());
        assertNotEquals(definition.contractVersion(),load(YAML.replace("confirmation_required: true","confirmation_required: false")).contractVersion());
    }
    @Test void contradictoryOrImplicitOperationMetadataIsRejected() {
        assertThrows(IllegalStateException.class,()->load(YAML.replace("side_effect: true","side_effect: false")));
        assertThrows(IllegalStateException.class,()->load(YAML.replace("max_attempts: 1","max_attempts: 2")));
        assertThrows(IllegalStateException.class,()->load(YAML.replace("method: POST","method: GET")));
        assertThrows(IllegalStateException.class,()->load(YAML.replace("kind: OPERATION","kind: READ")));
        assertThrows(IllegalStateException.class,()->load(YAML.replace("idempotent: true\n","")));
    }
}
