package me.prexorjustin.prexorcloud.modules.tablist.rest.dto;

import java.util.List;

public record UpsertTemplateRequest(
        String group, List<String> headerLines, List<String> footerLines, int refreshSeconds) {}
