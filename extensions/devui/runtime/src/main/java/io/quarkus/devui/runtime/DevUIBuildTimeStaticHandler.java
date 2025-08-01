package io.quarkus.devui.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import jakarta.enterprise.inject.spi.CDI;

import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.RoutingContext;

/**
 * Handler to return the "static" content created a build time
 */
public class DevUIBuildTimeStaticHandler implements Handler<RoutingContext> {

    @Override
    public void handle(RoutingContext event) {

        DevUIBuildTimeStaticService buildTimeStaticService = CDI.current().select(DevUIBuildTimeStaticService.class).get();

        String normalizedPath = event.normalizedPath();
        if (normalizedPath.contains(SLASH)) {
            int si = normalizedPath.lastIndexOf(SLASH) + 1;
            String path = normalizedPath.substring(0, si);
            String fileName = normalizedPath.substring(si);
            // TODO: Handle params ?

            if (path.startsWith(buildTimeStaticService.getBasePath())
                    && buildTimeStaticService.getUrlAndPath().containsKey(fileName)) {
                String pathOnDisk = buildTimeStaticService.getUrlAndPath().get(fileName);

                try {
                    byte[] content = Files.readAllBytes(Path.of(pathOnDisk));
                    event.response()
                            .setStatusCode(STATUS)
                            .setStatusMessage(OK)
                            .putHeader(CONTENT_TYPE, getMimeType(fileName))
                            .end(Buffer.buffer(content));
                } catch (IOException ex) {
                    event.next();
                }
            } else {
                event.next();
            }
        } else {
            event.next();
        }
    }

    private String getMimeType(String fileName) {
        if (fileName.contains(DOT)) {
            // Detect the mimeType from the file extension
            int dotIndex = fileName.lastIndexOf(DOT) + 1;
            String ext = fileName.substring(dotIndex);
            if (!ext.isEmpty()) {
                if (ext.equalsIgnoreCase(FileExtension.HTML) || ext.equalsIgnoreCase(FileExtension.HTM)) {
                    return MimeType.HTML;
                } else if (ext.equalsIgnoreCase(FileExtension.JS)) {
                    return MimeType.JS;
                } else if (ext.equalsIgnoreCase(FileExtension.CSS)) {
                    return MimeType.CSS;
                } else if (ext.equalsIgnoreCase(FileExtension.JSON)) {
                    return MimeType.JSON;
                }
            }
        }
        return MimeType.PLAIN;
    }

    private static final int STATUS = 200;
    private static final String OK = "OK";
    private static final String SLASH = "/";
    private static final String DOT = ".";
    private static final String CONTENT_TYPE = "Content-Type";

    public static interface FileExtension {
        public static final String HTML = "html";
        public static final String HTM = "htm";
        public static final String JS = "js";
        public static final String JSON = "json";
        public static final String CSS = "css";
    }

    public static interface MimeType {
        public static final String HTML = "text/html; charset=utf-8";
        public static final String JS = "text/javascript; charset=utf-8";
        public static final String JSON = "application/json";
        public static final String CSS = "text/css; charset=utf-8";
        public static final String PLAIN = "text/plain; charset=utf-8";
    }
}
